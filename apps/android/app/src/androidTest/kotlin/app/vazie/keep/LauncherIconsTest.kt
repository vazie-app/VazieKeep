package app.vazie.keep

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.settings.AppIcon
import app.vazie.keep.settings.IconPlate
import app.vazie.keep.settings.LauncherIcon
import app.vazie.keep.settings.PackageManagerLauncherIcons
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class LauncherIconsTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val icons = PackageManagerLauncherIcons(context)
    private val container get() = (context.applicationContext as KeepApplication).container

    @After
    fun restoreDefault() {
        runBlocking { container.launcherIcons.select(LauncherIcon.Default) }
    }

    private fun launcherEntries(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        return context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
    }

    @Test
    fun everyIconCanBeSelectedAndExactlyOneLauncherEntryRemains() {
        LauncherIcon.entries.forEach { icon ->
            assertTrue(icons.select(icon), "selecting $icon")
            assertEquals(icon, icons.current())
            assertEquals(listOf(icon), icons.enabledAliases())
            assertEquals(listOf("app.vazie.keep.launcher.${icon.aliasName}"), launcherEntries())
        }
    }

    @Test
    fun selectingTheSameIconTwiceChangesNothing() {
        val pink = LauncherIcon(AppIcon.Pink, IconPlate.Signature)
        assertTrue(icons.select(pink))
        assertTrue(icons.select(pink))
        assertEquals(listOf(pink), icons.enabledAliases())
        assertEquals(1, launcherEntries().size)
    }

    @Test
    fun theManagerStoresTheChoiceAndReconcilesItBack() = runBlocking {
        val violet = LauncherIcon(AppIcon.Violet, IconPlate.Ink)
        assertTrue(container.launcherIcons.select(violet))
        assertEquals(violet, container.settingsRepository.storedLauncherIcon())
        icons.select(LauncherIcon.Default)
        container.launcherIcons.reconcile()
        assertEquals(violet, icons.current())
        assertEquals(1, launcherEntries().size)
        Unit
    }

    @Test
    fun everyAliasLabelsItselfAsTheApp() {
        LauncherIcon.entries.forEach { icon ->
            val info = context.packageManager.getActivityInfo(
                android.content.ComponentName(context.packageName, "app.vazie.keep.launcher.${icon.aliasName}"),
                android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS,
            )
            assertEquals("Vazie Keep", info.loadLabel(context.packageManager).toString())
            assertEquals("app.vazie.keep.MainActivity", info.targetActivity)
        }
    }
}
