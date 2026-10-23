package app.vazie.keep.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RustKeepVaultSessionTest {
    private val vectors = File(System.getProperty("keep.testVectors")).resolve("kdbx4")
    private val keyFiles = File(System.getProperty("keep.testVectors")).resolve("keyfiles")

    private fun open(file: String, password: String): KeepVaultSession = runBlocking {
        RustKeepCore.openVault(vectors.resolve(file).readBytes(), password.toByteArray())
    }

    private fun synthetic() = open("aes256-argon2d.kdbx", "correct horse battery staple")

    private fun marker() = open("leak-marker.kdbx", MARKER)

    @Test
    fun reportsCoreVersionAndDetectsHeaders() {
        assertEquals("keep-core", RustKeepCore.info().name)
        val header = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(), 0x01, 0x00, 0x04, 0x00)
        assertEquals(VaultFormat.Kdbx(major = 4, minor = 1), RustKeepCore.detectVaultFormat(header))
    }

    @Test
    fun readsStructureAndSummariesWithoutSecrets() = synthetic().use { session ->
        val info = session.info()
        assertEquals("Synthetic Test Vault", info.name)
        val web = session.groups().single { it.name == "Web" && it.parentId == info.rootGroupId }
        val rootEntry = session.entries().first { it.groupId == info.rootGroupId }
        assertEquals(EntryText.Plain("Root Entry"), rootEntry.title)
        val mail = session.entries().single { it.groupId == web.id }
        assertTrue(mail.hasPassword && mail.hasProtectedFields)
        val detail = session.entry(mail.id)
        assertEquals(EntryText.Hidden, detail.customFields.single { it.name == "Recovery Code" }.value)
        assertEquals(listOf(mail.id), session.search("MAIL.EXAMPLE").map { it.id })
    }

    @Test
    fun revealsOneSecretOnDemandAndWipesIt() = marker().use { session ->
        val entry = session.entries().single()
        val secret = session.password(entry.id)
        assertEquals(MARKER, secret.useChars { String(it) })
        assertFalse(MARKER in secret.toString())
        secret.close()
        assertTrue(secret.isClosed)
        assertFailsWith<IllegalStateException> { secret.useBytes { it } }
        session.reveal(entry.id, SecretField.Custom("Marker Field")).use { field ->
            assertEquals(MARKER, field.useChars { String(it) })
        }
    }

    @Test
    fun formattedObjectsNeverContainSecrets() = marker().use { session ->
        val entry = session.entries().single()
        val rendered = listOf(session, session.info(), session.groups(), entry, session.entry(entry.id), session.password(entry.id))
            .joinToString { it.toString() }
        assertFalse(MARKER in rendered)
    }

    @Test
    fun closedSessionRejectsEveryCall() {
        val session = synthetic()
        val entry = session.entries().first()
        session.close()
        session.close()
        assertTrue(session.isClosed)
        val error = assertFailsWith<VaultSessionException> { session.password(entry.id) }
        assertEquals(VaultSessionException.Reason.SessionClosed, error.reason)
        assertFailsWith<VaultSessionException> { session.entries() }
    }

    @Test
    fun sessionsAreIndependent() {
        val a = synthetic()
        val b = open("chacha20-aeskdf-kdbx41.kdbx", "correct horse battery staple")
        a.close()
        b.use { session ->
            val only = session.entries().single()
            assertEquals("correct horse battery staple!", session.password(only.id).use { s -> s.useChars { String(it) } })
        }
    }

    @Test
    fun invalidIdsAndPasswordsMapToTypedErrors() {
        synthetic().use { session ->
            assertEquals(
                VaultSessionException.Reason.InvalidArgument,
                assertFailsWith<VaultSessionException> { session.entry(EntryId("entry[3]")) }.reason,
            )
            assertEquals(
                VaultSessionException.Reason.EntryNotFound,
                assertFailsWith<VaultSessionException> { session.password(EntryId("00000000-0000-0000-0000-000000000000")) }.reason,
            )
        }
        val password = "wrong".toByteArray()
        val error = assertFailsWith<VaultOpenException> {
            runBlocking { RustKeepCore.openVault(vectors.resolve("aes256-argon2d.kdbx").readBytes(), password) }
        }
        assertEquals(VaultOpenException.Reason.InvalidCredentials, error.reason)
        assertTrue(password.all { it == 0.toByte() })
    }

    @Test
    fun keyFilesAloneOrWithAPassword() = runBlocking {
        val keyFile = keyFiles.resolve("keyfile-v2.keyx").readBytes()
        RustKeepCore.openVault(keyFiles.resolve("vault-keyfile-only.kdbx").readBytes(), VaultCredentials(keyFile = keyFile)).use {
            assertEquals("Key File Vault", it.info().name)
        }
        assertTrue(keyFile.all { it == 0.toByte() })

        val both = keyFiles.resolve("vault-password-plus-keyfile.kdbx").readBytes()
        fun credentials(keyFile: String?) = VaultCredentials(
            password = "correct horse battery staple".toByteArray(),
            keyFile = keyFile?.let { keyFiles.resolve(it).readBytes() },
        )
        RustKeepCore.openVault(both, credentials("keyfile-v2.keyx")).close()
        for ((file, reason) in listOf(
            "keyfile-wrong.keyx" to VaultOpenException.Reason.InvalidCredentials,
            null to VaultOpenException.Reason.InvalidCredentials,
            "keyfile-v2-bad-hash.keyx" to VaultOpenException.Reason.InvalidKeyFile,
        )) {
            assertEquals(reason, assertFailsWith<VaultOpenException> { RustKeepCore.openVault(both, credentials(file)) }.reason)
        }
        assertEquals(
            VaultOpenException.Reason.MissingCredentials,
            assertFailsWith<VaultOpenException> { RustKeepCore.openVault(both, VaultCredentials()) }.reason,
        )
    }

    @Test
    fun unlockMaterialReopensTheVaultWithItsKeyFile() = runBlocking {
        val vault = keyFiles.resolve("vault-password-plus-keyfile.kdbx").readBytes()
        val keyFile = { keyFiles.resolve("keyfile-v2.keyx").readBytes() }
        val first = RustKeepCore.openVault(
            vault,
            VaultCredentials(password = "correct horse battery staple".toByteArray(), keyFile = keyFile(), retainUnlockMaterial = true),
        )
        val fingerprint = first.info().fingerprint
        assertEquals(64, fingerprint.length)
        val material = assertNotNull(first.unlockMaterial())
        assertFalse("correct horse" in material.toString())
        val copy = material.useBytes { it.copyOf() }
        material.close()
        assertTrue(material.isClosed)
        first.close()
        RustKeepCore.openVault(vault, VaultCredentials(unlockMaterial = copy.copyOf(), keyFile = keyFile())).use {
            assertEquals(fingerprint, it.info().fingerprint)
            assertNull(it.unlockMaterial())
        }
        assertEquals(
            VaultOpenException.Reason.InvalidCredentials,
            assertFailsWith<VaultOpenException> { RustKeepCore.openVault(vault, VaultCredentials(unlockMaterial = copy)) }.reason,
        )
        assertTrue(copy.all { it == 0.toByte() })
        synthetic().use { assertNull(it.unlockMaterial()) }
    }

    private companion object {
        const val MARKER = "VAZIE_KEEP_SECRET_DO_NOT_LEAK_42"
    }
}
