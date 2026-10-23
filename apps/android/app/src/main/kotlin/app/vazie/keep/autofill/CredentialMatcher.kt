package app.vazie.keep.autofill

import app.vazie.keep.core.HostMatch
import app.vazie.keep.core.KeepCore

enum class MatchKind { Exact, Subdomain, Associated }

data class CredentialMatch(val entry: IndexedEntry, val kind: MatchKind) {
    override fun toString() = "CredentialMatch($kind)"
}

// Which indexed entries may be offered to a request. Hosts follow the shared core policy (the one
// the iPhone uses): the exact host, or a subdomain of a registrable saved host; never a parent, a
// sibling, a look-alike, a public suffix or an IP that differs. On top of it an https entry is not
// offered to an http page, and an entry saved with a custom port is not offered at all.
class CredentialMatcher(private val core: KeepCore) {
    fun match(index: AutofillIndex, origin: RequestOrigin, associations: List<AppAssociation>): List<CredentialMatch> {
        val found = when (origin) {
            is RequestOrigin.Web -> index.entries.mapNotNull { entry -> webKind(entry, origin.origin)?.let { CredentialMatch(entry, it) } }
            is RequestOrigin.App -> appMatches(index, origin, associations)
        }
        return found.sortedWith(compareBy<CredentialMatch> { it.kind.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.entry.title })
    }

    fun matchesWeb(entry: IndexedEntry, request: WebOrigin): Boolean = webKind(entry, request) != null

    // After unlock, with the caller's signing identity read where Android names the caller: the app
    // must have been approved for this entry, under this certificate or one it rotated from.
    fun matchesApp(entryId: String, fingerprint: String, origin: RequestOrigin.App, signing: SigningIdentity?, associations: List<AppAssociation>): Boolean =
        signing != null && associations.any {
            it.packageName == origin.packageName && it.fingerprint == fingerprint && it.entryId == entryId &&
                // An empty certificate is a pending approval from a save: the first verified caller binds it.
                (it.certDigest.isEmpty() || signing.vouchesFor(it.certDigest))
        }

    private fun webKind(entry: IndexedEntry, request: WebOrigin): MatchKind? {
        val saved = WebOrigin.parseEntryUrl(entry.url) ?: return null
        if (saved.port != null || request.port != null) return null
        if (saved.scheme == "https" && request.scheme != "https") return null
        return when (core.matchHost(saved.host, request.host)) {
            HostMatch.Exact -> MatchKind.Exact
            HostMatch.Subdomain -> MatchKind.Subdomain
            HostMatch.None -> null
        }
    }

    // Before unlock only the package name is known (the service can't read other apps' certificates);
    // the certificate is checked in the authentication screen before anything is revealed.
    private fun appMatches(index: AutofillIndex, origin: RequestOrigin.App, associations: List<AppAssociation>): List<CredentialMatch> {
        val byId = index.entries.associateBy { it.entryId }
        return associations
            .filter { it.packageName == origin.packageName && it.fingerprint == index.fingerprint }
            .mapNotNull { byId[it.entryId] }
            .distinctBy { it.entryId }
            .map { CredentialMatch(it, MatchKind.Associated) }
    }
}
