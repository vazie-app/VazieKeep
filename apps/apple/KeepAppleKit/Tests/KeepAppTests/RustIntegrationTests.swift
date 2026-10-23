import CryptoKit
import Foundation
import Synchronization
import KeepApp
import KeepCore
import Testing

/// The real Rust XCFramework behind the application layer, on the synthetic M5 vectors.
@MainActor
struct RustIntegrationTests {
    let documents = FakeDocuments()
    let store = MemoryReferenceStore()
    let clipboard = FakeClipboard()

    init() throws {
        for name in ["vault-password-plus-keyfile.kdbx", "vault-keyfile-only.kdbx", "keyfile-v2.keyx", "keyfile-wrong.keyx"] {
            documents.put(name, try Data(contentsOf: vectorURL("keyfiles/\(name)")))
        }
        documents.put("aes256-argon2d.kdbx", try Data(contentsOf: vectorURL("kdbx4/aes256-argon2d.kdbx")))
    }

    func controller() -> VaultSessionController {
        VaultSessionController(opener: RustVaultOpener(), documents: documents, references: store, settings: { SecuritySettings() })
    }

    @Test func openBrowseRevealAndClose() async throws {
        let controller = controller()
        #expect(await controller.chooseVault(URL(filePath: "/x/aes256-argon2d.kdbx")) == .success(reference("aes256-argon2d.kdbx")))
        let unlock = UnlockModel(controller: controller)
        unlock.password = "wrong"
        await unlock.unlock()
        #expect(unlock.error == .wrongPassword)
        unlock.password = "correct horse battery staple"
        await unlock.unlock()
        guard case let .unlocked(info) = controller.state else { Issue.record("still locked"); return }
        #expect(info.name == "Synthetic Test Vault")
        #expect(info.fingerprint.count == 64)

        let root = BrowserModel(controller: controller)
        root.load()
        #expect(root.groups.map(\.name).contains("Web"))
        let web = try #require(root.groups.first { $0.name == "Web" })
        let webModel = BrowserModel(controller: controller, groupID: web.id)
        webModel.load()
        let mail = try #require(webModel.entries.first { $0.displayTitle == "Example Mail" })

        root.query = "MAIL.example"
        await root.search(now: "MAIL.example")
        #expect(root.searchResults?.map(\.id) == [mail.id])
        root.query = "hunter2"
        await root.search(now: "hunter2")
        #expect(root.searchResults == [])

        let entry = EntryModel(entryID: mail.id, controller: controller, clipboard: clipboard, clipboardTimeout: { .seconds(30) })
        entry.load()
        #expect(entry.detail?.customFields.first { $0.name == "Recovery Code" }?.value == .hidden)
        entry.reveal(.password)
        #expect(entry.revealed?.text == "hunter2-synthetic")
        entry.hide()

        controller.lock()
        #expect(controller.state == .locked)
        #expect(throws: VaultSessionError(reason: .sessionClosed)) { try controller.entry(mail.id) }
    }

