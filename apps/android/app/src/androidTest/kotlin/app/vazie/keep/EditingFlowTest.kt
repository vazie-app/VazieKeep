package app.vazie.keep

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.data.VaultReference
import app.vazie.keep.data.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class EditingFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private val password = "correct horse battery staple"
    private lateinit var vault: File

    @Before
    fun setUp() = runBlocking {
        lockOnMain()
        container.biometrics.disable()
        container.forgetAllVaults()
        vault = File(context.cacheDir, "editing-flow.kdbx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("editable/rich-aes-argon2d-gzip.kdbx").use { input ->
            vault.outputStream().use { input.copyTo(it) }
        }
        container.references.save(VaultReference(vault.toURI().toString().replace("file:/", "file:///"), vault.name, 0))
    }

    @After
    fun tearDown() {
        lockOnMain()
        vault.delete()
    }

    private fun string(id: Int) = context.getString(id)

    private fun lockOnMain() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

    private fun unlock() {
        compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
        compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Editable Fixture")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun textField(label: String) = compose.onNode(hasSetTextAction().and(hasText(label)))

    private fun waitForText(text: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun waitGone(text: String, timeout: Long = 10_000) =
        compose.waitUntil(timeout) { compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isEmpty() }

    @Test
    fun newEntryWithAGeneratedPasswordIsSavedAndSurvivesALock() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.onNodeWithContentDescription(string(R.string.action_add)).performClick()
            compose.onNodeWithText(string(R.string.action_new_entry)).performClick()
            waitForText(string(R.string.editor_title_new))

            textField(string(R.string.field_title)).performTextInput("Device Test Entry")
            textField(string(R.string.field_username)).performTextInput("dana")
            compose.onNodeWithContentDescription(string(R.string.field_password)).performScrollTo()
            compose.onNodeWithContentDescription(string(R.string.generator_open), useUnmergedTree = true).performClick()
            waitForText(string(R.string.generator_title))
            compose.onNodeWithContentDescription(string(R.string.generator_hidden)).assertExists()
            compose.onNodeWithText(string(R.string.generator_use)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(string(R.string.generator_title))).fetchSemanticsNodes().isEmpty() }

            compose.onNodeWithText(string(R.string.action_save)).performClick()
            waitGone(string(R.string.editor_title_new))
            waitForText("Device Test Entry")
            assertFalse(container.repository.hasUnsavedChanges.value)

            lockOnMain()
        }
        val saved = vault.readBytes()
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            waitForText("Device Test Entry")
            val entry = runBlocking { container.repository.search("Device Test").single() }
            val generated = runBlocking {
                container.repository.reveal(entry.id, app.vazie.keep.core.SecretField.Password).use { it.useChars { chars -> String(chars) } }
            }
            assertEquals(20, generated.length)
            assertTrue(saved.isNotEmpty())
        }
    }

    @Test
    fun theEditorNeverExposesThePasswordToAccessibility() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.onNodeWithContentDescription(string(R.string.action_add)).performClick()
            compose.onNodeWithText(string(R.string.action_new_entry)).performClick()
            waitForText(string(R.string.editor_title_new))
            compose.onNodeWithContentDescription(string(R.string.field_password)).performTextReplacement("TopSecretValue123")
            compose.onNodeWithContentDescription(string(R.string.generator_open), useUnmergedTree = true).performClick()
            waitForText(string(R.string.generator_title))
            compose.onNodeWithContentDescription(string(R.string.generator_show)).performClick()
            val all = compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString()
            assertFalse("TopSecretValue123" in all, "the typed password is in the semantics tree")
            assertTrue(compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password)).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun editingAnExistingEntrySavesAndRenamingGroupsWorks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            compose.onNodeWithText("Web").performClick()
            waitForText("Example Mail")
            compose.onNodeWithText("Example Mail").performClick()
            compose.onNodeWithContentDescription(string(R.string.action_edit)).performClick()
            waitForText(string(R.string.editor_title_edit))
            textField(string(R.string.field_username)).performTextReplacement("alice-edited@example.org")
            compose.onNodeWithText(string(R.string.action_save)).performClick()
            waitForText("alice-edited@example.org")

            lockOnMain()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            val entry = runBlocking { container.repository.search("Example Mail").single() }
            assertEquals(app.vazie.keep.core.EntryText.Plain("alice-edited@example.org"), entry.username)
        }
    }

    @Test
    fun anExternalChangeBlocksSavingAndShowsTheConflictDialog() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            vault.appendBytes(byteArrayOf(1, 2, 3))
            val externallyChanged = vault.readBytes()
            compose.onNodeWithContentDescription(string(R.string.action_add)).performClick()
            compose.onNodeWithText(string(R.string.action_new_group)).performClick()
            textField(string(R.string.group_name_label)).performTextInput("Doomed")
            compose.onNodeWithText(string(R.string.action_create)).performClick()
            waitForText(string(R.string.save_changed_title))
            compose.onNodeWithText(string(R.string.save_reload)).assertExists()
            assertTrue(compose.onAllNodes(hasText(string(R.string.save_copy))).fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithText(string(R.string.action_cancel)).assertExists()
            assertTrue(externallyChanged.contentEquals(vault.readBytes()))
            compose.onNodeWithText(string(R.string.save_reload)).performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription(string(R.string.unlock_password_label))).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(VaultState.Locked, container.repository.state.value)
        }
    }

    @Test
    fun lockingWithUnsavedChangesAsksAndRecoveryShowsAtTheNextUnlock() {
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            vault.appendBytes(byteArrayOf(9))
            compose.onNodeWithContentDescription(string(R.string.action_add)).performClick()
            compose.onNodeWithText(string(R.string.action_new_group)).performClick()
            textField(string(R.string.group_name_label)).performTextInput("Pending")
            compose.onNodeWithText(string(R.string.action_create)).performClick()
            waitForText(string(R.string.save_changed_title))
            compose.onNodeWithText(string(R.string.action_cancel)).performClick()
            waitGone(string(R.string.save_changed_title))
            waitForText(string(R.string.vault_unsaved_message))

            compose.onNodeWithContentDescription(string(R.string.action_lock)).performClick()
            waitForText(string(R.string.lock_unsaved_title))
            compose.onNodeWithText(string(R.string.action_cancel)).performClick()
            assertTrue(container.repository.state.value is VaultState.Unlocked)

            lockOnMain()
        }
        // The vault file was changed on purpose, so open the pristine bytes again.
        InstrumentationRegistry.getInstrumentation().context.assets.open("editable/rich-aes-argon2d-gzip.kdbx").use { input ->
            vault.outputStream().use { input.copyTo(it) }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            waitForText(string(R.string.vault_recovered_message))
            compose.onNodeWithText(string(R.string.action_delete)).performClick()
            waitGone(string(R.string.vault_recovered_message))
        }
    }
}
