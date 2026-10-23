package app.vazie.keep.account

import org.junit.Assert.assertEquals
import org.junit.Test

class EmailSuggestionsTest {
    @Test
    fun `a bare name offers every domain`() {
        assertEquals(
            listOf("anna@gmail.com", "anna@mail.ru", "anna@yandex.ru", "anna@proton.me", "anna@icloud.com"),
            EmailSuggestions.completions("anna"),
        )
    }

    @Test
    fun `a typed domain prefix narrows the list`() {
        assertEquals(listOf("anna@mail.ru"), EmailSuggestions.completions("anna@m"))
        assertEquals(listOf("anna@gmail.com"), EmailSuggestions.completions("anna@G"))
    }

    @Test
    fun `nothing is offered for an empty, complete or odd address`() {
        assertEquals(emptyList<String>(), EmailSuggestions.completions(""))
        assertEquals(emptyList<String>(), EmailSuggestions.completions("anna@gmail.com"))
        assertEquals(emptyList<String>(), EmailSuggestions.completions("@gm"))
        assertEquals(emptyList<String>(), EmailSuggestions.completions("a@b@c"))
        assertEquals(emptyList<String>(), EmailSuggestions.completions("anna@x"))
    }
}
