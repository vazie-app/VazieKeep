package app.vazie.keep.data

import app.vazie.keep.testing.FakeDocumentPort
import app.vazie.keep.testing.InMemoryCopyStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournaledOverwriteWriterTest {
    private val uri = "content://test/vault.kdbx"
    private val original = "original-vault".toByteArray()
    private val replacement = "a-longer-replacement-vault".toByteArray()
    private val port = FakeDocumentPort(original)
    private val copies = InMemoryCopyStore()
    private val writer = JournaledOverwriteWriter(port, copies, io = kotlinx.coroutines.Dispatchers.Unconfined)
    private val backed = mutableListOf<ByteArray>()
    private val backup: (ByteArray) -> Boolean = { backed += it; copies.keepBackup(it, "fp") }

    private suspend fun replace(bytes: ByteArray = replacement, expected: FileVersion = FileVersion.of(original)) =
        writer.replace(uri, expected, bytes, backup)

    private suspend fun failure(block: suspend () -> Unit): SaveFailure = assertFailsWith<WriteException> { block() }.failure

    @Test
    fun replacesTheDocumentAndKeepsABackupOfWhatItReplaced() = runTest {
        val version = replace()
        assertContentEquals(replacement, port.content)
        assertEquals(FileVersion.of(replacement), version)
        assertContentEquals(original, backed.single())
        assertContentEquals(original, copies.backup("fp"))
        assertNull(copies.pendingWrite(uri))
    }

    @Test
    fun neverTruncatesBeforeWritingTheNewBytes() = runTest {
        replace()
        val mutations = port.operations.filter { it != "read" && it != "open" }
        assertEquals(listOf("write", "truncate:${replacement.size}", "sync", "close"), mutations)
    }

    @Test
    fun shrinkingFilesAreTruncatedToTheNewLengthAfterTheWrite() = runTest {
        val shorter = "short".toByteArray()
        replace(shorter)
        assertContentEquals(shorter, port.content)
    }

    @Test
    fun aFileChangedSinceItWasOpenedIsNeverOverwritten() = runTest {
        port.content = "someone-elses-version".toByteArray()
        assertEquals(SaveFailure.ChangedExternally, failure { replace() })
        assertContentEquals("someone-elses-version".toByteArray(), port.content)
        assertTrue(backed.isEmpty())
        assertTrue(port.operations.none { it == "write" })
        assertNull(copies.pendingWrite(uri))
    }

    @Test
    fun providersThatCannotWriteOrAreVirtualAreRefusedWithoutTouchingAnything() = runTest {
        port.capabilities = port.capabilities.copy(canWrite = false)
        assertEquals(SaveFailure.ProviderUnsupported, failure { replace() })
        port.capabilities = port.capabilities.copy(canWrite = true, isVirtual = true)
        assertEquals(SaveFailure.ProviderUnsupported, failure { replace() })
        assertTrue(port.operations.isEmpty())
        assertContentEquals(original, port.content)
    }

    @Test
    fun aProviderWithoutASeekableDescriptorIsRefusedBeforeAnySideEffect() = runTest {
        port.openFailure = DocumentIoException.Kind.Unsupported
        assertEquals(SaveFailure.ProviderUnsupported, failure { replace() })
        assertTrue(backed.isEmpty())
        assertNull(copies.pendingWrite(uri))
        assertContentEquals(original, port.content)
    }

    @Test
    fun missingPermissionIsReportedAsNotWritable() = runTest {
        port.openFailure = DocumentIoException.Kind.NoPermission
        assertEquals(SaveFailure.NotWritable, failure { replace() })
        assertContentEquals(original, port.content)
    }

    @Test
    fun anUnreadableDocumentIsUnavailable() = runTest {
        port.readFailure = DocumentIoException.Kind.Unavailable
        assertEquals(SaveFailure.Unavailable, failure { replace() })
    }

    @Test
    fun aFailedBackupStopsBeforeTheFirstWrite() = runTest {
        copies.failBackup = true
        assertEquals(SaveFailure.BackupFailed, failure { replace() })
        assertTrue(port.operations.none { it == "write" })
        assertContentEquals(original, port.content)
        assertNull(copies.pendingWrite(uri))
    }

    @Test
    fun runningOutOfSpaceMidWritePutsTheOriginalBackAndKeepsNothingPending() = runTest {
        port.writeFailure = DocumentIoException.Kind.NoSpace
        port.writeCutAt = 6
        assertEquals(SaveFailure.InsufficientSpace, failure { replace() })
        assertContentEquals(original, port.content)
        assertNull(copies.pendingWrite(uri))
    }

    @Test
    fun aFailureWhenClosingAProxyDescriptorIsRolledBackToo() = runTest {
        port.closeFailure = DocumentIoException.Kind.Failed
        assertEquals(SaveFailure.Unavailable, failure { replace() })
        assertContentEquals(original, port.content)
        assertNull(copies.pendingWrite(uri))
    }

    @Test
    fun aSilentlyCorruptedWriteIsDetectedByTheReadBackAndUndone() = runTest {
        port.corruptWrites = true
        assertEquals(SaveFailure.VerificationFailed, failure { replace() })
        assertContentEquals(original, port.content)
        assertNull(copies.pendingWrite(uri))
    }

    @Test
    fun ifTheOriginalCannotBePutBackTheJournalKeepsACompleteCopyOfTheNewVersion() = runTest {
        port.writeFailure = DocumentIoException.Kind.Failed
        port.writeCutAt = 4
        port.failOpenFromCall = 2
        assertEquals(SaveFailure.RestoreNeeded, failure { replace() })
        assertFalse(port.content.contentEquals(original))
        assertContentEquals(replacement, copies.pendingWrite(uri))
        assertContentEquals(original, copies.backup("fp"))
        assertEquals(JournalState.Interrupted, copies.inspectWrite(uri, FileVersion.of(port.content)))
    }

    @Test
    fun restoreWritesTheJournaledBytesOverADamagedFileAndKeepsTheDamagedOne() = runTest {
        val damaged = "garbage".toByteArray()
        port.content = damaged
        writer.restore(uri, FileVersion.of(damaged), replacement)
        assertContentEquals(replacement, port.content)
        assertContentEquals(damaged, copies.damaged[uri])
    }

    @Test
    fun restoreRefusesIfTheFileChangedAgain() = runTest {
        port.content = "garbage".toByteArray()
        assertEquals(SaveFailure.ChangedExternally, failure { writer.restore(uri, FileVersion.of(original), replacement) })
    }

    @Test
    fun saveACopyVerifiesWhatItWrote() = runTest {
        writer.writeCopy("content://new", replacement)
        assertContentEquals(replacement, port.created)
        port.copyFailure = DocumentIoException.Kind.NoSpace
        assertEquals(SaveFailure.InsufficientSpace, failure { writer.writeCopy("content://new", replacement) })
    }

    @Test
    fun strategyFollowsTheProviderFlags() {
        val ok = ProviderCapabilities("a", true, true, true, true, true, false)
        assertEquals(SaveStrategy.JournaledOverwrite, chooseStrategy(ok))
        assertEquals(SaveStrategy.JournaledOverwrite, chooseStrategy(ProviderCapabilities.Unknown))
        assertEquals(SaveStrategy.Unsupported, chooseStrategy(ok.copy(canWrite = false)))
        assertEquals(SaveStrategy.Unsupported, chooseStrategy(ok.copy(isVirtual = true)))
    }
}
