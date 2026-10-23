import Foundation
import KeepApp
import KeepCore
import Testing

private func entry(_ id: String, _ title: String, user: String = "alice", url: String, password: Bool = true) -> EntrySummary {
    EntrySummary(id: EntryID(id), groupID: FakeSession.web, title: .plain(title), username: .plain(user), url: .plain(url), hasPassword: password)
}

private func origin(_ url: String) -> ServiceOrigin {
    ServiceOrigin(serviceIdentifier: url, isDomain: false)!
}

struct AutoFillMatcherTests {
    @Test func entryURLsAreParsedIntoOrigins() {
        #expect(ServiceOrigin(entryURL: "https://Accounts.Example.com/login?x=1") == ServiceOrigin(host: "accounts.example.com"))
        #expect(ServiceOrigin(entryURL: "example.com/login") == ServiceOrigin(host: "example.com"))
        #expect(ServiceOrigin(entryURL: "http://example.com:8080") == ServiceOrigin(scheme: "http", host: "example.com", port: 8080))
        #expect(ServiceOrigin(entryURL: "https://example.com:443/") == ServiceOrigin(host: "example.com"))
        #expect(ServiceOrigin(entryURL: "https://bücher.de")?.host == "xn--bcher-kva.de")
        #expect(ServiceOrigin(entryURL: "androidapp://com.example") == nil)
        #expect(ServiceOrigin(entryURL: "ssh://example.com") == nil)
        #expect(ServiceOrigin(entryURL: "  ") == nil)
        #expect(ServiceOrigin(serviceIdentifier: "example.com", isDomain: true) == ServiceOrigin(host: "example.com"))
    }

    @Test func exactAndSubdomainFollowTheCorePolicy() {
        let saved = ServiceOrigin(host: "example.com")
        #expect(AutoFillMatcher.match(entry: saved, request: origin("https://example.com/login")) == .exact)
        #expect(AutoFillMatcher.match(entry: saved, request: origin("https://accounts.example.com/")) == .subdomain)
        #expect(AutoFillMatcher.match(entry: ServiceOrigin(host: "accounts.example.com"), request: origin("https://example.com/")) == nil)
    }

    @Test func otherDomainsAndLookAlikesNeverMatch() {
        let saved = ServiceOrigin(host: "example.com")
        #expect(AutoFillMatcher.match(entry: saved, request: origin("https://example.net/")) == nil)
        #expect(AutoFillMatcher.match(entry: saved, request: origin("https://example.com.evil.net/")) == nil)
        #expect(AutoFillMatcher.match(entry: ServiceOrigin(host: "paypal.com"), request: origin("https://paypaI.com/")) == nil)
        #expect(AutoFillMatcher.match(entry: ServiceOrigin(host: "github.io"), request: origin("https://attacker.github.io/")) == nil)
    }

    @Test func portsMustAgreeAndHTTPSIsNeverDowngraded() {
        #expect(AutoFillMatcher.match(entry: ServiceOrigin(host: "example.com"), request: origin("http://example.com/")) == nil)
        #expect(AutoFillMatcher.match(entry: ServiceOrigin(scheme: "http", host: "example.com"), request: origin("https://example.com/")) == .exact)
        #expect(AutoFillMatcher.match(entry: ServiceOrigin(host: "example.com"), request: origin("https://example.com:8443/")) == nil)
        #expect(AutoFillMatcher.match(entry: ServiceOrigin(host: "example.com", port: 8443), request: origin("https://example.com:8443/")) == .exact)
    }

    @Test func identitiesHoldNoSecretsAndOnlyFillableEntries() {
        let entries = [
            entry("a", "Mail", url: "https://mail.example.com/login"),
            entry("b", "No user", user: "", url: "https://example.com"),
            entry("c", "No password", url: "https://example.com", password: false),
            entry("d", "App", url: "androidapp://com.example"),
            entry("e", "Port", url: "https://example.com:8443"),
        ]
        let identities = AutoFillIndex.identities(for: entries, fingerprint: String(repeating: "ab", count: 32))
        #expect(identities == [AutoFillIdentity(host: "mail.example.com", user: "alice", recordIdentifier: "abababababababab/a")])
    }

    @Test func recordIdentifiersAreBoundToTheVault() {
        let record = AutoFillIndex.recordIdentifier(fingerprint: "0123456789abcdef0000", entry: EntryID("x/y"))
        #expect(AutoFillIndex.parse(recordIdentifier: record, fingerprint: "0123456789abcdef1111") == EntryID("x/y"))
        #expect(AutoFillIndex.parse(recordIdentifier: record, fingerprint: "ffff456789abcdef0000") == nil)
        #expect(AutoFillIndex.parse(recordIdentifier: "garbage", fingerprint: "0123456789abcdef") == nil)
    }
}

