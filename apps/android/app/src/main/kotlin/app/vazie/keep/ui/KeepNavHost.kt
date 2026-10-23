package app.vazie.keep.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.vazie.keep.AppContainer
import app.vazie.keep.BuildConfig
import app.vazie.keep.R
import app.vazie.keep.account.AccountScreen
import app.vazie.keep.account.AccountViewModel
import app.vazie.keep.account.DeleteAccountScreen
import app.vazie.keep.account.currentPlus
import app.vazie.keep.account.settingsAccount
import app.vazie.keep.chooser.ChooserScreen
import app.vazie.keep.chooser.ChooserViewModel
import app.vazie.keep.chooser.MainVaultDialog
import app.vazie.keep.core.KeepAccessState
import app.vazie.keep.create.CreateVaultScreen
import app.vazie.keep.create.CreateVaultViewModel
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.data.copyFileName
import app.vazie.keep.editor.EDITOR_ENTRY_ARG
import app.vazie.keep.editor.EDITOR_GROUP_ARG
import app.vazie.keep.editor.EntryEditorScreen
import app.vazie.keep.editor.EntryEditorViewModel
import app.vazie.keep.entry.ENTRY_ID_ARG
import app.vazie.keep.entry.EntryScreen
import app.vazie.keep.entry.EntryViewModel
import app.vazie.keep.generator.GeneratorScreen
import app.vazie.keep.generator.GeneratorViewModel
import app.vazie.keep.history.HistoryScreen
import app.vazie.keep.history.HistoryViewModel
import app.vazie.keep.importer.ImportViewModel
import app.vazie.keep.links.LinkSection
import app.vazie.keep.links.inSection
import app.vazie.keep.plus.CurrentPlus
import app.vazie.keep.plus.HttpKeepPricesApi
import app.vazie.keep.plus.PlusScreen
import app.vazie.keep.plus.PlusStatus
import app.vazie.keep.plus.PlusViewModel
import app.vazie.keep.review.InstallStore
import app.vazie.keep.review.ReviewPrompt
import app.vazie.keep.review.openStoreListing
import app.vazie.keep.settings.AboutScreen
import app.vazie.keep.settings.AppearanceScreen
import app.vazie.keep.settings.FaqScreen
import app.vazie.keep.settings.Guide
import app.vazie.keep.settings.GuideScreen
import app.vazie.keep.settings.GuidesScreen
import app.vazie.keep.settings.supportLink
import app.vazie.keep.settings.SettingsScreen
import app.vazie.keep.settings.SettingsViewModel
import app.vazie.keep.ui.designsystem.KeepButtonStyle
import app.vazie.keep.ui.designsystem.KeepDialog
import app.vazie.keep.ui.designsystem.KeepDialogAction
import app.vazie.keep.unlock.UnlockScreen
import app.vazie.keep.unlock.UnlockViewModel
import app.vazie.keep.update.UpdateBanner
import app.vazie.keep.vault.VaultScreen
import app.vazie.keep.vault.VaultSwitcher
import app.vazie.keep.vault.VaultViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

const val GUIDE_ARG = "guide"

object Routes {
    const val CHOOSER = "chooser"
    const val UNLOCK = "unlock"
    const val VAULT = "vault"
    const val ENTRY = "entry/{$ENTRY_ID_ARG}"
    const val ENTRY_EDIT = "entry/{$EDITOR_ENTRY_ARG}/edit"
    const val ENTRY_HISTORY = "entry/{$ENTRY_ID_ARG}/history"
    const val ENTRY_NEW = "group/{$EDITOR_GROUP_ARG}/new-entry"
    const val CREATE = "create"
    const val SETTINGS = "settings"
    const val ABOUT = "about"
    const val APPEARANCE = "settings/appearance"
    const val ACCOUNT = "account"
    const val PLUS = "plus"
    const val DELETE_ACCOUNT = "account/delete"
    const val GENERATOR = "generator"
    const val GUIDES = "settings/guides"
    const val GUIDE = "settings/guides/{$GUIDE_ARG}"
    const val FAQ = "settings/faq"

