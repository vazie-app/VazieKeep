package app.vazie.keep.data

import app.vazie.keep.core.KdfSettings
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultManagerTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val forgotten = mutableListOf<String>()
    private val kdf = KdfSettings(65536, 6, 2)

    private fun manager(registry: VaultRegistry = FileVaultRegistry(folder.root.resolve("registry.bin"))) = Triple(
        registry,
        VaultManager(
            core, files, registry,
            testRepository(core, files, RegistryVaultReferenceStore(registry), now = { 1L }).also { },
            forgetData = { forgotten += it.id },
            now = { 7L },
        ),
        Unit,
    )

    private fun request(password: ByteArray? = "pw".toByteArray(), keyFile: ByteArray? = null) =
        CreateRequest("Family", kdf, password, keyFile, keyFile?.let { "content://key" }, keyFile?.let { "family.keyx" })

    @Test
    fun aCreatedVaultIsWrittenOnceVerifiedAndThenRegistered() = runTest {
        val (registry, manager) = manager()
        val result = manager.create(request(), "content://new/Family.kdbx")
        assertIs<CreateResult.Created>(result)
        assertEquals(1, files.newFiles.size)
        val vault = registry.active()!!
        assertEquals("Family", vault.name)
        assertEquals("content://new/Family.kdbx", vault.uri)
        assertEquals(7L, vault.lastUsedAt)
        assertTrue("content://new/Family.kdbx" in files.persisted)
    }

    @Test
    fun theRequestSecretsAreWipedAfterCreating() = runTest {
        val (_, manager) = manager()
        val password = "pw".toByteArray()
        val keyFile = "k".toByteArray()
        manager.create(request(password, keyFile), "content://new/a.kdbx")
        assertTrue(password.all { it == 0.toByte() })
        assertTrue(keyFile.all { it == 0.toByte() })
        assertTrue("content://key" in files.persisted)
    }

    @Test
    fun aFailedWriteLeavesNoRegistryEntryAndRemovesThePartialFile() = runTest {
        val (registry, manager) = manager()
        files.writeNewFailure = VaultFileException.Unavailable()
        val result = manager.create(request(), "content://new/a.kdbx")
        assertEquals(CreateResult.Failure(CreateError.CouldNotWrite), result)
        assertTrue(registry.snapshot.value.vaults.isEmpty())
        assertEquals(listOf("content://new/a.kdbx"), files.deleted)
    }

    @Test
    fun aVaultWithoutAnyCredentialIsRefused() = runTest {
        val (registry, manager) = manager()
        val result = manager.create(request(password = null), "content://new/a.kdbx")
        assertEquals(CreateResult.Failure(CreateError.NoCredentials), result)
        assertTrue(registry.snapshot.value.vaults.isEmpty())
        assertTrue(files.newFiles.isEmpty())
    }

    @Test
    fun forgettingRemovesTheEntryAndGrantsButNeverTheFile() = runTest {
        val (registry, manager) = manager()
        manager.create(request(keyFile = "k".toByteArray()), "content://new/a.kdbx")
        val id = registry.active()!!.id
        manager.forget(id)
        assertNull(registry.find(id))
        assertEquals(listOf(id), forgotten)
        assertTrue("content://new/a.kdbx" in files.released)
        assertTrue("content://key" in files.released)
        assertTrue(files.deleted.isEmpty())
    }

    @Test
    fun renamingTrimsAndIgnoresEmptyNames() = runTest {
        val (registry, manager) = manager()
        manager.create(request(), "content://new/a.kdbx")
        val id = registry.active()!!.id
        manager.rename(id, "  Work  ")
        assertEquals("Work", registry.find(id)?.name)
        manager.rename(id, "   ")
        assertEquals("Work", registry.find(id)?.name)
    }

    @Test
    fun relocatingKeepsTheVaultIdentity() = runTest {
        val (registry, manager) = manager()
        manager.create(request(), "content://new/a.kdbx")
        val id = registry.active()!!.id
        files.headers["content://moved/a.kdbx"] = files.headers.values.first()
        assertNull(manager.relocate(id, "content://moved/a.kdbx"))
        assertEquals("content://moved/a.kdbx", registry.find(id)?.uri)
        assertEquals(id, registry.active()?.id)
    }

    @Test
    fun fileNamesAreSafeForProviders() {
        assertEquals("a_b_c.kdbx", suggestedFileName("a/b:c"))
        assertEquals("Vault.kdbx", suggestedFileName("  ... "))
        assertEquals("Семейный сейф 🔐.kdbx", suggestedFileName("Семейный сейф 🔐"))
    }

    private fun twins(): Triple<VaultRegistry, VaultManager, MutableList<Pair<String, String>>> {
        val registry = FileVaultRegistry(folder.root.resolve("twins.bin"))
        val merged = mutableListOf<Pair<String, String>>()
        val manager = VaultManager(
            core, files, registry,
            testRepository(core, files, RegistryVaultReferenceStore(registry), now = { 1L }),
            forgetData = { forgotten += "data:${it.id}" },
            forgetTwin = { forgotten += "twin:${it.id}" },
            onMerged = { removed, into -> merged += removed to into },
        )
        registry.add(VaultDescriptor(id = "old", name = "Mine", fileName = "Mine.kdbx", uri = "content://files/Mine.kdbx", keyFileUri = "content://key", keyFileName = "k.keyx"), activate = false)
        registry.add(VaultDescriptor(id = "other", name = "Work", fileName = "Work.kdbx", uri = "content://files/Work.kdbx"), activate = false)
        registry.add(VaultDescriptor(id = "new", name = "Mine", fileName = "Mine.kdbx", uri = "content://downloads/77"), activate = true)
        return Triple(registry, manager, merged)
    }

    @Test
    fun theSameFileReachedAnotherWayIsOneEntryAndItsFileIsNotTouched() = runTest {
        val (registry, manager, merged) = twins()
        assertEquals(listOf("old"), manager.mergeDuplicates())
        assertEquals(listOf("other", "new"), registry.snapshot.value.vaults.map { it.id })
        assertEquals("new", registry.active()?.id)
        assertEquals("content://key", registry.active()?.keyFileUri, "the key file choice carries over")
        assertEquals(listOf("old" to "new"), merged)
        assertEquals(listOf("twin:old"), forgotten, "the recovery copies of the database stay")
        assertEquals(listOf("content://files/Mine.kdbx"), files.released)
        assertTrue(files.deleted.isEmpty() && files.newFiles.isEmpty())
        assertEquals(emptyList(), manager.mergeDuplicates())
    }

    @Test
    fun aFileOfTheSameNameWithOtherBytesStaysInTheList() = runTest {
        val (registry, manager, _) = twins()
        files.contents["content://files/Mine.kdbx"] = files.content + byteArrayOf(1)
        assertEquals(emptyList(), manager.mergeDuplicates())
        assertEquals(3, registry.snapshot.value.vaults.size)
        files.contents.remove("content://files/Mine.kdbx")
        files.unreadable += "content://files/Mine.kdbx"
        assertEquals(emptyList(), manager.mergeDuplicates(), "a file that cannot be read is not judged")
    }
}
