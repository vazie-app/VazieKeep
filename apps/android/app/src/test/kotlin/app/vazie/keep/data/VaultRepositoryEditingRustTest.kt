package app.vazie.keep.data

import app.vazie.keep.core.CustomFieldDraft
import app.vazie.keep.core.EntryDraft
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.core.SecretField
import app.vazie.keep.testing.FileBackedAccess
import app.vazie.keep.testing.FileDocumentPort
import app.vazie.keep.testing.InMemoryReferenceStore
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The real Rust core and the real writer on real files: nothing here is faked except where the
// file lives.
class VaultRepositoryEditingRustTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val vectors = File(System.getProperty("keep.testVectors"))
    private val password = "correct horse battery staple"

    private class Setup(val repository: VaultRepository, val vault: File, val copies: FileVaultCopyStore, val references: InMemoryReferenceStore)

    private fun setup(source: String, keyFile: String? = null, retain: Boolean = false): Setup {
        val vault = folder.newFile("vault.kdbx").also { vectors.resolve(source).copyTo(it, overwrite = true) }
        val key = keyFile?.let { name -> folder.newFile("key").also { vectors.resolve("keyfiles/$name").copyTo(it, overwrite = true) } }
        val references = InMemoryReferenceStore(
            VaultReference(vault.toURI().toString().replace("file:/", "file:///"), "vault.kdbx", 0, key?.let { it.toURI().toString().replace("file:/", "file:///") }, key?.name),
        )
        val copies = FileVaultCopyStore(folder.root.resolve("Vault Copies"))
        val repository = VaultRepository(
            core = RustKeepCore,
            files = FileBackedAccess(),
            references = references,
            writer = JournaledOverwriteWriter(FileDocumentPort(), copies),
            copies = copies,
            retainUnlockMaterial = { retain },
        )
        return Setup(repository, vault, copies, references)
    }

    private fun draft(title: String, pass: String = "p4ss") =
        EntryDraft(title, "user", pass.toByteArray(), "https://x.example", "notes", listOf(CustomFieldDraft("Pin", "1234".toByteArray(), true)), listOf("t"))

    @Test
    fun editSaveLockUnlockShowsTheChange() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        val original = s.vault.readBytes()
        assertEquals(UnlockResult.Success, s.repository.unlock(password.toByteArray()))
        val fingerprint = (s.repository.state.value as VaultState.Unlocked).info.fingerprint
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId

        val outcome = s.repository.edit { session -> draft("Brand new").use { session.createEntry(root, it) } }
        val id = assertIs<EditOutcome.Saved<app.vazie.keep.core.EntryId>>(outcome).value
        assertFalse(s.repository.hasUnsavedChanges.value)
        assertFalse(original.contentEquals(s.vault.readBytes()))
        assertContentEquals(original, s.copies.backup(fingerprint))
        assertNull(s.copies.unsaved(fingerprint))

        s.repository.lock()
        assertEquals(UnlockResult.Success, s.repository.unlock(password.toByteArray()))
        assertEquals(fingerprint, (s.repository.state.value as VaultState.Unlocked).info.fingerprint)
        assertEquals(EntryText.Plain("Brand new"), s.repository.entry(id).summary.title)
        assertEquals("p4ss", s.repository.reveal(id, SecretField.Password).use { it.useChars { c -> String(c) } })
        assertEquals("1234", s.repository.reveal(id, SecretField.Custom("Pin")).use { it.useChars { c -> String(c) } })
        assertTrue(s.repository.search("Bank").isNotEmpty())
    }

    @Test
    fun aSecondSaveKeepsWorkingAndTheBackupIsThePreviousVersion() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        s.repository.unlock(password.toByteArray())
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId
        val fingerprint = (s.repository.state.value as VaultState.Unlocked).info.fingerprint
        s.repository.edit { it.createGroup(root, "One") }
        val afterFirst = s.vault.readBytes()
        s.repository.edit { it.createGroup(root, "Two") }
        assertContentEquals(afterFirst, s.copies.backup(fingerprint))
        s.repository.lock()
        s.repository.unlock(password.toByteArray())
        assertEquals(setOf("One", "Two"), s.repository.groups().map { it.name }.toSet() intersect setOf("One", "Two"))
    }

    @Test
    fun aFileChangedByAnotherAppIsNeverOverwritten() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        s.repository.unlock(password.toByteArray())
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId
        val elsewhere = vectors.resolve("editable/rich-no-recycle-bin.kdbx").readBytes()
        s.vault.writeBytes(elsewhere)

        val outcome = s.repository.edit { it.createGroup(root, "Mine") }
        assertEquals(SaveFailure.ChangedExternally, assertIs<EditOutcome.NotSaved<*>>(outcome).failure)
        assertContentEquals(elsewhere, s.vault.readBytes())
        assertTrue(s.repository.hasUnsavedChanges.value)
        assertNotNull(s.copies.unsaved((s.repository.state.value as VaultState.Unlocked).info.fingerprint))
    }

    @Test
    fun aPasswordPlusKeyFileVaultSurvivesEditSaveLockUnlock() = runTest {
        val s = setup("keyfiles/vault-password-plus-keyfile.kdbx", keyFile = "keyfile-v2.keyx")
        assertEquals(UnlockResult.Success, s.repository.unlock(password.toByteArray()))
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId
        assertIs<EditOutcome.Saved<*>>(s.repository.edit { s2 -> draft("With key file").use { s2.createEntry(root, it) } })
        s.repository.lock()
        assertEquals(UnlockResult.Success, s.repository.unlock(password.toByteArray()))
        assertEquals(UnlockResult.Failure(UnlockError.WrongPasswordOrKeyFile), run {
            s.repository.lock()
            s.repository.unlock("wrong".toByteArray())
        })
        assertTrue(s.repository.unlock(password.toByteArray()) is UnlockResult.Success)
        assertTrue(s.repository.search("With key").isNotEmpty())
    }

    @Test
    fun biometricMaterialStillOpensTheVaultAfterASave() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx", retain = true)
        s.repository.unlock(password.toByteArray())
        val state = s.repository.state.value as VaultState.Unlocked
        assertTrue(state.materialRetained)
        val material = s.repository.unlockMaterial()!!.useBytes { it.copyOf() }
        s.repository.edit { it.createGroup(state.info.rootGroupId, "After") }
        s.repository.lock()
        assertEquals(UnlockResult.Success, s.repository.unlockWithMaterial(material.copyOf(), useKeyFile = false, expectedFingerprint = state.info.fingerprint))
        assertTrue(s.repository.groups().any { it.name == "After" })
    }

    @Test
    fun deleteFollowsTheRecycleBinAndSavesTheVault() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        s.repository.unlock(password.toByteArray())
        val bank = s.repository.search("Bank").single()
        val outcome = s.repository.edit { it.deleteEntry(bank.id, permanent = false) }
        assertEquals(app.vazie.keep.core.DeleteOutcome.MovedToRecycleBin, assertIs<EditOutcome.Saved<app.vazie.keep.core.DeleteOutcome>>(outcome).value)
        s.repository.lock()
        s.repository.unlock(password.toByteArray())
        val bin = (s.repository.state.value as VaultState.Unlocked).info.recycleBinId
        assertNotNull(bin)
        assertEquals(bin, s.repository.entry(bank.id).summary.groupId)
    }

    @Test
    fun savingIsFastEnoughToNeedNoProgressIndicator() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        s.repository.unlock(password.toByteArray())
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId
        val started = System.nanoTime()
        repeat(5) { n -> s.repository.edit { it.createGroup(root, "g$n") } }
        val perSave = (System.nanoTime() - started) / 5_000_000
        println("save (serialize + backup + write + verify) on the JVM host: $perSave ms")
        assertTrue(perSave < 500)
    }

    // A second device: another repository on the same file, with its own copies.
    private fun otherDevice(s: Setup): VaultRepository {
        val copies = FileVaultCopyStore(folder.newFolder("Other Copies"))
        return VaultRepository(
            core = RustKeepCore,
            files = FileBackedAccess(),
            references = InMemoryReferenceStore(s.references.load()),
            writer = JournaledOverwriteWriter(FileDocumentPort(), copies),
            copies = copies,
        )
    }

    @Test
    fun aFileChangedOnAnotherDeviceIsMergedWithoutAskingForThePassword() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        s.repository.unlock(password.toByteArray())
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId
        val theirs = otherDevice(s)
        theirs.unlock(password.toByteArray())
        assertIs<EditOutcome.Saved<*>>(theirs.edit { session -> draft("Theirs").use { session.createEntry(root, it) } })
        val theirBytes = s.vault.readBytes()

        val outcome = s.repository.edit { session -> draft("Mine").use { session.createEntry(root, it) } }
        assertEquals(SaveFailure.ChangedExternally, assertIs<EditOutcome.NotSaved<*>>(outcome).failure)
        assertContentEquals(theirBytes, s.vault.readBytes())

        val merged = assertIs<MergeOutcome.Merged>(s.repository.mergeChangedFile(null, " (conflict)"))
        assertEquals(SaveOutcome.Saved, merged.save)
        assertEquals(1, merged.report.entriesAdded)
        assertFalse(s.repository.hasUnsavedChanges.value)
        assertTrue(s.repository.search("Theirs").isNotEmpty())
        assertTrue(s.repository.search("Mine").isNotEmpty())

        // The file holds both, and a fresh unlock reads them.
        s.repository.lock()
        assertEquals(UnlockResult.Success, s.repository.unlock(password.toByteArray()))
        assertTrue(s.repository.search("Theirs").isNotEmpty())
        assertTrue(s.repository.search("Mine").isNotEmpty())
        assertNull(s.copies.unsaved((s.repository.state.value as VaultState.Unlocked).info.fingerprint))
    }

    @Test
    fun anEntryChangedOnBothDevicesKeepsBothVersionsAfterTheMerge() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        s.repository.unlock(password.toByteArray())
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId
        val id = assertIs<EditOutcome.Saved<app.vazie.keep.core.EntryId>>(
            s.repository.edit { session -> draft("Shared entry", "p0").use { session.createEntry(root, it) } },
        ).value
        val theirs = otherDevice(s)
        theirs.unlock(password.toByteArray())
        assertIs<EditOutcome.Saved<*>>(theirs.edit { session -> draft("Shared entry", "theirs").use { session.updateEntry(id, it) } })

        Thread.sleep(1100)
        val outcome = s.repository.edit { session -> draft("Shared entry", "mine").use { session.updateEntry(id, it) } }
        assertIs<EditOutcome.NotSaved<*>>(outcome)

        val merged = assertIs<MergeOutcome.Merged>(s.repository.mergeChangedFile(null, " (conflict)"))
        assertEquals(SaveOutcome.Saved, merged.save)
        assertEquals(1, merged.report.conflicts.size)
        assertEquals(2, s.repository.search("Shared entry").size)
        assertTrue(s.repository.search("(conflict)").isNotEmpty())
    }

    @Test
    fun aFileTheSessionKeyDoesNotFitAsksForThePasswordAndRefusesAWrongOne() = runTest {
        val s = setup("editable/rich-aes-argon2d-gzip.kdbx")
        s.repository.unlock(password.toByteArray())
        val root = (s.repository.state.value as VaultState.Unlocked).info.rootGroupId
        // Another database with another key: the session's key does not fit it.
        val elsewhere = vectors.resolve("editable/rich-no-recycle-bin.kdbx").readBytes()
        s.vault.writeBytes(elsewhere)
        assertIs<EditOutcome.NotSaved<*>>(s.repository.edit { it.createGroup(root, "Mine") })

        val asked = assertIs<MergeOutcome.Problem>(s.repository.mergeChangedFile(null, " (conflict)"))
        assertEquals(MergeProblem.NeedsPassword, asked.problem)
        assertContentEquals(elsewhere, s.vault.readBytes())

        val wrong = assertIs<MergeOutcome.Problem>(s.repository.mergeChangedFile("not the password".toByteArray(), " (conflict)"))
        assertEquals(MergeProblem.WrongPassword, wrong.problem)
        assertContentEquals(elsewhere, s.vault.readBytes())
        assertTrue(s.repository.hasUnsavedChanges.value)
    }
}
