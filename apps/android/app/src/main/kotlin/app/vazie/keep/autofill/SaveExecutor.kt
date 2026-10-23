package app.vazie.keep.autofill

import app.vazie.keep.core.CustomFieldDraft
import app.vazie.keep.core.EntryDraft
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.KeepVaultSession
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultSaveException
import app.vazie.keep.core.VaultSessionException
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultCopyStore
import app.vazie.keep.data.VaultDocumentWriter
import app.vazie.keep.data.VaultFileAccess
import app.vazie.keep.data.VaultReference
import app.vazie.keep.data.WriteException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface SaveResult {
    data class Created(val entryId: String) : SaveResult

    data class Updated(val entryId: String) : SaveResult

    // The entry already holds this password: nothing was written.
    data class Unchanged(val entryId: String) : SaveResult

    data class Failed(val problem: AuthProblem) : SaveResult
}

// Applies one confirmed choice to an opened session and writes it through the same safe save as the
// app (ADR 0015): version check, app-private backup, journaled overwrite, read-back. Autofill has no
// save path of its own.
class SaveExecutor(
    private val files: VaultFileAccess,
    private val writer: VaultDocumentWriter,
    private val copies: VaultCopyStore,
    private val afterSave: (OpenedVault) -> Unit,
    // Without Keep+ only the main vault takes new or changed entries.
    private val mayEdit: (VaultReference) -> Boolean = { true },
    private val timing: (label: String, millis: Long) -> Unit = { _, _ -> },
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun execute(
        vault: OpenedVault,
        reference: VaultReference,
        choice: SaveChoice,
        username: String,
        password: ByteArray,
        title: String,
        url: String,
    ): SaveResult = withContext(NonCancellable + background) {
        val session = vault.session
        try {
            if (!session.isEditable() || !files.hasWriteAccess(reference.uri) || !mayEdit(reference)) {
                return@withContext SaveResult.Failed(AuthProblem.NotWritable)
            }
            val started = System.nanoTime()
            val result = when (choice) {
                SaveChoice.Create -> create(session, vault, username, password, title, url)
                is SaveChoice.Update -> update(session, EntryId(choice.entry.entryId), username, password)
            }
            timing("autofill save mutate", (System.nanoTime() - started) / 1_000_000)
            if (result is SaveResult.Unchanged || result is SaveResult.Failed) return@withContext result
            val serialized = System.nanoTime()
            val snapshot = session.prepareSave()
            timing("autofill save serialize", (System.nanoTime() - serialized) / 1_000_000)
            val written = System.nanoTime()
            writer.replace(reference.uri, vault.version, snapshot.bytes) { copies.keepBackup(it, vault.info.fingerprint) }
            timing("autofill save write", (System.nanoTime() - written) / 1_000_000)
            afterSave(vault)
            result
        } catch (error: WriteException) {
            SaveResult.Failed(error.failure.toProblem())
        } catch (_: VaultSaveException) {
            SaveResult.Failed(AuthProblem.CouldNotSave)
        } catch (_: VaultEditException) {
            SaveResult.Failed(AuthProblem.CouldNotSave)
        } catch (_: VaultSessionException) {
            SaveResult.Failed(AuthProblem.CouldNotSave)
        } finally {
            password.fill(0)
        }
    }

    private fun create(session: KeepVaultSession, vault: OpenedVault, username: String, password: ByteArray, title: String, url: String): SaveResult {
        val draft = EntryDraft(title, username, password.copyOf(), url, "", emptyList(), emptyList())
        return draft.use { SaveResult.Created(session.createEntry(vault.info.rootGroupId, it).value) }
    }

    // Everything the entry holds is read back into the draft (notes, protected custom fields, tags,
    // title), so an update changes the password and nothing else. The core adds the previous version
    // to the entry's history.
    private fun update(session: KeepVaultSession, id: EntryId, username: String, password: ByteArray): SaveResult {
        val detail = try {
            session.entry(id)
        } catch (_: VaultSessionException) {
            return SaveResult.Failed(AuthProblem.EntryGone)
        }
        val current = if (detail.summary.hasPassword) session.reveal(id, SecretField.Password).use { it.useBytes { bytes -> bytes.copyOf() } } else ByteArray(0)
        val unchanged = current.contentEquals(password)
        current.fill(0)
        if (unchanged) return SaveResult.Unchanged(id.value)
        fun text(value: EntryText, field: SecretField) = (value as? EntryText.Plain)?.value ?: session.reveal(id, field).use { it.useChars { chars -> String(chars) } }
        val summary = detail.summary
        val existingUser = text(summary.username, SecretField.UserName)
        val fields = detail.customFields.map { field ->
            when (val value = field.value) {
                is EntryText.Plain -> CustomFieldDraft(field.name, value.value.toByteArray(), false)
                EntryText.Hidden -> CustomFieldDraft(field.name, session.reveal(id, SecretField.Custom(field.name)).use { it.useBytes { b -> b.copyOf() } }, true)
            }
        }
        val draft = EntryDraft(
            title = text(summary.title, SecretField.Title),
            username = existingUser.ifEmpty { username },
            password = password.copyOf(),
            url = text(summary.url, SecretField.Url),
            notes = text(detail.notes, SecretField.Notes),
            customFields = fields,
            tags = summary.tags,
        )
        draft.use { session.updateEntry(id, it) }
        return SaveResult.Updated(id.value)
    }

    private fun SaveFailure.toProblem() = when (this) {
        SaveFailure.ChangedExternally -> AuthProblem.VaultChangedElsewhere
        SaveFailure.NotWritable, SaveFailure.ProviderUnsupported, SaveFailure.ReadOnly -> AuthProblem.NotWritable
        SaveFailure.RestoreNeeded -> AuthProblem.RestoreNeeded
        SaveFailure.Unavailable -> AuthProblem.FileUnavailable
        else -> AuthProblem.CouldNotSave
    }
}
