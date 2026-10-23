package app.vazie.keep.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class SecuritySettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun kotlinx.coroutines.test.TestScope.store() =
        PreferenceDataStoreFactory.create(scope = backgroundScope) { folder.newFile("settings.preferences_pb").also { it.delete() } }

    @Test
    fun defaultsAreTheSafeOnes() = runTest {
        val settings = SecuritySettingsRepository(store()).settings.first()
        assertEquals(SecuritySettings(), settings)
        assertEquals(AutoLockTimeout.Seconds30, settings.autoLockTimeout)
        assertEquals(true, settings.lockOnDeviceLock)
        assertEquals(ClipboardTimeout.Seconds30, settings.clipboardTimeout)
        assertEquals(false, settings.screenshotsAllowed)
        assertEquals(ThemeMode.System, settings.themeMode)
    }

    @Test
    fun valuesRoundTrip() = runTest {
        val repository = SecuritySettingsRepository(store())
        repository.setAutoLockTimeout(AutoLockTimeout.Minutes5)
        repository.setLockOnDeviceLock(false)
        repository.setClipboardTimeout(ClipboardTimeout.Never)
        repository.setScreenshotsAllowed(true)
        repository.setThemeMode(ThemeMode.Dark)
        assertEquals(
            SecuritySettings(AutoLockTimeout.Minutes5, false, ClipboardTimeout.Never, true, ThemeMode.Dark),
            repository.settings.first(),
        )
        assertEquals(true, repository.screenshotsAllowed.first())
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() = runTest {
        val dataStore = store()
        dataStore.edit {
            it[stringPreferencesKey("auto_lock_timeout")] = "Forever"
            it[stringPreferencesKey("clipboard_timeout")] = ""
        }
        val settings = SecuritySettingsRepository(dataStore).settings.first()
        assertEquals(AutoLockTimeout.Seconds30, settings.autoLockTimeout)
        assertEquals(ClipboardTimeout.Seconds30, settings.clipboardTimeout)
    }

    @Test
    fun timeoutsMapToDurations() {
        assertEquals(listOf(0.seconds, 15.seconds, 30.seconds, 1.minutes, 5.minutes, 15.minutes), AutoLockTimeout.entries.map { it.duration })
        assertEquals(listOf(null, 15.seconds, 30.seconds, 60.seconds), ClipboardTimeout.entries.map { it.duration })
    }
}
