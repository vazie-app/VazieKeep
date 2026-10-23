package app.vazie.keep.autofill

import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.core.SecretField
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// A synthetic KDBX through the real core: open, summaries, index, match, reveal one secret.
class AutofillRealCoreTest {
    private val vault = File(System.getProperty("keep.testVectors")).resolve("autofill/autofill-fixture.kdbx")
    private val matcher = CredentialMatcher(RustKeepCore)

    private fun open() = runBlocking { RustKeepCore.openVault(vault.readBytes(), "correct horse battery staple".toByteArray()) }

    private fun titles(index: AutofillIndex, origin: RequestOrigin, associations: List<AppAssociation> = emptyList()) =
        matcher.match(index, origin, associations).map { it.entry.title }

    private fun web(host: String, scheme: String = "https") = RequestOrigin.Web(WebOrigin(scheme, host, null))

    @Test
    fun theIndexHoldsOnlyLiveEntriesAndNoSecrets() = open().use { session ->
        val index = IndexBuilder.build(session.info(), session.groups(), session.entries())
        val titles = index.entries.map { it.title }
        assertFalse("Trashed example" in titles)
        assertTrue("Protected custom" in titles)
        assertEquals(session.info().fingerprint, index.fingerprint)
        val dump = index.entries.joinToString { "${it.title}${it.username}${it.url}" }
        listOf("example-pass-1", "SuperSecretPassword!", "4321", "custom-pass").forEach { assertFalse(it in dump, it) }
    }

    @Test
    fun theMatchingPolicyOnRealSummaries() = open().use { session ->
        val index = IndexBuilder.build(session.info(), session.groups(), session.entries())
        assertEquals(listOf("Example", "Example (second account)"), titles(index, web("example.com")))
        assertEquals(listOf("Accounts subdomain", "Example", "Example (second account)"), titles(index, web("accounts.example.com")))
        assertEquals(emptyList(), titles(index, web("example.net")))
        assertEquals(listOf("PayPal"), titles(index, web("www.paypal.com")))
        assertEquals(emptyList(), titles(index, web("paypal.com")))
        assertEquals(emptyList(), titles(index, web("paypai.com")))
        assertEquals(listOf("Public suffix"), titles(index, web("github.io")))
        assertEquals(emptyList(), titles(index, web("alice.github.io")))
        assertEquals(listOf("Insecure"), titles(index, web("plain.example.org")))
        assertEquals(emptyList(), titles(index, web("example.org")))
        assertEquals(listOf("Unicode host"), titles(index, web("xn--bcher-kva.example")))
        assertEquals(listOf("Facebook"), titles(index, web("www.facebook.com")))
        assertEquals(listOf("The Internet"), titles(index, web("the-internet.herokuapp.com")))
        assertEquals(emptyList(), titles(index, web("herokuapp.com")))
    }

    @Test
    fun anAppIsMatchedOnlyThroughItsAssociation() = open().use { session ->
        val index = IndexBuilder.build(session.info(), session.groups(), session.entries())
        val app = index.entries.first { it.title == "Android app" }
        val associations = listOf(AppAssociation("com.example.app", "C", index.fingerprint, app.entryId))
        assertEquals(listOf("Android app"), titles(index, RequestOrigin.App("com.example.app"), associations))
        assertEquals(emptyList(), titles(index, RequestOrigin.App("com.example.app")))
    }

    @Test
    fun onlyTheChosenEntrysPasswordIsRevealed() = open().use { session ->
        val index = IndexBuilder.build(session.info(), session.groups(), session.entries())
        val chosen = matcher.match(index, web("example.com"), emptyList()).first().entry
        val id = app.vazie.keep.core.EntryId(chosen.entryId)
        val before = session.entries().size
        val password = session.reveal(id, SecretField.Password).use { it.useChars { chars -> String(chars) } }
        assertEquals("example-pass-1", password)
        assertEquals(0, session.entry(id).customFields.size)
        assertEquals(before, session.entries().size)
    }
}
