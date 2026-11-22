package com.autodeck.headunit.ui

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Gera um Bitmap QR Code de alto contraste para exibir na tela do tablet
 * (ex: QR de Wi-Fi padrão WIFI:T:WPA;S:...;P:...;; no Modo Convidado).
 */
fun generateQrBitmap(content: String, sizePx: Int = 360): Bitmap? {
    if (content.isBlank() || sizePx <= 0) return null
    return runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 2)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()
}
