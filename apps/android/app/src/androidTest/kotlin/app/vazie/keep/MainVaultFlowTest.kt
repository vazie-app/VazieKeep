package app.vazie.keep

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.data.VaultDescriptor
import app.vazie.keep.data.VaultState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Two vaults and no Keep+: the app asks for the main vault once, then edits it and opens the other one
// for reading. The choice is kept by the app, so the test needs a fresh install (or `no_backup/main_vault`
// removed) and an account without Keep+; otherwise it is skipped.
@RunWith(AndroidJUnit4::class)
class MainVaultFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private val password = "correct horse battery staple"
    private val files = mutableListOf<File>()

    private fun addVault(name: String): VaultDescriptor {
        val file = File(context.cacheDir, "$name.kdbx")
        InstrumentationRegistry.getInstrumentation().context.assets.open("editable/rich-aes-argon2d-gzip.kdbx").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        files += file
        val uri = file.toURI().toString().replace("file:/", "file:///")
        return container.registry.add(VaultDescriptor(id = name, name = name, fileName = file.name, uri = uri, lastUsedAt = 1), activate = name == "Everyday")
    }

    @Before
    fun setUp() = runBlocking {
        lockOnMain()
        container.biometrics.disable()
        container.forgetAllVaults()
        addVault("Everyday")
        addVault("Archive")
        Unit
    }

    @After
    fun tearDown() {
        lockOnMain()
        container.forgetAllVaults()
        files.forEach { it.delete() }
    }

    private fun string(id: Int) = context.getString(id)

    private fun lockOnMain() = InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String, timeout: Long = 10_000) = compose.waitUntil(timeout) { exists(text) }

    private fun unlock() {
        compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
        compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        compose.waitUntil(15_000) { container.repository.state.value is VaultState.Unlocked }
    }

    @Test
    fun theMainVaultIsChosenOnceAndTheOtherOpensForReading() {
        container.vaultPolicy.refresh()
        assumeTrue("needs an account without Keep+ and no main vault chosen yet", container.vaultPolicy.current().mustChooseMain)
        ActivityScenario.launch(MainActivity::class.java).use {
            waitForText(string(R.string.main_vault_title))
            compose.onNodeWithText("Archive.kdbx").performClick()
            compose.onNodeWithText(string(R.string.main_vault_confirm)).performClick()
            compose.waitUntil(10_000) { !exists(string(R.string.main_vault_title)) }
            assertEquals("Archive", container.vaultPolicy.current().mainId)
            assertFalse(container.vaultPolicy.chooseMain("Everyday"))

            compose.onNodeWithText(string(R.string.unlock_databases)).performClick()
            waitForText(string(R.string.hub_more_vaults_title))
            compose.onNodeWithText("Everyday").performClick()
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription(string(R.string.unlock_password_label))).fetchSemanticsNodes().isNotEmpty() }
            unlock()
            val everyday = container.repository.state.value as VaultState.Unlocked
            assertFalse(everyday.canEdit)
            assertTrue(everyday.readOnlyByPlan)
            waitForText(string(R.string.vault_read_only_plan))

            lockOnMain()
            InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.activate("Archive"); container.registry.activate("Archive") }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            unlock()
            val archive = container.repository.state.value as VaultState.Unlocked
            assertTrue(archive.canEdit)
            assertFalse(archive.readOnlyByPlan)
            assertFalse(exists(string(R.string.vault_read_only_plan)))
        }
    }
}
