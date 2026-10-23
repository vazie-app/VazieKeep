package app.vazie.keep.core

// A public key the app trusts for Keep+ leases. `publicKey` is the raw 32-byte Ed25519 key, base64url.
data class LeaseKey(val kid: String, val publicKey: String)

enum class LeaseTier { Free, KeepPlus }

data class KeepLease(
    val subject: String,
    val tier: LeaseTier,
    val lifetime: Boolean,
    val issuedAt: Long,
    val leaseExpiresAt: Long,
    val entitlementExpiresAt: Long?,
    val featuresVersion: Int,
    val revision: Long,
    val nonce: String,
    val keyId: String,
)

enum class KeepAccessState { Free, PlusActive, PlusOfflineGrace, PlusLifetime, PlusExpired }

data class KeepAccess(
    val state: KeepAccessState,
    val plusFeatures: Boolean,
    val needsOnlineVerification: Boolean,
    val leaseValidUntil: Long?,
    val observedDeviceTime: Long,
) {
    companion object {
        val Free = KeepAccess(KeepAccessState.Free, plusFeatures = false, needsOnlineVerification = false, leaseValidUntil = null, observedDeviceTime = 0)
    }
}

// Features that need Keep+. Anything that protects passwords is free and is deliberately not here.
enum class PlusFeature { MultiVault, Totp, PasswordHealth, BreachMonitoring, HistoryUi, AttachmentWrite, PremiumThemes }

class LeaseVerifyException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { Malformed, UnsupportedAlgorithm, UnknownKey, BadSignature, Unsupported, Inconsistent }
}
