package app.vazie.keep.passkeys

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAuthnJsonTest {
    @Test
    fun aCreationRequestIsReadWithItsExclusionsAndAlgorithms() {
        val options = WebAuthnJson.creation(
            """{"rp":{"name":"Example"},"user":{"id":"AQID","displayName":"Dana"},"challenge":"abc",
               "pubKeyCredParams":[{"type":"public-key","alg":-257}],"excludeCredentials":[{"id":"Zm9v=="},{"type":"public-key"}]}""",
        )!!
        assertNull(options.rpId, "left out: the page's own host stands in")
        assertEquals("Example", options.rpName)
        assertEquals("Dana", options.userName, "the display name when there is no account name")
        assertContentEquals(byteArrayOf(1, 2, 3), options.userHandle)
        assertEquals(listOf("Zm9v"), options.excluded)
        assertFalse(options.acceptsEs256)
        assertTrue(WebAuthnJson.creation("""{"rp":{"id":"a.example"},"user":{"id":"AQ","name":"n"},"challenge":"c"}""")!!.acceptsEs256, "no list means any")
    }

    @Test
    fun whatIsNotARequestIsRefused() {
        assertNull(WebAuthnJson.creation("not json"))
        assertNull(WebAuthnJson.creation("""{"rp":{"id":"a.example"},"user":{"id":"","name":"n"},"challenge":"c"}"""))
        assertNull(WebAuthnJson.creation("""{"rp":{"id":"a.example"},"user":{"id":"AQ","name":"n"},"challenge":""}"""))
        assertNull(WebAuthnJson.creation("""{"rp":{"id":"a.example"},"user":{"id":"!!!","name":"n"},"challenge":"c"}"""))
        assertNull(WebAuthnJson.assertion("""{"rpId":"a.example"}"""))
        val any = WebAuthnJson.assertion("""{"challenge":"c"}""")!!
        assertNull(any.rpId)
        assertTrue(any.allowed.isEmpty())
    }

    @Test
    fun clientDataNamesTheAppAndKeepsSlashesAsTheyAre() {
        val json = String(WebAuthnJson.clientData(WebAuthnJson.TYPE_GET, "abc", "android:apk-key-hash:x/y", "com.example"))
        assertTrue("\\/" !in json)
        val parsed = JSONObject(json)
        assertEquals("webauthn.get", parsed.getString("type"))
        assertEquals("android:apk-key-hash:x/y", parsed.getString("origin"))
        assertEquals("com.example", parsed.getString("androidPackageName"))
        assertFalse(parsed.getBoolean("crossOrigin"))
    }

    @Test
    fun siteStatementsNameTheAppByPackageAndCertificate() {
        val statements = """[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"web","site":"https://a.example"}},
            {"relation":["delegate_permission/common.get_login_creds"],"target":{"namespace":"android_app","package_name":"com.example","sha256_cert_fingerprints":["AB:CD"]}}]"""
        assertTrue(AssetLinks.allows(statements, "com.example", "abcd"))
        assertFalse(AssetLinks.allows(statements, "com.example", "abce"))
        assertFalse(AssetLinks.allows(statements, "com.other", "abcd"))
        assertFalse(AssetLinks.allows("""[{"relation":["something/else"],"target":{"namespace":"android_app","package_name":"com.example","sha256_cert_fingerprints":["AB:CD"]}}]""", "com.example", "abcd"))
        assertFalse(AssetLinks.allows("<html>", "com.example", "abcd"))
    }
}
