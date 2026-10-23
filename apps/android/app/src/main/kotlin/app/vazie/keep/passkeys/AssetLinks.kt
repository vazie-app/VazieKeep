package app.vazie.keep.passkeys

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException

// A site says which Android apps may use its passkeys in `/.well-known/assetlinks.json` (Digital Asset Links).
// Before an app signs in with a site's passkey, the site is asked. This is the one request the passkey flow
// makes, and it goes to the site the person is signing in to.
fun interface AssetLinkSource {
    suspend fun statements(rpId: String): String?
}

object AssetLinks {
    private val RELATIONS = setOf("delegate_permission/common.get_login_creds", "delegate_permission/common.handle_all_urls")

    // `certSha256` is the app's signing certificate digest in hex, any case, with or without colons.
    fun allows(statements: String, packageName: String, certSha256: String): Boolean = try {
        val wanted = certSha256.normalized()
        val list = JSONArray(statements)
        (0 until list.length()).any { index ->
            val statement = list.optJSONObject(index) ?: return@any false
            val relations = statement.optJSONArray("relation") ?: return@any false
            val target = statement.optJSONObject("target") ?: return@any false
            val prints = target.optJSONArray("sha256_cert_fingerprints") ?: return@any false
            (0 until relations.length()).any { relations.optString(it) in RELATIONS } &&
                target.optString("namespace") == "android_app" &&
                target.optString("package_name") == packageName &&
                (0 until prints.length()).any { prints.optString(it).normalized() == wanted }
        }
    } catch (_: JSONException) {
        false
    }

    private fun String.normalized() = replace(":", "").lowercase()
}

class HttpAssetLinks(private val io: CoroutineDispatcher = Dispatchers.IO) : AssetLinkSource {
    override suspend fun statements(rpId: String): String? = withContext(io) {
        if (!rpId.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return@withContext null
        val connection = try {
            URL("https://$rpId/.well-known/assetlinks.json").openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return@withContext null
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // The statement must come from the site itself: a redirect elsewhere proves nothing.
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            connection.inputStream.use { input ->
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > LIMIT) return@withContext null
                    out.write(buffer, 0, count)
                }
            }
            out.toString(Charsets.UTF_8.name())
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val LIMIT = 512 * 1024
    }
}
