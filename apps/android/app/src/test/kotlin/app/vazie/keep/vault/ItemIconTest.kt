package app.vazie.keep.vault

import app.vazie.keep.ui.components.matchingIcons
import app.vazie.keep.ui.icons.ItemIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemIconTest {
    private val russian = mapOf(ItemIcon.Bank to "Банк", ItemIcon.Mail to "Почта", ItemIcon.Money to "Деньги и карты")
    private fun label(icon: ItemIcon) = russian[icon] ?: icon.name

    @Test
    fun everyIconStandsForOneStandardKeePassNumber() {
        assertEquals(ItemIcon.entries.size, ItemIcon.entries.map { it.id }.toSet().size)
        assertTrue(ItemIcon.entries.all { it.id in 0..68 })
    }

    @Test
    fun everyStandardNumberHasADrawing() {
        for (id in 0..68) ItemIcon.forGroup(id)
        assertEquals(ItemIcon.Folder, ItemIcon.forGroup(48))
        assertEquals(ItemIcon.Folder, ItemIcon.forGroup(49), "an open folder is still a folder")
        assertEquals(ItemIcon.Folder, ItemIcon.forGroup(500), "a number outside the standard set")
        assertEquals(ItemIcon.Trash, ItemIcon.forGroup(43))
    }

    @Test
    fun anEntryWithTheDefaultKeyShowsItsLetter() {
        assertNull(ItemIcon.forEntry(0))
        assertEquals(ItemIcon.Bank, ItemIcon.forEntry(37))
        assertEquals(ItemIcon.Mail, ItemIcon.forEntry(25))
        assertEquals(ItemIcon.Key, ItemIcon.forEntry(999))
    }

    @Test
    fun iconsAreFoundByNameInTheAppsLanguageAndByEnglishWords() {
        assertEquals(ItemIcon.entries, matchingIcons("  ", ::label))
        assertEquals(listOf(ItemIcon.Bank), matchingIcons("банк", ::label))
        assertEquals(listOf(ItemIcon.Mail), matchingIcons("EMAIL", ::label))
        assertEquals(listOf(ItemIcon.Money), matchingIcons("card pay", ::label))
        assertTrue(matchingIcons("zzz", ::label).isEmpty())
    }
}
