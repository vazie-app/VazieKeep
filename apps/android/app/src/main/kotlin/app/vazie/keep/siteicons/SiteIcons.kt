package app.vazie.keep.siteicons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import app.vazie.keep.core.siteIconLinks
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The icon of a website, as a small PNG. The request goes straight to the site: no service in between
// learns which sites a vault holds.
interface SiteIcons {
    suspend fun fetch(host: String): ByteArray?
}

fun interface SiteHttp {
    // The body of an https page, or null. A longer body is cut at `limit` when `cut`, refused otherwise.
    fun get(url: String, limit: Int, cut: Boolean): ByteArray?
}

class HttpSiteIcons(
    private val http: SiteHttp = UrlConnectionSiteHttp(),
    private val links: (host: String, html: String) -> List<String> = ::siteIconLinks,
    private val toPng: (ByteArray) -> ByteArray? = ::iconPng,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SiteIcons {
    override suspend fun fetch(host: String): ByteArray? = withContext(io) {
        siteCandidates(host).firstNotNullOfOrNull(::fromSite)
    }

    private fun fromSite(host: String): ByteArray? {
        val page = http.get("https://$host/", PAGE_LIMIT, cut = true)?.let { String(it, Charsets.ISO_8859_1) }.orEmpty()
        return links(host, page).firstNotNullOfOrNull { link -> http.get(link, IMAGE_LIMIT, cut = false)?.let(toPng) }
    }

    private companion object {
        const val PAGE_LIMIT = 512 * 1024
        const val IMAGE_LIMIT = 1024 * 1024
    }
}

// Some sites answer only as "www."; and "accounts.example.com" has no icon of its own more often than "example.com" does.
fun siteCandidates(host: String): List<String> {
    val labels = host.split('.')
    return if (labels.size > 2) listOf(host, labels.drop(1).joinToString(".")) else listOf(host, "www.$host")
}

class UrlConnectionSiteHttp : SiteHttp {
    override fun get(url: String, limit: Int, cut: Boolean): ByteArray? {
        var current = url
        repeat(MAX_REDIRECTS) {
            if (!current.startsWith("https://")) return null
            val connection = try {
                URL(current).openConnection() as HttpURLConnection
            } catch (_: IOException) {
                return null
            }
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "text/html,image/*;q=0.9,*/*;q=0.5")
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return read(connection, limit, cut)
                    in 300..399 -> current = URL(URL(current), connection.getHeaderField("Location") ?: return null).toString()
                    else -> return null
                }
            } catch (_: IOException) {
                return null
            } catch (_: RuntimeException) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun read(connection: HttpURLConnection, limit: Int, cut: Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        connection.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > limit) {
                    if (!cut) return null
                    out.write(buffer, 0, limit - out.size())
                    break
                }
                out.write(buffer, 0, count)
            }
        }
        return out.toByteArray()
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val MAX_REDIRECTS = 5

        // What any phone browser sends: the request says nothing about this app.
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}

// Whatever image the site serves, kept as a PNG no larger than a list row needs.
fun iconPng(bytes: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..MAX_SOURCE || bounds.outHeight !in 1..MAX_SOURCE) return null
    val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / (ICON_SIZE * 2)).coerceAtLeast(1) }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
    val longest = maxOf(decoded.width, decoded.height)
    val scaled = if (longest > ICON_SIZE) {
        decoded.scale((decoded.width * ICON_SIZE / longest).coerceAtLeast(1), (decoded.height * ICON_SIZE / longest).coerceAtLeast(1))
    } else {
        decoded
    }
    val out = ByteArrayOutputStream()
    return if (scaled.compress(Bitmap.CompressFormat.PNG, 100, out)) out.toByteArray() else null
}

private const val ICON_SIZE = 96
private const val MAX_SOURCE = 4096
