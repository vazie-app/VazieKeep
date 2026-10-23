package app.vazie.keep.data

import app.vazie.keep.core.EntryDetail
import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.HistoryVersion
import app.vazie.keep.core.KeepAccess
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.KeepVaultSession
import app.vazie.keep.core.MergeOptions
import app.vazie.keep.core.SecretField
import app.vazie.keep.core.SecretValue
import app.vazie.keep.core.UnlockMaterial
import app.vazie.keep.core.VaultCredentials
import app.vazie.keep.core.VaultFormat
import app.vazie.keep.core.VaultHistoryException
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.core.VaultInfo
import app.vazie.keep.core.VaultMergeException
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultOpenException
import app.vazie.keep.core.VaultSaveException
import app.vazie.keep.core.VaultSessionException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

sealed interface VaultState {
    data object Locked : VaultState

    data class Unlocked(
        val info: VaultInfo,
        val usedPassword: Boolean = true,
        val usedKeyFile: Boolean = false,
        val materialRetained: Boolean = false,
        val canEdit: Boolean = false,
        // False when the persisted grant is read-only (a vault chosen before saving existed).
        val writeGranted: Boolean = true,
        // True when the vault could be edited but the account allows editing only the main vault.
        val readOnlyByPlan: Boolean = false,
    ) : VaultState
}

enum class UnlockError {
    WrongPassword,
    WrongPasswordOrKeyFile,
    MissingCredentials,
    KeyFileUnavailable,
    KeyFileTooLarge,
    InvalidKeyFile,
    VaultMismatch,
    Damaged,
    // The file doesn't read as a vault, but a complete copy of an interrupted save is kept in the app.
    DamagedRestorable,
    Unsupported,
    NotAVault,
    FileUnavailable,
    TooLarge,
    Unknown,
}

sealed interface UnlockResult {
    data object Success : UnlockResult

    data class Failure(val error: UnlockError) : UnlockResult

    data object AlreadyRunning : UnlockResult
}

enum class SelectError { NotAVault, FileUnavailable, NeedsPlus }

sealed interface SelectResult {
    data class Selected(val reference: VaultReference) : SelectResult

    data class Failure(val error: SelectError) : SelectResult
}

enum class KeyFileError { Unavailable, IsVault }

sealed interface KeyFileResult {
    data class Selected(val displayName: String) : KeyFileResult

    data class Failure(val error: KeyFileError) : KeyFileResult
}

class VaultLockedException : IllegalStateException("vault is locked")

sealed interface RestoreOutcome {
    data object Restored : RestoreOutcome

    data object NothingToRestore : RestoreOutcome

    data class Failed(val failure: SaveFailure) : RestoreOutcome
}

