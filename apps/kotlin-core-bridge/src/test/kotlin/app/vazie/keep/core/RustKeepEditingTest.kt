package app.vazie.keep.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustKeepEditingTest {
    private val vectors = File(System.getProperty("keep.testVectors")).resolve("editable")
    private val password = "correct horse battery staple"

    private fun open(file: String = "rich-aes-argon2d-gzip.kdbx", bytes: ByteArray = vectors.resolve(file).readBytes()) = runBlocking {
        RustKeepCore.openVault(bytes, password.toByteArray())
    }

    private fun draft(title: String = "Created", pass: String = "pw-created", fields: List<CustomFieldDraft> = emptyList()) = EntryDraft(
        title = title,
        username = "user",
        password = pass.toByteArray(),
        url = "https://created.example",
        notes = "note",
        customFields = fields,
        tags = listOf("a", "b"),
    )

    @Test
    fun createsEditsAndMovesAnEntryThenSavesAndReopens() = open().use { session ->
        assertTrue(session.isEditable())
        assertFalse(session.isDirty())
        val root = session.info().rootGroupId
        val web = session.groups().single { it.name == "Web" }.id
        val id = draft(fields = listOf(CustomFieldDraft("Pin", "1234".toByteArray(), true))).use { session.createEntry(root, it) }
        assertTrue(session.isDirty())
        draft(title = "Renamed", pass = "pw-2", fields = listOf(CustomFieldDraft("Pin", "1234".toByteArray(), true))).use { session.updateEntry(id, it) }
        session.moveEntry(id, web)

        val snapshot = session.prepareSave()
        assertTrue(session.isDirty())
        assertTrue(session.markSaved(snapshot.generation))
        assertFalse(session.isDirty())

        open(bytes = snapshot.bytes).use { reopened ->
            val entry = reopened.entries().single { it.id == id }
            assertEquals(EntryText.Plain("Renamed"), entry.title)
            assertEquals(web, entry.groupId)
            assertEquals("pw-2", reopened.password(id).use { it.useChars { chars -> String(chars) } })
            assertEquals(listOf(CustomFieldInfo("Pin", true)), entry.customFields)
        }
    }

    @Test
    fun markSavedIgnoresAnOutdatedGeneration() = open().use { session ->
        val root = session.info().rootGroupId
        draft().use { session.createEntry(root, it) }
        val stale = session.prepareSave()
        draft(title = "Second").use { session.createEntry(root, it) }
        assertFalse(session.markSaved(stale.generation))
        assertTrue(session.isDirty())
    }

    @Test
    fun groupsCanBeCreatedRenamedAndDeleted() = open().use { session ->
        val info = session.info()
        val group = session.createGroup(info.rootGroupId, "Fresh")
        session.renameGroup(group, "Fresher")
        assertEquals("Fresher", session.groups().single { it.id == group }.name)
        assertEquals(DeleteOutcome.MovedToRecycleBin, session.deleteGroup(group, recursive = false, permanent = false))
        assertNotNull(session.info().recycleBinId)
        Unit
    }

    @Test
    fun deletingTheRootOrANonEmptyGroupIsRefused() = open().use { session ->
        val info = session.info()
        assertEquals(
            VaultEditException.Reason.RootGroup,
            assertFailsWith<VaultEditException> { session.deleteGroup(info.rootGroupId, recursive = true, permanent = false) }.reason,
        )
        val web = session.groups().single { it.name == "Web" }.id
        assertEquals(
            VaultEditException.Reason.GroupNotEmpty,
            assertFailsWith<VaultEditException> { session.deleteGroup(web, recursive = false, permanent = false) }.reason,
        )
        assertFalse(session.isDirty())
    }

    @Test
    fun entryDeleteFollowsTheRecycleBinSetting() {
        open().use { session ->
            val entry = session.entries().first { it.title == EntryText.Plain("Example Mail") }
            assertEquals(DeleteOutcome.MovedToRecycleBin, session.deleteEntry(entry.id, permanent = false))
        }
        open("rich-no-recycle-bin.kdbx").use { session ->
            assertNull(session.info().recycleBinId)
            val entry = session.entries().first()
            assertEquals(DeleteOutcome.Deleted, session.deleteEntry(entry.id, permanent = false))
        }
    }

    @Test
    fun invalidFieldNamesAreRejectedWithoutDirtyingTheSession() = open().use { session ->
        val root = session.info().rootGroupId
        val clash = draft(fields = listOf(CustomFieldDraft("Title", "x".toByteArray(), false)))
        val error = assertFailsWith<VaultEditException> { session.createEntry(root, clash) }
        assertEquals(VaultEditException.Reason.InvalidFieldName, error.reason)
        assertFalse(session.isDirty())
    }

    @Test
    fun closedSessionRejectsEditsAndSaves() {
        val session = open()
        val root = session.info().rootGroupId
        session.close()
        assertEquals(
            VaultEditException.Reason.SessionClosed,
            assertFailsWith<VaultEditException> { draft().use { session.createEntry(root, it) } }.reason,
        )
        assertEquals(VaultSaveException.Reason.SessionClosed, assertFailsWith<VaultSaveException> { session.prepareSave() }.reason)
    }

    @Test
    fun drawsPasswordsFromTheCoreGenerator() {
        val options = PasswordOptions(length = 24, symbols = false, excludeAmbiguous = true)
        val first = RustKeepCore.generatePassword(options).use { it.useChars { chars -> String(chars) } }
        val second = RustKeepCore.generatePassword(options).use { it.useChars { chars -> String(chars) } }
        assertEquals(24, first.length)
        assertTrue(first != second)
        assertTrue(first.none { it in "0O1lI" })
        assertTrue(first.all { it.isLetterOrDigit() })
        assertEquals(
            PasswordGeneratorException.Reason.NoCharacterSets,
            assertFailsWith<PasswordGeneratorException> {
                RustKeepCore.generatePassword(PasswordOptions(lowercase = false, uppercase = false, digits = false, symbols = false))
            }.reason,
        )
        assertEquals(
            PasswordGeneratorException.Reason.LengthOutOfRange,
            assertFailsWith<PasswordGeneratorException> { RustKeepCore.generatePassword(PasswordOptions(length = 3)) }.reason,
        )
    }

    @Test
    fun draftsNeverPrintSecretsAndAreWipedOnClose() {
        val secret = "VAZIE_DRAFT_SECRET"
        val draft = draft(pass = secret, fields = listOf(CustomFieldDraft("f", secret.toByteArray(), true)))
        assertFalse(secret in draft.toString() + draft.customFields.single().toString())
        draft.close()
        assertContentEquals(ByteArray(secret.length), draft.password)
    }
}
