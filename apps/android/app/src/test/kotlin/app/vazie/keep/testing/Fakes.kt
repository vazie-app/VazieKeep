package app.vazie.keep.testing

import app.vazie.keep.core.CoreInfo
import app.vazie.keep.core.CustomField
import app.vazie.keep.core.CustomFieldInfo
import app.vazie.keep.core.DeleteOutcome
import app.vazie.keep.core.EntryDetail
import app.vazie.keep.core.EntryDraft
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.GroupId
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.KeepVaultSession
import app.vazie.keep.core.PasswordOptions
import app.vazie.keep.core.SaveSnapshot
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.SecretValue
import app.vazie.keep.core.UnlockMaterial
import app.vazie.keep.core.VaultCredentials
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultFormat
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.core.VaultInfo
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.core.VaultSaveException
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.data.FileVersion
import app.vazie.keep.data.JournalState
import app.vazie.keep.data.ProviderCapabilities
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.SecretClipboard
import app.vazie.keep.data.VaultCopyStore
import app.vazie.keep.data.VaultDocumentWriter
import app.vazie.keep.data.VaultFileAccess
import app.vazie.keep.data.VaultFileException
import app.vazie.keep.data.VaultReference
import app.vazie.keep.data.VaultReferenceStore
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.WriteException
import kotlinx.coroutines.CompletableDeferred

const val VAULT_URI = "content://test/vault.kdbx"
const val KEY_FILE_URI = "content://test/my.keyx"
const val FINGERPRINT = "f1ngerpr1nt"
val KDBX_HEADER = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(), 0x00, 0x00, 0x04, 0x00)

val ROOT = GroupId("root")
val WEB = GroupId("web")
val MAIL = EntryId("mail")
val ROOT_ENTRY = EntryId("root-entry")
const val MAIL_PASSWORD = "hunter2-synthetic"
const val MAIL_RECOVERY_CODE = "RC-0000-synthetic"

fun summary(id: EntryId, group: GroupId, title: String, username: String = "", url: String = "") = EntrySummary(
    id = id,
    groupId = group,
    title = EntryText.Plain(title),
    username = EntryText.Plain(username),
    url = EntryText.Plain(url),
    tags = emptyList(),
    hasPassword = true,
    hasNotes = false,
    hasProtectedFields = id == MAIL,
    customFields = if (id == MAIL) listOf(CustomFieldInfo("Recovery Code", true)) else emptyList(),
)

data class RecordedDraft(
    val title: String,
    val username: String,
    val password: String,
    val url: String,
    val notes: String,
    val tags: List<String>,
    val custom: List<Triple<String, String, Boolean>>,
    val favorite: Boolean = false,
    val expiresAt: Long? = null,
)

class FakeImportPlan(val entries: Int) : app.vazie.keep.core.ImportPlan {
    var closed = false

    override fun summary() = app.vazie.keep.core.ImportSummary(app.vazie.keep.core.ImportSource.Csv, entries, 1, 0, 0)

    override fun close() {
        closed = true
    }
}

class FakeVersion(
    val index: Int,
    val modifiedAt: Long?,
    val password: String,
    val differs: List<app.vazie.keep.core.ChangedField> = listOf(app.vazie.keep.core.ChangedField.Password),
)

