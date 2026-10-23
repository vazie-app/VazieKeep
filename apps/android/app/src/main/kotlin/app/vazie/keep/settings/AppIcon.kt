package app.vazie.keep.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import app.vazie.keep.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// One launcher alias per icon and plate, all pointing at MainActivity; exactly one is enabled at a time.
// The artwork is the same set as the iPhone app's alternate icons.
enum class AppIcon(
    val aliasName: String,
    @DrawableRes val foreground: Int,
    @DrawableRes val launcher: Int,
    @StringRes val label: Int,
    /** The name of this icon's own plate, in its colour, as each Vazie VPN mark has its signature field. */
    @StringRes val signatureLabel: Int,
) {
    Classic("KeepClassic", R.drawable.ic_launcher_foreground_classic, R.mipmap.ic_launcher_classic, R.string.icon_classic, R.string.icon_plate_dusk),
    Blue("KeepBlue", R.drawable.ic_launcher_foreground_blue, R.mipmap.ic_launcher_blue, R.string.icon_blue, R.string.icon_plate_abyss),
    Gray("KeepGray", R.drawable.ic_launcher_foreground_gray, R.mipmap.ic_launcher_gray, R.string.icon_gray, R.string.icon_plate_slate),
    Green("KeepGreen", R.drawable.ic_launcher_foreground_green, R.mipmap.ic_launcher_green, R.string.icon_green, R.string.icon_plate_forest),
    Pink("KeepPink", R.drawable.ic_launcher_foreground_pink, R.mipmap.ic_launcher_pink, R.string.icon_pink, R.string.icon_plate_rose),
    Red("KeepRed", R.drawable.ic_launcher_foreground_red, R.mipmap.ic_launcher_red, R.string.icon_red, R.string.icon_plate_garnet),
    Violet("KeepViolet", R.drawable.ic_launcher_foreground_violet, R.mipmap.ic_launcher_violet, R.string.icon_violet, R.string.icon_plate_deep),
    Yellow("KeepYellow", R.drawable.ic_launcher_foreground_yellow, R.mipmap.ic_launcher_yellow, R.string.icon_yellow, R.string.icon_plate_bronze),
    ;

    companion object {
        // The alias the manifest enables out of the box.
        val Default = Classic
    }
}

/**
 * The field an icon stands on in the launcher, as in Vazie VPN: [Milk], the icon's own [Signature] field in its colour
 * (Dusk under Cotton Candy, Abyss under Lagoon, …), and [Ink]. [Milk] is the plate every icon shipped with, so its
 * aliases keep their original names (`KeepClassic`); the others add the plate (`KeepClassicSignature`).
 */
enum class IconPlate(val aliasSuffix: String) {
    Milk(""),
    Signature("Signature"),
    Ink("Ink"),
    ;

    /** What the picker calls this plate under [icon]: the signature plate is named by the icon it belongs to. */
    @StringRes
    fun label(icon: AppIcon): Int = when (this) {
        Milk -> R.string.icon_plate_milk
        Signature -> icon.signatureLabel
        Ink -> R.string.icon_plate_ink
    }

    companion object {
        val Default = Milk
    }
}

/** What the launcher shows: an icon on a plate. */
data class LauncherIcon(val icon: AppIcon = AppIcon.Default, val plate: IconPlate = IconPlate.Default) {
    val aliasName: String get() = icon.aliasName + plate.aliasSuffix

    /** The adaptive icon the alias shows, for places that draw the app's own icon (the autofill header). */
    @get:DrawableRes
    val launcher: Int get() = when (icon) {
        AppIcon.Classic -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_classic
            IconPlate.Signature -> R.mipmap.ic_launcher_classic_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_classic_ink
        }
        AppIcon.Blue -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_blue
            IconPlate.Signature -> R.mipmap.ic_launcher_blue_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_blue_ink
        }
        AppIcon.Gray -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_gray
            IconPlate.Signature -> R.mipmap.ic_launcher_gray_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_gray_ink
        }
        AppIcon.Green -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_green
            IconPlate.Signature -> R.mipmap.ic_launcher_green_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_green_ink
        }
        AppIcon.Pink -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_pink
            IconPlate.Signature -> R.mipmap.ic_launcher_pink_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_pink_ink
        }
        AppIcon.Red -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_red
            IconPlate.Signature -> R.mipmap.ic_launcher_red_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_red_ink
        }
        AppIcon.Violet -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_violet
            IconPlate.Signature -> R.mipmap.ic_launcher_violet_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_violet_ink
        }
        AppIcon.Yellow -> when (plate) {
            IconPlate.Milk -> R.mipmap.ic_launcher_yellow
            IconPlate.Signature -> R.mipmap.ic_launcher_yellow_signature
            IconPlate.Ink -> R.mipmap.ic_launcher_yellow_ink
        }
    }

    companion object {
        val Default = LauncherIcon()
        val entries: List<LauncherIcon> = AppIcon.entries.flatMap { icon -> IconPlate.entries.map { LauncherIcon(icon, it) } }
    }
}

