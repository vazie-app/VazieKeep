package app.vazie.keep.links

import java.io.File
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
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Russian always; English when the operator wrote it. Any language but Russian gets the English, or the Russian. */
data class FaqText(val ru: String, val en: String? = null) {
    fun pick(russian: Boolean): String = if (russian) ru else en ?: ru
}

/** Where an answer points: an `https` page, or a path on the Vazie site (`/legal/refunds`). */
data class FaqLink(val url: String, val label: FaqText) {
    val isSitePath: Boolean get() = url.startsWith("/")
}

data class FaqItem(val key: String, val question: FaqText, val answer: FaqText, val link: FaqLink? = null)

/**
 * The answer of `GET /v1/faq/keep`, edited in the admin panel. `null` when it is not a list, or when it has questions
 * and none of them can be shown; an empty list is the operator's own answer and is believed. A question without its
 * Russian text is dropped; a link that is not `https` or a site path is dropped and its question stays.
 */
fun parseFaq(text: String): List<FaqItem>? = try {
    val raw = JSONObject(text).optJSONArray("items")
    if (raw == null) {
        null
    } else {
        val items = (0 until raw.length()).mapNotNull { raw.optJSONObject(it)?.toFaqItem() }
        if (raw.length() > 0 && items.isEmpty()) null else items
    }
} catch (_: JSONException) {
    null
}

fun encodeFaq(items: List<FaqItem>): String = JSONObject().put(
    "items",
    JSONArray(
        items.map { item ->
            JSONObject()
                .put("key", item.key)
                .put("question", item.question.toJson())
                .put("answer", item.answer.toJson())
                .apply { item.link?.let { put("link", JSONObject().put("url", it.url).put("label", it.label.toJson())) } }
        },
    ),
).toString()

/** `https` with a host, or a plain path on the site that is not `//host`. */
fun isFaqLink(url: String): Boolean = when {
    url.length > 512 -> false
    url.startsWith("//") -> false
    url.startsWith("/") -> SITE_PATH.matches(url)
    else -> url.startsWith("https://") && isOpenable(url)
}

private val SITE_PATH = Regex("^/[A-Za-z0-9._~/-]*(#[A-Za-z0-9_-]+)?$")

private fun FaqText.toJson() = JSONObject().put("ru", ru).apply { en?.let { put("en", it) } }

private fun JSONObject.toFaqItem(): FaqItem? {
    val link = optJSONObject("link")?.let { link ->
        val url = link.optString("url").takeIf(::isFaqLink) ?: return@let null
        FaqLink(url, link.optJSONObject("label")?.toFaqText(max = 80, multiline = false) ?: return@let null)
    }
    return FaqItem(
        key = optString("key").takeIf { it.isNotBlank() } ?: return null,
        question = optJSONObject("question")?.toFaqText(max = 200, multiline = false) ?: return null,
        answer = optJSONObject("answer")?.toFaqText(max = 2000, multiline = true) ?: return null,
        link = link,
    )
}

private fun JSONObject.toFaqText(max: Int, multiline: Boolean): FaqText? {
    fun usable(text: String) = text.isNotBlank() && text.length <= max && text.none { it.isISOControl() && !(multiline && it == '\n') }
    val ru = optString("ru").takeIf(::usable) ?: return null
    return FaqText(ru, optString("en").takeIf(::usable))
}

fun interface FaqSource {
    suspend fun fetch(): List<FaqItem>?
}

class HttpFaqSource(private val sharedOrigin: String, private val io: CoroutineDispatcher = Dispatchers.IO) : FaqSource {
    override suspend fun fetch(): List<FaqItem>? = withContext(io) {
        try {
            val connection = URL("$sharedOrigin/v1/faq/keep").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) null else parseFaq(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
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

/** What the FAQ screen has to show. */
sealed interface FaqState {
    /** Nothing cached yet, and the backend is being asked. */
    data object Loading : FaqState

    /** Nothing cached, and the backend could not be read. */
    data object Unavailable : FaqState

    /** The questions as last published; empty when the operator switched every one off. */
    data class Ready(val items: List<FaqItem>) : FaqState
}

/**
 * The FAQ screen's questions: only what the backend published, kept on disk so a cold start offline shows it too. The
 * app ships no questions of its own. [refresh] asks at most once per [minIntervalMillis] once something is cached —
 * with nothing cached every call asks, so «Try again» works — and a failure keeps what is there.
 */
class FaqRepository(
    private val source: FaqSource,
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = 10 * 60 * 1000L,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var lastAskedAt: Long? = null
    private val mutableState = MutableStateFlow<FaqState>(FaqState.Loading)

    val state: StateFlow<FaqState> = mutableState.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        if (!loaded) {
            loaded = true
            withContext(io) { runCatching { if (file.exists()) parseFaq(file.readText()) else null }.getOrNull() }
                ?.let { mutableState.value = FaqState.Ready(it) }
        }
        val cached = mutableState.value as? FaqState.Ready
        val now = nowMillis()
        lastAskedAt?.let { if (cached != null && now - it < minIntervalMillis) return@withLock }
        lastAskedAt = now
        if (cached == null) mutableState.value = FaqState.Loading
        val fetched = source.fetch()
        if (fetched == null) {
            if (cached == null) mutableState.value = FaqState.Unavailable
            return@withLock
        }
        if (fetched == cached?.items) return@withLock
        mutableState.value = FaqState.Ready(fetched)
        withContext(io) {
            runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(encodeFaq(fetched))
                if (!temporary.renameTo(file)) {
                    file.writeText(encodeFaq(fetched))
                    temporary.delete()
                }
            }
        }
    }
}