@MainActor
struct AutoFillFlowTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let references = MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx")))
    let authenticator = FakeAuthenticator()
    let keychain = FakeCredentialStore()
    let setups = MemorySetupStore()
    let completion = FakeAutoFillCompletion()
    let controller: VaultSessionController
    let biometric: BiometricUnlock

    init() {
        documents.put("vault.kdbx", kdbxHeader + Data(count: 64))
        documents.put("my.keyx", Data("key-file-bytes".utf8))
        opener.accepts.withLock { $0 = { password, _ in password == "pw" } }
        opener.fingerprint.withLock { $0 = String(repeating: "cd", count: 32) }
        opener.extraEntries.withLock {
            $0 = [
                entry("bank", "Bank", url: "https://example.com/login"),
                entry("acc", "Accounts", user: "bob", url: "https://accounts.example.com"),
                entry("net", "Example Net", url: "https://example.net"),
                entry("pp", "PayPal", url: "https://paypal.com"),
            ]
        }
        controller = VaultSessionController(opener: opener, documents: documents, references: references, settings: { SecuritySettings() })
        biometric = BiometricUnlock(controller: controller, authenticator: authenticator, credentials: keychain, setups: setups)
    }

    private func flow(_ request: AutoFillRequest) -> AutoFillFlow {
        AutoFillFlow(request: request, controller: controller, biometric: biometric, completion: completion)
    }

    private func unlock(_ flow: AutoFillFlow) async {
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        flow.vaultUnlocked()
    }

    private var reveals: [EntryID] { opener.sessions.withLock { $0.flatMap { $0.reveals.withLock { $0 } } } }

    @Test func listRequestSuggestsExactThenSubdomainAndNothingElse() async {
        let flow = flow(.list([origin("https://accounts.example.com/signin")]))
        #expect(flow.phase == .locked)
        await unlock(flow)
        #expect(flow.phase == .choosing)
        #expect(flow.suggested.map(\.id.rawValue) == ["acc", "bank"])
        #expect(!flow.others.map(\.id.rawValue).contains("acc"))
        #expect(flow.others.map(\.id.rawValue).contains("net"))
        #expect(reveals.isEmpty, "listing reveals no password")
    }

    @Test func aWrongDomainGetsNoSuggestion() async {
        let flow = flow(.list([origin("https://example.org/"), origin("https://paypaI.com/")]))
        await unlock(flow)
        #expect(flow.suggested.isEmpty)
        #expect(flow.others.count == 6)
    }

    @Test func choosingRevealsOnlyThatEntryThenCloses() async {
        let flow = flow(.list([origin("https://example.com/")]))
        await unlock(flow)
        flow.choose(EntryID("bank"))
        #expect(completion.filled.count == 1)
        #expect(completion.filled.first?.user == "alice")
        #expect(completion.filled.first?.password == "secret-bank")
        #expect(reveals == [EntryID("bank")])
        #expect(controller.state == .locked)
        #expect(flow.phase == .done)
        flow.choose(EntryID("net"))
        #expect(completion.filled.count == 1)
    }

    @Test func aSuggestionFillsDirectlyAfterUnlock() async {
        let record = AutoFillIndex.recordIdentifier(fingerprint: String(repeating: "cd", count: 32), entry: EntryID("bank"))
        let flow = flow(.credential(recordIdentifier: record, service: origin("https://example.com/login")))
        await unlock(flow)
        #expect(completion.filled.map(\.password) == ["secret-bank"])
        #expect(controller.state == .locked)
    }

    @Test func aStaleSuggestionFallsBackToTheList() async {
        let otherVault = AutoFillIndex.recordIdentifier(fingerprint: String(repeating: "ef", count: 32), entry: EntryID("bank"))
        let stale = flow(.credential(recordIdentifier: otherVault, service: origin("https://example.com/")))
        await unlock(stale)
        #expect(stale.phase == .choosing && stale.notice == .suggestionOutdated)
        #expect(completion.filled.isEmpty && reveals.isEmpty)
        controller.lock()

        // The entry's URL changed since it was indexed: it no longer belongs to this site.
        let record = AutoFillIndex.recordIdentifier(fingerprint: String(repeating: "cd", count: 32), entry: EntryID("net"))
        let moved = flow(.credential(recordIdentifier: record, service: origin("https://example.com/")))
        await unlock(moved)
        #expect(moved.notice == .suggestionOutdated)
        #expect(completion.filled.isEmpty && reveals.isEmpty)
    }

    @Test func searchUsesTheCoreAndSkipsEntriesWithoutPasswords() async {
        let flow = flow(.list([]))
        await unlock(flow)
        flow.query = "pay"
        await flow.search()
        #expect(flow.searchResults?.map(\.id.rawValue) == ["pp"])
        flow.query = ""
        await flow.search()
        #expect(flow.searchResults == nil)
    }

    @Test func cancelAndSuspendCloseTheSession() async {
        let flow = flow(.list([origin("https://example.com/")]))
        await unlock(flow)
        flow.suspend()
        #expect(controller.state == .locked && flow.phase == .locked && flow.suggested.isEmpty)
        await unlock(flow)
        flow.cancel()
        #expect(controller.state == .locked && completion.cancelled == 1 && completion.filled.isEmpty)
    }

    @Test func anUnconfiguredAppIsReportedAndTheExtensionNeverRetainsMaterial() async {
        let empty = VaultSessionController(opener: opener, documents: documents, references: MemoryReferenceStore(), settings: { SecuritySettings() })
        let unconfigured = AutoFillFlow(request: .list([]), controller: empty, biometric: biometric, completion: completion)
        #expect(unconfigured.phase == .failed(.notConfigured))

        _ = flow(.list([]))
        _ = await controller.unlock(password: Data("pw".utf8))
        #expect(opener.seen.withLock { $0.last?.retain } == false)
    }

    @Test func faceIDInTheExtensionUsesTheSameSetupAndKeyFile() async throws {
        _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        opener.accepts.withLock { $0 = { password, keyFile in password == "pw" && keyFile == "key-file-bytes" } }
        _ = await controller.unlock(password: Data("pw".utf8))
        #expect(await biometric.enable(reason: "on") == .enabled)
        controller.lock()

        let flow = flow(.list([origin("https://example.com/")]))
        #expect(await biometric.unlock(reason: "AutoFill") == .success)
        flow.vaultUnlocked()
        #expect(opener.seen.withLock { $0.last } == Seen(password: "pw", keyFile: "key-file-bytes", viaMaterial: true))
        flow.choose(EntryID("bank"))
        #expect(completion.filled.count == 1)

        documents.remove("my.keyx")
        #expect(await biometric.unlock(reason: "AutoFill") == .vaultError(.keyFileUnavailable))
        controller.removeKeyFile()
        #expect(await biometric.unlock(reason: "AutoFill") == .keyFileRequired)
    }

    @Test func anInvalidatedSetupIsSharedAndNeverPromptsAgain() async throws {
        _ = await controller.unlock(password: Data("pw".utf8))
        _ = await biometric.enable(reason: "on")
        controller.lock()
        authenticator.enrollment.withLock { $0 = Data("face-2".utf8) }
        #expect(await biometric.unlock(reason: "AutoFill") == .needsSetup)
        let prompts = authenticator.promptCount
        // The app reads the same record: it now says "needs setup" too.
        let app = BiometricUnlock(controller: controller, authenticator: authenticator, credentials: keychain, setups: setups)
        #expect(app.status == .needsSetup)
        #expect(await biometric.unlock(reason: "AutoFill") == .needsSetup)
        #expect(authenticator.promptCount == prompts)
    }

    @Test func faceIDUnavailableLeavesThePasswordPath() async {
        authenticator.availabilityValue.withLock { $0 = .lockedOut }
        biometric.refresh()
        let flow = flow(.list([origin("https://example.com/")]))
        #expect(flow.phase == .locked)
        await unlock(flow)
        #expect(flow.suggested.map(\.id.rawValue) == ["bank"])
    }
}

