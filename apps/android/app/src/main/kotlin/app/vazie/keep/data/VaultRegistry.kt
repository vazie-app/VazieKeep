package app.vazie.keep.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// One vault the app knows. Nothing here is secret: a label, a document URI and public identifiers.
// Everything the app keeps per vault (biometric record, Autofill index, key-file reference) hangs
// off [id], which is random and local, so moving or renaming the file changes nothing.
data class VaultDescriptor(
    val id: String,
    val name: String,
    val fileName: String,
    val uri: String,
    // The root-group fingerprint, known after the first unlock. A check that the file is still this
    // vault, not an identity: copies of one vault share it.
    val fingerprint: String? = null,
    val lastUsedAt: Long = 0,
    val keyFileUri: String? = null,
    val keyFileName: String? = null,
) {
    fun toReference() = VaultReference(
        uri = uri,
        displayName = name,
        lastUsedAt = lastUsedAt,
        keyFileUri = keyFileUri,
        keyFileName = keyFileName,
        id = id,
        fileName = fileName,
    )

    override fun toString() = "VaultDescriptor(id=$id)"
}

data class RegistrySnapshot(val vaults: List<VaultDescriptor>, val activeId: String?) {
    val active: VaultDescriptor? get() = vaults.firstOrNull { it.id == activeId }
}

interface VaultRegistry {
    val snapshot: StateFlow<RegistrySnapshot>

    fun active(): VaultDescriptor?

    fun find(id: String): VaultDescriptor?

    fun findByUri(uri: String): VaultDescriptor?

    // Adds a vault, or returns the one already registered for the same document. Optionally makes it active.
    fun add(descriptor: VaultDescriptor, activate: Boolean): VaultDescriptor

    fun activate(id: String)

    fun update(id: String, transform: (VaultDescriptor) -> VaultDescriptor)

    fun remove(id: String): VaultDescriptor?
}

fun newVaultId(): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

