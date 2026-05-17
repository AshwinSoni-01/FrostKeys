package helium314.keyboard.latin.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.aureusapps.android.webpandroid.decoder.WebPDecodeListener
import com.aureusapps.android.webpandroid.decoder.WebPDecoder
import com.aureusapps.android.webpandroid.decoder.WebPInfo
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoder
import java.io.File

class AnimatedStickerProcessor(private val context: Context) {

    fun createWhatsAppAnimatedSticker(sourceFile: File): File? {
        val targetSize = 512
        val maxFileSize = 500 * 1024 // 500 KB WhatsApp ceiling
        val outputDir = File(context.filesDir, "stickers/klipy").apply { if (!exists()) mkdirs() }
        val outputFile = File(outputDir, "animated_${sourceFile.nameWithoutExtension}.webp")

        if (outputFile.exists()) {
            Log.d("StickerProcessor", "Using cached sticker: ${outputFile.absolutePath}")
            return outputFile
        }

        Log.d("StickerProcessor", "Creating animated sticker from ${sourceFile.absolutePath}")
        var encoder: WebPAnimEncoder? = null
        val frames = mutableListOf<Bitmap>()
        try {
            // 1. Decode source file into frames
            val decoder = WebPDecoder(context)
            val timestamps = mutableListOf<Long>()

            decoder.addDecodeListener(object : WebPDecodeListener {
                override fun onInfoDecoded(info: WebPInfo) {
                    Log.d("StickerProcessor", "Info decoded: ${info.width}x${info.height}, frames=${info.frameCount}")
                }
                override fun onFrameDecoded(index: Int, timestamp: Long, bitmap: Bitmap, uri: Uri) {
                    Log.v("StickerProcessor", "Decoding frame $index at $timestamp")
                    val processedFrame = resizeAndPadFrame(bitmap, targetSize)
                    frames.add(processedFrame)
                    timestamps.add(timestamp)
                }
            })
            decoder.setDataSource(Uri.fromFile(sourceFile))
            decoder.decodeFrames()
            Log.d("StickerProcessor", "Finished decoding. Total frames: ${frames.size}")

            if (frames.isEmpty()) {
                Log.e("StickerProcessor", "No frames decoded from ${sourceFile.name}")
                return null
            }

            // 2. Initialize the libwebp native encoder
            encoder = WebPAnimEncoder(context, targetSize, targetSize)
            for (i in frames.indices) {
                encoder.addFrame(timestamps[i], frames[i])
            }

            // 3. Assemble and save
            val lastTimestamp = if (timestamps.size > 1) {
                timestamps.last() + (timestamps.last() - timestamps[timestamps.size - 2])
            } else {
                timestamps.last() + 100L
            }
            val outputUri = Uri.fromFile(outputFile)
            encoder.assemble(lastTimestamp, outputUri)
            Log.d("StickerProcessor", "Sticker assembled: ${outputFile.length()} bytes")

            if (outputFile.length() > maxFileSize) {
                Log.w("StickerProcessor", "File too large for WhatsApp: ${outputFile.length()} bytes")
            }

            return outputFile

        } catch (e: Exception) {
            Log.e("StickerProcessor", "Failed to create animated sticker", e)
            return null
        } finally {
            frames.forEach { it.recycle() }
            encoder?.release()
        }
    }

    private fun resizeAndPadFrame(source: Bitmap, targetSize: Int): Bitmap {
        val padding = 16
        val maxArtworkSize = (targetSize - (padding * 2)).toFloat()

        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val scale = Math.min(maxArtworkSize / source.width, maxArtworkSize / source.height)
        val left = (targetSize - (source.width * scale)) / 2f
        val top = (targetSize - (source.height * scale)) / 2f

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(left, top)
        }

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        canvas.drawBitmap(source, matrix, paint)
        return output
    }
}
