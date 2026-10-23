package app.vazie.keep.autofill

import app.vazie.keep.core.RustKeepCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The real core's host policy (the one the iPhone uses), through the Android layer's rules.
class CredentialMatcherTest {
    private val matcher = CredentialMatcher(RustKeepCore)
    private val fp = "fp"

    private fun entry(id: String, url: String, title: String = id) = IndexedEntry(id, title, "user", url)

    private fun index(vararg entries: IndexedEntry) = AutofillIndex(fp, entries.toList())

    private fun web(host: String, scheme: String = "https") = RequestOrigin.Web(WebOrigin(scheme, host, null))

    private fun ids(index: AutofillIndex, origin: RequestOrigin, associations: List<AppAssociation> = emptyList()) =
        matcher.match(index, origin, associations).map { it.entry.entryId }

    @Test
    fun exactHostMatchesAndSubdomainsOfARegistrableSavedHostToo() {
        val index = index(entry("a", "https://example.com/login"))
        assertEquals(listOf("a"), ids(index, web("example.com")))
        assertEquals(listOf("a"), ids(index, web("accounts.example.com")))
        assertEquals(MatchKind.Subdomain, matcher.match(index, web("accounts.example.com"), emptyList()).single().kind)
        assertEquals(MatchKind.Exact, matcher.match(index, web("example.com"), emptyList()).single().kind)
    }

    @Test
    fun wrongParentSiblingAndOtherTldsNeverMatch() {
        val index = index(entry("a", "https://accounts.example.com"), entry("b", "https://example.com"))
        assertEquals(listOf("b"), ids(index, web("example.com")))
        assertEquals(emptyList(), ids(index, web("example.net")))
        assertEquals(emptyList(), ids(index, web("mail.example.org")))
        assertEquals(listOf("b"), ids(index, web("mail.example.com")))
        assertEquals(emptyList(), ids(index(entry("a", "https://accounts.example.com")), web("mail.example.com")))
        assertEquals(emptyList(), ids(index(entry("a", "https://example.com")), web("example.com.evil.net")))
        assertEquals(emptyList(), ids(index(entry("a", "https://example.com")), web("notexample.com")))
    }

    @Test
    fun lookAlikesAreDifferentSites() {
        val index = index(entry("a", "https://paypal.com"))
        assertEquals(emptyList(), ids(index, web("paypai.com")))
        assertEquals(emptyList(), ids(index, web("paypaI.com")))
        assertEquals(emptyList(), ids(index, web("xn--paypa-7ve.com")))
        assertEquals(emptyList(), ids(index, web("paypal.com.co")))
        assertEquals(listOf("a"), ids(index, web("www.paypal.com")))
    }

    @Test
    fun publicSuffixesOwnNothing() {
        assertEquals(emptyList(), ids(index(entry("a", "https://github.io")), web("alice.github.io")))
        assertEquals(emptyList(), ids(index(entry("a", "https://co.uk")), web("example.co.uk")))
        assertEquals(emptyList(), ids(index(entry("a", "https://com")), web("example.com")))
        assertEquals(emptyList(), ids(index(entry("a", "https://alice.github.io")), web("bob.github.io")))
        assertEquals(listOf("a"), ids(index(entry("a", "https://alice.github.io")), web("alice.github.io")))
    }

    @Test
    fun ipAddressesMatchOnlyThemselves() {
        val index = index(entry("a", "http://192.168.1.10"))
        assertEquals(listOf("a"), ids(index, web("192.168.1.10", "http")))
        assertEquals(emptyList(), ids(index, web("192.168.1.11", "http")))
        assertEquals(emptyList(), ids(index, web("1.192.168.1.10", "http")))
        assertEquals(emptyList(), ids(index(entry("a", "http://1.2.3.4")), web("5.1.2.3.4", "http")))
    }

    @Test
    fun anHttpsEntryIsNotOfferedToAnHttpPageButTheReverseIs() {
        assertEquals(emptyList(), ids(index(entry("a", "https://example.com")), web("example.com", "http")))
        assertEquals(listOf("a"), ids(index(entry("a", "http://example.com")), web("example.com", "https")))
        assertEquals(listOf("a"), ids(index(entry("a", "http://example.com")), web("example.com", "http")))
    }

    @Test
    fun anEntrySavedWithACustomPortIsNeverOffered() {
        assertEquals(emptyList(), ids(index(entry("a", "https://example.org:8443")), web("example.org")))
    }

    @Test
    fun unicodeHostsMatchTheirPunycodeForm() {
        assertEquals(listOf("a"), ids(index(entry("a", "https://bücher.example")), web("xn--bcher-kva.example")))
        assertEquals(emptyList(), ids(index(entry("a", "https://bücher.example")), web("bucher.example")))
    }

