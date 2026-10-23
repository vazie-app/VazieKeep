package app.vazie.keep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.autofill.AuthProblem
import app.vazie.keep.autofill.AuthUiState
import app.vazie.keep.autofill.AutofillSheet
import app.vazie.keep.autofill.Choosing
import app.vazie.keep.autofill.ConfirmPane
import app.vazie.keep.autofill.FailedPane
import app.vazie.keep.autofill.IndexedEntry
import app.vazie.keep.autofill.SaveChoice
import app.vazie.keep.autofill.SavePlan
import app.vazie.keep.autofill.SaveUiState
import app.vazie.keep.autofill.UnlockForm
import app.vazie.keep.autofill.UnlockMessage
import app.vazie.keep.ui.designsystem.DockTab
import app.vazie.keep.ui.designsystem.HiddenDots
import app.vazie.keep.ui.designsystem.KeepBadge
import app.vazie.keep.ui.designsystem.KeepBadgeTone
import app.vazie.keep.ui.designsystem.KeepBanner
import app.vazie.keep.ui.designsystem.KeepBannerTone
import app.vazie.keep.ui.designsystem.KeepButton
import app.vazie.keep.ui.designsystem.KeepButtonSize
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepCard
import app.vazie.keep.ui.designsystem.KeepChip
import app.vazie.keep.ui.designsystem.KeepDesign
import app.vazie.keep.ui.designsystem.KeepDock
import app.vazie.keep.ui.designsystem.KeepScreen
import app.vazie.keep.ui.designsystem.KeepSectionLabel
import app.vazie.keep.ui.designsystem.KeepSwitch
import app.vazie.keep.ui.designsystem.KeepStrengthMeter
import app.vazie.keep.ui.designsystem.KeepTextField
import app.vazie.keep.ui.designsystem.KeepTheme
import app.vazie.keep.ui.designsystem.secretText
import app.vazie.keep.ui.icons.VazieIcons
import androidx.compose.material3.Text
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.flow.emptyFlow

// Renders design-system pieces and the Autofill sheets in both themes and writes PNGs to the app's
// files directory (compose capture ignores FLAG_SECURE). Pull them with run-as.
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class DesignGalleryTest {
    @get:Rule
    val compose = createComposeRule()

    private val out = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "gallery").apply { mkdirs() }

    private var dark by androidx.compose.runtime.mutableStateOf(false)
    private var body by androidx.compose.runtime.mutableStateOf<@Composable () -> Unit>({})
    private var frameHeight by androidx.compose.runtime.mutableIntStateOf(880)
    private var started = false

    private fun shoot(name: String, height: Int = 880, content: @Composable () -> Unit) {
        if (!started) {
            started = true
            compose.setContent {
                KeepTheme(darkTheme = dark) {
                    KeepScreen(Modifier.size(412.dp, frameHeight.dp)) { body() }
                }
            }
        }
        for (mode in listOf(false, true)) {
            compose.runOnUiThread {
                dark = mode
                frameHeight = height
                body = content
            }
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(out, "$name-${if (mode) "dark" else "light"}.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private val entries = listOf(
        IndexedEntry("1", "Pedalo", "mira.solenko@proton.me", "pedalo.app"),
        IndexedEntry("2", "Pedalo — family plan", "mira.family@proton.me", "pedalo.app"),
        IndexedEntry("3", "Strava", "mira@proton.me", "strava.com"),
    )

    @Test
    fun autofillSheets() {
        shoot("sheet-choose") {
            AutofillSheet("Fill Pedalo", "app.pedalo.android", "Pedalo", onCancel = {}) {
                Choosing(AuthUiState.Choosing(entries, true), remember = true, offerRemember = true, onRemember = {}, onSearch = {}, onChoose = {})
            }
        }
        shoot("sheet-unlock") {
            AutofillSheet("Unlock to fill", "pedalo.app", "Pedalo", onCancel = {}) {
                UnlockForm("Pedalo", "mira.solenko@proton.me", true, false, UnlockMessage.WrongPassword, "home.keyx", TextFieldState(), emptyFlow(), R.string.autofill_unlock_fill, {}, {}, {})
            }
        }
        shoot("sheet-save-update") {
            AutofillSheet("Update Pedalo?", "Pedalo", "Pedalo", onCancel = {}) {
                ConfirmPane(
                    SaveUiState.Confirm("Pedalo", "mira.solenko@proton.me", SavePlan.Update(entries[0]), SaveChoice.Update(entries[0])),
                    onSelect = {}, onToggleShown = {}, onConfirm = {}, onCancel = {},
                )
            }
        }
        shoot("sheet-save-create") {
            AutofillSheet("Save to Vazie Keep?", "Pedalo", "Pedalo", onCancel = {}) {
                ConfirmPane(
                    SaveUiState.Confirm("Pedalo", "mira.solenko@proton.me", SavePlan.Create(listOf(entries[1])), SaveChoice.Create, shown = "Tq9#rWm2-vLp8!Kz"),
                    onSelect = {}, onToggleShown = {}, onConfirm = {}, onCancel = {},
                )
            }
        }
        shoot("sheet-failed") {
            AutofillSheet("Fill Pedalo", "app.pedalo.android", "Pedalo", onCancel = {}) {
                FailedPane(AuthProblem.VaultChangedElsewhere, onOpenApp = {}, onCancel = {})
            }
        }
    }

    @Test
    fun kit() {
        shoot("kit", height = 1500) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeepButton("Create a new database", {}, icon = VazieIcons.Add)
                KeepButton("Open an existing .kdbx", {}, style = KeepButtonStyle.Tonal, icon = VazieIcons.File)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeepButton("Save", {}, size = KeepButtonSize.Pill, fullWidth = false)
                    KeepButton("Change", {}, style = KeepButtonStyle.Text, size = KeepButtonSize.Pill, fullWidth = false)
                    KeepButton("Discard", {}, style = KeepButtonStyle.Caution, size = KeepButtonSize.Pill, fullWidth = false)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeepChip("All", true, {}, count = "1,284")
                    KeepChip("Favorites", false, {}, count = "18", icon = VazieIcons.Star)
                    KeepChip("Logins", false, {}, count = "1,102")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeepBadge("Hidden", icon = VazieIcons.EyeOff)
                    KeepBadge("Visible · hides in 30 s", icon = VazieIcons.Eye, tone = KeepBadgeTone.Accent)
                    KeepBadge("Read-only", tone = KeepBadgeTone.Caution)
                }
                KeepBanner("Read-only. Opened from Drive without write access.", icon = VazieIcons.Pencil) {
                    KeepButton("Save a copy", {}, style = KeepButtonStyle.Tonal, size = KeepButtonSize.Pill, fullWidth = false)
                }
                KeepBanner("That didn't open Personal. Check Caps Lock.", tone = KeepBannerTone.Error)
                KeepBanner("Clipboard never clears.", tone = KeepBannerTone.Caution)
                KeepTextField(TextFieldState("mira.solenko@proton.me"), "Username")
                KeepTextField(TextFieldState(), "Master password", placeholder = "Master password", error = "That didn't open Personal.")
                KeepStrengthMeter(4, "Formidable", bits = "about 131 bits")
                KeepStrengthMeter(0, "Fragile", bits = "about 34 bits")
                KeepCard {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        HiddenDots()
                        KeepSwitch(true)
                        KeepSwitch(false)
                    }
                    Text(secretText("vX7qRt3!mPz8Kw#u2e", KeepDesign.colors), style = KeepDesign.type.secret, modifier = Modifier.padding(16.dp))
                }
                KeepDock(DockTab.Keep, {}, {}, {})
            }
        }
    }
}
