package com.nurvpn.app.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Matn → QR bitmap (zxing yordamida). */
object QrGenerator {

    /**
     * @param text QR ichiga yoziladigan matn (link, config, URL)
     * @param sizePx kvadrat bitmap o'lchami (piksel)
     * @return Bitmap yoki null (xato bo'lsa)
     */
    fun generate(text: String, sizePx: Int = 768): Bitmap? {
        if (text.isEmpty()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.MARGIN to 1
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            for (x in 0 until sizePx) {
                for (y in 0 until sizePx) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (t: Throwable) {
            null
        }
    }
}
