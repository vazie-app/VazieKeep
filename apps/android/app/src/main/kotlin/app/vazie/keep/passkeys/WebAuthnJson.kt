package app.vazie.keep.passkeys

import app.vazie.keep.core.CreatedPasskey
import app.vazie.keep.core.PasskeyAssertion
import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// What a site asks for when it wants a new passkey. `rpId` is null when the site left it out: it is then the
// host of the page that asked.
class CreationOptions(
    val rpId: String?,
    val rpName: String,
    val userName: String,
    val userHandle: ByteArray,
    val challenge: String,
    // Credential ids (base64url) the site already has for this account: a second passkey must not be made.
    val excluded: List<String>,
    val acceptsEs256: Boolean,
)

class AssertionOptions(
    val rpId: String?,
    val challenge: String,
    // Empty means any passkey of the site.
    val allowed: List<String>,
)

// The JSON of the Web Authentication API, as Android's Credential Manager hands it over and takes it back.
object WebAuthnJson {
    private const val ES256 = -7
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    fun decode(text: String): ByteArray? = try {
        decoder.decode(text.trim().trimEnd('='))
    } catch (_: IllegalArgumentException) {
        null
    }

    fun creation(json: String): CreationOptions? = try {
        val root = JSONObject(json)
        val rp = root.getJSONObject("rp")
        val user = root.getJSONObject("user")
        val handle = decode(user.getString("id"))
        val challenge = root.getString("challenge")
        val algorithms = root.optJSONArray("pubKeyCredParams")
        if (handle == null || handle.isEmpty() || handle.size > 64 || challenge.isEmpty()) {
            null
        } else {
            CreationOptions(
                rpId = rp.optString("id").ifEmpty { null },
                rpName = rp.optString("name"),
                userName = user.optString("name").ifEmpty { user.optString("displayName") },
                userHandle = handle,
                challenge = challenge,
                excluded = ids(root.optJSONArray("excludeCredentials")),
                acceptsEs256 = algorithms == null || algorithms.length() == 0 ||
                    (0 until algorithms.length()).any { algorithms.optJSONObject(it)?.optInt("alg") == ES256 },
            )
        }
    } catch (_: JSONException) {
        null
    }

    fun assertion(json: String): AssertionOptions? = try {
        val root = JSONObject(json)
        val challenge = root.getString("challenge")
        if (challenge.isEmpty()) null else AssertionOptions(root.optString("rpId").ifEmpty { null }, challenge, ids(root.optJSONArray("allowCredentials")))
    } catch (_: JSONException) {
        null
    }

    private fun ids(list: JSONArray?): List<String> =
        if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it)?.optString("id")?.trimEnd('=')?.ifEmpty { null } }

    // The client data an Android app's request is signed with. A browser makes its own and sends only the hash.
    fun clientData(type: String, challenge: String, origin: String, packageName: String): ByteArray =
        JSONObject()
            .put("type", type)
            .put("challenge", challenge)
            .put("origin", origin)
            .put("crossOrigin", false)
            .put("androidPackageName", packageName)
            .toString()
            .replace("\\/", "/")
            .toByteArray(Charsets.UTF_8)

    fun registrationResponse(made: CreatedPasskey, clientData: ByteArray): String {
        val id = encode(made.credentialId)
        val response = JSONObject()
            .put("clientDataJSON", encode(clientData))
            .put("attestationObject", encode(made.attestationObject))
            .put("authenticatorData", encode(made.authenticatorData))
            .put("publicKey", encode(made.publicKey))
            .put("publicKeyAlgorithm", ES256)
            .put("transports", JSONArray().put("internal").put("hybrid"))
        return JSONObject()
            .put("id", id)
            .put("rawId", id)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("response", response)
            .put("clientExtensionResults", JSONObject().put("credProps", JSONObject().put("rk", true)))
            .toString()
    }

    fun assertionResponse(signed: PasskeyAssertion, clientData: ByteArray): String {
        val id = encode(signed.credentialId)
        val response = JSONObject()
            .put("clientDataJSON", encode(clientData))
            .put("authenticatorData", encode(signed.authenticatorData))
            .put("signature", encode(signed.signature))
            .put("userHandle", encode(signed.userHandle))
        return JSONObject()
            .put("id", id)
            .put("rawId", id)
            .put("type", "public-key")
            .put("authenticatorAttachment", "platform")
            .put("response", response)
            .put("clientExtensionResults", JSONObject())
            .toString()
    }

    const val TYPE_CREATE = "webauthn.create"
    const val TYPE_GET = "webauthn.get"
}