class FakeVaultSession(
    private val secrets: Map<Pair<EntryId, SecretField>, String> = mapOf(
        (MAIL to SecretField.Password) to MAIL_PASSWORD,
        (MAIL to SecretField.Custom("Recovery Code")) to MAIL_RECOVERY_CODE,
    ),
    private val fingerprint: String = FINGERPRINT,
    private val material: String? = null,
    editable: Boolean = true,
) : KeepVaultSession {
    val groups = mutableListOf(VaultGroup(ROOT, null, "Root"), VaultGroup(WEB, ROOT, "Web"))
    val entries = mutableListOf(
        summary(ROOT_ENTRY, ROOT, "Root Entry", "root-user", "https://example.com"),
        summary(MAIL, WEB, "Example Mail", "alice@example.org", "https://mail.example.org"),
    )
    var editable = editable
    var recycleBin: GroupId? = null
    var editFailure: VaultEditException.Reason? = null
    var prepareFailure: VaultSaveException.Reason? = null
    var generation = 0L
        private set
    private var savedGeneration = 0L
    val created = mutableListOf<Pair<GroupId, String>>()
    val updated = mutableListOf<Pair<EntryId, String>>()
    val operations = mutableListOf<String>()
    val drafts = mutableListOf<RecordedDraft>()
    var prepareCalls = 0
    val searches = mutableListOf<String>()
    val revealed = mutableListOf<SecretValue>()
    val totpCodes = mutableMapOf<EntryId, String>()
    val versions = mutableMapOf<EntryId, MutableList<FakeVersion>>()
    override var isClosed = false
        private set

    override fun info() = check().let { VaultInfo("Fake Vault", 4, 0, ROOT, recycleBin, groups.size, entries.size, fingerprint) }

    override fun unlockMaterial(): UnlockMaterial? = check().let { material?.let { UnlockMaterial(it.toByteArray()) } }

    override fun groups() = check().let { groups }

    override fun entries() = check().let { entries }

    override fun search(query: String): List<EntrySummary> {
        check()
        searches += query
        return entries.filter { (it.title as? EntryText.Plain)?.value?.contains(query, ignoreCase = true) == true }
    }

    override fun entry(id: EntryId): EntryDetail {
        check()
        val summary = entries.firstOrNull { it.id == id } ?: throw VaultSessionException(VaultSessionException.Reason.EntryNotFound)
        val files = attachments[id].orEmpty().map { (name, bytes) -> app.vazie.keep.core.AttachmentInfo(name, bytes.size.toLong()) }
        return EntryDetail(summary, EntryText.Plain(""), listOf(CustomField("Recovery Code", EntryText.Hidden)), files, historyCount = versions[id].orEmpty().size)
    }

    override fun reveal(id: EntryId, field: SecretField): SecretValue {
        check()
        val text = secrets[id to field] ?: throw VaultSessionException(VaultSessionException.Reason.FieldNotFound)
        return SecretValue(text.toByteArray()).also { revealed += it }
    }

    private fun version(id: EntryId, index: Int, access: app.vazie.keep.core.KeepAccess): FakeVersion {
        check()
        if (!access.plusFeatures) throw app.vazie.keep.core.VaultHistoryException(app.vazie.keep.core.VaultHistoryException.Reason.NotEntitled)
        return versions[id].orEmpty().firstOrNull { it.index == index }
            ?: throw app.vazie.keep.core.VaultHistoryException(app.vazie.keep.core.VaultHistoryException.Reason.VersionNotFound)
    }

    override fun history(id: EntryId, access: app.vazie.keep.core.KeepAccess): List<app.vazie.keep.core.HistoryVersion> {
        check()
        if (!access.plusFeatures) throw app.vazie.keep.core.VaultHistoryException(app.vazie.keep.core.VaultHistoryException.Reason.NotEntitled)
        val summary = entries.firstOrNull { it.id == id }
            ?: throw app.vazie.keep.core.VaultHistoryException(app.vazie.keep.core.VaultHistoryException.Reason.EntryNotFound)
        return versions[id].orEmpty().sortedByDescending { it.index }.map {
            app.vazie.keep.core.HistoryVersion(it.index, it.modifiedAt, summary.title, summary.username, it.differs)
        }
    }

    override fun historyEntry(id: EntryId, index: Int, access: app.vazie.keep.core.KeepAccess): EntryDetail {
        version(id, index, access)
        return EntryDetail(entries.first { it.id == id }, EntryText.Plain(""), emptyList())
    }

    override fun revealHistory(id: EntryId, index: Int, field: SecretField, access: app.vazie.keep.core.KeepAccess): SecretValue {
        val version = version(id, index, access)
        if (field != SecretField.Password) throw app.vazie.keep.core.VaultHistoryException(app.vazie.keep.core.VaultHistoryException.Reason.FieldNotFound)
        return SecretValue(version.password.toByteArray()).also { revealed += it }
    }

    override fun restoreHistory(id: EntryId, index: Int, access: app.vazie.keep.core.KeepAccess) {
        edit()
        if (!access.plusFeatures) throw VaultEditException(VaultEditException.Reason.NotEntitled)
        if (versions[id].orEmpty().none { it.index == index }) throw VaultEditException(VaultEditException.Reason.VersionNotFound)
        operations += "restore:${id.value}:$index"
        generation++
    }

    override fun totpCode(id: EntryId, unixSeconds: Long, access: app.vazie.keep.core.KeepAccess): app.vazie.keep.core.TotpCode {
        check()
        if (!access.plusFeatures) throw app.vazie.keep.core.TotpException(app.vazie.keep.core.TotpException.Reason.NotEntitled)
        val code = totpCodes[id] ?: throw app.vazie.keep.core.TotpException(app.vazie.keep.core.TotpException.Reason.NoCode)
        return app.vazie.keep.core.TotpCode(SecretValue(code.toByteArray()), 30, (30 - unixSeconds % 30).toInt())
    }

    override fun isEditable() = check().let { editable }

    override fun isDirty() = check().let { generation != savedGeneration }

    override fun createEntry(group: GroupId, draft: EntryDraft): EntryId {
        edit()
        record(draft)
        val id = EntryId("new-${entries.size}")
        entries += summary(id, group, draft.title, draft.username, draft.url)
        created += group to draft.title
        generation++
        return id
    }

    override fun updateEntry(id: EntryId, draft: EntryDraft) {
        edit()
        record(draft)
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) throw VaultEditException(VaultEditException.Reason.EntryNotFound)
        entries[index] = entries[index].copy(title = EntryText.Plain(draft.title), username = EntryText.Plain(draft.username))
        updated += id to draft.title
        generation++
    }

    val attachments = mutableMapOf<EntryId, MutableMap<String, ByteArray>>()
    val attachmentAccess = mutableListOf<app.vazie.keep.core.KeepAccess>()

    override fun attachment(id: EntryId, name: String): SecretValue {
        check()
        val bytes = attachments[id]?.get(name) ?: throw VaultSessionException(VaultSessionException.Reason.FieldNotFound)
        return SecretValue(bytes.copyOf())
    }

    override fun addAttachment(id: EntryId, name: String, data: ByteArray, access: app.vazie.keep.core.KeepAccess): String {
        edit()
        attachmentAccess += access
        if (!access.plusFeatures) throw VaultEditException(VaultEditException.Reason.NotEntitled)
        attachments.getOrPut(id) { linkedMapOf() }[name] = data.copyOf()
        operations += "add-attachment:${id.value}:$name"
        generation++
        return name
    }

    override fun removeAttachment(id: EntryId, name: String) {
        edit()
        attachments[id]?.remove(name) ?: throw VaultEditException(VaultEditException.Reason.AttachmentNotFound)
        operations += "remove-attachment:${id.value}:$name"
        generation++
    }

    // Merging is covered against the real core; the screens do not use it yet.
    override fun merge(
        data: ByteArray,
        credentials: VaultCredentials,
        options: app.vazie.keep.core.MergeOptions,
    ): app.vazie.keep.core.MergeReport {
        credentials.close()
        throw app.vazie.keep.core.VaultMergeException(app.vazie.keep.core.VaultMergeException.Reason.Unavailable)
    }

    override fun mergeWithSessionKey(data: ByteArray, options: app.vazie.keep.core.MergeOptions): app.vazie.keep.core.MergeReport =
        throw app.vazie.keep.core.VaultMergeException(app.vazie.keep.core.VaultMergeException.Reason.Unavailable)

    override fun importPlan(group: GroupId, plan: app.vazie.keep.core.ImportPlan): app.vazie.keep.core.ImportOutcome {
        edit()
        val count = plan.summary().entries
        operations += "import:${group.value}:$count"
        generation++
        return app.vazie.keep.core.ImportOutcome(count, 1)
    }

    override fun setFavorite(id: EntryId, favorite: Boolean) {
        edit()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) throw VaultEditException(VaultEditException.Reason.EntryNotFound)
        if (entries[index].favorite == favorite) return
        entries[index] = entries[index].copy(favorite = favorite)
        generation++
    }

    override fun deleteEntry(id: EntryId, permanent: Boolean): DeleteOutcome {
        edit()
        operations += "delete-entry:${id.value}:$permanent"
        entries.removeAll { it.id == id }
        generation++
        return if (recycleBin != null && !permanent) DeleteOutcome.MovedToRecycleBin else DeleteOutcome.Deleted
    }

    override fun moveEntry(id: EntryId, group: GroupId) {
        edit()
        operations += "move:${id.value}:${group.value}"
        val index = entries.indexOfFirst { it.id == id }
        entries[index] = entries[index].copy(groupId = group)
        generation++
    }

    override fun createGroup(parent: GroupId, name: String): GroupId {
        edit()
        val id = GroupId("g-${groups.size}")
        groups += VaultGroup(id, parent, name)
        operations += "create-group:$name"
        generation++
        return id
    }

    override fun setGroupIcon(id: GroupId, icon: Int) {
        edit()
        val index = groups.indexOfFirst { it.id == id }
        if (index < 0) throw VaultEditException(VaultEditException.Reason.GroupNotFound)
        if (groups[index].icon == icon) return
        groups[index] = groups[index].copy(icon = icon)
        operations += "group-icon:${id.value}:$icon"
        generation++
    }

    var iconHosts: List<String> = emptyList()
    val images = mutableMapOf<String, ByteArray>()

    override fun siteIconHosts(): List<String> {
        check()
        return iconHosts
    }

    override fun setSiteIcons(icons: List<app.vazie.keep.core.SiteIcon>): Int {
        edit()
        val known = icons.filter { it.host in iconHosts }
        if (known.isNotEmpty()) {
            operations += "site-icons:" + known.joinToString(",") { it.host }
            known.forEach { images[it.host] = it.png }
            iconHosts = iconHosts - known.map { it.host }.toSet()
            generation++
        }
        return known.size
    }

    override fun customIcon(id: String): ByteArray? = images[id]

    val madePasskeys = mutableListOf<String>()

    override fun createPasskey(group: GroupId, rpId: String, rpName: String, userName: String, userHandle: ByteArray): app.vazie.keep.core.CreatedPasskey {
        edit()
        val id = EntryId("passkey-${madePasskeys.size}")
        madePasskeys += "$rpId:$userName"
        entries += summary(id, group, rpName.ifEmpty { rpId }, userName, "https://$rpId").copy(passkey = app.vazie.keep.core.PasskeyInfo(rpId, userName, "Y3JlZA", "aGFuZGxl"))
        operations += "passkey:$rpId:$userName"
        generation++
        return app.vazie.keep.core.CreatedPasskey(id, "cred".toByteArray(), byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))
    }

    override fun passkeys(rpId: String?): List<EntrySummary> {
        check()
        return entries.filter { it.passkey != null && (rpId == null || it.passkey?.rpId == rpId) }
    }

    override fun signPasskey(id: EntryId, rpId: String, clientDataHash: ByteArray): app.vazie.keep.core.PasskeyAssertion {
        check()
        val entry = entries.firstOrNull { it.id == id && it.passkey?.rpId == rpId } ?: throw app.vazie.keep.core.VaultPasskeyException(app.vazie.keep.core.VaultPasskeyException.Reason.NoPasskey)
        return app.vazie.keep.core.PasskeyAssertion("cred".toByteArray(), "handle".toByteArray(), rpId.toByteArray(), clientDataHash + entry.id.value.toByteArray())
    }

    override fun removePasskey(id: EntryId) {
        edit()
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0 && entries[index].passkey != null) {
            entries[index] = entries[index].copy(passkey = null)
            operations += "remove-passkey:${id.value}"
            generation++
        }
    }

    var entryIconHost: String? = null

    override fun siteIconHost(id: EntryId): String? {
        check()
        return entryIconHost
    }

    var iconsToAssign = 0

    override fun assignIconsByUrl(): Int {
        edit()
        if (iconsToAssign > 0) {
            operations += "assign-icons:$iconsToAssign"
            generation++
        }
        return iconsToAssign
    }

    override fun setEntryIcon(id: EntryId, icon: Int) {
        edit()
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) throw VaultEditException(VaultEditException.Reason.EntryNotFound)
        if (entries[index].icon == icon) return
        entries[index] = entries[index].copy(icon = icon)
        operations += "entry-icon:${id.value}:$icon"
        generation++
    }

    override fun renameGroup(id: GroupId, name: String) {
        edit()
        val index = groups.indexOfFirst { it.id == id }
        groups[index] = groups[index].copy(name = name)
        operations += "rename-group:$name"
        generation++
    }

    override fun deleteGroup(id: GroupId, recursive: Boolean, permanent: Boolean): DeleteOutcome {
        edit()
        if (id == ROOT) throw VaultEditException(VaultEditException.Reason.RootGroup)
        groups.removeAll { it.id == id }
        operations += "delete-group:${id.value}:$permanent"
        generation++
        return DeleteOutcome.Deleted
    }

    override fun prepareSave(): SaveSnapshot {
        if (isClosed) throw VaultSaveException(VaultSaveException.Reason.SessionClosed)
        prepareFailure?.let { throw VaultSaveException(it) }
        prepareCalls++
        return SaveSnapshot("encrypted-$generation".toByteArray(), generation)
    }

    override fun markSaved(generation: Long): Boolean {
        check()
        if (generation != this.generation) return false
        savedGeneration = generation
        return true
    }

    override fun close() {
        isClosed = true
    }

    private fun record(draft: EntryDraft) {
        drafts += RecordedDraft(
            draft.title,
            draft.username,
            draft.password.decodeToString(),
            draft.url,
            draft.notes,
            draft.tags,
            draft.customFields.map { Triple(it.name, it.value.decodeToString(), it.isProtected) },
            draft.favorite,
            draft.expiresAt,
        )
    }

    private fun edit() {
        if (isClosed) throw VaultEditException(VaultEditException.Reason.SessionClosed)
        editFailure?.let { throw VaultEditException(it) }
    }

    private fun check() {
        if (isClosed) throw VaultSessionException(VaultSessionException.Reason.SessionClosed)
    }
}

