package app.vazie.keep.save

import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultState
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.ROOT
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SaveFlowTest {
    private val writer = FakeDocumentWriter()
    private val copies = InMemoryCopyStore()
    private val repository = testRepository(FakeKeepCore(), FakeFileAccess(), InMemoryReferenceStore(), writer, copies, background = Dispatchers.Unconfined)

    private suspend fun dirty() {
        repository.unlock("x".toByteArray())
        writer.failure = SaveFailure.Unavailable
        repository.edit { it.createGroup(ROOT, "x") }
    }

    @Test
    fun retryClearsTheProblemWhenTheSaveWorks() = runTest(StandardTestDispatcher()) {
        dirty()
        val flow = SaveFlow(repository, this)
        flow.report(SaveFailure.Unavailable)
        writer.failure = null
        var saved = false
        flow.retry { saved = true }
        runCurrent()
        assertNull(flow.problem.value)
        assertTrue(saved)
        assertFalse(repository.hasUnsavedChanges.value)
    }

    @Test
    fun retryReportsTheNewFailureWhenItStillFails() = runTest(StandardTestDispatcher()) {
        dirty()
        val flow = SaveFlow(repository, this)
        writer.failure = SaveFailure.NotWritable
        flow.retry()
        runCurrent()
        assertEquals(SaveFailure.NotWritable, flow.problem.value)
        assertTrue(repository.hasUnsavedChanges.value)
    }

    @Test
    fun reloadDiscardsTheChangesAndLocks() = runTest(StandardTestDispatcher()) {
        dirty()
        val flow = SaveFlow(repository, this)
        flow.report(SaveFailure.ChangedExternally)
        flow.reload()
        assertNull(flow.problem.value)
        assertEquals(VaultState.Locked, repository.state.value)
        assertTrue(copies.unsaved.isEmpty())
    }

    @Test
    fun saveACopyWritesTheSessionAndReportsTheResult() = runTest(StandardTestDispatcher()) {
        dirty()
        val flow = SaveFlow(repository, this)
        flow.report(SaveFailure.ProviderUnsupported)
        flow.saveCopy("content://new/copy.kdbx")
        runCurrent()
        assertNull(flow.problem.value)
        assertEquals(CopyResult.Saved, flow.copyResult.value)
        assertEquals(1, writer.copies.size)
        assertTrue(repository.hasUnsavedChanges.value)

        writer.copyFailure = true
        flow.saveCopy("content://new/copy2.kdbx")
        runCurrent()
        assertEquals(CopyResult.Failed, flow.copyResult.value)
        flow.copyShown()
        assertNull(flow.copyResult.value)
    }

    @Test
    fun cancellingThePickerKeepsTheDialogOpen() = runTest(StandardTestDispatcher()) {
        dirty()
        val flow = SaveFlow(repository, this)
        flow.report(SaveFailure.ProviderUnsupported)
        flow.saveCopy(null)
        assertEquals(SaveFailure.ProviderUnsupported, flow.problem.value)
    }
}
