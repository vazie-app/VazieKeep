package app.vazie.keep.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The shared Keep+ vectors, verified through the real Rust core: the same file the core's own tests,
// the backend's signer test and the iOS tests use.
class RustKeepLeaseTest {
    private val text = File(System.getProperty("keep.testVectors")).resolve("keep-lease/vectors.json").readText()

    private val keys = Regex("\"kid\": \"([^\"]+)\",\\s*\"public_key\": \"([^\"]+)\"").findAll(text).map { LeaseKey(it.groupValues[1], it.groupValues[2]) }.toList()

    private class Case(val name: String, val token: String, val valid: Boolean)

    private val cases = Regex("\"name\": \"([^\"]+)\",\\s*\"token\": \"([^\"]*)\",\\s*\"valid\": (true|false)")
        .findAll(text).map { Case(it.groupValues[1], it.groupValues[2], it.groupValues[3] == "true") }.toList()

    private fun token(name: String) = cases.single { it.name == name }.token

    @Test
    fun everyVectorIsAcceptedOrRejectedAsDeclared() {
        assertTrue(cases.size >= 15 && keys.size == 1)
        for (case in cases) {
            if (case.valid) {
                RustKeepCore.verifyLease(case.token, keys)
            } else {
                assertFailsWith<LeaseVerifyException>(case.name) { RustKeepCore.verifyLease(case.token, keys) }
            }
        }
    }

    @Test
    fun tamperingAndUntrustedKeysAreDistinguished() {
        assertEquals(LeaseVerifyException.Reason.BadSignature, assertFailsWith<LeaseVerifyException> { RustKeepCore.verifyLease(token("tier_tampered"), keys) }.reason)
        assertEquals(LeaseVerifyException.Reason.UnknownKey, assertFailsWith<LeaseVerifyException> { RustKeepCore.verifyLease(token("plus_90_days"), emptyList()) }.reason)
        assertEquals(LeaseVerifyException.Reason.UnsupportedAlgorithm, assertFailsWith<LeaseVerifyException> { RustKeepCore.verifyLease(token("alg_none"), keys) }.reason)
    }

    @Test
    fun aVerifiedLeaseBecomesAccessThroughTheCoreClockRules() {
        val lease = RustKeepCore.verifyLease(token("plus_90_days"), keys)
        assertEquals(LeaseTier.KeepPlus, lease.tier)
        val fresh = RustKeepCore.evaluateAccess(lease, lease.issuedAt + 60, lease.issuedAt)
        assertEquals(KeepAccessState.PlusActive, fresh.state)
        assertTrue(RustKeepCore.canUse(PlusFeature.MultiVault, fresh))

        val rolledBack = RustKeepCore.evaluateAccess(lease, lease.issuedAt - 10 * 86_400, lease.issuedAt + 86_400)
        assertFalse(rolledBack.plusFeatures)
        assertTrue(rolledBack.needsOnlineVerification)
        assertFalse(RustKeepCore.canUse(PlusFeature.Totp, rolledBack))

        val expired = RustKeepCore.evaluateAccess(lease, lease.leaseExpiresAt, lease.leaseExpiresAt - 1)
        assertEquals(KeepAccessState.PlusExpired, expired.state)
        assertEquals(KeepAccessState.Free, RustKeepCore.evaluateAccess(null, 1, 0).state)
    }
}
