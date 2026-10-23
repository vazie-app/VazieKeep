package app.vazie.keep

import app.vazie.keep.data.OpenedVault
import app.vazie.keep.passkeys.PasskeyViewModel
import app.vazie.keep.passkeys.PasskeyRequest
import app.vazie.keep.passkeys.PasskeyOperations
import app.vazie.keep.passkeys.PasskeyIndexStore
import app.vazie.keep.passkeys.PasskeyIndex
import app.vazie.keep.passkeys.PasskeyGate
import app.vazie.keep.passkeys.HttpAssetLinks
import app.vazie.keep.passkeys.FilePasskeyIndexStore
import app.vazie.keep.passkeys.AssetLinkSource
import kotlinx.coroutines.flow.filterIsInstance
import app.vazie.keep.data.VaultState
import app.vazie.keep.data.VaultLockedException
import app.vazie.keep.siteicons.SiteIconUpdater
import app.vazie.keep.siteicons.SiteIcons
import app.vazie.keep.siteicons.HttpSiteIcons
import app.vazie.keep.siteicons.EntryImages
import android.app.Application
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ProcessLifecycleOwner
import app.vazie.keep.account.FeatureAccess
import app.vazie.keep.account.HttpVazieAccountApi
import app.vazie.keep.account.KeepEntitlementService
import app.vazie.keep.account.KeystoreAccountStore
import app.vazie.keep.autofill.AndroidAutofillSystem
import app.vazie.keep.autofill.AndroidPackageIdentity
import app.vazie.keep.autofill.AutofillSystem
import app.vazie.keep.autofill.AppAssociationStore
import app.vazie.keep.autofill.AuthRequest
import app.vazie.keep.autofill.AutofillAuthViewModel
import app.vazie.keep.autofill.AutofillSaveViewModel
import app.vazie.keep.autofill.SaveExecutor
import app.vazie.keep.autofill.SaveHandoff
import app.vazie.keep.data.VaultDocumentWriter
import app.vazie.keep.autofill.AutofillCoordinator
import app.vazie.keep.autofill.AutofillIndexStore
import app.vazie.keep.autofill.AutofillIndexer
import app.vazie.keep.autofill.CredentialMatcher
import app.vazie.keep.autofill.FileAppAssociationStore
import app.vazie.keep.autofill.FileAutofillIndexStore
import app.vazie.keep.autofill.FillResponseFactory
import app.vazie.keep.autofill.IndexBuilder
import app.vazie.keep.autofill.CallerVerifier
import app.vazie.keep.autofill.PackageIdentity
import app.vazie.keep.autofill.PlatformCallerVerifier
import app.vazie.keep.autofill.TrustedBrowsers
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.biometric.AndroidBiometricAuthenticator
import app.vazie.keep.biometric.BiometricAvailability
import app.vazie.keep.biometric.BiometricRecord
import app.vazie.keep.biometric.BiometricStatus
import app.vazie.keep.biometric.BiometricUnlock
import app.vazie.keep.biometric.FileBiometricSetupStore
import app.vazie.keep.biometric.KeystoreCredentialProtector
import app.vazie.keep.core.KeepCore
import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.data.AndroidSecretClipboard
import app.vazie.keep.data.ContentResolverDocumentPort
import app.vazie.keep.data.ContentResolverVaultFileAccess
import app.vazie.keep.data.FileVaultCopyStore
import app.vazie.keep.data.JournaledOverwriteWriter
import app.vazie.keep.data.SecretClipboard
import app.vazie.keep.data.SharedPreferencesVaultReferenceStore
import app.vazie.keep.data.FileVaultRegistry
import app.vazie.keep.data.RegistryVaultReferenceStore
import app.vazie.keep.data.FileMainVaultStore
import app.vazie.keep.data.VaultManager
import app.vazie.keep.data.VaultPolicy
import app.vazie.keep.data.vaultPlan
import app.vazie.keep.data.VaultReferenceStore
import app.vazie.keep.data.VaultRegistry
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultScopedFile
import app.vazie.keep.links.AppLinksRepository
import app.vazie.keep.links.FaqRepository
import app.vazie.keep.links.HttpFaqSource
import app.vazie.keep.links.HttpLinksSource
import app.vazie.keep.update.HttpReleaseSource
import app.vazie.keep.update.InstalledReleaseSource
import app.vazie.keep.update.PreferencesDismissedUpdateStore
import app.vazie.keep.update.UpdateChecker
import app.vazie.keep.lock.AutoLockController
import app.vazie.keep.lock.LockCleanup
import app.vazie.keep.lock.ScreenOffReceiver
import app.vazie.keep.review.PackageInstallSource
import app.vazie.keep.review.ReviewPrompt
import app.vazie.keep.settings.LauncherIconManager
import app.vazie.keep.settings.PackageManagerLauncherIcons
import app.vazie.keep.settings.SecuritySettings
import app.vazie.keep.settings.SecuritySettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Context.settingsStore by preferencesDataStore(name = "security_settings")
private val Context.reviewStore by preferencesDataStore(name = "review_prompt")

class KeepApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(container.autoLock)
        ScreenOffReceiver(container.autoLock::onScreenOff).register(this)
        LockCleanup(container.repository, container.clipboard).start(container.appScope)
        container.appScope.launch {
            container.repository.state.filterIsInstance<VaultState.Locked>().collect { container.entryImages.clear() }
        }
        container.appScope.launch {
            container.repository.state.filterIsInstance<VaultState.Unlocked>().collect { container.vaultManager.mergeDuplicates() }
        }
        container.appScope.launch { container.launcherIcons.reconcile() }
        container.autofillIndexer.start()
        container.entitlements.start()
        container.appScope.launch {
            kotlinx.coroutines.flow.combine(container.entitlements.state, container.registry.snapshot) { _, _ -> }.collect {
                container.vaultPolicy.refresh()
                container.repository.refreshEditPolicy()
            }
        }
        container.review.onLaunch()
        container.appScope.launch { container.entitlements.refresh() }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                    container.entitlements.reevaluate()
                    container.appScope.launch { container.entitlements.refresh() }
                }
            },
        )
    }
}

class AppContainer(application: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val core: KeepCore = RustKeepCore

    val settingsRepository = SecuritySettingsRepository(application.settingsStore)

    val settings: StateFlow<SecuritySettings> =
        settingsRepository.settings.stateIn(appScope, SharingStarted.Eagerly, SecuritySettings())

    private val installSource = PackageInstallSource(application)

    val review = ReviewPrompt(installSource, application.reviewStore, appScope)
    val launcherIcons = LauncherIconManager(PackageManagerLauncherIcons(application), settingsRepository)

    val links = AppLinksRepository(HttpLinksSource(BuildConfig.VAZIE_ACCOUNT_ORIGIN), File(application.filesDir, "app_links.json"))
    val faq = FaqRepository(HttpFaqSource(BuildConfig.VAZIE_ACCOUNT_ORIGIN), File(application.filesDir, "faq.json"))

    val updates = UpdateChecker(
        InstalledReleaseSource(HttpReleaseSource(BuildConfig.VAZIE_ACCOUNT_ORIGIN), installSource),
        BuildConfig.VERSION_CODE,
        PreferencesDismissedUpdateStore(application.getSharedPreferences("update", Context.MODE_PRIVATE)),
    )

    val biometricAuthenticator = AndroidBiometricAuthenticator(application)

    private val noBackup: File = application.noBackupFilesDir

    val registry: VaultRegistry = FileVaultRegistry(
        file = File(noBackup, "vault_registry.bin"),
        legacy = { SharedPreferencesVaultReferenceStore(application).load() },
        onLegacyMigrated = { vault ->
            File(noBackup, "biometric_unlock.bin").renameTo(File(noBackup, "biometric_unlock_${vault.id}.bin"))
            File(noBackup, "autofill_index.bin").renameTo(File(noBackup, "autofill_index_${vault.id}.bin"))
        },
    )

    val biometricStore = FileBiometricSetupStore(VaultScopedFile(noBackup, "biometric_unlock", "bin") { registry.active()?.id })

    // The typed password's KeePass component stays in the Rust session only while biometric unlock
    // could still be turned on for this vault.
    val copies = FileVaultCopyStore(File(application.noBackupFilesDir, "Vault Copies"))

    private val files = ContentResolverVaultFileAccess(application.contentResolver)

    val references: VaultReferenceStore = RegistryVaultReferenceStore(registry)

    private val fileAutofillIndex = FileAutofillIndexStore(VaultScopedFile(noBackup, "autofill_index", "bin") { registry.active()?.id })

    val autofillIndexStore: AutofillIndexStore = fileAutofillIndex

    private val filePasskeyIndex = FilePasskeyIndexStore(VaultScopedFile(noBackup, "passkey_index", "bin") { registry.active()?.id })

    val passkeyIndex: PasskeyIndexStore = filePasskeyIndex

    // Google's published list of browsers that may state a web origin for a passkey request, bundled with the app.
    val privilegedBrowsers: String by lazy { application.assets.open("passkey-privileged-apps.json").bufferedReader().use { it.readText() } }

    private fun indexAfterAutofill(vault: OpenedVault) {
        IndexBuilder.persist(vault, autofillIndexStore, appAssociations)
        // Recycled entries are left out by the session itself.
        passkeyIndex.save(PasskeyIndex.of(vault.info.fingerprint, vault.session.passkeys(null)))
    }

    val appAssociations: AppAssociationStore = FileAppAssociationStore(File(application.noBackupFilesDir, "autofill_associations.bin"))

    val packageIdentity: PackageIdentity = AndroidPackageIdentity(application)

    val autofillSystem: AutofillSystem = AndroidAutofillSystem(application)

    private val timing: (String, Long) -> Unit = if (BuildConfig.DEBUG) DebugTiming::log else { _, _ -> }

    val trustedBrowsers = TrustedBrowsers.Default

    val autofillCoordinator = AutofillCoordinator(autofillIndexStore, appAssociations, CredentialMatcher(core), packageIdentity, trustedBrowsers)

    val fillResponseFactory = FillResponseFactory(application) { launcherIcons.current.value.launcher }

    val vaultOpener = VaultOpener(core, files, copies, timing)

    val writer: VaultDocumentWriter = JournaledOverwriteWriter(ContentResolverDocumentPort(application.contentResolver), copies)

    val repository: VaultRepository = VaultRepository(
        core = core,
        files = files,
        references = references,
        registry = registry,
        writer = writer,
        copies = copies,
        timing = timing,
        onVaultSwitched = {},
        mayEditVault = { id -> vaultPolicy.current().canEdit(id) },
        mayAddVault = { vaultPolicy.current().canAdd },
        retainUnlockMaterial = {
            biometricAuthenticator.availability() == BiometricAvailability.Available && biometrics.status.value !is BiometricStatus.On
        },
    )

    val autofillIndexer = AutofillIndexer(repository, autofillIndexStore, appAssociations, appScope) { info, _ ->
        try {
            passkeyIndex.save(PasskeyIndex.of(info.fingerprint, repository.passkeys()))
        } catch (_: VaultLockedException) {
        }
    }

    fun autofillTiming(label: String, startedNanos: Long) = timing(label, (System.nanoTime() - startedNanos) / 1_000_000)

    // Tests replace this: a test can't be "started by" another package.
    @androidx.annotation.VisibleForTesting
    var callerVerifierOverride: ((callingPackage: String?) -> CallerVerifier)? = null

    fun callerVerifier(callingPackage: String?): CallerVerifier =
        callerVerifierOverride?.invoke(callingPackage) ?: PlatformCallerVerifier({ callingPackage }, packageIdentity, trustedBrowsers)

    val saveHandoff = SaveHandoff()

    private val saveExecutor = SaveExecutor(
        files = files,
        writer = writer,
        copies = copies,
        afterSave = ::indexAfterAutofill,
        mayEdit = { reference -> reference.id.isEmpty() || vaultPolicy.current().canEdit(reference.id) },
        timing = timing,
    )

    fun newAutofillSaveViewModel(token: String) = AutofillSaveViewModel(
        token = token,
        handoff = saveHandoff,
        references = references,
        index = autofillIndexStore,
        associations = appAssociations,
        opener = vaultOpener,
        biometrics = biometrics,
        matcher = CredentialMatcher(core),
        executor = saveExecutor,
        appLabel = packageIdentity::label,
        // The app's own open session no longer matches the file: lock it (unsaved edits are kept as a recovered copy).
        onSaved = { appScope.launch { repository.lock() } },
        timing = timing,
    )

    fun newAutofillAuthViewModel(request: AuthRequest, callingPackage: String?) = AutofillAuthViewModel(
        request = request,
        references = references,
        index = autofillIndexStore,
        associations = appAssociations,
        opener = vaultOpener,
        biometrics = biometrics,
        matcher = CredentialMatcher(core),
        caller = callerVerifier(callingPackage),
        reindex = ::indexAfterAutofill,
        timing = timing,
    )

    var assetLinks: AssetLinkSource = HttpAssetLinks()

    private val passkeyOperations = PasskeyOperations(
        files = files,
        writer = writer,
        copies = copies,
        afterSave = ::indexAfterAutofill,
        mayEdit = { reference -> reference.id.isEmpty() || vaultPolicy.current().canEdit(reference.id) },
    )

    fun newPasskeyViewModel(request: PasskeyRequest?) = PasskeyViewModel(
        request = request,
        references = references,
        opener = vaultOpener,
        biometrics = biometrics,
        gate = PasskeyGate(core, assetLinks),
        operations = passkeyOperations,
        userNameOf = { entryId -> passkeyIndex.load()?.passkeys?.firstOrNull { it.entryId == entryId }?.userName },
        // The app's own open session no longer matches the file: lock it (unsaved edits are kept as a recovered copy).
        onSaved = { appScope.launch { repository.lock() } },
    )

    var siteIcons: SiteIcons = HttpSiteIcons()

    val siteIconUpdater = SiteIconUpdater(repository, { siteIcons }, { settings.value.siteIconsAutomatic }, appScope)

    val entryImages = EntryImages { id ->
        try {
            repository.customIcon(id)
        } catch (_: VaultLockedException) {
            null
        }
    }

    private val keystoreProtector = KeystoreCredentialProtector()

    val biometrics: BiometricUnlock = BiometricUnlock(
        repository = repository,
        store = biometricStore,
        protector = keystoreProtector,
        authenticator = biometricAuthenticator,
    )

    val vaultManager = VaultManager(
        core = core,
        files = files,
        registry = registry,
        repository = repository,
        mayAddVault = { vaultPolicy.current().canAdd },
        forgetTwin = { vault ->
            withContext(Dispatchers.IO) {
                (biometricStore.loadFor(vault.id) as? BiometricRecord.Active)?.let { keystoreProtector.delete(it.alias) }
                biometricStore.clearFor(vault.id)
                fileAutofillIndex.clearFor(vault.id)
                filePasskeyIndex.clearFor(vault.id)
            }
        },
        onMerged = { removed, into -> vaultPolicy.merged(removed, into) },
        forgetData = { vault ->
            withContext(Dispatchers.IO) {
                (biometricStore.loadFor(vault.id) as? BiometricRecord.Active)?.let { keystoreProtector.delete(it.alias) }
                biometricStore.clearFor(vault.id)
                fileAutofillIndex.clearFor(vault.id)
                filePasskeyIndex.clearFor(vault.id)
                vault.fingerprint?.let(copies::removeAll)
            }
        },
    )

    val accountApi = HttpVazieAccountApi(BuildConfig.VAZIE_ACCOUNT_ORIGIN, BuildConfig.VAZIE_KEEP_ORIGIN)

    // Keep+ lives apart from vaults: nothing that opens or saves one waits for it.
    val entitlements = KeepEntitlementService(
        api = accountApi,
        store = KeystoreAccountStore(File(noBackup, "vazie_account.bin")),
        core = core,
        keys = KeepEntitlementService.parseKeys(BuildConfig.KEEP_LEASE_KEYS),
    )

    val featureAccess = FeatureAccess(core, entitlements.state)

    val vaultPolicy = VaultPolicy(registry, FileMainVaultStore(File(noBackup, "main_vault")), { entitlements.state.value.access.vaultPlan() })

    val autoLock = AutoLockController(repository, appScope, settings)

    val clipboard: SecretClipboard = AndroidSecretClipboard(application, appScope) { settings.value.clipboardTimeout.duration }
}
