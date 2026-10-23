package app.vazie.keep.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QrDecoderTest {
    private val link = "otpauth://totp/GitHub:anna?secret=JBSWY3DPEHPK3PXP&issuer=GitHub&algorithm=SHA1&digits=6&period=30"

    private fun matrix(text: String, margin: Int = 4): BitMatrix =
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to margin))

    private fun pixels(matrix: BitMatrix, scale: Int, dark: Int, light: Int): Triple<IntArray, Int, Int> {
        val width = matrix.width * scale
        val height = matrix.height * scale
        val out = IntArray(width * height) { index ->
            val x = index % width / scale
            val y = index / width / scale
            if (matrix[x, y]) dark else light
        }
        return Triple(out, width, height)
    }

    @Test
    fun readsAQrCodeFromAnImage() {
        val (data, w, h) = pixels(matrix(link), 6, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())

        assertEquals(link, QrDecoder.fromPixels(data, w, h))
    }

    @Test
    fun readsALightCodeOnADarkBackground() {
        val (data, w, h) = pixels(matrix(link), 6, 0xFFFFFFFF.toInt(), 0xFF000000.toInt())

        assertEquals(link, QrDecoder.fromPixels(data, w, h))
    }

    @Test
    fun readsACameraFrameOfLuminanceValues() {
        val (data, w, h) = pixels(matrix(link), 5, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        val luminance = ByteArray(w * h) { (data[it] and 0xFF).toByte() }

        assertEquals(link, QrDecoder.fromLuminance(luminance, w, h))
    }

    @Test
    fun anImageWithoutACodeGivesNothing() {
        val blank = IntArray(200 * 200) { 0xFF808080.toInt() }

        assertNull(QrDecoder.fromPixels(blank, 200, 200))
    }
}
