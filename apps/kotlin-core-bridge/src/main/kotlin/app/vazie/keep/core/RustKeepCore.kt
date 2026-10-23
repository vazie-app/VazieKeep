package app.vazie.keep.core

import app.vazie.keep.core.ffi.ChangedFieldDto
import app.vazie.keep.core.ffi.CustomFieldDraftDto
import app.vazie.keep.core.ffi.CustomFieldDto
import app.vazie.keep.core.ffi.DeleteOutcomeDto
import app.vazie.keep.core.ffi.EntryDetailDto
import app.vazie.keep.core.ffi.EntryDraftDto
import app.vazie.keep.core.ffi.EntrySummaryDto
import app.vazie.keep.core.ffi.HistoryVersionDto
import app.vazie.keep.core.ffi.ImportFileException
import app.vazie.keep.core.ffi.ImportPlanHandle
import app.vazie.keep.core.ffi.ImportSourceDto
import app.vazie.keep.core.ffi.parseImport as ffiParseImport
import app.vazie.keep.core.ffi.OpenVaultException
import app.vazie.keep.core.ffi.HostMatchDto
import app.vazie.keep.core.ffi.PasswordDto
import app.vazie.keep.core.ffi.PasswordOptionsDto
import app.vazie.keep.core.ffi.SecretFieldDto
import app.vazie.keep.core.ffi.TextDto
import app.vazie.keep.core.ffi.TotpAlgorithmDto
import app.vazie.keep.core.ffi.TotpCodeDto
import app.vazie.keep.core.ffi.TotpSetupDto
import app.vazie.keep.core.ffi.totpNormalize
import app.vazie.keep.core.ffi.VaultTotpException as FfiTotpException
import app.vazie.keep.core.ffi.VaultCredentialsDto
import app.vazie.keep.core.ffi.VaultFormatDto
import app.vazie.keep.core.ffi.VaultSessionHandle
import app.vazie.keep.core.ffi.CreateVaultConfigDto
import app.vazie.keep.core.ffi.CreateVaultException
import app.vazie.keep.core.ffi.SecurityPresetDto
import app.vazie.keep.core.ffi.benchmarkKdf as ffiBenchmarkKdf
import app.vazie.keep.core.ffi.createVault as ffiCreateVault
import app.vazie.keep.core.ffi.generateKeyFile as ffiGenerateKeyFile
import app.vazie.keep.core.ffi.securityPresetBalanced
import app.vazie.keep.core.ffi.securityPresetStronger
import app.vazie.keep.core.ffi.closeAllVaultSessions
import app.vazie.keep.core.ffi.coreInfo
import app.vazie.keep.core.ffi.generatePassword as ffiGeneratePassword
import app.vazie.keep.core.ffi.matchHost as ffiMatchHost
import app.vazie.keep.core.ffi.AccessDto
import app.vazie.keep.core.ffi.AccessStateDto
import app.vazie.keep.core.ffi.LeaseDto
import app.vazie.keep.core.ffi.LeaseTierDto
import app.vazie.keep.core.ffi.LeaseVerifyException
import app.vazie.keep.core.ffi.PlusFeatureDto
import app.vazie.keep.core.ffi.TrustedKeyDto
import app.vazie.keep.core.ffi.canUsePlusFeature
import app.vazie.keep.core.ffi.evaluateKeepAccess
import app.vazie.keep.core.ffi.verifyKeepLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import app.vazie.keep.core.ffi.PasswordGeneratorException as FfiGeneratorException
import app.vazie.keep.core.ffi.MergeOptionsDto
import app.vazie.keep.core.ffi.MergeReportDto
import app.vazie.keep.core.ffi.VaultEditException as FfiEditException
import app.vazie.keep.core.ffi.VaultMergeException as FfiMergeException
import app.vazie.keep.core.ffi.VaultHistoryException as FfiHistoryException
import app.vazie.keep.core.ffi.VaultSaveException as FfiSaveException
import app.vazie.keep.core.ffi.VaultSessionException as FfiSessionException
import app.vazie.keep.core.ffi.detectVaultFormat as ffiDetectVaultFormat
import app.vazie.keep.core.ffi.openVault as ffiOpenVault

object RustKeepCore : KeepCore {
    override fun info(): CoreInfo = coreInfo().let { CoreInfo(name = it.name, version = it.version) }

    override fun detectVaultFormat(header: ByteArray): VaultFormat =
        when (val dto = ffiDetectVaultFormat(header)) {
            is VaultFormatDto.Kdbx -> VaultFormat.Kdbx(dto.major.toInt(), dto.minor.toInt())
            VaultFormatDto.KeePass1 -> VaultFormat.KeePass1
            VaultFormatDto.Unknown -> VaultFormat.Unknown
        }

