package app.vazie.keep

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.SecretField
import app.vazie.keep.data.UnlockResult
import app.vazie.keep.data.VaultReference
import app.vazie.keep.importer.ImportDialog
import app.vazie.keep.importer.ImportUiState
import app.vazie.keep.importer.ImportViewModel
import app.vazie.keep.ui.designsystem.KeepTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A made-up Bitwarden export goes through the real core into a real vault file: read, shown, confirmed,
// saved, and the entries are there after the vault is opened again.
@RunWith(AndroidJUnit4::class)
class ImportFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private val password = "correct horse battery staple"
    private lateinit var vault: File
    private lateinit var export: File

    private val bitwarden = """
        {"encrypted": false, "folders": [{"id": "f", "name": "Imported/Servers"}], "items": [
          {"type": 1, "name": "Import Server", "favorite": true, "folderId": "f",
           "login": {"username": "root", "password": "made-up-password", "totp": "JBSWY3DPEHPK3PXP", "uris": [{"uri": "https://server.example"}]},
           "fields": [{"name": "Port", "value": "22", "type": 0}]},
          {"type": 2, "name": "Import Note", "notes": "nothing secret"}
        ]}
    """.trimIndent()

    @Before
    fun setUp() = runBlocking {
        lockOnMain()
        container.biometrics.disable()
        container.forgetAllVaults()
        vault = File(context.cacheDir, "import-flow.kdbx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("editable/rich-aes-argon2d-gzip.kdbx").use { input ->
            vault.outputStream().use { input.copyTo(it) }
        }
        export = File(context.cacheDir, "bitwarden-export.json").apply { writeText(bitwarden) }
        container.references.save(VaultReference(vault.toURI().toString().replace("file:/", "file:///"), vault.name, 0))
    }

    @After
    fun tearDown() {
        lockOnMain()
        container.forgetAllVaults()
        vault.delete()
        export.delete()
    }

    private fun string(id: Int) = context.getString(id)

    private fun lockOnMain() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

    private fun unlock() = runBlocking { assertEquals(UnlockResult.Success, container.repository.unlock(password.toByteArray())) }

    @Test
    fun anExportIsShownImportedAndSaved() {
        unlock()
        val before = container.repository.entries().size
        lateinit var importer: ImportViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { importer = ImportViewModel(container.core, container.repository) }
        compose.setContent { KeepTheme { ImportDialog(importer) } }

        importer.onFilePicked(export.toURI().toString().replace("file:/", "file:///"))
        compose.waitUntil(15_000) { importer.state.value is ImportUiState.Preview }
        val summary = (importer.state.value as ImportUiState.Preview).summary
        assertEquals(2, summary.entries)
        assertEquals(1, summary.withTotp)
        assertEquals(1, summary.folders)
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(string(R.string.import_source_bitwarden), substring = true)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(string(R.string.import_confirm)).performClick()
        compose.waitUntil(20_000) { importer.state.value is ImportUiState.Done }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(string(R.string.import_done_message))).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(before + 2, container.repository.entries().size)

        lockOnMain()
        unlock()
        val server = container.repository.search("Import Server").single()
        assertTrue(server.favorite && server.hasTotp)
        assertEquals(EntryText.Plain("root"), server.username)
        val group = container.repository.groups().single { it.id == server.groupId }
        assertEquals("Servers", group.name)
        assertEquals("made-up-password", container.repository.reveal(server.id, SecretField.Password).use { it.useChars { chars -> String(chars) } })
        assertEquals(1, container.repository.search("Import Note").size)
    }
}
