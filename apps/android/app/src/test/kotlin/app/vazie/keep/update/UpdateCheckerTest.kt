package app.vazie.keep.update

import app.vazie.keep.review.InstallStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {
    private val body = """{"latestVersionCode":3,"latestVersionName":"1.0.2","minimumSupportedVersionCode":2,"updateUrl":"https://vazie.app/keep","message":{"ru":"Вышла 1.0.2","en":"1.0.2 is out"}}"""

    private class MemoryStore : DismissedUpdateStore {
        var code: Int? = null
        override fun dismissed() = code
        override fun dismiss(versionCode: Int) { code = versionCode }
    }

    @Test
    fun `the answer is read, and anything but an https page to update from is no answer`() {
        val release = parseRelease(body)!!
        assertEquals(3, release.latestVersionCode)
        assertEquals("1.0.2 is out", release.message?.pick(russian = false))
        assertNull(parseRelease(body.replace("https://vazie.app/keep", "http://vazie.app/keep")))
        assertNull(parseRelease(body.replace("https://vazie.app/keep", "intent://x")))
        assertNull(parseRelease(body.replace("\"minimumSupportedVersionCode\":2", "\"minimumSupportedVersionCode\":4")))
        assertNull(parseRelease("<html>"))
    }

    @Test
    fun `compared by version code only`() {
        val release = parseRelease(body)!!
        assertEquals(UpdateState.Required(release), release.stateFor(1))
        assertEquals(UpdateState.Available(release), release.stateFor(2))
        assertEquals(UpdateState.Current, release.stateFor(3))
        assertEquals(UpdateState.Current, release.stateFor(4))
    }

    @Test
    fun `later hides that version only, a required update never hides, and the backend is asked rarely`() = runTest {
        var answer = parseRelease(body)
        var asked = 0
        var now = 0L
        val store = MemoryStore()
        val checker = UpdateChecker({ asked++; answer }, installedVersionCode = 2, dismissedStore = store, nowMillis = { now })

        checker.refresh()
        assertEquals(UpdateState.Available(answer!!), checker.update.value)
        checker.later()
        assertEquals(UpdateState.Current, checker.update.value)
        assertEquals(3, store.code)

        checker.refresh()
        assertEquals(1, asked)

        now += 7 * 60 * 60 * 1000L
        answer = parseRelease(body.replace("\"latestVersionCode\":3", "\"latestVersionCode\":4").replace("1.0.2", "1.0.3"))
        checker.refresh()
        assertEquals(UpdateState.Available(answer!!), checker.update.value)

        val old = UpdateChecker({ answer }, installedVersionCode = 1, dismissedStore = MemoryStore())
        old.refresh()
        old.later()
        assertEquals(UpdateState.Required(answer!!), old.update.value)
    }

    @Test
    fun `no answer keeps what is known`() = runTest {
        var answer = parseRelease(body)
        var now = 0L
        val checker = UpdateChecker({ answer }, installedVersionCode = 2, dismissedStore = MemoryStore(), nowMillis = { now })
        checker.refresh()
        now += 7 * 60 * 60 * 1000L
        answer = null
        checker.refresh()
        assertEquals(UpdateState.Available(parseRelease(body)!!), checker.update.value)
    }

    @Test
    fun `an app from a store is updated in that store, any other from the backend's page`() = runTest {
        val release = parseRelease(body)!!
        assertEquals(listOf("https://vazie.app/keep"), release.downloadLinks("app.vazie.keep"))

        val fromRuStore = InstalledReleaseSource({ release }) { InstallStore.RuStore }.fetch()!!
        assertEquals(
            listOf("rustore://apps.rustore.ru/app/app.vazie.keep", "https://apps.rustore.ru/app/app.vazie.keep"),
            fromRuStore.downloadLinks("app.vazie.keep"),
        )
        assertEquals("market://details?id=app.vazie.keep", release.copy(store = InstallStore.GooglePlay).downloadLinks("app.vazie.keep").first())

        assertEquals(listOf("https://vazie.app/keep"), InstalledReleaseSource({ release }) { null }.fetch()!!.downloadLinks("app.vazie.keep"))
        assertNull(InstalledReleaseSource({ null }) { InstallStore.RuStore }.fetch())
    }
}
