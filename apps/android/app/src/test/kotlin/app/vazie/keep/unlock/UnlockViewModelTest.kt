package app.vazie.keep.unlock

import app.vazie.keep.testing.testRepository
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.vazie.keep.biometric.AuthResult
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlock
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.data.KeyFileError
import app.vazie.keep.data.UnlockError
import app.vazie.keep.data.VaultState
import app.vazie.keep.testing.FakeAuthenticator
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.InMemorySetupStore
import app.vazie.keep.testing.KEY_FILE_URI
import app.vazie.keep.testing.PROMPT
import app.vazie.keep.testing.SoftwareProtector
import kotlinx.coroutines.CompletableDeferred
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

@OptIn(ExperimentalCoroutinesApi::class)
class UnlockViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val references = InMemoryReferenceStore()
    private val authenticator = FakeAuthenticator()
    private val repository = testRepository(core, files, references, retainUnlockMaterial = { true })
    private val biometrics = BiometricUnlock(repository, InMemorySetupStore(), SoftwareProtector(), authenticator, io = dispatcher)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(password: String = "correct horse") = UnlockViewModel(repository, biometrics).apply {
        this.password.setTextAndPlaceCursorAtEnd(password)
    }

    @Test
    fun successUnlocksAndClearsThePassword() = runTest(dispatcher) {
        val vm = viewModel()
        vm.unlock()
        assertEquals("", vm.password.text.toString())
        runCurrent()
        assertEquals(UnlockUiState.Unlocked, vm.state.value)
        assertIs<VaultState.Unlocked>(repository.state.value)
        assertEquals("correct horse".length, core.passwords.single().size)
    }

    @Test
    fun wrongPasswordShowsErrorAndClearsTheField() = runTest(dispatcher) {
        core.failure = VaultOpenException.Reason.InvalidCredentials
        val vm = viewModel()
        vm.unlock()
        runCurrent()
        assertEquals(UnlockUiState.Failed(UnlockError.WrongPassword), vm.state.value)
        assertEquals("", vm.password.text.toString())
    }

    @Test
    fun damagedVaultIsReported() = runTest(dispatcher) {
        core.failure = VaultOpenException.Reason.Damaged
        val vm = viewModel()
        vm.unlock()
        runCurrent()
        assertEquals(UnlockUiState.Failed(UnlockError.Damaged), vm.state.value)
    }

    @Test
    fun doubleTapStartsOneKeyDerivation() = runTest(dispatcher) {
        core.gate = CompletableDeferred()
        val vm = viewModel()
        vm.unlock()
        vm.password.setTextAndPlaceCursorAtEnd("typed again")
        vm.unlock()
        runCurrent()
        assertEquals(UnlockUiState.Unlocking, vm.state.value)
        core.gate?.complete(Unit)
        runCurrent()
        assertEquals(1, core.passwords.size)
        assertEquals(UnlockUiState.Unlocked, vm.state.value)
    }

    @Test
    fun emptyPasswordDoesNothing() = runTest(dispatcher) {
        val vm = viewModel(password = "")
        vm.unlock()
        runCurrent()
        assertEquals(UnlockUiState.Idle, vm.state.value)
        assertEquals(0, core.passwords.size)
    }

    @Test
    fun encodesUnicodeAsUtf8() {
        assertEquals("пароль-🔑".toByteArray(Charsets.UTF_8).toList(), "пароль-🔑".encodeUtf8().toList())
    }

    @Test
    fun aKeyFileAloneIsEnoughAndCanBeChangedOrRemoved() = runTest(dispatcher) {
        val vm = viewModel(password = "")
        vm.unlock()
        runCurrent()
        assertEquals(0, core.seen.size)
        vm.onKeyFileChosen(KEY_FILE_URI)
        runCurrent()
        assertEquals("my.keyx", vm.keyFile.value.name)
        vm.unlock()
        runCurrent()
        assertEquals(UnlockUiState.Unlocked, vm.state.value)
        assertEquals(null, core.seen.single().password)
        assertEquals("key-file-bytes", core.seen.single().keyFile)
        vm.removeKeyFile()
        assertEquals(null, vm.keyFile.value.name)
        assertEquals(null, references.reference?.keyFileUri)
    }

    @Test
    fun aVaultChosenAsKeyFileIsRefused() = runTest(dispatcher) {
        val vm = viewModel()
        vm.onKeyFileChosen("content://test/vault.kdbx")
        runCurrent()
        assertEquals(KeyFileError.IsVault, vm.keyFile.value.error)
        assertEquals(null, vm.keyFile.value.name)
    }

    @Test
    fun cancelledBiometricPromptLeavesThePasswordPath() = runTest(dispatcher) {
        val first = viewModel()
        first.unlock()
        runCurrent()
        biometrics.enable(PROMPT)
        repository.lock()
        val vm = viewModel()
        runCurrent()
        assertIs<BiometricStatus.On>(vm.biometricStatus.value)
        authenticator.outcome = AuthResult.Cancelled
        vm.unlockWithBiometrics(PROMPT)
        runCurrent()
        assertEquals(UnlockUiState.Idle, vm.state.value)
        assertEquals(VaultState.Locked, repository.state.value)
        vm.unlock()
        runCurrent()
        assertEquals(UnlockUiState.Unlocked, vm.state.value)
    }

    @Test
    fun biometricUnlockOpensTheVault() = runTest(dispatcher) {
        viewModel().apply { unlock() }
        runCurrent()
        biometrics.enable(PROMPT)
        repository.lock()
        val vm = viewModel(password = "")
        runCurrent()
        vm.unlockWithBiometrics(PROMPT)
        runCurrent()
        assertEquals(UnlockUiState.Unlocked, vm.state.value)
        assertEquals("correct horse", core.seen.last().material?.removePrefix("M:"))
    }
}
