package app.vazie.keep

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.core.SecretField
import app.vazie.keep.data.VaultReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

// The "Bank" entry of the fixture has two earlier versions. Without Keep+ the row leads to the Keep+
// screen; with it the test reads the oldest version and goes back to it.
@RunWith(AndroidJUnit4::class)
class HistoryFlowTest {
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
        vault = File(context.cacheDir, "history-flow.kdbx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("editable/rich-aes-argon2d-gzip.kdbx").use { input ->
            vault.outputStream().use { input.copyTo(it) }
        }
        container.references.save(VaultReference(vault.toURI().toString().replace("file:/", "file:///"), vault.name, 0))
    }

    @After
    fun tearDown() {
        lockOnMain()
        container.forgetAllVaults()
        vault.delete()
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun lockOnMain() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { exists(text) }

    private fun bankPassword() = runBlocking {
        val bank = container.repository.search("Bank").single()
        container.repository.reveal(bank.id, SecretField.Password).use { it.useChars { chars -> String(chars) } }
    }

    @Test
    fun historyOfAnEntry() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            waitForText("Editable Fixture", 15_000)
            compose.onNodeWithText("Web").performClick()
            waitForText("Work")
            compose.onNodeWithText("Work").performClick()
            waitForText("Bank")
            compose.onNodeWithText("Bank").performClick()

            waitForText(string(R.string.history_title))
            assertEquals(true, exists(context.resources.getQuantityString(R.plurals.history_versions, 2, 2)))
            compose.onNodeWithText(string(R.string.history_title)).performScrollTo().performClick()

            if (!container.entitlements.state.value.access.plusFeatures) {
                waitForText(string(R.string.plus_title))
                return
            }

            waitForText(string(R.string.history_intro))
            val rows = compose.onAllNodes(hasText(string(R.string.history_differs, string(R.string.history_field_password))))
            assertEquals(2, rows.fetchSemanticsNodes().size)
            rows[1].performClick()

            val showPassword = string(R.string.action_show_secret, string(R.string.field_password))
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(showPassword)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription(showPassword).performClick()
            // The revealed characters are drawn but never given to accessibility, so the test sees the state only.
            val hidePassword = string(R.string.action_hide_secret, string(R.string.field_password))
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(hidePassword)).fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithText(string(R.string.history_restore)).performScrollTo().performClick()
            waitForText(string(R.string.history_restore_title))
            compose.onNodeWithText(string(R.string.history_restore_confirm)).performClick()

            compose.waitUntil(15_000) { exists(context.resources.getQuantityString(R.plurals.history_versions, 3, 3)) }
            assertEquals("first-password", bankPassword())
        }
    }
}
