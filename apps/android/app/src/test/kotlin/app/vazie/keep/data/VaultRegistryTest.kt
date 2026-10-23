package app.vazie.keep.data

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VaultRegistryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun registry(legacy: VaultReference? = null, migrated: (VaultDescriptor) -> Unit = {}) =
        FileVaultRegistry(folder.root.resolve("registry.bin"), { legacy }, migrated)

    private fun vault(id: String, uri: String = "content://v/$id", name: String = "Vault $id") =
        VaultDescriptor(id = id, name = name, fileName = "$name.kdbx", uri = uri, fingerprint = "fp-$id", lastUsedAt = id.hashCode().toLong().coerceAtLeast(1))

    @Test
    fun vaultsSurviveARestartWithTheActiveOneIntact() {
        val first = registry()
        first.add(vault("a"), activate = true)
        first.add(vault("b"), activate = false)
        val reopened = registry()
        assertEquals(listOf("a", "b"), reopened.snapshot.value.vaults.map { it.id })
        assertEquals("a", reopened.active()?.id)
        assertEquals("fp-b", reopened.find("b")?.fingerprint)
    }

    @Test
    fun theSameDocumentIsNeverRegisteredTwice() {
        val registry = registry()
        val first = registry.add(vault("a", uri = "content://same"), activate = true)
        val second = registry.add(vault("b", uri = "content://same"), activate = true)
        assertEquals(first.id, second.id)
        assertEquals(1, registry.snapshot.value.vaults.size)
        assertEquals("a", registry.active()?.id)
    }

    @Test
    fun twoFilesWithOneNameAreTwoVaults() {
        val registry = registry()
        registry.add(vault("a", uri = "content://one/Vault.kdbx", name = "Vault"), activate = true)
        registry.add(vault("b", uri = "content://two/Vault.kdbx", name = "Vault"), activate = false)
        assertEquals(2, registry.snapshot.value.vaults.size)
        assertNotEquals(registry.find("a")?.id, registry.find("b")?.id)
    }

    @Test
    fun renamingChangesOnlyTheLocalLabel() {
        val registry = registry()
        registry.add(vault("a"), activate = true)
        registry.update("a") { it.copy(name = "Family") }
        assertEquals("Family", registry.find("a")?.name)
        assertEquals("content://v/a", registry.find("a")?.uri)
        assertEquals("a", registry.find("a")?.id)
    }

    @Test
    fun removingTheActiveVaultFallsBackToTheMostRecentOne() {
        val registry = registry()
        registry.add(vault("a").copy(lastUsedAt = 5), activate = true)
        registry.add(vault("b").copy(lastUsedAt = 9), activate = false)
        registry.add(vault("c").copy(lastUsedAt = 7), activate = false)
        assertEquals("a", registry.remove("a")?.id)
        assertEquals("b", registry.active()?.id)
        registry.remove("b")
        registry.remove("c")
        assertNull(registry.active())
        assertTrue(registry().snapshot.value.vaults.isEmpty())
    }

    @Test
    fun aDamagedFileIsAnEmptyList() {
        folder.root.resolve("registry.bin").writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        assertTrue(registry().snapshot.value.vaults.isEmpty())
    }

    @Test
    fun theSingleVaultOfAnOlderInstallBecomesTheFirstDescriptor() {
        var migrated: VaultDescriptor? = null
        val legacy = VaultReference("content://old", "Old.kdbx", 3, "content://key", "old.key")
        val registry = registry(legacy) { migrated = it }
        val only = registry.snapshot.value.vaults.single()
        assertEquals("content://old", only.uri)
        assertEquals("content://key", only.keyFileUri)
        assertEquals(only.id, registry.active()?.id)
        assertEquals(only.id, migrated?.id)
        var again: VaultDescriptor? = null
        registry(legacy) { again = it }
        assertNull(again)
    }

    @Test
    fun theDescriptorNeverPrintsItsLocation() {
        assertTrue("content://" !in vault("a").toString())
    }

    @Test
    fun theRegistryFileHoldsNoSecretShapedData() {
        val registry = registry()
        registry.add(vault("a"), activate = true)
        val text = folder.root.resolve("registry.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("password" !in text.lowercase())
    }

    @Test
    fun scopedFilesDoNotCollideBetweenVaults() {
        val scoped = VaultScopedFile(folder.root, "biometric_unlock", "bin") { "a" }
        assertNotEquals(scoped.forId("a"), scoped.forId("b"))
        assertEquals(scoped.forId("a"), scoped.current())
    }

    @Test
    fun theReferenceStoreFollowsTheActiveVault() {
        val registry = registry()
        val store = RegistryVaultReferenceStore(registry) { 11 }
        assertNull(store.load())
        registry.add(vault("a"), activate = true)
        registry.add(vault("b"), activate = false)
        assertEquals("content://v/a", store.load()?.uri)
        registry.activate("b")
        assertEquals("content://v/b", store.load()?.uri)
        store.clear()
        assertEquals(listOf("a"), registry.snapshot.value.vaults.map { it.id })
    }
}
