import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import KeepApp
import KeepCore
import Testing

@MainActor
struct ScreenModelTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let store: MemoryReferenceStore
    let clipboard = FakeClipboard()

    init() {
        documents.put("vault.kdbx", kdbxHeader)
        documents.put("my.keyx", Data("key".utf8))
        store = MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx")))
    }

    func unlocked() async -> VaultSessionController {
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { SecuritySettings() })
        _ = await controller.unlock(password: Data("pw".utf8))
        return controller
    }

    // MARK: Unlock

    @Test func wrongPasswordClearsTheFieldAndAsksForFocus() async {
        opener.accepts.withLock { $0 = { password, _ in password == "right" } }
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { SecuritySettings() })
        let model = UnlockModel(controller: controller)
        model.password = "wrong"
        await model.unlock()
        #expect(model.password.isEmpty)
        #expect(model.error == .wrongPassword)
        #expect(model.refocusSignal == 1)
        model.password = "right"
        await model.unlock()
        #expect(model.error == nil)
        #expect(controller.isUnlocked)
    }

    @Test func emptyFieldWithoutKeyFileDoesNothing() async {
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { SecuritySettings() })
        let model = UnlockModel(controller: controller)
        #expect(!model.canUnlock)
        await model.unlock()
        #expect(opener.seen.withLock { $0.isEmpty })
        await model.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        #expect(model.keyFileName == "my.keyx")
        #expect(model.canUnlock)
        model.removeKeyFile()
        #expect(model.keyFileName == nil)
    }

    @Test func keyFileErrorsClearWhenTheFileIsReplaced() async {
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { SecuritySettings() })
        let model = UnlockModel(controller: controller)
        await model.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        documents.remove("my.keyx")
        await model.unlock()
        #expect(model.error == .keyFileUnavailable)
        documents.put("again.keyx", Data("key".utf8))
        await model.chooseKeyFile(URL(filePath: "/x/again.keyx"))
        #expect(model.error == nil)
        await model.chooseKeyFile(URL(filePath: "/x/vault.kdbx"))
        #expect(model.keyFileError == .isVault)
    }

    // MARK: Browsing

    @Test func rootListsSubgroupsAndItsOwnEntries() async {
        let controller = await unlocked()
        let root = BrowserModel(controller: controller)
        root.load()
        #expect(root.title == "Fake Vault")
        #expect(root.groups.map(\.name) == ["Web"])
        #expect(root.entries.map(\.displayTitle) == ["Root Entry"])
        let web = BrowserModel(controller: controller, groupID: FakeSession.web)
        web.load()
        #expect(web.title == "Web")
        #expect(web.entries.map(\.id) == [FakeSession.mail])
    }

    @Test func searchUsesTheSessionAndClearsOnEmptyQuery() async {
        let controller = await unlocked()
        let model = BrowserModel(controller: controller)
        model.query = "mail"
        await model.search(now: "mail")
        #expect(model.searchResults?.map(\.id) == [FakeSession.mail])
        model.query = "  "
        #expect(model.searchResults == nil)
    }

    // MARK: Entry

    func entryModel(_ controller: VaultSessionController, sleep: @escaping @Sendable (Duration) async -> Void = { _ in try? await Task.sleep(for: .seconds(3600)) }) -> EntryModel {
        EntryModel(entryID: FakeSession.mail, controller: controller, clipboard: clipboard, clipboardTimeout: { .seconds(30) }, sleep: sleep)
    }

    let plusAccess = KeepAccess(state: .plusActive, plusFeatures: true, needsOnlineVerification: false, leaseValidUntil: nil, observedDeviceTime: 0)

    func totpModel(allowed: Bool = true, now: Int = 1_000_000_000, code: String? = "123456") async -> (EntryModel, VaultSessionController) {
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { SecuritySettings() })
        _ = await controller.unlock(password: Data("pw".utf8))
        opener.sessions.withLock { $0.last?.totpCode.withLock { $0 = code } }
        let model = EntryModel(
            entryID: FakeSession.mail, controller: controller, clipboard: clipboard, clipboardTimeout: { .seconds(30) },
            sleep: { _ in try? await Task.sleep(for: .seconds(3600)) }, access: { allowed ? plusAccess : .free }, now: { now }, autoTick: false
        )
        return (model, controller)
    }

    private func qrImage(_ text: String, invert: Bool = false) -> Data {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        var image = filter.outputImage!.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        if invert { image = image.applyingFilter("CIColorInvert") }
        let context = CIContext()
        return context.pngRepresentation(of: image, format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!)!
    }

    @Test func readsAQrCodeFromAnImage() {
        let link = "otpauth://totp/GitHub:anna?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"
        #expect(QRCodeReader.decode(imageData: qrImage(link)) == link)
    }

    @Test func anImageWithoutACodeGivesNothing() {
        #expect(QRCodeReader.decode(imageData: Data("not an image".utf8)) == nil)
        let blank = CIContext().pngRepresentation(of: CIImage(color: .gray).cropped(to: CGRect(x: 0, y: 0, width: 200, height: 200)), format: .RGBA8, colorSpace: CGColorSpace(name: CGColorSpace.sRGB)!)!
        #expect(QRCodeReader.decode(imageData: blank) == nil)
    }

    @Test func anExportFromAnotherAuthenticatorIsRecognisedAsSuch() async {
        let controller = await unlocked()
        let model = EntryEditorModel(controller: controller, mode: .create(in: FakeSession.root), access: { plusAccess })

        #expect(model.addTotp("otpauth-migration://offline?data=Cg") == .migration)
        #expect(model.fields.isEmpty)
    }

    @Test func aSecretKeyBecomesAProtectedOtpField() async {
        let controller = await unlocked()
        let model = EntryEditorModel(controller: controller, mode: .create(in: FakeSession.root), access: { plusAccess })

        #expect(model.addTotp("GEZDGNBVGY3TQOJQ") == nil)

        #expect(model.fields.count == 1)
        #expect(model.fields[0].name == "otp")
        #expect(model.fields[0].value.hasPrefix("otpauth://totp/"))
        #expect(model.fields[0].isProtected)
    }

    @Test func addingACodeTwiceReplacesTheFieldInsteadOfAddingAnother() async {
        let controller = await unlocked()
        let model = EntryEditorModel(controller: controller, mode: .create(in: FakeSession.root), access: { plusAccess })

        _ = model.addTotp("GEZDGNBVGY3TQOJQ")
        _ = model.addTotp("otpauth://totp/Mail:anna?secret=JBSWY3DPEHPK3PXP&issuer=Mail")

        #expect(model.fields.count == 1)
        #expect(model.fields[0].value.contains("JBSWY3DPEHPK3PXP"))
    }

    @Test func aBadKeyIsRefusedAndLeavesTheFieldsAlone() async {
        let controller = await unlocked()
        let model = EntryEditorModel(controller: controller, mode: .create(in: FakeSession.root), access: { plusAccess })

        #expect(model.addTotp("not a key!") == .invalidSecret)
        #expect(model.addTotp("otpauth://hotp/x?secret=GEZDGNBV&counter=1") == .unsupportedType)
        #expect(model.fields.isEmpty)
    }

    @Test func withoutKeepPlusTheEditorRefusesToAddACode() async {
        let controller = await unlocked()
        let model = EntryEditorModel(controller: controller, mode: .create(in: FakeSession.root))

        #expect(model.addTotp("GEZDGNBVGY3TQOJQ") == .notEntitled)
        #expect(model.fields.isEmpty)
    }

    @Test func anEntryWithoutACodeSetupShowsNoCode() async {
        let (model, _) = await totpModel(code: nil)
        model.load()
        #expect(model.totp == .none)
    }

    @Test func aKeepPlusPersonSeesTheCodeAndTheTimeLeft() async {
        let (model, _) = await totpModel(now: 1_000_000_000)
        model.load()
        guard case let .active(code, remaining, period) = model.totp else { Issue.record("no code"); return }
        #expect(code == "123456")
        #expect(period == 30)
        #expect(remaining == 30 - 1_000_000_000 % 30)
        #expect(!String(describing: model.totp).contains("123456"))
    }

    @Test func withoutKeepPlusTheCodeIsNeverComputed() async {
        let (model, _) = await totpModel(allowed: false)
        model.load()
        #expect(model.totp == .locked)
    }

    @Test func aHiddenScreenShowsNoCodeUntilItIsBack() async {
        let (model, _) = await totpModel()
        model.load()
        model.setTotpVisible(false)
        #expect(model.totp == .waiting)
        model.setTotpVisible(true)
        guard case .active = model.totp else { Issue.record("no code"); return }
    }

    @Test func aLockDropsTheCode() async {
        let (model, controller) = await totpModel()
        model.load()
        controller.lock()
        #expect(model.refreshTotp() == false)
        #expect(model.totp == .none)
    }

    @Test func copyingTheCodeUsesTheUsualClipboardTimer() async {
        let (model, _) = await totpModel()
        model.load()
        let feedback = model.copyTotp()
        #expect(feedback == .copied(clearsAfter: .seconds(30)) || feedback == .copied(clearsAfter: nil))
        #expect(clipboard.items.last?.0 == "123456")
        #expect(clipboard.items.last?.1 == .seconds(30))
    }

    @Test func secretsStayHiddenUntilAskedAndHideAgain() async {
        let controller = await unlocked()
        let model = entryModel(controller)
        model.load()
        #expect(model.detail?.summary.displayTitle == "Example Mail")
        #expect(model.revealed == nil)
        model.toggle(.password)
        #expect(model.revealed?.text == "hunter2-synthetic")
        #expect(!String(describing: model.revealed as Any).contains("hunter2"))
        model.toggle(.password)
        #expect(model.revealed == nil)
    }

    @Test func revealedSecretHidesItselfAfterTheTimeout() async {
        let controller = await unlocked()
        let model = entryModel(controller, sleep: { _ in })
        model.reveal(.password)
        while model.revealed != nil { await Task.yield() }
        #expect(model.revealed == nil)
    }

    @Test func nothingIsRevealedAfterALock() async {
        let controller = await unlocked()
        let model = entryModel(controller)
        controller.lock()
        model.reveal(.password)
        #expect(model.revealed == nil)
        #expect(model.copy(.password) == .failed)
    }

    @Test func copyUsesTheConfiguredExpiry() async {
        let controller = await unlocked()
        let model = entryModel(controller)
        #expect(model.copy(.password) == .copied(clearsAfter: .seconds(30)))
        #expect(clipboard.items.first?.0 == "hunter2-synthetic")
        #expect(clipboard.items.first?.1 == .seconds(30))
        clipboard.systemExpires = false
        #expect(model.copy(.password) == .copied(clearsAfter: nil))
    }

    // MARK: Settings

    @Test func settingsDefaultsAndPersistence() {
        let suite = "keep-tests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let fresh = SettingsStore(defaults: defaults)
        #expect(fresh.settings == SecuritySettings())
        #expect(fresh.settings.autoLock == .seconds30 && fresh.settings.clipboard == .seconds30)
        #expect(fresh.settings.lockWhenProtectedDataUnavailable && fresh.settings.theme == .system)
        fresh.setAutoLock(.minute1)
        fresh.setClipboard(.never)
        fresh.setLockWhenProtectedDataUnavailable(false)
        fresh.setTheme(.dark)
        let reloaded = SettingsStore(defaults: defaults)
        #expect(reloaded.settings.autoLock == .minute1)
        #expect(reloaded.settings.clipboard == .never)
        #expect(!reloaded.settings.lockWhenProtectedDataUnavailable)
        #expect(reloaded.settings.theme == .dark)
        defaults.set("bogus", forKey: "security.autoLock")
        #expect(SettingsStore(defaults: defaults).settings.autoLock == .seconds30)
    }

    @Test func timeoutsMatchThePolicy() {
        #expect(AutoLockTimeout.allCases.map(\.duration) == [.zero, .seconds(15), .seconds(30), .seconds(60), .seconds(300), .seconds(900)])
        #expect(ClipboardTimeout.allCases.map(\.duration) == [nil, .seconds(15), .seconds(30), .seconds(60)])
    }

    @Test func referencesPersistOnDiskWithoutContent() throws {
        let directory = FileManager.default.temporaryDirectory.appending(path: UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = FileVaultReferenceStore(directory: directory)
        #expect(files.load() == nil)
        let value = VaultReference(vault: reference("vault.kdbx"), keyFile: reference("my.keyx"))
        files.save(value)
        #expect(FileVaultReferenceStore(directory: directory).load() == value)
        let values = try directory.appending(path: "vault-reference.json").resourceValues(forKeys: [.isExcludedFromBackupKey])
        #expect(values.isExcludedFromBackup == true)
        files.clear()
        #expect(files.load() == nil)
    }
}
