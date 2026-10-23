package app.vazie.keep.account

import app.vazie.keep.core.KeepAccessState
import app.vazie.keep.plus.CurrentPlus

/** What Settings shows of the Vazie Account, as Vazie VPN does: the account's row, then the Keep+ row under it. */
data class SettingsAccount(val signedIn: Boolean, val email: String?, val plus: CurrentPlus?)

fun settingsAccount(state: KeepEntitlementState): SettingsAccount = SettingsAccount(
    signedIn = state.account != null,
    email = state.account?.email,
    plus = currentPlus(state),
)

/** The Keep+ the account holds now, or `null`. */
fun currentPlus(state: KeepEntitlementState): CurrentPlus? = state.access.takeIf { it.plusFeatures }?.let { access ->
    CurrentPlus(lifetime = access.state == KeepAccessState.PlusLifetime, untilSeconds = state.lease?.entitlementExpiresAt)
}
