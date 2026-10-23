package app.vazie.keep.lock

import app.vazie.keep.testing.testRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.testing.FakeClipboard
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryReferenceStore
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LockCleanupTest {
    @Test
    fun lockClearsOurClipboardButStartupDoesNot() = runTest(UnconfinedTestDispatcher()) {
        val repository = testRepository(FakeKeepCore(), FakeFileAccess(), InMemoryReferenceStore())
        val clipboard = FakeClipboard()
        LockCleanup(repository, clipboard).start(backgroundScope)
        assertEquals(0, clipboard.cleared)
        repository.unlock("x".toByteArray())
        repository.lock()
        assertEquals(1, clipboard.cleared)
    }

    @Test
    fun aFreshRepositoryAfterProcessDeathIsLockedEvenWithASavedReference() {
        val references = InMemoryReferenceStore()
        val repository = testRepository(FakeKeepCore(), FakeFileAccess(), references)
        assertEquals(VaultState.Locked, repository.state.value)
        assertEquals("vault.kdbx", repository.reference()?.displayName)
    }
}
