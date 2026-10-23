package app.vazie.keep.history

import androidx.lifecycle.SavedStateHandle
import app.vazie.keep.core.KeepAccess
import app.vazie.keep.entry.ENTRY_ID_ARG
import app.vazie.keep.entry.SecretKey
import app.vazie.keep.testing.FakeClipboard
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.FakeVersion
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.PLUS_ACCESS
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val writer = FakeDocumentWriter()
    private val repository = testRepository(core, FakeFileAccess(), InMemoryReferenceStore(), writer, background = dispatcher)
    private val clipboard = FakeClipboard()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun session() = core.sessions.single() as FakeVaultSession

    private suspend fun viewModel(plus: Boolean = true): HistoryViewModel {
        repository.unlock("x".toByteArray())
        session().versions[MAIL] = mutableListOf(FakeVersion(0, 1_700_000_000, "first"), FakeVersion(1, 1_700_100_000, "second"))
        return HistoryViewModel(
            SavedStateHandle(mapOf(ENTRY_ID_ARG to MAIL.value)),
            repository,
            clipboard,
            background = dispatcher,
            revealDuration = 15.seconds,
            access = { if (plus) PLUS_ACCESS else KeepAccess.Free },
        )
    }

    private fun HistoryViewModel.loaded() = assertIs<HistoryUiState.Loaded>(state.value)

    @Test
    fun withoutKeepPlusTheHistoryIsNotRead() = runTest(dispatcher) {
        val vm = viewModel(plus = false)
        runCurrent()

        assertEquals(HistoryUiState.NeedsPlus, vm.state.value)
        assertTrue(session().revealed.isEmpty())
    }

    @Test
    fun versionsAreListedNewestFirst() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        assertEquals(listOf(1, 0), vm.loaded().versions.map { it.index })
        assertTrue(vm.loaded().canRestore)
        assertNull(vm.loaded().opened)
    }

    @Test
    fun anOpenedVersionShowsItsOwnPasswordAndHidesItAgain() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.open(0)
        runCurrent()
        assertEquals(0, vm.loaded().opened?.version?.index)

        vm.reveal(SecretKey.Password)
        runCurrent()
        assertEquals("first", vm.loaded().opened?.revealed?.text)

        advanceTimeBy(16.seconds)
        runCurrent()
        assertNull(vm.loaded().opened?.revealed)
    }

    @Test
    fun copyingTakesTheVersionsPasswordNotTheCurrentOne() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.open(1)
        runCurrent()

        vm.copy(SecretKey.Password)
        runCurrent()
        assertEquals("second", clipboard.copied.single())
    }

    @Test
    fun goingBackClosesTheVersionBeforeTheScreen() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.open(1)
        runCurrent()
        vm.reveal(SecretKey.Password)
        runCurrent()

        assertTrue(vm.closeVersion())
        assertNull(vm.loaded().opened)
        assertFalse(vm.closeVersion())
    }

    @Test
    fun restoringAVersionSavesTheVaultAndLeavesTheScreen() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.open(0)
        runCurrent()

        vm.restore()
        runCurrent()

        assertEquals(listOf("restore:mail:0"), session().operations)
        assertEquals(HistoryEvent.Restored, vm.events.first())
        assertEquals(1, writer.written.size)
        assertFalse(repository.hasUnsavedChanges.value)
    }

    @Test
    fun lockingTheVaultDropsWhatWasShown() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.open(0)
        runCurrent()
        vm.reveal(SecretKey.Password)
        runCurrent()

        repository.lock()
        runCurrent()
        assertEquals(HistoryUiState.Locked, vm.state.value)
    }
}
