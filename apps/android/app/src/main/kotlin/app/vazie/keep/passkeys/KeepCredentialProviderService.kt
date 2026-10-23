package app.vazie.keep.passkeys

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import androidx.credentials.provider.PublicKeyCredentialEntry
import app.vazie.keep.KeepApplication
import app.vazie.keep.R

// Android asks this service what Vazie Keep can offer for a passkey request. It answers from what the app
// may know with the vault locked (the list of vaults and the passkey index) and never touches a key: making
// or using a passkey happens in PasskeyActivity, after the vault is unlocked.
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class KeepCredentialProviderService : CredentialProviderService() {
    private val container get() = (application as KeepApplication).container

    private companion object {
        const val TAG = "KeepPasskeys"
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        if (request !is BeginCreatePublicKeyCredentialRequest) {
            Log.w(TAG, "create: not a passkey request (${request.type})")
            callback.onError(CreateCredentialUnknownException())
            return
        }
        // Without a chosen vault the entry is still offered: the screen it opens says what is missing.
        val vault = container.registry.active()
        if (vault == null) Log.w(TAG, "create: no vault is chosen")
        val entry = CreateEntry.Builder(vault?.name ?: getString(R.string.app_name), pending(PasskeyActivity.ACTION_CREATE, null))
            .setDescription(getString(R.string.passkey_provider_description))
            .build()
        callback.onResult(BeginCreateCredentialResponse.Builder().addCreateEntry(entry).build())
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val index = container.passkeyIndex.load()
        val entries = mutableListOf<CredentialEntry>()
        if (index != null) {
            request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>().forEach { option ->
                val options = WebAuthnJson.assertion(option.requestJson) ?: return@forEach
                val rpId = options.rpId?.lowercase() ?: return@forEach
                index.passkeys
                    .filter { it.rpId == rpId && (options.allowed.isEmpty() || it.credentialId in options.allowed) }
                    .forEach { passkey ->
                        entries += PublicKeyCredentialEntry.Builder(
                            this,
                            passkey.userName.ifEmpty { passkey.title.ifEmpty { passkey.rpId } },
                            pending(PasskeyActivity.ACTION_GET, passkey.entryId),
                            option,
                        ).setDisplayName(passkey.title.ifEmpty { null }).build()
                    }
            }
        }
        if (index == null) Log.w(TAG, "get: no passkey index yet (the vault has not been opened since passkeys were added)")
        callback.onResult(BeginGetCredentialResponse.Builder().setCredentialEntries(entries).build())
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    // Android adds the request to the intent when the person picks the entry, so the intent has to be mutable.
    private fun pending(action: String, entryId: String?): PendingIntent {
        val intent = Intent(this, PasskeyActivity::class.java).setAction(action).putExtra(PasskeyActivity.EXTRA_ENTRY, entryId)
        val code = (action + entryId.orEmpty()).hashCode()
        return PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