    @Test
    fun entriesWithoutAWebUrlAreNeverWebMatches() {
        val index = index(entry("a", ""), entry("b", "androidapp://com.example.app"), entry("c", "not a url at all ::"))
        assertEquals(emptyList(), ids(index, web("example.com")))
    }

    @Test
    fun matchesAreOrderedExactFirstThenByTitle() {
        val index = index(entry("sub", "https://example.com", "Zed"), entry("exact", "https://accounts.example.com", "Alpha"), entry("other", "https://example.com", "Beta"))
        assertEquals(listOf("exact", "other", "sub"), ids(index, web("accounts.example.com")))
    }

    @Test
    fun appsAreOfferedOnlyEntriesAssociatedWithTheirPackage() {
        val index = index(entry("a", ""), entry("b", ""))
        val association = AppAssociation("com.example.app", "CERT1", fp, "a")
        assertEquals(listOf("a"), ids(index, RequestOrigin.App("com.example.app"), listOf(association)))
        assertEquals(emptyList(), ids(index, RequestOrigin.App("com.example.app2"), listOf(association)))
        assertEquals(emptyList(), ids(index, RequestOrigin.App("com.example.app"), listOf(association.copy(fingerprint = "another vault"))))
        assertEquals(emptyList(), ids(index, RequestOrigin.App("com.example.app"), listOf(association.copy(entryId = "deleted"))))
        assertEquals(emptyList(), ids(index, RequestOrigin.App("com.example.app"), emptyList()))
    }

    @Test
    fun severalEntriesCanBeAssociatedWithOneApp() {
        val index = index(entry("a", ""), entry("b", ""))
        val list = listOf(AppAssociation("p", "C", fp, "a"), AppAssociation("p", "C", fp, "b"), AppAssociation("p", "C2", fp, "a"))
        assertEquals(listOf("a", "b"), ids(index, RequestOrigin.App("p"), list))
    }

    @Test
    fun similarPackageNamesAreNotTheSameApp() {
        val index = index(entry("a", ""))
        val association = AppAssociation("com.example.app", "C", fp, "a")
        for (similar in listOf("com.example.app.beta", "com.example.apps", "com.exarnple.app", "COM.EXAMPLE.APP")) {
            assertEquals(emptyList(), ids(index, RequestOrigin.App(similar), listOf(association)), similar)
        }
    }

    @Test
    fun theCertificateIsCheckedWhereTheCallerIsKnown() {
        val saved = listOf(AppAssociation("p", "CERT1", fp, "a"))
        val app = RequestOrigin.App("p")
        assertTrue(matcher.matchesApp("a", fp, app, SigningIdentity("CERT1"), saved))
        assertEquals(false, matcher.matchesApp("a", fp, app, SigningIdentity("FORGED"), saved))
        assertEquals(false, matcher.matchesApp("a", fp, app, null, saved))
        assertEquals(false, matcher.matchesApp("a", fp, RequestOrigin.App("other"), SigningIdentity("CERT1"), saved))
        assertEquals(false, matcher.matchesApp("b", fp, app, SigningIdentity("CERT1"), saved))
    }

    @Test
    fun aPendingApprovalFromASaveAcceptsTheFirstVerifiedCaller() {
        val pending = listOf(AppAssociation("p", "", fp, "a"))
        assertTrue(matcher.matchesApp("a", fp, RequestOrigin.App("p"), SigningIdentity("ANY"), pending))
        assertEquals(false, matcher.matchesApp("a", fp, RequestOrigin.App("p"), null, pending))
        assertEquals(false, matcher.matchesApp("a", fp, RequestOrigin.App("other"), SigningIdentity("ANY"), pending))
    }

    @Test
    fun anAppThatRotatedItsKeyIsStillTheApprovedApp() {
        val saved = listOf(AppAssociation("p", "OLD", fp, "a"))
        val rotated = SigningIdentity("NEW", setOf("OLD"))
        assertTrue(matcher.matchesApp("a", fp, RequestOrigin.App("p"), rotated, saved))
        assertEquals(false, matcher.matchesApp("a", fp, RequestOrigin.App("p"), SigningIdentity("NEW", setOf("UNRELATED")), saved))
    }

    @Test
    fun revalidationOfAWebEntryUsesTheSamePolicy() {
        val saved = entry("a", "https://example.com")
        assertTrue(matcher.matchesWeb(saved, WebOrigin("https", "accounts.example.com", null)))
        assertEquals(false, matcher.matchesWeb(saved, WebOrigin("https", "example.net", null)))
    }
}