    override suspend fun openVault(data: ByteArray, credentials: VaultCredentials): KeepVaultSession =
        withContext(Dispatchers.Default) {
            credentials.use {
                val password = it.password?.let(PasswordDto::Text) ?: it.unlockMaterial?.let(PasswordDto::UnlockMaterial)
                try {
                    RustVaultSession(ffiOpenVault(data, VaultCredentialsDto(password, it.keyFile, it.retainUnlockMaterial)))
                } catch (error: OpenVaultException) {
                    throw VaultOpenException(error.reason())
                }
            }
        }

    override val balancedPreset: KdfSettings
        get() = securityPresetBalanced().let { KdfSettings(it.memoryKib.toInt(), it.iterations.toInt(), it.parallelism.toInt()) }

    override val strongerPreset: KdfSettings
        get() = securityPresetStronger().let { KdfSettings(it.memoryKib.toInt(), it.iterations.toInt(), it.parallelism.toInt()) }

    override suspend fun createVault(name: String, kdf: KdfSettings, credentials: VaultCredentials): CreatedVault =
        withContext(Dispatchers.Default) {
            credentials.use {
                val password = it.password?.let(PasswordDto::Text)
                try {
                    val created = ffiCreateVault(
                        CreateVaultConfigDto(name, kdf.memoryKib.toUInt(), kdf.iterations.toUInt(), kdf.parallelism.toUInt()),
                        VaultCredentialsDto(password, it.keyFile, false),
                    )
                    CreatedVault(created.bytes, created.fingerprint)
                } catch (error: CreateVaultException) {
                    throw VaultCreateException(error.reason())
                }
            }
        }

    override suspend fun benchmarkKdf(kdf: KdfSettings): Int = withContext(Dispatchers.Default) {
        try {
            ffiBenchmarkKdf(SecurityPresetDto(kdf.memoryKib.toUInt(), kdf.iterations.toUInt(), kdf.parallelism.toUInt())).toInt()
        } catch (error: CreateVaultException) {
            throw VaultCreateException(error.reason())
        }
    }

    override fun generateKeyFile(): SecretValue = try {
        SecretValue(ffiGenerateKeyFile().utf8)
    } catch (error: CreateVaultException) {
        throw VaultCreateException(error.reason())
    }

    override fun normalizeTotp(input: ByteArray, access: KeepAccess): TotpSetup = try {
        totpNormalize(input, access.toDto()).toSetup()
    } catch (error: FfiTotpException) {
        throw TotpException(error.reason())
    }

    override fun verifyLease(token: String, keys: List<LeaseKey>): KeepLease = try {
        verifyKeepLease(token, keys.map { TrustedKeyDto(it.kid, it.publicKey) }).toLease()
    } catch (error: LeaseVerifyException) {
        throw app.vazie.keep.core.LeaseVerifyException(
            when (error) {
                is LeaseVerifyException.Malformed -> app.vazie.keep.core.LeaseVerifyException.Reason.Malformed
                is LeaseVerifyException.UnsupportedAlgorithm -> app.vazie.keep.core.LeaseVerifyException.Reason.UnsupportedAlgorithm
                is LeaseVerifyException.UnknownKey -> app.vazie.keep.core.LeaseVerifyException.Reason.UnknownKey
                is LeaseVerifyException.BadSignature -> app.vazie.keep.core.LeaseVerifyException.Reason.BadSignature
                is LeaseVerifyException.Unsupported -> app.vazie.keep.core.LeaseVerifyException.Reason.Unsupported
                is LeaseVerifyException.Inconsistent -> app.vazie.keep.core.LeaseVerifyException.Reason.Inconsistent
            },
        )
    }

    override fun evaluateAccess(lease: KeepLease?, now: Long, lastObserved: Long): KeepAccess =
        evaluateKeepAccess(lease?.toDto(), now, lastObserved).toAccess()

    override fun canUse(feature: PlusFeature, access: KeepAccess): Boolean =
        canUsePlusFeature(PlusFeatureDto.valueOf(feature.toDtoName()), access.toDto())

    private fun CreateVaultException.reason() = when (this) {
        is CreateVaultException.EmptyName -> VaultCreateException.Reason.EmptyName
        is CreateVaultException.InvalidName -> VaultCreateException.Reason.InvalidName
        is CreateVaultException.InvalidSettings -> VaultCreateException.Reason.InvalidSettings
        is CreateVaultException.MissingCredentials -> VaultCreateException.Reason.MissingCredentials
        is CreateVaultException.InvalidKeyFile -> VaultCreateException.Reason.InvalidKeyFile
        is CreateVaultException.Unavailable -> VaultCreateException.Reason.Unavailable
    }

    override fun closeAllSessions(): Int = closeAllVaultSessions().toInt()

    override fun matchHost(entryHost: String, requestHost: String): HostMatch =
        when (ffiMatchHost(entryHost, requestHost)) {
            HostMatchDto.EXACT -> HostMatch.Exact
            HostMatchDto.SUBDOMAIN -> HostMatch.Subdomain
            HostMatchDto.NONE -> HostMatch.None
        }

