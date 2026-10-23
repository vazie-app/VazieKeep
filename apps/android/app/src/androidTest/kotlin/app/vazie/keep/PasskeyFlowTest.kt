package app.vazie.keep

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
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
import app.vazie.keep.data.EditOutcome
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// An entry that holds a passkey says so, the index Android is answered from follows the vault, and removing
// the passkey is a step of its own that leaves the entry.
@RunWith(AndroidJUnit4::class)
class PasskeyFlowTest {
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
        vault = File(context.cacheDir, "passkey-flow.kdbx")
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

    private fun waitFor(text: String) = compose.waitUntil(10_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun anEntryShowsItsPasskeyAndRemovingItLeavesTheEntry() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitUntil(15_000) { container.repository.state.value is VaultState.Unlocked }
            waitFor("Web")

            val root = (container.repository.state.value as VaultState.Unlocked).info.rootGroupId
            val made = runBlocking { container.repository.edit { it.createPasskey(root, "shop.example.com", "Example Shop", "dana", "user-42".toByteArray()) } }
            assertIs<EditOutcome.Saved<*>>(made)
            compose.waitUntil(10_000) { container.passkeyIndex.load()?.passkeys?.size == 1 }
            val indexed = container.passkeyIndex.load()!!.passkeys.single()
            assertEquals(listOf("shop.example.com", "dana", "Example Shop"), listOf(indexed.rpId, indexed.userName, indexed.title))

            waitFor("Example Shop")
            compose.onNodeWithText("Example Shop").performClick()
            waitFor(string(R.string.passkey_title))
            waitFor(string(R.string.passkey_subject, "shop.example.com", "dana"))

            compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
            compose.onNodeWithText(string(R.string.passkey_remove)).performClick()
            waitFor(string(R.string.passkey_remove_title))
            assertNotNull(container.repository.search("Example Shop").single().passkey, "nothing is removed before the answer")
            compose.onAllNodes(hasText(string(R.string.passkey_remove)))[0].performClick()
            compose.waitUntil(10_000) { container.repository.search("Example Shop").single().passkey == null }
            compose.waitUntil(10_000) { container.passkeyIndex.load()?.passkeys?.isEmpty() == true }
        }
        lockOnMain()
        runBlocking { container.repository.unlock(password.toByteArray()) }
        assertNull(container.repository.search("Example Shop").single().passkey)
    }
}
