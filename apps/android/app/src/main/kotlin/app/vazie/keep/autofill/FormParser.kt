package app.vazie.keep.autofill

// What the parser needs to know about one view. The Android adapter fills it from an
// AssistStructure; tests build it directly. `Id` is AutofillId on the device.
class FormNode<Id>(
    val id: Id,
    val hints: List<String> = emptyList(),
    val inputType: Int = 0,
    val isText: Boolean = true,
    val isVisible: Boolean = true,
    val isFocused: Boolean = false,
    val htmlTag: String? = null,
    val htmlType: String? = null,
    val htmlName: String? = null,
    val htmlId: String? = null,
    val htmlAutocomplete: String? = null,
    val resourceEntry: String? = null,
    val hintText: String? = null,
    val webDomain: String? = null,
    val webScheme: String? = null,
    // What the field holds right now. Only a save request carries it; never printed or logged.
    val value: CharSequence? = null,
) {
    override fun toString() = "FormNode(<redacted>)"
}

enum class FieldKind { CurrentPassword, NewPassword, Username, Email, Phone, Other }

enum class FormKind { Login, Registration, PasswordChange, Unsupported }

// `password` is the field to fill (a current password); `newPassword` is the one a save request
// stores. A login has only the first, a registration only the second, a password change both.
class ParsedForm<Id>(
    val kind: FormKind,
    val username: Id?,
    val password: Id?,
    val newPassword: Id? = null,
    val confirmPassword: Id? = null,
    // What the framework says the field's web page is. Whether it can be believed is for the
    // coordinator to decide: only a trusted browser's claim is.
    val webDomain: String?,
    val webScheme: String?,
) {
    // The field whose value a save would store.
    val savedPassword: Id? get() = newPassword ?: password

    val canFill: Boolean get() = password != null || (username != null && kind == FormKind.Login)

    override fun toString() = "ParsedForm($kind)"
}

// Finds the login form in a screen: one current-password field and the username field before it.
// Only login forms are served: new-password and confirm-password forms are left alone, and phone
// fields are never filled.
object FormParser {
    private const val MAX_NODES = 3000

    fun <Id> parse(nodes: List<FormNode<Id>>): ParsedForm<Id>? {
        val fields = nodes.take(MAX_NODES).filter { it.isText && it.isVisible }
        val kinds = fields.map(::classify)
        val current = fields.indices.filter { kinds[it] == FieldKind.CurrentPassword }
        val fresh = fields.indices.filter { kinds[it] == FieldKind.NewPassword }
        val focusedFirst = { list: List<Int> -> list.sortedBy { if (fields[it].isFocused) 0 else 1 } }
        return when {
            fresh.isNotEmpty() && current.isNotEmpty() -> build(
                fields, kinds, FormKind.PasswordChange, password = focusedFirst(current).first(), newPassword = fresh[0], confirm = fresh.getOrNull(1),
            )
            fresh.isNotEmpty() -> build(fields, kinds, FormKind.Registration, password = null, newPassword = fresh[0], confirm = fresh.getOrNull(1))
            // Plain password fields with no hint: three are current, new, confirm; two are taken as a
            // registration (password, confirm) until a saved form shows different values.
            current.size >= 3 && allPlain(current.map { fields[it] }) ->
                build(fields, kinds, FormKind.PasswordChange, password = current[0], newPassword = current[1], confirm = current[2])
            current.size == 2 && allPlain(current.map { fields[it] }) ->
                build(fields, kinds, FormKind.Registration, password = null, newPassword = current[0], confirm = current[1])
            current.isNotEmpty() -> build(fields, kinds, FormKind.Login, password = focusedFirst(current).first(), newPassword = null, confirm = null)
            else -> usernameOnly(fields, kinds)
        }
    }

    // A saved form is read with its values, which settle the two-password ambiguity.
    fun <Id> parseSubmitted(nodes: List<FormNode<Id>>): ParsedForm<Id>? {
        val form = parse(nodes) ?: return null
        val confirm = form.confirmPassword ?: return form
        val byId = nodes.associateBy { it.id }
        val first = byId[form.newPassword]?.value?.toString().orEmpty()
        val second = byId[confirm]?.value?.toString().orEmpty()
        if (form.kind != FormKind.Registration || first == second || form.password != null) return form
        // Two plain password fields with different values: the first is the old password.
        return ParsedForm(FormKind.PasswordChange, form.username, form.newPassword, confirm, null, form.webDomain, form.webScheme)
    }

    private fun <Id> build(
        fields: List<FormNode<Id>>,
        kinds: List<FieldKind>,
        kind: FormKind,
        password: Int?,
        newPassword: Int?,
        confirm: Int?,
    ): ParsedForm<Id> {
        val anchor = listOfNotNull(password, newPassword).min()
        val usernameIndex = (anchor - 1 downTo 0).firstOrNull { kinds[it].isUsernameLike() } ?: nearestPlainText(fields, kinds, anchor)
        val reference = fields[newPassword ?: password!!]
        val domain = reference.webDomain
        val username = usernameIndex?.let { fields[it] }?.takeIf { it.webDomain == domain }
        fun same(index: Int?) = index?.let { fields[it] }?.takeIf { it.webDomain == domain }?.id
        return ParsedForm(kind, username?.id, same(password), same(newPassword), same(confirm), domain, reference.webScheme)
    }

