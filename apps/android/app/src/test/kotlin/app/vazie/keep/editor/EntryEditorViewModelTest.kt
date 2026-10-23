package app.vazie.keep.editor

import androidx.lifecycle.SavedStateHandle
import app.vazie.keep.core.PasswordOptions
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultRepository
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.MAIL_PASSWORD
import app.vazie.keep.testing.MAIL_RECOVERY_CODE
import app.vazie.keep.testing.PLUS_ACCESS
import app.vazie.keep.testing.ROOT
import app.vazie.keep.testing.WEB
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class EntryEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val writer = FakeDocumentWriter()
    private val copies = InMemoryCopyStore()
    private val files = FakeFileAccess()
    private val repository: VaultRepository =
        testRepository(core, files, InMemoryReferenceStore(), writer, copies, background = Dispatchers.Unconfined)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val session get() = core.sessions.single() as FakeVaultSession

    private suspend fun editor(entry: String? = null, group: String? = null): EntryEditorViewModel {
        repository.unlock("x".toByteArray())
        val handle = SavedStateHandle(listOfNotNull(entry?.let { EDITOR_ENTRY_ARG to it }, group?.let { EDITOR_GROUP_ARG to it }).toMap())
        return EntryEditorViewModel(handle, repository, core, background = Dispatchers.Unconfined)
    }

    private suspend fun totpEditor(allowed: Boolean = true): EntryEditorViewModel {
        repository.unlock("x".toByteArray())
        val handle = SavedStateHandle(mapOf(EDITOR_GROUP_ARG to ROOT.value))
        return EntryEditorViewModel(handle, repository, core, background = Dispatchers.Unconfined, access = { if (allowed) PLUS_ACCESS else app.vazie.keep.core.KeepAccess.Free })
    }

    @Test
    fun theOneTimeCodeHelperIsOfferedOnlyWithKeepPlus() = runTest(dispatcher) {
        assertTrue(totpEditor(allowed = true).canAddTotp)
        assertFalse(totpEditor(allowed = false).canAddTotp)
    }

    @Test
    fun aSecretKeyBecomesAProtectedOtpField() = runTest(dispatcher) {
        val vm = totpEditor()
        runCurrent()

        assertNull(vm.addTotp("GEZDGNBVGY3TQOJQ"))

        val field = vm.fields.custom.single()
        assertEquals("otp", field.name.text.toString())
        assertTrue(field.value.text.toString().startsWith("otpauth://totp/"))
        assertTrue(field.isProtected.value)
    }

    @Test
    fun addingACodeTwiceReplacesTheFieldInsteadOfAddingAnother() = runTest(dispatcher) {
        val vm = totpEditor()
        runCurrent()

        vm.addTotp("GEZDGNBVGY3TQOJQ")
        vm.addTotp("otpauth://totp/Mail:anna?secret=JBSWY3DPEHPK3PXP&issuer=Mail")

        assertEquals(1, vm.fields.custom.size)
        assertTrue(vm.fields.custom.single().value.text.toString().contains("JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun withoutKeepPlusNothingIsAddedEvenIfTheHelperIsCalled() = runTest(dispatcher) {
        val vm = totpEditor(allowed = false)
        runCurrent()

        assertEquals(app.vazie.keep.core.TotpException.Reason.NotEntitled, vm.addTotp("GEZDGNBVGY3TQOJQ"))
        assertTrue(vm.fields.custom.isEmpty())
    }

    @Test
    fun aBadKeyIsRefusedAndLeavesTheFieldsAlone() = runTest(dispatcher) {
        val vm = totpEditor()
        runCurrent()

        assertEquals(app.vazie.keep.core.TotpException.Reason.InvalidSecret, vm.addTotp("not a key!"))
        assertTrue(vm.fields.custom.isEmpty())
    }

    @Test
    fun loadsAnExistingEntryIncludingItsProtectedValues() = runTest(dispatcher) {
        val vm = editor(MAIL.value)
        runCurrent()
        val ready = assertIs<EditorUiState.Ready>(vm.state.value)
        assertFalse(ready.isNew)
        assertEquals(WEB, ready.group)
        assertEquals("Example Mail", vm.fields.title.text.toString())
        assertEquals("alice@example.org", vm.fields.username.text.toString())
        assertEquals(MAIL_PASSWORD, vm.fields.password.text.toString())
        val custom = vm.fields.custom.single()
        assertEquals("Recovery Code", custom.name.text.toString())
        assertEquals(MAIL_RECOVERY_CODE, custom.value.text.toString())
        assertTrue(custom.isProtected.value)
        assertFalse(vm.hasChanges())
    }

    @Test
    fun groupChoicesAreSortedPathsWithTheRootFirst() = runTest(dispatcher) {
        val vm = editor(group = ROOT.value)
        runCurrent()
        val ready = assertIs<EditorUiState.Ready>(vm.state.value)
        assertEquals(listOf("Fake Vault", "Web"), ready.groups.map { it.path })
        assertEquals(ROOT, ready.group)
        assertTrue(ready.isNew)
    }

    @Test
    fun savingANewEntryCreatesItInTheChosenGroupAndWipesTheFields() = runTest(dispatcher) {
        val vm = editor(group = ROOT.value)
        runCurrent()
        vm.fields.title.edit { replace(0, length, "Brand new") }
        vm.fields.password.edit { replace(0, length, "s3cret") }
        assertTrue(vm.hasChanges())
        vm.selectGroup(WEB)
        vm.save()
        runCurrent()
        assertEquals(listOf(WEB to "Brand new"), session.created)
        assertEquals(EditorEvent.Saved, vm.events.first())
        assertEquals("", vm.fields.password.text.toString())
        assertEquals(1, writer.written.size)
    }

    @Test
    fun aFailedSaveOfANewEntryTurnsTheEditorIntoAnEditSoSavingAgainDoesNotDuplicate() = runTest(dispatcher) {
        val vm = editor(group = ROOT.value)
        runCurrent()
        vm.fields.title.edit { replace(0, length, "Once") }
        writer.failure = SaveFailure.Unavailable
        vm.save()
        runCurrent()
        assertEquals(SaveFailure.Unavailable, vm.saveFlow.problem.value)
        assertFalse(assertIs<EditorUiState.Ready>(vm.state.value).isNew)
        assertFalse(vm.isNew)

        writer.failure = null
        vm.saveFlow.dismiss()
        vm.save()
        runCurrent()
        assertEquals(1, session.created.size)
        assertEquals(1, session.updated.size)
        assertEquals(1, writer.written.size)
    }

    @Test
    fun editingMovesTheEntryWhenTheGroupChanged() = runTest(dispatcher) {
        val vm = editor(MAIL.value)
        runCurrent()
        vm.selectGroup(ROOT)
        vm.save()
        runCurrent()
        assertEquals(listOf("move:mail:root"), session.operations)
        assertEquals(1, session.updated.size)
    }

    @Test
    fun theDraftIsTrimmedAndKeepsEveryProtectedFlag() = runTest(dispatcher) {
        val vm = editor(MAIL.value)
        runCurrent()
        vm.addField()
        vm.addField()
        vm.fields.custom[1].name.edit { replace(0, length, " Pin ") }
        vm.fields.custom[1].value.edit { replace(0, length, "1234") }
        vm.fields.custom[1].isProtected.value = true
        vm.fields.url.edit { replace(0, length, "  https://example.org  ") }
        vm.addTag("a, b")
        vm.fields.tagInput.edit { replace(0, length, ";; c ,") }
        vm.save()
        runCurrent()
        val draft = session.drafts.single()
        assertEquals("https://example.org", draft.url)
        assertEquals(listOf("a", "b", "c"), draft.tags)
        assertEquals(
            listOf(Triple("Recovery Code", MAIL_RECOVERY_CODE, true), Triple("Pin", "1234", true)),
            draft.custom,
        )
        assertEquals(MAIL_PASSWORD, draft.password)
    }

    @Test
    fun aRemovedCustomFieldIsGone() = runTest(dispatcher) {
        val vm = editor(MAIL.value)
        runCurrent()
        vm.removeField(vm.fields.custom.single().key)
        assertTrue(vm.hasChanges())
        vm.save()
        runCurrent()
        assertTrue(session.drafts.single().custom.isEmpty())
    }

    @Test
    fun leavingWithoutChangesIsNotAChange() = runTest(dispatcher) {
        val vm = editor(group = ROOT.value)
        runCurrent()
        assertFalse(vm.hasChanges())
        vm.addTag("a")
        assertTrue(vm.hasChanges())
        vm.removeTag("a")
        assertFalse(vm.hasChanges())
        vm.setFavorite(true)
        assertTrue(vm.hasChanges())
        vm.setFavorite(false)
        vm.setExpiry(1_791_244_800)
        assertTrue(vm.hasChanges())
        vm.setExpiry(null)
        assertFalse(vm.hasChanges())
    }

    @Test
    fun theGeneratedPasswordIsHiddenUntilAskedAndNeverUsedUntilConfirmed() = runTest(dispatcher) {
        val vm = editor(group = ROOT.value)
        runCurrent()
        vm.openGenerator()
        val state = vm.generator.value
        assertTrue(state.generated)
        assertNull(state.revealed)
        assertEquals("", vm.fields.password.text.toString())

        vm.revealGenerated()
        assertEquals("g".repeat(20), vm.generator.value.revealed)
        vm.hideGenerated()
        assertNull(vm.generator.value.revealed)

        vm.setGeneratorOptions(PasswordOptions(length = 12))
        assertNull(vm.generator.value.revealed)
        vm.useGenerated()
        assertEquals("g".repeat(12), vm.fields.password.text.toString())
        assertFalse(vm.generator.value.generated)
    }

    @Test
    fun closingTheGeneratorDropsTheGeneratedValue() = runTest(dispatcher) {
        val vm = editor(group = ROOT.value)
        runCurrent()
        vm.openGenerator()
        vm.revealGenerated()
        vm.closeGenerator()
        assertNull(vm.generator.value.revealed)
        assertFalse(vm.generator.value.generated)
        assertEquals("", vm.fields.password.text.toString())
    }

    @Test
    fun hideRequestsAndLockingWipeTheEditor() = runTest(dispatcher) {
        val vm = editor(MAIL.value)
        runCurrent()
        vm.openGenerator()
        vm.revealGenerated()
        repository.requestHideSecrets()
        runCurrent()
        assertNull(vm.generator.value.revealed)

        repository.lock()
        runCurrent()
        assertEquals(EditorUiState.Locked, vm.state.value)
        assertEquals("", vm.fields.password.text.toString())
        assertTrue(vm.fields.custom.isEmpty())
    }

    @Test
    fun nothingSecretIsWrittenToTheSavedStateHandle() = runTest(dispatcher) {
        repository.unlock("x".toByteArray())
        val handle = SavedStateHandle(mapOf(EDITOR_ENTRY_ARG to MAIL.value))
        val vm = EntryEditorViewModel(handle, repository, core, background = Dispatchers.Unconfined)
        runCurrent()
        vm.openGenerator()
        vm.revealGenerated()
        assertEquals(setOf(EDITOR_ENTRY_ARG), handle.keys())
        assertNotNull(vm.fields.password.text)
    }

    @Test
    fun anEntryThatIsGoneIsReportedNotFound() = runTest(dispatcher) {
        val vm = editor("missing")
        runCurrent()
        assertEquals(EditorUiState.NotFound, vm.state.value)
    }

    @Test
    fun editingKeepsTheFavouriteTagsAndExpiry() = runTest(dispatcher) {
        repository.unlock("x".toByteArray())
        session.entries[1] = session.entries[1].copy(tags = listOf("mail"), favorite = true, expiresAt = 1_791_244_800)
        val vm = EntryEditorViewModel(SavedStateHandle(mapOf(EDITOR_ENTRY_ARG to MAIL.value)), repository, core, background = Dispatchers.Unconfined)
        runCurrent()
        assertFalse(vm.hasChanges())
        assertEquals(listOf("mail"), vm.knownTags.value)
        vm.fields.username.edit { replace(0, length, "bob") }
        vm.save()
        runCurrent()
        val draft = session.drafts.single()
        assertEquals(listOf("mail"), draft.tags)
        assertTrue(draft.favorite)
        assertEquals(1_791_244_800, draft.expiresAt)
    }

    @Test
    fun attachmentsAreRemovedAndAddedWhenTheEntryIsSaved() = runTest(dispatcher) {
        repository.unlock("x".toByteArray())
        session.attachments[MAIL] = linkedMapOf("old.txt" to "old".toByteArray())
        files.attachmentFiles["content://picked/scan.pdf"] = "%PDF".toByteArray()
        val vm = EntryEditorViewModel(
            SavedStateHandle(mapOf(EDITOR_ENTRY_ARG to MAIL.value)),
            repository,
            core,
            background = Dispatchers.Unconfined,
            access = { PLUS_ACCESS },
        )
        runCurrent()
        assertTrue(vm.canAddAttachments)
        assertEquals(listOf("old.txt"), vm.fields.attachments.map { it.name })
        vm.removeAttachment(vm.fields.attachments.single().key)
        vm.addAttachmentFrom("content://picked/scan.pdf")
        runCurrent()
        assertEquals(listOf("scan.pdf"), vm.fields.attachments.map { it.name })
        assertTrue(vm.hasChanges())
        vm.save()
        runCurrent()
        assertEquals(
            listOf("remove-attachment:mail:old.txt", "add-attachment:mail:scan.pdf"),
            session.operations.filter { "attachment" in it },
        )
        assertEquals("%PDF", session.attachments[MAIL]?.get("scan.pdf")?.decodeToString())
    }

    @Test
    fun withoutKeepPlusAttachmentsCanOnlyBeRemoved() = runTest(dispatcher) {
        val vm = editor(MAIL.value)
        runCurrent()
        assertFalse(vm.canAddAttachments)
    }

    @Test
    fun aFileThatIsTooLargeIsReportedAndNotAdded() = runTest(dispatcher) {
        val vm = editor(group = ROOT.value)
        runCurrent()
        files.attachmentFiles["content://picked/huge.bin"] = ByteArray((app.vazie.keep.core.MAX_ATTACHMENT_BYTES + 1).toInt())
        vm.addAttachmentFrom("content://picked/huge.bin")
        runCurrent()
        assertEquals(app.vazie.keep.data.AttachmentFile.TooLarge, vm.attachmentProblem.value)
        assertTrue(vm.fields.attachments.isEmpty())
    }
}
