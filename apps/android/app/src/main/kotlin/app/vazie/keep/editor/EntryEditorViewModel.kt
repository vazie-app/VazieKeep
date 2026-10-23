package app.vazie.keep.editor

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.vazie.keep.core.CustomFieldDraft
import app.vazie.keep.core.EntryDraft
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.GroupId
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.PasswordGeneratorException
import app.vazie.keep.core.PasswordOptions
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.KeepAccess
import app.vazie.keep.core.OTP_FIELD_NAME
import app.vazie.keep.core.PlusFeature
import app.vazie.keep.core.SecretValue
import app.vazie.keep.core.TotpException
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.data.AttachmentFile
import app.vazie.keep.data.EditOutcome
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.save.SaveFlow
import app.vazie.keep.vault.GroupChoice
import app.vazie.keep.vault.groupChoices
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val EDITOR_ENTRY_ARG = "entryId"
const val EDITOR_GROUP_ARG = "groupId"

// `data` is set only for a file picked in this editor and not yet in the vault.
class AttachmentItem(val key: Long, val name: String, val size: Long, val data: ByteArray? = null) {
    override fun toString() = "AttachmentItem(size=$size, pending=${data != null})"
}

class CustomFieldState(val key: Long, name: String = "", value: String = "", isProtected: Boolean = false) {
    val name = TextFieldState(name)
    val value = TextFieldState(value)
    val isProtected = mutableStateOf(isProtected)
}

// Held by the screen model, never by composition state or the saved-state bundle: the text of a
// password must not be written to an instance-state parcel.
class EditorFields {
    val title = TextFieldState()
    val username = TextFieldState()
    val password = TextFieldState()
    val url = TextFieldState()
    val notes = TextFieldState()
    val tags = mutableStateListOf<String>()
    val tagInput = TextFieldState()
    val favorite = mutableStateOf(false)
    val expiresAt = mutableStateOf<Long?>(null)
    val custom = mutableStateListOf<CustomFieldState>()
    val attachments = mutableStateListOf<AttachmentItem>()

    fun wipe() {
        attachments.forEach { it.data?.fill(0) }
        attachments.clear()
        listOf(title, username, password, url, notes, tagInput).forEach { it.setTextAndPlace("") }
        tags.clear()
        favorite.value = false
        expiresAt.value = null
        custom.forEach {
            it.name.setTextAndPlace("")
            it.value.setTextAndPlace("")
        }
        custom.clear()
    }

    private fun TextFieldState.setTextAndPlace(text: String) = edit { replace(0, length, text) }
}

sealed interface EditorUiState {
    data object Loading : EditorUiState

    data object Locked : EditorUiState

    data object NotFound : EditorUiState

    data class Ready(
        val isNew: Boolean,
        val groups: List<GroupChoice>,
        val group: GroupId,
        val saving: Boolean = false,
        val invalid: VaultEditException.Reason? = null,
        val changed: Boolean = false,
    ) : EditorUiState
}

sealed interface EditorEvent {
    data object Saved : EditorEvent
}

class GeneratorState(val options: PasswordOptions = PasswordOptions(), val generated: Boolean = false, val revealed: String? = null)

class EntryEditorViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: VaultRepository,
    private val core: KeepCore,
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val access: () -> KeepAccess = { KeepAccess.Free },
    private val onSaved: (EntryId) -> Unit = {},
) : ViewModel() {
    private var entryId: EntryId? = savedStateHandle.get<String>(EDITOR_ENTRY_ARG)?.let(::EntryId)
    private val initialGroup = savedStateHandle.get<String>(EDITOR_GROUP_ARG)?.let(::GroupId)
    private var originalGroup: GroupId? = initialGroup

    val fields = EditorFields()
    val saveFlow = SaveFlow(repository, viewModelScope)

    private val mutableState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val state: StateFlow<EditorUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = eventChannel.receiveAsFlow()

    private val mutableGenerator = MutableStateFlow(GeneratorState())
    val generator: StateFlow<GeneratorState> = mutableGenerator.asStateFlow()
    private var generated: SecretValue? = null

    val hideSignal: Flow<Unit> get() = repository.hideSecretsRequests

    private val mutableKnownTags = MutableStateFlow<List<String>>(emptyList())
    val knownTags: StateFlow<List<String>> = mutableKnownTags.asStateFlow()

    private val mutableCanEdit = MutableStateFlow(true)
    val canEdit: StateFlow<Boolean> = mutableCanEdit.asStateFlow()

    private var initial: Snapshot? = null

    init {
        viewModelScope.launch {
            repository.state.collect { vault ->
                if (vault is VaultState.Locked) {
                    discard()
                    mutableState.value = EditorUiState.Locked
                } else if (vault is VaultState.Unlocked) {
                    mutableCanEdit.value = vault.canEdit
                }
            }
        }
        viewModelScope.launch { repository.hideSecretsRequests.collect { hideGenerated() } }
        viewModelScope.launch { load() }
    }

    val isNew: Boolean get() = entryId == null

    fun hasChanges(): Boolean = initial?.let { it != snapshot() } ?: false

    fun selectGroup(group: GroupId) {
        mutableState.update { if (it is EditorUiState.Ready) it.copy(group = group) else it }
    }

    fun addField() {
        fields.custom.add(CustomFieldState(nextKey++))
    }

    val canAddTotp: Boolean get() = core.canUse(PlusFeature.Totp, access())

    fun addTotp(input: String): TotpException.Reason? = try {
        core.normalizeTotp(input.toByteArray(), access()).use { setup ->
            val uri = setup.uri.useChars { String(it) }
            val existing = fields.custom.firstOrNull { it.name.text.toString().trim() == OTP_FIELD_NAME }
            if (existing != null) {
                existing.value.edit { replace(0, length, uri) }
                existing.isProtected.value = true
            } else {
                fields.custom.add(CustomFieldState(nextKey++, OTP_FIELD_NAME, uri, true))
            }
        }
        null
    } catch (error: TotpException) {
        error.reason
    }

    fun addTag(text: String = fields.tagInput.text.toString()) {
        splitTags(text).forEach { tag -> if (fields.tags.none { it == tag }) fields.tags.add(tag) }
        fields.tagInput.edit { replace(0, length, "") }
    }

    fun removeTag(tag: String) {
        fields.tags.remove(tag)
    }

    fun setFavorite(favorite: Boolean) {
        fields.favorite.value = favorite
    }

    fun setExpiry(unixSeconds: Long?) {
        fields.expiresAt.value = unixSeconds
    }

    val canAddAttachments: Boolean get() = core.canUse(PlusFeature.AttachmentWrite, access())

    private val removedAttachments = mutableListOf<String>()

    private val mutableAttachmentProblem = MutableStateFlow<AttachmentFile?>(null)
    val attachmentProblem: StateFlow<AttachmentFile?> = mutableAttachmentProblem.asStateFlow()

    fun addAttachmentFrom(uri: String?) {
        if (uri == null) return
        viewModelScope.launch {
            when (val file = repository.readAttachmentFile(uri)) {
                is AttachmentFile.Read -> {
                    mutableAttachmentProblem.value = null
                    addAttachment(file.name, file.bytes)
                }
                else -> mutableAttachmentProblem.value = file
            }
        }
    }

    fun addAttachment(name: String, data: ByteArray) {
        fields.attachments.add(AttachmentItem(nextKey++, name, data.size.toLong(), data))
    }

    fun removeAttachment(key: Long) {
        val item = fields.attachments.firstOrNull { it.key == key } ?: return
        if (item.data == null) removedAttachments += item.name else item.data.fill(0)
        fields.attachments.remove(item)
    }

    fun removeField(key: Long) {
        fields.custom.removeAll { it.key == key }
    }

    fun clearInvalid() {
        mutableState.update { if (it is EditorUiState.Ready) it.copy(invalid = null) else it }
    }

    fun save() {
        val current = state.value as? EditorUiState.Ready ?: return
        if (current.saving) return
        mutableState.value = current.copy(saving = true, invalid = null)
        val draft = buildDraft()
        val existing = entryId
        val target = current.group
        val moved = existing != null && target != originalGroup
        val removals = removedAttachments.toList()
        val additions = fields.attachments.mapNotNull { item -> item.data?.let { item.name to it } }
        val plus = access()
        viewModelScope.launch {
            val outcome = try {
                repository.edit { session ->
                    val id = if (existing == null) {
                        session.createEntry(target, draft)
                    } else {
                        session.updateEntry(existing, draft)
                        if (moved) session.moveEntry(existing, target)
                        existing
                    }
                    removals.forEach { session.removeAttachment(id, it) }
                    additions.forEach { (name, data) -> session.addAttachment(id, name, data, plus) }
                    id
                }
            } catch (_: VaultLockedException) {
                return@launch
            } finally {
                draft.close()
            }
            when (outcome) {
                is EditOutcome.Saved -> {
                    onSaved(outcome.value)
                    discard()
                    eventChannel.send(EditorEvent.Saved)
                }
                is EditOutcome.NotSaved -> {
                    // The entry is in the open vault now, so saving again must update it, not create another.
                    entryId = outcome.value
                    originalGroup = target
                    reloadAttachments(outcome.value)
                    initial = snapshot()
                    mutableState.update { if (it is EditorUiState.Ready) it.copy(saving = false, isNew = false) else it }
                    saveFlow.report(outcome.failure)
                }
                is EditOutcome.Invalid -> mutableState.update {
                    if (it is EditorUiState.Ready) it.copy(saving = false, invalid = outcome.reason) else it
                }
            }
        }
    }

    fun openGenerator() = generate()

    fun setGeneratorOptions(options: PasswordOptions) {
        mutableGenerator.value = GeneratorState(options)
        generate()
    }

    fun generate() {
        val options = mutableGenerator.value.options
        clearGenerated()
        try {
            generated = core.generatePassword(options)
            mutableGenerator.value = GeneratorState(options, generated = true)
        } catch (_: PasswordGeneratorException) {
            mutableGenerator.value = GeneratorState(options)
        }
    }

    fun revealGenerated() {
        val secret = generated ?: return
        val text = secret.useChars { String(it) }
        mutableGenerator.update { GeneratorState(it.options, generated = true, revealed = text) }
    }

    fun hideGenerated() {
        mutableGenerator.update { if (it.revealed != null) GeneratorState(it.options, it.generated) else it }
    }

    fun useGenerated() {
        val secret = generated ?: return
        val text = secret.useChars { String(it) }
        fields.password.edit { replace(0, length, text) }
        closeGenerator()
    }

    fun closeGenerator() {
        clearGenerated()
        mutableGenerator.update { GeneratorState(it.options) }
    }

    override fun onCleared() {
        discard()
    }

    private fun discard() {
        clearGenerated()
        mutableGenerator.value = GeneratorState()
        fields.wipe()
        removedAttachments.clear()
        initial = null
    }

    private fun clearGenerated() {
        generated?.close()
        generated = null
    }

    private var nextKey = 1L

    private suspend fun reloadAttachments(id: EntryId) {
        removedAttachments.clear()
        val stored = try {
            withContext(background) { repository.entry(id).attachments }
        } catch (_: VaultLockedException) {
            return
        } catch (_: VaultSessionException) {
            return
        }
        fields.attachments.forEach { it.data?.fill(0) }
        fields.attachments.clear()
        stored.forEach { fields.attachments.add(AttachmentItem(nextKey++, it.name, it.size)) }
    }

    private suspend fun load() {
        try {
            val loaded = withContext(background) {
                val info = (repository.state.value as? VaultState.Unlocked)?.info ?: throw VaultLockedException()
                val groups = groupChoices(repository.groups(), info)
                mutableKnownTags.value = repository.entries().flatMap { it.tags }.distinct().sortedBy { it.lowercase() }
                val id = entryId
                if (id == null) return@withContext Loaded(groups, initialGroup ?: info.rootGroupId, null)
                val detail = repository.entry(id)
                fun secret(field: SecretField) = repository.reveal(id, field).use { it.useChars { chars -> String(chars) } }
                fun plainOr(text: EntryText, field: SecretField) = (text as? EntryText.Plain)?.value ?: secret(field)
                val summary = detail.summary
                val values = EntryValues(
                    title = plainOr(summary.title, SecretField.Title),
                    username = plainOr(summary.username, SecretField.UserName),
                    url = plainOr(summary.url, SecretField.Url),
                    notes = plainOr(detail.notes, SecretField.Notes),
                    password = if (summary.hasPassword) secret(SecretField.Password) else "",
                    tags = summary.tags,
                    favorite = summary.favorite,
                    expiresAt = summary.expiresAt,
                    attachments = detail.attachments,
                    custom = detail.customFields.map { field ->
                        val value = (field.value as? EntryText.Plain)?.value ?: secret(SecretField.Custom(field.name))
                        Triple(field.name, value, field.value is EntryText.Hidden)
                    },
                )
                Loaded(groups, summary.groupId, values)
            }
            loaded.values?.let { values ->
                fields.title.edit { replace(0, length, values.title) }
                fields.username.edit { replace(0, length, values.username) }
                fields.url.edit { replace(0, length, values.url) }
                fields.notes.edit { replace(0, length, values.notes) }
                fields.password.edit { replace(0, length, values.password) }
                fields.tags.addAll(values.tags)
                fields.favorite.value = values.favorite
                fields.expiresAt.value = values.expiresAt
                values.custom.forEach { (name, value, hidden) -> fields.custom.add(CustomFieldState(nextKey++, name, value, hidden)) }
                values.attachments.forEach { fields.attachments.add(AttachmentItem(nextKey++, it.name, it.size)) }
            }
            originalGroup = loaded.group
            initial = snapshot(loaded.group)
            mutableState.value = EditorUiState.Ready(isNew = entryId == null, groups = loaded.groups, group = loaded.group)
        } catch (_: VaultLockedException) {
            mutableState.value = EditorUiState.Locked
        } catch (_: VaultSessionException) {
            mutableState.value = EditorUiState.NotFound
        }
    }

    private fun buildDraft(): EntryDraft = EntryDraft(
        title = fields.title.text.toString(),
        username = fields.username.text.toString(),
        password = fields.password.text.toString().toByteArray(),
        url = fields.url.text.toString().trim(),
        notes = fields.notes.text.toString(),
        customFields = fields.custom
            .filter { it.name.text.isNotBlank() || it.value.text.isNotEmpty() }
            .map { CustomFieldDraft(it.name.text.toString().trim(), it.value.text.toString().toByteArray(), it.isProtected.value) },
        tags = (fields.tags + splitTags(fields.tagInput.text.toString())).distinct(),
        favorite = fields.favorite.value,
        expiresAt = fields.expiresAt.value,
    )

    private fun snapshot(group: GroupId? = (state.value as? EditorUiState.Ready)?.group) = Snapshot(
        listOf(fields.title, fields.username, fields.password, fields.url, fields.notes, fields.tagInput).map { it.text.toString() },
        fields.custom.map { Triple(it.name.text.toString(), it.value.text.toString(), it.isProtected.value) },
        group,
        fields.tags.toList(),
        fields.favorite.value,
        fields.expiresAt.value,
        fields.attachments.map { it.key },
    )

    private data class Snapshot(
        val texts: List<String>,
        val custom: List<Triple<String, String, Boolean>>,
        val group: GroupId?,
        val tags: List<String>,
        val favorite: Boolean,
        val expiresAt: Long?,
        val attachments: List<Long>,
    ) {
        override fun toString() = "Snapshot(<redacted>)"
    }

    private class EntryValues(
        val title: String,
        val username: String,
        val url: String,
        val notes: String,
        val password: String,
        val tags: List<String>,
        val favorite: Boolean,
        val expiresAt: Long?,
        val attachments: List<app.vazie.keep.core.AttachmentInfo>,
        val custom: List<Triple<String, String, Boolean>>,
    ) {
        override fun toString() = "EntryValues(<redacted>)"
    }

    private class Loaded(val groups: List<GroupChoice>, val group: GroupId, val values: EntryValues?)
}

private fun splitTags(text: String): List<String> = text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
