package app.vazie.keep.data

import app.vazie.keep.testing.testRepository
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.MAIL_PASSWORD
import app.vazie.keep.testing.VAULT_URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VaultRepositoryTest {
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val references = InMemoryReferenceStore()
    private val repository = testRepository(core, files, references, now = { 42L })

    @Test
    fun unlockOpensOneSessionAndWipesThePassword() = runTest {
        val password = "secret".toByteArray()
        assertEquals(UnlockResult.Success, repository.unlock(password))
        assertTrue(password.all { it == 0.toByte() })
        assertIs<VaultState.Unlocked>(repository.state.value)
        assertEquals(42L, references.reference?.lastUsedAt)
        assertEquals(MAIL_PASSWORD, repository.reveal(MAIL, SecretField.Password).use { s -> s.useChars { String(it) } })
    }

    @Test
    fun openErrorsMapToUserFacingCategories() = runTest {
        val cases = mapOf(
            VaultOpenException.Reason.InvalidCredentials to UnlockError.WrongPassword,
            VaultOpenException.Reason.Damaged to UnlockError.Damaged,
            VaultOpenException.Reason.Unsupported to UnlockError.Unsupported,
            VaultOpenException.Reason.NotAVault to UnlockError.NotAVault,
            VaultOpenException.Reason.TooLarge to UnlockError.TooLarge,
            VaultOpenException.Reason.Unavailable to UnlockError.Unknown,
        )
        for ((reason, expected) in cases) {
            core.failure = reason
            val password = "x".toByteArray()
            assertEquals(UnlockResult.Failure(expected), repository.unlock(password))
            assertTrue(password.all { it == 0.toByte() })
            assertEquals(VaultState.Locked, repository.state.value)
        }
    }

    @Test
    fun fileProblemsNeverReachTheCore() = runTest {
        files.failure = VaultFileException.Unavailable()
        assertEquals(UnlockResult.Failure(UnlockError.FileUnavailable), repository.unlock("x".toByteArray()))
        files.failure = VaultFileException.TooLarge()
        assertEquals(UnlockResult.Failure(UnlockError.TooLarge), repository.unlock("x".toByteArray()))
        references.clear()
        files.failure = null
        assertEquals(UnlockResult.Failure(UnlockError.FileUnavailable), repository.unlock("x".toByteArray()))
        assertTrue(core.passwords.isEmpty())
    }

    @Test
    fun concurrentUnlockIsRejected() = runTest(UnconfinedTestDispatcher()) {
        core.gate = CompletableDeferred()
        val first = async { repository.unlock("a".toByteArray()) }
        val second = "b".toByteArray()
        assertEquals(UnlockResult.AlreadyRunning, repository.unlock(second))
        assertTrue(second.all { it == 0.toByte() })
        core.gate?.complete(Unit)
        assertEquals(UnlockResult.Success, first.await())
        assertEquals(1, core.passwords.size)
    }

    @Test
    fun lockClosesTheSessionAndRequestsHiding() = runTest(UnconfinedTestDispatcher()) {
        repository.unlock("x".toByteArray())
        val session = core.sessions.single() as FakeVaultSession
        val hidden = async { repository.hideSecretsRequests.firstOrNullWithin() }
        repository.lock()
        assertTrue(session.isClosed)
        assertEquals(1, core.closeAllCalls)
        assertEquals(VaultState.Locked, repository.state.value)
        assertEquals(Unit, hidden.await())
        assertFailsWith<VaultLockedException> { repository.entries() }
    }

    @Test
    fun aSessionClosedUnderneathLocksTheRepository() = runTest {
        repository.unlock("x".toByteArray())
        core.sessions.single().close()
        assertFailsWith<VaultLockedException> { repository.groups() }
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun selectAcceptsOnlyKdbxAndKeepsOnePersistedPermission() = runTest {
        files.headers["content://other/notes.txt"] = "hello world!".toByteArray()
        assertEquals(SelectResult.Failure(SelectError.NotAVault), repository.select("content://other/notes.txt"))
        assertEquals(SelectResult.Failure(SelectError.FileUnavailable), repository.select("content://missing"))

        val next = "content://other/new.kdbx"
        files.headers[next] = files.headers.getValue(VAULT_URI)
        val result = repository.select(next)
        assertIs<SelectResult.Selected>(result)
        assertEquals("new.kdbx", result.reference.displayName)
        assertEquals(listOf(VAULT_URI), files.released)
        assertEquals(setOf(next), files.persisted)
        assertEquals(next, references.reference?.uri)
    }
}

private suspend fun <T> Flow<T>.firstOrNullWithin(): T? = withTimeoutOrNull(1_000) { first() }
