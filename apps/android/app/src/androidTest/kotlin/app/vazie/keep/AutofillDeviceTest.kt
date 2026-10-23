package app.vazie.keep

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.EditText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.vazie.keep.autofill.AppAssociation
import app.vazie.keep.autofill.AuthRequest
import app.vazie.keep.autofill.AutofillSaveActivity
import app.vazie.keep.autofill.CallerCheck
import app.vazie.keep.autofill.SubmittedCredential
import app.vazie.keep.autofill.CallerVerifier
import app.vazie.keep.autofill.FormKind
import app.vazie.keep.autofill.SigningIdentity
import app.vazie.keep.autofill.AutofillAuthActivity
import app.vazie.keep.autofill.FillPlan
import app.vazie.keep.autofill.IndexBuilder
import app.vazie.keep.autofill.ParsedForm
import app.vazie.keep.autofill.RequestOrigin
import app.vazie.keep.autofill.VazieAutofillService
import app.vazie.keep.autofill.WebOrigin
import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.core.SecretValue
import app.vazie.keep.data.VaultReference
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class AutofillDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (context.applicationContext as KeepApplication).container
    private val password = "correct horse battery staple"
    private lateinit var vault: File
    private lateinit var fingerprint: String
    private lateinit var exampleId: String

    @Before
    fun setUp() = runBlocking {
        AutofillAuthActivity.lastReturned = null
        container.repository.lock()
        container.biometrics.disable()
        vault = File(context.cacheDir, "autofill-device.kdbx")
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("autofill/autofill-fixture.kdbx").use { it.readBytes() }
        vault.writeBytes(bytes)
        container.references.save(VaultReference(vault.toURI().toString().replace("file:/", "file:///"), vault.name, 0))
        RustKeepCore.openVault(bytes, password.toByteArray()).use { session ->
            val index = IndexBuilder.build(session.info(), session.groups(), session.entries())
            container.autofillIndexStore.save(index)
            fingerprint = index.fingerprint
            exampleId = index.entries.first { it.title == "Example" }.entryId
        }
        container.appAssociations.clear()
        container.callerVerifierOverride = { CallerVerifier { CallerCheck.Verified(SigningIdentity("CERT")) } }
    }

    @After
    fun tearDown() {
        container.callerVerifierOverride = null
        container.autofillIndexStore.clear()
        container.appAssociations.clear()
        vault.delete()
    }

    private fun string(id: Int) = context.getString(id)

    private fun ids(): Pair<android.view.autofill.AutofillId, android.view.autofill.AutofillId> =
        EditText(context).autofillId!! to EditText(context).autofillId!!

    private fun selectIntent(origin: RequestOrigin = RequestOrigin.Web(WebOrigin("https", "example.com", null)), entry: String = exampleId): Intent {
        val (username, passwordId) = ids()
        return AuthRequest(AuthRequest.Mode.Select, entry, fingerprint, origin, username, passwordId).toIntent(context)
    }

    private fun awaitResult(@Suppress("UNUSED_PARAMETER") scenario: ActivityScenario<AutofillAuthActivity>): Pair<Int, Intent?>? {
        compose.waitUntil(15_000) { AutofillAuthActivity.lastReturned != null }
        return AutofillAuthActivity.lastReturned
    }

    @Test
    fun theManifestDeclaresTheServiceProtectedAndTheAuthActivityPrivate() {
        val pm = context.packageManager
        val service = pm.getServiceInfo(ComponentName(context, VazieAutofillService::class.java), 0)
        assertEquals("android.permission.BIND_AUTOFILL_SERVICE", service.permission)
        assertTrue(service.exported)
        val auth = pm.getActivityInfo(ComponentName(context, AutofillAuthActivity::class.java), 0)
        assertEquals(false, auth.exported)
        assertTrue(auth.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(auth.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY != 0)
        val intent = Intent("android.service.autofill.AutofillService").setPackage(context.packageName)
        assertEquals(1, pm.queryIntentServices(intent, PackageManager.GET_META_DATA).size)
    }

    @Test
    fun theSaveScreenIsPrivateAndNoPackageInventoryPermissionIsDeclared() {
        val pm = context.packageManager
        val save = pm.getActivityInfo(ComponentName(context, AutofillSaveActivity::class.java), 0)
        assertEquals(false, save.exported)
        assertTrue(save.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(save.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY != 0)
        val requested = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue("android.permission.QUERY_ALL_PACKAGES" !in requested)
    }

    private fun saveIntent(username: String, secret: String, origin: RequestOrigin = RequestOrigin.Web(WebOrigin("https", "device-save.example", null))): Intent {
        val token = container.saveHandoff.put(SubmittedCredential(origin, FormKind.Login, username, SecretValue(secret.toByteArray())))
        return Intent(context, AutofillSaveActivity::class.java).putExtra(AutofillSaveActivity.TOKEN, token)
    }

    private fun enterPassword() {
        compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
        compose.onNodeWithText(string(R.string.autofill_save_unlock_save)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test
    fun aSavedCredentialIsOnlyWrittenAfterConfirmationAndIndexed() {
        AutofillSaveActivity.lastReturned = null
        ActivityScenario.launch<AutofillSaveActivity>(saveIntent("new-user@example.org", "device-secret-1")).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(string(R.string.autofill_save_create))).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(vault.readBytes().contentEquals(vaultBytesBefore()))
            compose.onNodeWithText(string(R.string.autofill_save_create)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            enterPassword()
            compose.waitUntil(20_000) { AutofillSaveActivity.lastReturned != null }
            assertEquals(Activity.RESULT_OK, AutofillSaveActivity.lastReturned)
        }
        val index = container.autofillIndexStore.load()
        assertTrue(index?.entries.orEmpty().any { it.username == "new-user@example.org" && it.url == "https://device-save.example" })
        runBlocking { RustKeepCore.openVault(vault.readBytes(), password.toByteArray()) }.use { session ->
            val entry = session.entries().single { it.username == app.vazie.keep.core.EntryText.Plain("new-user@example.org") }
            assertEquals("device-secret-1", session.password(entry.id).use { it.useChars { chars -> String(chars) } })
        }
    }

    @Test
    fun cancellingTheSaveScreenWritesNothingAndDiscardsTheCredential() {
        AutofillSaveActivity.lastReturned = null
        val before = vault.readBytes()
        ActivityScenario.launch<AutofillSaveActivity>(saveIntent("cancelled@example.org", "device-secret-2")).use {
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(string(R.string.action_cancel))).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText(string(R.string.action_cancel)))[0].performClick()
            compose.waitUntil(10_000) { AutofillSaveActivity.lastReturned != null }
            assertEquals(Activity.RESULT_CANCELED, AutofillSaveActivity.lastReturned)
        }
        assertTrue(before.contentEquals(vault.readBytes()))
        assertEquals(0, container.saveHandoff.size())
    }

    @Test
    fun anUnknownOrUsedTokenFinishesCancelledWithoutWriting() {
        AutofillSaveActivity.lastReturned = null
        val before = vault.readBytes()
        val intent = Intent(context, AutofillSaveActivity::class.java).putExtra(AutofillSaveActivity.TOKEN, "no-such-token")
        ActivityScenario.launch<AutofillSaveActivity>(intent).use {
            compose.waitUntil(10_000) { AutofillSaveActivity.lastReturned != null || compose.onAllNodes(hasText(string(R.string.action_cancel))).fetchSemanticsNodes().isNotEmpty() }
        }
        assertTrue(before.contentEquals(vault.readBytes()))
    }

    private fun vaultBytesBefore() = InstrumentationRegistry.getInstrumentation().context.assets.open("autofill/autofill-fixture.kdbx").use { it.readBytes() }

    @Test
    fun theSystemKnowsTheServiceAsAnAutofillProvider() {
        val manager = context.getSystemService(AutofillManager::class.java)
        assertTrue(manager.isAutofillSupported)
        assertNotNull(container.autofillSystem.enableIntent().data)
        assertEquals("package:${context.packageName}", container.autofillSystem.enableIntent().data.toString())
    }

    @Test
    fun anAuthRequestSurvivesTheIntent() {
        val (username, passwordId) = ids()
        val origin = RequestOrigin.App("com.example.app", ignoredWebClaim = true)
        val request = AuthRequest.fromIntent(AuthRequest(AuthRequest.Mode.Select, "id", "fp", origin, username, passwordId).toIntent(context))!!
        assertEquals(AuthRequest.Mode.Select, request.mode)
        assertEquals("id", request.entryId)
        assertEquals(origin, request.origin)
        assertEquals(username, request.usernameId)
        assertNull(AuthRequest.fromIntent(Intent()))
        assertNull(AuthRequest.fromIntent(Intent(context, AutofillAuthActivity::class.java)))
    }

    @Test
    fun theResponseFactoryBuildsDatasetsOrASearchEntryAndNothingForNoFields() {
        val (username, passwordId) = ids()
        // This app is not a pinned browser, so its web claim is ignored and only search is offered.
        val offer = container.autofillCoordinator.plan(ParsedForm(FormKind.Login, username, passwordId, null, null, "example.com", "https"), context.packageName) as FillPlan.Offer<android.view.autofill.AutofillId>
        assertTrue(offer.matches.isEmpty())
        assertNotNull(container.fillResponseFactory.build(offer, null))
        if (container.trustedBrowsers.isTrusted("com.android.chrome", container.packageIdentity.signing("com.android.chrome"))) {
            val chrome = container.autofillCoordinator.plan(ParsedForm(FormKind.Login, username, passwordId, null, null, "example.com", "https"), "com.android.chrome") as FillPlan.Offer<android.view.autofill.AutofillId>
            assertEquals(listOf("Example", "Example (second account)"), chrome.matches.map { it.entry.title })
            assertNotNull(container.fillResponseFactory.build(chrome, null))
        }
        val none = FillPlan.Offer<android.view.autofill.AutofillId>(ParsedForm<android.view.autofill.AutofillId>(FormKind.Unsupported, null, null, null, null, null, null), offer.origin, emptyList(), fingerprint)
        assertNull(container.fillResponseFactory.build(none, null))
    }

    @Test
    fun theAuthWindowIsSecureAndTheCorrectPasswordReturnsADataset() {
        ActivityScenario.launch<AutofillAuthActivity>(selectIntent()).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithText(string(R.string.autofill_unlock_fill)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            val result = awaitResult(scenario)
            assertEquals(Activity.RESULT_OK, result?.first)
            assertNotNull(result?.second?.extras?.get(AutofillManager.EXTRA_AUTHENTICATION_RESULT))
        }
    }

    @Test
    fun aWrongPasswordShowsTheErrorKeepsTheScreenAndReturnsNothing() {
        ActivityScenario.launch<AutofillAuthActivity>(selectIntent()).use { scenario ->
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement("wrong")
            compose.onNodeWithText(string(R.string.autofill_unlock_fill)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(string(R.string.error_wrong_password))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(string(R.string.action_cancel)).assertExists()
        }
    }

    @Test
    fun cancellingReturnsNothingAndShowsNoMessage() {
        ActivityScenario.launch<AutofillAuthActivity>(selectIntent()).use { scenario ->
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription(string(R.string.unlock_password_label))).fetchSemanticsNodes().isNotEmpty() }
            compose.onAllNodes(hasText(string(R.string.action_cancel)))[0].performClick()
            val result = awaitResult(scenario)
            assertEquals(Activity.RESULT_CANCELED, result?.first)
            assertNull(result?.second)
        }
    }

    @Test
    fun anEntryThatIsNoLongerAssociatedIsRefusedAfterUnlock() {
        val origin = RequestOrigin.App("com.example.app")
        ActivityScenario.launch<AutofillAuthActivity>(selectIntent(origin)).use {
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithText(string(R.string.autofill_unlock_fill)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            compose.waitUntil(15_000) { compose.onAllNodes(hasText(string(R.string.autofill_problem_no_match))).fetchSemanticsNodes().isNotEmpty() }
        }
        container.appAssociations.add(AppAssociation("com.example.app", "CERT", fingerprint, exampleId))
        ActivityScenario.launch<AutofillAuthActivity>(selectIntent(origin)).use { scenario ->
            compose.onNodeWithContentDescription(string(R.string.unlock_password_label)).performTextReplacement(password)
            compose.onNodeWithText(string(R.string.autofill_unlock_fill)).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
            assertEquals(Activity.RESULT_OK, awaitResult(scenario)?.first)
        }
    }

    @Test
    fun searchListsTheIndexWithoutUnlockingAnything() {
        val (username, passwordId) = ids()
        val intent = AuthRequest(AuthRequest.Mode.Search, null, fingerprint, RequestOrigin.App("com.example.app"), username, passwordId).toIntent(context)
        ActivityScenario.launch<AutofillAuthActivity>(intent).use {
            compose.waitUntil(10_000) { compose.onAllNodes(hasText("Example (second account)")).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(compose.onAllNodes(hasText("Trashed example")).fetchSemanticsNodes().isEmpty())
            assertTrue(container.repository.state.value is app.vazie.keep.data.VaultState.Locked)
        }
    }

    @Test
    fun aMalformedRequestFinishesCancelled() {
        ActivityScenario.launch<AutofillAuthActivity>(Intent(context, AutofillAuthActivity::class.java)).use { scenario ->
            assertEquals(Activity.RESULT_CANCELED, awaitResult(scenario)?.first)
        }
    }

    private fun viewModel(entry: String, origin: RequestOrigin = RequestOrigin.Web(WebOrigin("https", "example.com", null))): app.vazie.keep.autofill.AutofillAuthViewModel {
        val (username, passwordId) = ids()
        val request = AuthRequest(AuthRequest.Mode.Select, entry, fingerprint, origin, username, passwordId)
        var created: app.vazie.keep.autofill.AutofillAuthViewModel? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { created = container.newAutofillAuthViewModel(request, null) }
        return created!!
    }

    private fun await(timeout: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(condition(), "timed out")
    }

    private fun fillWithPassword(entry: String): app.vazie.keep.autofill.AutofillAuthViewModel {
        val vm = viewModel(entry)
        await { vm.state.value is app.vazie.keep.autofill.AuthUiState.Unlock }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm.password.edit { replace(0, length, password) }
            vm.unlockWithPassword()
        }
        return vm
    }

    @Test
    fun aPasswordChangedInTheAppIsTheOneFilledNext() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }
        assertEquals(app.vazie.keep.data.UnlockResult.Success, container.repository.unlock(password.toByteArray()))
        val id = app.vazie.keep.core.EntryId(exampleId)
        val outcome = container.repository.edit { session ->
            app.vazie.keep.core.EntryDraft("Example", "alice", "changed-in-app".toByteArray(), "https://example.com/login", "", emptyList(), emptyList()).use { session.updateEntry(id, it) }
        }
        assertTrue(outcome is app.vazie.keep.data.EditOutcome.Saved)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

        val vm = fillWithPassword(exampleId)
        await { vm.state.value is app.vazie.keep.autofill.AuthUiState.Done }
        val payload = (vm.state.value as app.vazie.keep.autofill.AuthUiState.Done).payload
        assertEquals("changed-in-app", payload.password?.useChars { String(it) })
        payload.close()
    }

    @Test
    fun anEntryDeletedInTheAppDisappearsFromTheIndexAndIsRefusedIfStillSuggested() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }
        assertEquals(app.vazie.keep.data.UnlockResult.Success, container.repository.unlock(password.toByteArray()))
        val outcome = container.repository.edit { it.deleteEntry(app.vazie.keep.core.EntryId(exampleId), permanent = true) }
        assertTrue(outcome is app.vazie.keep.data.EditOutcome.Saved)
        await { container.autofillIndexStore.load()?.entries?.none { it.entryId == exampleId } == true }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }

        // A suggestion made before the delete still names the entry: the screen refuses it.
        container.autofillIndexStore.save(
            app.vazie.keep.autofill.AutofillIndex(fingerprint, listOf(app.vazie.keep.autofill.IndexedEntry(exampleId, "Example", "alice", "https://example.com/login"))),
        )
        val vm = fillWithPassword(exampleId)
        await { vm.state.value is app.vazie.keep.autofill.AuthUiState.Failed }
        assertEquals(app.vazie.keep.autofill.AuthProblem.EntryGone, (vm.state.value as app.vazie.keep.autofill.AuthUiState.Failed).problem)
    }

    @Test
    fun anUrlChangedInTheAppUpdatesWhatMatches() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }
        assertEquals(app.vazie.keep.data.UnlockResult.Success, container.repository.unlock(password.toByteArray()))
        container.repository.edit { session ->
            app.vazie.keep.core.EntryDraft("Example", "alice", "p".toByteArray(), "https://moved.example.net", "", emptyList(), emptyList())
                .use { session.updateEntry(app.vazie.keep.core.EntryId(exampleId), it) }
        }
        await { container.autofillIndexStore.load()?.entries?.firstOrNull { it.entryId == exampleId }?.url == "https://moved.example.net" }
        val matcher = app.vazie.keep.autofill.CredentialMatcher(container.core)
        val index = container.autofillIndexStore.load()!!
        assertTrue(matcher.match(index, RequestOrigin.Web(WebOrigin("https", "moved.example.net", null)), emptyList()).any { it.entry.entryId == exampleId })
        assertTrue(matcher.match(index, RequestOrigin.Web(WebOrigin("https", "example.com", null)), emptyList()).none { it.entry.entryId == exampleId })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { container.repository.lock() }
    }

    @Test
    fun eachVaultKeepsItsOwnAutofillIndex() = runBlocking {
        container.appAssociations.add(AppAssociation("com.example.app", "CERT", fingerprint, exampleId))
        val first = checkNotNull(container.registry.active()).id
        val other = File(context.cacheDir, "other.kdbx").also { it.writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("kdbx4/aes256-argon2d.kdbx").use { s -> s.readBytes() }) }
        val result = container.repository.select(other.toURI().toString().replace("file:/", "file:///"))
        assertTrue(result is app.vazie.keep.data.SelectResult.Selected)
        assertTrue(checkNotNull(container.registry.active()).id != first)
        assertNull(container.autofillIndexStore.load(), "the new vault has no index, and the first vault's entries are not offered for it")
        container.repository.activate(first)
        assertEquals(fingerprint, container.autofillIndexStore.load()?.fingerprint, "going back finds the first vault's index untouched")
        container.registry.snapshot.value.vaults.filter { it.id != first }.forEach { container.registry.remove(it.id) }
        other.delete()
        Unit
    }
}