interface LauncherIcons {
    fun current(): LauncherIcon

    // Enables `choice` and disables every other alias. False if the system refused.
    fun select(choice: LauncherIcon): Boolean
}

class PackageManagerLauncherIcons(private val context: Context) : LauncherIcons {
    private val packageManager get() = context.packageManager

    override fun current(): LauncherIcon = LauncherIcon.entries.firstOrNull(::isEnabled) ?: LauncherIcon.Default

    override fun select(choice: LauncherIcon): Boolean = try {
        set(choice, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        LauncherIcon.entries.filter { it != choice }.forEach { set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    fun enabledAliases(): List<LauncherIcon> = LauncherIcon.entries.filter(::isEnabled)

    private fun isEnabled(choice: LauncherIcon) = when (packageManager.getComponentEnabledSetting(component(choice))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
        else -> choice == LauncherIcon.Default
    }

    private fun set(choice: LauncherIcon, state: Int) {
        packageManager.setComponentEnabledSetting(component(choice), state, PackageManager.DONT_KILL_APP)
    }

    private fun component(choice: LauncherIcon) = ComponentName(context.packageName, "app.vazie.keep.launcher.${choice.aliasName}")
}

class LauncherIconManager(private val launcher: LauncherIcons, private val settings: SecuritySettingsRepository) {
    private val mutableCurrent = MutableStateFlow(launcher.current())
    val current: StateFlow<LauncherIcon> = mutableCurrent.asStateFlow()

    // Each icon keeps the plate last chosen under it; one never chosen is on [IconPlate.Default].
    private val mutablePlates = MutableStateFlow(mapOf(mutableCurrent.value.icon to mutableCurrent.value.plate))
    val plates: StateFlow<Map<AppIcon, IconPlate>> = mutablePlates.asStateFlow()

    fun plateOf(icon: AppIcon): IconPlate = mutablePlates.value[icon] ?: IconPlate.Default

    // After an update the launcher keeps the chosen alias, and so does the stored choice; after a
    // reinstall both are back to the default. If they ever disagree, the stored choice wins.
    suspend fun reconcile() {
        val stored = settings.storedLauncherIcon()
        val actual = launcher.current()
        when {
            stored == null -> settings.setLauncherIcon(actual)
            stored != actual -> launcher.select(stored)
        }
        mutableCurrent.value = launcher.current()
        mutablePlates.value = settings.storedPlates() + (mutableCurrent.value.icon to mutableCurrent.value.plate)
    }

    /** Another icon, on the plate last chosen under it. */
    suspend fun select(icon: AppIcon): Boolean = select(LauncherIcon(icon, plateOf(icon)))

    /** The icon in force on another plate; the other icons keep theirs. */
    suspend fun select(plate: IconPlate): Boolean = select(mutableCurrent.value.copy(plate = plate))

    suspend fun select(choice: LauncherIcon): Boolean {
        if (choice == mutableCurrent.value) return true
        if (!launcher.select(choice)) return false
        settings.setLauncherIcon(choice)
        mutableCurrent.value = launcher.current()
        mutablePlates.value += choice.icon to choice.plate
        return true
    }
}

/** The plate under [icon] as the picker draws it: the colour resources of the launcher's background layer, start to end. */
internal fun IconPlate.colors(icon: AppIcon): List<Int> = when (this) {
    IconPlate.Milk -> listOf(R.color.launcher_background)
    IconPlate.Ink -> listOf(R.color.launcher_plate_ink)
    IconPlate.Signature -> when (icon) {
        AppIcon.Classic -> listOf(R.color.launcher_plate_classic_start, R.color.launcher_plate_classic_center, R.color.launcher_plate_classic_end)
        AppIcon.Blue -> listOf(R.color.launcher_plate_blue_start, R.color.launcher_plate_blue_center, R.color.launcher_plate_blue_end)
        AppIcon.Gray -> listOf(R.color.launcher_plate_gray_start, R.color.launcher_plate_gray_center, R.color.launcher_plate_gray_end)
        AppIcon.Green -> listOf(R.color.launcher_plate_green_start, R.color.launcher_plate_green_center, R.color.launcher_plate_green_end)
        AppIcon.Pink -> listOf(R.color.launcher_plate_pink_start, R.color.launcher_plate_pink_center, R.color.launcher_plate_pink_end)
        AppIcon.Red -> listOf(R.color.launcher_plate_red_start, R.color.launcher_plate_red_center, R.color.launcher_plate_red_end)
        AppIcon.Violet -> listOf(R.color.launcher_plate_violet_start, R.color.launcher_plate_violet_center, R.color.launcher_plate_violet_end)
        AppIcon.Yellow -> listOf(R.color.launcher_plate_yellow_start, R.color.launcher_plate_yellow_center, R.color.launcher_plate_yellow_end)
    }
}
