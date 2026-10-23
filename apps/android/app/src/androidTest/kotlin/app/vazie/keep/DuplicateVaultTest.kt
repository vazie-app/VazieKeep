package app.vazie.keep

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

// The same file listed twice under two addresses becomes one entry once it is opened; the file stays as it is.
@RunWith(AndroidJUnit4::class)
class DuplicateVaultTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private val folders = listOf(File(context.cacheDir, "twin-one"), File(context.cacheDir, "twin-two"))

    private fun lockOnMain() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

    private fun add(id: String, folder: File, active: Boolean): File {
        folder.mkdirs()
        val file = File(folder, "Twin.kdbx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("editable/rich-aes-argon2d-gzip.kdbx").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        val uri = file.toURI().toString().replace("file:/", "file:///")
        container.registry.add(VaultDescriptor(id = id, name = "Twin", fileName = file.name, uri = uri, lastUsedAt = 1), activate = active)
        return file
    }

    @Before
    fun setUp() = runBlocking {
        lockOnMain()
        container.biometrics.disable()
        container.forgetAllVaults()
    }

    @After
    fun tearDown() {
        lockOnMain()
        container.forgetAllVaults()
        folders.forEach { it.deleteRecursively() }
    }

    @Test
    fun openingOneOfTwoEntriesOfTheSameFileLeavesOne() {
        val first = add("first", folders[0], active = false)
        add("second", folders[1], active = true)
        val before = first.readBytes()
        assertEquals(2, container.registry.snapshot.value.vaults.size)

        assertIs<UnlockResult.Success>(runBlocking { container.repository.unlock("correct horse battery staple".toByteArray()) })
        val deadline = System.currentTimeMillis() + 10_000
        while (container.registry.snapshot.value.vaults.size > 1 && System.currentTimeMillis() < deadline) Thread.sleep(100)

        assertEquals(listOf("second"), container.registry.snapshot.value.vaults.map { it.id })
        assertContentEquals(before, first.readBytes(), "the file of the entry that left is not touched")
    }
}
