package app.vazie.keep.data

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileVaultCopyStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val uri = "content://test/vault.kdbx"
    private fun store() = FileVaultCopyStore(folder.root.resolve("Vault Copies"))

    @Test
    fun keepsOnlyTheLatestBackupPerVault() {
        val store = store()
        assertTrue(store.keepBackup("one".toByteArray(), "f".repeat(64)))
        assertTrue(store.keepBackup("two".toByteArray(), "f".repeat(64)))
        assertContentEquals("two".toByteArray(), store.backup("f".repeat(64)))
        assertNull(store.backup("0".repeat(64)))
        assertEquals(1, folder.root.resolve("Vault Copies").listFiles().orEmpty().count { it.name.endsWith("previous.kdbx") })
    }

    @Test
    fun unsavedChangesBecomeARecoveredCopyAtTheNextUnlock() {
        val store = store()
        store.keepUnsaved("lost".toByteArray(), "fp")
        assertFalse(store.hasRecovered("fp"))
        assertTrue(store.promoteUnsaved("fp"))
        assertNull(store.unsaved("fp"))
        assertContentEquals("lost".toByteArray(), store.recovered("fp"))
        store.keepUnsaved("newer".toByteArray(), "fp")
        assertContentEquals("lost".toByteArray(), store.recovered("fp"))
        store.removeRecovered("fp")
        assertFalse(store.hasRecovered("fp"))
        assertFalse(store.promoteUnsaved("other"))
    }

    @Test
    fun theJournalTellsWhatAnInterruptedWriteLeftBehind() {
        val store = store()
        val baseline = "old".toByteArray()
        val pending = "new".toByteArray()
        assertEquals(JournalState.None, store.inspectWrite(uri, FileVersion.of(baseline)))
        assertTrue(store.beginWrite(uri, FileVersion.of(baseline), pending))
        assertEquals(JournalState.NeverWritten, store.inspectWrite(uri, FileVersion.of(baseline)))
        assertEquals(JournalState.Completed, store.inspectWrite(uri, FileVersion.of(pending)))
        assertEquals(JournalState.Interrupted, store.inspectWrite(uri, FileVersion.of("half".toByteArray())))
        assertContentEquals(pending, store.pendingWrite(uri))
        store.finishWrite(uri)
        assertEquals(JournalState.None, store.inspectWrite(uri, FileVersion.of(baseline)))
        assertNull(store.pendingWrite(uri))
    }

    @Test
    fun leavesNoTemporaryFilesBehind() {
        val store = store()
        store.keepBackup("x".toByteArray(), "fp")
        store.keepUnsaved("y".toByteArray(), "fp")
        store.beginWrite(uri, FileVersion.of("a".toByteArray()), "b".toByteArray())
        assertTrue(folder.root.resolve("Vault Copies").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun copiesAreNamedByAShortHashNeverByTheUri() {
        val store = store()
        store.beginWrite("content://secret-name/Passwords%20Private.kdbx", FileVersion.of("a".toByteArray()), "b".toByteArray())
        assertTrue(folder.root.resolve("Vault Copies").listFiles().orEmpty().none { "secret" in it.name || "Passwords" in it.name })
    }
}
