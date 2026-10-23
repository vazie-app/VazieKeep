package app.vazie.keep.core

enum class ChangedField { Title, UserName, Password, Url, Notes, CustomFields, Tags, Favorite, Attachments, Expiry }

// An earlier version of an entry; `differs` is what it has different from the entry as it is now.
data class HistoryVersion(
    val index: Int,
    val modifiedAt: Long?,
    val title: EntryText,
    val username: EntryText,
    val differs: List<ChangedField>,
) {
    override fun toString() = "HistoryVersion(index=$index)"
}

class VaultHistoryException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { SessionNotFound, SessionClosed, EntryNotFound, FieldNotFound, VersionNotFound, NotEntitled, InvalidArgument }
}
