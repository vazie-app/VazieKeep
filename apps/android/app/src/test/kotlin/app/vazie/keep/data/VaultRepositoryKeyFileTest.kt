package app.vazie.keep.data

import app.vazie.keep.testing.testRepository
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.KDBX_HEADER
import app.vazie.keep.testing.KEY_FILE_URI
import app.vazie.keep.testing.VAULT_URI
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultRepositoryKeyFileTest {
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val references = InMemoryReferenceStore()
    private val repository = testRepository(core, files, references, now = { 7L })

    private suspend fun chooseKeyFile(uri: String = KEY_FILE_URI) = repository.selectKeyFile(uri)

    @Test
    fun passwordOnlyStaysAsBefore() = runTest {
        assertEquals(UnlockResult.Success, repository.unlock("pw".toByteArray()))
        assertEquals("pw", core.seen.single().password)
        assertNull(core.seen.single().keyFile)
        assertTrue(files.keyFileReads.isEmpty())
    }

    @Test
    fun keyFileOnlyIsReadOnEveryUnlockAndWiped() = runTest {
        assertEquals(KeyFileResult.Selected("my.keyx"), chooseKeyFile())
        assertEquals(KEY_FILE_URI, references.reference?.keyFileUri)
        assertTrue(KEY_FILE_URI in files.persisted)
        repeat(2) {
            assertEquals(UnlockResult.Success, repository.unlock(null))
            repository.lock()
        }
        assertEquals(2, files.keyFileReads.size)
        assertTrue(files.keyFileReads.all { bytes -> bytes.all { it == 0.toByte() } })
        assertTrue(core.seen.all { it.password == null && it.keyFile == "key-file-bytes" })
        val unlocked = repository.unlock(null).let { repository.state.value }
        assertIs<VaultState.Unlocked>(unlocked)
        assertEquals(false, unlocked.usedPassword)
        assertEquals(true, unlocked.usedKeyFile)
    }

    @Test
    fun passwordPlusKeyFile() = runTest {
        core.accepts = { password, keyFile -> password == "pw" && keyFile == "key-file-bytes" }
        chooseKeyFile()
        assertEquals(UnlockResult.Success, repository.unlock("pw".toByteArray()))
        assertEquals("pw" to "key-file-bytes", core.seen.single().let { it.password to it.keyFile })
    }

    @Test
    fun wrongKeyFileIsReportedTogetherWithThePassword() = runTest {
        core.accepts = { _, keyFile -> keyFile == "the right one" }
        chooseKeyFile()
        assertEquals(UnlockResult.Failure(UnlockError.WrongPasswordOrKeyFile), repository.unlock("pw".toByteArray()))
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun damagedKeyFileIsItsOwnError() = runTest {
        core.failure = VaultOpenException.Reason.InvalidKeyFile
        chooseKeyFile()
        assertEquals(UnlockResult.Failure(UnlockError.InvalidKeyFile), repository.unlock(null))
    }

    @Test
    fun missingKeyFileNeverReachesTheCore() = runTest {
        chooseKeyFile()
        files.keyFiles.clear()
        val password = "pw".toByteArray()
        assertEquals(UnlockResult.Failure(UnlockError.KeyFileUnavailable), repository.unlock(password))
        assertTrue(password.all { it == 0.toByte() })
        assertTrue(core.seen.isEmpty())
        assertEquals(KEY_FILE_URI, references.reference?.keyFileUri)
    }

    @Test
    fun lostPermissionAndOversizedKeyFilesAreTypedErrors() = runTest {
        chooseKeyFile()
        files.keyFileFailure = VaultFileException.Unavailable()
        assertEquals(UnlockResult.Failure(UnlockError.KeyFileUnavailable), repository.unlock(null))
        files.keyFileFailure = VaultFileException.TooLarge()
        assertEquals(UnlockResult.Failure(UnlockError.KeyFileTooLarge), repository.unlock(null))
        assertTrue(core.seen.isEmpty())
    }

    @Test
    fun switchingKeyFilesReleasesTheOldPermission() = runTest {
        files.keyFiles["content://test/other.key"] = "other"
        chooseKeyFile()
        assertEquals(KeyFileResult.Selected("other.key"), chooseKeyFile("content://test/other.key"))
        assertEquals(listOf(KEY_FILE_URI), files.released)
        repository.unlock(null)
        assertEquals("other", core.seen.single().keyFile)
    }

    @Test
    fun removingTheKeyFileForgetsItAndReleasesAccess() = runTest {
        chooseKeyFile()
        repository.removeKeyFile()
        assertNull(references.reference?.keyFileUri)
        assertNull(references.reference?.keyFileName)
        assertEquals(listOf(KEY_FILE_URI), files.released)
        assertEquals(UnlockResult.Failure(UnlockError.MissingCredentials), repository.unlock(null))
    }

    @Test
    fun aVaultOrAnUnreadableFileIsNotAcceptedAsKeyFile() = runTest {
        files.keyFiles["content://test/another.kdbx"] = String(KDBX_HEADER, Charsets.ISO_8859_1)
        files.headers["content://test/another.kdbx"] = KDBX_HEADER
        assertEquals(KeyFileResult.Failure(KeyFileError.IsVault), chooseKeyFile("content://test/another.kdbx"))
        assertEquals(KeyFileResult.Failure(KeyFileError.IsVault), chooseKeyFile(VAULT_URI))
        assertEquals(KeyFileResult.Failure(KeyFileError.Unavailable), chooseKeyFile("content://test/gone"))
        assertNull(references.reference?.keyFileUri)
    }

    @Test
    fun anEmptyPasswordFieldWithAKeyFileAlsoTriesTheEmptyPassword() = runTest {
        core.accepts = { password, keyFile -> password == "" && keyFile != null }
        chooseKeyFile()
        assertEquals(UnlockResult.Success, repository.unlock(null))
        assertEquals(listOf(null, ""), core.seen.map { it.password })
    }

    @Test
    fun choosingAnotherVaultForgetsTheKeyFile() = runTest {
        chooseKeyFile()
        files.headers["content://test/second.kdbx"] = KDBX_HEADER
        assertIs<SelectResult.Selected>(repository.select("content://test/second.kdbx"))
        assertNull(references.reference?.keyFileUri)
        assertTrue(KEY_FILE_URI in files.released)
        assertIs<SelectResult.Selected>(repository.select("content://test/second.kdbx"))
        chooseKeyFile()
        assertIs<SelectResult.Selected>(repository.select("content://test/second.kdbx"))
        assertEquals(KEY_FILE_URI, references.reference?.keyFileUri)
    }

    @Test
    fun materialIsRetainedOnlyWhenAskedAndOnlyForATypedPassword() = runTest {
        var retain = false
        val repository = testRepository(core, files, references, retainUnlockMaterial = { retain })
        repository.unlock("pw".toByteArray())
        assertNull(repository.unlockMaterial())
        retain = true
        repository.unlock("pw".toByteArray())
        assertEquals("M:pw", repository.unlockMaterial()?.useBytes { it.decodeToString() })
        assertEquals(true, (repository.state.value as VaultState.Unlocked).materialRetained)
        repository.lock()
        assertNull(repository.unlockMaterial())
        repository.selectKeyFile(KEY_FILE_URI)
        repository.unlock(null)
        assertEquals(false, core.seen.last().retain)
    }
}
