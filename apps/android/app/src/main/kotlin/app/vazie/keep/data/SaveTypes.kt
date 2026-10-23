package app.vazie.keep.data

import app.vazie.keep.core.VaultEditException
import java.security.MessageDigest

// The SHA-256 of the encrypted file, which is all that identifies a version of it.
class FileVersion(val sha256: String) {
    override fun equals(other: Any?) = other is FileVersion && other.sha256 == sha256

    override fun hashCode() = sha256.hashCode()

    override fun toString() = "FileVersion(${sha256.take(8)})"

    companion object {
        fun of(bytes: ByteArray) = FileVersion(sha256Hex(bytes))
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

enum class SaveFailure {
    // Someone else wrote the file since it was opened here. Nothing was written.
    ChangedExternally,
    Unavailable,
    NotWritable,
    InsufficientSpace,
    BackupFailed,
    VerificationFailed,
    EncryptionFailed,
    // The provider can't be overwritten safely. Nothing was written.
    ProviderUnsupported,
    // A write failed midway and putting the old bytes back failed too. The file may be damaged;
    // complete copies are kept in the app.
    RestoreNeeded,
    ReadOnly,
    Locked,
}

class WriteException(val failure: SaveFailure) : Exception(failure.name)

sealed interface SaveOutcome {
    data object Saved : SaveOutcome

    data class Failed(val failure: SaveFailure) : SaveOutcome
}

sealed interface EditOutcome<out T> {
    data class Saved<T>(val value: T) : EditOutcome<T>

    // The edit is in the open session, the file still holds the previous version.
    data class NotSaved<T>(val value: T, val failure: SaveFailure) : EditOutcome<T>

    data class Invalid(val reason: VaultEditException.Reason) : EditOutcome<Nothing>
}

sealed interface AttachmentFile {
    class Read(val name: String, val bytes: ByteArray) : AttachmentFile {
        override fun toString() = "AttachmentFile.Read(size=${bytes.size})"
    }

    data object TooLarge : AttachmentFile

    data object Unavailable : AttachmentFile
}

sealed interface CopyOutcome {
    data object Written : CopyOutcome

    data object Failed : CopyOutcome
}

// Why combining the file as it is now with the open vault did not happen. Nothing in the file changed.
enum class MergeProblem {
    // The vault asks for the master password and none is known here; the person types it.
    NeedsPassword,
    WrongPassword,
    // The file on the storage is another database, not a version of this one.
    DifferentVault,
    ReadOnly,
    FileUnavailable,
    TooLarge,
    KeyFileUnavailable,
    // The file no longer opens as a vault, or its format is not supported.
    Unreadable,
}

sealed interface MergeOutcome {
    // The two versions are combined in the session; [save] says whether the result reached the file.
    data class Merged(val report: app.vazie.keep.core.MergeReport, val save: SaveOutcome) : MergeOutcome

    data class Problem(val problem: MergeProblem) : MergeOutcome
}
