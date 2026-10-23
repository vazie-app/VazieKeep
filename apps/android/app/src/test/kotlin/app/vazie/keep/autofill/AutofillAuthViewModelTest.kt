package app.vazie.keep.autofill

import androidx.lifecycle.ViewModel
import app.vazie.keep.biometric.BiometricAvailability
import app.vazie.keep.biometric.BiometricMaterialSource
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlockResult
import app.vazie.keep.biometric.MaterialResult
import app.vazie.keep.biometric.PromptText
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReference
import app.vazie.keep.testing.FINGERPRINT
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.KEY_FILE_URI
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.MAIL_PASSWORD
import app.vazie.keep.testing.VAULT_URI
import app.vazie.keep.data.VaultFileException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AutofillAuthViewModelTest {
    private class FakeBiometrics(var enabled: Boolean = false, var keyFileRequired: Boolean = false) : BiometricMaterialSource {
        val results = ArrayDeque<MaterialResult>()
        var prompts = 0
        var rejected = 0
        val record get() = BiometricRecord.Active(VAULT_URI, FINGERPRINT, "alias", keyFileRequired, ByteArray(0), ByteArray(0))

        override suspend fun refresh(): BiometricStatus =
            if (enabled) BiometricStatus.On(keyFileRequired) else BiometricStatus.Unavailable(BiometricAvailability.NoneEnrolled)

        override suspend fun obtainMaterial(text: PromptText): MaterialResult {
            prompts++
            return results.removeFirstOrNull() ?: MaterialResult.Cancelled
        }

        override suspend fun materialRejected(record: BiometricRecord.Active): BiometricUnlockResult {
            rejected++
            return BiometricUnlockResult.NeedsSetup
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val copies = InMemoryCopyStore()
    private val references = InMemoryReferenceStore()
    private val opener = VaultOpener(core, files, copies)
    private val biometrics = FakeBiometrics()
    private val matcher = CredentialMatcher(core)
    private val associations = FileAppAssociationStoreInMemory()
    private val prompt = PromptText("t", "s", "n")
    private val mailEntry = IndexedEntry(MAIL.value, "Example Mail", "alice@example.org", "https://mail.example.org")
    private var reindexed = 0
    private var callerCheck: CallerCheck = CallerCheck.Verified(SigningIdentity("CERT"))

    private class FileAppAssociationStoreInMemory : AppAssociationStore {
        var list = emptyList<AppAssociation>()

        override fun all() = list

        override fun add(association: AppAssociation) {
            list = list.filterNot { it.packageName == association.packageName && it.entryId == association.entryId } + association
        }

        override fun retain(fingerprint: String, existingEntryIds: Set<String>) = Unit

        override fun clear() {
            list = emptyList()
        }
    }

    private class MemoryIndex(var index: AutofillIndex?) : AutofillIndexStore {
        override fun load() = index

        override fun save(index: AutofillIndex) {
            this.index = index
        }

        override fun clear() {
            index = null
        }
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val web = RequestOrigin.Web(WebOrigin("https", "mail.example.org", null))

    private fun viewModel(
        mode: AuthRequest.Mode = AuthRequest.Mode.Select,
        origin: RequestOrigin = web,
        entry: IndexedEntry? = mailEntry,
        fingerprint: String = FINGERPRINT,
        indexFingerprint: String = FINGERPRINT,
        index: AutofillIndex? = AutofillIndex(indexFingerprint, listOfNotNull(entry, IndexedEntry("other", "Other", "o", "https://other.example"))),
    ) = AutofillAuthViewModel(
        request = AuthRequest(mode, if (mode == AuthRequest.Mode.Select) entry?.entryId else null, fingerprint, origin, null, null),
        references = references,
        index = MemoryIndex(index),
        associations = associations,
        opener = opener,
        biometrics = biometrics,
        matcher = matcher,
        caller = { callerCheck },
        reindex = { reindexed++ },
        background = dispatcher,
    )

    private val session get() = core.sessions.single() as FakeVaultSession

    private suspend fun AutofillAuthViewModel.typePassword(text: String = "secret") {
        password.edit { replace(0, length, text) }
        unlockWithPassword()
    }

    private fun payloadOf(vm: AutofillAuthViewModel) = assertIs<AuthUiState.Done>(vm.state.value).payload

    @Test
    fun aChosenEntryIsFilledWithItsPasswordAndOnlyThat() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        assertIs<AuthUiState.Unlock>(vm.state.value)
        vm.typePassword()
        advanceUntilIdle()
        val payload = payloadOf(vm)
        assertEquals("alice@example.org", payload.username)
        assertEquals(MAIL_PASSWORD, payload.password?.useChars { String(it) })
        assertEquals(1, session.revealed.size)
        assertTrue(session.isClosed)
        assertEquals("", vm.password.text.toString())
        payload.close()
        assertTrue(payload.password!!.isClosed)
    }

    @Test
    fun theSessionIsOpenedWithTheTypedPasswordAndWipedAfterwards() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword("hunter")
        advanceUntilIdle()
        assertEquals("hunter", core.seen.single().password)
        assertTrue(core.passwords.single().all { it == 0.toByte() })
    }

    @Test
    fun aWrongPasswordClearsTheFieldAndAsksForFocusAgain() = runTest(dispatcher) {
        core.accepts = { password, _ -> password == "right" }
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword("wrong")
        advanceUntilIdle()
        val unlock = assertIs<AuthUiState.Unlock>(vm.state.value)
        assertEquals(UnlockMessage.WrongPassword, unlock.message)
        assertFalse(unlock.busy)
        assertEquals("", vm.password.text.toString())
        vm.focusRequests.first()
        vm.typePassword("right")
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
    }

    @Test
    fun noFingerprintPromptUntilAnEntryIsChosen() = runTest(dispatcher) {
        biometrics.enabled = true
        val vm = viewModel(mode = AuthRequest.Mode.Search)
        vm.start(prompt)
        advanceUntilIdle()
        assertIs<AuthUiState.Choosing>(vm.state.value)
        assertEquals(0, biometrics.prompts)
        vm.choose(mailEntry)
        advanceUntilIdle()
        assertEquals(1, biometrics.prompts)
    }

    @Test
    fun theFingerprintReleasesTheStoredMaterialAndOpensTheVault() = runTest(dispatcher) {
        biometrics.enabled = true
        biometrics.results += MaterialResult.Obtained("M:pw".toByteArray(), biometrics.record)
        val vm = viewModel()
        vm.start(prompt)
        advanceUntilIdle()
        val payload = payloadOf(vm)
        assertEquals(MAIL_PASSWORD, payload.password?.useChars { String(it) })
        assertEquals("M:pw", core.seen.single().material)
        assertNull(core.seen.single().password)
        assertEquals(1, biometrics.prompts)
    }

    @Test
    fun cancellingTheFingerprintFallsBackToThePasswordWithoutAnError() = runTest(dispatcher) {
        biometrics.enabled = true
        biometrics.results += MaterialResult.Cancelled
        val vm = viewModel()
        vm.start(prompt)
        advanceUntilIdle()
        val unlock = assertIs<AuthUiState.Unlock>(vm.state.value)
        assertNull(unlock.message)
        assertTrue(unlock.biometric)
        assertFalse(unlock.busy)
        assertTrue(core.sessions.isEmpty())
    }

    @Test
    fun aFingerprintTheVaultRefusesInvalidatesTheSetupAndOffersThePassword() = runTest(dispatcher) {
        biometrics.enabled = true
        core.accepts = { _, _ -> false }
        biometrics.results += MaterialResult.Obtained("M:stale".toByteArray(), biometrics.record)
        val vm = viewModel()
        vm.start(prompt)
        advanceUntilIdle()
        val unlock = assertIs<AuthUiState.Unlock>(vm.state.value)
        assertEquals(UnlockMessage.BiometricNeedsSetup, unlock.message)
        assertFalse(unlock.biometric)
        assertEquals(1, biometrics.rejected)
    }

    @Test
    fun lockoutAndFailureAreShownButLeaveThePasswordPath() = runTest(dispatcher) {
        biometrics.enabled = true
        biometrics.results += MaterialResult.LockedOut
        val vm = viewModel()
        vm.start(prompt)
        advanceUntilIdle()
        assertEquals(UnlockMessage.BiometricLockedOut, assertIs<AuthUiState.Unlock>(vm.state.value).message)
        biometrics.results += MaterialResult.Failed
        vm.useBiometric()
        advanceUntilIdle()
        assertEquals(UnlockMessage.BiometricFailed, assertIs<AuthUiState.Unlock>(vm.state.value).message)
    }

    @Test
    fun aKeyFileVaultReadsTheKeyFileAgainAndShowsItsName() = runTest(dispatcher) {
        references.reference = VaultReference(VAULT_URI, "vault.kdbx", 0, KEY_FILE_URI, "my.keyx")
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals("my.keyx", assertIs<AuthUiState.Unlock>(vm.state.value).keyFileName)
        vm.typePassword("pw")
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
        assertEquals("key-file-bytes", core.seen.single().keyFile)
        assertEquals(1, files.keyFileReads.size)
    }

    @Test
    fun theFingerprintNeverReplacesTheKeyFile() = runTest(dispatcher) {
        references.reference = VaultReference(VAULT_URI, "vault.kdbx", 0, KEY_FILE_URI, "my.keyx")
        biometrics.enabled = true
        biometrics.keyFileRequired = true
        biometrics.results += MaterialResult.Obtained("M:pw".toByteArray(), biometrics.record)
        val vm = viewModel()
        vm.start(prompt)
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
        assertEquals("M:pw", core.seen.single().material)
        assertEquals("key-file-bytes", core.seen.single().keyFile)
    }

    @Test
    fun aKeyFileThatCannotBeReadSendsThePersonToTheApp() = runTest(dispatcher) {
        references.reference = VaultReference(VAULT_URI, "vault.kdbx", 0, KEY_FILE_URI, "my.keyx")
        files.keyFileFailure = VaultFileException.Unavailable()
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword("pw")
        advanceUntilIdle()
        assertEquals(AuthProblem.KeyFileUnavailable, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertTrue(core.sessions.isEmpty())
    }

    @Test
    fun aKeyFileOnlyVaultNeedsNoPassword() = runTest(dispatcher) {
        references.reference = VaultReference(VAULT_URI, "vault.kdbx", 0, KEY_FILE_URI, "my.keyx")
        val vm = viewModel()
        advanceUntilIdle()
        vm.unlockWithPassword()
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
    }

    @Test
    fun withoutAKeyFileAnEmptyPasswordDoesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.unlockWithPassword()
        advanceUntilIdle()
        assertIs<AuthUiState.Unlock>(vm.state.value)
        assertTrue(core.received.isEmpty())
    }

    @Test
    fun aJournalThatNeedsRestoringIsRefusedNotRestored() = runTest(dispatcher) {
        copies.pending[VAULT_URI] = "new".toByteArray()
        copies.baselines[VAULT_URI] = "old-baseline"
        core.failure = VaultOpenException.Reason.Damaged
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.RestoreNeeded, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertContentEquals("new".toByteArray(), copies.pendingWrite(VAULT_URI))
    }

    @Test
    fun aVaultThatIsNotTheIndexedOneIsRefusedBeforeAnyPrompt() = runTest(dispatcher) {
        val vm = viewModel(indexFingerprint = "another vault")
        advanceUntilIdle()
        assertEquals(AuthProblem.VaultChanged, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertEquals(0, biometrics.prompts)
    }

    @Test
    fun aVaultSwappedUnderTheSameFileIsRefusedAfterUnlockWithoutRevealing() = runTest(dispatcher) {
        core.fingerprint = "swapped"
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.VaultChanged, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertTrue(session.revealed.isEmpty())
        assertTrue(session.isClosed)
    }

    @Test
    fun anEntryDeletedSinceTheIndexWasBuiltIsReportedAndNothingIsRevealed() = runTest(dispatcher) {
        val vm = viewModel(entry = IndexedEntry("ghost", "Ghost", "g", "https://mail.example.org"))
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.EntryGone, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertTrue(session.revealed.isEmpty())
        assertTrue(session.isClosed)
    }

    @Test
    fun anEntryWhoseUrlChangedNoLongerMatchesAndIsNotFilled() = runTest(dispatcher) {
        val stale = IndexedEntry(MAIL.value, "Example Mail", "alice", "https://old.example.net")
        val vm = viewModel(origin = RequestOrigin.Web(WebOrigin("https", "old.example.net", null)), entry = stale)
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.NoLongerMatches, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertTrue(session.revealed.isEmpty())
    }

    @Test
    fun theIndexIsRefreshedFromTheSessionThatWasJustOpened() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(1, reindexed)
    }

    @Test
    fun cancellingFillsNothingAndKeepsNoSession() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.password.edit { replace(0, length, "typed") }
        vm.cancel()
        assertEquals(AuthUiState.Cancelled, vm.state.value)
        assertEquals("", vm.password.text.toString())
        assertTrue(core.sessions.isEmpty())
    }

    @Test
    fun searchListsTheIndexAndFiltersByTitleUserAndUrl() = runTest(dispatcher) {
        val vm = viewModel(mode = AuthRequest.Mode.Search)
        advanceUntilIdle()
        assertEquals(listOf("Example Mail", "Other"), assertIs<AuthUiState.Choosing>(vm.state.value).entries.map { it.title })
        vm.search("MAIL")
        assertEquals(listOf("Example Mail"), assertIs<AuthUiState.Choosing>(vm.state.value).entries.map { it.title })
        vm.search("o@x")
        assertEquals(emptyList(), assertIs<AuthUiState.Choosing>(vm.state.value).entries)
        vm.search("other.example")
        assertEquals(listOf("Other"), assertIs<AuthUiState.Choosing>(vm.state.value).entries.map { it.title })
    }

    @Test
    fun searchWithoutAnIndexSaysSo() = runTest(dispatcher) {
        val vm = viewModel(mode = AuthRequest.Mode.Search, index = null)
        advanceUntilIdle()
        val choosing = assertIs<AuthUiState.Choosing>(vm.state.value)
        assertFalse(choosing.indexAvailable)
        assertTrue(choosing.entries.isEmpty())
    }

    @Test
    fun aManualPickThatDoesNotMatchThePageAsksFirst() = runTest(dispatcher) {
        val other = IndexedEntry("other", "Other", "o", "https://other.example")
        val vm = viewModel(mode = AuthRequest.Mode.Search, origin = RequestOrigin.Web(WebOrigin("https", "phish.example", null)))
        advanceUntilIdle()
        vm.choose(other)
        assertEquals(AuthUiState.Confirm(other), vm.state.value)
        vm.declineChoice()
        assertIs<AuthUiState.Choosing>(vm.state.value)
        vm.choose(other)
        vm.confirmChoice()
        advanceUntilIdle()
        assertIs<AuthUiState.Unlock>(vm.state.value)
    }

    @Test
    fun aManualPickThatMatchesNeedsNoConfirmation() = runTest(dispatcher) {
        val vm = viewModel(mode = AuthRequest.Mode.Search)
        advanceUntilIdle()
        vm.choose(mailEntry)
        advanceUntilIdle()
        assertIs<AuthUiState.Unlock>(vm.state.value)
    }

    @Test
    fun aManualPickForAnAppIsRememberedWithPackageAndCertificate() = runTest(dispatcher) {
        val vm = viewModel(mode = AuthRequest.Mode.Search, origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        vm.choose(mailEntry)
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
        assertEquals(listOf(AppAssociation("com.example.app", "CERT", FINGERPRINT, MAIL.value)), associations.list)
    }

    @Test
    fun nothingIsRememberedWhenTheTogglIsOffOrTheCertificateIsUnknownOrItIsAWebPage() = runTest(dispatcher) {
        val off = viewModel(mode = AuthRequest.Mode.Search, origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        off.remembersChoice.value = false
        off.choose(mailEntry)
        advanceUntilIdle()
        off.typePassword()
        advanceUntilIdle()
        assertTrue(associations.list.isEmpty())

        callerCheck = CallerCheck.Verified(null)
        val unknown = viewModel(mode = AuthRequest.Mode.Search, origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        unknown.choose(mailEntry)
        advanceUntilIdle()
        unknown.typePassword()
        advanceUntilIdle()
        assertTrue(associations.list.isEmpty())

        val webPage = viewModel(mode = AuthRequest.Mode.Search)
        advanceUntilIdle()
        webPage.choose(mailEntry)
        advanceUntilIdle()
        webPage.typePassword()
        advanceUntilIdle()
        assertTrue(associations.list.isEmpty())
    }

    @Test
    fun anAssociatedAppIsFilledAfterTheAssociationIsRevalidated() = runTest(dispatcher) {
        val origin = RequestOrigin.App("com.example.app")
        associations.add(AppAssociation("com.example.app", "CERT", FINGERPRINT, MAIL.value))
        val vm = viewModel(origin = origin)
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
    }

    @Test
    fun anAssociationRemovedBeforeUnlockStopsTheFill() = runTest(dispatcher) {
        val vm = viewModel(origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.NoLongerMatches, assertIs<AuthUiState.Failed>(vm.state.value).problem)
    }

    @Test
    fun noVaultReferenceMeansNoVault() = runTest(dispatcher) {
        references.clear()
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(AuthProblem.NoVault, assertIs<AuthUiState.Failed>(vm.state.value).problem)
    }

    @Test
    fun theSessionCapIsReportedNotCrashed() = runTest(dispatcher) {
        core.failure = VaultOpenException.Reason.TooManySessions
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.CouldNotOpen, assertIs<AuthUiState.Failed>(vm.state.value).problem)
    }

    @Test
    fun aFilePayloadNeverPrintsItsSecret() {
        val payload = FillPayload("alice", app.vazie.keep.core.SecretValue("hunter2".toByteArray()))
        assertFalse("hunter2" in payload.toString() + payload.password.toString())
        payload.close()
    }

    @Test
    fun clearingTheModelClosesAPayloadNobodyTookAndClearsThePassword() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        val payload = payloadOf(vm)
        vm.password.edit { replace(0, length, "left") }
        val clear = ViewModel::class.java.getDeclaredMethod("clear\$lifecycle_viewmodel")
        clear.isAccessible = true
        clear.invoke(vm)
        assertTrue(payload.password!!.isClosed)
        assertEquals("", vm.password.text.toString())
    }

    @Test
    fun aCallerAndroidDidNotConfirmGetsNothingAndNoPrompt() = runTest(dispatcher) {
        callerCheck = CallerCheck.Unverified
        biometrics.enabled = true
        val vm = viewModel()
        vm.start(prompt)
        advanceUntilIdle()
        assertEquals(AuthProblem.CallerNotVerified, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertEquals(0, biometrics.prompts)
        assertTrue(core.sessions.isEmpty())
    }

    @Test
    fun anAppWhoseKeyAndroidCannotReadIsNotFilledNorRemembered() = runTest(dispatcher) {
        callerCheck = CallerCheck.Verified(null)
        associations.add(AppAssociation("com.example.app", "CERT", FINGERPRINT, MAIL.value))
        val vm = viewModel(origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.NoLongerMatches, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertTrue(session.revealed.isEmpty())
    }

    @Test
    fun anAppWithADifferentCertificateIsRefused() = runTest(dispatcher) {
        callerCheck = CallerCheck.Verified(SigningIdentity("FORGED"))
        associations.add(AppAssociation("com.example.app", "CERT", FINGERPRINT, MAIL.value))
        val vm = viewModel(origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertEquals(AuthProblem.NoLongerMatches, assertIs<AuthUiState.Failed>(vm.state.value).problem)
        assertTrue(session.revealed.isEmpty())
    }

    @Test
    fun anAppThatRotatedItsKeyIsFilledAndItsApprovalMovesToTheNewCertificate() = runTest(dispatcher) {
        callerCheck = CallerCheck.Verified(SigningIdentity("NEW", setOf("CERT")))
        associations.add(AppAssociation("com.example.app", "CERT", FINGERPRINT, MAIL.value))
        val vm = viewModel(origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
        assertEquals(listOf(AppAssociation("com.example.app", "NEW", FINGERPRINT, MAIL.value)), associations.list)
    }

    @Test
    fun aPendingApprovalFromASaveIsBoundToTheVerifiedCertificateAtTheFirstFill() = runTest(dispatcher) {
        callerCheck = CallerCheck.Verified(SigningIdentity("FIRST"))
        associations.add(AppAssociation("com.example.app", "", FINGERPRINT, MAIL.value))
        val vm = viewModel(origin = RequestOrigin.App("com.example.app"))
        advanceUntilIdle()
        vm.typePassword()
        advanceUntilIdle()
        assertIs<AuthUiState.Done>(vm.state.value)
        assertEquals(listOf(AppAssociation("com.example.app", "FIRST", FINGERPRINT, MAIL.value)), associations.list)
    }
}