// What the fake core saw, copied before the caller's arrays are wiped.
data class Seen(val password: String?, val material: String?, val keyFile: String?, val retain: Boolean)

class FakeKeepCore : KeepCore {
    var failure: VaultOpenException.Reason? = null
    var gate: CompletableDeferred<Unit>? = null
    var fingerprint = FINGERPRINT
    var editable = true
    var accepts: (password: String?, keyFile: String?) -> Boolean = { _, _ -> true }
    val passwords = mutableListOf<ByteArray>()
    val received = mutableListOf<VaultCredentials>()
    val seen = mutableListOf<Seen>()
    var closeAllCalls = 0
    val sessions = mutableListOf<KeepVaultSession>()

    override fun info() = CoreInfo("keep-core", "test")

    override fun detectVaultFormat(header: ByteArray): VaultFormat =
        if (header.contentEquals(KDBX_HEADER)) VaultFormat.Kdbx(4, 0) else VaultFormat.Unknown

    override suspend fun openVault(data: ByteArray, credentials: VaultCredentials): KeepVaultSession {
        credentials.password?.let { passwords += it }
        received += credentials
        val password = credentials.password?.decodeToString()
        val material = credentials.unlockMaterial?.decodeToString()
        val keyFile = credentials.keyFile?.decodeToString()
        seen += Seen(password, material, keyFile, credentials.retainUnlockMaterial)
        gate?.await()
        try {
            failure?.let { throw VaultOpenException(it) }
            if (password == null && material == null && keyFile == null) throw VaultOpenException(VaultOpenException.Reason.MissingCredentials)
            val effective = password ?: material?.removePrefix("M:")
            if (!accepts(effective, keyFile)) throw VaultOpenException(VaultOpenException.Reason.InvalidCredentials)
            val retained = password?.takeIf { credentials.retainUnlockMaterial }?.let { "M:$it" }
            return FakeVaultSession(fingerprint = fingerprint, material = retained, editable = editable).also { sessions += it }
        } finally {
            credentials.close()
        }
    }