    override fun parseImport(data: ByteArray): ImportPlan = try {
        RustImportPlan(ffiParseImport(data))
    } catch (error: ImportFileException) {
        throw ImportException(
            when (error) {
                is ImportFileException.Empty -> ImportException.Reason.Empty
                is ImportFileException.TooLarge -> ImportException.Reason.TooLarge
                is ImportFileException.Unrecognized -> ImportException.Reason.Unrecognized
                is ImportFileException.Encrypted -> ImportException.Reason.Encrypted
                is ImportFileException.Malformed -> ImportException.Reason.Malformed
            },
        )
    }

    override fun generatePassword(options: PasswordOptions): SecretValue = try {
        SecretValue(
            ffiGeneratePassword(
                PasswordOptionsDto(
                    options.length.toUInt(),
                    options.lowercase,
                    options.uppercase,
                    options.digits,
                    options.symbols,
                    options.excludeAmbiguous,
                ),
            ).utf8,
        )
    } catch (error: FfiGeneratorException) {
        throw PasswordGeneratorException(
            when (error) {
                is FfiGeneratorException.NoCharacterSets -> PasswordGeneratorException.Reason.NoCharacterSets
                is FfiGeneratorException.LengthOutOfRange -> PasswordGeneratorException.Reason.LengthOutOfRange
                is FfiGeneratorException.RandomUnavailable -> PasswordGeneratorException.Reason.RandomUnavailable
            },
        )
    }

    private fun OpenVaultException.reason() = when (this) {
        is OpenVaultException.NotAVault -> VaultOpenException.Reason.NotAVault
        is OpenVaultException.Unsupported -> VaultOpenException.Reason.Unsupported
        is OpenVaultException.MissingCredentials -> VaultOpenException.Reason.MissingCredentials
        is OpenVaultException.InvalidCredentials -> VaultOpenException.Reason.InvalidCredentials
        is OpenVaultException.InvalidKeyFile -> VaultOpenException.Reason.InvalidKeyFile
        is OpenVaultException.Damaged -> VaultOpenException.Reason.Damaged
        is OpenVaultException.TooLarge -> VaultOpenException.Reason.TooLarge
        is OpenVaultException.TooManySessions -> VaultOpenException.Reason.TooManySessions
        is OpenVaultException.Unavailable -> VaultOpenException.Reason.Unavailable
    }
}

private class RustVaultSession(private val handle: VaultSessionHandle) : KeepVaultSession {
    private val closed = AtomicBoolean(false)

    override val isClosed: Boolean get() = closed.get()

    override fun info(): VaultInfo = call {
        it.info().let { dto ->
            VaultInfo(
                name = dto.name,
                formatMajor = dto.formatMajor.toInt(),
                formatMinor = dto.formatMinor.toInt(),
                rootGroupId = GroupId(dto.rootGroupId),
                recycleBinId = dto.recycleBinId?.let(::GroupId),
                groupCount = dto.groupCount.toInt(),
                entryCount = dto.entryCount.toInt(),
                fingerprint = dto.fingerprint,
            )
        }
    }

    override fun groups(): List<VaultGroup> = call { handle ->
        handle.groups().map { VaultGroup(GroupId(it.id), it.parentId?.let(::GroupId), it.name, it.icon.toInt()) }
    }

    override fun entries(): List<EntrySummary> = call { handle -> handle.entries().map { it.toSummary() } }

    override fun search(query: String): List<EntrySummary> = call { handle -> handle.search(query).map { it.toSummary() } }

    override fun entry(id: EntryId): EntryDetail = call { it.entry(id.value).toDetail() }

    override fun reveal(id: EntryId, field: SecretField): SecretValue = call {
        SecretValue(it.reveal(id.value, field.toDto()).utf8)
    }

    override fun totpCode(id: EntryId, unixSeconds: Long, access: KeepAccess): TotpCode {
        if (closed.get()) throw TotpException(TotpException.Reason.SessionClosed)
        return try {
            handle.totpCode(id.value, unixSeconds.toULong(), access.toDto()).toCode()
        } catch (error: FfiTotpException) {
            throw TotpException(error.reason())
        } catch (_: IllegalStateException) {
            throw TotpException(TotpException.Reason.SessionClosed)
        }
    }

    override fun unlockMaterial(): UnlockMaterial? = call { it.unlockMaterial()?.let { dto -> UnlockMaterial(dto.bytes) } }

    override fun isEditable(): Boolean = call { it.isEditable() }

    override fun isDirty(): Boolean = call { it.isDirty() }

    override fun createEntry(group: GroupId, draft: EntryDraft): EntryId =
        edit { EntryId(it.createEntry(group.value, draft.toDto())) }

    override fun updateEntry(id: EntryId, draft: EntryDraft) = edit { it.updateEntry(id.value, draft.toDto()) }

