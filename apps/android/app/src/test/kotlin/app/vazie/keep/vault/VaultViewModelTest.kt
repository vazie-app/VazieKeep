package app.vazie.keep.vault

import app.vazie.keep.testing.testRepository
import app.vazie.keep.data.VaultState
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.MAIL
import app.vazie.keep.testing.ROOT_ENTRY
import app.vazie.keep.testing.WEB
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val core = FakeKeepCore()
    private val repository = testRepository(core, FakeFileAccess(), InMemoryReferenceStore())

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun unlocked(): VaultViewModel {
        repository.unlock("x".toByteArray())
        return VaultViewModel(repository, background = dispatcher)
    }

    @Test
    fun browsesGroupsAsAStack() = runTest(dispatcher) {
        val vm = unlocked()
        runCurrent()
        val root = assertIs<VaultUiState.Browsing>(vm.state.value)
        assertEquals("Fake Vault", root.vaultName)
        assertEquals(listOf(WEB), root.groups.map { it.id })
        assertEquals(listOf(ROOT_ENTRY), root.entries.map { it.id })

        vm.openGroup(WEB)
        runCurrent()
        val web = assertIs<VaultUiState.Browsing>(vm.state.value)
        assertEquals(listOf("Web"), web.path.map { it.name })
        assertEquals(listOf(MAIL), web.entries.map { it.id })
        assertEquals(true, vm.up())
        assertEquals(false, vm.up())
    }

    @Test
    fun searchIsDebouncedAndRunsInRust() = runTest(dispatcher) {
        val vm = unlocked()
        runCurrent()
        vm.onQueryChange("m")
        vm.onQueryChange("ma")
        vm.onQueryChange("mail")
        advanceTimeBy(SEARCH_DEBOUNCE + 50.milliseconds)
        runCurrent()
        val session = core.sessions.single() as FakeVaultSession
        assertEquals(listOf("mail"), session.searches)
        val searching = assertIs<VaultUiState.Searching>(vm.state.value)
        assertEquals(listOf(MAIL), searching.results?.map { it.id })
    }

    @Test
    fun lockClosesTheSessionAndDropsTheData() = runTest(dispatcher) {
        val vm = unlocked()
        runCurrent()
        vm.requestLock()
        runCurrent()
        assertEquals(VaultUiState.Locked, vm.state.value)
        assertEquals(VaultState.Locked, repository.state.value)
        assertEquals(true, core.sessions.single().isClosed)
    }

    @Test
    fun filtersShowFavouritesExpiredAndTagsAcrossGroups() = runTest(dispatcher) {
        repository.unlock("x".toByteArray())
        val session = core.sessions.single() as FakeVaultSession
        session.entries[0] = session.entries[0].copy(tags = listOf("home"), expiresAt = 100)
        session.entries[1] = session.entries[1].copy(tags = listOf("home", "mail"), favorite = true)
        val vm = VaultViewModel(repository, background = dispatcher, nowSeconds = { 200 })
        runCurrent()
        val root = assertIs<VaultUiState.Browsing>(vm.state.value)
        assertEquals(
            listOf(
                FilterChoice(VaultFilter.All, 2),
                FilterChoice(VaultFilter.Favorites, 1),
                FilterChoice(VaultFilter.Expired, 1),
                FilterChoice(VaultFilter.Tag("home"), 2),
                FilterChoice(VaultFilter.Tag("mail"), 1),
            ),
            root.filters,
        )

        vm.selectFilter(VaultFilter.Favorites)
        runCurrent()
        assertEquals(listOf(MAIL), assertIs<VaultUiState.Browsing>(vm.state.value).entries.map { it.id })
        vm.selectFilter(VaultFilter.Expired)
        runCurrent()
        assertEquals(listOf(ROOT_ENTRY), assertIs<VaultUiState.Browsing>(vm.state.value).entries.map { it.id })
        vm.selectFilter(VaultFilter.Tag("home"))
        runCurrent()
        val tagged = assertIs<VaultUiState.Browsing>(vm.state.value)
        assertEquals(listOf(ROOT_ENTRY, MAIL), tagged.entries.map { it.id })
        assertEquals(emptyList(), tagged.groups)

        assertEquals(true, vm.up())
        runCurrent()
        assertEquals(VaultFilter.All, assertIs<VaultUiState.Browsing>(vm.state.value).filter)
        assertEquals(false, vm.up())
    }

    @Test
    fun noFiltersWithoutFavouritesTagsOrExpiry() = runTest(dispatcher) {
        val vm = unlocked()
        runCurrent()
        assertEquals(emptyList(), assertIs<VaultUiState.Browsing>(vm.state.value).filters)
    }
}
