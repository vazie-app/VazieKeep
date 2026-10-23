package app.vazie.keep.data

import app.vazie.keep.core.EntryDraft
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.GroupId
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.core.VaultSaveException
import app.vazie.keep.testing.FINGERPRINT
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.ROOT
import app.vazie.keep.testing.VAULT_URI
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class VaultRepositoryEditingTest {
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val references = InMemoryReferenceStore()
    private val writer = FakeDocumentWriter()
    private val copies = InMemoryCopyStore()
    private val repository = testRepository(core, files, references, writer, copies, background = kotlinx.coroutines.Dispatchers.Unconfined, progressDelay = 500.milliseconds)

    private val session get() = core.sessions.single() as FakeVaultSession

    private fun draft(title: String = "Created") = EntryDraft(title, "user", "pw".toByteArray(), "", "", emptyList(), emptyList())

    private suspend fun unlock() = assertEquals(UnlockResult.Success, repository.unlock("x".toByteArray()))

    @Test
    fun anEditIsWrittenToTheFileAtOnce() = runTest {
        unlock()
        val outcome = repository.edit { s -> draft().use { s.createEntry(ROOT, it) } }
        assertIs<EditOutcome.Saved<EntryId>>(outcome)
        assertEquals(1, writer.written.size)
        assertContentEquals("encrypted-1".toByteArray(), writer.written.single())
        assertFalse(repository.hasUnsavedChanges.value)
        assertEquals(1, repository.contentRevision.value)
        assertEquals(1, writer.backups)
        assertNull(copies.unsaved(FINGERPRINT))
    }

    @Test
    fun theInfoInTheStateIsRefreshedAfterEachEdit() = runTest {
        unlock()
        repository.edit { it.createGroup(ROOT, "Fresh") }
        assertEquals(3, (repository.state.value as VaultState.Unlocked).info.groupCount)
    }

    @Test
    fun aFailedSaveKeepsTheEditInTheSessionAndAnEncryptedCopyInTheApp() = runTest {
        unlock()
        writer.failure = SaveFailure.Unavailable
        val outcome = repository.edit { s -> draft().use { s.createEntry(ROOT, it) } }
        assertIs<EditOutcome.NotSaved<EntryId>>(outcome)
        assertEquals(SaveFailure.Unavailable, outcome.failure)
        assertTrue(repository.hasUnsavedChanges.value)
        assertContentEquals("encrypted-1".toByteArray(), copies.unsaved(FINGERPRINT))
        assertTrue(session.entries.any { it.id == outcome.value })

        writer.failure = null
        assertEquals(SaveOutcome.Saved, repository.save())
        assertFalse(repository.hasUnsavedChanges.value)
        assertNull(copies.unsaved(FINGERPRINT))
    }

    @Test
    fun aChangedFileIsReportedAndNeverOverwritten() = runTest {
        unlock()
        writer.failure = SaveFailure.ChangedExternally
        val outcome = repository.edit { it.renameGroup(GroupId("web"), "Renamed") }
        assertEquals(SaveFailure.ChangedExternally, (outcome as EditOutcome.NotSaved).failure)
        assertTrue(writer.written.isEmpty())
        assertTrue(repository.hasUnsavedChanges.value)
    }

    @Test
    fun anInvalidEditChangesNothingAndWritesNothing() = runTest {
        unlock()
        session.editFailure = VaultEditException.Reason.InvalidFieldName
        val outcome = repository.edit { s -> draft().use { s.createEntry(ROOT, it) } }
        assertEquals(VaultEditException.Reason.InvalidFieldName, (outcome as EditOutcome.Invalid).reason)
        assertTrue(writer.written.isEmpty())
        assertFalse(repository.hasUnsavedChanges.value)
        assertEquals(0, repository.contentRevision.value)
    }

    @Test
    fun editingAClosedSessionLocksAndThrows() = runTest {
        unlock()
        session.close()
        assertFailsWith<VaultLockedException> { repository.edit { it.createGroup(ROOT, "x") } }
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun editingWhileLockedThrows() = runTest {
        assertFailsWith<VaultLockedException> { repository.edit { it.createGroup(ROOT, "x") } }
    }

    @Test
    fun anEncryptionFailureIsReportedWithoutTouchingTheFile() = runTest {
        unlock()
        session.prepareFailure = VaultSaveException.Reason.EncryptionFailed
        val outcome = repository.edit { it.createGroup(ROOT, "x") }
        assertEquals(SaveFailure.EncryptionFailed, (outcome as EditOutcome.NotSaved).failure)
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun lockingWithUnsavedChangesKeepsThemForTheNextUnlock() = runTest {
        unlock()
        writer.failure = SaveFailure.Unavailable
        repository.edit { it.createGroup(ROOT, "x") }
        repository.lock()
        assertFalse(repository.hasUnsavedChanges.value)
        assertTrue(copies.unsaved.containsKey(FINGERPRINT))

        unlock()
        assertTrue(repository.hasRecoveredCopy.value)
        assertFalse(copies.unsaved.containsKey(FINGERPRINT))
        assertContentEquals("encrypted-1".toByteArray(), copies.recovered(FINGERPRINT))

        repository.discardRecoveredCopy()
        assertFalse(repository.hasRecoveredCopy.value)
        assertNull(copies.recovered(FINGERPRINT))
    }

    @Test
    fun aRecoveredCopySurvivesTheNextSessionsOwnSave() = runTest {
        unlock()
        writer.failure = SaveFailure.Unavailable
        repository.edit { it.createGroup(ROOT, "lost") }
        repository.lock()
        unlock()
        writer.failure = null
        repository.edit { it.createGroup(ROOT, "kept") }
        assertTrue(repository.hasRecoveredCopy.value)
        assertTrue(copies.hasRecovered(FINGERPRINT))
    }

    @Test
    fun discardingOnLockDropsTheUnsavedCopy() = runTest {
        unlock()
        writer.failure = SaveFailure.Unavailable
        repository.edit { it.createGroup(ROOT, "x") }
        repository.lock(discardChanges = true)
        assertTrue(copies.unsaved.isEmpty())
        unlock()
        assertFalse(repository.hasRecoveredCopy.value)
    }

    @Test
    fun aReadOnlyGrantBlocksSavingUntilTheFileIsPickedAgain() = runTest {
        files.writable = false
        unlock()
        val state = repository.state.value as VaultState.Unlocked
        assertFalse(state.canEdit)
        assertFalse(state.writeGranted)
        val outcome = repository.edit { it.createGroup(ROOT, "x") }
        assertEquals(SaveFailure.NotWritable, (outcome as EditOutcome.NotSaved).failure)
        assertTrue(writer.written.isEmpty())

        assertFalse(repository.regrantWriteAccess("content://other/vault.kdbx"))
        files.writable = true
        assertTrue(repository.regrantWriteAccess(VAULT_URI))
        assertTrue((repository.state.value as VaultState.Unlocked).canEdit)
        assertTrue(VAULT_URI in files.writePersisted)
    }

    @Test
    fun aVaultTheCoreCannotEditIsNeverEditable() = runTest {
        core.editable = false
        unlock()
        val state = repository.state.value as VaultState.Unlocked
        assertFalse(state.canEdit)
        assertTrue(state.writeGranted)
        assertEquals(SaveFailure.NotWritable, (repository.edit { it.createGroup(ROOT, "x") } as EditOutcome.NotSaved).failure)
    }

    @Test
    fun saveACopyWritesTheEncryptedSessionAndNothingElseChanges() = runTest {
        unlock()
        writer.failure = SaveFailure.Unavailable
        repository.edit { it.createGroup(ROOT, "x") }
        assertEquals(CopyOutcome.Written, repository.saveCopyTo("content://new/copy.kdbx"))
        assertContentEquals("encrypted-1".toByteArray(), writer.copies.single().second)
        assertTrue(repository.hasUnsavedChanges.value)
        writer.copyFailure = true
        assertEquals(CopyOutcome.Failed, repository.saveCopyTo("content://new/copy2.kdbx"))
    }

    @Test
    fun theRecoveredCopyCanBeSavedElsewhere() = runTest {
        copies.recovered[FINGERPRINT] = "recovered".toByteArray()
        unlock()
        assertTrue(repository.hasRecoveredCopy.value)
        assertEquals(CopyOutcome.Written, repository.saveRecoveredCopyTo("content://new/r.kdbx"))
        assertContentEquals("recovered".toByteArray(), writer.copies.single().second)
    }

    @Test
    fun anInterruptedWriteOffersTheJournaledVersionWhenTheFileIsDamaged() = runTest {
        copies.pending[VAULT_URI] = "complete-new-version".toByteArray()
        copies.baselines[VAULT_URI] = "some-older-baseline"
        core.failure = VaultOpenException.Reason.Damaged
        assertEquals(UnlockResult.Failure(UnlockError.DamagedRestorable), repository.unlock("x".toByteArray()))

        assertEquals(RestoreOutcome.Restored, repository.restoreInterruptedWrite())
        assertContentEquals("complete-new-version".toByteArray(), writer.restored)
        assertNull(copies.pendingWrite(VAULT_URI))
    }

    @Test
    fun aDamagedFileWithoutAJournalIsJustDamaged() = runTest {
        core.failure = VaultOpenException.Reason.Damaged
        assertEquals(UnlockResult.Failure(UnlockError.Damaged), repository.unlock("x".toByteArray()))
        assertEquals(RestoreOutcome.NothingToRestore, repository.restoreInterruptedWrite())
    }

    @Test
    fun aFailedRestoreIsReported() = runTest {
        copies.pending[VAULT_URI] = "complete".toByteArray()
        writer.restoreFailure = SaveFailure.NotWritable
        assertEquals(RestoreOutcome.Failed(SaveFailure.NotWritable), repository.restoreInterruptedWrite())
        assertNotNullPending()
    }

    private fun assertNotNullPending() = assertTrue(copies.pending.containsKey(VAULT_URI))

    @Test
    fun aJournalThatIsAlreadyFinishedOrNeverStartedIsCleanedAtUnlock() = runTest {
        copies.pending[VAULT_URI] = "pending".toByteArray()
        copies.baselines[VAULT_URI] = FileVersion.of(files.content).sha256
        unlock()
        assertNull(copies.pendingWrite(VAULT_URI))
    }

    @Test
    fun aStaleJournalIsDroppedOnceTheFileOpensNormally() = runTest {
        copies.pending[VAULT_URI] = "pending".toByteArray()
        copies.baselines[VAULT_URI] = "someone-elses-baseline"
        unlock()
        assertNull(copies.pendingWrite(VAULT_URI))
    }

    @Test
    fun unlockedSessionsAreNotEditedBehindTheMutexAndSavesRunOneAtATime() = runTest {
        unlock()
        val gate = CompletableDeferred<Unit>()
        writer.gate = gate
        val first = async { repository.edit { it.createGroup(ROOT, "one") } }
        val second = async { repository.edit { it.createGroup(ROOT, "two") } }
        runCurrent()
        assertTrue(writer.written.size <= 1)
        gate.complete(Unit)
        first.await()
        second.await()
        assertEquals(2, writer.written.size)
        assertEquals(listOf("create-group:one", "create-group:two"), session.operations)
    }

    @Test
    fun progressShowsOnlyForSavesLongerThanTheDelay() = runTest(StandardTestDispatcher()) {
        unlock()
        val seen = mutableListOf<Boolean>()
        val watcher = launch(UnconfinedTestDispatcher(testScheduler)) { repository.saveInProgress.toList(seen) }
        repository.edit { it.createGroup(ROOT, "quick") }
        runCurrent()
        assertFalse(true in seen)

        val gate = CompletableDeferred<Unit>()
        writer.gate = gate
        val slow = async { repository.edit { it.createGroup(ROOT, "slow") } }
        runCurrent()
        advanceTimeBy(400)
        assertFalse(repository.saveInProgress.value)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(repository.saveInProgress.value)
        gate.complete(Unit)
        slow.await()
        assertFalse(repository.saveInProgress.value)
        watcher.cancel()
    }

    @Test
    fun aSaveFinishingAfterALockDoesNotTouchTheNextSession() = runTest {
        unlock()
        val gate = CompletableDeferred<Unit>()
        writer.gate = gate
        val pending = async { repository.edit { it.createGroup(ROOT, "x") } }
        runCurrent()
        repository.lock()
        gate.complete(Unit)
        assertIs<EditOutcome.Saved<GroupId>>(pending.await())
        assertEquals(VaultState.Locked, repository.state.value)
        assertFalse(repository.hasUnsavedChanges.value)
    }

    @Test
    fun theBackupGoesToTheVaultsOwnSlot() = runTest {
        unlock()
        repository.edit { it.createGroup(ROOT, "x") }
        assertTrue(copies.backups.containsKey(FINGERPRINT))
        assertEquals(setOf(FINGERPRINT), copies.backups.keys)
    }
}