    override fun closeAllSessions(): Int {
        closeAllCalls++
        return 0
    }

    override fun generatePassword(options: PasswordOptions) = SecretValue("g".repeat(options.length).toByteArray())

    var importFailure: app.vazie.keep.core.ImportException.Reason? = null
    val importPlans = mutableListOf<FakeImportPlan>()
    var importBytes: ByteArray? = null

    override fun parseImport(data: ByteArray): app.vazie.keep.core.ImportPlan {
        importBytes = data
        importFailure?.let { throw app.vazie.keep.core.ImportException(it) }
        return FakeImportPlan(String(data).lines().count { it.isNotBlank() }).also { importPlans += it }
    }

    override val balancedPreset = app.vazie.keep.core.KdfSettings(65536, 6, 2)
    override val strongerPreset = app.vazie.keep.core.KdfSettings(131072, 10, 2)

    val created = mutableListOf<Pair<String, ByteArray>>()

    override suspend fun createVault(name: String, kdf: app.vazie.keep.core.KdfSettings, credentials: VaultCredentials): app.vazie.keep.core.CreatedVault {
        credentials.use {
            if (it.password == null && it.keyFile == null) throw app.vazie.keep.core.VaultCreateException(app.vazie.keep.core.VaultCreateException.Reason.MissingCredentials)
        }
        val bytes = KDBX_HEADER + name.toByteArray()
        created += name to bytes
        return app.vazie.keep.core.CreatedVault(bytes, fingerprint)
    }

