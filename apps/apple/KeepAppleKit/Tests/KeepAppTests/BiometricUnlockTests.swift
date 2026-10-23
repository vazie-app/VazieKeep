import Foundation
import KeepApp
import KeepCore
import Synchronization
import Testing

@MainActor
struct BiometricUnlockTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let references = MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx")))
    let authenticator = FakeAuthenticator()
    let keychain = FakeCredentialStore()
    let setups = MemorySetupStore()
    let controller: VaultSessionController
    let biometric: BiometricUnlock

    init() {
        documents.put("vault.kdbx", kdbxHeader + Data(count: 64))
        documents.put("my.keyx", Data("key-file-bytes".utf8))
        opener.accepts.withLock { $0 = { password, _ in password == "pw" } }
        controller = VaultSessionController(opener: opener, documents: documents, references: references, settings: { SecuritySettings() })
        biometric = BiometricUnlock(controller: controller, authenticator: authenticator, credentials: keychain, setups: setups, newID: { "setup-\(UUID().uuidString)" })
    }

    private func enabled(keyFile: Bool = false) async throws -> BiometricSetup {
        if keyFile { _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx")) }
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        #expect(biometric.eligibility == .ready)
        #expect(await biometric.enable(reason: "Turn on") == .enabled)
        controller.lock()
        return try #require(setups.load())
    }

    // MARK: Setup

    @Test func setupStoresOnlyThePasswordComponentBoundToTheVault() async throws {
        #expect(biometric.status == .off)
        let setup = try await enabled()
        #expect(biometric.status == .on(keyFileRequired: false))
        #expect(setup.fingerprint == "fp")
        #expect(setup.installation == setups.installationID())
        #expect(setup.enrollment == Data("face-1".utf8))
        #expect(keychain.material(setup.id) == fakeMaterial("pw"))
        #expect(authenticator.promptCount == 1)
        #expect(opener.seen.withLock { $0.first?.retain } == true)
    }

    @Test func cancelledSetupLeavesNothingBehind() async {
        _ = await controller.unlock(password: Data("pw".utf8))
        authenticator.results.withLock { $0 = [.cancelled] }
        #expect(await biometric.enable(reason: "Turn on") == .cancelled)
        authenticator.results.withLock { $0 = [.lockedOut] }
        #expect(await biometric.enable(reason: "Turn on") == .failed(.lockedOut))
        #expect(biometric.status == .off)
        #expect(keychain.count == 0)
        #expect(setups.load() == nil)
    }

    @Test func aFailedKeychainWriteLeavesItOff() async {
        _ = await controller.unlock(password: Data("pw".utf8))
        keychain.createError.withLock { $0 = .failed(-25293) }
        #expect(await biometric.enable(reason: "Turn on") == .failed(.failed))
        #expect(biometric.status == .off)
        #expect(setups.load() == nil)
    }

    @Test func unavailableBiometryIsNeverOfferedAndNothingIsRetained() async {
        authenticator.availabilityValue.withLock { $0 = .notEnrolled }
        biometric.refresh()
        #expect(biometric.status == .unavailable(.notEnrolled))
        _ = await controller.unlock(password: Data("pw".utf8))
        #expect(opener.seen.withLock { $0.last?.retain } == false)
        #expect(biometric.eligibility == .unavailable(.notEnrolled))
        #expect(await biometric.enable(reason: "Turn on") == .failed(.notEligible(.unavailable(.notEnrolled))))
        #expect(authenticator.promptCount == 0)
    }

    @Test func eligibilityNeedsATypedPasswordUnlock() async {
        #expect(biometric.eligibility == .locked)
        _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        opener.accepts.withLock { $0 = { password, keyFile in keyFile != nil && (password == nil || password == "pw") } }
        _ = await controller.unlock(password: nil)
        #expect(biometric.eligibility == .noPassword)
        #expect(controller.unlockMaterial() == nil)
    }

    @Test func enableThenDisableRemovesTheItemAndTheRecord() async throws {
        _ = try await enabled()
        biometric.disable()
        #expect(biometric.status == .off)
        #expect(keychain.count == 0)
        #expect(setups.load() == nil)
        _ = await controller.unlock(password: Data("pw".utf8))
        #expect(opener.seen.withLock { $0.last?.retain } == true)
    }

    @Test func noMaterialIsRetainedWhileFaceIDIsOnAndReEnablingNeedsAPasswordUnlock() async throws {
        _ = try await enabled()
        #expect(await biometric.unlock(reason: "Unlock") == .success)
        #expect(biometric.eligibility == .needsPasswordUnlock)
        controller.lock()
        _ = await controller.unlock(password: Data("pw".utf8))
        #expect(opener.seen.withLock { $0.last?.retain } == false)
    }

    // MARK: Unlock

    @Test func faceIDUnlockOpensTheVaultWithTheStoredMaterial() async throws {
        _ = try await enabled()
        #expect(await biometric.unlock(reason: "Unlock") == .success)
        #expect(controller.isUnlocked)
        #expect(controller.unlockMethod == UnlockMethod(typedPassword: false, biometric: true, keyFile: false, materialRetained: false))
        #expect(opener.seen.withLock { $0.last } == Seen(password: "pw", keyFile: nil, viaMaterial: true))
    }

    @Test func cancelAndFallbackKeepTheVaultLockedAndTheSetupOn() async throws {
        _ = try await enabled()
        let attempts = opener.seen.withLock { $0.count }
        authenticator.results.withLock { $0 = [.cancelled, .fallback, .failed] }
        #expect(await biometric.unlock(reason: "Unlock") == .cancelled)
        #expect(await biometric.unlock(reason: "Unlock") == .fallback)
        #expect(await biometric.unlock(reason: "Unlock") == .failed)
        #expect(controller.state == .locked)
        #expect(biometric.status == .on(keyFileRequired: false))
        #expect(opener.seen.withLock { $0.count } == attempts)
    }

    @Test func lockoutNeverPrompts() async throws {
        _ = try await enabled()
        authenticator.availabilityValue.withLock { $0 = .lockedOut }
        #expect(await biometric.unlock(reason: "Unlock") == .lockedOut)
        #expect(authenticator.promptCount == 1)
        #expect(biometric.status == .on(keyFileRequired: false))
    }

    @Test func aChangedEnrollmentInvalidatesWithoutAPrompt() async throws {
        let setup = try await enabled()
        authenticator.enrollment.withLock { $0 = Data("face-2".utf8) }
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(authenticator.promptCount == 1)
        #expect(biometric.status == .needsSetup)
        #expect(keychain.material(setup.id) == nil)
        #expect(setups.load()?.state == .needsSetup)
    }

    @Test func aKeychainRefusalAfterThePromptInvalidates() async throws {
        _ = try await enabled()
        keychain.readError.withLock { $0 = .invalidated }
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(biometric.status == .needsSetup)
        #expect(keychain.count == 0)
    }

    @Test func aMissingKeychainItemInvalidatesWithoutAPrompt() async throws {
        let setup = try await enabled()
        keychain.remove(id: setup.id)
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(authenticator.promptCount == 1)
    }

    @Test func aCorruptedKeychainRecordInvalidates() async throws {
        let setup = try await enabled()
        keychain.items.withLock { $0[setup.id]?.material = Data([1, 2, 3]) }
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        try await reenable()
        keychain.items.withLock { $0[setups.load()!.id]?.material = Data(repeating: 9, count: 33) }
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(controller.state == .locked)
    }

    @Test func aDifferentDatabaseIsClosedAndInvalidates() async throws {
        _ = try await enabled()
        opener.fingerprint.withLock { $0 = "another vault" }
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(controller.state == .locked)
        #expect(try #require(opener.sessions.withLock { $0.last }).isClosed)
    }

    @Test func aPasswordChangedElsewhereInvalidatesOnceAndNeverPromptsAgain() async throws {
        _ = try await enabled()
        opener.accepts.withLock { $0 = { password, _ in password == "new" } }
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        let prompts = authenticator.promptCount
        #expect(await biometric.unlock(reason: "Unlock") == .needsSetup)
        #expect(authenticator.promptCount == prompts)
        #expect(await controller.unlock(password: Data("new".utf8)) == .success)
        #expect(biometric.eligibility == .ready)
    }

    @Test func turningOffABrokenSetupClearsIt() async throws {
        _ = try await enabled()
        keychain.readError.withLock { $0 = .invalidated }
        _ = await biometric.unlock(reason: "Unlock")
        biometric.disable()
        #expect(biometric.status == .off)
        #expect(setups.load() == nil)
    }

    // MARK: Key files

    @Test func aKeyFileVaultStillReadsItsKeyFileEveryTime() async throws {
        opener.accepts.withLock { $0 = { password, keyFile in password == "pw" && keyFile == "key-file-bytes" } }
        let setup = try await enabled(keyFile: true)
        #expect(setup.keyFileRequired)
        #expect(keychain.material(setup.id) == fakeMaterial("pw"))
        #expect(await biometric.unlock(reason: "Unlock") == .success)
        #expect(opener.seen.withLock { $0.last } == Seen(password: "pw", keyFile: "key-file-bytes", viaMaterial: true))
    }

    @Test func aMissingKeyFileIsAskedForBeforeAnyPrompt() async throws {
        opener.accepts.withLock { $0 = { password, keyFile in password == "pw" && keyFile == "key-file-bytes" } }
        _ = try await enabled(keyFile: true)
        let prompts = authenticator.promptCount
        controller.removeKeyFile()
        #expect(await biometric.unlock(reason: "Unlock") == .keyFileRequired)
        _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        documents.remove("my.keyx")
        #expect(await biometric.unlock(reason: "Unlock") == .vaultError(.keyFileUnavailable))
        #expect(authenticator.promptCount == prompts)
        #expect(biometric.status == .on(keyFileRequired: true))
    }

    @Test func aStaleKeyFileBookmarkIsRefreshedAndKeepsTheSetup() async throws {
        opener.accepts.withLock { $0 = { password, keyFile in password == "pw" && keyFile == "key-file-bytes" } }
        _ = try await enabled(keyFile: true)
        documents.staleNames.withLock { _ = $0.insert("my.keyx") }
        #expect(await biometric.unlock(reason: "Unlock") == .success)
        #expect(references.current?.keyFile?.displayName == "my.keyx (moved)")
        #expect(biometric.status == .on(keyFileRequired: true))
    }

    // MARK: Binding

    @Test func choosingAnotherVaultRemovesTheSetup() async throws {
        _ = try await enabled()
        documents.put("other.kdbx", kdbxHeader)
        _ = await controller.chooseVault(URL(filePath: "/x/other.kdbx"))
        #expect(biometric.status == .off)
        #expect(keychain.count == 0)
        #expect(setups.load() == nil)
    }

    @Test func aReinstallIgnoresAndRemovesTheOldKeychainItem() async throws {
        let setup = try await enabled()
        // The sandbox is gone after a reinstall; the Keychain item is not.
        setups.clear()
        setups.installation.withLock { $0 = Data(repeating: 8, count: 16) }
        let reinstalled = BiometricUnlock(controller: controller, authenticator: authenticator, credentials: keychain, setups: setups)
        #expect(reinstalled.status == .off)
        #expect(keychain.material(setup.id) == nil)
    }

    @Test func aRecordFromAnotherInstallationIsDiscarded() async throws {
        _ = try await enabled()
        setups.installation.withLock { $0 = Data(repeating: 9, count: 16) }
        biometric.refresh()
        #expect(biometric.status == .off)
        #expect(setups.load() == nil)
        #expect(keychain.count == 0)
    }

    @Test func aLockDuringThePromptWins() async throws {
        _ = try await enabled()
        let gate = AsyncStream<Void>.makeStream()
        authenticator.results.withLock { $0 = [] }
        let slow = SlowAuthenticator(inner: authenticator, gate: gate.stream)
        let guarded = BiometricUnlock(controller: controller, authenticator: slow, credentials: keychain, setups: setups)
        let attempt = Task { await guarded.unlock(reason: "Unlock") }
        while slow.waiting.withLock({ !$0 }) { await Task.yield() }
        controller.lock()
        gate.continuation.yield()
        #expect(await attempt.value == .cancelled)
        #expect(controller.state == .locked)
        #expect(guarded.status == .on(keyFileRequired: false))
    }

    // MARK: Unlock screen

    @Test func cancelOnTheUnlockScreenIsQuietAndRefocusesThePassword() async throws {
        _ = try await enabled()
        let model = UnlockModel(controller: controller, biometric: biometric)
        #expect(model.offersBiometricUnlock)
        authenticator.results.withLock { $0 = [.cancelled, .lockedOut] }
        let focus = model.refocusSignal
        await model.unlockWithBiometrics(reason: "Unlock")
        #expect(model.biometricResult == nil && model.error == nil)
        #expect(model.refocusSignal == focus + 1)
        await model.unlockWithBiometrics(reason: "Unlock")
        #expect(model.biometricResult == .lockedOut)
        model.password = "pw"
        await model.unlock()
        #expect(controller.isUnlocked)
        #expect(model.biometricResult == nil)
    }

    @Test func theUnlockScreenStopsOfferingFaceIDOnceItNeedsSetup() async throws {
        _ = try await enabled()
        let model = UnlockModel(controller: controller, biometric: biometric)
        keychain.readError.withLock { $0 = .invalidated }
        await model.unlockWithBiometrics(reason: "Unlock")
        #expect(model.biometricResult == .needsSetup)
        #expect(!model.offersBiometricUnlock)
        #expect(model.biometricNeedsSetup)
    }

    private func reenable() async throws {
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        #expect(await biometric.enable(reason: "Turn on") == .enabled)
        controller.lock()
    }
}

private final class SlowAuthenticator: BiometricAuthenticator {
    let inner: FakeAuthenticator
    let gate: AsyncStream<Void>
    let waiting = Mutex(false)

    init(inner: FakeAuthenticator, gate: AsyncStream<Void>) {
        self.inner = inner
        self.gate = gate
    }

    func availability() -> BiometricAvailability { inner.availability() }
    func enrollmentState() -> Data? { inner.enrollmentState() }

    func authenticate(reason: String) async -> BiometricAuthResult {
        waiting.withLock { $0 = true }
        for await _ in gate { break }
        return await inner.authenticate(reason: reason)
    }
}
