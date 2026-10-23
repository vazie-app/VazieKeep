package app.vazie.keep.core

import java.io.Closeable

interface KeepVaultSession : Closeable {
    val isClosed: Boolean

    fun info(): VaultInfo

    fun groups(): List<VaultGroup>

    fun entries(): List<EntrySummary>

    fun search(query: String): List<EntrySummary>

    fun entry(id: EntryId): EntryDetail

    fun reveal(id: EntryId, field: SecretField): SecretValue

    fun totpCode(id: EntryId, unixSeconds: Long, access: KeepAccess): TotpCode

    fun password(id: EntryId): SecretValue = reveal(id, SecretField.Password)

    // Only present when the session was opened from a typed password with retainUnlockMaterial.
    fun unlockMaterial(): UnlockMaterial?

    fun isEditable(): Boolean

    fun isDirty(): Boolean

    fun createEntry(group: GroupId, draft: EntryDraft): EntryId

    fun updateEntry(id: EntryId, draft: EntryDraft)

    fun setFavorite(id: EntryId, favorite: Boolean)

    fun attachment(id: EntryId, name: String): SecretValue

    // Returns the name it was stored under; adding needs Keep+, the core checks it.
    fun addAttachment(id: EntryId, name: String, data: ByteArray, access: KeepAccess): String

    fun removeAttachment(id: EntryId, name: String)

    // Takes every entry of the export into `group`; the export's folders become groups.
    fun importPlan(group: GroupId, plan: ImportPlan): ImportOutcome

    // Combines another version of this database into the open one (ADR 0023). `data` is the other file's
    // encrypted bytes, opened with its own credentials (wiped); it takes no session. Nothing is written:
    // the session is dirty if anything changed, and is saved like any edit. A failure changes nothing.
    fun merge(data: ByteArray, credentials: VaultCredentials, options: MergeOptions = MergeOptions()): MergeReport

    // `merge` for a file the session's own key opens (the same database saved by another device running Keep), so no
    // credentials are needed. `InvalidCredentials` means the key does not fit that file, as when another program rewrote
    // it or the password changed: the caller asks for the password and uses `merge`. A failure changes nothing.
    fun mergeWithSessionKey(data: ByteArray, options: MergeOptions = MergeOptions()): MergeReport

    // Earlier versions, newest first. Reading them and going back to one need Keep+, the core checks it.
    fun history(id: EntryId, access: KeepAccess): List<HistoryVersion>

    fun historyEntry(id: EntryId, index: Int, access: KeepAccess): EntryDetail

    fun revealHistory(id: EntryId, index: Int, field: SecretField, access: KeepAccess): SecretValue

    fun restoreHistory(id: EntryId, index: Int, access: KeepAccess)

    fun deleteEntry(id: EntryId, permanent: Boolean): DeleteOutcome

    fun moveEntry(id: EntryId, group: GroupId)

    fun createGroup(parent: GroupId, name: String): GroupId

    fun renameGroup(id: GroupId, name: String)

    // One of the standard KeePass icons (0..68); other apps show their own drawing of it.
    fun setGroupIcon(id: GroupId, icon: Int)

    fun setEntryIcon(id: EntryId, icon: Int)

    // Gives every entry that still has the default icon one by its address, offline; returns how many.
    fun assignIconsByUrl(): Int

    // The sites whose icons are worth fetching: entries with an address and no image of their own.
    fun siteIconHosts(): List<String>

    // Makes a passkey for a site and keeps it in a new entry of the group; the private key stays in the vault.
    fun createPasskey(group: GroupId, rpId: String, rpName: String, userName: String, userHandle: ByteArray): CreatedPasskey

    // The entries that hold a passkey, for one site or (null) for every site. Deleted entries are left out.
    fun passkeys(rpId: String?): List<EntrySummary>

    // Signs a sign-in request with the entry's passkey, for the site it was made for only.
    fun signPasskey(id: EntryId, rpId: String, clientDataHash: ByteArray): PasskeyAssertion

    // Takes the passkey out of an entry; the entry stays.
    fun removePasskey(id: EntryId)

    // The site to ask for one entry's icon, when it has an address and no image yet.
    fun siteIconHost(id: EntryId): String?

    // Keeps the fetched icons (PNG) in the file and shows them on the entries of their sites; returns how many entries got one.
    fun setSiteIcons(icons: List<SiteIcon>): Int

