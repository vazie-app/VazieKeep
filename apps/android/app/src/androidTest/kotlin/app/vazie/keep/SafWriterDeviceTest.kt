package app.vazie.keep

import android.content.Context
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.data.ContentResolverDocumentPort
import app.vazie.keep.data.DocumentIoException
import app.vazie.keep.data.FileVaultCopyStore
import app.vazie.keep.data.FileVersion
import app.vazie.keep.data.JournaledOverwriteWriter
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.WriteException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// The real ContentResolver and real descriptors, on a file:// document (the descriptor semantics
// are the same ones an ExternalStorageProvider hands out). Real providers are in SafProviderDeviceTest.
@RunWith(AndroidJUnit4::class)
class SafWriterDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "saf-writer.kdbx")
    private val uri = file.toURI().toString().replace("file:/", "file:///")
    private val copies = FileVaultCopyStore(File(context.cacheDir, "writer-copies"))
    private val port = ContentResolverDocumentPort(context.contentResolver)
    private val writer = JournaledOverwriteWriter(port, copies)
    private val original = ByteArray(5000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        file.writeBytes(original)
    }

    @After
    fun tearDown() {
        file.delete()
        File(context.cacheDir, "writer-copies").deleteRecursively()
    }

    @Test
    fun overwritesInPlaceGrowingAndShrinkingAndVerifies() = runBlocking {
        val bigger = ByteArray(9000) { (it % 13).toByte() }
        val version = writer.replace(uri, FileVersion.of(original), bigger) { true }
        assertContentEquals(bigger, file.readBytes())
        val smaller = ByteArray(1200) { 7 }
        writer.replace(uri, version, smaller) { true }
        assertContentEquals(smaller, file.readBytes())
        assertEquals(0, File(context.cacheDir, "writer-copies").listFiles().orEmpty().count { it.name.contains("pending") })
        Unit
    }

    @Test
    fun refusesAChangedFileAndKeepsItIntact() = runBlocking {
        val elsewhere = ByteArray(300) { 9 }
        file.writeBytes(elsewhere)
        val error = assertFailsWith<WriteException> { writer.replace(uri, FileVersion.of(original), ByteArray(10)) { true } }
        assertEquals(SaveFailure.ChangedExternally, error.failure)
        assertContentEquals(elsewhere, file.readBytes())
        Unit
    }

    @Test
    fun aDocumentThatIsGoneIsUnavailableAndNothingIsCreated() = runBlocking {
        file.delete()
        val error = assertFailsWith<WriteException> { writer.replace(uri, FileVersion.of(original), ByteArray(10)) { true } }
        assertEquals(SaveFailure.Unavailable, error.failure)
        assertTrue(!file.exists())
        Unit
    }

    @Test
    fun theDescriptorIsSeekableAndDoesNotTruncateOnOpen() {
        port.openOverwrite(uri).close()
        assertContentEquals(original, file.readBytes())
        assertTrue(port.hasWriteAccess(uri))
        val capabilities = port.capabilities(uri)
        assertTrue(capabilities.canWrite)
    }

    @Test
    fun aMissingPermissionOrProviderIsAnIoExceptionOfTheRightKind() {
        val error = assertFailsWith<DocumentIoException> { port.read("content://app.vazie.nonexistent/doc/1", 100) }
        assertTrue(error.kind == DocumentIoException.Kind.Unavailable || error.kind == DocumentIoException.Kind.NoPermission)
        assertFailsWith<DocumentIoException> { port.openOverwrite("content://app.vazie.nonexistent/doc/1".toUri().toString()) }
    }

    @Test
    fun saveACopyWritesAndVerifiesANewDocument() = runBlocking {
        val copy = File(context.cacheDir, "copy.kdbx")
        val copyUri = copy.toURI().toString().replace("file:/", "file:///")
        writer.writeCopy(copyUri, original)
        assertContentEquals(original, copy.readBytes())
        copy.delete()
        Unit
    }
}
