package app.vazie.keep.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebOriginTest {
    private fun parse(url: String) = WebOrigin.parseEntryUrl(url)

    @Test
    fun aBareHostIsHttps() {
        assertEquals(WebOrigin("https", "example.com", null), parse("example.com/login"))
        assertEquals(WebOrigin("https", "example.com", null), parse("  https://Example.COM:443/x?y#z "))
    }

    @Test
    fun onlyHttpAndHttpsHaveAnOrigin() {
        assertNull(parse("androidapp://com.example.app"))
        assertNull(parse("ssh://example.com"))
        assertNull(parse(""))
        assertNull(parse("   "))
        assertEquals("http", parse("http://example.com")?.scheme)
    }

    @Test
    fun portsOtherThanTheDefaultAreKept() {
        assertEquals(8443, parse("https://example.org:8443")?.port)
        assertEquals(null, parse("http://example.org:80")?.port)
        assertEquals(8000, parse("http://localhost:8000/login")?.port)
        assertNull(parse("https://example.org:99999"))
        assertNull(parse("https://example.org:abc"))
    }

    @Test
    fun userinfoAndPathsAreDropped() {
        assertEquals("example.com", parse("https://user:pw@example.com/path")?.host)
    }

    @Test
    fun unicodeHostsBecomePunycodeSoTheyCannotPoseAsAscii() {
        assertEquals("xn--bcher-kva.example", parse("https://bücher.example")?.host)
        assertEquals("xn--pypal-4ve.com", parse("https://pаypal.com")?.host)
    }

    @Test
    fun ipv6AndIpv4AreHosts() {
        assertEquals("::1", parse("http://[::1]:8080")?.host)
        assertEquals("192.168.1.1", parse("http://192.168.1.1/")?.host)
    }

    @Test
    fun invalidHostsAreRefused() {
        assertNull(parse("https:///path"))
        assertNull(parse("https://" + "a".repeat(300) + ".com"))
    }

    @Test
    fun requestOriginsAssumeHttpsUnlessToldOtherwise() {
        assertEquals(WebOrigin("https", "example.com", null), WebOrigin.fromRequest(null, "Example.com"))
        assertEquals(WebOrigin("http", "example.com", null), WebOrigin.fromRequest("http", "example.com"))
        assertEquals(WebOrigin("https", "example.com", null), WebOrigin.fromRequest("ftp", "example.com"))
        assertNull(WebOrigin.fromRequest("https", ""))
    }

    @Test
    fun originsNeverPrintTheirHost() {
        assertEquals("WebOrigin(<redacted>)", WebOrigin("https", "secret.example", null).toString())
    }
}
