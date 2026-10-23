package app.vazie.keep.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

enum class AutoLockTimeout(val duration: Duration) {
    Immediately(Duration.ZERO),
    Seconds15(15.seconds),
    Seconds30(30.seconds),
    Minute1(1.minutes),
    Minutes5(5.minutes),
    Minutes15(15.minutes),
}

enum class ClipboardTimeout(val duration: Duration?) {
    Never(null),
    Seconds15(15.seconds),
    Seconds30(30.seconds),
    Seconds60(60.seconds),
}

enum class ThemeMode { System, Light, Dark }

data class SecuritySettings(
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.Seconds30,
    val lockOnDeviceLock: Boolean = true,
    val clipboardTimeout: ClipboardTimeout = ClipboardTimeout.Seconds30,
    val screenshotsAllowed: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.System,
    val autofillSaveOffers: Boolean = true,
    val siteIconsAutomatic: Boolean = false,
)

class SecuritySettingsRepository(private val store: DataStore<Preferences>) {
    val settings: Flow<SecuritySettings> = store.data.map { it.toSettings() }.distinctUntilChanged()

    val autoLockTimeout: Flow<AutoLockTimeout> = settings.map { it.autoLockTimeout }.distinctUntilChanged()
    val lockOnDeviceLock: Flow<Boolean> = settings.map { it.lockOnDeviceLock }.distinctUntilChanged()
    val clipboardTimeout: Flow<ClipboardTimeout> = settings.map { it.clipboardTimeout }.distinctUntilChanged()
    val screenshotsAllowed: Flow<Boolean> = settings.map { it.screenshotsAllowed }.distinctUntilChanged()
    val themeMode: Flow<ThemeMode> = settings.map { it.themeMode }.distinctUntilChanged()

    suspend fun setAutoLockTimeout(value: AutoLockTimeout) = store.edit { it[AUTO_LOCK] = value.name }

    suspend fun setLockOnDeviceLock(value: Boolean) = store.edit { it[LOCK_ON_DEVICE_LOCK] = value }

    suspend fun setClipboardTimeout(value: ClipboardTimeout) = store.edit { it[CLIPBOARD] = value.name }

    suspend fun setScreenshotsAllowed(value: Boolean) = store.edit { it[SCREENSHOTS] = value }

    suspend fun setAutofillSaveOffers(value: Boolean) = store.edit { it[AUTOFILL_SAVE] = value }

    suspend fun setSiteIconsAutomatic(value: Boolean) = store.edit { it[SITE_ICONS] = value }

    suspend fun setThemeMode(value: ThemeMode) = store.edit { it[THEME] = value.name }

    /** The launcher icon last chosen, `null` before the first. A stored icon without a plate is on [IconPlate.Default]. */
    suspend fun storedLauncherIcon(): LauncherIcon? {
        val preferences = store.data.first()
        val icon = preferences[APP_ICON]?.let { name -> AppIcon.entries.firstOrNull { it.name == name } } ?: return null
        return LauncherIcon(icon, enumOr(preferences[APP_ICON_PLATE], IconPlate.Default))
    }

    /** The plate last chosen under each icon. The icon in force before plates were kept per icon has the plate then in force. */
    suspend fun storedPlates(): Map<AppIcon, IconPlate> {
        val preferences = store.data.first()
        val plates = preferences[APP_ICON_PLATES].orEmpty().mapNotNull { pair ->
            val icon = AppIcon.entries.firstOrNull { it.name == pair.substringBefore('=') }
            val plate = IconPlate.entries.firstOrNull { it.name == pair.substringAfter('=') }
            if (icon != null && plate != null) icon to plate else null
        }.toMap()
        val current = storedLauncherIcon() ?: return plates
        return if (current.icon in plates) plates else plates + (current.icon to current.plate)
    }

    /** Sets [value] in force and remembers its plate as the one under its icon. */
    suspend fun setLauncherIcon(value: LauncherIcon) = store.edit {
        it[APP_ICON] = value.icon.name
        it[APP_ICON_PLATE] = value.plate.name
        it[APP_ICON_PLATES] = it[APP_ICON_PLATES].orEmpty().filterNot { pair -> pair.substringBefore('=') == value.icon.name }.toSet() +
            "${value.icon.name}=${value.plate.name}"
    }

    private fun Preferences.toSettings(): SecuritySettings {
        val defaults = SecuritySettings()
        return SecuritySettings(
            autoLockTimeout = enumOr(this[AUTO_LOCK], defaults.autoLockTimeout),
            lockOnDeviceLock = this[LOCK_ON_DEVICE_LOCK] ?: defaults.lockOnDeviceLock,
            clipboardTimeout = enumOr(this[CLIPBOARD], defaults.clipboardTimeout),
            screenshotsAllowed = this[SCREENSHOTS] ?: defaults.screenshotsAllowed,
            themeMode = enumOr(this[THEME], defaults.themeMode),
            autofillSaveOffers = this[AUTOFILL_SAVE] ?: defaults.autofillSaveOffers,
            siteIconsAutomatic = this[SITE_ICONS] ?: defaults.siteIconsAutomatic,
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private companion object {
        val AUTO_LOCK = stringPreferencesKey("auto_lock_timeout")
        val LOCK_ON_DEVICE_LOCK = booleanPreferencesKey("lock_on_device_lock")
        val CLIPBOARD = stringPreferencesKey("clipboard_timeout")
        val SCREENSHOTS = booleanPreferencesKey("screenshots_allowed")
        val THEME = stringPreferencesKey("theme_mode")
        val APP_ICON = stringPreferencesKey("app_icon")
        val APP_ICON_PLATE = stringPreferencesKey("app_icon_plate")
        val APP_ICON_PLATES = stringSetPreferencesKey("app_icon_plates")
        val AUTOFILL_SAVE = booleanPreferencesKey("autofill_save_offers")
        val SITE_ICONS = booleanPreferencesKey("site_icons_automatic")
    }
}
