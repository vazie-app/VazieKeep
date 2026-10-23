package app.vazie.keep.account

object EmailSuggestions {
    private val domains = listOf("gmail.com", "mail.ru", "yandex.ru", "proton.me", "icloud.com")

    fun completions(input: String): List<String> {
        val text = input.trim()
        if (text.isEmpty() || text.startsWith("@") || text.count { it == '@' } > 1) return emptyList()
        val at = text.indexOf('@')
        val name = if (at < 0) text else text.substring(0, at)
        val typed = if (at < 0) "" else text.substring(at + 1).lowercase()
        return domains
            .filter { it.startsWith(typed) && it != typed }
            .map { "$name@$it" }
    }
}
