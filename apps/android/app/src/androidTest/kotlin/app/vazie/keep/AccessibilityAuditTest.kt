package app.vazie.keep

import android.content.Context
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.data.VaultReference
import app.vazie.keep.data.VaultState
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityViewCheckResult
import com.google.android.apps.common.testing.accessibility.framework.integrations.espresso.AccessibilityValidator
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

// Walks the main screens with the Accessibility Test Framework's checks on: touch targets, labels, clickable
// spans, duplicate descriptions. Contrast is not judged here: the window is secure, so there is no screenshot.
@RunWith(AndroidJUnit4::class)
class AccessibilityAuditTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private val password = "correct horse battery staple"
    private lateinit var vault: File
    private val found = mutableListOf<String>()
    private var screen = ""

    @Before
    fun setUp() = runBlocking {
        lockOnMain()
        container.biometrics.disable()
        container.forgetAllVaults()
        vault = File(context.cacheDir, "accessibility.kdbx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("editable/rich-aes-argon2d-gzip.kdbx").use { input ->
            vault.outputStream().use { input.copyTo(it) }
        }
        container.references.save(VaultReference(vault.toURI().toString().replace("file:/", "file:///"), vault.name, 0))
        val validator = AccessibilityValidator().setRunChecksFromRootView(true).setThrowExceptionFor(null)
        validator.addCheckListener { _, results: Iterable<AccessibilityViewCheckResult> ->
            results.filter { it.type == AccessibilityCheckResultType.ERROR || it.type == AccessibilityCheckResultType.WARNING }.filterNot(::expected).forEach {
                val element = it.element
                val what = listOfNotNull(element?.text?.toString(), element?.contentDescription?.toString(), element?.boundsInScreen?.let { b -> "${b.width}x${b.height}@${b.left},${b.top}" }).joinToString(" / ")
                val line = "$screen | ${it.type} | ${it.sourceCheckClass.simpleName} | $what | ${it.message.toString().take(110)}"
                if (line !in found) found += line
            }
        }
        compose.enableAccessibilityChecks(validator)
    }

    // A password field speaks its label over a masked value on purpose (ADR 0008); the letter on a vault tile and
    // rows passing under the floating dock are judged by the colour tokens, not by a screenshot.
    private fun expected(result: AccessibilityViewCheckResult): Boolean = when (result.sourceCheckClass.simpleName) {
        "EditableContentDescCheck" -> true
        "TextContrastCheck" -> true
        else -> false
    }

    @After
    fun tearDown() {
        lockOnMain()
        container.forgetAllVaults()
        vault.delete()
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun lockOnMain() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

    private fun waitFor(vararg texts: String) = compose.waitUntil(10_000) {
        texts.any { compose.onAllNodes(hasText(it, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForLabel(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(text)).fetchSemanticsNodes().isNotEmpty() }

    private fun back() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun audit(name: String) {
        screen = name
        compose.waitForIdle()
        compose.onAllNodes(isRoot()).fetchSemanticsNodes().indices.forEach { compose.onAllNodes(isRoot())[it].tryPerformAccessibilityChecks() }
    }

    @Test
    fun theMainScreensPassTheChecks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForLabel(string(R.string.unlock_password_label))
            audit("unlock")
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) { container.repository.state.value is VaultState.Unlocked }
            waitFor("Web")
            audit("vault")

            compose.onNodeWithContentDescription(string(R.string.action_add)).performClick()
            waitFor(string(R.string.action_new_entry))
            audit("add menu")
            compose.onNodeWithText(string(R.string.action_new_entry)).performClick()
            waitFor(string(R.string.action_save))
            audit("editor")
            back()
            waitFor("Web")

            compose.onNodeWithText("Web").performClick()
            waitFor("Example Mail")
            audit("group")
            compose.onNodeWithText("Example Mail").performClick()
            waitForLabel(string(R.string.action_more))
            audit("entry")
            compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
            waitFor(string(R.string.action_change_icon))
            audit("entry menu")
            compose.onNodeWithText(string(R.string.action_change_icon)).performClick()
            waitFor(string(R.string.icon_picker_entry))
            audit("icon picker")
            back()
            back()
            back()
            waitFor("Web")

            compose.onNodeWithText(string(R.string.dock_generator)).performClick()
            waitFor(string(R.string.generator_use), string(R.string.generator_copy))
            audit("generator")
            compose.onNodeWithText(string(R.string.dock_keep)).performClick()
            waitFor("Web")

            compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
            waitFor(string(R.string.settings_title))
            audit("settings")
            compose.onNode(hasText(string(R.string.account_title), substring = true).and(hasClickAction())).performScrollTo().performClick()
            compose.waitForIdle()
            audit("account")
            back()
            waitFor(string(R.string.settings_title))
            compose.onNode(hasText(string(R.string.about_row_title), substring = true).and(hasClickAction())).performScrollTo().performClick()
            compose.waitForIdle()
            audit("about")
            back()
            back()
            waitFor("Web")
        }
        lockOnMain()
        container.forgetAllVaults()
        ActivityScenario.launch(MainActivity::class.java).use {
            waitFor(string(R.string.hub_open_file))
            audit("chooser")
        }
        found.forEach { Log.w("A11Y", it) }
        assertEquals(emptyList(), found)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        val image = compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        File(context.cacheDir, "a11y-$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    // For a person to look at, with the system font size turned up before the run: `adb shell settings put system font_scale 2.0`.
    @Test
    fun theMainScreensAtALargeFontSize() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForLabel(string(R.string.unlock_password_label))
            shot("01-unlock")
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) { container.repository.state.value is VaultState.Unlocked }
            waitFor("Web")
            shot("02-vault")
            compose.onNodeWithText("Web").performClick()
            waitFor("Example Mail")
            shot("03-group")
            compose.onNodeWithText("Example Mail").performClick()
            waitForLabel(string(R.string.action_more))
            shot("04-entry")
            compose.onNodeWithContentDescription(string(R.string.action_edit)).performClick()
            waitFor(string(R.string.action_save))
            shot("05-editor")
            back()
            back()
            back()
            waitFor("Web")
            compose.onNodeWithContentDescription(string(R.string.settings_title)).performClick()
            waitFor(string(R.string.settings_title))
            shot("06-settings")
        }
    }
}
