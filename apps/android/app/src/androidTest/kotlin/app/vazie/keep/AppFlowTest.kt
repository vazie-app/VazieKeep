package app.vazie.keep

import android.content.Context
import android.view.WindowManager
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.data.VaultReference
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AppFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container

    @Before
    fun resetState() = runBlocking {
        container.repository.lock()
        container.biometrics.disable()
        container.settingsRepository.setScreenshotsAllowed(false)
        container.forgetAllVaults()
    }

    private fun asset(path: String): File {
        val file = File(context.cacheDir, path.substringAfterLast('/'))
        InstrumentationRegistry.getInstrumentation().context.assets.open(path).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        return file
    }

    private fun File.uri() = toURI().toString().replace("file:/", "file:///")

    @Test
    fun windowIsSecureByDefault() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.waitForIdle()
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Test
    fun freshInstallStartsAtTheChooser() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.hub_open_file)).assertExists()
        }
    }

    @Test
    fun lockClearsTheBackStack() {
        val vault = File(context.cacheDir, "flow-test.kdbx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("kdbx4/aes256-argon2d.kdbx").use { input ->
            vault.outputStream().use { input.copyTo(it) }
        }
        container.references.save(VaultReference(vault.toURI().toString().replace("file:/", "file:///"), "flow-test.kdbx", 0))

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithContentDescription(context.getString(R.string.unlock_password_label))
                .performTextReplacement("correct horse battery staple")
            compose.onNodeWithContentDescription(context.getString(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Synthetic Test Vault")).fetchSemanticsNodes().isNotEmpty() }

            compose.onNodeWithContentDescription(context.getString(R.string.action_lock)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription(context.getString(R.string.unlock_password_label))).fetchSemanticsNodes().isNotEmpty()
            }

            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            val deadline = System.currentTimeMillis() + 5_000
            while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(context.getString(R.string.unlock_password_label)).assertExists()
            assertTrue(compose.onAllNodes(hasText("Synthetic Test Vault")).fetchSemanticsNodes().isEmpty())
            assertEquals(app.vazie.keep.data.VaultState.Locked, container.repository.state.value)
        }
        vault.delete()
    }

    @Test
    fun keyFileVaultOpensWithoutAPasswordAndWrongKeyFilesAreExplained() {
        val vault = asset("keyfiles/vault-password-plus-keyfile.kdbx")
        val keyFile = asset("keyfiles/keyfile-v2.keyx")
        val wrong = asset("keyfiles/keyfile-wrong.keyx")
        val store = container.references
        store.save(VaultReference(vault.uri(), vault.name, 0, keyFileUri = wrong.uri(), keyFileName = wrong.name))
        val password = context.getString(R.string.unlock_password_label)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithText(wrong.name).assertExists()
            compose.onNodeWithContentDescription(password).performTextReplacement("correct horse battery staple")
            compose.onNodeWithContentDescription(context.getString(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.error_wrong_password_or_key_file))).fetchSemanticsNodes().isNotEmpty()
            }
        }

        store.save(VaultReference(vault.uri(), vault.name, 0, keyFileUri = File(context.cacheDir, "gone.keyx").uri(), keyFileName = "gone.keyx"))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(password).performTextReplacement("correct horse battery staple")
            compose.onNodeWithContentDescription(context.getString(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasText(context.getString(R.string.error_key_file_unavailable))).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(context.getString(R.string.key_file_change)).assertExists()
        }

        store.save(VaultReference(vault.uri(), vault.name, 0, keyFileUri = keyFile.uri(), keyFileName = keyFile.name))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(password).performTextReplacement("correct horse battery staple")
            compose.onNodeWithContentDescription(context.getString(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Key File Vault")).fetchSemanticsNodes().isNotEmpty() }
            val state = container.repository.state.value as app.vazie.keep.data.VaultState.Unlocked
            assertTrue(state.usedPassword && state.usedKeyFile)
        }
        listOf(vault, keyFile, wrong).forEach { it.delete() }
    }

    @Test
    fun keyFileOnlyVaultNeedsNoPassword() {
        val vault = asset("keyfiles/vault-keyfile-only.kdbx")
        val keyFile = asset("keyfiles/keyfile-v2.keyx")
        container.references
            .save(VaultReference(vault.uri(), vault.name, 0, keyFileUri = keyFile.uri(), keyFileName = keyFile.name))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(context.getString(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Key File Vault")).fetchSemanticsNodes().isNotEmpty() }
            val state = container.repository.state.value as app.vazie.keep.data.VaultState.Unlocked
            assertTrue(!state.usedPassword && state.usedKeyFile && !state.materialRetained)
        }
        listOf(vault, keyFile).forEach { it.delete() }
    }
}
