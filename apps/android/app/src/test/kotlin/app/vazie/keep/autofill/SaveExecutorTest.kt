package app.vazie.keep.autofill

import app.vazie.keep.core.VaultEditException
import app.vazie.keep.data.FileVersion
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultReference
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.MAIL_PASSWORD
import app.vazie.keep.testing.MAIL_RECOVERY_CODE
import app.vazie.keep.testing.ROOT
import app.vazie.keep.testing.VAULT_URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SaveExecutorTest {
    private val files = FakeFileAccess()
    private val writer = FakeDocumentWriter()
    private val copies = InMemoryCopyStore()
    private val reference = VaultReference(VAULT_URI, "vault.kdbx", 0)
    private val persisted = mutableListOf<OpenedVault>()
    private val executor = SaveExecutor(files, writer, copies, { persisted += it }, background = Dispatchers.Unconfined)

    private fun vault(session: FakeVaultSession = FakeVaultSession()) =
        OpenedVault(session, session.info(), FileVersion.of("on disk".toByteArray()), false, true, false, false)

    private suspend fun run(
        vault: OpenedVault,
        choice: SaveChoice,
        username: String = "alice@example.org",
        password: String = "brand-new-password",
    ) = executor.execute(vault, reference, choice, username, password.toByteArray(), "example.com", "https://example.com")

    private val mailEntry = IndexedEntry(MAIL.value, "Example Mail", "alice@example.org", "https://mail.example.org")

    @Test
    fun aNewEntryIsCreatedInTheRootWithTheOriginAsUrlAndSaved() = runTest {
        val session = FakeVaultSession()
        val result = run(vault(session), SaveChoice.Create, username = "carol")
        assertIs<SaveResult.Created>(result)
        assertEquals(listOf(ROOT to "example.com"), session.created)
        val draft = session.drafts.single()
        assertEquals(RecordedDraftLike("example.com", "carol", "brand-new-password", "https://example.com"), RecordedDraftLike(draft.title, draft.username, draft.password, draft.url))
        assertEquals("", draft.notes)
        assertTrue(draft.custom.isEmpty())
        assertEquals(1, writer.written.size)
        assertEquals(1, persisted.size)
        assertEquals(1, writer.backups)
    }

    private data class RecordedDraftLike(val title: String, val username: String, val password: String, val url: String)

    @Test
    fun anUpdateChangesThePasswordAndKeepsEverythingElse() = runTest {
        val session = FakeVaultSession()
        val result = run(vault(session), SaveChoice.Update(mailEntry))
        assertEquals(SaveResult.Updated(MAIL.value), result)
        val draft = session.drafts.single()
        assertEquals("brand-new-password", draft.password)
        assertEquals("Example Mail", draft.title)
        assertEquals("alice@example.org", draft.username)
        assertEquals("https://mail.example.org", draft.url)
        assertEquals(listOf(Triple("Recovery Code", MAIL_RECOVERY_CODE, true)), draft.custom)
        assertEquals(1, writer.written.size)
        assertTrue(session.created.isEmpty())
    }

    @Test
    fun anEmptyUsernameOnTheEntryTakesTheSubmittedOne() = runTest {
        val session = FakeVaultSession()
        session.entries[session.entries.indexOfFirst { it.id == MAIL }] =
            session.entries.first { it.id == MAIL }.copy(username = app.vazie.keep.core.EntryText.Plain(""))
        run(vault(session), SaveChoice.Update(mailEntry), username = "submitted")
        assertEquals("submitted", session.drafts.single().username)
    }

    @Test
    fun theSamePasswordWritesNothingAndMakesNoHistoryVersion() = runTest {
        val session = FakeVaultSession()
        val result = run(vault(session), SaveChoice.Update(mailEntry), password = MAIL_PASSWORD)
        assertEquals(SaveResult.Unchanged(MAIL.value), result)
        assertTrue(session.drafts.isEmpty())
        assertTrue(writer.written.isEmpty())
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun anEntryThatIsGoneIsReported() = runTest {
        val result = run(vault(), SaveChoice.Update(IndexedEntry("ghost", "g", "u", "")))
        assertEquals(SaveResult.Failed(AuthProblem.EntryGone), result)
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun aVaultTheCoreCannotEditOrTheAppCannotWriteIsRefused() = runTest {
        assertEquals(SaveResult.Failed(AuthProblem.NotWritable), run(vault(FakeVaultSession(editable = false)), SaveChoice.Create))
        files.writable = false
        assertEquals(SaveResult.Failed(AuthProblem.NotWritable), run(vault(), SaveChoice.Create))
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun anExternalChangeIsNeverOverwritten() = runTest {
        writer.failure = SaveFailure.ChangedExternally
        assertEquals(SaveResult.Failed(AuthProblem.VaultChangedElsewhere), run(vault(), SaveChoice.Create))
        assertTrue(writer.written.isEmpty())
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun writeFailuresMapToUserSafeProblems() = runTest {
        val cases = mapOf(
            SaveFailure.NotWritable to AuthProblem.NotWritable,
            SaveFailure.ProviderUnsupported to AuthProblem.NotWritable,
            SaveFailure.RestoreNeeded to AuthProblem.RestoreNeeded,
            SaveFailure.Unavailable to AuthProblem.FileUnavailable,
            SaveFailure.InsufficientSpace to AuthProblem.CouldNotSave,
            SaveFailure.VerificationFailed to AuthProblem.CouldNotSave,
            SaveFailure.BackupFailed to AuthProblem.CouldNotSave,
        )
        for ((failure, problem) in cases) {
            writer.failure = failure
            assertEquals(SaveResult.Failed(problem), run(vault(), SaveChoice.Create), failure.name)
        }
        assertTrue(persisted.isEmpty())
    }

    @Test
    fun anInvalidEditIsCouldNotSaveAndNothingIsWritten() = runTest {
        val session = FakeVaultSession().apply { editFailure = VaultEditException.Reason.InvalidText }
        assertEquals(SaveResult.Failed(AuthProblem.CouldNotSave), run(vault(session), SaveChoice.Create))
        assertTrue(writer.written.isEmpty())
    }

    @Test
    fun thePasswordBytesAreWipedWhateverHappens() = runTest {
        val bytes = "wipe-me".toByteArray()
        executor.execute(vault(), reference, SaveChoice.Create, "u", bytes, "t", "")
        assertContentEquals(ByteArray(bytes.size), bytes)
        val second = "wipe-me-too".toByteArray()
        writer.failure = SaveFailure.Unavailable
        executor.execute(vault(), reference, SaveChoice.Create, "u", second, "t", "")
        assertContentEquals(ByteArray(second.size), second)
    }
}
