package dev.owreceipt.infra.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import dev.owreceipt.core.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Preview, exported PNG and printer rows all use the same native-width, 300 ppi raster. */
class MonochromeReceiptRenderer(context: Context) : ReceiptRenderer {
    private val typefaces = ReceiptTypefaces(context)

    override suspend fun render(document: ReceiptDocument): RenderedReceipt = withContext(Dispatchers.Default) {
        val bitmap = ReceiptLayout(document, typefaces).bitmap()
        try {
            val packed = BinaryRaster.thresholdAndPack(bitmap)
            val stream = ByteArrayOutputStream()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            RenderedReceipt(bitmap.width, bitmap.height,
                withDensity(stream.toByteArray(), document.profile.nominalPpi), packed)
        } finally { bitmap.recycle() }
    }

    private fun withDensity(png: ByteArray, ppi: Int): ByteArray {
        val pixelsPerMeter = (ppi / .0254).roundToInt()
        val typeAndData = ByteBuffer.allocate(13).put("pHYs".toByteArray(Charsets.US_ASCII))
            .putInt(pixelsPerMeter).putInt(pixelsPerMeter).put(1).array()
        val crc = CRC32().apply { update(typeAndData) }.value.toInt()
        val chunk = ByteBuffer.allocate(21).putInt(9).put(typeAndData).putInt(crc).array()
        return png.copyOfRange(0, 33) + chunk + png.copyOfRange(33, png.size)
    }
}

object BinaryRaster {
    /** Black=1, MSB first. A balanced coverage threshold preserves the intended glyph weights. */
    fun thresholdAndPack(bitmap: Bitmap): ByteArray {
        require(bitmap.width % 8 == 0)
        val packed = ByteArray(bitmap.width / 8 * bitmap.height)
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            for (x in row.indices) {
                val color = row[x]
                val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
                val black = Color.alpha(color) >= 128 && luminance < 128
                row[x] = if (black) Color.BLACK else Color.WHITE
                if (black) {
                    val i = y * bitmap.width / 8 + x / 8
                    packed[i] = (packed[i].toInt() or (0x80 ushr (x % 8))).toByte()
                }
            }
            bitmap.setPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
        }
        return packed
    }
}
