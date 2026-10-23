package app.vazie.keep.siteicons

import app.vazie.keep.core.EntryId
import app.vazie.keep.testing.FakeDocumentWriter
import app.vazie.keep.testing.FakeFileAccess
import app.vazie.keep.testing.FakeKeepCore
import app.vazie.keep.testing.FakeVaultSession
import app.vazie.keep.testing.InMemoryCopyStore
import app.vazie.keep.testing.InMemoryReferenceStore
import app.vazie.keep.testing.testRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SiteIconUpdaterTest {
    private val core = FakeKeepCore()
    private val writer = FakeDocumentWriter()
    private val repository = testRepository(core, FakeFileAccess(), InMemoryReferenceStore(), writer, InMemoryCopyStore(), background = Dispatchers.Unconfined)
    private val session get() = core.sessions.single() as FakeVaultSession
    private val asked = mutableListOf<String>()
    private val icons = object : SiteIcons {
        override suspend fun fetch(host: String): ByteArray? {
            asked += host
            return byteArrayOf(1)
        }
    }

    private fun updater(scope: TestScope, enabled: Boolean) =
        SiteIconUpdater(repository, { icons }, { enabled }, scope, background = Dispatchers.Unconfined)

    @Test
    fun switchedOffNothingIsAskedOfAnySite() = runTest(UnconfinedTestDispatcher()) {
        repository.unlock("x".toByteArray())
        session.entryIconHost = "a.example"
        session.iconHosts = listOf("a.example")
        updater(this, enabled = false).entrySaved(EntryId("e"))
        assertTrue(asked.isEmpty())
        assertTrue(session.operations.isEmpty())
    }

    @Test
    fun switchedOnTheSavedEntryGetsTheIconOfItsSite() = runTest(UnconfinedTestDispatcher()) {
        repository.unlock("x".toByteArray())
        session.entryIconHost = "a.example"
        session.iconHosts = listOf("a.example")
        updater(this, enabled = true).entrySaved(EntryId("e"))
        assertEquals(listOf("a.example"), asked)
        assertEquals(listOf("site-icons:a.example"), session.operations)
        assertEquals(1, writer.written.size)
    }

    @Test
    fun anEntryWithoutAWebsiteAsksNothing() = runTest(UnconfinedTestDispatcher()) {
        repository.unlock("x".toByteArray())
        updater(this, enabled = true).entrySaved(EntryId("e"))
        assertTrue(asked.isEmpty())
    }
}
