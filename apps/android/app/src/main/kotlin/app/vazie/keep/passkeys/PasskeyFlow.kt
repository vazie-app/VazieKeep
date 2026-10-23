package app.vazie.keep.passkeys

import app.vazie.keep.autofill.AuthProblem
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.HostMatch
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultPasskeyException
import app.vazie.keep.core.VaultSaveException
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultCopyStore
import app.vazie.keep.data.VaultDocumentWriter
import app.vazie.keep.data.VaultFileAccess
import app.vazie.keep.data.VaultReference
import app.vazie.keep.data.WriteException
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// Who is asking. A browser on Android's list of privileged apps speaks for the page it shows; any other app
// speaks for itself, by its package and signing certificate.
sealed interface PasskeyCaller {
    val packageName: String

    data class Browser(override val packageName: String, val origin: String) : PasskeyCaller

    class App(override val packageName: String, val certSha256: ByteArray) : PasskeyCaller {
        val origin: String get() = "android:apk-key-hash:" + WebAuthnJson.encode(certSha256)
        val certHex: String get() = certSha256.joinToString("") { "%02x".format(it) }
    }
}

sealed interface PasskeyRequest {
    val caller: PasskeyCaller

    // Made by a browser from the page's own client data; null when this app has to make the client data.
    val clientDataHash: ByteArray?

    class Create(val options: CreationOptions, override val caller: PasskeyCaller, override val clientDataHash: ByteArray?) : PasskeyRequest

    class Get(val options: AssertionOptions, val entryId: String, override val caller: PasskeyCaller, override val clientDataHash: ByteArray?) : PasskeyRequest
}

enum class PasskeyProblem {
    BadRequest,
    // Android did not say who is asking, or a browser that is not on the list claimed a web page.
    CallerNotVerified,
    // The page or the app is not one the site's passkeys belong to.
    SiteMismatch,
    CouldNotCheckSite,
    AlreadyExists,
    UnsupportedAlgorithm,
    NoPasskey,
    NotWritable,
    VaultChangedElsewhere,
    CouldNotSave,
    Failed,
}

sealed interface PasskeyFailure {
    data class Passkey(val problem: PasskeyProblem) : PasskeyFailure

    data class Unlock(val problem: AuthProblem) : PasskeyFailure
}

class ClientData(val json: ByteArray, val hash: ByteArray)

// Decides which site a request is for, and refuses a caller that has no right to that site's passkeys.
class PasskeyGate(private val core: KeepCore, private val links: AssetLinkSource) {
    sealed interface Decision {
        class Allowed(val rpId: String, val clientData: ClientData) : Decision

        data class Refused(val problem: PasskeyProblem) : Decision
    }

    suspend fun check(request: PasskeyRequest): Decision {
        val (asked, challenge, type) = when (request) {
            is PasskeyRequest.Create -> Triple(request.options.rpId, request.options.challenge, WebAuthnJson.TYPE_CREATE)
            is PasskeyRequest.Get -> Triple(request.options.rpId, request.options.challenge, WebAuthnJson.TYPE_GET)
        }
        return when (val caller = request.caller) {
            is PasskeyCaller.Browser -> {
                val page = try {
                    URI(caller.origin)
                } catch (_: Exception) {
                    return Decision.Refused(PasskeyProblem.CallerNotVerified)
                }
                val host = page.host?.lowercase()
                if (page.scheme != "https" || host.isNullOrEmpty()) return Decision.Refused(PasskeyProblem.CallerNotVerified)
                val rpId = (asked ?: host).lowercase()
                // A page may use the passkeys of its own host or of a registrable parent of it, never of another site.
                if (core.matchHost(entryHost = rpId, requestHost = host) == HostMatch.None) return Decision.Refused(PasskeyProblem.SiteMismatch)
                val hash = request.clientDataHash
                if (hash != null && hash.size == 32) {
                    Decision.Allowed(rpId, ClientData(PLACEHOLDER, hash))
                } else {
                    Decision.Allowed(rpId, clientData(type, challenge, caller.origin, caller.packageName))
                }
            }
            is PasskeyCaller.App -> {
                val rpId = asked?.lowercase() ?: return Decision.Refused(PasskeyProblem.BadRequest)
                val statements = links.statements(rpId) ?: return Decision.Refused(PasskeyProblem.CouldNotCheckSite)
                if (!AssetLinks.allows(statements, caller.packageName, caller.certHex)) return Decision.Refused(PasskeyProblem.SiteMismatch)
                Decision.Allowed(rpId, clientData(type, challenge, caller.origin, caller.packageName))
            }
        }
    }

