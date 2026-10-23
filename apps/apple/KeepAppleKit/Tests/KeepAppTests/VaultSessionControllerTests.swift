import Foundation
import KeepApp
import KeepCore
import Testing

@MainActor
struct VaultSessionControllerTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let store: MemoryReferenceStore
    var settings = SecuritySettings()

    init() {
        documents.put("vault.kdbx", kdbxHeader + Data(count: 64))
        documents.put("my.keyx", Data("key-file-bytes".utf8))
        store = MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx")))
    }

    func controller(now: @escaping () -> ContinuousClock.Instant = { .now }, sleep: @escaping @Sendable (Duration) async -> Void = { _ in }) -> VaultSessionController {
        let settings = settings
        return VaultSessionController(opener: opener, documents: documents, references: store, settings: { settings }, now: now, sleep: sleep)
    }

    @Test func freshControllerIsLockedEvenWithAStoredVault() {
        let controller = controller()
        #expect(controller.state == .locked)
        #expect(controller.reference?.vault.displayName == "vault.kdbx")
    }

    @Test func unlockSucceedsAndLockClosesTheSession() async throws {
        let controller = controller()
        var locks = 0
        controller.onLock = { locks += 1 }
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        guard case let .unlocked(info) = controller.state else { Issue.record("not unlocked"); return }
        #expect(info.name == "Fake Vault")
        let session = try #require(opener.sessions.withLock { $0.last })
        let signal = controller.hideSecretsSignal
        controller.lock()
        #expect(session.isClosed)
        #expect(controller.state == .locked)
        #expect(controller.hideSecretsSignal > signal)
        #expect(locks == 1)
        #expect(throws: VaultSessionError(reason: .sessionClosed)) { try controller.entries() }
    }

    @Test func wrongPasswordAndWrongKeyFileAreDistinguished() async {
        opener.accepts.withLock { $0 = { password, keyFile in password == "right" && keyFile == nil } }
        let controller = controller()
        #expect(await controller.unlock(password: Data("wrong".utf8)) == .failure(.wrongPassword))
        _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        #expect(await controller.unlock(password: Data("right".utf8)) == .failure(.wrongPasswordOrKeyFile))
        #expect(controller.state == .locked)
    }

    @Test func passwordPlusKeyFileAndKeyFileOnly() async {
        opener.accepts.withLock { $0 = { _, keyFile in keyFile == "key-file-bytes" } }
        let controller = controller()
        #expect(await controller.unlock(password: nil) == .failure(.missingCredentials))
        #expect(await controller.chooseKeyFile(URL(filePath: "/x/my.keyx")) == .success(reference("my.keyx")))
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        controller.lock()
        #expect(await controller.unlock(password: nil) == .success)
        #expect(opener.seen.withLock { $0 } == [Seen(password: "pw", keyFile: "key-file-bytes"), Seen(password: nil, keyFile: "key-file-bytes")])
    }

    @Test func emptyPasswordFieldAlsoTriesAnEmptyPassword() async {
        opener.accepts.withLock { $0 = { password, keyFile in password == "" && keyFile != nil } }
        let controller = controller()
        _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        #expect(await controller.unlock(password: nil) == .success)
        #expect(opener.seen.withLock { $0.map(\.password) } == [nil, ""])
    }

    @Test func unavailableFilesAreTypedErrors() async {
        let controller = controller()
        _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        documents.remove("my.keyx")
        #expect(await controller.unlock(password: Data("pw".utf8)) == .failure(.keyFileUnavailable))
        documents.remove("vault.kdbx")
        #expect(await controller.unlock(password: Data("pw".utf8)) == .failure(.fileUnavailable))
        #expect(opener.seen.withLock { $0.isEmpty })
        #expect(controller.reference?.keyFile != nil)
    }

    @Test func openErrorsMapToUserCategories() async {
        let controller = controller()
        let cases: [(VaultOpenError.Reason, UnlockError)] = [
            (.damaged, .damaged), (.unsupported, .unsupported), (.notAVault, .notAVault),
            (.tooLarge, .tooLarge), (.invalidKeyFile, .invalidKeyFile), (.unavailable, .unknown),
        ]
        for (reason, expected) in cases {
            opener.failure.withLock { $0 = reason }
            #expect(await controller.unlock(password: Data("pw".utf8)) == .failure(expected))
        }
    }

    @Test func doubleUnlockIsRejectedAndALockDuringTheKdfWins() async throws {
        let controller = controller()
        opener.holdNext.store(true, ordering: .releasing)
        let first = Task { await controller.unlock(password: Data("pw".utf8)) }
        while opener.seen.withLock({ $0.isEmpty }) { await Task.yield() }
        #expect(controller.isUnlocking)
        #expect(await controller.unlock(password: Data("pw".utf8)) == .alreadyRunning)
        controller.sceneDidEnterBackground()
        controller.lock()
        await opener.release()
        #expect(await first.value == .cancelled)
        #expect(controller.state == .locked)
        #expect(try #require(opener.sessions.withLock { $0.last }).isClosed)
    }

    @Test func staleBookmarksAreRefreshedAndStored() async {
        documents.staleNames.withLock { _ = $0.insert("vault.kdbx") }
        let controller = controller()
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        #expect(store.current?.vault.displayName == "vault.kdbx (moved)")
    }

    @Test func choosingFiles() async {
        documents.put("other.kdbx", kdbxHeader)
        documents.put("notes.txt", Data("hello".utf8))
        let controller = controller()
        _ = await controller.chooseKeyFile(URL(filePath: "/x/my.keyx"))
        #expect(await controller.chooseVault(URL(filePath: "/x/notes.txt")) == .failure(.notAVault))
        #expect(await controller.chooseVault(URL(filePath: "/x/missing.kdbx")) == .failure(.unavailable))
        #expect(await controller.chooseKeyFile(URL(filePath: "/x/other.kdbx")) == .failure(.isVault))
        #expect(await controller.chooseVault(URL(filePath: "/x/other.kdbx")) == .success(reference("other.kdbx")))
        #expect(store.current?.keyFile == nil)
        controller.forgetVault()
        #expect(controller.reference == nil && store.current == nil)
    }

    @Test func searchRunsAgainstTheSessionAndALockMidwayIsHarmless() async throws {
        let controller = controller()
        _ = await controller.unlock(password: Data("pw".utf8))
        #expect(try await controller.search("mail").map(\.id) == [FakeSession.mail])
        controller.lock()
        await #expect(throws: VaultSessionError(reason: .sessionClosed)) { try await controller.search("mail") }
    }

    // MARK: Auto-lock

    @Test func backgroundShorterThanTheTimeoutKeepsTheVaultOpen() async {
        var clock = ContinuousClock.now
        let controller = controller(now: { clock }, sleep: { _ in await Task.yield(); try? await Task.sleep(for: .seconds(3600)) })
        _ = await controller.unlock(password: Data("pw".utf8))
        controller.sceneDidEnterBackground()
        clock += .seconds(10)
        controller.sceneWillEnterForeground()
        #expect(controller.isUnlocked)
    }

    @Test func foregroundAfterTheTimeoutLocksEvenIfTheTimerNeverFired() async {
        var clock = ContinuousClock.now
        let controller = controller(now: { clock }, sleep: { _ in try? await Task.sleep(for: .seconds(3600)) })
        _ = await controller.unlock(password: Data("pw".utf8))
        controller.sceneDidEnterBackground()
        clock += .seconds(35)
        controller.sceneWillEnterForeground()
        #expect(controller.state == .locked)
    }

    @Test func theBackgroundTimerLocksOnItsOwn() async {
        let controller = controller(sleep: { _ in })
        _ = await controller.unlock(password: Data("pw".utf8))
        controller.sceneDidEnterBackground()
        while controller.isUnlocked { await Task.yield() }
        #expect(controller.state == .locked)
    }

    @Test func immediatelyLocksOnBackground() async {
        var settings = SecuritySettings()
        settings.autoLock = .immediately
        let controller = VaultSessionController(opener: opener, documents: documents, references: store, settings: { settings })
        _ = await controller.unlock(password: Data("pw".utf8))
        controller.sceneDidEnterBackground()
        #expect(controller.state == .locked)
    }

    @Test func protectedDataLossLocksUnlessDisabled() async {
        let controller = controller()
        _ = await controller.unlock(password: Data("pw".utf8))
        controller.protectedDataWillBecomeUnavailable()
        #expect(controller.state == .locked)

        var settings = SecuritySettings()
        settings.lockWhenProtectedDataUnavailable = false
        let relaxed = VaultSessionController(opener: opener, documents: documents, references: store, settings: { settings })
        _ = await relaxed.unlock(password: Data("pw".utf8))
        let signal = relaxed.hideSecretsSignal
        relaxed.protectedDataWillBecomeUnavailable()
        #expect(relaxed.isUnlocked)
        #expect(relaxed.hideSecretsSignal > signal)
    }

    @Test func captureAndBackgroundHideSecrets() async {
        let controller = controller(sleep: { _ in try? await Task.sleep(for: .seconds(3600)) })
        _ = await controller.unlock(password: Data("pw".utf8))
        let start = controller.hideSecretsSignal
        controller.screenCaptureStarted()
        controller.sceneDidEnterBackground()
        #expect(controller.hideSecretsSignal == start + 2)
        controller.sceneWillEnterForeground()
        #expect(controller.isUnlocked)
    }
}
