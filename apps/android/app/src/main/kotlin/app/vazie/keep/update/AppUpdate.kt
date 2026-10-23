package app.vazie.keep.update

import androidx.core.content.edit
import app.vazie.keep.links.LocalizedText
import app.vazie.keep.links.isOpenable
import app.vazie.keep.review.InstallSource
import app.vazie.keep.review.InstallStore
import app.vazie.keep.review.listingLinks
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

data class Release(
    val latestVersionCode: Int,
    val latestVersionName: String,
    val minimumSupportedVersionCode: Int,
    val updateUrl: String,
    val message: LocalizedText? = null,
    /** The store Keep was installed from; `null` for the APK from the site or GitHub. */
    val store: InstallStore? = null,
)

/**
 * What «Download» opens. An app from a store is updated in that store: an APK from the site would not install over its
 * signature, and the store would lose track of the app.
 */
fun Release.downloadLinks(packageName: String): List<String> = store?.listingLinks(packageName) ?: listOf(updateUrl)

sealed interface UpdateState {
    data object Current : UpdateState
    data class Available(val release: Release) : UpdateState

    /** Older than the oldest supported build. Keep still opens every vault; only the banner stays. */
    data class Required(val release: Release) : UpdateState
}

/** The answer of `GET /v1/apps/keep/android/version`, or `null` when it is not the contract or opens anything but https. */
fun parseRelease(text: String): Release? = try {
    val root = JSONObject(text)
    val latest = root.getInt("latestVersionCode")
    val minimum = root.getInt("minimumSupportedVersionCode")
    val url = root.getString("updateUrl").takeIf { isOpenable(it) && it.startsWith("https://") }
    val name = root.getString("latestVersionName").takeIf { it.isNotBlank() && it.length <= 32 }
    val message = root.optJSONObject("message")?.let { m ->
        val ru = m.optString("ru").takeIf { it.isNotBlank() && it.length <= 300 }
        val en = m.optString("en").takeIf { it.isNotBlank() && it.length <= 300 }
        if (ru != null && en != null) LocalizedText(ru, en) else null
    }
    if (url == null || name == null || latest <= 0 || minimum <= 0 || minimum > latest) null else Release(latest, name, minimum, url, message)
} catch (_: JSONException) {
    null
}

fun Release.stateFor(installedVersionCode: Int): UpdateState = when {
    installedVersionCode < minimumSupportedVersionCode -> UpdateState.Required(this)
    installedVersionCode < latestVersionCode -> UpdateState.Available(this)
    else -> UpdateState.Current
}

fun interface ReleaseSource {
    suspend fun fetch(): Release?
}

class HttpReleaseSource(private val sharedOrigin: String, private val io: CoroutineDispatcher = Dispatchers.IO) : ReleaseSource {
    override suspend fun fetch(): Release? = withContext(io) {
        try {
            val connection = URL("$sharedOrigin/v1/apps/keep/android/version").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) null else parseRelease(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            } finally {
                connection.disconnect()
            }
        } catch (_: IOException) {
            null
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
    }
}

/** The backend's answer, marked with the store Keep was installed from. */
class InstalledReleaseSource(private val source: ReleaseSource, private val installed: InstallSource) : ReleaseSource {
    override suspend fun fetch(): Release? = source.fetch()?.copy(store = installed.store())
}

/** Where «Later» is remembered: the version the person put off, so the next one is shown again. */
interface DismissedUpdateStore {
    fun dismissed(): Int?
    fun dismiss(versionCode: Int)
}

/**
 * Whether a newer Vazie Keep is out. Asks at most once per [minIntervalMillis]; no answer keeps what is known. A version
 * put off with «Later» is not shown again; a required update is never hidden.
 */
class UpdateChecker(
    private val source: ReleaseSource,
    private val installedVersionCode: Int,
    private val dismissedStore: DismissedUpdateStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = 6 * 60 * 60 * 1000L,
) {
    private val mutex = Mutex()
    private var lastAskedAt: Long? = null
    private var release: Release? = null
    private val state = MutableStateFlow<UpdateState>(UpdateState.Current)

    val update: StateFlow<UpdateState> = state.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        val now = nowMillis()
        lastAskedAt?.let { if (now - it < minIntervalMillis) return@withLock }
        lastAskedAt = now
        release = source.fetch() ?: return@withLock
        publish()
    }

    /**
     * «Check for updates»: asks now, whatever the interval, and shows the answer even for a version put off with «Later».
     * `null` when the backend could not be read; what was known stays.
     */
    suspend fun checkNow(): UpdateState? = mutex.withLock {
        lastAskedAt = nowMillis()
        val fetched = source.fetch() ?: return@withLock null
        release = fetched
        fetched.stateFor(installedVersionCode).also { state.value = it }
    }

    fun later() {
        val shown = state.value as? UpdateState.Available ?: return
        dismissedStore.dismiss(shown.release.latestVersionCode)
        publish()
    }

    private fun publish() {
        val current = release?.stateFor(installedVersionCode) ?: UpdateState.Current
        state.value = if (current is UpdateState.Available && dismissedStore.dismissed() == current.release.latestVersionCode) UpdateState.Current else current
    }
}

class PreferencesDismissedUpdateStore(private val preferences: android.content.SharedPreferences) : DismissedUpdateStore {
    override fun dismissed(): Int? = preferences.getInt(KEY, 0).takeIf { it > 0 }

    override fun dismiss(versionCode: Int) {
        preferences.edit { putInt(KEY, versionCode) }
    }

    private companion object {
        const val KEY = "dismissed_version_code"
    }
}