    override fun setFavorite(id: EntryId, favorite: Boolean) = edit { it.setFavorite(id.value, favorite) }

    override fun attachment(id: EntryId, name: String): SecretValue = call { SecretValue(it.attachment(id.value, name).utf8) }

    override fun addAttachment(id: EntryId, name: String, data: ByteArray, access: KeepAccess): String =
        edit { it.addAttachment(id.value, name, data, access.toDto()) }

    override fun removeAttachment(id: EntryId, name: String) = edit { it.removeAttachment(id.value, name) }

    override fun importPlan(group: GroupId, plan: ImportPlan): ImportOutcome = edit {
        val handle = (plan as? RustImportPlan)?.handle ?: throw VaultEditException(VaultEditException.Reason.InvalidArgument)
        it.importPlan(group.value, handle).let { outcome -> ImportOutcome(outcome.entries.toInt(), outcome.groups.toInt()) }
    }

    override fun merge(data: ByteArray, credentials: VaultCredentials, options: MergeOptions): MergeReport {
        if (closed.get()) {
            credentials.close()
            throw VaultMergeException(VaultMergeException.Reason.SessionClosed)
        }
        return credentials.use {
            val password = it.password?.let(PasswordDto::Text) ?: it.unlockMaterial?.let(PasswordDto::UnlockMaterial)
            try {
                handle.merge(
                    data,
                    VaultCredentialsDto(password, it.keyFile, false),
                    MergeOptionsDto(options.conflictSuffix, options.allowUnrelated),
                ).toReport()
            } catch (error: FfiMergeException) {
                throw VaultMergeException(error.reason())
            } catch (_: IllegalStateException) {
                throw VaultMergeException(VaultMergeException.Reason.SessionClosed)
            }
        }
    }

    override fun mergeWithSessionKey(data: ByteArray, options: MergeOptions): MergeReport {
        if (closed.get()) throw VaultMergeException(VaultMergeException.Reason.SessionClosed)
        return try {
            handle.mergeWithSessionKey(data, MergeOptionsDto(options.conflictSuffix, options.allowUnrelated)).toReport()
        } catch (error: FfiMergeException) {
            throw VaultMergeException(error.reason())
        } catch (_: IllegalStateException) {
            throw VaultMergeException(VaultMergeException.Reason.SessionClosed)
        }
    }

    override fun history(id: EntryId, access: KeepAccess): List<HistoryVersion> =
        history { handle -> handle.history(id.value, access.toDto()).map { it.toVersion() } }

    override fun historyEntry(id: EntryId, index: Int, access: KeepAccess): EntryDetail =
        history { it.historyEntry(id.value, index.toUInt(), access.toDto()).toDetail() }

    override fun revealHistory(id: EntryId, index: Int, field: SecretField, access: KeepAccess): SecretValue =
        history { SecretValue(it.revealHistory(id.value, index.toUInt(), field.toDto(), access.toDto()).utf8) }

    override fun restoreHistory(id: EntryId, index: Int, access: KeepAccess) =
        edit { it.restoreHistory(id.value, index.toUInt(), access.toDto()) }

    override fun deleteEntry(id: EntryId, permanent: Boolean): DeleteOutcome =
        edit { it.deleteEntry(id.value, permanent).toOutcome() }

    override fun moveEntry(id: EntryId, group: GroupId) = edit { it.moveEntry(id.value, group.value) }

    override fun createGroup(parent: GroupId, name: String): GroupId = edit { GroupId(it.createGroup(parent.value, name)) }

    override fun renameGroup(id: GroupId, name: String) = edit { it.renameGroup(id.value, name) }

    override fun setGroupIcon(id: GroupId, icon: Int) = edit { it.setGroupIcon(id.value, icon.toUInt()) }

    override fun setEntryIcon(id: EntryId, icon: Int) = edit { it.setEntryIcon(id.value, icon.toUInt()) }

    override fun assignIconsByUrl(): Int = edit { it.assignIconsByUrl().toInt() }

    override fun createPasskey(group: GroupId, rpId: String, rpName: String, userName: String, userHandle: ByteArray): CreatedPasskey = passkey {
        val made = it.createPasskey(group.value, rpId, rpName, userName, userHandle)
        CreatedPasskey(EntryId(made.entryId), made.credentialId, made.publicKey, made.authenticatorData, made.attestationObject)
    }

    override fun passkeys(rpId: String?): List<EntrySummary> = call { handle -> handle.passkeys(rpId.orEmpty()).map { it.toSummary() } }

    override fun signPasskey(id: EntryId, rpId: String, clientDataHash: ByteArray): PasskeyAssertion = passkey {
        val signed = it.signPasskey(id.value, rpId, clientDataHash)
        PasskeyAssertion(signed.credentialId, signed.userHandle, signed.authenticatorData, signed.signature)
    }

