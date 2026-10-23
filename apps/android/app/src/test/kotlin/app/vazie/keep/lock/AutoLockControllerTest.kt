package app.vazie.keep.lock

import app.vazie.keep.testing.testRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.settings.AutoLockTimeout
import app.vazie.keep.settings.SecuritySettings
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryReferenceStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AutoLockControllerTest {
    private val repository = testRepository(FakeKeepCore(), FakeFileAccess(), InMemoryReferenceStore())
    private val settings = MutableStateFlow(SecuritySettings())

    private fun TestScope.controller(clock: () -> Long = { testScheduler.currentTime }) =
        AutoLockController(repository, backgroundScope, settings, clock)

    private fun TestScope.settle() = runCurrent()

    @Test
    fun defaultPolicyIsThirtySecondsAndLockOnDeviceLock() {
        assertEquals(AutoLockTimeout.Seconds30, SecuritySettings().autoLockTimeout)
        assertEquals(true, SecuritySettings().lockOnDeviceLock)
    }

    @Test
    fun locksAfterTheTimeoutInBackground() = runTest {
        repository.unlock("x".toByteArray())
        val autoLock = controller()
        settle()
        autoLock.onBackground()
        advanceTimeBy(29.seconds)
        settle()
        assertIs<VaultState.Unlocked>(repository.state.value)
        advanceTimeBy(2.seconds)
        settle()
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun immediatelyLocksOnBackground() = runTest {
        settings.value = SecuritySettings(autoLockTimeout = AutoLockTimeout.Immediately)
        repository.unlock("x".toByteArray())
        controller().onBackground()
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun returningInTimeKeepsTheVaultOpen() = runTest {
        repository.unlock("x".toByteArray())
        val autoLock = controller()
        settle()
        autoLock.onBackground()
        advanceTimeBy(10.seconds)
        autoLock.onForeground()
        advanceTimeBy(60.seconds)
        settle()
        assertIs<VaultState.Unlocked>(repository.state.value)
    }

    @Test
    fun aFrozenProcessLocksOnReturn() = runTest {
        repository.unlock("x".toByteArray())
        var now = 0L
        val autoLock = controller(clock = { now })
        autoLock.onBackground()
        now = 45_000
        autoLock.onForeground()
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun shorteningTheTimeoutWhileInBackgroundReschedules() = runTest {
        settings.value = SecuritySettings(autoLockTimeout = AutoLockTimeout.Minutes5)
        repository.unlock("x".toByteArray())
        val autoLock = controller()
        settle()
        autoLock.onBackground()
        advanceTimeBy(20.seconds)
        settings.value = SecuritySettings(autoLockTimeout = AutoLockTimeout.Seconds30)
        settle()
        assertIs<VaultState.Unlocked>(repository.state.value)
        advanceTimeBy(11.seconds)
        settle()
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun shorteningBelowTheElapsedTimeLocksAtOnce() = runTest {
        settings.value = SecuritySettings(autoLockTimeout = AutoLockTimeout.Minutes5)
        repository.unlock("x".toByteArray())
        val autoLock = controller()
        settle()
        autoLock.onBackground()
        advanceTimeBy(40.seconds)
        settings.value = SecuritySettings(autoLockTimeout = AutoLockTimeout.Seconds15)
        settle()
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun screenOffLocksWhenEnabled() = runTest {
        repository.unlock("x".toByteArray())
        controller().onScreenOff()
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun screenOffOnlyHidesWhenDisabled() = runTest(UnconfinedTestDispatcher()) {
        settings.value = SecuritySettings(lockOnDeviceLock = false)
        repository.unlock("x".toByteArray())
        val hidden = async { repository.hideSecretsRequests.first() }
        controller().onScreenOff()
        assertEquals(Unit, hidden.await())
        assertIs<VaultState.Unlocked>(repository.state.value)
    }

    @Test
    fun backgroundHidesSecretsImmediately() = runTest(UnconfinedTestDispatcher()) {
        repository.unlock("x".toByteArray())
        val hidden = async { repository.hideSecretsRequests.first() }
        controller().onBackground()
        assertEquals(Unit, hidden.await())
    }
}
