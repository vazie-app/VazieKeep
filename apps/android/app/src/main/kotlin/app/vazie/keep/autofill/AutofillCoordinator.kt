package app.vazie.keep.autofill

// What to answer a fill request with.
sealed interface FillPlan<out Id> {
    // Not a login form we serve.
    data object Ignore : FillPlan<Nothing>

    class Offer<Id>(
        val form: ParsedForm<Id>,
        val origin: RequestOrigin,
        val matches: List<CredentialMatch>,
        // The vault the index belongs to, or null while there is no index yet.
        val fingerprint: String?,
    ) : FillPlan<Id>
}

// Decides who is asking and which entries fit, from a parsed form and the non-secret index. Knows
// nothing about KDBX, the vault or Android views.
class AutofillCoordinator(
    private val index: AutofillIndexStore,
    private val associations: AppAssociationStore,
    private val matcher: CredentialMatcher,
    private val identity: PackageIdentity,
    private val browsers: TrustedBrowsers = TrustedBrowsers.Default,
) {
    fun <Id> plan(form: ParsedForm<Id>, requestingPackage: String): FillPlan<Id> {
        val origin = originOf(form, requestingPackage) ?: return FillPlan.Ignore
        val loaded = index.load()
        val matches = if (loaded == null) emptyList() else matcher.match(loaded, origin, associations.all())
        return FillPlan.Offer(form, origin, matches, loaded?.fingerprint)
    }

    // A page's host is believed only from a pinned browser; for anything else the package itself is
    // the identity, and a web domain it claims is ignored (an app can set one on its own views).
    fun originOf(form: ParsedForm<*>, requestingPackage: String): RequestOrigin? {
        val domain = form.webDomain
        if (browsers.isTrusted(requestingPackage, identity.signing(requestingPackage))) {
            // A trusted browser's own screens (address bar, settings) have no page behind them.
            val web = domain?.let { WebOrigin.fromRequest(form.webScheme, it) } ?: return null
            return RequestOrigin.Web(web)
        }
        return RequestOrigin.App(requestingPackage, ignoredWebClaim = domain != null)
    }
}
