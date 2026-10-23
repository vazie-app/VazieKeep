package app.vazie.keep.data

import app.vazie.keep.testing.testRepository
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.core.SecretField
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.InMemoryReferenceStore
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VaultRepositoryRustTest {
    private val vault = File(System.getProperty("keep.testVectors")).resolve("kdbx4/aes256-argon2d.kdbx")
    private val repository = testRepository(RustKeepCore, FakeFileAccess(vault.readBytes()), InMemoryReferenceStore())

    @Test
    fun unlockBrowseRevealAndLockAgainstTheRealCore() = runTest {
        assertEquals(UnlockResult.Success, repository.unlock("correct horse battery staple".toByteArray()))
        val mail = repository.search("mail.example").single()
        assertEquals(EntryText.Plain("Example Mail"), mail.title)
        assertTrue(repository.groups().any { it.name == "Web" })
        assertEquals("hunter2-synthetic", repository.reveal(mail.id, SecretField.Password).use { s -> s.useChars { String(it) } })
        repository.lock()
        assertFailsWith<VaultLockedException> { repository.entries() }
    }

    @Test
    fun wrongPasswordIsReportedAsSuch() = runTest {
        assertEquals(UnlockResult.Failure(UnlockError.WrongPassword), repository.unlock("nope".toByteArray()))
    }
}
