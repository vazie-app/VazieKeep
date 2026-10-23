import Foundation
import KeepApp
import KeepCore
import Synchronization
import Testing

@MainActor
struct SaveTests {
    let documents = FakeDocuments()
    let opener = FakeOpener()
    let copies = MemoryCopyStore()
    let original = kdbxHeader + Data("original".utf8)
    let controller: VaultSessionController

    init() {
        documents.put("vault.kdbx", original)
        controller = VaultSessionController(opener: opener, documents: documents, references: MemoryReferenceStore(VaultReference(vault: reference("vault.kdbx"))), copies: copies, settings: { SecuritySettings() })
    }

    private func unlocked() async -> FakeSession {
        #expect(await controller.unlock(password: Data("pw".utf8)) == .success)
        return opener.sessions.withLock { $0.last! }
    }

    private func create(_ title: String = "New") async -> EditResult<EntryID> {
        await controller.edit { session throws(VaultEditError) in try session.createEntry(in: FakeSession.root, EntryDraft(title: title, password: Data("p".utf8))) }
    }

    @Test func anEditIsWrittenAtOnceAndTheOldVersionIsKept() async {
        _ = await unlocked()
        var saves = 0
        controller.onSaved = { saves += 1 }
        let revision = controller.contentRevision
        guard case let .saved(id) = await create() else { Issue.record("not saved"); return }
        #expect(id == EntryID("new-0"))
        #expect(documents.data("vault.kdbx") == kdbxHeader + Data("saved-1".utf8))
        #expect(copies.backup(fingerprint: "fp") == original)
        #expect(!controller.hasUnsavedChanges && saves == 1)
        #expect(controller.contentRevision == revision + 1)

        // The next save expects the bytes this one wrote.
        guard case .saved = await create("Second") else { Issue.record("second not saved"); return }
        #expect(documents.data("vault.kdbx") == kdbxHeader + Data("saved-2".utf8))
    }

    @Test func aChangeMadeElsewhereBlocksTheSave() async {
        _ = await unlocked()
        let theirs = kdbxHeader + Data("saved by another app".utf8)
        documents.put("vault.kdbx", theirs)
        guard case .notSaved(_, .changedExternally) = await create() else { Issue.record("expected a conflict"); return }
        #expect(documents.data("vault.kdbx") == theirs, "their version is untouched")
        #expect(controller.hasUnsavedChanges)
        #expect(copies.backup(fingerprint: "fp") == nil)
    }

    @Test func aFailedWriteLeavesTheOriginalAndCanBeRetried() async {
        _ = await unlocked()
        for failure in [WriteFailure.insufficientSpace, .notWritable, .verificationFailed, .outOfDate] {
            documents.writeFailure.withLock { $0 = failure }
            guard case let .notSaved(_, reason) = await create() else { Issue.record("expected \(failure)"); return }
            #expect(reason == VaultSessionController.map(failure))
            #expect(documents.data("vault.kdbx") == original)
            #expect(controller.hasUnsavedChanges)
        }
        #expect(await controller.save() == .saved)
        #expect(documents.data("vault.kdbx") == kdbxHeader + Data("saved-4".utf8))
        #expect(!controller.hasUnsavedChanges)
    }

    @Test func aVanishedFileOrAMissingBackupStopsTheSave() async {
        _ = await unlocked()
        copies.backupsFail.store(true, ordering: .relaxed)
        guard case .notSaved(_, .backupFailed) = await create() else { Issue.record("expected backupFailed"); return }
        #expect(documents.data("vault.kdbx") == original)
        copies.backupsFail.store(false, ordering: .relaxed)
        documents.remove("vault.kdbx")
        #expect(await controller.save() == .failed(.unavailable))
        #expect(documents.writes.load(ordering: .relaxed) == 0)
    }

    @Test func anEncryptionOrVerificationFailureWritesNothing() async {
        let session = await unlocked()
        session.saveFailure.withLock { $0 = .verificationFailed }
        guard case .notSaved(_, .verificationFailed) = await create() else { Issue.record("expected verificationFailed"); return }
        session.saveFailure.withLock { $0 = .encryptionFailed }
        #expect(await controller.save() == .failed(.encryptionFailed))
        #expect(documents.data("vault.kdbx") == original)
    }

    @Test func aRefusedEditChangesNothing() async {
        _ = await unlocked()
        guard case .failed(VaultEditError(reason: .invalidText)) = await create("bell\u{7}") else { Issue.record("expected invalidText"); return }
        #expect(!controller.hasUnsavedChanges)
        #expect(documents.writes.load(ordering: .relaxed) == 0)
        let result = await controller.edit { session throws(VaultEditError) in try session.deleteGroup(FakeSession.web, recursive: false, permanent: false) }
        guard case .failed(VaultEditError(reason: .groupNotEmpty)) = result else { Issue.record("expected groupNotEmpty"); return }
    }

    @Test func unsavedChangesSurviveALockAsAnEncryptedCopyUnlessDiscarded() async {
        _ = await unlocked()
        documents.writeFailure.withLock { $0 = .unavailable }
        _ = await create()
        controller.lock()
        #expect(copies.unsaved(fingerprint: "fp") == kdbxHeader + Data("saved-1".utf8))
        _ = await unlocked()
        #expect(controller.hasRecoveredCopy)
        #expect(controller.recoveredCopy() != nil)
        controller.discardRecoveredCopy()
        #expect(!controller.hasRecoveredCopy && copies.unsaved(fingerprint: "fp") == nil)

        documents.writeFailure.withLock { $0 = .unavailable }
        _ = await create()
        controller.lock(discardingChanges: true)
        #expect(copies.unsaved(fingerprint: "fp") == nil)
    }

    @Test func concurrentEditsAreSavedOneAfterAnother() async {
        _ = await unlocked()
        async let first = create("A")
        async let second = create("B")
        let results = await [first, second]
        for result in results {
            guard case .saved = result else { Issue.record("not saved"); return }
        }
        #expect(documents.data("vault.kdbx") == kdbxHeader + Data("saved-2".utf8))
        #expect(!controller.hasUnsavedChanges)
    }

    @Test func aCopyIsMadeWithoutWritingTheVault() async {
        _ = await unlocked()
        documents.writeFailure.withLock { $0 = .unavailable }
        _ = await create()
        guard case let .success(bytes) = await controller.snapshotForCopy() else { Issue.record("no copy"); return }
        #expect(bytes == kdbxHeader + Data("saved-1".utf8))
        #expect(documents.data("vault.kdbx") == original)
    }

    @Test func editingALockedVaultIsRefused() async {
        guard case .failed(VaultEditError(reason: .sessionClosed)) = await create() else { Issue.record("expected sessionClosed"); return }
        #expect(await controller.save() == .failed(.locked))
    }
}