    override suspend fun benchmarkKdf(kdf: app.vazie.keep.core.KdfSettings) = 100

    override fun generateKeyFile() = SecretValue("<KeyFile/>".toByteArray())

    override fun normalizeTotp(input: ByteArray, access: app.vazie.keep.core.KeepAccess): app.vazie.keep.core.TotpSetup {
        if (!access.plusFeatures) throw app.vazie.keep.core.TotpException(app.vazie.keep.core.TotpException.Reason.NotEntitled)
        val text = input.decodeToString().trim()
        val valid = text.startsWith("otpauth://totp/") || (text.isNotEmpty() && text.all { it in 'A'..'Z' || it in '2'..'7' })
        if (!valid) throw app.vazie.keep.core.TotpException(app.vazie.keep.core.TotpException.Reason.InvalidSecret)
        val uri = if (text.startsWith("otpauth://")) text else "otpauth://totp/?secret=$text&algorithm=SHA1&digits=6&period=30"
        return app.vazie.keep.core.TotpSetup(SecretValue(uri.toByteArray()), app.vazie.keep.core.TotpAlgorithm.Sha1, 6, 30, false, "", "")
    }

    override fun verifyLease(token: String, keys: List<app.vazie.keep.core.LeaseKey>): app.vazie.keep.core.KeepLease = error("the real core verifies leases")

