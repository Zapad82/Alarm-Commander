package com.example.mission

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

/**
 * Утилита для генерации контрольного QR-кода (для печати/сохранения в ванной)
 * и потокового распознавания QR-кодов с камеры через CameraX ImageAnalysis + ZXing.
 */
object QrCodeUtils {

    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap {
        val safeContent = content.ifBlank { "ALARM-COMMANDER-BATHROOM-01" }
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(safeContent, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }
}

/**
 * Анализатор кадров CameraX для декодирования QR-кодов в реальном времени без внешних облачных сервисов.
 */
class ZxingQrCodeAnalyzer(
    private val onQrDetected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.EAN_13,
                    BarcodeFormat.EAN_8,
                    BarcodeFormat.UPC_A,
                    BarcodeFormat.CODE_128,
                    BarcodeFormat.DATA_MATRIX
                ),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    @Volatile
    private var lastDispatchedAtMs = 0L

    override fun analyze(image: ImageProxy) {
        try {
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val width = image.width
            val height = image.height
            val source = PlanarYUVLuminanceSource(
                bytes,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            val result = runCatching { reader.decodeWithState(bitmap) }.getOrNull()
            if (result != null && !result.text.isNullOrBlank()) {
                val now = System.currentTimeMillis()
                if (now - lastDispatchedAtMs > 900L) {
                    lastDispatchedAtMs = now
                    onQrDetected(result.text.trim())
                }
            }
        } catch (_: Exception) {
            // Кадр без QR-кода — штатная ситуация
        } finally {
            reader.reset()
            image.close()
        }
    }
}