    // The bytes of an image kept in the file, by the id an entry names.
    fun customIcon(id: String): ByteArray?

    fun deleteGroup(id: GroupId, recursive: Boolean, permanent: Boolean): DeleteOutcome

    fun prepareSave(): SaveSnapshot

    // True when nothing changed since the snapshot with this generation was prepared.
    fun markSaved(generation: Long): Boolean
}

class VaultSessionException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { SessionNotFound, SessionClosed, EntryNotFound, FieldNotFound, InvalidArgument }
}

@JvmInline
value class EntryId(val value: String)

@JvmInline
value class GroupId(val value: String)

data class VaultInfo(
    val name: String,
    val formatMajor: Int,
    val formatMinor: Int,
    val rootGroupId: GroupId,
    val recycleBinId: GroupId? = null,
    val groupCount: Int,
    val entryCount: Int,
    val fingerprint: String = "",
) {
    override fun toString() = "VaultInfo(format=$formatMajor.$formatMinor, groups=$groupCount, entries=$entryCount)"
}

// `icon` is a standard KeePass icon number; 48 is the plain folder.
data class VaultGroup(val id: GroupId, val parentId: GroupId?, val name: String, val icon: Int = 48) {
    override fun toString() = "VaultGroup(id=${id.value})"
}

data class EntrySummary(
    val id: EntryId,
    val groupId: GroupId,
    val title: EntryText,
    val username: EntryText,
    val url: EntryText,
    val tags: List<String>,
    val hasPassword: Boolean,
    val hasNotes: Boolean,
    val hasProtectedFields: Boolean,
    val customFields: List<CustomFieldInfo>,
    val hasTotp: Boolean = false,
    val favorite: Boolean = false,
    val expiresAt: Long? = null,
    // A standard KeePass icon number; 0, the key, is what an entry has by default.
    val icon: Int = 0,
    // The id of an image kept in the file that stands in for the standard icon.
    val customIcon: String? = null,
    // The public side of the passkey the entry holds, if it holds one.
    val passkey: PasskeyInfo? = null,
) {
    fun isExpired(nowSeconds: Long): Boolean = expiresAt != null && expiresAt <= nowSeconds

    override fun toString() = "EntrySummary(id=${id.value})"
}

data class CustomFieldInfo(val name: String, val isProtected: Boolean) {
    override fun toString() = "CustomFieldInfo(protected=$isProtected)"
}

data class AttachmentInfo(val name: String, val size: Long) {
    override fun toString() = "AttachmentInfo(size=$size)"
}

data class EntryDetail(
    val summary: EntrySummary,
    val notes: EntryText,
    val customFields: List<CustomField>,
    val attachments: List<AttachmentInfo> = emptyList(),
    val modifiedAt: Long? = null,
    val historyCount: Int = 0,
) {
    override fun toString() = "EntryDetail(id=${summary.id.value})"
}

data class CustomField(val name: String, val value: EntryText) {
    override fun toString() = "CustomField(value=$value)"
}

sealed interface EntryText {
    data class Plain(val value: String) : EntryText {
        override fun toString() = "Plain(<redacted>)"
    }

    data object Hidden : EntryText
}

sealed interface SecretField {
    data object Password : SecretField

    data object Title : SecretField

    data object UserName : SecretField

    data object Url : SecretField

    data object Notes : SecretField

    data class Custom(val name: String) : SecretField {
        override fun toString() = "Custom(<name>)"
    }
}

class SiteIcon(val host: String, val png: ByteArray)

// Where a site's icon may be, best first, judged from the text of its front page.
fun siteIconLinks(host: String, html: String): List<String> = app.vazie.keep.core.ffi.siteIconLinks(host, html)

// What an entry's passkey is for. The identifiers are base64url, as the site knows them; nothing here signs.
data class PasskeyInfo(val rpId: String, val userName: String, val credentialId: String, val userHandle: String)

class CreatedPasskey(
    val entryId: EntryId,
    val credentialId: ByteArray,
    // SubjectPublicKeyInfo DER.
    val publicKey: ByteArray,
    val authenticatorData: ByteArray,
    val attestationObject: ByteArray,
)

class PasskeyAssertion(val credentialId: ByteArray, val userHandle: ByteArray, val authenticatorData: ByteArray, val signature: ByteArray)

class VaultPasskeyException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { SessionClosed, ReadOnly, InvalidRequest, NoPasskey, InvalidKey, Failed }
}
