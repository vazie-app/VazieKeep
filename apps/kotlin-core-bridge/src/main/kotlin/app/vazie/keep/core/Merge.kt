package app.vazie.keep.core

// How a second version of the database is combined into the open one.
data class MergeOptions(
    // Appended to the title of a conflict copy; the app passes one in the person's language.
    val conflictSuffix: String = " (conflict)",
    // Merge a database that is not a version of this one: every item is then new.
    val allowUnrelated: Boolean = false,
)

// What a merge did: counts and ids only, never a value from the vault.
data class MergeReport(
    // Whether the open database is different now, and so dirty.
    val changed: Boolean,
    val entriesAdded: Int,
    // Entries changed after a deletion here, which therefore came back.
    val entriesRestored: Int,
    // Entries whose current version is now the other side's.
    val entriesUpdated: Int,
    val entriesMoved: Int,
    val entriesDeleted: Int,
    // Entries whose current version stayed but whose history changed.
    val historiesMerged: Int,
    val historyVersionsAdded: Int,
    val groupsAdded: Int,
    val groupsUpdated: Int,
    val groupsMoved: Int,
    val groupsDeleted: Int,
    // The conflict copies made, one per entry changed on both sides.
    val conflicts: List<EntryId>,
    val iconsAdded: Int,
    val deletionRecordsAdded: Int,
    val settingsUpdated: Boolean,
)

class VaultMergeException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        SessionNotFound,
        SessionClosed,
        ReadOnly,

        // The two databases are not versions of one vault, and the options did not allow it.
        DifferentVault,

        // The merged result would not be a valid vault; nothing changed.
        InvalidResult,

        // Opening the other file, as for openVault.
        NotAVault,
        Unsupported,
        MissingCredentials,
        InvalidCredentials,
        InvalidKeyFile,
        Damaged,
        TooLarge,
        Unavailable,
    }
}
