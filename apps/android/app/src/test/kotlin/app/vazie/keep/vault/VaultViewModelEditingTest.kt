package app.vazie.keep.vault

import app.vazie.keep.core.GroupId
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultState
import app.vazie.keep.siteicons.SiteIcons
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.ROOT
import app.vazie.keep.testing.WEB
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelEditingTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val writer = FakeDocumentWriter()
    private val copies = InMemoryCopyStore()
    private val repository = testRepository(core, FakeFileAccess(), InMemoryReferenceStore(), writer, copies, background = Dispatchers.Unconfined)
    private val session get() = core.sessions.single() as FakeVaultSession

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun vm(): VaultViewModel {
        repository.unlock("x".toByteArray())
        return VaultViewModel(repository, background = Dispatchers.Unconfined)
    }

    @Test
    fun creatingAGroupHappensInTheGroupYouAreBrowsing() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        vm.openGroup(WEB)
        runCurrent()
        vm.requestNewGroup()
        assertEquals(VaultDialog.NewGroup, vm.dialog.value)
        vm.createGroup("  Fresh  ")
        runCurrent()
        assertNull(vm.dialog.value)
        assertEquals(listOf("create-group:Fresh"), session.operations)
        assertEquals(1, writer.written.size)
        val browsing = assertIs<VaultUiState.Browsing>(vm.state.value)
        assertEquals(listOf("Fresh"), browsing.groups.map { it.name })
    }

    @Test
    fun renamingAGroupUpdatesTheListAfterTheSave() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        vm.requestRename(VaultGroup(WEB, ROOT, "Web"))
        vm.renameGroup(VaultGroup(WEB, ROOT, "Web"), " Sites ")
        runCurrent()
        assertEquals(listOf("Sites"), assertIs<VaultUiState.Browsing>(vm.state.value).groups.map { it.name })
    }

    @Test
    fun deletingANonEmptyGroupAsksWithTheCounts() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        vm.requestDelete(VaultGroup(WEB, ROOT, "Web"))
        val dialog = assertIs<VaultDialog.DeleteGroup>(vm.dialog.value)
        assertEquals(1, dialog.entries)
        assertEquals(0, dialog.groups)
        assertFalse(dialog.toBin)
        vm.deleteGroup(dialog.group, permanent = true)
        runCurrent()
        assertEquals(listOf("delete-group:web:true"), session.operations)
    }

    @Test
    fun withARecycleBinTheDialogOffersToMoveItThere() = runTest(dispatcher) {
        repository.unlock("x".toByteArray())
        val bin = GroupId("bin")
        session.groups += VaultGroup(bin, ROOT, "Recycle Bin")
        session.recycleBin = bin
        repository.edit { it.renameGroup(WEB, "Web") }
        val vm = VaultViewModel(repository, background = Dispatchers.Unconfined)
        runCurrent()
        vm.requestDelete(VaultGroup(WEB, ROOT, "Web"))
        assertTrue(assertIs<VaultDialog.DeleteGroup>(vm.dialog.value).toBin)
        vm.requestDelete(VaultGroup(bin, ROOT, "Recycle Bin"))
        assertFalse(assertIs<VaultDialog.DeleteGroup>(vm.dialog.value).toBin)
    }

    @Test
    fun searchResultsFollowEdits() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        vm.onQueryChange("Mail")
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf(MAIL), assertIs<VaultUiState.Searching>(vm.state.value).results?.map { it.id })
        repository.edit { s -> s.deleteEntry(MAIL, permanent = true) }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(emptyList(), assertIs<VaultUiState.Searching>(vm.state.value).results?.map { it.id })
    }

    @Test
    fun aFailedSaveIsReportedAndLeavesTheBannerUp() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        writer.failure = SaveFailure.ChangedExternally
        vm.createGroup("x")
        runCurrent()
        assertEquals(SaveFailure.ChangedExternally, vm.saveFlow.problem.value)
        assertTrue(vm.hasUnsavedChanges.value)
    }

    @Test
    fun thePassOverAddressesSavesAndSaysHowManyEntriesGotAnIcon() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        session.iconsToAssign = 3
        vm.assignIcons()
        runCurrent()
        assertEquals(VaultMessage.IconsAssigned, vm.messages.first())
        assertEquals(3, vm.lastIconCount)
        assertEquals(listOf("assign-icons:3"), session.operations)
        assertEquals(1, writer.written.size)
    }

    private val png = byteArrayOf(1, 2, 3)

    private suspend fun vmWithSites(vararg answering: String): VaultViewModel {
        repository.unlock("x".toByteArray())
        val sites = object : SiteIcons {
            override suspend fun fetch(host: String) = png.takeIf { host in answering }
        }
        return VaultViewModel(repository, background = Dispatchers.Unconfined, siteIcons = sites)
    }

    @Test
    fun siteIconsAreAskedForOnlyAfterAYesAndKeptInTheVault() = runTest(dispatcher) {
        val vm = vmWithSites("a.example")
        runCurrent()
        session.iconHosts = listOf("a.example", "b.example")
        vm.requestSiteIcons()
        assertEquals(VaultDialog.SiteIcons, vm.dialog.value)
        assertTrue(session.operations.isEmpty())
        vm.assignIcons()
        runCurrent()
        assertNull(vm.dialog.value)
        assertNull(vm.iconProgress.value)
        assertEquals(VaultMessage.IconsAssigned, vm.messages.first())
        assertEquals(1, vm.lastIconCount)
        assertEquals(listOf("site-icons:a.example"), session.operations)
        assertEquals(1, writer.written.size)
    }

    @Test
    fun whenNoSiteAnswersNothingIsWrittenAndThePersonIsTold() = runTest(dispatcher) {
        val vm = vmWithSites()
        runCurrent()
        session.iconHosts = listOf("a.example")
        vm.assignIcons()
        runCurrent()
        assertEquals(VaultMessage.IconsUnavailable, vm.messages.first())
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun aPassWithNothingToDoWritesNothing() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        vm.assignIcons()
        runCurrent()
        assertEquals(VaultMessage.IconsNothing, vm.messages.first())
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun aGroupIconIsChangedAndSaved() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        val web = session.groups.single { it.id == WEB }
        vm.requestIcon(web)
        assertIs<VaultDialog.GroupIcon>(vm.dialog.value)
        vm.setGroupIcon(web, 19)
        runCurrent()
        assertNull(vm.dialog.value)
        assertEquals(listOf("group-icon:web:19"), session.operations)
        assertEquals(1, writer.written.size)
    }

    @Test
    fun anInvalidNameIsReportedAsAMessage() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        session.editFailure = VaultEditException.Reason.InvalidText
        vm.createGroup("x")
        runCurrent()
        assertEquals(VaultMessage.InvalidName, vm.messages.first())
    }

    @Test
    fun lockingWithUnsavedChangesAsksFirst() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        writer.failure = SaveFailure.Unavailable
        vm.createGroup("x")
        runCurrent()
        vm.saveFlow.dismiss()

        vm.requestLock()
        assertEquals(VaultDialog.ConfirmLock, vm.dialog.value)
        assertIs<VaultState.Unlocked>(repository.state.value)

        vm.dismissDialog()
        assertIs<VaultState.Unlocked>(repository.state.value)
    }

    @Test
    fun savingBeforeLockingLocksOnlyAfterTheSaveWorked() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        writer.failure = SaveFailure.Unavailable
        vm.createGroup("x")
        runCurrent()
        vm.requestLock()

        vm.lockSavingFirst()
        runCurrent()
        assertIs<VaultState.Unlocked>(repository.state.value)
        assertEquals(SaveFailure.Unavailable, vm.saveFlow.problem.value)

        writer.failure = null
        vm.lockSavingFirst()
        runCurrent()
        assertEquals(VaultState.Locked, repository.state.value)
        assertTrue(copies.unsaved.isEmpty())
    }

    @Test
    fun discardingBeforeLockingDropsTheChangesAndTheirCopy() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        writer.failure = SaveFailure.Unavailable
        vm.createGroup("x")
        runCurrent()
        vm.requestLock()
        vm.lockDiscarding()
        assertEquals(VaultState.Locked, repository.state.value)
        assertTrue(copies.unsaved.isEmpty())
    }

    @Test
    fun lockingACleanVaultNeedsNoQuestion() = runTest(dispatcher) {
        val vm = vm()
        runCurrent()
        vm.requestLock()
        assertNull(vm.dialog.value)
        assertEquals(VaultState.Locked, repository.state.value)
    }

    @Test
    fun theEditControlsFollowWriteAccess() = runTest(dispatcher) {
        val files = FakeFileAccess().apply { writable = false }
        val readOnly = testRepository(FakeKeepCore(), files, InMemoryReferenceStore(), writer, copies, background = Dispatchers.Unconfined)
        readOnly.unlock("x".toByteArray())
        val vm = VaultViewModel(readOnly, background = Dispatchers.Unconfined)
        runCurrent()
        assertFalse(vm.canEdit.value)
        assertTrue(vm.needsWriteGrant.value)
    }

    @Test
    fun theRecoveredCopyBannerCanBeDismissedByDeleting() = runTest(dispatcher) {
        copies.recovered["f1ngerpr1nt"] = "r".toByteArray()
        val vm = vm()
        runCurrent()
        assertTrue(vm.hasRecoveredCopy.value)
        vm.discardRecoveredCopy()
        assertFalse(vm.hasRecoveredCopy.value)
    }
}
