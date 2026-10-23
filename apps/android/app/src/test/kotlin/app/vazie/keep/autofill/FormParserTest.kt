package app.vazie.keep.autofill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FormParserTest {
    private val text = 0x1
    private val password = 0x81
    private val email = 0x21
    private val webPassword = 0xe1
    private val phone = 0x3

    private fun field(id: String, hints: List<String> = emptyList(), type: Int = text, focused: Boolean = false, visible: Boolean = true, domain: String? = null, isText: Boolean = true, name: String? = null, htmlType: String? = null, autocomplete: String? = null, value: String? = null) =
        FormNode(id, hints = hints, inputType = type, isFocused = focused, isVisible = visible, webDomain = domain, isText = isText, htmlName = name, htmlType = htmlType, htmlAutocomplete = autocomplete, value = value)

    @Test
    fun hintsFindUsernameAndPassword() {
        val form = FormParser.parse(listOf(field("u", listOf("username")), field("p", listOf("password"))))
        assertEquals("u", form?.username)
        assertEquals("p", form?.password)
    }

    @Test
    fun inputTypesFindAPasswordWithoutHints() {
        val form = FormParser.parse(listOf(field("u", type = email), field("p", type = password)))
        assertEquals("u", form?.username)
        assertEquals("p", form?.password)
        assertEquals("p", FormParser.parse(listOf(field("p", type = webPassword)))?.password)
        assertEquals("p", FormParser.parse(listOf(field("p", type = 0x12)))?.password)
    }

    @Test
    fun htmlAttributesAreRead() {
        val form = FormParser.parse(
            listOf(
                field("u", autocomplete = "username", domain = "example.com"),
                field("p", htmlType = "password", domain = "example.com"),
            ),
        )
        assertEquals("u", form?.username)
        assertEquals("p", form?.password)
        assertEquals("example.com", form?.webDomain)
    }

    @Test
    fun theTextFieldRightAbovePasswordIsTheUsernameWhenNothingSaysOtherwise() {
        val form = FormParser.parse(listOf(field("a"), field("b"), field("p", type = password)))
        assertEquals("b", form?.username)
    }

    @Test
    fun aFieldNamedLikeALoginIsAUsername() {
        val form = FormParser.parse(listOf(field("x", name = "user_login"), field("p", type = password)))
        assertEquals("x", form?.username)
    }

    @Test
    fun registrationFormsAreSavedButNeverFilled() {
        val form = FormParser.parse(listOf(field("u", listOf("emailAddress")), field("n", listOf("newPassword")), field("c", listOf("newPassword"))))!!
        assertEquals(FormKind.Registration, form.kind)
        assertEquals("u", form.username)
        assertEquals("n", form.newPassword)
        assertEquals("c", form.confirmPassword)
        assertNull(form.password)
        assertEquals(false, form.canFill)
        assertEquals("n", form.savedPassword)
        assertEquals(FormKind.Registration, FormParser.parse(listOf(field("n", autocomplete = "new-password"), field("c", autocomplete = "new-password")))?.kind)
    }

    @Test
    fun changePasswordFormsFillTheCurrentPasswordAndSaveTheNewOne() {
        val form = FormParser.parse(listOf(field("old", listOf("password")), field("new", listOf("newPassword")), field("again", listOf("newPassword"))))!!
        assertEquals(FormKind.PasswordChange, form.kind)
        assertEquals("old", form.password)
        assertEquals("new", form.newPassword)
        assertEquals("again", form.confirmPassword)
        assertEquals("new", form.savedPassword)
        assertNull(form.username)
        assertTrue(form.canFill)
    }

    @Test
    fun threePlainPasswordFieldsAreCurrentNewAndConfirm() {
        val form = FormParser.parse(listOf(field("a", type = password), field("b", type = password), field("c", type = password)))!!
        assertEquals(FormKind.PasswordChange, form.kind)
        assertEquals("a", form.password)
        assertEquals("b", form.newPassword)
        assertEquals("c", form.confirmPassword)
    }

    @Test
    fun twoPlainPasswordFieldsAreARegistrationUnlessTheSavedValuesDiffer() {
        fun submitted(first: String, second: String) = FormParser.parseSubmitted(
            listOf(field("u", listOf("username")), field("a", type = password, value = first), field("b", type = password, value = second)),
        )!!
        val same = submitted("pw", "pw")
        assertEquals(FormKind.Registration, same.kind)
        assertEquals("a", same.savedPassword)
        val different = submitted("old", "new")
        assertEquals(FormKind.PasswordChange, different.kind)
        assertEquals("a", different.password)
        assertEquals("b", different.savedPassword)
    }

    @Test
    fun aFormWithNoPasswordIsNotACredentialForm() {
        assertNull(FormParser.parse(listOf(field("otp", autocomplete = "one-time-code"))))
        assertNull(FormParser.parse(listOf(field("q", name = "search"), field("go"))))
        assertNull(FormParser.parse(listOf(field("card", autocomplete = "cc-number"), field("exp", autocomplete = "cc-exp"))))
        assertNull(FormParser.parse(listOf(field("tel", htmlType = "tel"))))
    }

    @Test
    fun phoneFieldsAreNeverUsernames() {
        val form = FormParser.parse(listOf(field("tel", listOf("phoneNumber")), field("p", listOf("password"))))
        assertEquals("p", form?.password)
        assertNull(form?.username)
        assertNull(FormParser.parse(listOf(field("tel", type = phone))))
        assertNull(FormParser.parse(listOf(field("tel", htmlType = "tel"))))
    }

    @Test
    fun anEmailAloneIsNotALoginFormButAnExplicitUsernameIs() {
        assertNull(FormParser.parse(listOf(field("e", listOf("emailAddress")))))
        assertNull(FormParser.parse(listOf(field("e", type = email))))
        val form = FormParser.parse(listOf(field("u", listOf("username"))))
        assertEquals("u", form?.username)
        assertNull(form?.password)
    }

    @Test
    fun invisibleAndNonTextViewsAreIgnored() {
        assertNull(FormParser.parse(listOf(field("p", listOf("password"), visible = false))))
        assertNull(FormParser.parse(listOf(field("p", listOf("password"), isText = false))))
    }

    @Test
    fun theFocusedPasswordWinsAmongSeveral() {
        val form = FormParser.parse(listOf(field("p1", listOf("password")), field("p2", listOf("password"), focused = true)))
        assertEquals("p2", form?.password)
    }

    @Test
    fun aUsernameFromAnotherWebOriginIsNotFilled() {
        val form = FormParser.parse(listOf(field("u", listOf("username"), domain = "evil.example"), field("p", listOf("password"), domain = "bank.example")))
        assertEquals("p", form?.password)
        assertNull(form?.username)
        assertEquals("bank.example", form?.webDomain)
    }

    @Test
    fun noFieldsMeansNoForm() {
        assertNull(FormParser.parse(emptyList<FormNode<String>>()))
        assertNull(FormParser.parse(listOf(field("x"), field("y"))))
        assertNotNull(FormParser.parse(listOf(field("p", listOf("password")))))
    }

    @Test
    fun nodesNeverPrintTheirContents() {
        assertEquals("FormNode(<redacted>)", field("secret-id", name = "secret").toString())
    }
}