    // Username-first flows: a field that says plainly it is the username, with no password yet.
    private fun <Id> usernameOnly(fields: List<FormNode<Id>>, kinds: List<FieldKind>): ParsedForm<Id>? {
        val strong = fields.indices.filter { kinds[it] == FieldKind.Username && explicitUsername(fields[it]) }
        val only = strong.singleOrNull() ?: strong.firstOrNull { fields[it].isFocused } ?: return null
        val field = fields[only]
        return ParsedForm(FormKind.Login, field.id, null, null, null, field.webDomain, field.webScheme)
    }

    fun <Id> classify(node: FormNode<Id>): FieldKind {
        val hints = node.hints.map { it.lowercase() }
        val autocomplete = node.htmlAutocomplete?.lowercase()?.split(' ').orEmpty()
        val type = node.htmlType?.lowercase()
        val variation = node.inputType and INPUT_MASK_VARIATION
        val inputClass = node.inputType and INPUT_MASK_CLASS
        return when {
            "newpassword" in hints || "new-password" in autocomplete -> FieldKind.NewPassword
            "password" in hints || "current-password" in autocomplete || type == "password" || isPasswordInput(inputClass, variation) ->
                FieldKind.CurrentPassword
            "phone" in hints || "phonenumber" in hints || type == "tel" || "tel" in autocomplete || inputClass == CLASS_PHONE -> FieldKind.Phone
            "username" in hints || "username" in autocomplete -> FieldKind.Username
            "emailaddress" in hints || "email" in autocomplete || type == "email" ||
                (inputClass == CLASS_TEXT && (variation == VARIATION_EMAIL || variation == VARIATION_WEB_EMAIL)) -> FieldKind.Email
            looksLikeUsername(node) -> FieldKind.Username
            else -> FieldKind.Other
        }
    }

    // Fields that only look like passwords (inputType, type=password), with no hint saying which kind.
    private fun allPlain(nodes: List<FormNode<*>>) = nodes.none { node ->
        node.hints.any { it.equals("password", true) } || node.htmlAutocomplete?.split(' ')?.any { it.equals("current-password", true) } == true
    }

    private fun explicitUsername(node: FormNode<*>): Boolean =
        node.hints.any { it.equals("username", true) } || node.htmlAutocomplete?.split(' ')?.any { it.equals("username", true) } == true

    private fun looksLikeUsername(node: FormNode<*>): Boolean {
        val words = listOfNotNull(node.htmlName, node.htmlId, node.resourceEntry, node.hintText).joinToString(" ").lowercase()
        return USERNAME_WORDS.any { it in words }
    }

    private fun FieldKind.isUsernameLike() = this == FieldKind.Username || this == FieldKind.Email

    private fun <Id> chooseIndex(fields: List<FormNode<Id>>, kinds: List<FieldKind>, wanted: (FieldKind) -> Boolean): Int? {
        val candidates = fields.indices.filter { wanted(kinds[it]) }
        return candidates.firstOrNull { fields[it].isFocused } ?: candidates.firstOrNull()
    }

    // No hints at all: the plain text field right above the password is the username.
    private fun <Id> nearestPlainText(fields: List<FormNode<Id>>, kinds: List<FieldKind>, before: Int): Int? {
        val index = before - 1
        if (index < 0) return null
        return index.takeIf { kinds[it] == FieldKind.Other && fields[it].inputType and INPUT_MASK_CLASS == CLASS_TEXT }
    }

    private fun isPasswordInput(inputClass: Int, variation: Int) = when (inputClass) {
        CLASS_TEXT -> variation == VARIATION_PASSWORD || variation == VARIATION_VISIBLE_PASSWORD || variation == VARIATION_WEB_PASSWORD
        CLASS_NUMBER -> variation == NUMBER_VARIATION_PASSWORD
        else -> false
    }

    // android.text.InputType, copied so the parser runs in plain JVM tests.
    private const val INPUT_MASK_CLASS = 0xf
    private const val INPUT_MASK_VARIATION = 0xff0
    private const val CLASS_TEXT = 0x1
    private const val CLASS_NUMBER = 0x2
    private const val CLASS_PHONE = 0x3
    private const val VARIATION_EMAIL = 0x20
    private const val VARIATION_PASSWORD = 0x80
    private const val VARIATION_VISIBLE_PASSWORD = 0x90
    private const val VARIATION_WEB_EMAIL = 0xd0
    private const val VARIATION_WEB_PASSWORD = 0xe0
    private const val NUMBER_VARIATION_PASSWORD = 0x10

    private val USERNAME_WORDS = listOf("user", "login", "email", "e-mail", "account", "uname")
}
