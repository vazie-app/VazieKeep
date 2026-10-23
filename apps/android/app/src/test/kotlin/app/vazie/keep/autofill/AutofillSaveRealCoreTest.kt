package app.vazie.keep.autofill

import app.vazie.keep.core.EntryId
import app.vazie.keep.core.RustKeepCore
import app.vazie.keep.core.SecretField
import app.vazie.keep.data.FileVaultCopyStore
import app.vazie.keep.data.JournaledOverwriteWriter
import app.vazie.keep.data.OpenOutcome
import app.vazie.keep.data.OpenedVault
import app.vazie.keep.data.SaveFailure
import app.vazie.keep.data.VaultOpener
import app.vazie.keep.data.VaultReference
import app.vazie.keep.testing.FileBackedAccess
import app.vazie.keep.testing.FileDocumentPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// The real core and the real safe writer on real files: a submitted credential in, a reopened
// vault out.
class AutofillSaveRealCoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val vectors = File(System.getProperty("keep.testVectors"))
    private val password = "correct horse battery staple"

    private class Setup(val reference: VaultReference, val vault: File, val opener: VaultOpener, val executor: SaveExecutor, val stored: AutofillIndexStore)

    private fun setup(source: String = "autofill/autofill-fixture.kdbx", keyFile: String? = null): Setup {
        val vault = folder.newFile("vault.kdbx").also { vectors.resolve(source).copyTo(it, overwrite = true) }
        val key = keyFile?.let { name -> folder.newFile("key").also { vectors.resolve("keyfiles/$name").copyTo(it, overwrite = true) } }
        fun uri(file: File) = file.toURI().toString().replace("file:/", "file:///")
        val reference = VaultReference(uri(vault), "vault.kdbx", 0, key?.let(::uri), key?.name)
        val copies = FileVaultCopyStore(folder.root.resolve("Vault Copies"))
        val files = FileBackedAccess()
        val store = FileAutofillIndexStore(folder.root.resolve("index.bin"))
        val associations = FileAppAssociationStore(folder.root.resolve("assoc.bin"))
        val executor = SaveExecutor(
            files,
            JournaledOverwriteWriter(FileDocumentPort(), copies),
            copies,
            { IndexBuilder.persist(it, store, associations) },
            background = Dispatchers.Unconfined,
        )
        return Setup(reference, vault, VaultOpener(RustKeepCore, files, copies), executor, store)
    }

    private suspend fun open(setup: Setup, pass: String = password, retain: Boolean = false): OpenedVault =
        (setup.opener.open(setup.reference, pass.toByteArray(), null, true, retain, null) as OpenOutcome.Opened).vault

    private suspend fun save(setup: Setup, choice: SaveChoice, username: String, newPassword: String, title: String = "t", url: String = "") =
        open(setup).let { vault ->
            try {
                setup.executor.execute(vault, setup.reference, choice, username, newPassword.toByteArray(), title, url)
            } finally {
                vault.session.close()
            }
        }

    private fun passwordOf(vault: OpenedVault, id: String) =
        vault.session.reveal(EntryId(id), SecretField.Password).use { it.useChars { chars -> String(chars) } }

    @Test
    fun updatingAnExistingPasswordIsVisibleAfterReopen() = runBlocking {
        val s = setup()
        val before = open(s)
        val example = before.session.entries().first { (it.title as app.vazie.keep.core.EntryText.Plain).value == "Example" }
        val count = before.session.entries().size
        before.session.close()
        val choice = SaveChoice.Update(IndexedEntry(example.id.value, "Example", "alice", "https://example.com/login"))

        val result = save(s, choice, "alice", "rotated-password")
        assertEquals(SaveResult.Updated(example.id.value), result)

        val after = open(s)
        assertEquals("rotated-password", passwordOf(after, example.id.value))
        assertEquals(count, after.session.entries().size)
        assertEquals("example-pass-2", passwordOf(after, after.session.entries().first { (it.title as app.vazie.keep.core.EntryText.Plain).value == "Example (second account)" }.id.value))
        val detail = after.session.entry(example.id)
        assertEquals(app.vazie.keep.core.EntryText.Plain("https://example.com/login"), detail.summary.url)
        File("build/autofill-save").mkdirs()
        s.vault.copyTo(File("build/autofill-save/updated.kdbx"), overwrite = true)
        after.session.close()
    }

    @Test
    fun aNewCredentialIsCreatedAndImmediatelyMatchable() = runBlocking {
        val s = setup()
        val result = save(s, SaveChoice.Create, "newbie", "fresh-password", "shop.example", "https://shop.example")
        val id = assertIs<SaveResult.Created>(result).entryId

        val after = open(s)
        assertEquals("fresh-password", passwordOf(after, id))
        assertEquals(app.vazie.keep.core.EntryText.Plain("shop.example"), after.session.entry(EntryId(id)).summary.title)
        after.session.close()

        val index = s.stored.load()!!
        assertTrue(index.entries.any { it.entryId == id && it.url == "https://shop.example" && it.username == "newbie" })
        val matcher = CredentialMatcher(RustKeepCore)
        val matches = matcher.match(index, RequestOrigin.Web(WebOrigin("https", "shop.example", null)), emptyList())
        assertEquals(listOf(id), matches.map { it.entry.entryId })
        assertTrue("fresh-password" !in index.entries.joinToString { "${it.title}${it.username}${it.url}" })
        File("build/autofill-save").mkdirs()
        s.vault.copyTo(File("build/autofill-save/created.kdbx"), overwrite = true)
        Unit
    }

    @Test
    fun theIndexSeesAChangedUsernameOrUrlAfterTheSave() = runBlocking {
        val s = setup()
        val before = open(s)
        val example = before.session.entries().first { (it.title as app.vazie.keep.core.EntryText.Plain).value == "Example" }
        before.session.close()
        save(s, SaveChoice.Update(IndexedEntry(example.id.value, "Example", "alice", "")), "alice", "x")
        val index = s.stored.load()!!
        assertEquals("alice", index.entries.first { it.entryId == example.id.value }.username)
    }

    @Test
    fun aFileChangedByAnotherAppIsNeverOverwrittenAndTheVaultIsUntouched() = runBlocking {
        val s = setup()
        val vault = open(s)
        val elsewhere = vectors.resolve("editable/rich-no-recycle-bin.kdbx").readBytes()
        s.vault.writeBytes(elsewhere)
        val result = try {
            s.executor.execute(vault, s.reference, SaveChoice.Create, "u", "p".toByteArray(), "t", "")
        } finally {
            vault.session.close()
        }
        assertEquals(SaveResult.Failed(AuthProblem.VaultChangedElsewhere), result)
        assertContentEquals(elsewhere, s.vault.readBytes())
    }

    @Test
    fun aPasswordPlusKeyFileVaultKeepsOpeningAfterAnAutofillSave() = runBlocking {
        val s = setup("keyfiles/vault-password-plus-keyfile.kdbx", "keyfile-v2.keyx")
        val before = open(s)
        val entry = before.session.entries().first()
        before.session.close()
        val result = save(s, SaveChoice.Update(IndexedEntry(entry.id.value, "t", "u", "")), "u", "kf-rotated")
        assertIs<SaveResult.Updated>(result)
        val after = open(s)
        assertEquals("kf-rotated", passwordOf(after, entry.id.value))
        assertTrue(after.usedKeyFile)
        after.session.close()
    }

    @Test
    fun biometricMaterialStillOpensTheVaultAfterAnAutofillSave() = runBlocking {
        val s = setup()
        val first = open(s, retain = true)
        val material = first.session.unlockMaterial()!!.useBytes { it.copyOf() }
        val fingerprint = first.info.fingerprint
        first.session.close()
        assertIs<SaveResult.Created>(save(s, SaveChoice.Create, "u", "p", "t"))
        val outcome = s.opener.open(s.reference, null, material.copyOf(), false, false, fingerprint)
        val reopened = assertIs<OpenOutcome.Opened>(outcome).vault
        assertEquals(fingerprint, reopened.info.fingerprint)
        reopened.session.close()
    }

    @Test
    fun repeatedSavesKeepTheVaultConsistentAndLeaveNothingOpen() = runBlocking {
        val s = setup()
        val before = open(s)
        val example = before.session.entries().first { (it.title as app.vazie.keep.core.EntryText.Plain).value == "Example" }
        before.session.close()
        val choice = SaveChoice.Update(IndexedEntry(example.id.value, "Example", "alice", ""))
        repeat(25) { n -> assertIs<SaveResult.Updated>(save(s, choice, "alice", "pass-$n")) }
        val after = open(s)
        assertEquals("pass-24", passwordOf(after, example.id.value))
        after.session.close()
    }

    @Test
    fun anEntryInTheRecycleBinIsNotACandidateForAnUpdate() = runBlocking {
        val s = setup()
        val vault = open(s)
        val index = IndexBuilder.build(vault.info, vault.session.groups(), vault.session.entries())
        vault.session.close()
        val plan = SavePlanner.plan(index, RequestOrigin.Web(WebOrigin("https", "example.com", null)), "mallory", CredentialMatcher(RustKeepCore), emptyList())
        assertIs<SavePlan.Create>(plan)
        assertTrue((plan as SavePlan.Create).alternatives.none { it.title == "Trashed example" })
    }
}
