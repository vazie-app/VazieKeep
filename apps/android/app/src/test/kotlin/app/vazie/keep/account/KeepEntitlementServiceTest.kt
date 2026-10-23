package app.vazie.keep.account

import app.vazie.keep.core.KeepAccessState
import app.vazie.keep.core.LeaseKey
import app.vazie.keep.core.PlusFeature
import app.vazie.keep.core.RustKeepCore
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The service over the real Rust core. Leases are signed here with the public test seed of
// tooling/test-vectors/generate_keep_lease.py, so every verdict comes from the real verifier.
class KeepEntitlementServiceTest {
    private val day = 86_400L
    private val seed = "5645525349454b454550544553544f4e4c59ffffffffffffffffffffffffffff".take(64).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val privateKey = KeyFactory.getInstance("Ed25519").generatePrivate(
        PKCS8EncodedKeySpec(byteArrayOf(0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20) + seed),
    )
    private val publicKey = LeaseKey("test-1", "fqGTImPbO7V1Wg9BLZGK6cVsnIpw5cNdSwvlILlZA-s")

    private var now = 1_800_000_000L
    private val store = MemoryStore()
    private val api = FakeApi()
    private val service = KeepEntitlementService(api, store, RustKeepCore, listOf(publicKey)) { now }

    private class MemoryStore : AccountStore {
        var account: StoredAccount? = null
        var cleared = 0
        override fun load() = account
        override fun save(account: StoredAccount) { this.account = account }
        override fun clear() { account = null; cleared++ }
    }

    private inner class FakeApi : VazieAccountApi {
        var accountId = "acct-1"
        var tier = "keep_plus"
        var lifetime = false
        var entitlementEnd: Long? = null
        var leaseDays = 30
        var failure: AccountApiException? = null
        var fetches = 0
        var signOuts = 0
        var deletions = 0
        var codeValid = true
        var issuedAtOverride: Long? = null

        override suspend fun startEmailSignIn(email: String) { failure?.let { throw it } }
        override suspend fun verifyEmailCode(email: String, code: String): ApiSession {
            if (!codeValid) throw AccountApiException.InvalidCode()
            return ApiSession(accountId, email, "session-$accountId")
        }
        override suspend fun devSignIn() = ApiSession(accountId, null, "session-$accountId")
        override suspend fun fetchLease(token: String): FetchedLease {
            fetches++
            failure?.let { throw it }
            return FetchedLease(lease())
        }
        override suspend fun signOut(token: String) { signOuts++ }
        override suspend fun deleteAccount(token: String) {
            failure?.let { throw it }
            deletions++
        }

        fun lease(subject: String = accountId, issued: Long = issuedAtOverride ?: now): String {
            val end = entitlementEnd
            val leaseEnd = issued + leaseDays * day
            val payload = """{"version":1,"issuer":"vazie","subject":"$subject","product":"keep","tier":"$tier","lifetime":$lifetime,""" +
                """"issued_at":$issued,"lease_expires_at":${if (end != null) minOf(leaseEnd, end) else leaseEnd},""" +
                """"entitlement_expires_at":${end ?: "null"},"features_version":1,"revision":2,"nonce":"n$issued"}"""
            return sign(payload)
        }
    }

    private fun sign(payload: String, kid: String = "test-1"): String {
        fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        val input = b64("""{"alg":"EdDSA","kid":"$kid","typ":"vazie-keep-lease+jws"}""".toByteArray()) + "." + b64(payload.toByteArray())
        val signature = Signature.getInstance("Ed25519").run { initSign(privateKey); update(input.toByteArray()); sign() }
        return input + "." + b64(signature)
    }

    private fun signIn() = runBlocking { service.signIn("a@b.c", "123456") }

    @Test
    fun `without an account the app is Free and nothing is asked of the network`() = runBlocking {
        service.start()
        service.refresh()
        assertEquals(KeepAccessState.Free, service.state.value.access.state)
        assertNull(service.state.value.account)
        assertEquals(0, api.fetches)
    }

