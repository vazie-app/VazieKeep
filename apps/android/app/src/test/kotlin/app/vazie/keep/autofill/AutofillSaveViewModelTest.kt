package app.vazie.keep.autofill

import app.vazie.keep.biometric.MaterialResult
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.core.SecretValue
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultFileException
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReference
import app.vazie.keep.testing.FINGERPRINT
import app.vazie.keep.testing.FakeBiometricSource
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.KEY_FILE_URI
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.MAIL_PASSWORD
import app.vazie.keep.testing.MemoryAssociations
import app.vazie.keep.testing.MemoryIndexStore
import app.vazie.keep.testing.VAULT_URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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

@OptIn(ExperimentalCoroutinesApi::class)
class AutofillSaveViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val copies = InMemoryCopyStore()
    private val references = InMemoryReferenceStore()
    private val writer = FakeDocumentWriter()
    private val biometrics = FakeBiometricSource()
    private val associations = MemoryAssociations()
    private val handoff = SaveHandoff()
    private val prompt = PromptText("t", "s", "n")
    private var saved = 0
    private val mailEntry = IndexedEntry(MAIL.value, "Example Mail", "alice@example.org", "https://mail.example.org")
    private val web = RequestOrigin.Web(WebOrigin("https", "mail.example.org", null))
    private val session get() = core.sessions.single() as FakeVaultSession

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun credential(username: String = "alice@example.org", password: String = "new-pass", origin: RequestOrigin = web) =
        SubmittedCredential(origin, FormKind.Login, username, SecretValue(password.toByteArray()))

    private fun viewModel(
        credential: SubmittedCredential? = credential(),
        entries: List<IndexedEntry> = listOf(mailEntry),
        fingerprint: String = FINGERPRINT,
    ): AutofillSaveViewModel {
        val token = credential?.let(handoff::put) ?: "missing"
        val executor = SaveExecutor(files, writer, copies, { }, background = dispatcher)
        return AutofillSaveViewModel(
            token = token,
            handoff = handoff,
            references = references,
            index = MemoryIndexStore(AutofillIndex(fingerprint, entries)),
            associations = associations,
            opener = VaultOpener(core, files, copies),
            biometrics = biometrics,
            matcher = CredentialMatcher(core),
            executor = executor,
            appLabel = { "Example App" },
            onSaved = { saved++ },
            background = dispatcher,
        )
    }

    private suspend fun AutofillSaveViewModel.typePassword(text: String = "master") {
        password.edit { replace(0, length, text) }
        unlockWithPassword()
    }

    private fun confirmOf(vm: AutofillSaveViewModel) = assertIs<SaveUiState.Confirm>(vm.state.value)

    @Test
    fun aKnownSiteAndUsernameOffersAnUpdateAndSavesItAfterUnlock() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        val confirm = confirmOf(vm)
        assertEquals("mail.example.org", confirm.subject)
        assertEquals(SaveChoice.Update(mailEntry), confirm.selected)
        vm.confirm()
        advanceUntilIdle()
        assertIs<SaveUiState.Unlock>(vm.state.value)
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(SaveUiState.Done(SaveResult.Updated(MAIL.value)), vm.state.value)
        assertEquals("new-pass", session.drafts.single().password)
        assertEquals(1, writer.written.size)
        assertEquals(1, saved)
        assertTrue(session.isClosed)
        assertTrue(associations.list.isEmpty())
    }

    @Test
    fun aNewUsernameOffersCreateWithTheExistingEntryAsAnAlternative() = runTest(dispatcher) {
        val vm = viewModel(credential(username = "carol"))
        advanceUntilIdle()
        val confirm = confirmOf(vm)
        assertEquals(SaveChoice.Create, confirm.selected)
        assertEquals(listOf(mailEntry), (confirm.plan as SavePlan.Create).alternatives)
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertIs<SaveResult.Created>((vm.state.value as SaveUiState.Done).result)
        assertEquals(listOf(app.vazie.keep.testing.ROOT to "mail.example.org"), session.created)
        assertEquals("https://mail.example.org", session.drafts.single().url)
    }

    @Test
    fun theChosenAlternativeIsUpdatedInsteadOfCreating() = runTest(dispatcher) {
        val vm = viewModel(credential(username = "carol"))
        advanceUntilIdle()
        vm.select(SaveChoice.Update(mailEntry))
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(SaveResult.Updated(MAIL.value), (vm.state.value as SaveUiState.Done).result)
        assertTrue(session.created.isEmpty())
        assertEquals("alice@example.org", session.drafts.single().username)
    }

    @Test
    fun severalCandidatesAreNotGuessedAndNothingProceedsUntilOneIsPicked() = runTest(dispatcher) {
        val other = mailEntry.copy(entryId = "second", title = "Second")
        val vm = viewModel(entries = listOf(mailEntry, other))
        advanceUntilIdle()
        val confirm = confirmOf(vm)
        assertIs<SavePlan.Choose>(confirm.plan)
        assertNull(confirm.selected)
        vm.confirm()
        advanceUntilIdle()
        assertIs<SaveUiState.Confirm>(vm.state.value)
        assertEquals(0, biometrics.prompts)
        vm.select(SaveChoice.Update(other))
        vm.confirm()
        advanceUntilIdle()
        assertIs<SaveUiState.Unlock>(vm.state.value)
    }

    @Test
    fun theFingerprintIsAskedOnlyAfterSavingWasConfirmed() = runTest(dispatcher) {
        biometrics.enabled = true
        biometrics.results += MaterialResult.Obtained("M:pw".toByteArray(), biometrics.record)
        val vm = viewModel()
        vm.start(prompt)
        advanceUntilIdle()
        assertIs<SaveUiState.Confirm>(vm.state.value)
        assertEquals(0, biometrics.prompts)
        vm.confirm()
        advanceUntilIdle()
        assertEquals(1, biometrics.prompts)
        assertEquals("M:pw", core.seen.single().material)
        assertIs<SaveUiState.Done>(vm.state.value)
    }

    @Test
    fun cancellingFromThePreviewSavesNothingAndWipesThePassword() = runTest(dispatcher) {
        val held = credential()
        val vm = viewModel(held)
        advanceUntilIdle()
        vm.cancel()
        assertEquals(SaveUiState.Cancelled, vm.state.value)
        assertTrue(held.password.isClosed)
        assertTrue(writer.written.isEmpty())
        assertTrue(core.sessions.isEmpty())
        assertEquals(0, saved)
    }

    @Test
    fun cancellingAtTheUnlockStepKeepsNoSession() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.password.edit { replace(0, length, "typed") }
        vm.cancel()
        assertEquals("", vm.password.text.toString())
        assertTrue(core.sessions.isEmpty())
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun aMissingTokenMeansTheSaveExpired() = runTest(dispatcher) {
        val vm = viewModel(credential = null)
        advanceUntilIdle()
        assertEquals(AuthProblem.Expired, assertIs<SaveUiState.Failed>(vm.state.value).problem)
    }

    @Test
    fun aVaultChangedElsewhereIsReportedAndNotOverwritten() = runTest(dispatcher) {
        writer.failure = SaveFailure.ChangedExternally
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.VaultChangedElsewhere, assertIs<SaveUiState.Failed>(vm.state.value).problem)
        assertTrue(writer.written.isEmpty())
        assertEquals(0, saved)
        assertTrue(session.isClosed)
    }

    @Test
    fun aFailedWriteIsNotReportedAsSuccess() = runTest(dispatcher) {
        writer.failure = SaveFailure.InsufficientSpace
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.CouldNotSave, assertIs<SaveUiState.Failed>(vm.state.value).problem)
        assertEquals(0, saved)
        assertTrue(associations.list.isEmpty())
    }

    @Test
    fun aKeyFileVaultReadsTheKeyFileAndAMissingOneSendsThePersonToTheApp() = runTest(dispatcher) {
        references.reference = VaultReference(VAULT_URI, "vault.kdbx", 0, KEY_FILE_URI, "my.keyx")
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        assertEquals("my.keyx", assertIs<SaveUiState.Unlock>(vm.state.value).keyFileName)
        vm.typePassword()
        advanceUntilIdle()
        assertEquals("key-file-bytes", core.seen.single().keyFile)
        assertIs<SaveUiState.Done>(vm.state.value)

        files.keyFileFailure = VaultFileException.Unavailable()
        val second = viewModel()
        advanceUntilIdle()
        second.confirm()
        advanceUntilIdle()
        second.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.KeyFileUnavailable, assertIs<SaveUiState.Failed>(second.state.value).problem)
    }

    @Test
    fun aJournalThatNeedsRestoringRefusesTheSave() = runTest(dispatcher) {
        copies.pending[VAULT_URI] = "new".toByteArray()
        copies.baselines[VAULT_URI] = "old"
        core.failure = VaultOpenException.Reason.Damaged
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.RestoreNeeded, assertIs<SaveUiState.Failed>(vm.state.value).problem)
        assertTrue(writer.written.isEmpty())
        assertTrue(writer.restored == null)
    }

    @Test
    fun aWrongMasterPasswordClearsTheFieldAndRetries() = runTest(dispatcher) {
        core.accepts = { password, _ -> password == "right" }
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword("wrong")
        advanceUntilIdle()
        assertEquals(UnlockMessage.WrongPassword, assertIs<SaveUiState.Unlock>(vm.state.value).message)
        assertEquals("", vm.password.text.toString())
        vm.focusRequests.first()
        vm.typePassword("right")
        advanceUntilIdle()
        assertIs<SaveUiState.Done>(vm.state.value)
    }

    @Test
    fun aSwappedVaultIsNotWrittenTo() = runTest(dispatcher) {
        core.fingerprint = "swapped"
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.VaultChanged, assertIs<SaveUiState.Failed>(vm.state.value).problem)
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun theSamePasswordIsDoneWithoutAWrite() = runTest(dispatcher) {
        val vm = viewModel(credential(password = MAIL_PASSWORD))
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(SaveUiState.Done(SaveResult.Unchanged(MAIL.value)), vm.state.value)
        assertTrue(writer.written.isEmpty())
        assertEquals(0, saved)
    }

    @Test
    fun aNativeAppCredentialIsApprovedForItsPackageAndBoundToTheCertificateAtTheFirstFill() = runTest(dispatcher) {
        val app = RequestOrigin.App("com.example.app")
        val vm = viewModel(credential(username = "dana", origin = app))
        advanceUntilIdle()
        assertEquals("Example App", confirmOf(vm).subject)
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        val created = (assertIs<SaveUiState.Done>(vm.state.value).result as SaveResult.Created).entryId
        assertEquals(listOf(AppAssociation("com.example.app", "", FINGERPRINT, created)), associations.list)
        assertEquals("", session.drafts.single().url)
        assertEquals("Example App", session.drafts.single().title)
    }

    @Test
    fun anExistingBoundApprovalIsNotDowngradedToAPendingOne() = runTest(dispatcher) {
        val bound = AppAssociation("com.example.app", "CERT", FINGERPRINT, MAIL.value)
        associations.add(bound)
        val vm = viewModel(credential(username = "alice@example.org", origin = RequestOrigin.App("com.example.app")))
        advanceUntilIdle()
        assertEquals(SaveChoice.Update(mailEntry), confirmOf(vm).selected)
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertIs<SaveUiState.Done>(vm.state.value)
        assertEquals(listOf(bound), associations.list)
    }

    @Test
    fun aBrowserSaveIsNeverBoundToTheBrowserPackage() = runTest(dispatcher) {
        val vm = viewModel(credential(username = "dana"))
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertIs<SaveUiState.Done>(vm.state.value)
        assertTrue(associations.list.isEmpty())
    }

    @Test
    fun thePasswordIsShownOnlyAfterAnExplicitTapAndHiddenAgain() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertNull(confirmOf(vm).shown)
        vm.toggleShown()
        assertEquals("new-pass", confirmOf(vm).shown)
        vm.hide()
        assertNull(confirmOf(vm).shown)
        vm.toggleShown()
        vm.toggleShown()
        assertNull(confirmOf(vm).shown)
        assertFalse("new-pass" in confirmOf(vm).toString().replace("shown=new-pass", ""))
    }

    @Test
    fun progressAppearsOnlyForASlowSave() = runTest(dispatcher) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        writer.gate = gate
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirm()
        advanceUntilIdle()
        vm.typePassword()
        advanceTimeBy(100)
        assertEquals(SaveUiState.Saving(slow = false), vm.state.value)
        advanceTimeBy(600)
        assertEquals(SaveUiState.Saving(slow = true), vm.state.value)
        gate.complete(Unit)
        advanceUntilIdle()
        assertIs<SaveUiState.Done>(vm.state.value)
    }

    @Test
    fun repeatedSavesLeaveNoSessionOpen() = runTest(dispatcher) {
        repeat(20) {
            val vm = viewModel(credential(password = "pass-$it"))
            advanceUntilIdle()
            vm.confirm()
            advanceUntilIdle()
            vm.typePassword()
            advanceUntilIdle()
            assertIs<SaveUiState.Done>(vm.state.value)
        }
        assertEquals(20, core.sessions.size)
        assertTrue(core.sessions.all { it.isClosed })
        assertEquals(0, handoff.size())
    }
}