    @Test func keyFilesThroughTheRealCore() async {
        let controller = controller()
        _ = await controller.chooseVault(URL(filePath: "/x/vault-password-plus-keyfile.kdbx"))
        #expect(await controller.unlock(password: Data("correct horse battery staple".utf8)) == .failure(.wrongPassword))
        _ = await controller.chooseKeyFile(URL(filePath: "/x/keyfile-wrong.keyx"))
        #expect(await controller.unlock(password: Data("correct horse battery staple".utf8)) == .failure(.wrongPasswordOrKeyFile))
        _ = await controller.chooseKeyFile(URL(filePath: "/x/keyfile-v2.keyx"))
        #expect(await controller.unlock(password: Data("correct horse battery staple".utf8)) == .success)
        controller.lock()

        _ = await controller.chooseVault(URL(filePath: "/x/vault-keyfile-only.kdbx"))
        _ = await controller.chooseKeyFile(URL(filePath: "/x/keyfile-v2.keyx"))
        #expect(await controller.unlock(password: nil) == .success)
        if case let .unlocked(info) = controller.state { #expect(info.name == "Key File Vault") }
        controller.lock()
    }

    @Test func freshControllerAfterARestartIsLocked() async {
        let first = controller()
        _ = await first.chooseVault(URL(filePath: "/x/aes256-argon2d.kdbx"))
        _ = await first.unlock(password: Data("correct horse battery staple".utf8))
        #expect(first.isUnlocked)
        let restarted = controller()
        #expect(restarted.state == .locked)
        #expect(restarted.reference?.vault.displayName == "aes256-argon2d.kdbx")
        first.lock()
    }

    @Test func faceIDUnlockThroughTheRealCore() async throws {
        let controller = controller()
        let keychain = FakeCredentialStore()
        let setups = MemorySetupStore()
        let biometric = BiometricUnlock(controller: controller, authenticator: FakeAuthenticator(), credentials: keychain, setups: setups)
        _ = await controller.chooseVault(URL(filePath: "/x/vault-password-plus-keyfile.kdbx"))
        _ = await controller.chooseKeyFile(URL(filePath: "/x/keyfile-v2.keyx"))
        #expect(await controller.unlock(password: Data("correct horse battery staple".utf8)) == .success)
        let fingerprint = try #require(controller.state.info?.fingerprint)
        #expect(await biometric.enable(reason: "Turn on") == .enabled)
        let setup = try #require(setups.load())
        let material = try #require(keychain.material(setup.id))
        #expect(material.count == 33 && material.first == 1)
        #expect(material != Data("correct horse battery staple".utf8))
        #expect(setup.keyFileRequired && setup.fingerprint == fingerprint)
        controller.lock()

        #expect(await biometric.unlock(reason: "Unlock") == .success)
        #expect(controller.state.info?.fingerprint == fingerprint)
        controller.lock()

        // The material alone is not enough for a key-file vault.
        _ = await controller.chooseKeyFile(URL(filePath: "/x/keyfile-wrong.keyx"))
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(controller.state == .locked)
    }

    @Test func aReplacedVaultFileInvalidatesTheSetup() async throws {
        let controller = controller()
        let keychain = FakeCredentialStore()
        let setups = MemorySetupStore()
        let biometric = BiometricUnlock(controller: controller, authenticator: FakeAuthenticator(), credentials: keychain, setups: setups)
        _ = await controller.chooseVault(URL(filePath: "/x/aes256-argon2d.kdbx"))
        _ = await controller.unlock(password: Data("correct horse battery staple".utf8))
        #expect(await biometric.enable(reason: "Turn on") == .enabled)
        controller.lock()
        // Same location, another database. The synthetic vectors share one root-group UUID, so a
        // fingerprint mismatch is covered by BiometricUnlockTests; here the password differs.
        documents.put("aes256-argon2d.kdbx", try Data(contentsOf: vectorURL("kdbx4/unicode-password.kdbx")))
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(controller.state == .locked)
    }
}

@MainActor
struct AutoFillIntegrationTests {
    @Test func syntheticVaultToFilledCredential() async throws {
        let documents = FakeDocuments()
        documents.put("aes256-argon2d.kdbx", try Data(contentsOf: vectorURL("kdbx4/aes256-argon2d.kdbx")))
        let controller = VaultSessionController(opener: RustVaultOpener(), documents: documents, references: MemoryReferenceStore(VaultReference(vault: reference("aes256-argon2d.kdbx"))), settings: { SecuritySettings() })
        let biometric = BiometricUnlock(controller: controller, authenticator: FakeAuthenticator(), credentials: FakeCredentialStore(), setups: MemorySetupStore())
        let registry = FakeRegistry()
        let indexer = AutoFillIndexer(controller: controller, registry: registry)
        controller.onUnlock = { indexer.vaultUnlocked() }

        #expect(await controller.unlock(password: Data("correct horse battery staple".utf8)) == .success)
        await indexer.settle()
        let identity = try #require(registry.stored.withLock { $0.first { $0.host == "mail.example.org" } })
        #expect(identity.user == "alice@example.org")
        controller.lock()

        let completion = FakeAutoFillCompletion()
        let request = try #require(ServiceOrigin(serviceIdentifier: "https://login.mail.example.org/", isDomain: false))
        let flow = AutoFillFlow(request: .credential(recordIdentifier: identity.recordIdentifier, service: request), controller: controller, biometric: biometric, completion: completion)
        #expect(await controller.unlock(password: Data("correct horse battery staple".utf8)) == .success)
        flow.vaultUnlocked()
        #expect(completion.filled.first?.user == "alice@example.org")
        #expect(completion.filled.first?.password == "hunter2-synthetic")
        #expect(controller.state == .locked)

        let parent = AutoFillFlow(request: .list([try #require(ServiceOrigin(serviceIdentifier: "example.org", isDomain: true))]), controller: controller, biometric: biometric, completion: completion)
        _ = await controller.unlock(password: Data("correct horse battery staple".utf8))
        parent.vaultUnlocked()
        #expect(parent.suggested.isEmpty, "an entry for mail.example.org is not offered on example.org")
        parent.cancel()
    }
}

@MainActor
struct PasskeyIntegrationTests {
    let documents = FakeDocuments()
    let password = Data("correct horse battery staple".utf8)

    init() throws {
        documents.put("rich.kdbx", try Data(contentsOf: vectorURL("editable/rich-aes-argon2d-gzip.kdbx")))
    }

    private func parts() -> (VaultSessionController, BiometricUnlock) {
        let controller = VaultSessionController(opener: RustVaultOpener(), documents: documents, references: MemoryReferenceStore(VaultReference(vault: reference("rich.kdbx"))), copies: MemoryCopyStore(), settings: { SecuritySettings() })
        return (controller, BiometricUnlock(controller: controller, authenticator: FakeAuthenticator(), credentials: FakeCredentialStore(), setups: MemorySetupStore()))
    }

    /// The real core on a real vault file: iOS asks to keep a passkey, the file gets it, and after the file is
    /// opened again the passkey signs a request that the public key in the attestation verifies.
    @Test func aPasskeyIsMadeKeptInTheFileOfferedToIOSAndSignsIn() async throws {
        let (controller, biometric) = parts()
        let completion = FakeAutoFillCompletion()
        let hash = Data(SHA256.hash(data: Data("registration".utf8)))
        let asked = PasskeyRegistrationQuery(rpID: "shop.example.com", userName: "dana", userHandle: Data("user-42".utf8), clientDataHash: hash)
        let before = try #require(documents.data("rich.kdbx"))
        let making = AutoFillFlow(request: .passkeyRegistration(asked), controller: controller, biometric: biometric, completion: completion)
        #expect(making.isRegistering)
        #expect(await controller.unlock(password: password) == .success)
        making.vaultUnlocked()
        for _ in 0..<200 where completion.registrations.isEmpty { try await Task.sleep(for: .milliseconds(20)) }
        let made = try #require(completion.registrations.first?.0)
        #expect(making.phase == .done)
        #expect(controller.state == .locked)
        #expect(documents.data("rich.kdbx") != before, "the site gets the passkey only after the file holds it")

        let registry = FakeRegistry()
        let indexer = AutoFillIndexer(controller: controller, registry: registry)
        #expect(await controller.unlock(password: password) == .success)
        indexer.vaultUnlocked()
        await indexer.settle()
        let offered = try #require(registry.storedPasskeys.withLock { $0.first })
        #expect(offered.rpID == "shop.example.com" && offered.userName == "dana")
        #expect(offered.credentialID == made.credentialID)
        #expect(offered.userHandle == Data("user-42".utf8))
        let entry = try #require(try controller.entries().first { $0.id == made.entryID })
        #expect(entry.passkey?.rpID == "shop.example.com")
        #expect(try controller.entry(made.entryID).customFields.isEmpty, "the passkey is not shown as custom fields")
        controller.lock()

        let login = Data(SHA256.hash(data: Data("sign in".utf8)))
        let query = PasskeyQuery(rpID: "shop.example.com", clientDataHash: login, allowedCredentials: [made.credentialID])
        let using = AutoFillFlow(request: .passkey(recordIdentifier: offered.recordIdentifier, query), controller: controller, biometric: biometric, completion: completion)
        #expect(await controller.unlock(password: password) == .success)
        using.vaultUnlocked()
        let assertion = try #require(completion.assertions.first?.0)
        #expect(controller.state == .locked)
        #expect(assertion.credentialID == made.credentialID)
        #expect(assertion.userHandle == Data("user-42".utf8))
        // The COSE key ends the attestation object: ... 0x21 0x58 0x20 <x> 0x22 0x58 0x20 <y>.
        let object = made.attestationObject
        let key = try P256.Signing.PublicKey(rawRepresentation: object.suffix(67).prefix(32) + object.suffix(32))
        let signature = try P256.Signing.ECDSASignature(derRepresentation: assertion.signature)
        #expect(key.isValidSignature(signature, for: assertion.authenticatorData + login), "the site's public key verifies the sign-in")

        let elsewhere = AutoFillFlow(request: .passkeyList([], PasskeyQuery(rpID: "example.com", clientDataHash: login)), controller: controller, biometric: biometric, completion: completion)
        #expect(await controller.unlock(password: password) == .success)
        elsewhere.vaultUnlocked()
        #expect(elsewhere.passkeys.isEmpty, "a parent domain is another site")
        elsewhere.cancel()

        let listed = AutoFillFlow(request: .passkeyList([], PasskeyQuery(rpID: "shop.example.com", clientDataHash: login)), controller: controller, biometric: biometric, completion: completion)
        #expect(await controller.unlock(password: password) == .success)
        listed.vaultUnlocked()
        #expect(listed.passkeys.map(\.id) == [made.entryID])
        listed.usePasskey(made.entryID)
        #expect(completion.assertions.count == 2)
    }

    @Test func aSiteThatWantsAnotherKindOfKeyGetsNothingAndTheFileStays() async throws {
        let (controller, biometric) = parts()
        let completion = FakeAutoFillCompletion()
        let before = documents.data("rich.kdbx")
        let asked = PasskeyRegistrationQuery(rpID: "shop.example.com", userName: "dana", userHandle: Data([1]), clientDataHash: Data(count: 32), acceptsES256: false)
        let flow = AutoFillFlow(request: .passkeyRegistration(asked), controller: controller, biometric: biometric, completion: completion)
        #expect(await controller.unlock(password: password) == .success)
        flow.vaultUnlocked()
        #expect(flow.phase == .failed(.passkeyUnsupported))
        #expect(completion.registrations.isEmpty)
        #expect(documents.data("rich.kdbx") == before)
        #expect(controller.state == .locked)
    }
}

@MainActor
struct EditingIntegrationTests {
    let documents = FakeDocuments()
    let copies = MemoryCopyStore()
    let password = Data("correct horse battery staple".utf8)

    init() throws {
        documents.put("rich.kdbx", try Data(contentsOf: vectorURL("editable/rich-aes-argon2d-gzip.kdbx")))
        for name in ["vault-password-plus-keyfile.kdbx", "keyfile-v2.keyx"] {
            documents.put(name, try Data(contentsOf: vectorURL("keyfiles/\(name)")))
        }
    }

    private func controller(_ vault: String, keyFile: String? = nil) -> VaultSessionController {
        VaultSessionController(opener: RustVaultOpener(), documents: documents, references: MemoryReferenceStore(VaultReference(vault: reference(vault), keyFile: keyFile.map(reference))), copies: copies, settings: { SecuritySettings() })
    }

    @Test func aSavedOtpFieldGivesCodesThroughTheRealCore() async throws {
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        let web = try #require(try controller.groups().first { $0.name == "Web" }).id
        let uri = "otpauth://totp/Example:anna?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&digits=8"
        let draft = EntryDraft(title: "With Code", username: "anna", password: Data("pw".utf8), customFields: [CustomFieldDraft(name: "otp", value: Data(uri.utf8), isProtected: true)])
        guard case let .saved(id) = await controller.edit({ s throws(VaultEditError) in try s.createEntry(in: web, draft) }) else { Issue.record("create"); return }

        let summary = try #require(try controller.entries().first { $0.id == id })
        #expect(summary.hasTotp)
        let plus = KeepAccess(state: .plusActive, plusFeatures: true, needsOnlineVerification: false, leaseValidUntil: nil, observedDeviceTime: 0)
        #expect(throws: TotpError.self) { try controller.totpCode(for: id, at: 59, access: .free) }
        let code = try controller.totpCode(for: id, at: 59, access: plus)
        defer { code.code.wipe() }
        #expect(try code.code.withString { $0 } == "94287082")
        #expect(code.period == 30)
        #expect(code.secondsRemaining == 1)
    }

    @Test func editSaveLockReopenThroughTheRealCore() async throws {
        let controller = controller("rich.kdbx")
        let biometric = BiometricUnlock(controller: controller, authenticator: FakeAuthenticator(), credentials: FakeCredentialStore(), setups: MemorySetupStore())
        let registry = FakeRegistry()
        let indexer = AutoFillIndexer(controller: controller, registry: registry)
        controller.onSaved = { indexer.vaultUnlocked() }
        #expect(await controller.unlock(password: password) == .success)
        #expect(await biometric.enable(reason: "on") == .enabled)
        let fingerprint = try #require(controller.state.info?.fingerprint)
        let original = try #require(documents.data("rich.kdbx"))

        let web = try #require(try controller.groups().first { $0.name == "Web" }).id
        let draft = EntryDraft(title: "Created On iPhone", username: "phone-user", password: Data("Gen-3rated!".utf8), url: "https://phone.example.com", notes: "", customFields: [CustomFieldDraft(name: "PIN", value: Data("1234".utf8), isProtected: true)], tags: ["phone"])
        guard case let .saved(created) = await controller.edit({ s throws(VaultEditError) in try s.createEntry(in: web, draft) }) else { Issue.record("create"); return }
        let mail = try #require(try controller.entries().first { $0.displayTitle == "Example Mail" }).id
        var edit = EntryDraft(title: "Example Mail", username: "alice@new.example.org", password: Data("hunter3".utf8), url: "https://mail.example.org/login")
        guard case .saved = await controller.edit({ s throws(VaultEditError) in try s.updateEntry(mail, edit) }) else { Issue.record("update"); return }
        edit.wipe()
        await indexer.settle()
        #expect(registry.stored.withLock { $0.contains { $0.user == "alice@new.example.org" } })
        #expect(registry.stored.withLock { $0.contains { $0.host == "phone.example.com" } })
        let old = try #require(try controller.entries().first { $0.displayTitle == "Old Account" }).id
        guard case .saved(.deleted) = await controller.edit({ s throws(VaultEditError) in try s.deleteEntry(old, permanent: false) }) else { Issue.record("delete from the bin"); return }

        #expect(documents.data("rich.kdbx") != original)
        #expect(copies.backup(fingerprint: fingerprint) != nil)
        controller.lock()

        #expect(await biometric.unlock(reason: "again") == .success, "Face ID still opens the saved vault")
        #expect(controller.state.info?.fingerprint == fingerprint)
        #expect(try controller.entry(created).summary.username == .plain("phone-user"))
        #expect(try controller.reveal(.custom("PIN"), for: created).withString { $0 } == "1234")
        #expect(try controller.reveal(.password, for: mail).withString { $0 } == "hunter3")
        #expect(try controller.entries().contains { $0.id == old } == false)
        #expect(try controller.entry(mail).summary.username == .plain("alice@new.example.org"))
    }

    @Test func aKeyFileVaultSavesAndNeedsTheKeyFileAgain() async throws {
        let controller = controller("vault-password-plus-keyfile.kdbx", keyFile: "keyfile-v2.keyx")
        #expect(await controller.unlock(password: password) == .success)
        let root = try #require(controller.state.info?.rootGroupID)
        guard case .saved = await controller.edit({ s throws(VaultEditError) in try s.createGroup(in: root, name: "Saved With Key File") }) else { Issue.record("group"); return }
        controller.lock()
        #expect(await controller.unlock(password: password) == .success)
        #expect(try controller.groups().contains { $0.name == "Saved With Key File" })
        controller.lock()
        controller.removeKeyFile()
        #expect(await controller.unlock(password: password) == .failure(.wrongPassword))
    }

    @Test func favouritesTagsAndExpiryThroughTheRealCore() async throws {
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        let mail = try #require(try controller.entries().first { $0.displayTitle == "Example Mail" }).id
        guard case .saved = await VaultActions(controller: controller).setFavorite(mail, true) else { Issue.record("favourite"); return }
        let web = try #require(try controller.groups().first { $0.name == "Web" }).id
        let draft = EntryDraft(title: "Expired", username: "e", password: Data("pw".utf8), tags: ["old", "mail"], expiresAt: 1_000)
        guard case let .saved(expired) = await controller.edit({ s throws(VaultEditError) in try s.createEntry(in: web, draft) }) else { Issue.record("create"); return }

        let browser = BrowserModel(controller: controller)
        browser.load()
        #expect(browser.filters.map(\.filter).prefix(3) == [.all, .favorites, .expired])
        #expect(browser.filters.contains(FilterChoice(filter: .tag("mail"), count: 2)))
        browser.select(.favorites)
        #expect(browser.entries.map(\.id) == [mail])
        #expect(browser.groups.isEmpty)
        browser.select(.expired)
        #expect(browser.entries.map(\.id) == [expired])

        let editor = EntryEditorModel(controller: controller, mode: .edit(mail))
        editor.load()
        #expect(editor.favorite)
        #expect(editor.tags == ["mail", "personal"])
        #expect(editor.knownTags.contains("old"))
        editor.addTag("new, mail")
        editor.tagInput = "typed"
        #expect(editor.tags == ["mail", "personal", "new"])
        editor.removeTag("personal")
        editor.expiresAt = 2_000_000_000
        #expect(await editor.save() == .saved)
        let saved = try controller.entry(mail).summary
        #expect(saved.favorite)
        #expect(saved.tags == ["mail", "new", "typed"])
        #expect(saved.expiresAt == 2_000_000_000)
    }

    @Test func attachmentsThroughTheRealCore() async throws {
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        let mail = try #require(try controller.entries().first { $0.displayTitle == "Example Mail" }).id
        let plus = KeepAccess(state: .plusActive, plusFeatures: true, needsOnlineVerification: false, leaseValidUntil: nil, observedDeviceTime: 0)

        let free = EntryEditorModel(controller: controller, mode: .edit(mail))
        free.load()
        free.addAttachment(name: "scan.pdf", data: Data("%PDF".utf8))
        #expect(await free.save() == .invalid(.notEntitled))

        let editor = EntryEditorModel(controller: controller, mode: .edit(mail), access: { plus })
        editor.load()
        #expect(editor.addAttachment(name: "scan.pdf", data: Data("%PDF".utf8)))
        #expect(!editor.addAttachment(name: "huge.bin", data: Data(count: Int(maxAttachmentBytes) + 1)))
        #expect(await editor.save() == .saved)
        #expect(try controller.entry(mail).attachments == [AttachmentInfo(name: "scan.pdf", size: 4)])

        let entry = EntryModel(entryID: mail, controller: controller, clipboard: FakeClipboard(), clipboardTimeout: { nil })
        #expect(entry.attachmentData("scan.pdf") == Data("%PDF".utf8))

        let remover = EntryEditorModel(controller: controller, mode: .edit(mail))
        remover.load()
        remover.removeAttachment(try #require(remover.attachments.first))
        #expect(await remover.save() == .saved)
        #expect(try controller.entry(mail).attachments.isEmpty)
    }

    @Test func historyThroughTheRealCore() async throws {
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        let bank = try #require(try controller.entries().first { $0.displayTitle == "Bank" }).id
        let plus = KeepAccess(state: .plusActive, plusFeatures: true, needsOnlineVerification: false, leaseValidUntil: nil, observedDeviceTime: 0)
        #expect(try controller.entry(bank).historyCount == 2)

        let free = HistoryModel(entryID: bank, controller: controller, access: { .free })
        free.load()
        #expect(free.phase == .needsPlus)

        let list = HistoryModel(entryID: bank, controller: controller, access: { plus })
        list.load()
        #expect(list.versions.count == 2)
        let oldest = try #require(list.versions.last)
        #expect(oldest.differs.contains(.password))

        let current = try controller.reveal(.password, for: bank).withString { $0 }
        let version = HistoryVersionModel(entryID: bank, index: oldest.index, controller: controller, clipboard: FakeClipboard(), clipboardTimeout: { nil }, access: { plus })
        version.load()
        version.reveal(.password)
        let earlier = try #require(version.revealed?.text)
        #expect(earlier != current)

        #expect(await version.restore() == .restored)
        #expect(try controller.reveal(.password, for: bank).withString { $0 } == earlier)
        #expect(try controller.entry(bank).historyCount == 3)
        list.load()
        #expect(list.versions.count == 3)
    }

    @Test func siteIconsThroughTheRealCore() async throws {
        struct OneSite: SiteIconSource {
            let png: Data
            func fetch(host: String) async -> Data? { host == "bank.example.com" ? png : nil }
        }
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        #expect(controller.siteIconHosts().contains("bank.example.com"))
        let pixel = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
        let png = try #require(iconPNG(pixel))
        let outcome = await SiteIconPass(controller: controller, source: OneSite(png: png)).run()
        #expect(outcome == .assigned(1))

        controller.lock()
        #expect(await controller.unlock(password: password) == .success)
        let bank = try #require(try controller.entries().first { $0.displayTitle == "Bank" })
        let image = try #require(bank.customIcon)
        #expect(controller.customIcon(image) == png)
        #expect(!controller.siteIconHosts().contains("bank.example.com"))
    }

    @Test func aSavedEntryGetsItsSiteIconThroughTheRealCore() async throws {
        final class AnySite: SiteIconSource {
            let png: Data
            let asked = Mutex<[String]>([])
            init(png: Data) { self.png = png }
            func fetch(host: String) async -> Data? {
                asked.withLock { $0.append(host) }
                return png
            }
        }
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        let pixel = try #require(Data(base64Encoded: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="))
        let source = AnySite(png: try #require(iconPNG(pixel)))
        let group = try #require(try controller.entries().first { $0.displayTitle == "Bank" }).groupID
        let editor = EntryEditorModel(controller: controller, mode: .create(in: group))
        editor.title = "Почта"
        editor.url = "https://почта.рф/login"
        #expect(await editor.save() == .saved)
        let id = try #require(editor.savedEntry)
        #expect(try controller.entry(id).summary.icon != 0, "a built-in icon at once, offline")
        await SiteIconUpdater(controller: controller, source: source, enabled: { true }).entrySaved(id)?.value
        #expect(source.asked.withLock { $0 } == ["xn--80a1acny.xn--p1ai"])
        #expect(try controller.entry(id).summary.customIcon != nil)
    }

    @Test func iconsByAddressThroughTheRealCore() async throws {
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        let actions = VaultActions(controller: controller)
        let bank = try #require(try controller.entries().first { $0.displayTitle == "Bank" })
        #expect(bank.icon == 0)
        guard case let .saved(changed) = await actions.assignIconsByURL() else {
            Issue.record("the pass did not save")
            return
        }
        #expect(changed >= 2)
        #expect(try controller.entry(bank.id).summary.icon == ItemIcon.bank.rawValue)
        #expect(ItemIcon.forEntry(try controller.entries().first { $0.displayTitle == "Old Account" }!.icon) == nil, "no address, no icon")
        guard case let .saved(again) = await actions.assignIconsByURL() else { return }
        #expect(again == 0)

        guard case .saved = await actions.setGroupIcon(try controller.groups().first { $0.name == "Web" }!.id, ItemIcon.mail.rawValue) else {
            Issue.record("the group icon did not save")
            return
        }
        controller.lock()
        #expect(await controller.unlock(password: password) == .success)
        #expect(try controller.groups().first { $0.name == "Web" }?.icon == ItemIcon.mail.rawValue)
        #expect(try controller.entry(bank.id).summary.icon == ItemIcon.bank.rawValue)
    }

    @Test func importThroughTheRealCore() async throws {
        let controller = controller("rich.kdbx")
        #expect(await controller.unlock(password: password) == .success)
        let before = try controller.entries().count
        let export = Data(#"""
        {"encrypted": false, "folders": [{"id": "f", "name": "Imported/Servers"}], "items": [
          {"type": 1, "name": "Import Server", "favorite": true, "folderId": "f",
           "login": {"username": "root", "password": "made-up-password", "totp": "JBSWY3DPEHPK3PXP", "uris": [{"uri": "https://server.example"}]}},
          {"type": 2, "name": "Import Note", "notes": "nothing secret"}
        ]}
        """#.utf8)

        let model = ImportModel(controller: controller)
        await model.read(export)
        #expect(model.phase == .preview(ImportSummary(source: .bitwardenJSON, entries: 2, folders: 1, withTotp: 1, skipped: 0)))
        await model.confirm()
        #expect(model.phase == .done(ImportOutcome(entries: 2, groups: 2)))
        #expect(try controller.entries().count == before + 2)

        controller.lock()
        #expect(await controller.unlock(password: password) == .success)
        let server = try #require(try controller.entries().first { $0.displayTitle == "Import Server" })
        #expect(server.favorite && server.hasTotp)
        #expect(try controller.reveal(.password, for: server.id).withString { $0 } == "made-up-password")

        #expect(server.icon == ItemIcon.server.rawValue || server.icon == ItemIcon.web.rawValue, "an imported entry gets an icon by its address")

        let refused = ImportModel(controller: controller)
        await refused.read(Data(#"{"encrypted": true, "items": []}"#.utf8))
        #expect(refused.phase == .failed(.encrypted))
    }
}

extension VaultState {
    var info: VaultInfo? {
        if case let .unlocked(info) = self { return info }
        return nil
    }
}
