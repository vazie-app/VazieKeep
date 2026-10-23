package app.vazie.keep.autofill

import app.vazie.keep.core.EntryId
import app.vazie.keep.core.EntrySummary
import app.vazie.keep.core.EntryText
import app.vazie.keep.core.GroupId
import app.vazie.keep.core.VaultGroup
import app.vazie.keep.core.VaultInfo
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutofillIndexStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = FileAutofillIndexStore(folder.root.resolve("files/autofill_index.bin"))

    private fun associations() = FileAppAssociationStore(folder.root.resolve("files/autofill_associations.bin"))

    @Test
    fun theIndexRoundTripsUnicodeAndEmptyFields() {
        val index = AutofillIndex("fp", listOf(IndexedEntry("1", "Юникод 🗝", "пользователь", "https://пример.рф"), IndexedEntry("2", "", "", "")))
        val store = store()
        store.save(index)
        val loaded = store.load()!!
        assertEquals("fp", loaded.fingerprint)
        assertEquals(index.entries, loaded.entries)
    }

    @Test
    fun missingOrCorruptFilesAreNoIndex() {
        val store = store()
        assertNull(store.load())
        store.save(AutofillIndex("fp", emptyList()))
        folder.root.resolve("files/autofill_index.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.load())
        folder.root.resolve("files/autofill_index.bin").writeBytes(byteArrayOf(0, 0, 0, 9, 1, 1))
        assertNull(store.load())
    }

    @Test
    fun clearRemovesTheFileAndSavingLeavesNoTemporaryOne() {
        val store = store()
        store.save(AutofillIndex("fp", listOf(IndexedEntry("1", "a", "b", "c"))))
        assertTrue(folder.root.resolve("files").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun aSecondSaveReplacesTheFirst() {
        val store = store()
        store.save(AutofillIndex("one", listOf(IndexedEntry("1", "a", "b", "c"))))
        store.save(AutofillIndex("two", emptyList()))
        assertEquals("two", store.load()?.fingerprint)
        assertEquals(0, store.load()?.entries?.size)
    }

    @Test
    fun oversizedFieldsAreSkippedNotTruncated() {
        val store = store()
        store.save(AutofillIndex("fp", listOf(IndexedEntry("1", "x".repeat(40_000), "u", "https://a.example"), IndexedEntry("2", "ok", "u", "https://b.example"))))
        assertEquals(listOf("2"), store.load()?.entries?.map { it.entryId })
    }

    @Test
    fun associationsAreStoredOnceAndPrunedWhenTheEntryOrVaultGoes() {
        val store = associations()
        val one = AppAssociation("p1", "C1", "fp", "e1")
        store.add(one)
        store.add(one)
        store.add(AppAssociation("p2", "C2", "fp", "e2"))
        store.add(AppAssociation("p3", "C3", "other", "e3"))
        assertEquals(3, store.all().size)
        store.retain("fp", setOf("e1"))
        assertEquals(listOf(one), store.all())
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun associationFilesSurviveANewStoreInstance() {
        associations().add(AppAssociation("p", "C", "fp", "e"))
        assertEquals(1, associations().all().size)
        folder.root.resolve("files/autofill_associations.bin").writeBytes(byteArrayOf(5, 5))
        assertTrue(associations().all().isEmpty())
    }

    @Test
    fun entriesPrintNothingAboutThemselves() {
        assertEquals("IndexedEntry(<redacted>)", IndexedEntry("1", "secret title", "secret user", "https://secret.example").toString())
        assertFalse("secret" in AppAssociation("secret.pkg", "cert", "fp", "e").toString())
        assertEquals("AutofillIndex(entries=0)", AutofillIndex("fp", emptyList()).toString())
    }

    private fun summary(id: String, group: String, title: EntryText = EntryText.Plain(id), username: EntryText = EntryText.Plain("u-$id"), url: EntryText = EntryText.Plain("https://$id.example")) =
        EntrySummary(EntryId(id), GroupId(group), title, username, url, emptyList(), true, false, false, emptyList())

    @Test
    fun theBuilderSkipsTheRecycleBinAndProtectedValues() {
        val root = GroupId("root")
        val bin = GroupId("bin")
        val groups = listOf(VaultGroup(root, null, "Root"), VaultGroup(bin, root, "Recycle Bin"), VaultGroup(GroupId("old"), bin, "Old"))
        val info = VaultInfo("v", 4, 0, root, bin, 3, 4, "fp")
        val entries = listOf(
            summary("live", "root"),
            summary("trashed", "bin"),
            summary("deep", "old"),
            summary("hidden", "root", title = EntryText.Hidden, username = EntryText.Hidden, url = EntryText.Hidden),
        )
        val index = IndexBuilder.build(info, groups, entries)
        assertEquals("fp", index.fingerprint)
        assertEquals(listOf("live", "hidden"), index.entries.map { it.entryId })
        assertEquals(IndexedEntry("hidden", "", "", ""), index.entries.last())
    }

    @Test
    fun persistingAlsoDropsAssociationsOfEntriesThatAreGone() {
        val index = AutofillIndex("fp", listOf(IndexedEntry("e1", "a", "b", "c")))
        val associationStore = associations()
        associationStore.add(AppAssociation("p", "C", "fp", "e1"))
        associationStore.add(AppAssociation("p", "C", "fp", "deleted"))
        IndexBuilder.persist(index, store(), associationStore)
        assertEquals(listOf("e1"), associationStore.all().map { it.entryId })
        IndexBuilder.persist(AutofillIndex("another vault", emptyList()), store(), associationStore)
        assertTrue(associationStore.all().isEmpty())
    }
}
