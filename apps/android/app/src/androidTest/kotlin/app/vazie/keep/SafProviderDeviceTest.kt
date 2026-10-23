package app.vazie.keep

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.core.EntryDraft
import app.vazie.keep.data.EditOutcome
import app.vazie.keep.data.FileVersion
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Runs against the vault the person picked in the real document picker (a content:// URI served by a
// DocumentsProvider), so it is skipped unless one is chosen. Start it with `am instrument` so the
// app, and with it that grant, is not uninstalled afterwards.
@RunWith(AndroidJUnit4::class)
class SafProviderDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private val password = "correct horse battery staple"

    private fun uri(): String {
        val reference = container.references.load()
        assumeTrue("pick a vault in the app first", reference != null && reference.uri.startsWith("content://"))
        return reference!!.uri
    }

    private fun readAll(uri: String) = context.contentResolver.openInputStream(uri.toUri())!!.use { it.readBytes() }

    private fun log(message: String) = Log.i("SafProvider", message)

    private fun lockOnMain() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

    private fun draft(title: String) = EntryDraft(title, "u", "pw".toByteArray(), "", "", emptyList(), emptyList())

    @Test
    fun reportsWhatTheProviderCanDo() = runBlocking {
        val uri = uri()
        val capabilities = container.repository.providerCapabilities()!!
        log("authority=${capabilities.authority} known=${capabilities.known} write=${capabilities.canWrite} delete=${capabilities.canDelete} rename=${capabilities.canRename} move=${capabilities.canMove} virtual=${capabilities.isVirtual}")
        val persisted = context.contentResolver.persistedUriPermissions.single { it.uri == uri.toUri() }
        log("persisted read=${persisted.isReadPermission} write=${persisted.isWritePermission}")
        assertTrue(persisted.isWritePermission, "the picker must have granted write access")
        assertTrue(capabilities.canWrite)
    }

    @Test
    fun saveEditsAndRestoresTheOriginalThroughTheRealProvider() = runBlocking {
        val uri = uri()
        val original = readAll(uri)
        try {
            lockOnMain()
            assertEquals(UnlockResult.Success, container.repository.unlock(password.toByteArray()))
            val state = container.repository.state.value as VaultState.Unlocked
            assertTrue(state.canEdit)

            val started = System.nanoTime()
            val outcome = container.repository.edit { s -> draft("SAF probe").use { s.createEntry(state.info.rootGroupId, it) } }
            log("edit+save total ${(System.nanoTime() - started) / 1_000_000} ms")
            assertIs<EditOutcome.Saved<*>>(outcome)
            val changed = readAll(uri)
            assertTrue(!changed.contentEquals(original))

            lockOnMain()
            assertEquals(UnlockResult.Success, container.repository.unlock(password.toByteArray()))
            assertEquals(1, container.repository.search("SAF probe").size)

            val external = changed + byteArrayOf(0)
            context.contentResolver.openOutputStream(uri.toUri(), "wt")!!.use { it.write(external) }
            val blocked = container.repository.edit { s -> draft("Should not land").use { s.createEntry(state.info.rootGroupId, it) } }
            assertEquals(SaveFailure.ChangedExternally, assertIs<EditOutcome.NotSaved<*>>(blocked).failure)
            assertContentEquals(external, readAll(uri))
        } finally {
            lockOnMain()
            context.contentResolver.openOutputStream(uri.toUri(), "wt")!!.use { it.write(original) }
            assertEquals(FileVersion.of(original), FileVersion.of(readAll(uri)))
        }
        Unit
    }
}
