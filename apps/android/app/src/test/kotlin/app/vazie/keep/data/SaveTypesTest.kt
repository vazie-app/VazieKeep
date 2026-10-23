package app.vazie.keep.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SaveTypesTest {
    private fun reference(name: String) = VaultReference("content://x", name, 0)

    @Test
    fun theSuggestedCopyNameKeepsTheExtension() {
        assertEquals("Passwords copy.kdbx", reference("Passwords.kdbx").copyFileName())
        assertEquals("my.vault copy.kdbx", reference("my.vault.kdbx").copyFileName())
        assertEquals("vault copy", reference("vault").copyFileName())
        assertEquals(".hidden copy", reference(".hidden").copyFileName())
    }

    @Test
    fun fileVersionsCompareByContentHashOnlyAndPrintNoMoreThanAPrefix() {
        val a = FileVersion.of("one".toByteArray())
        assertEquals(a, FileVersion.of("one".toByteArray()))
        assertFalse(a == FileVersion.of("two".toByteArray()))
        assertEquals(64, a.sha256.length)
        assertEquals(8 + "FileVersion()".length, a.toString().length)
    }
}
