package app.vazie.keep.autofill

import app.vazie.keep.testing.FakeKeepCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutofillCoordinatorTest {
    private class MemoryIndex(var index: AutofillIndex?) : AutofillIndexStore {
        override fun load() = index

        override fun save(index: AutofillIndex) {
            this.index = index
        }

        override fun clear() {
            index = null
        }
    }

    private class MemoryAssociations(var list: List<AppAssociation> = emptyList()) : AppAssociationStore {
        override fun all() = list

        override fun add(association: AppAssociation) {
            list += association
        }

        override fun retain(fingerprint: String, existingEntryIds: Set<String>) {
            list = list.filter { it.fingerprint == fingerprint && it.entryId in existingEntryIds }
        }

        override fun clear() {
            list = emptyList()
        }
    }

    private class Identity(val certs: Map<String, SigningIdentity>) : PackageIdentity {
        override fun signing(packageName: String) = certs[packageName]

        override fun label(packageName: String) = packageName
    }

    private val fp = "fp"
    private val index = AutofillIndex(fp, listOf(IndexedEntry("a", "Example", "alice", "https://example.com"), IndexedEntry("b", "App entry", "bob", "")))
    private val browsers = TrustedBrowsers(mapOf("com.browser" to setOf("GOOD")))

    private fun coordinator(
        index: AutofillIndex? = this.index,
        associations: List<AppAssociation> = emptyList(),
        certs: Map<String, SigningIdentity> = mapOf(
            "com.browser" to SigningIdentity("GOOD"),
            "com.evil" to SigningIdentity("EVIL"),
            "com.example.app" to SigningIdentity("APPCERT"),
            "com.fake.browser" to SigningIdentity("GOOD"),
        ),
    ) = AutofillCoordinator(MemoryIndex(index), MemoryAssociations(associations), CredentialMatcher(FakeKeepCore()), Identity(certs), browsers)

    private fun form(domain: String? = null, scheme: String? = null) = ParsedForm(FormKind.Login, "u", "p", null, null, domain, scheme)

    private fun offer(plan: FillPlan<String>) = assertIs<FillPlan.Offer<String>>(plan)

    @Test
    fun aPinnedBrowsersWebDomainIsBelievedAndMatched() {
        val plan = offer(coordinator().plan(form("accounts.example.com", "https"), "com.browser"))
        assertEquals(RequestOrigin.Web(WebOrigin("https", "accounts.example.com", null)), plan.origin)
        assertEquals(listOf("a"), plan.matches.map { it.entry.entryId })
        assertEquals(fp, plan.fingerprint)
    }

    @Test
    fun aPackageThatOnlyLooksLikeABrowserIsNotBelieved() {
        val plan = offer(coordinator().plan(form("example.com"), "com.fake.browser").let { it })
        // Same certificate as the pinned browser but a different package name.
        assertIs<RequestOrigin.App>(plan.origin)
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun aPinnedBrowserThatRotatedItsKeyStaysTrusted() {
        val rotated = mapOf("com.browser" to SigningIdentity("NEWKEY", setOf("GOOD")))
        val plan = offer(coordinator(certs = rotated).plan(form("example.com", "https"), "com.browser"))
        assertIs<RequestOrigin.Web>(plan.origin)
    }

    @Test
    fun aPinnedPackageWithAnotherCertificateIsNotBelieved() {
        val plan = offer(coordinator(certs = mapOf("com.browser" to SigningIdentity("BAD"))).plan(form("example.com"), "com.browser"))
        assertIs<RequestOrigin.App>(plan.origin)
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun anAppThatClaimsAWebDomainIsIgnoredAndTreatedAsAnApp() {
        val plan = offer(coordinator().plan(form("example.com"), "com.evil"))
        assertEquals(RequestOrigin.App("com.evil", ignoredWebClaim = true), plan.origin)
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun aTrustedBrowsersOwnScreensGetNothing() {
        assertEquals(FillPlan.Ignore, coordinator().plan(form(null), "com.browser"))
    }

    @Test
    fun aNativeAppMatchesItsAssociationOnly() {
        val associations = listOf(AppAssociation("com.example.app", "APPCERT", fp, "b"))
        val plan = offer(coordinator(associations = associations).plan(form(), "com.example.app"))
        assertEquals(listOf("b"), plan.matches.map { it.entry.entryId })
        assertEquals(MatchKind.Associated, plan.matches.single().kind)
        assertTrue(offer(coordinator(associations = associations).plan(form(), "com.evil")).matches.isEmpty())
    }

    @Test
    fun beforeUnlockAnAppIsMatchedByPackageAndTheCertificateIsCheckedLater() {
        val associations = listOf(AppAssociation("com.example.app", "OLDCERT", fp, "b"))
        assertEquals(1, offer(coordinator(associations = associations).plan(form(), "com.example.app")).matches.size)
    }

    @Test
    fun anUnknownPackageHasNoMatches() {
        val plan = offer(coordinator().plan(form(), "com.unknown"))
        assertEquals(RequestOrigin.App("com.unknown"), plan.origin)
        assertTrue(plan.matches.isEmpty())
    }

    @Test
    fun withoutAnIndexEveryRequestOffersOnlySearch() {
        val plan = offer(coordinator(index = null).plan(form("example.com"), "com.browser"))
        assertTrue(plan.matches.isEmpty())
        assertNull(plan.fingerprint)
    }
}
