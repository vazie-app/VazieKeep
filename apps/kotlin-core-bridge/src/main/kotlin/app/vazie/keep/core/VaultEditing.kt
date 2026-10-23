package app.vazie.keep.core

import java.io.Closeable

class CustomFieldDraft(val name: String, val value: ByteArray, val isProtected: Boolean) {
    override fun toString() = "CustomFieldDraft(<redacted>)"
}

// Secret values cross the boundary as UTF-8 bytes; closing wipes them.
class EntryDraft(
    val title: String,
    val username: String,
    val password: ByteArray,
    val url: String,
    val notes: String,
    val customFields: List<CustomFieldDraft>,
    val tags: List<String>,
    val favorite: Boolean = false,
    val expiresAt: Long? = null,
) : Closeable {
    override fun close() {
        password.fill(0)
        customFields.forEach { it.value.fill(0) }
    }

    override fun toString() = "EntryDraft(<redacted>)"
}

data class PasswordOptions(
    val length: Int = 20,
    val lowercase: Boolean = true,
    val uppercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val excludeAmbiguous: Boolean = false,
) {
    companion object {
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 128
    }
}

enum class DeleteOutcome { MovedToRecycleBin, Deleted }

// Encrypted KDBX bytes ready for the platform to write; the session stays dirty until markSaved.
class SaveSnapshot(val bytes: ByteArray, val generation: Long) {
    override fun toString() = "SaveSnapshot(bytes=${bytes.size}, generation=$generation)"
}

class VaultEditException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        SessionNotFound,
        SessionClosed,
        ReadOnly,
        EntryNotFound,
        GroupNotFound,
        GroupNotEmpty,
        RootGroup,
        InvalidFieldName,
        InvalidText,
        InvalidArgument,
        RandomUnavailable,
        AttachmentNotFound,
        AttachmentTooLarge,
        NotEntitled,
        VersionNotFound,
    }
}

const val MAX_ATTACHMENT_BYTES: Long = 64L * 1024 * 1024

class VaultSaveException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { SessionNotFound, SessionClosed, ReadOnly, RandomUnavailable, EncryptionFailed, VerificationFailed }
}

class PasswordGeneratorException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NoCharacterSets, LengthOutOfRange, RandomUnavailable }
}