    override fun removePasskey(id: EntryId) = edit { it.removePasskey(id.value) }

    private inline fun <T> passkey(block: (VaultSessionHandle) -> T): T {
        if (closed.get()) throw VaultPasskeyException(VaultPasskeyException.Reason.SessionClosed)
        return try {
            block(handle)
        } catch (error: app.vazie.keep.core.ffi.VaultPasskeyException) {
            throw VaultPasskeyException(
                when (error) {
                    is app.vazie.keep.core.ffi.VaultPasskeyException.SessionNotFound, is app.vazie.keep.core.ffi.VaultPasskeyException.SessionClosed -> VaultPasskeyException.Reason.SessionClosed
                    is app.vazie.keep.core.ffi.VaultPasskeyException.ReadOnly -> VaultPasskeyException.Reason.ReadOnly
                    is app.vazie.keep.core.ffi.VaultPasskeyException.InvalidRequest, is app.vazie.keep.core.ffi.VaultPasskeyException.GroupNotFound -> VaultPasskeyException.Reason.InvalidRequest
                    is app.vazie.keep.core.ffi.VaultPasskeyException.NoPasskey -> VaultPasskeyException.Reason.NoPasskey
                    is app.vazie.keep.core.ffi.VaultPasskeyException.InvalidKey -> VaultPasskeyException.Reason.InvalidKey
                    is app.vazie.keep.core.ffi.VaultPasskeyException.Failed -> VaultPasskeyException.Reason.Failed
                },
            )
        } catch (_: IllegalStateException) {
            throw VaultPasskeyException(VaultPasskeyException.Reason.SessionClosed)
        }
    }

    override fun siteIconHosts(): List<String> = call { it.siteIconHosts() }

    override fun siteIconHost(id: EntryId): String? = call { it.siteIconHost(id.value) }

    override fun setSiteIcons(icons: List<SiteIcon>): Int =
        edit { handle -> handle.setSiteIcons(icons.map { app.vazie.keep.core.ffi.SiteIconDto(it.host, it.png) }).toInt() }

    override fun customIcon(id: String): ByteArray? = call { it.customIcon(id) }

    override fun deleteGroup(id: GroupId, recursive: Boolean, permanent: Boolean): DeleteOutcome =
        edit { it.deleteGroup(id.value, recursive, permanent).toOutcome() }

    override fun prepareSave(): SaveSnapshot {
        if (closed.get()) throw VaultSaveException(VaultSaveException.Reason.SessionClosed)
        return try {
            handle.prepareSave().let { SaveSnapshot(it.bytes, it.generation.toLong()) }
        } catch (error: FfiSaveException) {
            throw VaultSaveException(error.reason())
        } catch (_: IllegalStateException) {
            throw VaultSaveException(VaultSaveException.Reason.SessionClosed)
        }
    }