@MainActor
struct AutoFillIndexerTests {
    @Test func theIndexFollowsUnlocksAndVaultChanges() async {
        let documents = FakeDocuments()
        documents.put("vault.kdbx", kdbxHeader + Data(count: 64))
        documents.put("other.kdbx", kdbxHeader)
        let opener = FakeOpener()
        opener.extraEntries.withLock { $0 = [entry("bank", "Bank", url: "https://example.com/login")] }
        let controller = VaultSessionController(opener: opener, documents: documents, references: MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx"))), settings: { SecuritySettings() })
        let registry = FakeRegistry()
        let indexer = AutoFillIndexer(controller: controller, registry: registry)
        controller.onUnlock = { indexer.vaultUnlocked() }
        controller.onVaultChanged = { indexer.clear() }

        _ = await controller.unlock(password: Data("pw".utf8))
        await indexer.settle()
        #expect(registry.stored.withLock { $0.map(\.host) } == ["mail.example.org", "example.com"])
        #expect(registry.stored.withLock { $0.map(\.user) } == ["alice", "alice"])

        _ = await controller.chooseVault(URL(filePath: "/x/other.kdbx"))
        await indexer.settle()
        #expect(await registry.count() == 0)

        registry.enabled.withLock { $0 = false }
        _ = await controller.unlock(password: Data("pw".utf8))
        await indexer.settle()
        #expect(registry.replacements.load(ordering: .relaxed) == 1)
    }
}
