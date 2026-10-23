package app.vazie.keep.autofill

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.autofill.AutofillId
import androidx.core.content.IntentCompat

// What the authentication screen is asked to do. It travels in the PendingIntent that Android
// fires when the person taps a suggestion: ids, the origin that was decided at request time and
// the vault the index belonged to. Never a secret.
class AuthRequest(
    val mode: Mode,
    val entryId: String?,
    val fingerprint: String,
    val origin: RequestOrigin,
    val usernameId: AutofillId?,
    val passwordId: AutofillId?,
) {
    enum class Mode { Select, Search }

    fun toIntent(context: Context): Intent = Intent(context, AutofillAuthActivity::class.java).apply {
        putExtra(MODE, mode.name)
        putExtra(ENTRY, entryId)
        putExtra(FINGERPRINT, fingerprint)
        putExtra(USERNAME_ID, usernameId)
        putExtra(PASSWORD_ID, passwordId)
        putExtras(origin.toBundle())
    }

    override fun toString() = "AuthRequest($mode)"

    companion object {
        private const val MODE = "mode"
        private const val ENTRY = "entry"
        private const val FINGERPRINT = "fingerprint"
        private const val USERNAME_ID = "usernameId"
        private const val PASSWORD_ID = "passwordId"
        private const val ORIGIN = "origin"
        private const val SCHEME = "scheme"
        private const val HOST = "host"
        private const val PACKAGE = "package"
        private const val IGNORED = "ignored"

        fun fromIntent(intent: Intent): AuthRequest? {
            val mode = intent.getStringExtra(MODE)?.let { name -> Mode.entries.firstOrNull { it.name == name } } ?: return null
            val fingerprint = intent.getStringExtra(FINGERPRINT) ?: return null
            val origin = originOf(intent.extras ?: return null) ?: return null
            val usernameId = IntentCompat.getParcelableExtra(intent, USERNAME_ID, AutofillId::class.java)
            val passwordId = IntentCompat.getParcelableExtra(intent, PASSWORD_ID, AutofillId::class.java)
            if (usernameId == null && passwordId == null) return null
            val entry = intent.getStringExtra(ENTRY)
            if (mode == Mode.Select && entry == null) return null
            return AuthRequest(mode, entry, fingerprint, origin, usernameId, passwordId)
        }

        private fun RequestOrigin.toBundle() = Bundle().apply {
            when (this@toBundle) {
                is RequestOrigin.Web -> {
                    putString(ORIGIN, "web")
                    putString(SCHEME, origin.scheme)
                    putString(HOST, origin.host)
                }
                is RequestOrigin.App -> {
                    putString(ORIGIN, "app")
                    putString(PACKAGE, packageName)
                    putBoolean(IGNORED, ignoredWebClaim)
                }
            }
        }

        private fun originOf(extras: Bundle): RequestOrigin? = when (extras.getString(ORIGIN)) {
            "web" -> RequestOrigin.Web(WebOrigin(extras.getString(SCHEME) ?: return null, extras.getString(HOST) ?: return null, null))
            "app" -> RequestOrigin.App(extras.getString(PACKAGE) ?: return null, extras.getBoolean(IGNORED))
            else -> null
        }
    }
}