    private fun clientData(type: String, challenge: String, origin: String, packageName: String): ClientData {
        val json = WebAuthnJson.clientData(type, challenge, origin, packageName)
        return ClientData(json, MessageDigest.getInstance("SHA-256").digest(json))
    }

    private companion object {
        // A browser signs with its own client data and puts it into the answer itself.
        val PLACEHOLDER = "{}".toByteArray()
    }
}

sealed interface PasskeyOutcome {
    // The JSON the site gets.
    class Done(val json: String) : PasskeyOutcome

    data class Failed(val problem: PasskeyProblem) : PasskeyOutcome
}

// What happens once the vault is open: a new passkey is made and written through the app's safe save, or a
// stored one signs. The private key never leaves the vault session.
class PasskeyOperations(
    private val files: VaultFileAccess,
    private val writer: VaultDocumentWriter,
    private val copies: VaultCopyStore,
    private val afterSave: (OpenedVault) -> Unit,
    private val mayEdit: (VaultReference) -> Boolean = { true },
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun create(vault: OpenedVault, reference: VaultReference, rpId: String, options: CreationOptions, clientData: ClientData): PasskeyOutcome =
        withContext(NonCancellable + background) {
            val session = vault.session
            try {
                if (!options.acceptsEs256) return@withContext PasskeyOutcome.Failed(PasskeyProblem.UnsupportedAlgorithm)
                if (!session.isEditable() || !files.hasWriteAccess(reference.uri) || !mayEdit(reference)) {
                    return@withContext PasskeyOutcome.Failed(PasskeyProblem.NotWritable)
                }
                if (session.passkeys(rpId).any { it.passkey?.credentialId in options.excluded }) {
                    return@withContext PasskeyOutcome.Failed(PasskeyProblem.AlreadyExists)
                }
                val made = session.createPasskey(vault.info.rootGroupId, rpId, options.rpName, options.userName, options.userHandle)
                val snapshot = session.prepareSave()
                writer.replace(reference.uri, vault.version, snapshot.bytes) { copies.keepBackup(it, vault.info.fingerprint) }
                afterSave(vault)
                PasskeyOutcome.Done(WebAuthnJson.registrationResponse(made, clientData.json))
            } catch (error: WriteException) {
                PasskeyOutcome.Failed(
                    when (error.failure) {
                        SaveFailure.ChangedExternally -> PasskeyProblem.VaultChangedElsewhere
                        SaveFailure.NotWritable, SaveFailure.ProviderUnsupported, SaveFailure.ReadOnly -> PasskeyProblem.NotWritable
                        else -> PasskeyProblem.CouldNotSave
                    },
                )
            } catch (error: VaultPasskeyException) {
                PasskeyOutcome.Failed(if (error.reason == VaultPasskeyException.Reason.ReadOnly) PasskeyProblem.NotWritable else PasskeyProblem.BadRequest)
            } catch (_: VaultSaveException) {
                PasskeyOutcome.Failed(PasskeyProblem.CouldNotSave)
            } catch (_: VaultEditException) {
                PasskeyOutcome.Failed(PasskeyProblem.CouldNotSave)
            } catch (_: VaultSessionException) {
                PasskeyOutcome.Failed(PasskeyProblem.Failed)
            }
        }

    suspend fun sign(vault: OpenedVault, entryId: String, rpId: String, options: AssertionOptions, clientData: ClientData): PasskeyOutcome = withContext(background) {
        try {
            val held = vault.session.passkeys(rpId).firstOrNull { it.id.value == entryId }?.passkey
                ?: return@withContext PasskeyOutcome.Failed(PasskeyProblem.NoPasskey)
            if (options.allowed.isNotEmpty() && held.credentialId !in options.allowed) return@withContext PasskeyOutcome.Failed(PasskeyProblem.NoPasskey)
            val signed = vault.session.signPasskey(EntryId(entryId), rpId, clientData.hash)
            PasskeyOutcome.Done(WebAuthnJson.assertionResponse(signed, clientData.json))
        } catch (error: VaultPasskeyException) {
            PasskeyOutcome.Failed(if (error.reason == VaultPasskeyException.Reason.NoPasskey) PasskeyProblem.NoPasskey else PasskeyProblem.Failed)
        } catch (_: VaultSessionException) {
            PasskeyOutcome.Failed(PasskeyProblem.Failed)
        }
    }
}