class VaultRepository(
    private val core: KeepCore,
    private val files: VaultFileAccess,
    private val references: VaultReferenceStore,
    private val writer: VaultDocumentWriter,
    private val copies: VaultCopyStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val timing: (label: String, millis: Long) -> Unit = { _, _ -> },
    private val retainUnlockMaterial: () -> Boolean = { false },
    private val background: CoroutineDispatcher = Dispatchers.Default,
    private val progressDelay: Duration = 500.milliseconds,
    private val onVaultSwitched: () -> Unit = {},
    private val registry: VaultRegistry? = null,
    // Without Keep+ only the main vault is edited and no vault is added (data/VaultPolicy.kt).
    private val mayEditVault: (vaultId: String) -> Boolean = { true },
    private val mayAddVault: () -> Boolean = { true },
) {
    private val opener = VaultOpener(core, files, copies, timing)
    private val unlockMutex = Mutex()
    private val saveMutex = Mutex()
    private val sessionLock = Any()
    private var session: KeepVaultSession? = null
    private var baseline: FileVersion? = null
    private var fingerprint: String? = null
    private var generation = 0

    private val mutableRevision = MutableStateFlow(0)

    // Bumped after every edit, so lists reload.
    val contentRevision: StateFlow<Int> = mutableRevision.asStateFlow()

    private val mutableDirty = MutableStateFlow(false)

    // True when an edit is in the session but not in the file (a save failed).
    val hasUnsavedChanges: StateFlow<Boolean> = mutableDirty.asStateFlow()

    private val mutableSaving = MutableStateFlow(false)

    // Turns true only when a save takes longer than the progress delay.
    val saveInProgress: StateFlow<Boolean> = mutableSaving.asStateFlow()

    private val mutableRecovered = MutableStateFlow(false)

    // Changes that couldn't be saved before an earlier lock, kept as an encrypted copy.
    val hasRecoveredCopy: StateFlow<Boolean> = mutableRecovered.asStateFlow()

    private val mutableState = MutableStateFlow<VaultState>(VaultState.Locked)
    val state: StateFlow<VaultState> = mutableState.asStateFlow()

    private val hideRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val hideSecretsRequests: SharedFlow<Unit> = hideRequests.asSharedFlow()

    fun reference(): VaultReference? = references.load()

    suspend fun isReferenceReadable(): Boolean {
        val reference = references.load() ?: return false
        return try {
            files.readHeader(reference.uri).isNotEmpty()
        } catch (_: VaultFileException) {
            false
        }
    }

    suspend fun select(uri: String): SelectResult {
        val header = try {
            files.readHeader(uri)
        } catch (_: VaultFileException) {
            return SelectResult.Failure(SelectError.FileUnavailable)
        }
        if (core.detectVaultFormat(header) !is VaultFormat.Kdbx) return SelectResult.Failure(SelectError.NotAVault)
        files.persistPermission(uri, write = true)
        val known = registry
        if (known != null) {
            if (known.findByUri(uri) == null && !mayAddVault()) {
                files.releasePermission(uri)
                return SelectResult.Failure(SelectError.NeedsPlus)
            }
            // Another vault is a different security boundary: close this session first.
            lock()
            val fileName = files.displayName(uri) ?: DEFAULT_NAME
            val descriptor = known.findByUri(uri) ?: known.add(
                VaultDescriptor(id = newVaultId(), name = fileName.removeSuffix(".kdbx"), fileName = fileName, uri = uri, lastUsedAt = now()),
                activate = false,
            )
            val changed = known.active()?.id != descriptor.id
            known.activate(descriptor.id)
            if (changed) onVaultSwitched()
            return SelectResult.Selected(descriptor.toReference())
        }
        val previous = references.load()
        val sameVault = previous?.uri == uri
        if (previous != null && !sameVault) {
            files.releasePermission(previous.uri)
            previous.keyFileUri?.let(files::releasePermission)
        }
        val reference = VaultReference(
            uri = uri,
            displayName = files.displayName(uri) ?: DEFAULT_NAME,
            lastUsedAt = now(),
            keyFileUri = previous?.keyFileUri?.takeIf { sameVault },
            keyFileName = previous?.keyFileName?.takeIf { sameVault },
        )
        lock()
        if (!sameVault) onVaultSwitched()
        references.save(reference)
        return SelectResult.Selected(reference)
    }

    // Makes another known vault the active one. The open session, revealed secrets and navigation are
    // dropped first: a vault never sees what another one showed.
    fun activate(id: String) {
        val known = registry ?: return
        if (known.find(id) == null) return
        lock()
        if (known.active()?.id != id) onVaultSwitched()
        known.activate(id)
    }

    suspend fun selectKeyFile(uri: String): KeyFileResult {
        val reference = references.load() ?: return KeyFileResult.Failure(KeyFileError.Unavailable)
        val header = try {
            files.readHeader(uri)
        } catch (_: VaultFileException) {
            return KeyFileResult.Failure(KeyFileError.Unavailable)
        }
        if (uri == reference.uri || core.detectVaultFormat(header) != VaultFormat.Unknown) {
            return KeyFileResult.Failure(KeyFileError.IsVault)
        }
        files.persistPermission(uri)
        reference.keyFileUri?.takeIf { it != uri }?.let(files::releasePermission)
        val name = files.displayName(uri) ?: DEFAULT_KEY_FILE_NAME
        references.save(reference.copy(keyFileUri = uri, keyFileName = name))
        return KeyFileResult.Selected(name)
    }

    fun removeKeyFile() {
        val reference = references.load() ?: return
        reference.keyFileUri?.let(files::releasePermission)
        references.save(reference.copy(keyFileUri = null, keyFileName = null))
    }

    // [password] is typed text (null when the field is empty). Both arrays are wiped before returning.
    suspend fun unlock(password: ByteArray?): UnlockResult = exclusive(password) {
        open(password, material = null, useKeyFile = true, expectedFingerprint = null)
    }

    suspend fun unlockWithMaterial(material: ByteArray, useKeyFile: Boolean, expectedFingerprint: String): UnlockResult =
        exclusive(material) { open(password = null, material, useKeyFile, expectedFingerprint) }

    fun unlockMaterial(): UnlockMaterial? = try {
        synchronized(sessionLock) { session }?.unlockMaterial()
    } catch (_: VaultSessionException) {
        null
    }

    private suspend inline fun exclusive(secret: ByteArray?, block: () -> UnlockResult): UnlockResult {
        if (!unlockMutex.tryLock()) {
            secret?.fill(0)
            return UnlockResult.AlreadyRunning
        }
        try {
            return block()
        } finally {
            secret?.fill(0)
            unlockMutex.unlock()
        }
    }

    private suspend fun open(password: ByteArray?, material: ByteArray?, useKeyFile: Boolean, expectedFingerprint: String?): UnlockResult {
        val reference = references.load() ?: return UnlockResult.Failure(UnlockError.FileUnavailable)
        val started = System.nanoTime()
        val vault = when (val outcome = opener.open(reference, password, material, useKeyFile, retainUnlockMaterial(), expectedFingerprint)) {
            is OpenOutcome.Failed -> return UnlockResult.Failure(outcome.error)
            is OpenOutcome.Opened -> outcome.vault
        }
        val opened = vault.session
        val info = vault.info
        val previous = synchronized(sessionLock) { session.also { session = opened } }
        previous?.close()
        if (vault.interruptedWrite) copies.finishWrite(reference.uri)
        references.save(reference.copy(lastUsedAt = now()))
        registry?.takeIf { reference.id.isNotEmpty() }?.update(reference.id) { it.copy(fingerprint = info.fingerprint) }
        val writeGranted = files.hasWriteAccess(reference.uri)
        val editable = try {
            opened.isEditable() && writeGranted
        } catch (_: VaultSessionException) {
            false
        }
        val allowed = reference.id.isEmpty() || mayEditVault(reference.id)
        val canEdit = editable && allowed
        baseline = vault.version
        fingerprint = info.fingerprint
        generation++
        mutableDirty.value = false
        copies.promoteUnsaved(info.fingerprint)
        mutableRecovered.value = copies.hasRecovered(info.fingerprint)
        mutableState.value = VaultState.Unlocked(
            info = info,
            usedPassword = vault.usedPassword,
            usedKeyFile = vault.usedKeyFile,
            materialRetained = vault.retainedMaterial,
            canEdit = canEdit,
            writeGranted = writeGranted,
            readOnlyByPlan = editable && !allowed,
        )
        timing("unlock total", (System.nanoTime() - started) / 1_000_000)
        return UnlockResult.Success
    }

    // Changes that never reached the file are kept as an encrypted copy (auto-lock can't wait for an
    // answer), unless the user chose to discard them.
    fun lock(discardChanges: Boolean = false) {
        hideRequests.tryEmit(Unit)
        val closing = synchronized(sessionLock) { session.also { session = null } }
        val kept = fingerprint
        if (closing != null && kept != null) {
            if (discardChanges) {
                copies.removeUnsaved(kept)
            } else if (mutableDirty.value) {
                try {
                    copies.keepUnsaved(closing.prepareSave().bytes, kept)
                } catch (_: VaultSaveException) {
                }
            }
        }
        generation++
        baseline = null
        fingerprint = null
        mutableDirty.value = false
        mutableRecovered.value = false
        closing?.close()
        core.closeAllSessions()
        mutableState.value = VaultState.Locked
    }

    // Applies `block` to the open session, then writes the vault (edit = save). A failed save leaves
    // the edit in the session and the file untouched. Runs to completion even if the caller leaves.
    // The account's plan changed, or the main vault was chosen: what may be edited is judged again.
    fun refreshEditPolicy() {
        val unlocked = mutableState.value as? VaultState.Unlocked ?: return
        val id = references.load()?.id?.takeIf { it.isNotEmpty() } ?: return
        val editable = unlocked.canEdit || unlocked.readOnlyByPlan
        val allowed = mayEditVault(id)
        mutableState.value = unlocked.copy(canEdit = editable && allowed, readOnlyByPlan = editable && !allowed)
    }

    suspend fun <T> edit(block: (KeepVaultSession) -> T): EditOutcome<T> = withContext(NonCancellable) {
        saveMutex.withLock {
            val current = synchronized(sessionLock) { session } ?: throw VaultLockedException()
            if ((mutableState.value as? VaultState.Unlocked)?.readOnlyByPlan == true) {
                return@withLock EditOutcome.Invalid(VaultEditException.Reason.ReadOnly)
            }
            val value = try {
                withContext(background) { block(current) }
            } catch (error: VaultEditException) {
                if (error.reason == VaultEditException.Reason.SessionClosed || error.reason == VaultEditException.Reason.SessionNotFound) {
                    lock()
                    throw VaultLockedException()
                }
                return@withLock EditOutcome.Invalid(error.reason)
            } catch (_: VaultSessionException) {
                lock()
                throw VaultLockedException()
            }
            afterEdit(current)
            if (!mutableDirty.value) return@withLock EditOutcome.Saved(value)
            when (val outcome = writeLocked()) {
                SaveOutcome.Saved -> EditOutcome.Saved(value)
                is SaveOutcome.Failed -> EditOutcome.NotSaved(value, outcome.failure)
            }
        }
    }

    /**
     * The file was changed elsewhere (ADR 0023): reads it as it is now, combines it with the open vault and saves the
     * result. The merged state contains those bytes, so their hash becomes the baseline of the save. A clash on the
     * same entry keeps both versions, one as a copy titled with [conflictSuffix]; nothing is thrown away.
     *
     * [password] is typed text, wiped before returning; `null` uses the key file or the material this session kept.
     * Without any credential the answer is [MergeProblem.NeedsPassword] and nothing was read from the vault.
     */
    suspend fun mergeChangedFile(password: ByteArray?, conflictSuffix: String): MergeOutcome = withContext(NonCancellable) {
        try {
            saveMutex.withLock { mergeLocked(password, conflictSuffix) }
        } finally {
            password?.fill(0)
        }
    }

    private suspend fun mergeLocked(password: ByteArray?, conflictSuffix: String): MergeOutcome {
        val current = synchronized(sessionLock) { session } ?: throw VaultLockedException()
        val reference = references.load() ?: throw VaultLockedException()
        val unlocked = mutableState.value as? VaultState.Unlocked ?: throw VaultLockedException()
        if (!unlocked.canEdit) return MergeOutcome.Problem(MergeProblem.ReadOnly)
        val data = try {
            files.read(reference.uri)
        } catch (_: VaultFileException.TooLarge) {
            return MergeOutcome.Problem(MergeProblem.TooLarge)
        } catch (_: VaultFileException) {
            return MergeOutcome.Problem(MergeProblem.FileUnavailable)
        }
        val options = MergeOptions(conflictSuffix = conflictSuffix)
        // A typed password is used as it is. Otherwise the session's own key comes first: it opens the file when it was
        // saved by another device running Keep, so nothing is asked. Only a key that does not fit leads to the password.
        val report = try {
            withContext(background) {
                if (password != null) mergeWithCredentials(current, reference, unlocked, data, password, options)
                else {
                    try {
                        current.mergeWithSessionKey(data, options)
                    } catch (error: VaultMergeException) {
                        if (error.reason != VaultMergeException.Reason.InvalidCredentials) throw error
                        mergeWithCredentials(current, reference, unlocked, data, null, options)
                    }
                }
            }
        } catch (error: VaultMergeException) {
            return MergeOutcome.Problem(error.reason.toProblem(typedPassword = password != null) ?: run {
                lock()
                throw VaultLockedException()
            })
        } catch (_: VaultFileException) {
            return MergeOutcome.Problem(MergeProblem.KeyFileUnavailable)
        }
        baseline = FileVersion.of(data)
        afterEdit(current)
        val saved = if (mutableDirty.value) writeLocked() else SaveOutcome.Saved
        return MergeOutcome.Merged(report, saved)
    }

    // The other file opened with credentials: what was typed, or the material and key file this session kept. Throws
    // `InvalidCredentials` (asking for the password) when there is nothing to try.
    private suspend fun mergeWithCredentials(
        current: KeepVaultSession,
        reference: VaultReference,
        unlocked: VaultState.Unlocked,
        data: ByteArray,
        password: ByteArray?,
        options: MergeOptions,
    ): app.vazie.keep.core.MergeReport {
        val keyFile = reference.keyFileUri?.takeIf { unlocked.usedKeyFile }?.let { files.readKeyFile(it) }
        val kept = if (password == null) {
            try {
                current.unlockMaterial()?.useBytes { it.copyOf() }
            } catch (_: VaultSessionException) {
                null
            }
        } else {
            null
        }
        try {
            if (password == null && kept == null && keyFile == null) throw VaultMergeException(VaultMergeException.Reason.InvalidCredentials)

            fun attempt(typed: ByteArray?) = VaultCredentials(password = typed?.copyOf(), unlockMaterial = kept?.copyOf(), keyFile = keyFile?.copyOf()).use { credentials ->
                current.merge(data, credentials, options)
            }
            return try {
                attempt(password)
            } catch (error: VaultMergeException) {
                // A vault opened by its key file alone is unlocked with an empty password.
                if (error.reason == VaultMergeException.Reason.InvalidCredentials && password == null && kept == null) attempt(ByteArray(0)) else throw error
            }
        } finally {
            kept?.fill(0)
            keyFile?.fill(0)
        }
    }

    private fun VaultMergeException.Reason.toProblem(typedPassword: Boolean): MergeProblem? = when (this) {
        VaultMergeException.Reason.SessionNotFound, VaultMergeException.Reason.SessionClosed -> null
        VaultMergeException.Reason.ReadOnly -> MergeProblem.ReadOnly
        VaultMergeException.Reason.DifferentVault -> MergeProblem.DifferentVault
        VaultMergeException.Reason.MissingCredentials -> MergeProblem.NeedsPassword
        VaultMergeException.Reason.InvalidCredentials -> if (typedPassword) MergeProblem.WrongPassword else MergeProblem.NeedsPassword
        VaultMergeException.Reason.TooLarge -> MergeProblem.TooLarge
        VaultMergeException.Reason.InvalidKeyFile -> MergeProblem.KeyFileUnavailable
        VaultMergeException.Reason.InvalidResult, VaultMergeException.Reason.NotAVault, VaultMergeException.Reason.Unsupported,
        VaultMergeException.Reason.Damaged, VaultMergeException.Reason.Unavailable,
        -> MergeProblem.Unreadable
    }

    // Writes the session to the file if it holds unsaved edits. Saves run one at a time.
    suspend fun save(): SaveOutcome = withContext(NonCancellable) { saveMutex.withLock { writeLocked() } }

    private fun afterEdit(current: KeepVaultSession) {
        try {
            mutableDirty.value = current.isDirty()
            val info = current.info()
            mutableState.value = (mutableState.value as? VaultState.Unlocked)?.copy(info = info) ?: mutableState.value
        } catch (_: VaultSessionException) {
            mutableDirty.value = true
        }
        mutableRevision.value++
    }

    private suspend fun writeLocked(): SaveOutcome {
        val current = synchronized(sessionLock) { session }
        val reference = references.load()
        val expected = baseline
        val kept = fingerprint
        if (current == null || reference == null || expected == null || kept == null) return SaveOutcome.Failed(SaveFailure.Locked)
        if (!current.isDirtySafely()) {
            mutableDirty.value = false
            return SaveOutcome.Saved
        }
        if ((mutableState.value as? VaultState.Unlocked)?.canEdit != true) return SaveOutcome.Failed(SaveFailure.NotWritable)
        val ticket = generation
        val started = System.nanoTime()
        return coroutineScope {
            val progress = launch {
                delay(progressDelay)
                mutableSaving.value = true
            }
            try {
                val snapshot = try {
                    withContext(background) { current.prepareSave() }
                } catch (error: VaultSaveException) {
                    return@coroutineScope SaveOutcome.Failed(error.reason.toFailure())
                }
                timing("save: serialize + encrypt + verify", (System.nanoTime() - started) / 1_000_000)
                val writeStarted = System.nanoTime()
                val written = try {
                    writer.replace(reference.uri, expected, snapshot.bytes) { copies.keepBackup(it, kept) }
                } catch (error: WriteException) {
                    copies.keepUnsaved(snapshot.bytes, kept)
                    timing("save failed", (System.nanoTime() - started) / 1_000_000)
                    return@coroutineScope SaveOutcome.Failed(error.failure)
                }
                timing("save: write + verify", (System.nanoTime() - writeStarted) / 1_000_000)
                timing("save total", (System.nanoTime() - started) / 1_000_000)
                if (ticket == generation) {
                    baseline = written
                    try {
                        current.markSaved(snapshot.generation)
                    } catch (_: VaultSessionException) {
                    }
                    mutableDirty.value = current.isDirtySafely()
                    if (!mutableDirty.value) copies.removeUnsaved(kept)
                }
                SaveOutcome.Saved
            } finally {
                progress.cancel()
                mutableSaving.value = false
            }
        }
    }

    private fun KeepVaultSession.isDirtySafely() = try {
        isDirty()
    } catch (_: VaultSessionException) {
        false
    }

    // Writes the encrypted session to a document the user just created. Nothing in the vault changes.
    suspend fun saveCopyTo(uri: String): CopyOutcome {
        val current = synchronized(sessionLock) { session } ?: return CopyOutcome.Failed
        return try {
            val snapshot = withContext(background) { current.prepareSave() }
            writer.writeCopy(uri, snapshot.bytes)
            CopyOutcome.Written
        } catch (_: VaultSaveException) {
            CopyOutcome.Failed
        } catch (_: WriteException) {
            CopyOutcome.Failed
        }
    }

    suspend fun saveRecoveredCopyTo(uri: String): CopyOutcome {
        val kept = fingerprint ?: return CopyOutcome.Failed
        val bytes = copies.recovered(kept) ?: return CopyOutcome.Failed
        return try {
            writer.writeCopy(uri, bytes)
            CopyOutcome.Written
        } catch (_: WriteException) {
            CopyOutcome.Failed
        }
    }

    fun discardRecoveredCopy() {
        fingerprint?.let(copies::removeRecovered)
        mutableRecovered.value = false
    }

    // A save was cut off and left a file that doesn't open: put the complete bytes of that save back.
    suspend fun restoreInterruptedWrite(): RestoreOutcome = withContext(NonCancellable) {
        val reference = references.load() ?: return@withContext RestoreOutcome.NothingToRestore
        val pending = copies.pendingWrite(reference.uri) ?: return@withContext RestoreOutcome.NothingToRestore
        try {
            val current = files.read(reference.uri)
            writer.restore(reference.uri, FileVersion.of(current), pending)
            copies.finishWrite(reference.uri)
            RestoreOutcome.Restored
        } catch (error: WriteException) {
            RestoreOutcome.Failed(error.failure)
        } catch (_: VaultFileException) {
            RestoreOutcome.Failed(SaveFailure.Unavailable)
        }
    }

    // The persisted grant of a vault opened before saving existed is read-only. Picking the same
    // file again upgrades it without locking.
    fun regrantWriteAccess(uri: String): Boolean {
        val reference = references.load() ?: return false
        if (reference.uri != uri) return false
        files.persistPermission(uri, write = true)
        val granted = files.hasWriteAccess(uri)
        if (granted) {
            mutableState.value = (mutableState.value as? VaultState.Unlocked)?.copy(canEdit = true, writeGranted = true) ?: mutableState.value
        }
        return granted
    }

    suspend fun providerCapabilities(): ProviderCapabilities? =
        references.load()?.let { writer.capabilities(it.uri) }

    fun requestHideSecrets() {
        hideRequests.tryEmit(Unit)
    }

    fun groups(): List<VaultGroup> = withSession { it.groups() }

    fun entries(): List<EntrySummary> = withSession { it.entries() }

    fun passkeys(): List<EntrySummary> = withSession { it.passkeys(null) }

    fun siteIconHosts(): List<String> = withSession { it.siteIconHosts() }

    fun customIcon(id: String): ByteArray? = withSession { it.customIcon(id) }

    fun siteIconHost(id: EntryId): String? = withSession { it.siteIconHost(id) }

    fun search(query: String): List<EntrySummary> = measured("search") { withSession { it.search(query) } }

    fun entry(id: EntryId): EntryDetail = measured("entry open") { withSession { it.entry(id) } }

    private inline fun <T> measured(label: String, block: () -> T): T {
        val started = System.nanoTime()
        try {
            return block()
        } finally {
            timing(label, (System.nanoTime() - started) / 1_000_000)
        }
    }

    fun reveal(id: EntryId, field: SecretField): SecretValue = withSession { it.reveal(id, field) }

    suspend fun exportAttachment(id: EntryId, name: String, uri: String): CopyOutcome = try {
        val bytes = withContext(background) { withSession { it.attachment(id, name) } }.use { secret -> secret.useBytes { it.copyOf() } }
        try {
            files.writeNew(uri, bytes)
        } finally {
            bytes.fill(0)
        }
        CopyOutcome.Written
    } catch (_: VaultSessionException) {
        files.deleteQuietly(uri)
        CopyOutcome.Failed
    } catch (_: VaultFileException) {
        files.deleteQuietly(uri)
        CopyOutcome.Failed
    }

    suspend fun readAttachmentFile(uri: String): AttachmentFile = try {
        val bytes = files.readAttachment(uri)
        AttachmentFile.Read(files.displayName(uri) ?: DEFAULT_ATTACHMENT_NAME, bytes)
    } catch (_: VaultFileException.TooLarge) {
        AttachmentFile.TooLarge
    } catch (_: VaultFileException) {
        AttachmentFile.Unavailable
    }

    fun totpCode(id: EntryId, unixSeconds: Long, access: app.vazie.keep.core.KeepAccess): app.vazie.keep.core.TotpCode =
        withSession { it.totpCode(id, unixSeconds, access) }

    fun history(id: EntryId, access: KeepAccess): List<HistoryVersion> = withHistory { it.history(id, access) }

    fun historyEntry(id: EntryId, index: Int, access: KeepAccess): EntryDetail = withHistory { it.historyEntry(id, index, access) }

    fun revealHistory(id: EntryId, index: Int, field: SecretField, access: KeepAccess): SecretValue =
        withHistory { it.revealHistory(id, index, field, access) }

    private inline fun <T> withHistory(block: (KeepVaultSession) -> T): T {
        val current = synchronized(sessionLock) { session } ?: throw VaultLockedException()
        return try {
            block(current)
        } catch (error: VaultHistoryException) {
            if (error.reason == VaultHistoryException.Reason.SessionClosed || error.reason == VaultHistoryException.Reason.SessionNotFound) {
                lock()
                throw VaultLockedException()
            }
            throw error
        }
    }

    private inline fun <T> withSession(block: (KeepVaultSession) -> T): T {
        val current = synchronized(sessionLock) { session } ?: throw VaultLockedException()
        return try {
            block(current)
        } catch (error: VaultSessionException) {
            if (error.reason == VaultSessionException.Reason.SessionClosed || error.reason == VaultSessionException.Reason.SessionNotFound) {
                lock()
                throw VaultLockedException()
            }
            throw error
        }
    }

    private companion object {
        const val DEFAULT_NAME = "Vault.kdbx"
        const val DEFAULT_KEY_FILE_NAME = "Key file"
        const val DEFAULT_ATTACHMENT_NAME = "attachment"
    }
}

private fun VaultSaveException.Reason.toFailure() = when (this) {
    VaultSaveException.Reason.ReadOnly -> SaveFailure.ReadOnly
    VaultSaveException.Reason.SessionClosed, VaultSaveException.Reason.SessionNotFound -> SaveFailure.Locked
    VaultSaveException.Reason.VerificationFailed -> SaveFailure.VerificationFailed
    VaultSaveException.Reason.RandomUnavailable, VaultSaveException.Reason.EncryptionFailed -> SaveFailure.EncryptionFailed
}
