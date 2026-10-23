package app.vazie.keep

import android.content.Context
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.data.VaultReference
import app.vazie.keep.data.VaultState
import app.vazie.keep.ui.icons.ItemIcon
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import app.vazie.keep.siteicons.SiteIcons
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals

// A group and an entry get an icon from the picker, found by typing its name, and keep it in the file.
@RunWith(AndroidJUnit4::class)
class IconFlowTest {
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
        vault = File(context.cacheDir, "icon-flow.kdbx")
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

    private fun pick(query: String, icon: ItemIcon) {
        compose.onNode(hasSetTextAction().and(hasText(string(R.string.icon_search)))).performTextInput(query)
        val name = string(icon.label)
        compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(name)).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithContentDescription(name).performClick()
    }

    @Test
    fun siteIconsAreFetchedAfterAYesAndKeptInTheFile() {
        val png = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(0, 150, 136)) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val real = container.siteIcons
        container.siteIcons = object : SiteIcons {
            override suspend fun fetch(host: String) = png.takeIf { host == "bank.example.com" }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
                compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                compose.waitUntil(15_000) { container.repository.state.value is VaultState.Unlocked }
                waitFor("Web")

                compose.onNodeWithContentDescription(string(R.string.action_add)).performClick()
                compose.onNodeWithText(string(R.string.icons_assign_action)).performClick()
                waitFor(string(R.string.site_icons_title))
                assertEquals(null, container.repository.search("Bank").single().customIcon, "nothing is fetched before the person agrees")
                compose.onNodeWithText(string(R.string.site_icons_confirm)).performClick()
                compose.waitUntil(10_000) { container.repository.search("Bank").single().customIcon != null }
            }
        } finally {
            container.siteIcons = real
        }
        lockOnMain()
        runBlocking { container.repository.unlock(password.toByteArray()) }
        val image = container.repository.search("Bank").single().customIcon
        kotlin.test.assertContentEquals(png, image?.let(container.repository::customIcon))
        assertEquals(ItemIcon.Mail.id, container.repository.search("Example Mail").single().icon, "a site that gave nothing gets a built-in icon")
        assertEquals(0, container.repository.search("Old Account").single().icon, "no address, no icon")
    }

    @Test
    fun aNewEntryGetsItsSiteIconOnlyWhenThatIsSwitchedOn() {
        val png = java.io.ByteArrayOutputStream().also { out ->
            android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(0, 150, 136)) }
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }.toByteArray()
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val real = container.siteIcons
        container.siteIcons = object : SiteIcons {
            override suspend fun fetch(host: String): ByteArray? {
                asked += host
                return png
            }
        }
        fun create(title: String, url: String) {
            compose.onNodeWithContentDescription(string(R.string.action_add)).performClick()
            compose.onNodeWithText(string(R.string.action_new_entry)).performClick()
            waitFor(string(R.string.editor_title_new))
            compose.onNode(hasSetTextAction().and(hasText(string(R.string.field_title)))).performTextInput(title)
            compose.onNode(hasSetTextAction().and(hasText(string(R.string.field_url)))).performTextInput(url)
            compose.onNodeWithText(string(R.string.action_save)).performClick()
            compose.waitUntil(10_000) { container.repository.search(title).isNotEmpty() }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
                compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
                compose.waitUntil(15_000) { container.repository.state.value is VaultState.Unlocked }
                waitFor("Web")

                runBlocking { container.settingsRepository.setSiteIconsAutomatic(false) }
                compose.waitUntil(5_000) { !container.settings.value.siteIconsAutomatic }
                create("Quiet Shop", "https://shop.example.net")
                compose.waitForIdle()
                assertEquals(emptyList(), asked.toList(), "switched off, no site is asked")
                assertEquals(ItemIcon.Shopping.id, container.repository.search("Quiet Shop").single().icon, "a built-in icon, offline")

                runBlocking { container.settingsRepository.setSiteIconsAutomatic(true) }
                compose.waitUntil(5_000) { container.settings.value.siteIconsAutomatic }
                waitFor("Web")
                create("Почта", "https://почта.рф/login")
                compose.waitUntil(10_000) { container.repository.search("Почта").single().customIcon != null }
                assertEquals(listOf("xn--80a1acny.xn--p1ai"), asked.toList())
            }
        } finally {
            container.siteIcons = real
            runBlocking { container.settingsRepository.setSiteIconsAutomatic(false) }
        }
    }

    @Test
    fun aGroupAndAnEntryGetAnIconFoundByName() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithContentDescription(string(R.string.unlock_action)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(15_000) { container.repository.state.value is VaultState.Unlocked }
            waitFor("Web")

            compose.onNodeWithContentDescription(string(R.string.group_actions, "Web")).performClick()
            compose.onNodeWithText(string(R.string.action_change_icon)).performClick()
            waitFor(string(R.string.icon_picker_group, "Web"))
            pick("mail", ItemIcon.Mail)
            compose.waitUntil(10_000) { container.repository.groups().single { it.name == "Web" }.icon == ItemIcon.Mail.id }

            compose.onNodeWithText("Web").performClick()
            waitFor("Example Mail")
            compose.onNodeWithText("Example Mail").performClick()
            compose.onNodeWithContentDescription(string(R.string.action_more)).performClick()
            compose.onNodeWithText(string(R.string.action_change_icon)).performClick()
            waitFor(string(R.string.icon_picker_entry))
            pick("bank", ItemIcon.Bank)
            compose.waitUntil(10_000) { runBlocking { container.repository.search("Example Mail").single().icon } == ItemIcon.Bank.id }
        }
        lockOnMain()
        runBlocking { container.repository.unlock(password.toByteArray()) }
        assertEquals(ItemIcon.Mail.id, container.repository.groups().single { it.name == "Web" }.icon)
        assertEquals(ItemIcon.Bank.id, container.repository.search("Example Mail").single().icon)
    }
}
