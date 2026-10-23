package app.vazie.keep.autofill

// Who is asking for a credential.
sealed interface RequestOrigin {
    // A page in a browser we trust to report the page's host honestly.
    data class Web(val origin: WebOrigin) : RequestOrigin

    // An app, named by the package the system reports. Also what a non-browser app that claims a web
    // domain is treated as: the claim is ignored. The signing certificate is checked later, by the
    // screen the system opens for the person's tap, where the calling package is visible.
    data class App(val packageName: String, val ignoredWebClaim: Boolean = false) : RequestOrigin
}

// The signing identity of an installed package. `lineage` holds the certificates it rotated away
// from, which the platform vouches for (APK Signature Scheme v3 proof-of-rotation): an app that
// legitimately changed its key is still the same app.
class SigningIdentity(val current: String, val lineage: Set<String> = emptySet()) {
    fun vouchesFor(approved: String) = approved == current || approved in lineage

    override fun toString() = "SigningIdentity(<redacted>)"
}

// Browsers whose report of the page host is believed, pinned by package and signing certificate.
// The framework tells us the package; nothing in the page or app can change it. A package that
// merely says it is a browser, or an app that sets a web domain on its own views, is not here.
class TrustedBrowsers(private val pinned: Map<String, Set<String>>) {
    val packages: Set<String> get() = pinned.keys

    fun isTrusted(packageName: String, signing: SigningIdentity?): Boolean =
        signing != null && pinned[packageName]?.any(signing::vouchesFor) == true

    companion object {
        // Chrome stable's release certificate, read from the signed APK on the test device
        // (apksigner verify --print-certs). Further browsers are added only once their
        // certificates have been verified the same way, and need a <queries> entry in the manifest.
        val Default = TrustedBrowsers(
            mapOf("com.android.chrome" to setOf("f0fd6c5b410f25cb25c3b53346c8972fae30f8ee7411df910480ad6b2d60db83")),
        )
    }
}

interface PackageIdentity {
    // Null when the package is unknown, unsigned, or not visible to us.
    fun signing(packageName: String): SigningIdentity?

    fun label(packageName: String): String?
}

sealed interface CallerCheck {
    data class Verified(val signing: SigningIdentity?) : CallerCheck

    data object Unverified : CallerCheck
}

// Checked where Android tells us who really started the screen (the calling package), against what
// the service decided from the screen's structure.
fun interface CallerVerifier {
    fun verify(origin: RequestOrigin): CallerCheck
}

class PlatformCallerVerifier(
    private val callingPackage: () -> String?,
    private val identity: PackageIdentity,
    private val browsers: TrustedBrowsers,
) : CallerVerifier {
    override fun verify(origin: RequestOrigin): CallerCheck {
        val caller = callingPackage() ?: return CallerCheck.Unverified
        val signing = identity.signing(caller)
        return when (origin) {
            is RequestOrigin.Web -> if (browsers.isTrusted(caller, signing)) CallerCheck.Verified(signing) else CallerCheck.Unverified
            is RequestOrigin.App -> if (caller == origin.packageName && signing != null) CallerCheck.Verified(signing) else CallerCheck.Unverified
        }
    }
}