    override fun evaluateAccess(lease: app.vazie.keep.core.KeepLease?, now: Long, lastObserved: Long): app.vazie.keep.core.KeepAccess = error("the real core evaluates access")

    override fun canUse(feature: app.vazie.keep.core.PlusFeature, access: app.vazie.keep.core.KeepAccess) = access.plusFeatures

    override fun matchHost(entryHost: String, requestHost: String) = when {
        entryHost.equals(requestHost, ignoreCase = true) -> app.vazie.keep.core.HostMatch.Exact
        requestHost.endsWith(".$entryHost", ignoreCase = true) -> app.vazie.keep.core.HostMatch.Subdomain
        else -> app.vazie.keep.core.HostMatch.None
    }
}

class FakeFileAccess(var content: ByteArray = KDBX_HEADER + ByteArray(64)) : VaultFileAccess {
    var failure: VaultFileException? = null
    var keyFiles = mutableMapOf(KEY_FILE_URI to "key-file-bytes")
    var keyFileFailure: VaultFileException? = null
    val keyFileReads = mutableListOf<ByteArray>()
    val persisted = mutableSetOf<String>()
    val newFiles = mutableMapOf<String, ByteArray>()
    var writeNewFailure: VaultFileException? = null
    val deleted = mutableListOf<String>()

