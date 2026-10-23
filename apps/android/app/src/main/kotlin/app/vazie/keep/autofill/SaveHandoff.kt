package app.vazie.keep.autofill

import app.vazie.keep.core.SecretValue
import java.security.SecureRandom

// What the person submitted in a form. The password is held as a SecretValue so it can be wiped.
class SubmittedCredential(
    val origin: RequestOrigin,
    val kind: FormKind,
    val username: String,
    val password: SecretValue,
) {
    fun wipe() = password.close()

    override fun toString() = "SubmittedCredential(<redacted>)"
}

// Carries a submitted credential from the service to the save screen inside this process only, so
// no password travels in an Intent or PendingIntent. The screen holds a random token. Entries expire,
// are taken exactly once, and are wiped if nobody takes them. If the process dies the credential is
// gone and the person is asked to try again.
class SaveHandoff(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val ttlMillis: Long = 10 * 60_000L,
    private val capacity: Int = 4,
) {
    private class Held(val credential: SubmittedCredential, val at: Long)

    private val held = LinkedHashMap<String, Held>()
    private val random = SecureRandom()

    @Synchronized
    fun put(credential: SubmittedCredential): String {
        expire()
        while (held.size >= capacity) {
            val oldest = held.keys.first()
            held.remove(oldest)?.credential?.wipe()
        }
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        held[token] = Held(credential, clock())
        return token
    }

    @Synchronized
    fun take(token: String): SubmittedCredential? {
        expire()
        return held.remove(token)?.credential
    }

    @Synchronized
    fun discard(token: String) {
        held.remove(token)?.credential?.wipe()
    }

    @Synchronized
    fun size(): Int {
        expire()
        return held.size
    }

    private fun expire() {
        val now = clock()
        val iterator = held.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value.at > ttlMillis) {
                entry.value.credential.wipe()
                iterator.remove()
            }
        }
    }
}
