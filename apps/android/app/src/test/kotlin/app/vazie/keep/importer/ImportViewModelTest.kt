package app.vazie.keep.importer

import app.vazie.keep.core.ImportException
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val writer = FakeDocumentWriter()
    private val repository = testRepository(core, files, InMemoryReferenceStore(), writer, background = dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun viewModel(): ImportViewModel {
        repository.unlock("x".toByteArray())
        files.attachmentFiles["content://export.csv"] = "one\ntwo\nthree\n".toByteArray()
        files.attachmentFiles["content://export.json"] = "{}".toByteArray()
        return ImportViewModel(core, repository, background = dispatcher)
    }

    private fun session() = core.sessions.single() as FakeVaultSession

    @Test
    fun aFileIsShownBeforeAnythingIsAdded() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFilePicked("content://export.csv")
        runCurrent()

        assertEquals(3, assertIs<ImportUiState.Preview>(vm.state.value).summary.entries)
        assertTrue(session().operations.isEmpty())
        assertTrue(core.importBytes!!.all { it == 0.toByte() }, "the plain-text export is wiped once it is read")
    }

    @Test
    fun confirmingAddsTheEntriesToTheRootAndSavesTheVault() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFilePicked("content://export.csv")
        runCurrent()
        vm.confirm()
        runCurrent()

        assertEquals(3, assertIs<ImportUiState.Done>(vm.state.value).outcome.entries)
        assertEquals(listOf("import:root:3"), session().operations)
        assertEquals(1, writer.written.size)
        assertTrue(core.importPlans.single().closed)
    }

    @Test
    fun cancellingDropsTheParsedExport() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFilePicked("content://export.csv")
        runCurrent()
        vm.dismiss()

        assertEquals(ImportUiState.Idle, vm.state.value)
        assertTrue(core.importPlans.single().closed)
        assertTrue(session().operations.isEmpty())
    }

    @Test
    fun aFileKeepCannotReadSaysWhy() = runTest(dispatcher) {
        val vm = viewModel()
        core.importFailure = ImportException.Reason.Encrypted
        vm.onFilePicked("content://export.json")
        runCurrent()
        assertEquals(ImportUiState.Failed(ImportProblem.Encrypted), vm.state.value)

        assertTrue(core.importBytes!!.all { it == 0.toByte() })

        vm.dismiss()
        vm.onFilePicked("content://gone.csv")
        runCurrent()
        assertEquals(ImportUiState.Failed(ImportProblem.Unreadable), vm.state.value)
    }

    @Test
    fun aRefusedImportLeavesTheVaultAlone() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFilePicked("content://export.csv")
        runCurrent()
        session().editFailure = VaultEditException.Reason.InvalidText
        vm.confirm()
        runCurrent()

        assertEquals(ImportUiState.Failed(ImportProblem.Failed), vm.state.value)
        assertTrue(writer.written.isEmpty())
        assertTrue(core.importPlans.single().closed)
    }

    @Test
    fun aFailedSaveIsReportedAndTheEntriesStayInTheSession() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFilePicked("content://export.csv")
        runCurrent()
        writer.failure = SaveFailure.ChangedExternally
        vm.confirm()
        runCurrent()

        assertIs<ImportUiState.Done>(vm.state.value)
        assertEquals(SaveFailure.ChangedExternally, vm.saveFlow.problem.value)
        assertTrue(repository.hasUnsavedChanges.value)
    }

    @Test
    fun lockingTheVaultDropsAPendingImport() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onFilePicked("content://export.csv")
        runCurrent()
        repository.lock()
        runCurrent()

        assertEquals(ImportUiState.Idle, vm.state.value)
        assertTrue(core.importPlans.single().closed)
    }
}
