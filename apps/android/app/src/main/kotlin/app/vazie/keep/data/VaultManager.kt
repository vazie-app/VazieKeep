package app.vazie.keep.data

import app.vazie.keep.core.CreatedVault
import app.vazie.keep.core.KdfSettings
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.VaultCreateException
import app.vazie.keep.core.VaultCredentials
import app.vazie.keep.core.VaultFormat
import java.security.MessageDigest

enum class CreateError { InvalidName, WeakSettings, NoCredentials, InvalidKeyFile, CouldNotWrite, VerificationFailed, Failed, NeedsPlus }

sealed interface CreateResult {
    data class Created(val reference: VaultReference) : CreateResult

    data class Failure(val error: CreateError) : CreateResult
}

// Everything a new vault is made from. The arrays are wiped by the manager once they are used.
class CreateRequest(
    val name: String,
    val kdf: KdfSettings,
    val password: ByteArray?,
    // Key file content, either picked by the person or just generated.
    val keyFile: ByteArray?,
    val keyFileUri: String?,
    val keyFileName: String?,
)

enum class RelocateError { NotAVault, FileUnavailable }

// Vault-level operations on the list of known vaults. Each vault is its own security boundary: what
// is kept for it (biometric record and key, Autofill index, recovery copies, key-file grant) is
// created with it and removed with it.
class VaultManager(
    private val core: KeepCore,
    private val files: VaultFileAccess,
    private val registry: VaultRegistry,
    private val repository: VaultRepository,
    // Removes what the app keeps for a vault that is being forgotten.
    private val forgetData: suspend (VaultDescriptor) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val mayAddVault: () -> Boolean = { true },
    // Removes what is kept under the id of a list entry that turned out to be a vault already in the list.
    // The recovery copies stay: they belong to the database, which is still here.
    private val forgetTwin: suspend (VaultDescriptor) -> Unit = {},
    private val onMerged: (removed: String, into: String) -> Unit = { _, _ -> },
) {
    // Builds a new KDBX in memory, writes it once to the document the person chose, reads it back
    // byte for byte, and only then registers it. A failed write leaves nothing behind.
    suspend fun create(request: CreateRequest, destinationUri: String): CreateResult {
        if (!mayAddVault()) {
            request.password?.fill(0)
            request.keyFile?.fill(0)
            files.deleteQuietly(destinationUri)
            return CreateResult.Failure(CreateError.NeedsPlus)
        }
        val credentials = VaultCredentials(password = request.password?.copyOf(), keyFile = request.keyFile?.copyOf())
        val created: CreatedVault = try {
            core.createVault(request.name, request.kdf, credentials)
        } catch (error: VaultCreateException) {
            files.deleteQuietly(destinationUri)
            return CreateResult.Failure(error.reason.toError())
        } finally {
            request.password?.fill(0)
            request.keyFile?.fill(0)
        }
        try {
            files.writeNew(destinationUri, created.bytes)
            val readBack = files.read(destinationUri)
            if (!MessageDigest.isEqual(sha256(readBack), sha256(created.bytes))) {
                files.deleteQuietly(destinationUri)
                return CreateResult.Failure(CreateError.VerificationFailed)
            }
        } catch (_: VaultFileException) {
            files.deleteQuietly(destinationUri)
            return CreateResult.Failure(CreateError.CouldNotWrite)
        }
        files.persistPermission(destinationUri, write = true)
        request.keyFileUri?.let { files.persistPermission(it) }
        repository.lock()
        val fileName = files.displayName(destinationUri) ?: (request.name + ".kdbx")
        val descriptor = registry.add(
            VaultDescriptor(
                id = newVaultId(),
                name = request.name.trim(),
                fileName = fileName,
                uri = destinationUri,
                fingerprint = created.fingerprint,
                lastUsedAt = now(),
                keyFileUri = request.keyFileUri,
                keyFileName = request.keyFileName,
            ),
            activate = true,
        )
        return CreateResult.Created(descriptor.toReference())
    }

    // Forget removes the vault from this app only. The KDBX file is never touched.
    suspend fun forget(id: String) {
        val descriptor = registry.find(id) ?: return
        if (registry.active()?.id == id) repository.lock()
        forgetData(descriptor)
        files.releasePermission(descriptor.uri)
        descriptor.keyFileUri?.let(files::releasePermission)
        registry.remove(id)
    }

    // One file, one entry. Two providers can hand out different addresses for the same file, so after the
    // chosen vault opens, an entry with its file name and exactly its bytes is that file again: the entry
    // goes, the file is not touched. Returns the ids that left the list.
    suspend fun mergeDuplicates(): List<String> {
        val active = registry.active() ?: return emptyList()
        val twins = registry.snapshot.value.vaults.filter { it.id != active.id && it.fileName == active.fileName }
        if (twins.isEmpty()) return emptyList()
        val own = digest(active.uri) ?: return emptyList()
        val same = twins.filter { twin -> digest(twin.uri)?.let { MessageDigest.isEqual(it, own) } == true }
        same.forEach { twin ->
            forgetTwin(twin)
            files.releasePermission(twin.uri)
            if (active.keyFileUri == null && twin.keyFileUri != null) {
                registry.update(active.id) { it.copy(keyFileUri = twin.keyFileUri, keyFileName = twin.keyFileName) }
            } else if (twin.keyFileUri != null && twin.keyFileUri != active.keyFileUri) {
                files.releasePermission(twin.keyFileUri)
            }
            registry.remove(twin.id)
            onMerged(twin.id, active.id)
        }
        return same.map { it.id }
    }

    private suspend fun digest(uri: String): ByteArray? = try {
        sha256(files.read(uri))
    } catch (_: VaultFileException) {
        null
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NAME) return
        registry.update(id) { it.copy(name = trimmed) }
    }

    // The file was moved or deleted: point the same vault at a new document.
    suspend fun relocate(id: String, uri: String): RelocateError? {
        val header = try {
            files.readHeader(uri)
        } catch (_: VaultFileException) {
            return RelocateError.FileUnavailable
        }
        if (core.detectVaultFormat(header) !is VaultFormat.Kdbx) return RelocateError.NotAVault
        val descriptor = registry.find(id) ?: return RelocateError.FileUnavailable
        if (registry.findByUri(uri)?.id.let { it != null && it != id }) return RelocateError.FileUnavailable
        files.persistPermission(uri, write = true)
        if (descriptor.uri != uri) files.releasePermission(descriptor.uri)
        val fileName = files.displayName(uri) ?: descriptor.fileName
        if (registry.active()?.id == id) repository.lock()
        registry.update(id) { it.copy(uri = uri, fileName = fileName) }
        return null
    }

    // A key file the person picked for a new vault: its bytes, and the grant that lets the vault use it later.
    suspend fun readKeyFile(uri: String): ByteArray? = try {
        files.readKeyFile(uri).also { files.persistPermission(uri) }
    } catch (_: VaultFileException) {
        null
    }

    // Writes a generated key file to the document the person chose. It lives only there, never in the vault.
    suspend fun saveKeyFile(uri: String, bytes: ByteArray): Boolean = try {
        files.writeNew(uri, bytes)
        val ok = MessageDigest.isEqual(sha256(files.readKeyFile(uri)), sha256(bytes))
        if (ok) files.persistPermission(uri) else files.deleteQuietly(uri)
        ok
    } catch (_: VaultFileException) {
        files.deleteQuietly(uri)
        false
    }

    suspend fun isAvailable(descriptor: VaultDescriptor): Boolean = try {
        files.readHeader(descriptor.uri).isNotEmpty()
    } catch (_: VaultFileException) {
        false
    }

    private fun VaultCreateException.Reason.toError() = when (this) {
        VaultCreateException.Reason.EmptyName, VaultCreateException.Reason.InvalidName -> CreateError.InvalidName
        VaultCreateException.Reason.InvalidSettings -> CreateError.WeakSettings
        VaultCreateException.Reason.MissingCredentials -> CreateError.NoCredentials
        VaultCreateException.Reason.InvalidKeyFile -> CreateError.InvalidKeyFile
        VaultCreateException.Reason.Unavailable -> CreateError.Failed
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val MAX_NAME = 128
    }
}

// "Семейный сейф 🔐" -> "Семейный сейф 🔐.kdbx"; characters providers reject become "_".
fun suggestedFileName(name: String): String {
    val cleaned = name.trim()
        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("")
        .trim('.', ' ')
        .take(80)
        .ifEmpty { "Vault" }
    return "$cleaned.kdbx"
}
