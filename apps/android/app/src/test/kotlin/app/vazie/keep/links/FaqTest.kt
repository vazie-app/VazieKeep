package app.vazie.keep.links

import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The FAQ screen's questions: only the backend's, cached on disk; with nothing cached, every try asks again. */
class FaqTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 0L
    private var answer: List<FaqItem>? = ITEMS
    private var asked = 0

    @Test
    fun theBackendsAnswerIsReadQuestionByQuestion() {
        val items = parseFaq(
            """{"items":[
                {"key":"a","question":{"ru":"Q","en":"Q en"},"answer":{"ru":"A\nB"},"link":{"url":"/legal/refunds","label":{"ru":"L"}}},
                {"key":"b","question":{"en":"only"},"answer":{"ru":"A"}},
                {"key":"c","question":{"ru":"Q"},"answer":{"ru":"A"},"link":{"url":"javascript:alert(1)","label":{"ru":"L"}}},
                {"key":"d","question":{"ru":"Q"},"answer":{"ru":"A"},"link":{"url":"//evil.example","label":{"ru":"L"}}}
            ]}""",
        )!!
        assertEquals(listOf("a", "c", "d"), items.map { it.key })
        assertEquals(FaqText("A\nB"), items[0].answer)
        assertEquals("Q en", items[0].question.pick(russian = false))
        assertEquals("A\nB", items[0].answer.pick(russian = false), "no English is the Russian")
        assertEquals(FaqLink("/legal/refunds", FaqText("L")), items[0].link)
        assertTrue(items.drop(1).all { it.link == null })
        assertEquals(emptyList(), parseFaq("""{"items":[]}"""))
        assertNull(parseFaq("{}"))
        assertNull(parseFaq("""{"items":[{"key":"x","question":{"en":"Q"},"answer":{"ru":"A"}}]}"""))
        assertEquals(ITEMS, parseFaq(encodeFaq(ITEMS)))
    }

    @Test
    fun nothingCachedIsLoadingThenUnavailableAndEveryTryAsks() = runTest {
        answer = null
        val repository = repository()
        assertEquals(FaqState.Loading, repository.state.value)
        repository.refresh()
        repository.refresh()
        assertEquals(FaqState.Unavailable, repository.state.value)
        assertEquals(2, asked)

        answer = ITEMS
        repository.refresh()
        assertEquals(FaqState.Ready(ITEMS), repository.state.value)
        repository.refresh()
        assertEquals(3, asked, "once cached, at most once per interval")
    }

    @Test
    fun theLastListIsKeptForAColdStartOffline() = runTest {
        repository().refresh()
        answer = null
        val restarted = repository()
        restarted.refresh()
        assertEquals(FaqState.Ready(ITEMS), restarted.state.value)
    }

    private fun TestScope.repository() = FaqRepository(
        source = { asked++; answer },
        file = File(folder.root, "faq.json"),
        nowMillis = { now },
        io = StandardTestDispatcher(testScheduler),
    )

    private companion object {
        val ITEMS = listOf(
            FaqItem("account", FaqText("Нужен ли аккаунт?", "Do I need an account?"), FaqText("Нет.\nТолько для Keep+.", "No.")),
            FaqItem("refund", FaqText("Возврат?"), FaqText("Пишите."), FaqLink("https://vazie.app/legal/refunds", FaqText("Возвраты"))),
        )
    }
}