    override fun markSaved(generation: Long): Boolean = call { it.markSaved(generation.toULong()) }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            handle.closeSession()
            handle.close()
        }
    }

    override fun toString() = "KeepVaultSession(<opaque>)"

    private inline fun <T> call(block: (VaultSessionHandle) -> T): T {
        if (closed.get()) throw VaultSessionException(VaultSessionException.Reason.SessionClosed)
        return try {
            block(handle)
        } catch (error: FfiSessionException) {
            throw VaultSessionException(error.reason())
        } catch (_: IllegalStateException) {
            throw VaultSessionException(VaultSessionException.Reason.SessionClosed)
        }
    }

    private inline fun <T> edit(block: (VaultSessionHandle) -> T): T {
        if (closed.get()) throw VaultEditException(VaultEditException.Reason.SessionClosed)
        return try {
            block(handle)
        } catch (error: FfiEditException) {
            throw VaultEditException(error.reason())
        } catch (_: IllegalStateException) {
            throw VaultEditException(VaultEditException.Reason.SessionClosed)
        }
    }

    private inline fun <T> history(block: (VaultSessionHandle) -> T): T {
        if (closed.get()) throw VaultHistoryException(VaultHistoryException.Reason.SessionClosed)
        return try {
            block(handle)
        } catch (error: FfiHistoryException) {
            throw VaultHistoryException(error.reason())
        } catch (_: IllegalStateException) {
            throw VaultHistoryException(VaultHistoryException.Reason.SessionClosed)
        }
    }

    private fun FfiHistoryException.reason() = when (this) {
        is FfiHistoryException.SessionNotFound -> VaultHistoryException.Reason.SessionNotFound
        is FfiHistoryException.SessionClosed -> VaultHistoryException.Reason.SessionClosed
        is FfiHistoryException.EntryNotFound -> VaultHistoryException.Reason.EntryNotFound
        is FfiHistoryException.FieldNotFound -> VaultHistoryException.Reason.FieldNotFound
        is FfiHistoryException.VersionNotFound -> VaultHistoryException.Reason.VersionNotFound
        is FfiHistoryException.NotEntitled -> VaultHistoryException.Reason.NotEntitled
        is FfiHistoryException.InvalidArgument -> VaultHistoryException.Reason.InvalidArgument
    }

    private fun FfiEditException.reason() = when (this) {
        is FfiEditException.SessionNotFound -> VaultEditException.Reason.SessionNotFound
        is FfiEditException.SessionClosed -> VaultEditException.Reason.SessionClosed
        is FfiEditException.ReadOnly -> VaultEditException.Reason.ReadOnly
        is FfiEditException.EntryNotFound -> VaultEditException.Reason.EntryNotFound
        is FfiEditException.GroupNotFound -> VaultEditException.Reason.GroupNotFound
        is FfiEditException.GroupNotEmpty -> VaultEditException.Reason.GroupNotEmpty
        is FfiEditException.RootGroup -> VaultEditException.Reason.RootGroup
        is FfiEditException.InvalidFieldName -> VaultEditException.Reason.InvalidFieldName
        is FfiEditException.InvalidText -> VaultEditException.Reason.InvalidText
        is FfiEditException.InvalidArgument -> VaultEditException.Reason.InvalidArgument
        is FfiEditException.RandomUnavailable -> VaultEditException.Reason.RandomUnavailable
        is FfiEditException.AttachmentNotFound -> VaultEditException.Reason.AttachmentNotFound
        is FfiEditException.AttachmentTooLarge -> VaultEditException.Reason.AttachmentTooLarge
        is FfiEditException.NotEntitled -> VaultEditException.Reason.NotEntitled
        is FfiEditException.VersionNotFound -> VaultEditException.Reason.VersionNotFound
    }

    private fun MergeReportDto.toReport() = MergeReport(
        changed = changed,
        entriesAdded = entriesAdded.toInt(),
        entriesRestored = entriesRestored.toInt(),
        entriesUpdated = entriesUpdated.toInt(),
        entriesMoved = entriesMoved.toInt(),
        entriesDeleted = entriesDeleted.toInt(),
        historiesMerged = historiesMerged.toInt(),
        historyVersionsAdded = historyVersionsAdded.toInt(),
        groupsAdded = groupsAdded.toInt(),
        groupsUpdated = groupsUpdated.toInt(),
        groupsMoved = groupsMoved.toInt(),
        groupsDeleted = groupsDeleted.toInt(),
        conflicts = conflicts.map(::EntryId),
        iconsAdded = iconsAdded.toInt(),
        deletionRecordsAdded = deletionRecordsAdded.toInt(),
        settingsUpdated = settingsUpdated,
    )

    private fun FfiMergeException.reason() = when (this) {
        is FfiMergeException.SessionNotFound -> VaultMergeException.Reason.SessionNotFound
        is FfiMergeException.SessionClosed -> VaultMergeException.Reason.SessionClosed
        is FfiMergeException.ReadOnly -> VaultMergeException.Reason.ReadOnly
        is FfiMergeException.DifferentVault -> VaultMergeException.Reason.DifferentVault
        is FfiMergeException.InvalidResult -> VaultMergeException.Reason.InvalidResult
        is FfiMergeException.NotAVault -> VaultMergeException.Reason.NotAVault
        is FfiMergeException.Unsupported -> VaultMergeException.Reason.Unsupported
        is FfiMergeException.MissingCredentials -> VaultMergeException.Reason.MissingCredentials
        is FfiMergeException.InvalidCredentials -> VaultMergeException.Reason.InvalidCredentials
        is FfiMergeException.InvalidKeyFile -> VaultMergeException.Reason.InvalidKeyFile
        is FfiMergeException.Damaged -> VaultMergeException.Reason.Damaged
        is FfiMergeException.TooLarge -> VaultMergeException.Reason.TooLarge
        is FfiMergeException.Unavailable -> VaultMergeException.Reason.Unavailable
    }

    private fun FfiSaveException.reason() = when (this) {
        is FfiSaveException.SessionNotFound -> VaultSaveException.Reason.SessionNotFound
        is FfiSaveException.SessionClosed -> VaultSaveException.Reason.SessionClosed
        is FfiSaveException.ReadOnly -> VaultSaveException.Reason.ReadOnly
        is FfiSaveException.RandomUnavailable -> VaultSaveException.Reason.RandomUnavailable
        is FfiSaveException.EncryptionFailed -> VaultSaveException.Reason.EncryptionFailed
        is FfiSaveException.VerificationFailed -> VaultSaveException.Reason.VerificationFailed
    }

    private fun FfiSessionException.reason() = when (this) {
        is FfiSessionException.SessionNotFound -> VaultSessionException.Reason.SessionNotFound
        is FfiSessionException.SessionClosed -> VaultSessionException.Reason.SessionClosed
        is FfiSessionException.EntryNotFound -> VaultSessionException.Reason.EntryNotFound
        is FfiSessionException.FieldNotFound -> VaultSessionException.Reason.FieldNotFound
        is FfiSessionException.InvalidArgument -> VaultSessionException.Reason.InvalidArgument
    }
}

