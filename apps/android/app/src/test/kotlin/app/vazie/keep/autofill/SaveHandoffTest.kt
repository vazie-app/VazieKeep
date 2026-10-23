package app.vazie.keep.autofill

import app.vazie.keep.core.SecretValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveHandoffTest {
    private var now = 0L
    private val handoff = SaveHandoff(clock = { now }, ttlMillis = 1000, capacity = 2)

    private fun credential(user: String = "u") =
        SubmittedCredential(RequestOrigin.App("p"), FormKind.Login, user, SecretValue("secret".toByteArray()))

    @Test
    fun aCredentialIsTakenExactlyOnce() {
        val held = credential()
        val token = handoff.put(held)
        assertEquals(held, handoff.take(token))
        assertNull(handoff.take(token))
        assertFalse(held.password.isClosed)
    }

    @Test
    fun anUnknownTokenGivesNothing() {
        assertNull(handoff.take("nope"))
    }

    @Test
    fun tokensAreRandomAndLong() {
        val a = handoff.put(credential())
        val b = handoff.put(credential())
        assertNotEquals(a, b)
        assertEquals(32, a.length)
    }

    @Test
    fun anExpiredCredentialIsWipedAndGone() {
        val held = credential()
        val token = handoff.put(held)
        now = 1500
        assertNull(handoff.take(token))
        assertTrue(held.password.isClosed)
        assertEquals(0, handoff.size())
    }

    @Test
    fun theOldestIsEvictedAndWipedAtCapacity() {
        val first = credential("1")
        val tokenFirst = handoff.put(first)
        handoff.put(credential("2"))
        handoff.put(credential("3"))
        assertTrue(first.password.isClosed)
        assertNull(handoff.take(tokenFirst))
        assertEquals(2, handoff.size())
    }

    @Test
    fun discardingWipes() {
        val held = credential()
        val token = handoff.put(held)
        handoff.discard(token)
        assertTrue(held.password.isClosed)
        assertNull(handoff.take(token))
    }

    @Test
    fun aCredentialNeverPrintsItself() {
        assertEquals("SubmittedCredential(<redacted>)", credential("alice").toString())
    }
}
