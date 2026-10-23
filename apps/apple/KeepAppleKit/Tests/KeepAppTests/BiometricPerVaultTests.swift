import Foundation
import KeepApp
import KeepCore
import Testing

/// Face ID unlock is set up per vault: switching vaults puts one setup aside and brings the other back.
@MainActor
struct BiometricPerVaultTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let references = MemoryReferenceStore()
    let authenticator = FakeAuthenticator()
    let keychain = FakeCredentialStore()
    let setups = MemorySetupStore()
    let controller: VaultSessionController
    let biometric: BiometricUnlock
    let library: VaultLibrary

    init() async {
        for name in ["a.kdbx", "b.kdbx"] { documents.put(name, kdbxHeader + Data(count: 64)) }
        opener.accepts.withLock { $0 = { password, _ in password == "pw" } }
        controller = VaultSessionController(opener: opener, documents: documents, references: references, settings: { SecuritySettings() })
        biometric = BiometricUnlock(controller: controller, authenticator: authenticator, credentials: keychain, setups: setups, newID: { "setup-\(UUID().uuidString)" })
        library = VaultLibrary(controller: controller, store: MemoryVaultListStore(), plan: { .plus })
        let biometric = biometric
        controller.onVaultChanged = {}
        library.onSwitched = { old, new in biometric.vaultSwitched(from: old, to: new) }
        library.onForgotten = { id, wasChosen in biometric.vaultForgotten(id, wasChosen: wasChosen) }
        _ = await controller.chooseVault(URL(fileURLWithPath: "/v/a.kdbx"))
        _ = await controller.chooseVault(URL(fileURLWithPath: "/v/b.kdbx"))
    }

    private var a: String { library.vaults[0].id }
    private var b: String { library.vaults[1].id }

    private func enable() async {
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        #expect(await biometric.enable(reason: "Turn on") == .enabled)
        controller.lock()
    }

    @Test func eachVaultKeepsItsOwnSetup() async throws {
        library.activate(a)
        await enable()
        let forA = try #require(setups.load())
        #expect(biometric.status == .on(keyFileRequired: false))

        library.activate(b)
        #expect(biometric.status == .off, "b has no setup of its own")
        #expect(setups.load() == nil)
        #expect(setups.loadOthers()[a] == forA)
        #expect(keychain.material(forA.id) != nil, "a's item is kept while b is the chosen vault")

        await enable()
        let forB = try #require(setups.load())
        #expect(forB.id != forA.id)
        #expect(keychain.count == 2)

        library.activate(a)
        #expect(setups.load() == forA)
        #expect(setups.loadOthers()[b] == forB)
        #expect(biometric.status == .on(keyFileRequired: false))
        #expect(await biometric.unlock(reason: "Unlock") == .success)
    }

    @Test func turningItOffForOneVaultLeavesTheOther() async throws {
        library.activate(a)
        await enable()
        let forA = try #require(setups.load())
        library.activate(b)
        await enable()
        biometric.disable()
        #expect(biometric.status == .off)
        #expect(keychain.material(forA.id) != nil)
        biometric.refresh()
        #expect(keychain.material(forA.id) != nil, "a stale-item sweep must not take another vault's item")

        library.activate(a)
        #expect(biometric.status == .on(keyFileRequired: false))
    }

    @Test func aForgottenVaultTakesItsSetupWithIt() async throws {
        let a = a
        let b = b
        library.activate(a)
        await enable()
        let forA = try #require(setups.load())
        library.activate(b)
        await enable()
        let forB = try #require(setups.load())

        library.forget(a)
        #expect(setups.loadOthers().isEmpty)
        #expect(keychain.material(forA.id) == nil)
        #expect(keychain.material(forB.id) != nil)
        #expect(biometric.status == .on(keyFileRequired: false))

        library.forget(b)
        #expect(setups.load() == nil)
        #expect(keychain.count == 0)
    }

    @Test func aSetupFromAnotherInstallationIsDropped() async throws {
        library.activate(a)
        await enable()
        library.activate(b)
        #expect(!setups.loadOthers().isEmpty)
        setups.installation.withLock { $0 = Data(repeating: 9, count: 16) }
        biometric.refresh()
        #expect(setups.loadOthers().isEmpty)
        #expect(keychain.count == 0)
    }
}