    override suspend fun writeNew(uri: String, bytes: ByteArray) {
        writeNewFailure?.let { throw it }
        newFiles[uri] = bytes.copyOf()
        content = bytes.copyOf()
    }

    override suspend fun deleteQuietly(uri: String) {
        deleted += uri
        newFiles.remove(uri)
    }
    val released = mutableListOf<String>()
    var headers = mutableMapOf(VAULT_URI to KDBX_HEADER)

    val contents = mutableMapOf<String, ByteArray>()
    val unreadable = mutableSetOf<String>()

    override suspend fun read(uri: String): ByteArray {
        failure?.let { throw it }
        if (uri in unreadable) throw VaultFileException.Unavailable()
        return contents[uri] ?: content
    }

    val attachmentFiles = mutableMapOf<String, ByteArray>()

    override suspend fun readAttachment(uri: String): ByteArray {
        val bytes = attachmentFiles[uri] ?: throw VaultFileException.Unavailable()
        if (bytes.size > app.vazie.keep.core.MAX_ATTACHMENT_BYTES) throw VaultFileException.TooLarge()
        return bytes.copyOf()
    }

    override suspend fun readKeyFile(uri: String): ByteArray {
        keyFileFailure?.let { throw it }
        val text = keyFiles[uri] ?: throw VaultFileException.Unavailable()
        return text.toByteArray().also { keyFileReads += it }
    }

    override suspend fun readHeader(uri: String): ByteArray =
        headers[uri] ?: keyFiles[uri]?.toByteArray()?.take(12)?.toByteArray() ?: throw VaultFileException.Unavailable()

    override suspend fun displayName(uri: String) = uri.substringAfterLast('/')

    var writable = true

    override fun hasWriteAccess(uri: String) = writable

    override fun persistPermission(uri: String, write: Boolean) {
        persisted += uri
        if (write) writePersisted += uri
    }

    val writePersisted = mutableSetOf<String>()

    override fun releasePermission(uri: String) {
        released += uri
        persisted -= uri
    }
}

class InMemoryReferenceStore(var reference: VaultReference? = VaultReference(VAULT_URI, "vault.kdbx", 0)) : VaultReferenceStore {
    override fun load() = reference

    override fun save(reference: VaultReference) {
        this.reference = reference
    }

    override fun clear() {
        reference = null
    }
}

class FakeClipboard(
    private val systemConfirms: Boolean = false,
    var clearsAfter: kotlin.time.Duration? = kotlin.time.Duration.parse("30s"),
) : SecretClipboard {
    val copied = mutableListOf<String>()
    var cleared = 0

    override fun copy(secret: SecretValue): app.vazie.keep.data.CopyResult {
        copied += secret.useChars { String(it) }
        return app.vazie.keep.data.CopyResult(systemConfirms, clearsAfter)
    }

    override fun clearNow() {
        cleared++
    }
}

// Records what the repository asks the document layer to write; `failure` makes the next replace throw.
class FakeDocumentWriter : VaultDocumentWriter {
    var failure: SaveFailure? = null
    var gate: CompletableDeferred<Unit>? = null
    val written = mutableListOf<ByteArray>()
    val copies = mutableListOf<Pair<String, ByteArray>>()
    var copyFailure = false
    var restoreFailure: SaveFailure? = null
    var restored: ByteArray? = null
    var capabilities = ProviderCapabilities.Unknown
    var backups = 0

