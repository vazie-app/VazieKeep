package app.vazie.keep.siteicons

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HttpSiteIconsTest {
    private val asked = mutableListOf<String>()
    private val pages = mutableMapOf<String, ByteArray>()
    private val http = SiteHttp { url, _, _ ->
        asked += url
        pages[url]
    }

    private fun icons() = HttpSiteIcons(
        http = http,
        links = { host, html -> listOfNotNull("https://$host/touch.png".takeIf { "touch" in html }, "https://$host/favicon.ico") },
        toPng = { bytes -> bytes.takeIf { it.firstOrNull() == 7.toByte() } },
        io = Dispatchers.Unconfined,
    )

    @Test
    fun theIconThePageNamesComesFirst() = runTest {
        pages["https://example.com/"] = "<link rel=icon href=touch.png>".toByteArray()
        pages["https://example.com/touch.png"] = byteArrayOf(7, 1)
        pages["https://example.com/favicon.ico"] = byteArrayOf(7, 2)
        assertContentEquals(byteArrayOf(7, 1), icons().fetch("example.com"))
        assertEquals(listOf("https://example.com/", "https://example.com/touch.png"), asked)
    }

    @Test
    fun whatIsNotAnImageIsPassedOver() = runTest {
        pages["https://example.com/"] = "touch".toByteArray()
        pages["https://example.com/touch.png"] = "<html>".toByteArray()
        pages["https://example.com/favicon.ico"] = byteArrayOf(7, 2)
        assertContentEquals(byteArrayOf(7, 2), icons().fetch("example.com"))
    }

    @Test
    fun aSubdomainWithoutAnIconTakesTheOneOfItsSite() = runTest {
        pages["https://example.com/favicon.ico"] = byteArrayOf(7, 3)
        assertContentEquals(byteArrayOf(7, 3), icons().fetch("accounts.example.com"))
        assertEquals("https://accounts.example.com/", asked.first())
    }

    @Test
    fun aSiteThatDoesNotAnswerGivesNothing() = runTest {
        assertNull(icons().fetch("example.com"))
        assertEquals(listOf("example.com", "www.example.com"), siteCandidates("example.com"))
        assertEquals(listOf("a.b.example.com", "b.example.com"), siteCandidates("a.b.example.com"))
    }
}
