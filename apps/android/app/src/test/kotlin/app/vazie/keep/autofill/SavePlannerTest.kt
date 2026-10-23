package app.vazie.keep.autofill

import app.vazie.keep.testing.FakeKeepCore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SavePlannerTest {
    private val matcher = CredentialMatcher(FakeKeepCore())
    private val fp = "fp"
    private val web = RequestOrigin.Web(WebOrigin("https", "example.com", null))

    private fun entry(id: String, user: String, url: String = "https://example.com") = IndexedEntry(id, id, user, url)

    private fun plan(entries: List<IndexedEntry>, username: String, origin: RequestOrigin = web, associations: List<AppAssociation> = emptyList()) =
        SavePlanner.plan(AutofillIndex(fp, entries), origin, username, matcher, associations)

    @Test
    fun withoutAnyEntryForTheSiteANewOneIsCreated() {
        assertEquals(SavePlan.Create(emptyList()), plan(listOf(entry("other", "u", "https://other.example")), "alice"))
        assertEquals(SavePlan.Create(emptyList()), SavePlanner.plan(null, web, "alice", matcher, emptyList()))
    }

    @Test
    fun theSameSiteAndUsernameIsAnUpdate() {
        val plan = plan(listOf(entry("a", "alice"), entry("b", "bob")), "alice")
        assertEquals(SavePlan.Update(entry("a", "alice")), plan)
    }

    @Test
    fun aDifferentUsernameIsANewEntryWithTheOthersOfferedAsAlternatives() {
        val plan = assertIs<SavePlan.Create>(plan(listOf(entry("a", "alice"), entry("b", "bob")), "carol"))
        assertEquals(listOf("a", "b"), plan.alternatives.map { it.entryId })
    }

    @Test
    fun severalEntriesWithTheSameUsernameAreNeverGuessed() {
        val plan = assertIs<SavePlan.Choose>(plan(listOf(entry("a", "alice"), entry("b", "alice"), entry("c", "bob")), "alice"))
        assertEquals(listOf("a", "b"), plan.candidates.map { it.entryId })
    }

    @Test
    fun aFormWithNoUsernameUpdatesTheOnlyEntryOrAsks() {
        assertEquals(SavePlan.Update(entry("a", "alice")), plan(listOf(entry("a", "alice")), ""))
        assertIs<SavePlan.Choose>(plan(listOf(entry("a", "alice"), entry("b", "bob")), "   "))
        assertEquals(SavePlan.Create(emptyList()), plan(emptyList(), ""))
    }

    @Test
    fun usernamesAreTrimmedAndOnlyEmailAddressesIgnoreCase() {
        assertEquals(true, SavePlanner.sameUsername("alice", " alice "))
        assertEquals(false, SavePlanner.sameUsername("alice", "Alice"))
        assertEquals(true, SavePlanner.sameUsername("Alice@Example.org", "alice@example.org"))
        assertEquals(false, SavePlanner.sameUsername("", ""))
        assertEquals(false, SavePlanner.sameUsername("alice", "alice2"))
    }

    @Test
    fun aSubdomainLoginMatchesTheSavedParentSite() {
        val origin = RequestOrigin.Web(WebOrigin("https", "accounts.example.com", null))
        assertEquals(SavePlan.Update(entry("a", "alice")), plan(listOf(entry("a", "alice")), "alice", origin))
    }

    @Test
    fun anAppMatchesOnlyItsAssociatedEntries() {
        val app = RequestOrigin.App("com.example.app")
        val entries = listOf(entry("a", "alice", ""), entry("b", "alice", "https://example.com"))
        assertEquals(SavePlan.Create(emptyList()), plan(entries, "alice", app))
        val associated = plan(entries, "alice", app, listOf(AppAssociation("com.example.app", "C", fp, "a")))
        assertEquals(SavePlan.Update(entry("a", "alice", "")), associated)
    }

    @Test
    fun aWebPageNeverMatchesAnEntryAssociatedWithABrowserPackage() {
        val entries = listOf(entry("a", "alice", ""))
        val browserAssociation = listOf(AppAssociation("com.android.chrome", "C", fp, "a"))
        assertEquals(SavePlan.Create(emptyList()), plan(entries, "alice", web, browserAssociation))
    }

    @Test
    fun theUrlKeepsSchemeAndHostOnly() {
        assertEquals("https://example.com", SavePlanner.urlFor(RequestOrigin.Web(WebOrigin("https", "example.com", null))))
        assertEquals("http://localhost", SavePlanner.urlFor(RequestOrigin.Web(WebOrigin("http", "localhost", null))))
        assertEquals("", SavePlanner.urlFor(RequestOrigin.App("com.example.app")))
    }

    @Test
    fun titlesAreTheHostOrTheAppLabel() {
        assertEquals("example.com", SavePlanner.titleFor(RequestOrigin.Web(WebOrigin("https", "www.example.com", null)), null))
        assertEquals("accounts.example.com", SavePlanner.titleFor(RequestOrigin.Web(WebOrigin("https", "accounts.example.com", null)), null))
        assertEquals("Example App", SavePlanner.titleFor(RequestOrigin.App("com.example.app"), "Example App"))
        assertEquals("com.example.app", SavePlanner.titleFor(RequestOrigin.App("com.example.app"), null))
        assertEquals("com.example.app", SavePlanner.titleFor(RequestOrigin.App("com.example.app"), "  "))
    }

    @Test
    fun onlyRealCredentialFormsFromBelievableOriginsAreEligible() {
        fun form(kind: FormKind, password: String?, newPassword: String?) = ParsedForm(kind, "u", password, newPassword, null, null, null)
        assertEquals(true, SaveEligibility.eligible(form(FormKind.Login, "p", null), web))
        assertEquals(true, SaveEligibility.eligible(form(FormKind.Registration, null, "n"), web))
        assertEquals(true, SaveEligibility.eligible(form(FormKind.PasswordChange, "p", "n"), RequestOrigin.App("a")))
        assertEquals(false, SaveEligibility.eligible(form(FormKind.Login, null, null), web))
        assertEquals(false, SaveEligibility.eligible(form(FormKind.Login, "p", null), RequestOrigin.App("a", ignoredWebClaim = true)))
    }
}
