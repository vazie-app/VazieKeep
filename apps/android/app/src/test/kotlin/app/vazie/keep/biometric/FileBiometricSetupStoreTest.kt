package app.vazie.keep.biometric

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class FileBiometricSetupStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { folder.root.resolve("no_backup/biometric_unlock.bin") }
    private val store by lazy { FileBiometricSetupStore(file) }

    @Test
    fun activeRecordRoundTrips() {
        val record = BiometricRecord.Active("content://v", "fp", "alias", true, ByteArray(12) { 1 }, ByteArray(49) { 2 })
        store.save(record)
        val loaded = assertIs<BiometricRecord.Active>(store.load())
        assertEquals("content://v", loaded.vaultUri)
        assertEquals("fp", loaded.fingerprint)
        assertEquals("alias", loaded.alias)
        assertEquals(true, loaded.keyFileRequired)
        assertContentEquals(record.iv, loaded.iv)
        assertContentEquals(record.ciphertext, loaded.ciphertext)
        assertContentEquals(record.associatedData(), loaded.associatedData())
        assertFalse(folder.root.resolve("no_backup/biometric_unlock.bin.tmp").exists())
    }

    @Test
    fun invalidatedRecordKeepsNoSecret() {
        store.save(BiometricRecord.Invalidated("content://v"))
        assertEquals(BiometricRecord.Invalidated("content://v"), store.load())
        assertEquals(8 + "content://v".length, file.length().toInt())
    }

    @Test
    fun damagedOrForeignFilesReadAsNeedingSetup() {
        assertNull(store.load())
        file.parentFile.mkdirs()
        for (bytes in listOf(byteArrayOf(1, 2, 3), ByteArray(64), "VKBU\u0001\u0001".toByteArray())) {
            file.writeBytes(bytes)
            assertEquals(BiometricRecord.Invalidated(null), store.load())
        }
    }

    @Test
    fun clearRemovesTheFile() {
        store.save(BiometricRecord.Invalidated(null))
        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun associatedDataCoversEveryPublicField() {
        val base = BiometricRecord.Active("u", "f", "a", false, ByteArray(0), ByteArray(0))
        val variants = listOf(
            BiometricRecord.Active("u2", "f", "a", false, ByteArray(0), ByteArray(0)),
            BiometricRecord.Active("u", "f2", "a", false, ByteArray(0), ByteArray(0)),
            BiometricRecord.Active("u", "f", "a2", false, ByteArray(0), ByteArray(0)),
            BiometricRecord.Active("u", "f", "a", true, ByteArray(0), ByteArray(0)),
        )
        variants.forEach { assertFalse(it.associatedData().contentEquals(base.associatedData())) }
    }
}
