package app.vazie.keep.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LauncherIconManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class FakeLauncher(var enabled: LauncherIcon = LauncherIcon.Default, var refuse: Boolean = false) : LauncherIcons {
        val selections = mutableListOf<LauncherIcon>()

        override fun current() = enabled

        override fun select(choice: LauncherIcon): Boolean {
            if (refuse) return false
            selections += choice
            enabled = choice
            return true
        }
    }

    private fun TestScope.settings() = SecuritySettingsRepository(
        PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("settings.preferences_pb").also { it.delete() } },
    )

    @Test
    fun theDefaultIsTheClassicIconOnMilkAndEveryPairHasItsOwnAlias() {
        assertEquals(LauncherIcon(AppIcon.Classic, IconPlate.Milk), LauncherIcon.Default)
        assertEquals("KeepClassic", LauncherIcon.Default.aliasName, "the shipped alias keeps its name, so an update keeps the icon")
        assertEquals(8, AppIcon.entries.size)
        assertEquals(24, LauncherIcon.entries.size)
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.map { it.aliasName }.toSet().size)
        assertEquals(LauncherIcon.entries.size, LauncherIcon.entries.map { it.launcher }.toSet().size)
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.foreground }.toSet().size)
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.signatureLabel }.toSet().size, "every icon has a plate of its own")
        assertEquals("KeepBlueSignature", LauncherIcon(AppIcon.Blue, IconPlate.Signature).aliasName)
    }

    @Test
    fun aFreshInstallStoresWhateverTheLauncherHas() = runTest {
        val settings = settings()
        val manager = LauncherIconManager(FakeLauncher(), settings)
        assertNull(settings.storedLauncherIcon())
        manager.reconcile()
        assertEquals(LauncherIcon.Default, settings.storedLauncherIcon())
        assertEquals(LauncherIcon.Default, manager.current.value)
    }

    @Test
    fun aPlateKeepsTheIconAndBelongsToItAlone() = runTest {
        val settings = settings()
        val launcher = FakeLauncher()
        val manager = LauncherIconManager(launcher, settings)
        assertTrue(manager.select(AppIcon.Green))
        assertEquals(LauncherIcon(AppIcon.Green, IconPlate.Milk), launcher.enabled)
        assertTrue(manager.select(IconPlate.Ink))
        assertEquals(LauncherIcon(AppIcon.Green, IconPlate.Ink), manager.current.value)
        assertEquals(LauncherIcon(AppIcon.Green, IconPlate.Ink), settings.storedLauncherIcon())
        assertTrue(manager.select(IconPlate.Ink))
        assertEquals(2, launcher.selections.size, "choosing what is in force asks the launcher nothing")
        assertEquals(IconPlate.Milk, manager.plateOf(AppIcon.Red), "Ink under Green repaints no other icon")
        assertTrue(manager.select(AppIcon.Red))
        assertEquals(LauncherIcon(AppIcon.Red, IconPlate.Milk), launcher.enabled, "another icon comes on its own plate")
    }

    @Test
    fun twoIconsKeepTwoPlatesAcrossARestart() = runTest {
        val settings = settings()
        val launcher = FakeLauncher()
        val manager = LauncherIconManager(launcher, settings)
        manager.select(AppIcon.Green)
        manager.select(IconPlate.Ink)
        manager.select(AppIcon.Blue)
        manager.select(IconPlate.Signature)
        assertEquals(mapOf(AppIcon.Green to IconPlate.Ink, AppIcon.Blue to IconPlate.Signature), manager.plates.value - AppIcon.Classic)

        val restarted = LauncherIconManager(launcher, settings)
        restarted.reconcile()
        assertEquals(IconPlate.Ink, restarted.plateOf(AppIcon.Green))
        assertEquals(IconPlate.Signature, restarted.plateOf(AppIcon.Blue))
        assertEquals(IconPlate.Milk, restarted.plateOf(AppIcon.Pink))
        assertTrue(restarted.select(AppIcon.Green))
        assertEquals(LauncherIcon(AppIcon.Green, IconPlate.Ink), launcher.enabled)
    }

    @Test
    fun theIconStoredBeforePlatesWereKeptPerIconKeepsItsPlate() = runTest {
        val settings = settings()
        settings.setLauncherIcon(LauncherIcon(AppIcon.Violet, IconPlate.Ink))
        // As an earlier version left it: the icon and the plate in force, no plates per icon.
        assertEquals(mapOf(AppIcon.Violet to IconPlate.Ink), settings.storedPlates())
        val launcher = FakeLauncher(enabled = LauncherIcon(AppIcon.Violet, IconPlate.Ink))
        val manager = LauncherIconManager(launcher, settings)
        manager.reconcile()
        assertTrue(launcher.selections.isEmpty())
        assertEquals(IconPlate.Ink, manager.plateOf(AppIcon.Violet))
        assertEquals(IconPlate.Milk, manager.plateOf(AppIcon.Gray))
    }

    @Test
    fun aRefusedSwitchChangesNothing() = runTest {
        val settings = settings()
        val manager = LauncherIconManager(FakeLauncher(refuse = true), settings)
        assertFalse(manager.select(AppIcon.Red))
        assertEquals(LauncherIcon.Default, manager.current.value)
        assertNull(settings.storedLauncherIcon())
    }

    @Test
    fun theStoredChoiceWinsIfTheLauncherLostIt() = runTest {
        val settings = settings()
        settings.setLauncherIcon(LauncherIcon(AppIcon.Violet, IconPlate.Signature))
        val launcher = FakeLauncher(enabled = LauncherIcon.Default)
        val manager = LauncherIconManager(launcher, settings)
        manager.reconcile()
        assertEquals(LauncherIcon(AppIcon.Violet, IconPlate.Signature), launcher.enabled)
        assertEquals(LauncherIcon(AppIcon.Violet, IconPlate.Signature), manager.current.value)
    }

    @Test
    fun anIconStoredBeforePlatesIsOnMilk() = runTest {
        val settings = settings()
        settings.setLauncherIcon(LauncherIcon(AppIcon.Pink))
        val launcher = FakeLauncher(enabled = LauncherIcon(AppIcon.Pink))
        LauncherIconManager(launcher, settings).reconcile()
        assertTrue(launcher.selections.isEmpty())
    }
}
