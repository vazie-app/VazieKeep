package app.vazie.keep.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

object QrDecoder {
    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    fun fromLuminance(luminance: ByteArray, width: Int, height: Int): String? =
        decode(PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false))

    fun fromPixels(argb: IntArray, width: Int, height: Int): String? = decode(RGBLuminanceSource(width, height, argb))

    private fun decode(source: LuminanceSource): String? = attempt(source) ?: attempt(source.invert())

    private fun attempt(source: LuminanceSource): String? = try {
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        null
    }
}