internal class RustImportPlan(val handle: ImportPlanHandle) : ImportPlan {
    override fun summary(): ImportSummary = handle.summary().let {
        ImportSummary(
            source = when (it.source) {
                ImportSourceDto.CSV -> ImportSource.Csv
                ImportSourceDto.ONE_PASSWORD_CSV -> ImportSource.OnePasswordCsv
                ImportSourceDto.ONE_PASSWORD1PUX -> ImportSource.OnePassword1pux
                ImportSourceDto.BITWARDEN_JSON -> ImportSource.BitwardenJson
                ImportSourceDto.BITWARDEN_CSV -> ImportSource.BitwardenCsv
            },
            entries = it.entries.toInt(),
            folders = it.folders.toInt(),
            withTotp = it.withTotp.toInt(),
            skipped = it.skipped.toInt(),
        )
    }

    override fun close() = handle.close()

    override fun toString() = "ImportPlan(<opaque>)"
}

private fun EntrySummaryDto.toSummary() = EntrySummary(
    id = EntryId(id),
    groupId = GroupId(groupId),
    title = title.toText(),
    username = username.toText(),
    url = url.toText(),
    tags = tags,
    hasPassword = hasPassword,
    hasNotes = hasNotes,
    hasProtectedFields = hasProtectedFields,
    customFields = customFields.map { CustomFieldInfo(it.name, it.protected) },
    hasTotp = hasTotp,
    favorite = favorite,
    expiresAt = expiresAt,
    icon = icon.toInt(),
    customIcon = customIcon,
    passkey = passkey?.let { PasskeyInfo(it.rpId, it.userName, it.credentialId, it.userHandle) },
)

private fun TotpCodeDto.toCode() = TotpCode(SecretValue(code.utf8), period.toInt(), secondsRemaining.toInt())

private fun TotpSetupDto.toSetup() = TotpSetup(
    uri = SecretValue(uri.utf8),
    algorithm = when (algorithm) {
        TotpAlgorithmDto.SHA1 -> TotpAlgorithm.Sha1
        TotpAlgorithmDto.SHA256 -> TotpAlgorithm.Sha256
        TotpAlgorithmDto.SHA512 -> TotpAlgorithm.Sha512
    },
    digits = digits.toInt(),
    period = period.toInt(),
    steam = steam,
    issuer = issuer,
    account = account,
)

private fun FfiTotpException.reason() = when (this) {
    is FfiTotpException.SessionNotFound -> TotpException.Reason.SessionNotFound
    is FfiTotpException.SessionClosed -> TotpException.Reason.SessionClosed
    is FfiTotpException.EntryNotFound -> TotpException.Reason.EntryNotFound
    is FfiTotpException.NotEntitled -> TotpException.Reason.NotEntitled
    is FfiTotpException.NoCode -> TotpException.Reason.NoCode
    is FfiTotpException.Empty -> TotpException.Reason.Empty
    is FfiTotpException.TooLong -> TotpException.Reason.TooLong
    is FfiTotpException.UnsupportedType -> TotpException.Reason.UnsupportedType
    is FfiTotpException.MissingSecret -> TotpException.Reason.MissingSecret
    is FfiTotpException.InvalidSecret -> TotpException.Reason.InvalidSecret
    is FfiTotpException.InvalidAlgorithm -> TotpException.Reason.InvalidAlgorithm
    is FfiTotpException.InvalidDigits -> TotpException.Reason.InvalidDigits
    is FfiTotpException.InvalidPeriod -> TotpException.Reason.InvalidPeriod
    is FfiTotpException.Migration -> TotpException.Reason.Migration
    is FfiTotpException.Malformed -> TotpException.Reason.Malformed
    is FfiTotpException.InvalidText -> TotpException.Reason.InvalidText
}

private fun EntryDetailDto.toDetail() = EntryDetail(
    summary = summary.toSummary(),
    notes = notes.toText(),
    customFields = customFields.map { field: CustomFieldDto -> CustomField(field.name, field.value.toText()) },
    attachments = attachments.map { AttachmentInfo(it.name, it.size.toLong()) },
    modifiedAt = modifiedAt,
    historyCount = historyCount.toInt(),
)

