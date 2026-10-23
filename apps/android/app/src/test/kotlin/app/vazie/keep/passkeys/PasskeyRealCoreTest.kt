package app.vazie.keep.passkeys

import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.data.FileVaultCopyStore
import app.vazie.keep.data.JournaledOverwriteWriter
import app.vazie.keep.data.OpenOutcome
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReference
import app.vazie.keep.testing.FileBackedAccess
import app.vazie.keep.testing.FileDocumentPort
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The real Rust core and the real writer on a real file: a passkey is made for a site, written into the
// vault, and after the file is opened again it signs a challenge that the site's copy of the public key verifies.
class PasskeyRealCoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val vectors = File(System.getProperty("keep.testVectors"))
    private val password = "correct horse battery staple"
    private val cert = ByteArray(32) { it.toByte() }
    private val certHex = cert.joinToString("") { "%02x".format(it) }
    private val statements = """[{"relation":["delegate_permission/common.get_login_creds"],"target":{"namespace":"android_app","package_name":"com.example.shop","sha256_cert_fingerprints":["${certHex.chunked(2).joinToString(":").uppercase()}"]}}]"""
    private val gate = PasskeyGate(RustKeepCore) { rpId -> statements.takeIf { rpId == "shop.example.com" } }
    private val indexed = mutableListOf<PasskeyIndex>()

    private class Setup(val reference: VaultReference, val vault: File, val opener: VaultOpener, val operations: PasskeyOperations)

    private fun setup(): Setup {
        val vault = folder.newFile("vault.kdbx").also { vectors.resolve("editable/rich-aes-argon2d-gzip.kdbx").copyTo(it, overwrite = true) }
        val reference = VaultReference(vault.toURI().toString().replace("file:/", "file:///"), "vault.kdbx", 0)
        val copies = FileVaultCopyStore(folder.root.resolve("Vault Copies"))
        val files = FileBackedAccess()
        val operations = PasskeyOperations(
            files,
            JournaledOverwriteWriter(FileDocumentPort(), copies),
            copies,
            afterSave = { indexed += PasskeyIndex.of(it.info.fingerprint, it.session.passkeys(null)) },
            background = Dispatchers.Unconfined,
        )
        return Setup(reference, vault, VaultOpener(RustKeepCore, files, copies), operations)
    }

    private suspend fun open(setup: Setup): OpenedVault =
        (setup.opener.open(setup.reference, password.toByteArray(), null, true, false, null) as OpenOutcome.Opened).vault

    private fun creationJson(exclude: String? = null) = """{"rp":{"id":"shop.example.com","name":"Example Shop"},"user":{"id":"dXNlci00Mg","name":"dana","displayName":"Dana"},
        "challenge":"Y2hhbGxlbmdlLTE","pubKeyCredParams":[{"type":"public-key","alg":-257},{"type":"public-key","alg":-7}]
        ${exclude?.let { ""","excludeCredentials":[{"type":"public-key","id":"$it"}]""" }.orEmpty()}}"""

    private fun verify(publicKey: ByteArray, signed: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey)))
            update(signed)
            verify(signature)
        }

    @Test
    fun anAppMakesAPasskeyAndSignsInWithItAfterTheFileIsReopened() = runBlocking {
        val s = setup()
        val app = PasskeyCaller.App("com.example.shop", cert)
        val create = PasskeyRequest.Create(WebAuthnJson.creation(creationJson())!!, app, null)
        val allowed = assertIs<PasskeyGate.Decision.Allowed>(gate.check(create))
        assertEquals("shop.example.com", allowed.rpId)
        val clientData = JSONObject(String(allowed.clientData.json))
        assertEquals("webauthn.create", clientData.getString("type"))
        assertEquals("Y2hhbGxlbmdlLTE", clientData.getString("challenge"))
        assertEquals(app.origin, clientData.getString("origin"))
        assertTrue(app.origin.startsWith("android:apk-key-hash:"))

        val first = open(s)
        val made = try {
            assertIs<PasskeyOutcome.Done>(s.operations.create(first, s.reference, allowed.rpId, create.options, allowed.clientData))
        } finally {
            first.session.close()
        }
        val registration = JSONObject(made.json)
        val response = registration.getJSONObject("response")
        val credentialId = registration.getString("id")
        assertEquals("public-key", registration.getString("type"))
        assertEquals(-7, response.getInt("publicKeyAlgorithm"))
        assertTrue(WebAuthnJson.decode(response.getString("attestationObject"))!!.isNotEmpty())
        val publicKey = WebAuthnJson.decode(response.getString("publicKey"))!!
        val passkey = indexed.single().passkeys.single()
        assertEquals(listOf("shop.example.com", "dana", credentialId, "Example Shop"), listOf(passkey.rpId, passkey.userName, passkey.credentialId, passkey.title))

        val get = PasskeyRequest.Get(WebAuthnJson.assertion("""{"rpId":"shop.example.com","challenge":"bG9naW4","allowCredentials":[{"type":"public-key","id":"$credentialId"}]}""")!!, passkey.entryId, app, null)
        val signing = assertIs<PasskeyGate.Decision.Allowed>(gate.check(get))
        val second = open(s)
        val signed = try {
            assertIs<PasskeyOutcome.Done>(s.operations.sign(second, passkey.entryId, signing.rpId, get.options, signing.clientData))
        } finally {
            second.session.close()
        }
        val assertion = JSONObject(signed.json).getJSONObject("response")
        assertEquals(credentialId, JSONObject(signed.json).getString("id"))
        assertEquals("dXNlci00Mg", assertion.getString("userHandle"))
        val authenticatorData = WebAuthnJson.decode(assertion.getString("authenticatorData"))!!
        val sentClientData = WebAuthnJson.decode(assertion.getString("clientDataJSON"))!!
        assertEquals("webauthn.get", JSONObject(String(sentClientData)).getString("type"))
        val message = authenticatorData + MessageDigest.getInstance("SHA-256").digest(sentClientData)
        assertTrue(verify(publicKey, message, WebAuthnJson.decode(assertion.getString("signature"))!!), "the site's public key verifies the sign-in")
        Unit
    }

    @Test
    fun aBrowserSignsWithThePagesOwnClientDataAndOnlyForThatSite() = runBlocking {
        val s = setup()
        val hash = MessageDigest.getInstance("SHA-256").digest("the page's client data".toByteArray())
        val browser = PasskeyCaller.Browser("com.android.chrome", "https://www.shop.example.com")
        val create = PasskeyRequest.Create(WebAuthnJson.creation(creationJson())!!, browser, hash)
        val allowed = assertIs<PasskeyGate.Decision.Allowed>(gate.check(create))
        val first = open(s)
        val made = try {
            assertIs<PasskeyOutcome.Done>(s.operations.create(first, s.reference, allowed.rpId, create.options, allowed.clientData))
        } finally {
            first.session.close()
        }
        val publicKey = WebAuthnJson.decode(JSONObject(made.json).getJSONObject("response").getString("publicKey"))!!
        val credentialId = JSONObject(made.json).getString("id")
        val entryId = indexed.single().passkeys.single().entryId

        val again = open(s)
        try {
            val repeat = PasskeyRequest.Create(WebAuthnJson.creation(creationJson(exclude = credentialId))!!, browser, hash)
            assertEquals(PasskeyOutcome.Failed(PasskeyProblem.AlreadyExists), s.operations.create(again, s.reference, allowed.rpId, repeat.options, allowed.clientData))

            val get = PasskeyRequest.Get(WebAuthnJson.assertion("""{"rpId":"shop.example.com","challenge":"bG9naW4"}""")!!, entryId, browser, hash)
            val signing = assertIs<PasskeyGate.Decision.Allowed>(gate.check(get))
            val signed = assertIs<PasskeyOutcome.Done>(s.operations.sign(again, entryId, signing.rpId, get.options, signing.clientData))
            val assertion = JSONObject(signed.json).getJSONObject("response")
            val message = WebAuthnJson.decode(assertion.getString("authenticatorData"))!! + hash
            assertTrue(verify(publicKey, message, WebAuthnJson.decode(assertion.getString("signature"))!!))

            val other = PasskeyRequest.Get(WebAuthnJson.assertion("""{"rpId":"shop.example.com","challenge":"bG9naW4","allowCredentials":[{"id":"c29tZXRoaW5nLWVsc2U"}]}""")!!, entryId, browser, hash)
            assertEquals(PasskeyOutcome.Failed(PasskeyProblem.NoPasskey), s.operations.sign(again, entryId, "shop.example.com", other.options, signing.clientData))
            assertEquals(PasskeyOutcome.Failed(PasskeyProblem.NoPasskey), s.operations.sign(again, entryId, "example.com", get.options, signing.clientData))
        } finally {
            again.session.close()
        }
        Unit
    }

    @Test
    fun aCallerWithNoRightToTheSiteIsRefusedBeforeAnythingIsUnlocked() = runBlocking {
        val options = WebAuthnJson.creation(creationJson())!!
        fun refused(caller: PasskeyCaller) = runBlocking { gate.check(PasskeyRequest.Create(options, caller, ByteArray(32))) }
        assertEquals(PasskeyGate.Decision.Refused(PasskeyProblem.SiteMismatch), refused(PasskeyCaller.Browser("com.android.chrome", "https://evil.example.org")))
        assertEquals(PasskeyGate.Decision.Refused(PasskeyProblem.SiteMismatch), refused(PasskeyCaller.Browser("com.android.chrome", "https://example.com")), "a parent domain is not the site")
        assertEquals(PasskeyGate.Decision.Refused(PasskeyProblem.CallerNotVerified), refused(PasskeyCaller.Browser("com.android.chrome", "http://shop.example.com")))
        assertEquals(PasskeyGate.Decision.Refused(PasskeyProblem.SiteMismatch), refused(PasskeyCaller.App("com.example.other", cert)), "another package")
        assertEquals(PasskeyGate.Decision.Refused(PasskeyProblem.SiteMismatch), refused(PasskeyCaller.App("com.example.shop", ByteArray(32) { 9 })), "another signer")
        val elsewhere = WebAuthnJson.creation(creationJson().replace("shop.example.com", "unreachable.example"))!!
        assertEquals(PasskeyGate.Decision.Refused(PasskeyProblem.CouldNotCheckSite), gate.check(PasskeyRequest.Create(elsewhere, PasskeyCaller.App("com.example.shop", cert), null)))
        assertIs<PasskeyGate.Decision.Allowed>(refused(PasskeyCaller.Browser("org.mozilla.firefox", "https://shop.example.com")))
        Unit
    }
}