// The registry lives in the app's no-backup directory: it names the person's vaults, so it is kept
// off device backups. Written to a temporary file and renamed into place.
class FileVaultRegistry(
    private val file: File,
    private val legacy: () -> VaultReference? = { null },
    private val onLegacyMigrated: (VaultDescriptor) -> Unit = {},
) : VaultRegistry {
    private val state: MutableStateFlow<RegistrySnapshot>

    init {
        var loaded = read()
        if (loaded == null) {
            val old = legacy()
            if (old != null) {
                val descriptor = VaultDescriptor(
                    id = newVaultId(),
                    name = old.displayName,
                    fileName = old.displayName,
                    uri = old.uri,
                    lastUsedAt = old.lastUsedAt,
                    keyFileUri = old.keyFileUri,
                    keyFileName = old.keyFileName,
                )
                loaded = RegistrySnapshot(listOf(descriptor), descriptor.id)
                write(loaded)
                onLegacyMigrated(descriptor)
            }
        }
        state = MutableStateFlow(loaded ?: RegistrySnapshot(emptyList(), null))
    }

    override val snapshot: StateFlow<RegistrySnapshot> = state.asStateFlow()

    override fun active(): VaultDescriptor? = state.value.active

    override fun find(id: String): VaultDescriptor? = state.value.vaults.firstOrNull { it.id == id }

    override fun findByUri(uri: String): VaultDescriptor? = state.value.vaults.firstOrNull { it.uri == uri }

    @Synchronized
    override fun add(descriptor: VaultDescriptor, activate: Boolean): VaultDescriptor {
        val current = state.value
        val existing = current.vaults.firstOrNull { it.uri == descriptor.uri }
        val kept = existing ?: descriptor
        val vaults = if (existing == null) current.vaults + descriptor else current.vaults
        commit(RegistrySnapshot(vaults, if (activate) kept.id else current.activeId))
        return kept
    }

    @Synchronized
    override fun activate(id: String) {
        val current = state.value
        if (current.vaults.none { it.id == id }) return
        commit(current.copy(activeId = id))
    }

    @Synchronized
    override fun update(id: String, transform: (VaultDescriptor) -> VaultDescriptor) {
        val current = state.value
        commit(current.copy(vaults = current.vaults.map { if (it.id == id) transform(it).copy(id = id) else it }))
    }

    @Synchronized
    override fun remove(id: String): VaultDescriptor? {
        val current = state.value
        val removed = current.vaults.firstOrNull { it.id == id } ?: return null
        val vaults = current.vaults.filterNot { it.id == id }
        val active = if (current.activeId == id) vaults.maxByOrNull { it.lastUsedAt }?.id else current.activeId
        commit(RegistrySnapshot(vaults, active))
        return removed
    }

    private fun commit(snapshot: RegistrySnapshot) {
        write(snapshot)
        state.value = snapshot
    }

    private fun read(): RegistrySnapshot? {
        if (!file.isFile) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readByte() != VERSION) return null
                val active = input.readUTF().ifEmpty { null }
                val vaults = List(input.readInt().also { require(it in 0..MAX_VAULTS) }) {
                    VaultDescriptor(
                        id = input.readUTF(),
                        name = input.readUTF(),
                        fileName = input.readUTF(),
                        uri = input.readUTF(),
                        fingerprint = input.readUTF().ifEmpty { null },
                        lastUsedAt = input.readLong(),
                        keyFileUri = input.readUTF().ifEmpty { null },
                        keyFileName = input.readUTF().ifEmpty { null },
                    )
                }
                RegistrySnapshot(vaults, active)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun write(snapshot: RegistrySnapshot) {
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(MAGIC)
                out.writeByte(VERSION.toInt())
                out.writeUTF(snapshot.activeId.orEmpty())
                out.writeInt(snapshot.vaults.size)
                snapshot.vaults.forEach {
                    out.writeUTF(it.id)
                    out.writeUTF(it.name)
                    out.writeUTF(it.fileName)
                    out.writeUTF(it.uri)
                    out.writeUTF(it.fingerprint.orEmpty())
                    out.writeLong(it.lastUsedAt)
                    out.writeUTF(it.keyFileUri.orEmpty())
                    out.writeUTF(it.keyFileName.orEmpty())
                }
            }
        }.toByteArray()
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IOException("could not store the vault list")
        }
    }

    private companion object {
        const val MAGIC = 0x564B5247
        const val VERSION: Byte = 1
        const val MAX_VAULTS = 64
    }
}

// The single-vault view the rest of the app was built on: it reads and writes the active vault.
class RegistryVaultReferenceStore(private val registry: VaultRegistry, private val now: () -> Long = System::currentTimeMillis) : VaultReferenceStore {
    override fun load(): VaultReference? = registry.active()?.toReference()

    override fun save(reference: VaultReference) {
        val active = registry.active()
        if (active == null || (reference.id.isNotEmpty() && reference.id != active.id)) {
            registry.add(
                VaultDescriptor(
                    id = reference.id.ifEmpty { newVaultId() },
                    name = reference.displayName,
                    fileName = reference.fileName,
                    uri = reference.uri,
                    lastUsedAt = reference.lastUsedAt.takeIf { it > 0 } ?: now(),
                    keyFileUri = reference.keyFileUri,
                    keyFileName = reference.keyFileName,
                ),
                activate = true,
            )
            return
        }
        registry.update(active.id) {
            it.copy(
                name = reference.displayName,
                fileName = reference.fileName,
                uri = reference.uri,
                lastUsedAt = reference.lastUsedAt,
                keyFileUri = reference.keyFileUri,
                keyFileName = reference.keyFileName,
            )
        }
    }

    override fun clear() {
        registry.active()?.let { registry.remove(it.id) }
    }
}

// A file kept per vault, in the app's no-backup directory: `<prefix>_<vault id>.<extension>`.
class VaultScopedFile(
    private val directory: File,
    private val prefix: String,
    private val extension: String,
    private val single: File? = null,
    private val activeId: () -> String?,
) {
    fun current(): File = forId(activeId() ?: NONE)

    fun forId(id: String): File = single ?: File(directory, "${prefix}_$id.$extension")

    companion object {
        private const val NONE = "none"

        fun fixed(file: File) = VaultScopedFile(file.parentFile ?: File("."), file.nameWithoutExtension, file.extension, file) { null }
    }
}
