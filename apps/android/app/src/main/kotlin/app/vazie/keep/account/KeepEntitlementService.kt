package app.vazie.keep.account

import app.vazie.keep.core.KeepAccess
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.KeepLease
import app.vazie.keep.core.LeaseKey
import app.vazie.keep.core.LeaseTier
import app.vazie.keep.core.LeaseVerifyException
import app.vazie.keep.core.PlusFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AccountInfo(val accountId: String, val email: String?)

// Why Keep+ could not be checked just now. It is about the account, never about a vault.
enum class DeleteResult { Deleted, Failed }

enum class EntitlementProblem {
    // The backend could not be reached. A valid lease keeps working.
    Offline,

    // The session expired or was revoked: sign in again to keep Keep+ up to date.
    SessionExpired,

    // The device date and time disagree with the backend's.
    ClockOff,

    // The backend answered with something that is not a valid lease for this account.
    Failed,
}

data class KeepEntitlementState(
    val account: AccountInfo? = null,
    val lease: KeepLease? = null,
    val access: KeepAccess = KeepAccess.Free,
    val refreshing: Boolean = false,
    val problem: EntitlementProblem? = null,
)

sealed interface CodeRequestResult {
    data object Sent : CodeRequestResult

    data object InvalidEmail : CodeRequestResult

    data object RateLimited : CodeRequestResult

    data object Unavailable : CodeRequestResult
}

sealed interface SignInResult {
    data object Success : SignInResult

    data object InvalidCode : SignInResult

    data object RateLimited : SignInResult

    data object Unavailable : SignInResult
}

