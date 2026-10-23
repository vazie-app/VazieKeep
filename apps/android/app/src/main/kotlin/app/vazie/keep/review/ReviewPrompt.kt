package app.vazie.keep.review

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun interface InstallSource {
    fun store(): InstallStore?
}

class PackageInstallSource(private val context: Context) : InstallSource {
    override fun store(): InstallStore? {
        val installer = runCatching {
            val packages = context.packageManager
            if (Build.VERSION.SDK_INT >= 30) {
                packages.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packages.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
        return when (installer) {
            "com.android.vending" -> InstallStore.GooglePlay
            "ru.vk.store" -> InstallStore.RuStore
            else -> null
        }
    }
}

class ReviewPrompt(
    private val source: InstallSource,
    private val preferences: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val mutablePending = MutableStateFlow<InstallStore?>(null)
    val pending: StateFlow<InstallStore?> = mutablePending.asStateFlow()

    private var state = ReviewState()
    private var counted = false

    fun onLaunch() {
        scope.launch {
            state = read().afterLaunch()
            write(state)
            counted = true
        }
    }

    // Called when the vault has just been opened: the one calm moment to ask.
    fun onVaultOpened() {
        scope.launch {
            if (!counted) state = read()
            val store = source.store()
            if (state.isDue(store)) mutablePending.value = store
        }
    }

    fun rated() = finish { it.answered() }

    fun postponed() = finish { it.postponed() }

    fun declined() = finish { it.answered() }

    private fun finish(change: (ReviewState) -> ReviewState) {
        mutablePending.value = null
        scope.launch {
            state = change(state)
            write(state)
        }
    }

    private suspend fun read(): ReviewState = preferences.data.map {
        ReviewState(
            launches = it[LAUNCHES] ?: 0,
            nextAskAt = it[NEXT_ASK_AT] ?: ReviewState.FIRST_ASK_AT_LAUNCH,
            asks = it[ASKS] ?: 0,
            finished = it[FINISHED] ?: false,
        )
    }.first()

    private suspend fun write(value: ReviewState) {
        preferences.edit {
            it[LAUNCHES] = value.launches
            it[NEXT_ASK_AT] = value.nextAskAt
            it[ASKS] = value.asks
            it[FINISHED] = value.finished
        }
    }

    private companion object {
        val LAUNCHES = intPreferencesKey("launches")
        val NEXT_ASK_AT = intPreferencesKey("next_ask_at")
        val ASKS = intPreferencesKey("asks")
        val FINISHED = booleanPreferencesKey("finished")
    }
}

/** The app's page in the store: the store's own app first, its site when that app is gone. */
fun InstallStore.listingLinks(packageName: String): List<String> = when (this) {
    InstallStore.GooglePlay -> listOf("market://details?id=$packageName", "https://play.google.com/store/apps/details?id=$packageName")
    InstallStore.RuStore -> listOf("rustore://apps.rustore.ru/app/$packageName", "https://apps.rustore.ru/app/$packageName")
}

fun openStoreListing(context: Context, store: InstallStore) = openFirst(context, store.listingLinks(context.packageName))

/** Opens the first of [links] that something on the phone can open. */
fun openFirst(context: Context, links: List<String>) {
    for (link in links) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
