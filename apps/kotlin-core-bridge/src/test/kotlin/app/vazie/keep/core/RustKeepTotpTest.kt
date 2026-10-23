package app.vazie.keep.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RustKeepTotpTest {
    private val plus = KeepAccess(KeepAccessState.PlusActive, plusFeatures = true, needsOnlineVerification = false, leaseValidUntil = null, observedDeviceTime = 0)
    private val vectors = File(System.getProperty("keep.testVectors")).resolve("editable")

    private fun open() = runBlocking {
        RustKeepCore.openVault(vectors.resolve("rich-aes-argon2d-gzip.kdbx").readBytes(), "correct horse battery staple".toByteArray())
    }

    private fun session(uri: String?): Pair<KeepVaultSession, EntryId> {
        val session = open()
        val fields = listOfNotNull(uri?.let { CustomFieldDraft("otp", it.toByteArray(), true) })
        val id = EntryDraft("Mail", "anna", "pw".toByteArray(), "", "", fields, emptyList()).use { session.createEntry(session.info().rootGroupId, it) }
        return session to id
    }

    @Test
    fun anOtpFieldGivesCodesThroughTheRealCore() {
        val (session, id) = session("otpauth://totp/Example:anna?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ&issuer=Example&digits=8")
        session.use {
            assertTrue(it.entries().single { entry -> entry.id == id }.hasTotp)
            it.totpCode(id, 59, plus).use { code ->
                assertEquals("94287082", code.code.useChars { chars -> String(chars) })
                assertEquals(30, code.period)
                assertEquals(1, code.secondsRemaining)
            }
        }
    }

    @Test
    fun anEntryWithoutOneHasNoCode() {
        val (session, id) = session(null)
        session.use {
            assertFalse(it.entries().single { entry -> entry.id == id }.hasTotp)
            assertEquals(TotpException.Reason.NoCode, assertFailsWith<TotpException> { it.totpCode(id, 59, plus) }.reason)
        }
    }

    @Test
    fun aClosedSessionOrAnUnknownEntryIsATypedError() {
        val (session, id) = session("otpauth://totp/x?secret=GEZDGNBV")
        assertEquals(TotpException.Reason.EntryNotFound, assertFailsWith<TotpException> { session.totpCode(EntryId("00000000-0000-0000-0000-000000000000"), 59, plus) }.reason)
        session.close()
        assertEquals(TotpException.Reason.SessionClosed, assertFailsWith<TotpException> { session.totpCode(id, 59, plus) }.reason)
    }

    @Test
    fun normalizingAKeyGivesACanonicalLinkAndRefusesWhatCannotBeACode() {
        RustKeepCore.normalizeTotp("GEZD GNBV GY3T QOJQ".toByteArray(), plus).use { setup ->
            assertEquals(6, setup.digits)
            assertEquals(30, setup.period)
            assertEquals(TotpAlgorithm.Sha1, setup.algorithm)
            assertTrue(setup.uri.useChars { String(it) }.startsWith("otpauth://totp/"))
        }
        RustKeepCore.normalizeTotp("otpauth://totp/Mail:me?secret=JBSWY3DPEHPK3PXP&issuer=Mail&algorithm=SHA256&digits=8&period=60".toByteArray(), plus).use { setup ->
            assertEquals(TotpAlgorithm.Sha256, setup.algorithm)
            assertEquals(8, setup.digits)
            assertEquals(60, setup.period)
            assertEquals("Mail", setup.issuer)
            assertEquals("me", setup.account)
        }
        assertEquals(TotpException.Reason.InvalidSecret, assertFailsWith<TotpException> { RustKeepCore.normalizeTotp("not a key!".toByteArray(), plus) }.reason)
        assertEquals(TotpException.Reason.UnsupportedType, assertFailsWith<TotpException> { RustKeepCore.normalizeTotp("otpauth://hotp/x?secret=GEZDGNBV&counter=1".toByteArray(), plus) }.reason)
        assertEquals(TotpException.Reason.Empty, assertFailsWith<TotpException> { RustKeepCore.normalizeTotp(" ".toByteArray(), plus) }.reason)
    }

    @Test
    fun withoutKeepPlusTheCoreRefusesBothTheCodeAndTheHelper() {
        val (session, id) = session("otpauth://totp/x?secret=GEZDGNBV")
        session.use {
            assertEquals(TotpException.Reason.NotEntitled, assertFailsWith<TotpException> { it.totpCode(id, 59, KeepAccess.Free) }.reason)
        }
        assertEquals(TotpException.Reason.NotEntitled, assertFailsWith<TotpException> { RustKeepCore.normalizeTotp("GEZDGNBV".toByteArray(), KeepAccess.Free) }.reason)
    }

    @Test
    fun theKnownFieldNamesAreRecognised() {
        assertTrue(isTotpFieldName("otp"))
        assertTrue(isTotpFieldName("TimeOtp-Secret-Base32"))
        assertTrue(isTotpFieldName("TOTP Seed"))
        assertFalse(isTotpFieldName("Recovery Code"))
    }
}
