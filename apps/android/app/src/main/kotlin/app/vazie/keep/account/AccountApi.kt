package app.vazie.keep.account

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

class ApiSession(val accountId: String, val email: String?, val token: String) {
    override fun toString() = "ApiSession(accountId=$accountId)"
}

class FetchedLease(val lease: String)

// Why a call to the Vazie backend did not work. None of these ever concerns a vault.
sealed class AccountApiException(message: String) : Exception(message) {
    // The device could not reach the backend, or it answered with something unusable.
    class Unreachable : AccountApiException("unreachable")

    // The session token is no longer accepted: it expired or was revoked.
    class SessionRejected : AccountApiException("session rejected")

    class InvalidCode : AccountApiException("invalid code")

    class InvalidEmail : AccountApiException("invalid email")

    class RateLimited : AccountApiException("rate limited")

    // The backend has this feature switched off (sign-in by email, or licensing).
    class NotEnabled : AccountApiException("not enabled")
}

// The two things Keep asks of the Vazie backend: sign in, and fetch a signed lease. No vault data,
// no device identifier and no vault count is ever sent.
interface VazieAccountApi {
    suspend fun startEmailSignIn(email: String)

    suspend fun verifyEmailCode(email: String, code: String): ApiSession

    // Only a development backend answers this; the production one has it switched off.
    suspend fun devSignIn(): ApiSession

    suspend fun fetchLease(token: String): FetchedLease

    suspend fun signOut(token: String)

    // Deletes the account for good: its address, sessions and Keep+. Every charge for it ends with it.
    suspend fun deleteAccount(token: String)
}

class HttpVazieAccountApi(
    // Origins without a path, e.g. https://api.example.com.
    private val accountOrigin: String,
    private val keepOrigin: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : VazieAccountApi {

    override suspend fun startEmailSignIn(email: String) {
        call("POST", "$accountOrigin/v1/auth/email/start", body = JSONObject().put("email", email))
    }

    override suspend fun verifyEmailCode(email: String, code: String): ApiSession {
        val body = JSONObject().put("email", email).put("code", code).put("platform", "ANDROID")
        return session(call("POST", "$accountOrigin/v1/auth/email/verify", body = body))
    }

    override suspend fun devSignIn(): ApiSession =
        session(call("POST", "$accountOrigin/api/v1/auth/dev", body = JSONObject().put("platform", "ANDROID")))

    override suspend fun fetchLease(token: String): FetchedLease {
        val json = call("GET", "$keepOrigin/v1/entitlement", bearer = token)
        return FetchedLease(json.optString("lease").takeIf { it.isNotEmpty() } ?: throw AccountApiException.Unreachable())
    }

    override suspend fun signOut(token: String) {
        call("DELETE", "$accountOrigin/v1/session", bearer = token)
    }

    override suspend fun deleteAccount(token: String) {
        call("DELETE", "$accountOrigin/v1/account", body = JSONObject().put("confirm", "DELETE"), bearer = token)
    }

    private fun session(json: JSONObject) = ApiSession(
        accountId = json.optString("accountId").takeIf { it.isNotEmpty() } ?: throw AccountApiException.Unreachable(),
        email = json.optString("email").takeIf { it.isNotEmpty() },
        token = json.optString("token").takeIf { it.isNotEmpty() } ?: throw AccountApiException.Unreachable(),
    )

    private suspend fun call(method: String, url: String, body: JSONObject? = null, bearer: String? = null): JSONObject =
        withContext(io) {
            val connection = try {
                URL(url).openConnection() as HttpURLConnection
            } catch (_: IOException) {
                throw AccountApiException.Unreachable()
            }
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/json")
                bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val status = connection.responseCode
                val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (status !in 200..299) throw failure(status, text)
                if (text.isBlank()) JSONObject() else JSONObject(text)
            } catch (error: AccountApiException) {
                throw error
            } catch (_: IOException) {
                throw AccountApiException.Unreachable()
            } catch (_: JSONException) {
                throw AccountApiException.Unreachable()
            } finally {
                connection.disconnect()
            }
        }

    private fun failure(status: Int, text: String): AccountApiException {
        val code = try {
            JSONObject(text).optJSONObject("error")?.optString("code")
        } catch (_: JSONException) {
            null
        }
        return when {
            code == "EMAIL_CODE_INVALID" -> AccountApiException.InvalidCode()
            code == "AUTH_INVALID" || code == "AUTH_REQUIRED" || status == 401 -> AccountApiException.SessionRejected()
            code == "NOT_ENABLED" -> AccountApiException.NotEnabled()
            code == "RATE_LIMITED" || status == 429 -> AccountApiException.RateLimited()
            code == "VALIDATION_FAILED" -> AccountApiException.InvalidEmail()
            else -> AccountApiException.Unreachable()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