private fun HistoryVersionDto.toVersion() = HistoryVersion(
    index = index.toInt(),
    modifiedAt = modifiedAt,
    title = title.toText(),
    username = username.toText(),
    differs = differs.map {
        when (it) {
            ChangedFieldDto.TITLE -> ChangedField.Title
            ChangedFieldDto.USER_NAME -> ChangedField.UserName
            ChangedFieldDto.PASSWORD -> ChangedField.Password
            ChangedFieldDto.URL -> ChangedField.Url
            ChangedFieldDto.NOTES -> ChangedField.Notes
            ChangedFieldDto.CUSTOM_FIELDS -> ChangedField.CustomFields
            ChangedFieldDto.TAGS -> ChangedField.Tags
            ChangedFieldDto.FAVORITE -> ChangedField.Favorite
            ChangedFieldDto.ATTACHMENTS -> ChangedField.Attachments
            ChangedFieldDto.EXPIRY -> ChangedField.Expiry
        }
    },
)

private fun TextDto.toText(): EntryText = when (this) {
    is TextDto.Plain -> EntryText.Plain(value)
    TextDto.Protected -> EntryText.Hidden
}

private fun SecretField.toDto(): SecretFieldDto = when (this) {
    SecretField.Password -> SecretFieldDto.Password
    SecretField.Title -> SecretFieldDto.Title
    SecretField.UserName -> SecretFieldDto.UserName
    SecretField.Url -> SecretFieldDto.Url
    SecretField.Notes -> SecretFieldDto.Notes
    is SecretField.Custom -> SecretFieldDto.Custom(name)
}

private fun EntryDraft.toDto() = EntryDraftDto(
    title = title,
    username = username,
    password = password,
    url = url,
    notes = notes,
    customFields = customFields.map { CustomFieldDraftDto(it.name, it.value, it.isProtected) },
    tags = tags,
    favorite = favorite,
    expiresAt = expiresAt,
)

private fun DeleteOutcomeDto.toOutcome() = when (this) {
    DeleteOutcomeDto.MOVED_TO_RECYCLE_BIN -> DeleteOutcome.MovedToRecycleBin
    DeleteOutcomeDto.DELETED -> DeleteOutcome.Deleted
}

private fun LeaseDto.toLease() = KeepLease(
    subject = subject,
    tier = if (tier == LeaseTierDto.FREE) LeaseTier.Free else LeaseTier.KeepPlus,
    lifetime = lifetime,
    issuedAt = issuedAt,
    leaseExpiresAt = leaseExpiresAt,
    entitlementExpiresAt = entitlementExpiresAt,
    featuresVersion = featuresVersion.toInt(),
    revision = revision.toLong(),
    nonce = nonce,
    keyId = keyId,
)

private fun KeepLease.toDto() = LeaseDto(
    subject = subject,
    tier = if (tier == LeaseTier.Free) LeaseTierDto.FREE else LeaseTierDto.KEEP_PLUS,
    lifetime = lifetime,
    issuedAt = issuedAt,
    leaseExpiresAt = leaseExpiresAt,
    entitlementExpiresAt = entitlementExpiresAt,
    featuresVersion = featuresVersion.toUInt(),
    revision = revision.toULong(),
    nonce = nonce,
    keyId = keyId,
)

private fun AccessDto.toAccess() = KeepAccess(
    state = when (state) {
        AccessStateDto.FREE -> KeepAccessState.Free
        AccessStateDto.PLUS_ACTIVE -> KeepAccessState.PlusActive
        AccessStateDto.PLUS_OFFLINE_GRACE -> KeepAccessState.PlusOfflineGrace
        AccessStateDto.PLUS_LIFETIME -> KeepAccessState.PlusLifetime
        AccessStateDto.PLUS_EXPIRED -> KeepAccessState.PlusExpired
    },
    plusFeatures = plusFeatures,
    needsOnlineVerification = needsOnlineVerification,
    leaseValidUntil = leaseValidUntil,
    observedDeviceTime = observedDeviceTime,
)

private fun KeepAccess.toDto() = AccessDto(
    state = when (state) {
        KeepAccessState.Free -> AccessStateDto.FREE
        KeepAccessState.PlusActive -> AccessStateDto.PLUS_ACTIVE
        KeepAccessState.PlusOfflineGrace -> AccessStateDto.PLUS_OFFLINE_GRACE
        KeepAccessState.PlusLifetime -> AccessStateDto.PLUS_LIFETIME
        KeepAccessState.PlusExpired -> AccessStateDto.PLUS_EXPIRED
    },
    plusFeatures = plusFeatures,
    needsOnlineVerification = needsOnlineVerification,
    leaseValidUntil = leaseValidUntil,
    observedDeviceTime = observedDeviceTime,
)

private fun PlusFeature.toDtoName(): String = when (this) {
    PlusFeature.MultiVault -> "MULTI_VAULT"
    PlusFeature.Totp -> "TOTP"
    PlusFeature.PasswordHealth -> "PASSWORD_HEALTH"
    PlusFeature.BreachMonitoring -> "BREACH_MONITORING"
    PlusFeature.HistoryUi -> "HISTORY_UI"
    PlusFeature.AttachmentWrite -> "ATTACHMENT_WRITE"
    PlusFeature.PremiumThemes -> "PREMIUM_THEMES"
}
