package app.vazie.keep.entry

import app.vazie.keep.testing.testRepository
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.ROOT
import app.vazie.keep.data.SaveFailure
import kotlinx.coroutines.flow.first
import androidx.lifecycle.SavedStateHandle
import app.vazie.keep.testing.FakeClipboard
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.MAIL_PASSWORD
import app.vazie.keep.testing.PLUS_ACCESS
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
class EntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val repository = testRepository(core, FakeFileAccess(), InMemoryReferenceStore())
    private val clipboard = FakeClipboard()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun viewModel(): EntryViewModel {
        repository.unlock("x".toByteArray())
        return EntryViewModel(SavedStateHandle(mapOf(ENTRY_ID_ARG to MAIL.value)), repository, clipboard, background = dispatcher)
    }

    private suspend fun totpViewModel(allowed: Boolean = true, now: Long = 1_000_000_000L): EntryViewModel {
        repository.unlock("x".toByteArray())
        val session = core.sessions.single() as FakeVaultSession
        session.entries.replaceAll { if (it.id == MAIL) it.copy(hasTotp = true) else it }
        session.totpCodes[MAIL] = "123456"
        return EntryViewModel(
            SavedStateHandle(mapOf(ENTRY_ID_ARG to MAIL.value)),
            repository,
            clipboard,
            background = dispatcher,
            access = { if (allowed) PLUS_ACCESS else app.vazie.keep.core.KeepAccess.Free },
            nowSeconds = { now },
        )
    }

    private fun EntryViewModel.totp() = (state.value as EntryUiState.Loaded).totp

    @Test
    fun anEntryWithoutACodeSetupHasNoTotp() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()

        assertEquals(TotpState.None, (vm.state.value as EntryUiState.Loaded).totp)
    }

    @Test
    fun aKeepPlusPersonSeesTheCodeAndTheTimeLeft() = runTest(dispatcher) {
        val vm = totpViewModel(now = 1_000_000_000L)
        runCurrent()

        val active = assertIs<TotpState.Active>(vm.totp())
        assertEquals("123456", active.code)
        assertEquals(30, active.period)
        assertEquals((30 - 1_000_000_000L % 30).toInt(), active.secondsRemaining)
        assertFalse("123456" in vm.state.value.toString())
        vm.setTotpVisible(false)
    }

    @Test
    fun withoutKeepPlusTheCoreRefusesAndTheCardIsLocked() = runTest(dispatcher) {
        val vm = totpViewModel(allowed = false)
        runCurrent()

        assertEquals(TotpState.Locked, vm.totp())
        assertFalse("123456" in vm.state.value.toString())
    }

    @Test
    fun theCodeTicksEverySecondAndStopsWhenTheScreenIsHidden() = runTest(dispatcher) {
        var now = 1_000_000_000L
        repository.unlock("x".toByteArray())
        val session = core.sessions.single() as FakeVaultSession
        session.entries.replaceAll { if (it.id == MAIL) it.copy(hasTotp = true) else it }
        session.totpCodes[MAIL] = "654321"
        val vm = EntryViewModel(SavedStateHandle(mapOf(ENTRY_ID_ARG to MAIL.value)), repository, clipboard, background = dispatcher, access = { PLUS_ACCESS }, nowSeconds = { now })
        runCurrent()
        val before = assertIs<TotpState.Active>(vm.totp()).secondsRemaining

        now += 1
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(before - 1, assertIs<TotpState.Active>(vm.totp()).secondsRemaining)

        vm.setTotpVisible(false)
        runCurrent()
        assertEquals(TotpState.Waiting, vm.totp())
        now += 5
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(TotpState.Waiting, vm.totp())

        vm.setTotpVisible(true)
        runCurrent()
        assertIs<TotpState.Active>(vm.totp())
        vm.setTotpVisible(false)
    }

    @Test
    fun copyingTheCodePutsItOnTheClipboardWithTheUsualClearTimer() = runTest(dispatcher) {
        val vm = totpViewModel()
        runCurrent()

        vm.copyTotp()
        runCurrent()

        assertEquals(listOf("123456"), clipboard.copied)
        assertEquals(EntryEvent.Copied(30), vm.events.first())
        vm.setTotpVisible(false)
    }

    @Test
    fun lockingTheVaultDropsTheCode() = runTest(dispatcher) {
        val vm = totpViewModel()
        runCurrent()

        repository.lock()
        runCurrent()

        assertEquals(EntryUiState.Locked, vm.state.value)
    }

    private fun EntryViewModel.revealed() = (state.value as EntryUiState.Loaded).revealed

    @Test
    fun loadsWithThePasswordHidden() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        val loaded = assertIs<EntryUiState.Loaded>(vm.state.value)
        assertNull(loaded.revealed)
        assertFalse(MAIL_PASSWORD in loaded.toString())
    }

    @Test
    fun revealReturnsOneSecretAndClosesTheSecretValue() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.reveal(SecretKey.Password)
        runCurrent()
        assertEquals(MAIL_PASSWORD, vm.revealed()?.text)
        assertFalse(MAIL_PASSWORD in vm.state.value.toString())
        val session = core.sessions.single() as FakeVaultSession
        assertTrue(session.revealed.single().isClosed)
    }

    @Test
    fun hideClearsTheRevealedValue() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.reveal(SecretKey.Password)
        runCurrent()
        vm.hide()
        assertNull(vm.revealed())
    }

    @Test
    fun revealHidesItselfAfterTheTimeout() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.reveal(SecretKey.Password)
        runCurrent()
        advanceTimeBy(REVEAL_DURATION - 1.seconds)
        runCurrent()
        assertEquals(MAIL_PASSWORD, vm.revealed()?.text)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertNull(vm.revealed())
    }

    @Test
    fun backgroundAndLockHideImmediately() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.reveal(SecretKey.Password)
        runCurrent()
        repository.requestHideSecrets()
        runCurrent()
        assertNull(vm.revealed())

        vm.reveal(SecretKey.Password)
        runCurrent()
        repository.lock()
        runCurrent()
        assertEquals(EntryUiState.Locked, vm.state.value)
    }

    @Test
    fun copyGoesToTheClipboardAndAnnouncesWhenTheSystemDoesNot() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.copy(SecretKey.Password)
        runCurrent()
        assertEquals(listOf(MAIL_PASSWORD), clipboard.copied)
        assertEquals(EntryEvent.Copied(30), vm.events.first())
        assertNull(vm.revealed())
    }

    @Test
    fun neverClearingClipboardAlwaysWarnsEvenWhenTheSystemConfirms() = runTest(dispatcher) {
        val warned = FakeClipboard(systemConfirms = true, clearsAfter = null)
        repository.unlock("x".toByteArray())
        val vm = EntryViewModel(SavedStateHandle(mapOf(ENTRY_ID_ARG to MAIL.value)), repository, warned, background = dispatcher)
        runCurrent()
        vm.copy(SecretKey.Password)
        runCurrent()
        assertEquals(EntryEvent.Copied(null), vm.events.first())
    }

    @Test
    fun readAloudNeedsConfirmationOnce() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.requestReadAloud(SecretKey.Password)
        assertEquals(SecretKey.Password, (vm.state.value as EntryUiState.Loaded).confirmReadAloud)
        assertNull(vm.revealed())
        vm.confirmReadAloud(true)
        runCurrent()
        assertEquals(true, vm.revealed()?.announce)
        vm.hide()
        vm.requestReadAloud(SecretKey.Password)
        runCurrent()
        assertEquals(true, vm.revealed()?.announce)
    }

    @Test
    fun declinedReadAloudRevealsNothing() = runTest(dispatcher) {
        val vm = viewModel()
        runCurrent()
        vm.requestReadAloud(SecretKey.Password)
        vm.confirmReadAloud(false)
        runCurrent()
        assertNull(vm.revealed())
        assertNull((vm.state.value as EntryUiState.Loaded).confirmReadAloud)
    }

    private val writer = FakeDocumentWriter()
    private val exportFiles = FakeFileAccess()
    private val editing = testRepository(core, exportFiles, InMemoryReferenceStore(), writer, background = dispatcher)

    private suspend fun editable(): EntryViewModel {
        editing.unlock("x".toByteArray())
        return EntryViewModel(SavedStateHandle(mapOf(ENTRY_ID_ARG to MAIL.value)), editing, clipboard, background = dispatcher)
    }

    @Test
    fun deletingTheEntryWritesTheVaultAndLeavesTheScreen() = runTest(dispatcher) {
        val vm = editable()
        runCurrent()
        vm.delete(permanent = true)
        runCurrent()
        assertEquals(EntryEvent.Deleted, vm.events.first())
        assertEquals(listOf("delete-entry:mail:true"), (core.sessions.single() as FakeVaultSession).operations)
        assertEquals(1, writer.written.size)
    }

    @Test
    fun aFailedDeleteIsReportedAndTheEntryStaysDeletedInTheSession() = runTest(dispatcher) {
        val vm = editable()
        runCurrent()
        writer.failure = SaveFailure.ChangedExternally
        vm.delete(permanent = false)
        runCurrent()
        assertEquals(SaveFailure.ChangedExternally, vm.saveFlow.problem.value)
        assertTrue(editing.hasUnsavedChanges.value)
    }

    @Test
    fun movingTheEntryOffersEveryOtherGroup() = runTest(dispatcher) {
        val vm = editable()
        runCurrent()
        val loaded = assertIs<EntryUiState.Loaded>(vm.state.value)
        assertEquals(listOf(ROOT), loaded.moveTargets.map { it.id })
        assertFalse(loaded.deletesToBin)
        vm.move(ROOT)
        runCurrent()
        assertEquals(EntryEvent.Moved, vm.events.first())
        assertEquals(listOf("move:mail:root"), (core.sessions.single() as FakeVaultSession).operations)
    }

    @Test
    fun theEntryReloadsAfterAnEdit() = runTest(dispatcher) {
        val vm = editable()
        runCurrent()
        editing.edit { s -> s.updateEntry(MAIL, app.vazie.keep.core.EntryDraft("Renamed", "u", ByteArray(0), "", "", emptyList(), emptyList())) }
        runCurrent()
        val title = assertIs<EntryUiState.Loaded>(vm.state.value).detail.summary.title
        assertEquals(app.vazie.keep.core.EntryText.Plain("Renamed"), title)
    }

    @Test
    fun theEditControlsFollowWriteAccess() = runTest(dispatcher) {
        val vm = editable()
        runCurrent()
        assertTrue(vm.canEdit.value)
    }

    @Test
    fun theStarTogglesTheFavourite() = runTest(dispatcher) {
        val vm = editable()
        runCurrent()
        val session = core.sessions.single() as FakeVaultSession
        vm.toggleFavorite()
        runCurrent()
        assertTrue(session.entries.single { it.id == MAIL }.favorite)
        assertTrue(assertIs<EntryUiState.Loaded>(vm.state.value).detail.summary.favorite)
        vm.toggleFavorite()
        runCurrent()
        assertFalse(session.entries.single { it.id == MAIL }.favorite)
        assertEquals(2, writer.written.size)
    }

    @Test
    fun anAttachmentIsWrittenToThePickedDocument() = runTest(dispatcher) {
        val vm = editable()
        runCurrent()
        val session = core.sessions.single() as FakeVaultSession
        session.attachments[MAIL] = linkedMapOf("notes.txt" to "hello".toByteArray())
        vm.exportAttachment("notes.txt", "content://export/notes.txt")
        runCurrent()
        assertEquals(EntryEvent.AttachmentExported(true), vm.events.first())
        assertEquals("hello", exportFiles.newFiles["content://export/notes.txt"]?.decodeToString())
        vm.exportAttachment("missing.txt", "content://export/missing.txt")
        runCurrent()
        assertEquals(EntryEvent.AttachmentExported(false), vm.events.first())
    }
}