    @Test
    fun `signing in fetches a lease and switches Keep+ on`() {
        api.entitlementEnd = now + 90 * day
        assertEquals(SignInResult.Success, signIn())
        val state = service.state.value
        assertEquals(KeepAccessState.PlusActive, state.access.state)
        assertTrue(state.access.plusFeatures)
        assertEquals("a@b.c", state.account?.email)
        assertNotNull(store.account?.leaseToken)
    }

    @Test
    fun `the cached lease works offline after a restart and ends at its expiry`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        api.failure = AccountApiException.Unreachable()
        now += 10 * day
        val restarted = KeepEntitlementService(api, store, RustKeepCore, listOf(publicKey)) { now }
        restarted.start()
        assertEquals(KeepAccessState.PlusOfflineGrace, restarted.state.value.access.state)
        restarted.refresh(force = true)
        assertEquals(EntitlementProblem.Offline, restarted.state.value.problem)
        assertTrue(restarted.state.value.access.plusFeatures, "an unreachable backend does not switch Keep+ off")
        now += 21 * day
        restarted.reevaluate()
        assertFalse(restarted.state.value.access.plusFeatures)
        assertEquals(KeepAccessState.PlusExpired, restarted.state.value.access.state)
    }

    @Test
    fun `setting the clock back does not extend a lease`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        now += 20 * day
        service.reevaluate()
        assertTrue(service.state.value.access.plusFeatures)
        now -= 25 * day
        service.reevaluate()
        assertFalse(service.state.value.access.plusFeatures)
        assertTrue(service.state.value.access.needsOnlineVerification)
        val feature = FeatureAccess(RustKeepCore, service.state)
        assertFalse(feature.canUse(PlusFeature.Totp))
    }

    @Test
    fun `a lease shorter than thirty days is not extended`() {
        api.entitlementEnd = now + 5 * day
        signIn()
        assertEquals(now + 5 * day, service.state.value.access.leaseValidUntil)
    }

    @Test
    fun `a lifetime shows as lifetime`() {
        api.lifetime = true
        signIn()
        assertEquals(KeepAccessState.PlusLifetime, service.state.value.access.state)
    }

    @Test
    fun `a signed-in account without Keep+ is Free and that is not a problem`() {
        api.tier = "free"
        signIn()
        assertEquals(KeepAccessState.Free, service.state.value.access.state)
        assertNull(service.state.value.problem)
        assertNotNull(service.state.value.account)
    }

    @Test
    fun `signing out removes the account and the lease at once`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        service.signOut()
        assertNull(store.account)
        assertEquals(1, store.cleared)
        assertEquals(KeepAccessState.Free, service.state.value.access.state)
        assertNull(service.state.value.account)
        assertEquals(1, api.signOuts)
    }

    @Test
    fun `signing out works even when the backend is unreachable`() = runBlocking {
        signIn()
        val offline = object : VazieAccountApi by api {
            override suspend fun signOut(token: String) = throw AccountApiException.Unreachable()
        }
        val s = KeepEntitlementService(offline, store, RustKeepCore, listOf(publicKey)) { now }
        s.start()
        s.signOut()
        assertNull(store.account)
        assertNull(s.state.value.account)
    }

    @Test
    fun `deleting the account asks the backend and then forgets everything here`() = runBlocking<Unit> {
        api.entitlementEnd = now + 90 * day
        signIn()
        assertEquals(DeleteResult.Deleted, service.deleteAccount())
        assertEquals(1, api.deletions)
        assertNull(store.account)
        assertNull(service.state.value.account)
        assertEquals(KeepAccessState.Free, service.state.value.access.state)
    }

    @Test
    fun `a failed deletion forgets nothing, so the person can try again`() = runBlocking<Unit> {
        api.entitlementEnd = now + 90 * day
        signIn()
        val failing = object : VazieAccountApi by api {
            override suspend fun deleteAccount(token: String) = throw AccountApiException.Unreachable()
        }
        val s = KeepEntitlementService(failing, store, RustKeepCore, listOf(publicKey)) { now }
        s.start()
        assertEquals(DeleteResult.Failed, s.deleteAccount())
        assertNotNull(store.account)
        assertNotNull(s.state.value.account)
    }

    @Test
    fun `a session the backend no longer knows has no account left to delete`() = runBlocking<Unit> {
        signIn()
        val gone = object : VazieAccountApi by api {
            override suspend fun deleteAccount(token: String) = throw AccountApiException.SessionRejected()
        }
        val s = KeepEntitlementService(gone, store, RustKeepCore, listOf(publicKey)) { now }
        s.start()
        assertEquals(DeleteResult.Deleted, s.deleteAccount())
        assertNull(store.account)
    }

    @Test
    fun `another account never inherits the first one's Keep+`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        assertTrue(service.state.value.access.plusFeatures)
        service.signOut()
        api.accountId = "acct-2"
        api.tier = "free"
        api.entitlementEnd = null
        signIn()
        assertEquals("acct-2", service.state.value.account?.accountId)
        assertFalse(service.state.value.access.plusFeatures)
    }

    @Test
    fun `signing in as another account replaces the cached lease before it is shown`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        api.accountId = "acct-2"
        api.tier = "free"
        api.entitlementEnd = null
        api.failure = AccountApiException.Unreachable()
        signIn()
        assertEquals("acct-2", service.state.value.account?.accountId)
        assertNull(service.state.value.lease, "the other account's lease is gone even if the new fetch failed")
        assertFalse(service.state.value.access.plusFeatures)
    }

    @Test
    fun `a lease for another account, a forged lease and a wrong key are all rejected`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        val good = store.account!!.leaseToken!!
        val forged = good.split('.').let { it[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(String(Base64.getUrlDecoder().decode(it[1])).replace("keep_plus", "keep_plux").toByteArray()) + "." + it[2] }
        store.account = store.account!!.let { StoredAccount(it.accountId, it.email, it.sessionToken, forged, it.lastObservedDeviceTime, it.lastRefreshAt) }
        service.start()
        assertFalse(service.state.value.access.plusFeatures)
        val other = api.lease(subject = "someone-else")
        store.account = store.account!!.let { StoredAccount(it.accountId, it.email, it.sessionToken, other, it.lastObservedDeviceTime, it.lastRefreshAt) }
        service.start()
        assertFalse(service.state.value.access.plusFeatures, "a validly signed lease for a different account is not this account's Keep+")
        val unknownKey = sign("""{"version":1,"issuer":"vazie","subject":"acct-1","product":"keep","tier":"keep_plus","lifetime":true,"issued_at":$now,"lease_expires_at":${now + day},"entitlement_expires_at":null,"features_version":1,"revision":1,"nonce":"x"}""", kid = "nobody")
        store.account = store.account!!.let { StoredAccount(it.accountId, it.email, it.sessionToken, unknownKey, it.lastObservedDeviceTime, it.lastRefreshAt) }
        service.start()
        assertFalse(service.state.value.access.plusFeatures)
    }

    @Test
    fun `an expired session keeps the lease until it ends and is reported`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        api.failure = AccountApiException.SessionRejected()
        service.refresh(force = true)
        assertEquals(EntitlementProblem.SessionExpired, service.state.value.problem)
        assertTrue(service.state.value.access.plusFeatures)
    }

    @Test
    fun `refresh happens at most every five minutes unless the lease is expiring or invalid`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        signIn()
        val after = api.fetches
        now += 120
        service.refresh()
        assertEquals(after, api.fetches)
        now += 600
        service.refresh()
        assertEquals(after + 1, api.fetches)
    }

    @Test
    fun `a device clock behind the backend turns Keep+ off with a clear reason`() = runBlocking {
        api.entitlementEnd = now + 90 * day
        api.issuedAtOverride = now + 2 * 3_600
        signIn()
        assertFalse(service.state.value.access.plusFeatures)
        assertEquals(EntitlementProblem.ClockOff, service.state.value.problem)
    }

    @Test
    fun `a wrong code and a rate limit are reported without touching the account`() = runBlocking {
        api.codeValid = false
        assertEquals(SignInResult.InvalidCode, signIn())
        assertNull(service.state.value.account)
        api.failure = AccountApiException.RateLimited()
        assertEquals(CodeRequestResult.RateLimited, service.requestCode("a@b.c"))
    }
}