    override suspend fun replace(uri: String, expected: FileVersion, bytes: ByteArray, backup: (ByteArray) -> Boolean): FileVersion {
        gate?.await()
        failure?.let { throw WriteException(it) }
        if (backup("previous".toByteArray())) backups++
        written += bytes
        return FileVersion.of(bytes)
    }

    override suspend fun restore(uri: String, damaged: FileVersion, bytes: ByteArray): FileVersion {
        restoreFailure?.let { throw WriteException(it) }
        restored = bytes
        return FileVersion.of(bytes)
    }

    override suspend fun capabilities(uri: String) = capabilities

    override suspend fun writeCopy(uri: String, bytes: ByteArray) {
        if (copyFailure) throw WriteException(SaveFailure.Unavailable)
        copies += uri to bytes
    }
}

class InMemoryCopyStore : VaultCopyStore {
    val backups = mutableMapOf<String, ByteArray>()
    val unsaved = mutableMapOf<String, ByteArray>()
    val recovered = mutableMapOf<String, ByteArray>()
    val pending = mutableMapOf<String, ByteArray>()
    val damaged = mutableMapOf<String, ByteArray>()
    val baselines = mutableMapOf<String, String>()
    var failBackup = false

    override fun keepBackup(bytes: ByteArray, fingerprint: String): Boolean {
        if (failBackup) return false
        backups[fingerprint] = bytes
        return true
    }

    override fun backup(fingerprint: String) = backups[fingerprint]

    override fun keepUnsaved(bytes: ByteArray, fingerprint: String) = unsaved.put(fingerprint, bytes).let { true }

    override fun unsaved(fingerprint: String) = unsaved[fingerprint]

    override fun removeUnsaved(fingerprint: String) {
        unsaved.remove(fingerprint)
    }

    override fun promoteUnsaved(fingerprint: String): Boolean {
        val bytes = unsaved.remove(fingerprint) ?: return false
        recovered[fingerprint] = bytes
        return true
    }

    override fun recovered(fingerprint: String) = recovered[fingerprint]

    override fun hasRecovered(fingerprint: String) = fingerprint in recovered

    override fun removeRecovered(fingerprint: String) {
        recovered.remove(fingerprint)
    }

    override fun beginWrite(uri: String, baseline: FileVersion, pending: ByteArray): Boolean {
        this.pending[uri] = pending
        baselines[uri] = baseline.sha256
        return true
    }

    override fun inspectWrite(uri: String, current: FileVersion): JournalState {
        val bytes = pending[uri] ?: return JournalState.None
        return when {
            FileVersion.of(bytes) == current -> JournalState.Completed
            baselines[uri] == current.sha256 -> JournalState.NeverWritten
            else -> JournalState.Interrupted
        }
    }

    override fun pendingWrite(uri: String) = pending[uri]

    override fun finishWrite(uri: String) {
        pending.remove(uri)
        baselines.remove(uri)
    }

    override fun keepDamaged(uri: String, bytes: ByteArray) = damaged.put(uri, bytes).let { true }
}

fun testRepository(
    core: KeepCore,
    files: VaultFileAccess,
    references: VaultReferenceStore,
    writer: VaultDocumentWriter = FakeDocumentWriter(),
    copies: VaultCopyStore = InMemoryCopyStore(),
    now: () -> Long = System::currentTimeMillis,
    retainUnlockMaterial: () -> Boolean = { false },
    background: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Default,
    progressDelay: kotlin.time.Duration = kotlin.time.Duration.parse("500ms"),
) = VaultRepository(
    core = core,
    files = files,
    references = references,
    writer = writer,
    copies = copies,
    now = now,
    retainUnlockMaterial = retainUnlockMaterial,
    background = background,
    progressDelay = progressDelay,
)

val PLUS_ACCESS = app.vazie.keep.core.KeepAccess(app.vazie.keep.core.KeepAccessState.PlusActive, true, false, null, 0)
