package app.vazie.keep.links

import java.nio.file.Files
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLinksTest {
    private val body = """
        {"links":[
          {"key":"developer","section":"AUTHOR","url":"https://t.me/vazovsky17","title":{"ru":"Автор","en":"Author"}},
          {"key":"mail","section":"PROJECT","url":"mailto:support@vazie.app","title":{"ru":"Почта","en":"Email"},"subtitle":{"ru":"Пишите","en":"Write"}},
          {"key":"script","section":"PROJECT","url":"javascript:alert(1)","title":{"ru":"X","en":"X"}},
          {"key":"plain","section":"PROJECT","url":"http://vazie.app","title":{"ru":"X","en":"X"}},
          {"key":"half","section":"PROJECT","url":"https://vazie.app","title":{"ru":"Только","en":""}},
          {"key":"future","section":"BANNER","url":"https://vazie.app","title":{"ru":"X","en":"X"}},
          {"key":"form","section":"FEEDBACK","url":"https://forms.gle/x","title":{"ru":"Отзыв","en":"Feedback"}}
        ]}
    """.trimIndent()

    @Test
    fun `the security and help cards are read, and the shipped source link sits in security`() {
        val links = parseLinks("""{"links":[
            {"key":"privacy","section":"SECURITY","url":"https://vazie.app/legal/privacy","title":{"ru":"П","en":"P"}},
            {"key":"faq","section":"HELP","url":"https://vazie.app/keep","title":{"ru":"В","en":"F"}}
        ]}""")!!
        assertEquals(listOf(LinkSection.SECURITY, LinkSection.HELP), links.map { it.section })
        assertEquals(listOf("source"), ShippedLinks.inSection(LinkSection.SECURITY).map { it.key })
        // The source lives in vazie-app; the earlier vazovsky17 repository is gone.
        assertEquals("https://github.com/vazie-app/VazieKeep", ShippedLinks.inSection(LinkSection.SECURITY).single().url)
        assertTrue(ShippedLinks.none { "github.com/vazovsky17" in it.url })
    }

    @Test
    fun `only links the app can show are kept, in the backend's order`() {
        val links = parseLinks(body)!!
        assertEquals(listOf("developer", "mail", "form"), links.map { it.key })
        assertEquals("Write", links[1].subtitle?.pick(russian = false))
        assertEquals("Почта", links[1].title.pick(russian = true))
    }

    @Test
    fun `an answer with nothing to show, or not the contract, is no answer`() {
        assertNull(parseLinks("""{"links":[]}"""))
        assertNull(parseLinks("""{"links":[{"key":"x","section":"AUTHOR","url":"ftp://x","title":{"ru":"X","en":"X"}}]}"""))
        assertNull(parseLinks("<html>"))
    }

    @Test
    fun `only https pages with a host and mailto addresses open`() {
        assertTrue(isOpenable("https://github.com/vazie-app/VazieKeep"))
        assertTrue(isOpenable("mailto:support@vazie.app"))
        assertFalse(isOpenable("https:///nohost"))
        assertFalse(isOpenable("intent://x#Intent;end"))
        assertFalse(isOpenable("mailto:@x"))
        assertFalse(isOpenable("https://vazie.app/a b"))
    }

    @Test
    fun `the shipped links survive their own round trip`() {
        assertEquals(ShippedLinks, parseLinks(encodeLinks(ShippedLinks)))
        assertEquals("https://vazie.app/contacts?app=keep&category=bug_report#feedback", ShippedLinks.inSection(LinkSection.FEEDBACK).single().url)
        assertTrue(isOpenable(ShippedLinks.inSection(LinkSection.FEEDBACK).single().url))
    }

    @Test
    fun `the backend's list replaces the shipped one, is kept on disk, and a failure keeps it`() = runTest {
        val file = Files.createTempDirectory("links").resolve("app_links.json").toFile()
        val fetched = parseLinks(body)!!
        var answer: List<AppLink>? = fetched
        var asked = 0
        var now = 0L
        val repository = AppLinksRepository({ asked++; answer }, file, { now }, io = UnconfinedTestDispatcher(testScheduler))

        assertEquals(ShippedLinks, repository.links.value)
        repository.refresh()
        assertEquals(fetched, repository.links.value)
        assertTrue(file.exists())

        repository.refresh()
        assertEquals(1, asked)

        now += 11 * 60 * 1000L
        answer = null
        repository.refresh()
        assertEquals(2, asked)
        assertEquals(fetched, repository.links.value)

        val offline = AppLinksRepository({ null }, file, { 0L }, io = UnconfinedTestDispatcher(testScheduler))
        offline.refresh()
        assertEquals(fetched, offline.links.value)
    }
}