    val aboutAccount = setOf(PLUS, ACCOUNT, DELETE_ACCOUNT, SETTINGS, APPEARANCE, GUIDES, GUIDE, FAQ)

    fun guide(key: String) = "settings/guides/$key"

    val requiresSession = setOf(VAULT, ENTRY, ENTRY_EDIT, ENTRY_HISTORY, ENTRY_NEW, GENERATOR)

    fun entry(id: String) = "entry/$id"

    fun entryEdit(id: String) = "entry/$id/edit"

    fun entryHistory(id: String) = "entry/$id/history"

    fun entryNew(groupId: String) = "group/$groupId/new-entry"
}

@Composable
fun KeepNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val repository = container.repository
    val start = remember { if (repository.reference() != null) Routes.UNLOCK else Routes.CHOOSER }
    val registrySnapshot by container.registry.snapshot.collectAsStateWithLifecycle()
    val entitlement by container.entitlements.state.collectAsStateWithLifecycle()
    val links by container.links.links.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.links.refresh() }
    val update by container.updates.update.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { container.updates.refresh() }
    val updateBanner: @Composable () -> Unit = { UpdateBanner(update, onLater = container.updates::later) }
    val copyName = registrySnapshot.active?.toReference()?.copyFileName() ?: "Vault copy.kdbx"

    LaunchedEffect(navController) {
        combine(repository.state, navController.currentBackStackEntryFlow) { vault, entry -> vault to entry.destination.route }
            .collect { (vault, route) ->
                if (vault is VaultState.Locked && route in Routes.requiresSession) navController.resetTo(Routes.UNLOCK)
            }
    }

    ReviewDialog(container.review, repository)

    val vaultRules by container.vaultPolicy.rules.collectAsStateWithLifecycle()
    val currentRoute = navController.currentBackStackEntryFlow.collectAsStateWithLifecycle(null).value?.destination?.route
    // The person may go and look at Keep+ or sign in first: the question waits behind those screens.
    if (vaultRules.mustChooseMain && currentRoute !in Routes.aboutAccount) {
        MainVaultDialog(
            vaults = registrySnapshot.vaults,
            onChoose = { id ->
                container.vaultPolicy.chooseMain(id)
                repository.refreshEditPolicy()
            },
            onGetPlus = { navController.navigate(Routes.PLUS) },
        )
    }

    NavHost(
        navController = navController,
        startDestination = start,
        // Plain fades: the default predictive-back look shrinks the whole screen.
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(120)) },
        popEnterTransition = { fadeIn(tween(180)) },
        popExitTransition = { fadeOut(tween(120)) },
    ) {
        composable(Routes.CHOOSER) {
            ChooserScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { ChooserViewModel(repository, container.registry, container.vaultManager) } }),
                coreVersion = remember { container.core.info().version },
                onVaultReady = { navController.resetTo(Routes.UNLOCK) },
                onCreate = { navController.navigate(Routes.CREATE) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                rules = vaultRules,
                onGetPlus = { navController.navigate(Routes.PLUS) },
                banner = updateBanner,
            )
        }
        composable(Routes.CREATE) {
            CreateVaultScreen(
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { CreateVaultViewModel(container.core, container.vaultManager, repository, container.biometrics) }
                    },
                ),
                onBack = { navController.popBackStack() },
                onOpenVault = { navController.resetTo(Routes.VAULT) },
                onOpenList = { navController.resetTo(Routes.CHOOSER) },
            )
        }
        composable(Routes.UNLOCK) {
            UnlockScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { UnlockViewModel(repository, container.biometrics) } }),
                onUnlocked = { navController.resetTo(Routes.VAULT) },
                onChooseOther = { navController.navigate(Routes.CHOOSER) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                banner = updateBanner,
            )
        }
        composable(Routes.VAULT) {
            VaultScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { VaultViewModel(repository, clipboard = container.clipboard, siteIcons = container.siteIcons) } }),
                suggestedCopyName = copyName,
                onOpenEntry = { id -> navController.navigate(Routes.entry(id.value)) },
                onNewEntry = { group -> navController.navigate(Routes.entryNew(group.value)) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
                onGenerator = { navController.navigate(Routes.GENERATOR) { launchSingleTop = true } },
                switcher = VaultSwitcher(
                    vaults = registrySnapshot.vaults.sortedByDescending { it.lastUsedAt },
                    activeId = registrySnapshot.activeId,
                    onSwitch = { id -> repository.activate(id) },
                    onAll = { repository.lock(); navController.resetTo(Routes.CHOOSER) },
                ),
                onGetPlus = { navController.navigate(Routes.PLUS) },
                importer = viewModel(factory = viewModelFactory { initializer { ImportViewModel(container.core, repository) } }),
            )
        }
        composable(Routes.GENERATOR) {
            GeneratorScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { GeneratorViewModel(container.core, container.clipboard) } }),
                onKeep = { navController.popBackStack(Routes.VAULT, inclusive = false) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.ENTRY, arguments = listOf(navArgument(ENTRY_ID_ARG) { type = NavType.StringType })) { entry ->
            EntryScreen(
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { EntryViewModel(createSavedStateHandle(), repository, container.clipboard, access = { container.entitlements.state.value.access }) }
                    },
                ),
                suggestedCopyName = copyName,
                onBack = { navController.popBackStack() },
                onEdit = { entry.arguments?.getString(ENTRY_ID_ARG)?.let { navController.navigate(Routes.entryEdit(it)) } },
                onGetPlus = { navController.navigate(Routes.PLUS) },
                onHistory = { entry.arguments?.getString(ENTRY_ID_ARG)?.let { navController.navigate(Routes.entryHistory(it)) } },
            )
        }
        composable(Routes.ENTRY_HISTORY, arguments = listOf(navArgument(ENTRY_ID_ARG) { type = NavType.StringType })) {
            HistoryScreen(
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { HistoryViewModel(createSavedStateHandle(), repository, container.clipboard, access = { container.entitlements.state.value.access }) }
                    },
                ),
                suggestedCopyName = copyName,
                onBack = { navController.popBackStack() },
                onGetPlus = { navController.navigate(Routes.PLUS) },
            )
        }
        composable(Routes.ENTRY_EDIT, arguments = listOf(navArgument(EDITOR_ENTRY_ARG) { type = NavType.StringType })) {
            EditorRoute(container, copyName) { navController.popBackStack() }
        }
        composable(Routes.ENTRY_NEW, arguments = listOf(navArgument(EDITOR_GROUP_ARG) { type = NavType.StringType })) {
            EditorRoute(container, copyName) { navController.popBackStack() }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel(
                    factory = viewModelFactory {
                        initializer { SettingsViewModel(container.settingsRepository, container.settings, container.biometrics, repository.state, container.launcherIcons, container.autofillSystem) }
                    },
                ),
                coreVersion = remember { container.core.info().version },
                account = settingsAccount(entitlement),
                onPlus = { navController.navigate(Routes.PLUS) },
                onBack = { navController.popBackStack() },
                onAccount = { navController.navigate(Routes.ACCOUNT) },
                onAbout = { navController.navigate(Routes.ABOUT) },
                onAppearance = { navController.navigate(Routes.APPEARANCE) },
                onGuides = { navController.navigate(Routes.GUIDES) },
                onFaq = { navController.navigate(Routes.FAQ) },
                feedback = links.inSection(LinkSection.FEEDBACK).firstOrNull(),
                support = links.supportLink(),
                onCheckForUpdates = container.updates::checkNow,
            )
        }
        composable(Routes.GUIDES) {
            GuidesScreen(onBack = { navController.popBackStack() }, onGuide = { navController.navigate(Routes.guide(it.key)) })
        }
        composable(Routes.GUIDE, arguments = listOf(navArgument(GUIDE_ARG) { type = NavType.StringType })) { entry ->
            val guide = Guide.fromKey(entry.arguments?.getString(GUIDE_ARG))
            if (guide == null) {
                LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                GuideScreen(guide, onBack = { navController.popBackStack() })
            }
        }
        composable(Routes.FAQ) {
            val faq by container.faq.state.collectAsStateWithLifecycle()
            val scope = rememberCoroutineScope()
            LaunchedEffect(Unit) { container.faq.refresh() }
            FaqScreen(
                state = faq,
                siteOrigin = BuildConfig.VAZIE_SITE_ORIGIN,
                onRetry = { scope.launch { container.faq.refresh() } },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.APPEARANCE) { entry ->
            val parent = remember(entry) { navController.getBackStackEntry(Routes.SETTINGS) }
            AppearanceScreen(
                viewModel = viewModel(
                    viewModelStoreOwner = parent,
                    factory = viewModelFactory {
                        initializer { SettingsViewModel(container.settingsRepository, container.settings, container.biometrics, repository.state, container.launcherIcons, container.autofillSystem) }
                    },
                ),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ACCOUNT) {
            AccountScreen(
                viewModel = viewModel(factory = viewModelFactory { initializer { AccountViewModel(container.entitlements) } }),
                onBack = { navController.popBackStack() },
                onGetPlus = { navController.navigate(Routes.PLUS) },
                onDeleteAccount = { navController.navigate(Routes.DELETE_ACCOUNT) },
            )
        }
        composable(Routes.DELETE_ACCOUNT) { entry ->
            val parent = remember(entry) { navController.getBackStackEntry(Routes.ACCOUNT) }
            DeleteAccountScreen(
                viewModel = viewModel(viewModelStoreOwner = parent, factory = viewModelFactory { initializer { AccountViewModel(container.entitlements) } }),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PLUS) {
            PlusScreen(
                signedIn = entitlement.account != null,
                current = currentPlus(entitlement),
                onSignIn = { navController.navigate(Routes.ACCOUNT) },
                viewModel = viewModel(factory = viewModelFactory { initializer { PlusViewModel(
                                HttpKeepPricesApi(BuildConfig.VAZIE_KEEP_ORIGIN),
                                BuildConfig.VAZIE_SITE_ORIGIN,
                                PlusStatus {
                                    container.entitlements.refresh(force = true)
                                    container.entitlements.state.value.access.plusFeatures
                                },
                            ) } }),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(coreVersion = remember { container.core.info().version }, links = links, onBack = { navController.popBackStack() })
        }
    }
}

@Composable
private fun EditorRoute(container: AppContainer, copyName: String, onClose: () -> Unit) {
    val saveInProgress by container.repository.saveInProgress.collectAsStateWithLifecycle()
    EntryEditorScreen(
        viewModel = viewModel(
            factory = viewModelFactory {
                initializer { EntryEditorViewModel(createSavedStateHandle(), container.repository, container.core, access = { container.entitlements.state.value.access }, onSaved = container.siteIconUpdater::entrySaved) }
            },
        ),
        suggestedCopyName = copyName,
        saveInProgress = saveInProgress,
        onClose = onClose,
    )
}

private fun NavHostController.resetTo(route: String) {
    navigate(route) {
        popUpTo(graph.id) { inclusive = true }
        launchSingleTop = true
    }
}

@Composable
private fun ReviewDialog(review: ReviewPrompt, repository: VaultRepository) {
    val store by review.pending.collectAsStateWithLifecycle()
    val vault by repository.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(vault is VaultState.Unlocked) { if (vault is VaultState.Unlocked) review.onVaultOpened() }
    val current = store ?: return
    if (vault !is VaultState.Unlocked) return
    KeepDialog(
        onDismissRequest = review::postponed,
        title = stringResource(R.string.review_title),
        message = stringResource(if (current == InstallStore.GooglePlay) R.string.review_body_play else R.string.review_body_rustore),
        actions = {
            KeepDialogAction(stringResource(R.string.review_never), review::declined)
            KeepDialogAction(stringResource(R.string.review_later), review::postponed)
            KeepDialogAction(stringResource(R.string.review_rate), {
                openStoreListing(context, current)
                review.rated()
            }, style = KeepButtonStyle.Action)
        },
    )
}
