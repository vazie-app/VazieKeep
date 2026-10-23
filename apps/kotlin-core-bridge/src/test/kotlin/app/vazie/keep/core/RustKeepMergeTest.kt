package app.vazie.keep.core

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Merging two versions of a database (ADR 0023), against the real Rust core.
class RustKeepMergeTest {
    private val password = "correct horse"

    private fun credentials(text: String = password) = VaultCredentials(password = text.toByteArray())

    private fun open(bytes: ByteArray) = runBlocking { RustKeepCore.openVault(bytes, credentials().password!!) }

    private fun draft(title: String, pass: String) = EntryDraft(
        title = title,
        username = "user",
        password = pass.toByteArray(),
        url = "",
        notes = "",
        customFields = emptyList(),
        tags = emptyList(),
    )

    private fun created(): ByteArray = runBlocking {
        RustKeepCore.createVault("Shared", KdfSettings(8192, 1, 1), credentials()).bytes
    }

    private fun saved(session: KeepVaultSession): ByteArray {
        val snapshot = session.prepareSave()
        assertTrue(session.markSaved(snapshot.generation))
        return snapshot.bytes
    }

    private fun base(vararg entries: Pair<String, String>): ByteArray = open(created()).use { first ->
        val root = first.info().rootGroupId
        entries.forEach { (title, pass) -> draft(title, pass).use { first.createEntry(root, it) } }
        saved(first)
    }

    private fun KeepVaultSession.idOf(title: String) = entries().single { it.title == EntryText.Plain(title) }.id

    private fun KeepVaultSession.passwordOf(id: EntryId) = password(id).use { it.useChars { chars -> String(chars) } }

    private fun KeepVaultSession.edit(title: String, pass: String) = draft(title, pass).use { updateEntry(idOf(title), it) }

    // Edits in the same second tie, and a tie is a clash.
    private fun nextSecond() = Thread.sleep(1100)

    @Test
    fun mergesASecondVersionAndMarksTheSessionDirty() {
        val bytes = base("Mail" to "m0")
        open(bytes).use { mine ->
            val theirsBytes = open(bytes).use { theirs ->
                nextSecond()
                theirs.edit("Mail", "m1")
                draft("Shop", "s1").use { theirs.createEntry(theirs.info().rootGroupId, it) }
                saved(theirs)
            }
            assertFalse(mine.isDirty())
            val secret = password.toByteArray()
            val report = mine.merge(theirsBytes, VaultCredentials(password = secret))
            assertTrue(secret.all { it == 0.toByte() }, "the other file's password is wiped")
            assertTrue(report.changed)
            assertEquals(1, report.entriesUpdated)
            assertEquals(1, report.entriesAdded)
            assertTrue(report.conflicts.isEmpty())
            assertTrue(mine.isDirty())
            assertEquals("m1", mine.passwordOf(mine.idOf("Mail")))
            assertEquals(setOf("Mail", "Shop"), mine.entries().map { (it.title as EntryText.Plain).value }.toSet())

            val merged = saved(mine)
            assertFalse(mine.merge(theirsBytes, credentials()).changed)
            assertFalse(mine.isDirty())
            open(merged).use { assertEquals(2, it.entries().size) }
        }
    }

    @Test
    fun aClashKeepsTheLoserAsACopyTheReportNames() {
        val bytes = base("Mail" to "m0")
        open(bytes).use { mine ->
            val theirsBytes = open(bytes).use { theirs ->
                nextSecond()
                mine.edit("Mail", "m-mine")
                nextSecond()
                theirs.edit("Mail", "m-theirs")
                saved(theirs)
            }
            val report = mine.merge(theirsBytes, credentials(), MergeOptions(conflictSuffix = " (конфликт)"))
            assertEquals(1, report.conflicts.size)
            val copy = mine.idOf("Mail (конфликт)")
            assertEquals(listOf(copy), report.conflicts)
            assertEquals("m-mine", mine.passwordOf(copy))
            assertEquals("m-theirs", mine.passwordOf(mine.idOf("Mail")))
        }
    }

    @Test
    fun refusesWhatItCannotAndChangesNothing() {
        val mine = open(base("Mail" to "m0"))
        val other = created()

        assertEquals(
            VaultMergeException.Reason.InvalidCredentials,
            assertFailsWith<VaultMergeException> { mine.merge(other, credentials("nope")) }.reason,
        )
        assertEquals(
            VaultMergeException.Reason.NotAVault,
            assertFailsWith<VaultMergeException> { mine.merge("not a vault".toByteArray(), credentials()) }.reason,
        )
        assertEquals(
            VaultMergeException.Reason.DifferentVault,
            assertFailsWith<VaultMergeException> { mine.merge(other, credentials()) }.reason,
        )
        assertFalse(mine.isDirty())

        val report = mine.merge(other, credentials(), MergeOptions(allowUnrelated = true))
        assertFalse(report.changed, "an empty database adds nothing")

        val keyCredentials = credentials()
        mine.close()
        assertEquals(
            VaultMergeException.Reason.SessionClosed,
            assertFailsWith<VaultMergeException> { mine.merge(other, keyCredentials) }.reason,
        )
        assertNotNull(keyCredentials)
        assertContentEquals(ByteArray(password.length), keyCredentials.password, "a refused call still wipes the password")
    }
}
