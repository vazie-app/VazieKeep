package app.vazie.keep.data

import app.vazie.keep.core.EntryDraft
import app.vazie.keep.core.KdfSettings
import app.vazie.keep.core.KeepAccess
import app.vazie.keep.core.KeepAccessState
import app.vazie.keep.core.VaultEditException
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.KDBX_HEADER
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.PLUS_ACCESS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryMainVaultStore(var id: String? = null) : MainVaultStore {
    override fun load() = id

    override fun save(id: String) {
        this.id = id
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VaultPolicyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val files = FakeFileAccess()
    private val writer = FakeDocumentWriter()
    private var plan = VaultPlan.Free
    private val store = MemoryMainVaultStore()
    private val registry by lazy { FileVaultRegistry(folder.root.resolve("registry.bin")) }
    private val policy by lazy { VaultPolicy(registry, store) { plan } }
    private val repository by lazy {
        VaultRepository(
            core = core,
            files = files,
            references = RegistryVaultReferenceStore(registry),
            writer = writer,
            copies = InMemoryCopyStore(),
            background = dispatcher,
            registry = registry,
            mayEditVault = { policy.current().canEdit(it) },
            mayAddVault = { policy.current().canAdd },
        )
    }

    private fun vault(id: String) = VaultDescriptor(id = id, name = "Vault $id", fileName = "$id.kdbx", uri = "content://v/$id", lastUsedAt = 1)

    private fun twoVaults() {
        registry.add(vault("a"), activate = true)
        registry.add(vault("b"), activate = false)
    }

    private fun draft() = EntryDraft("Renamed", "u", ByteArray(0), "", "", emptyList(), emptyList())

    @Test
    fun keepPlusLeavesEveryVaultEditableAndLetsMoreBeAdded() {
        val rules = VaultRules.of(VaultPlan.Plus, listOf("a", "b", "c"), storedMain = "a")
        assertTrue(rules.canEdit("a") && rules.canEdit("c"))
        assertTrue(rules.canAdd)
        assertFalse(rules.mustChooseMain)
    }

    @Test
    fun aFreeAccountKeepsItsOneVaultAndCannotAddASecond() {
        val none = VaultRules.of(VaultPlan.Free, emptyList(), null)
        assertTrue(none.canAdd)
        val one = VaultRules.of(VaultPlan.Free, listOf("a"), null)
        assertTrue(one.canEdit("a"))
        assertFalse(one.canAdd)
        assertFalse(one.mustChooseMain)
    }

    @Test
    fun aFreeAccountWithSeveralVaultsEditsOnlyTheMainOne() {
        val asking = VaultRules.of(VaultPlan.Free, listOf("a", "b"), null)
        assertTrue(asking.mustChooseMain)
        assertFalse(asking.canEdit("a") || asking.canEdit("b"))
        assertFalse(asking.canAdd)

        val chosen = VaultRules.of(VaultPlan.Free, listOf("a", "b"), "b")
        assertFalse(chosen.mustChooseMain)
        assertTrue(chosen.canEdit("b"))
        assertFalse(chosen.canEdit("a"))
        assertEquals("b", chosen.mainId)
    }

    @Test
    fun aMainVaultThatWasForgottenIsAskedForAgain() {
        assertTrue(VaultRules.of(VaultPlan.Free, listOf("a", "b"), "gone").mustChooseMain)
        assertFalse(VaultRules.of(VaultPlan.Free, listOf("a"), "gone").mustChooseMain)
    }

    @Test
    fun anUncheckedLeaseAsksNothingAndKeepsOneVaultEditable() {
        val never = VaultRules.of(VaultPlan.Unverified, listOf("a", "b"), null)
        assertFalse(never.mustChooseMain)
        assertTrue(never.canEdit("a"))
        assertFalse(never.canEdit("b"))
        assertFalse(never.canAdd)
        assertTrue(VaultRules.of(VaultPlan.Unverified, listOf("a", "b"), "b").canEdit("b"))
    }

    @Test
    fun severalFilesOfOneDatabaseCountAsOneVault() {
        val same = VaultRules.of(VaultPlan.Free, listOf("a", "b"), null, mapOf("a" to "f1", "b" to "f1"))
        assertFalse(same.mustChooseMain)
        assertTrue(same.canEdit("a") && same.canEdit("b"))
        assertFalse(same.canAdd)
        val mixed = VaultRules.of(VaultPlan.Free, listOf("a", "b", "c"), "a", mapOf("a" to "f1", "b" to "f1", "c" to "f2"))
        assertTrue(mixed.canEdit("a") && mixed.canEdit("b"))
        assertFalse(mixed.canEdit("c"))
        assertTrue(VaultRules.of(VaultPlan.Free, listOf("a", "b"), null, mapOf("a" to "f1")).mustChooseMain)
    }

    @Test
    fun thePlanFollowsTheAccess() {
        assertEquals(VaultPlan.Plus, PLUS_ACCESS.vaultPlan())
        assertEquals(VaultPlan.Free, KeepAccess.Free.vaultPlan())
        assertEquals(VaultPlan.Unverified, KeepAccess(KeepAccessState.PlusExpired, false, true, 10, 0).vaultPlan())
    }

    @Test
    fun theMainVaultIsChosenOnceAndStaysThroughKeepPlus() {
        twoVaults()
        assertTrue(policy.current().mustChooseMain)
        assertFalse(policy.chooseMain("unknown"))
        assertTrue(policy.chooseMain("b"))
        assertEquals("b", store.id)
        assertFalse(policy.chooseMain("a"), "the choice is made once")
        assertEquals("b", policy.current().mainId)

        plan = VaultPlan.Plus
        assertTrue(policy.refresh().canEdit("a"))
        assertFalse(policy.chooseMain("a"))

        plan = VaultPlan.Free
        val again = policy.refresh()
        assertFalse(again.mustChooseMain)
        assertTrue(again.canEdit("b"))
        assertFalse(again.canEdit("a"))
    }

    @Test
    fun anotherVaultOpensForReadingAndRefusesEdits() = runTest(dispatcher) {
        twoVaults()
        policy.chooseMain("a")
        repository.activate("b")
        registry.activate("b")
        repository.unlock("x".toByteArray())

        val unlocked = assertIs<VaultState.Unlocked>(repository.state.value)
        assertFalse(unlocked.canEdit)
        assertTrue(unlocked.readOnlyByPlan)
        assertEquals(EditOutcome.Invalid(VaultEditException.Reason.ReadOnly), repository.edit { it.updateEntry(MAIL, draft()) })
        assertTrue(writer.written.isEmpty())

        plan = VaultPlan.Plus
        policy.refresh()
        repository.refreshEditPolicy()
        val withPlus = assertIs<VaultState.Unlocked>(repository.state.value)
        assertTrue(withPlus.canEdit)
        assertFalse(withPlus.readOnlyByPlan)
        assertIs<EditOutcome.Saved<Unit>>(repository.edit { it.updateEntry(MAIL, draft()) })
    }

    @Test
    fun theMainVaultIsEditedAsBefore() = runTest(dispatcher) {
        twoVaults()
        policy.chooseMain("a")
        repository.unlock("x".toByteArray())
        val unlocked = assertIs<VaultState.Unlocked>(repository.state.value)
        assertTrue(unlocked.canEdit)
        assertFalse(unlocked.readOnlyByPlan)
    }

    @Test
    fun aNewVaultIsNotAddedWithoutKeepPlusButAKnownOneStillOpens() = runTest(dispatcher) {
        registry.add(vault("a"), activate = true)
        files.headers["content://v/new"] = KDBX_HEADER
        files.headers["content://v/a"] = KDBX_HEADER
        assertEquals(SelectResult.Failure(SelectError.NeedsPlus), repository.select("content://v/new"))
        assertNull(registry.findByUri("content://v/new"))
        assertTrue("content://v/new" in files.released)
        assertIs<SelectResult.Selected>(repository.select("content://v/a"))

        plan = VaultPlan.Plus
        assertIs<SelectResult.Selected>(repository.select("content://v/new"))
        assertEquals(2, registry.snapshot.value.vaults.size)
    }

    @Test
    fun aSecondVaultIsNotCreatedWithoutKeepPlus() = runTest(dispatcher) {
        registry.add(vault("a"), activate = true)
        val manager = VaultManager(core, files, registry, repository, forgetData = {}, mayAddVault = { policy.current().canAdd })
        val password = "pw".toByteArray()
        val result = manager.create(CreateRequest("Second", KdfSettings(65536, 6, 2), password, null, null, null), "content://new/Second.kdbx")
        assertEquals(CreateResult.Failure(CreateError.NeedsPlus), result)
        assertTrue(password.all { it == 0.toByte() })
        assertTrue(files.newFiles.isEmpty())
        assertEquals(1, registry.snapshot.value.vaults.size)
    }

    @Test
    fun theMainVaultChoiceCarriesOverWhenItsEntryIsMergedIntoAnother() {
        twoVaults()
        assertTrue(policy.chooseMain("a"))
        policy.merged(removed = "a", into = "b")
        assertEquals("b", store.load())
        policy.merged(removed = "x", into = "a")
        assertEquals("b", store.load())
    }
}