// Keep+ for the app: the account on this device, the latest signed lease, and what that allows now.
//
// Free needs none of it. Nothing here is awaited by opening a vault, nothing here is sent a vault
// identifier, and a failure here only ever changes what this screen says.
class KeepEntitlementService(
    private val api: VazieAccountApi,
    private val store: AccountStore,
    private val core: KeepCore,
    private val keys: List<LeaseKey>,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val mutable = MutableStateFlow(KeepEntitlementState())
    val state: StateFlow<KeepEntitlementState> = mutable.asStateFlow()
    private val lock = Mutex()
    private var stored: StoredAccount? = null

    fun sessionToken(): String? = stored?.sessionToken

    // Reads what is stored and judges it with the current clock. Does no network.
    fun start() {
        stored = store.load()
        publish()
    }

    // Call when the app comes to the foreground: the clock may have moved.
    fun reevaluate() {
        if (stored != null) publish()
    }

    suspend fun requestCode(email: String): CodeRequestResult = try {
        api.startEmailSignIn(email.trim())
        CodeRequestResult.Sent
    } catch (_: AccountApiException.InvalidEmail) {
        CodeRequestResult.InvalidEmail
    } catch (_: AccountApiException.RateLimited) {
        CodeRequestResult.RateLimited
    } catch (_: AccountApiException) {
        CodeRequestResult.Unavailable
    }

    suspend fun signIn(email: String, code: String): SignInResult = try {
        begin(api.verifyEmailCode(email.trim(), code.trim()))
        SignInResult.Success
    } catch (_: AccountApiException.InvalidCode) {
        SignInResult.InvalidCode
    } catch (_: AccountApiException.RateLimited) {
        SignInResult.RateLimited
    } catch (_: AccountApiException) {
        SignInResult.Unavailable
    }

    // For a development backend only; production answers NotEnabled.
    suspend fun devSignIn(): SignInResult = try {
        begin(api.devSignIn())
        SignInResult.Success
    } catch (_: AccountApiException) {
        SignInResult.Unavailable
    }

    private suspend fun begin(session: ApiSession) = lock.withLock {
        // Another account: the old lease is dropped, never carried over.
        val previous = stored
        val sameAccount = previous?.accountId == session.accountId
        val account = StoredAccount(
            accountId = session.accountId,
            email = session.email,
            sessionToken = session.token,
            leaseToken = previous?.leaseToken?.takeIf { sameAccount },
            lastObservedDeviceTime = previous?.lastObservedDeviceTime?.takeIf { sameAccount } ?: 0,
            lastRefreshAt = 0,
        )
        store.save(account)
        stored = account
        publish()
        fetch(account)
    }

    // Asks the backend for a lease when one is due (or `force`). Safe to call often.
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        val account = stored ?: return@withLock
        if (!force && !due(account)) return@withLock
        fetch(account)
    }

    private fun due(account: StoredAccount): Boolean {
        val now = nowSeconds()
        val access = mutable.value.access
        return account.leaseToken == null ||
            access.needsOnlineVerification ||
            now - account.lastRefreshAt >= REFRESH_AFTER_SECONDS ||
            (access.leaseValidUntil?.let { it - now < EXPIRING_SOON_SECONDS } ?: false)
    }

    private suspend fun fetch(account: StoredAccount) {
        mutable.update { it.copy(refreshing = true, problem = null) }
        try {
            val token = api.fetchLease(account.sessionToken).lease
            val lease = core.verifyLease(token, keys)
            if (lease.subject != account.accountId) throw LeaseVerifyException(LeaseVerifyException.Reason.Inconsistent)
            val now = nowSeconds()
            val updated = StoredAccount(
                accountId = account.accountId,
                email = account.email,
                sessionToken = account.sessionToken,
                leaseToken = token,
                // The backend's clock is the reference after a successful check.
                lastObservedDeviceTime = lease.issuedAt,
                lastRefreshAt = now,
            )
            store.save(updated)
            stored = updated
            publish()
            if (mutable.value.access.needsOnlineVerification && lease.tier == LeaseTier.KeepPlus) {
                mutable.update { it.copy(problem = EntitlementProblem.ClockOff) }
            }
        } catch (_: AccountApiException.SessionRejected) {
            mutable.update { it.copy(problem = EntitlementProblem.SessionExpired) }
        } catch (_: AccountApiException) {
            mutable.update { it.copy(problem = EntitlementProblem.Offline) }
        } catch (_: LeaseVerifyException) {
            mutable.update { it.copy(problem = EntitlementProblem.Failed) }
        } finally {
            mutable.update { it.copy(refreshing = false) }
        }
    }

    // Removes the account, its session and the cached lease from this device. Vaults are untouched.
    suspend fun signOut() = lock.withLock {
        val account = stored
        forgetLocally()
        // After the local state is gone, so a slow or failing network never keeps Keep+ switched on.
        if (account != null) runCatching { api.signOut(account.sessionToken) }
    }

    // Deletes the account on the backend and then forgets it here. Nothing is forgotten unless the backend did it,
    // so a failed deletion leaves the person signed in and able to try again. A session the backend no longer knows
    // has no account left to delete.
    suspend fun deleteAccount(): DeleteResult = lock.withLock {
        val account = stored ?: return@withLock DeleteResult.Deleted
        try {
            api.deleteAccount(account.sessionToken)
        } catch (_: AccountApiException.SessionRejected) {
        } catch (_: AccountApiException) {
            return@withLock DeleteResult.Failed
        }
        forgetLocally()
        DeleteResult.Deleted
    }

    private fun forgetLocally() {
        stored = null
        store.clear()
        mutable.value = KeepEntitlementState()
    }

    private fun publish() {
        val account = stored
        if (account == null) {
            mutable.value = KeepEntitlementState()
            return
        }
        val lease = account.leaseToken?.let { token ->
            try {
                core.verifyLease(token, keys).takeIf { it.subject == account.accountId }
            } catch (_: LeaseVerifyException) {
                null
            }
        }
        val access = core.evaluateAccess(lease, nowSeconds(), account.lastObservedDeviceTime)
        if (access.observedDeviceTime - account.lastObservedDeviceTime >= PERSIST_AFTER_SECONDS) {
            stored = StoredAccount(
                account.accountId, account.email, account.sessionToken, account.leaseToken, access.observedDeviceTime, account.lastRefreshAt,
            ).also(store::save)
        }
        mutable.update {
            it.copy(account = AccountInfo(account.accountId, account.email), lease = lease, access = access)
        }
    }

    companion object {
        const val REFRESH_AFTER_SECONDS = 5 * 60L
        const val EXPIRING_SOON_SECONDS = 24 * 60 * 60L
        private const val PERSIST_AFTER_SECONDS = 60L

        // "kid:publicKey,kid2:publicKey2", as written into BuildConfig.
        fun parseKeys(text: String): List<LeaseKey> = text.split(',').mapNotNull { entry ->
            val parts = entry.trim().split(':')
            if (parts.size == 2 && parts.all { it.isNotEmpty() }) LeaseKey(parts[0], parts[1]) else null
        }
    }
}

// The one place the app asks "may this person use that?". Free features are never asked about.
class FeatureAccess(private val core: KeepCore, private val state: StateFlow<KeepEntitlementState>) {
    fun canUse(feature: PlusFeature): Boolean = core.canUse(feature, state.value.access)
}
