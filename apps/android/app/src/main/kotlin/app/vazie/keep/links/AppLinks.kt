package app.vazie.keep.links

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
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

enum class LinkSection { AUTHOR, PROJECT, SECURITY, HELP, SUPPORT, FEEDBACK }

data class LocalizedText(val ru: String, val en: String) {
    fun pick(russian: Boolean): String = if (russian) ru else en
}

data class AppLink(
    val key: String,
    val section: LinkSection,
    val url: String,
    val title: LocalizedText,
    val subtitle: LocalizedText? = null,
    val action: LocalizedText? = null,
)

fun List<AppLink>.inSection(section: LinkSection): List<AppLink> = filter { it.section == section }

/** The answer of `GET /v1/apps/keep/links`. A link the app cannot show is dropped on its own; `null` when none is left. */
fun parseLinks(text: String): List<AppLink>? = try {
    val links = JSONObject(text).optJSONArray("links") ?: JSONArray()
    (0 until links.length()).mapNotNull { index -> links.optJSONObject(index)?.toLink() }.takeIf { it.isNotEmpty() }
} catch (_: JSONException) {
    null
}

fun encodeLinks(links: List<AppLink>): String = JSONObject().put(
    "links",
    JSONArray(
        links.map { link ->
            JSONObject()
                .put("key", link.key)
                .put("section", link.section.name)
                .put("url", link.url)
                .put("title", link.title.toJson())
                .apply {
                    link.subtitle?.let { put("subtitle", it.toJson()) }
                    link.action?.let { put("action", it.toJson()) }
                }
        },
    ),
).toString()

private fun LocalizedText.toJson() = JSONObject().put("ru", ru).put("en", en)

private fun JSONObject.toLink(): AppLink? {
    val section = LinkSection.entries.firstOrNull { it.name == optString("section") } ?: return null
    val url = optString("url").takeIf(::isOpenable) ?: return null
    return AppLink(
        key = optString("key").takeIf { it.isNotBlank() } ?: return null,
        section = section,
        url = url,
        title = optJSONObject("title")?.toText() ?: return null,
        subtitle = optJSONObject("subtitle")?.toText(),
        action = optJSONObject("action")?.toText(),
    )
}

private fun JSONObject.toText(): LocalizedText? {
    val ru = optString("ru").takeIf(::isText) ?: return null
    val en = optString("en").takeIf(::isText) ?: return null
    return LocalizedText(ru, en)
}

private fun isText(text: String) = text.isNotBlank() && text.length <= 400 && text.none { it.isISOControl() }

/** Only a web page over `https` with a host, or a `mailto:` address: nothing else is ever opened. */
fun isOpenable(url: String): Boolean {
    if (url.length > 512 || url.any { it.isWhitespace() || it.isISOControl() }) return false
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return when (uri.scheme?.lowercase()) {
        "https" -> !uri.host.isNullOrBlank()
        "mailto" -> uri.schemeSpecificPart.orEmpty().let { "@" in it && !it.startsWith("@") }
        else -> false
    }
}

fun interface LinksSource {
    suspend fun fetch(): List<AppLink>?
}

class HttpLinksSource(private val sharedOrigin: String, private val io: CoroutineDispatcher = Dispatchers.IO) : LinksSource {
    override suspend fun fetch(): List<AppLink>? = withContext(io) {
        try {
            val connection = URL("$sharedOrigin/v1/apps/keep/links").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode != 200) null else parseLinks(connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
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

/**
 * The About screen's links: what the app shipped with, then the last list the backend published, kept on disk so a
 * cold start offline shows it too. [refresh] asks at most once per [minIntervalMillis]; a failure keeps what is there.
 */
class AppLinksRepository(
    private val source: LinksSource,
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val minIntervalMillis: Long = 10 * 60 * 1000L,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var loaded = false
    private var lastAskedAt: Long? = null
    private val state = MutableStateFlow(ShippedLinks)

    val links: StateFlow<List<AppLink>> = state.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        if (!loaded) {
            loaded = true
            withContext(io) { runCatching { if (file.exists()) parseLinks(file.readText()) else null }.getOrNull() }?.let { state.value = it }
        }
        val now = nowMillis()
        lastAskedAt?.let { if (now - it < minIntervalMillis) return@withLock }
        lastAskedAt = now
        val fetched = source.fetch() ?: return@withLock
        if (fetched == state.value) return@withLock
        state.value = fetched
        withContext(io) {
            runCatching {
                file.parentFile?.mkdirs()
                val temporary = File(file.parentFile, file.name + ".tmp")
                temporary.writeText(encodeLinks(fetched))
                if (!temporary.renameTo(file)) {
                    file.writeText(encodeLinks(fetched))
                    temporary.delete()
                }
            }
        }
    }
}

val ShippedLinks: List<AppLink> = listOf(
    AppLink(
        "developer", LinkSection.AUTHOR, "https://t.me/vazovsky17",
        LocalizedText("Vazovsky в Telegram", "Vazovsky on Telegram"),
        LocalizedText("@vazovsky17 — лично автору", "@vazovsky17 — to the author personally"),
    ),
    AppLink(
        "folio", LinkSection.AUTHOR, "https://folio.link/vazovsky",
        LocalizedText("Folio", "Folio"),
        LocalizedText("folio.link/vazovsky — другие проекты", "folio.link/vazovsky — other projects"),
    ),
    AppLink(
        "channel", LinkSection.PROJECT, "https://t.me/vazieapp",
        LocalizedText("Vazie в Telegram", "Vazie on Telegram"),
        LocalizedText("@vazieapp — новости, релизы и вопросы о приложении", "@vazieapp — news, releases and questions about the app"),
    ),
    AppLink(
        "support_email", LinkSection.PROJECT, "mailto:support@vazie.app",
        LocalizedText("Почта поддержки", "Support email"),
        LocalizedText("support@vazie.app — Keep+, оплата, возвраты", "support@vazie.app — Keep+, payments, refunds"),
    ),
    AppLink(
        "source", LinkSection.SECURITY, "https://github.com/vazie-app/VazieKeep",
        LocalizedText("Исходный код", "Source code"),
        LocalizedText("github.com/vazie-app/VazieKeep", "github.com/vazie-app/VazieKeep"),
    ),
    AppLink(
        "behance", LinkSection.PROJECT, "https://www.behance.net/gallery/256724355/Vazie-Keep-Local-First-Password-Manager",
        LocalizedText("Поддержать проект на Behance", "Support the project on Behance"),
        LocalizedText("behance.net — кейс Vazie Keep", "behance.net — the Vazie Keep case"),
    ),
    AppLink(
        // Vazie Feedback on the site, the app and the topic chosen; nothing from the vault or the device is in the address.
        "bug_report", LinkSection.FEEDBACK, "https://vazie.app/contacts?app=keep&category=bug_report#feedback",
        LocalizedText("Сообщить об ошибке или оставить отзыв", "Report a bug or leave feedback"),
        LocalizedText("Форма на vazie.app для ошибок, предложений и отзывов. Без аккаунта.", "A form on vazie.app for bug reports, suggestions and feedback. No account needed."),
    ),
)
