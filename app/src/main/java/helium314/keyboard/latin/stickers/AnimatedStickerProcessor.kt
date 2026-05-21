package helium314.keyboard.latin.stickers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.aureusapps.android.webpandroid.CodecException
import com.aureusapps.android.webpandroid.CodecResult
import com.aureusapps.android.webpandroid.decoder.WebPDecodeListener
import com.aureusapps.android.webpandroid.decoder.WebPDecoder
import com.aureusapps.android.webpandroid.decoder.WebPInfo
import com.aureusapps.android.webpandroid.encoder.WebPAnimEncoder
import com.aureusapps.android.webpandroid.encoder.WebPConfig
import com.aureusapps.android.webpandroid.encoder.WebPPreset
import java.io.File

class AnimatedStickerProcessor(private val context: Context) {

    companion object {
        private const val TAG = "StickerProcessor"
        private const val TARGET_SIZE = 512
        private const val MAX_FILE_SIZE = 500 * 1024 // 500 KB WhatsApp ceiling
        private const val MAX_STICKER_FRAMES = 60
        private const val OVERSIZED_MARKER_PREFIX = "oversized_v1_"
        private val PROCESSING_LOCK = Any()
    }

    fun createWhatsAppAnimatedSticker(sourceFile: File): File? {
        return synchronized(PROCESSING_LOCK) {
            createWhatsAppAnimatedStickerLocked(sourceFile)
        }
    }

    private fun createWhatsAppAnimatedStickerLocked(sourceFile: File): File? {
        val outputDir = File(context.filesDir, "stickers/klipy").apply { if (!exists()) mkdirs() }
        val outputFile = File(outputDir, "animated_${sourceFile.nameWithoutExtension}.webp")
        val oversizedMarkerFile = File(outputDir, "$OVERSIZED_MARKER_PREFIX${sourceFile.nameWithoutExtension}.marker")

        if (oversizedMarkerFile.exists()) {
            Log.d(TAG, "Skipping known oversized sticker: ${sourceFile.name}")
            return null
        }

        if (outputFile.exists()) {
            if (outputFile.length() > MAX_FILE_SIZE) {
                Log.w(TAG, "Deleting oversized cached sticker: ${outputFile.length()} bytes")
                outputFile.delete()
            } else {
                Log.d(TAG, "Using cached sticker: ${outputFile.absolutePath}")
                return outputFile
            }
        }

        Log.d(TAG, "Creating animated sticker from ${sourceFile.absolutePath}")
        var decoder: WebPDecoder? = null
        var encoder: WebPAnimEncoder? = null
        val frames = mutableListOf<Bitmap>()
        try {
            decoder = WebPDecoder(context)
            val timestamps = mutableListOf<Long>()
            var sourceFrameCount = 0

            decoder.addDecodeListener(object : WebPDecodeListener {
                override fun onInfoDecoded(info: WebPInfo) {
                    sourceFrameCount = info.frameCount
                    Log.d(TAG, "Info decoded: ${info.width}x${info.height}, frames=${info.frameCount}")
                }

                override fun onFrameDecoded(index: Int, timestamp: Long, bitmap: Bitmap, uri: Uri) {
                    if (frames.size >= MAX_STICKER_FRAMES) {
                        decoder.cancel()
                        return
                    }
                    frames.add(resizeAndPadFrame(bitmap, TARGET_SIZE))
                    timestamps.add(timestamp)

                    if (frames.size >= MAX_STICKER_FRAMES) {
                        decoder.cancel()
                    }
                }
            })

            decoder.setDataSource(Uri.fromFile(sourceFile))
            try {
                decoder.decodeFrames()
            } catch (e: CodecException) {
                if (e.codecResult != CodecResult.ERROR_USER_ABORT || frames.isEmpty()) {
                    throw e
                }
            }

            if (sourceFrameCount > MAX_STICKER_FRAMES) {
                Log.d(TAG, "Frame decode capped at $MAX_STICKER_FRAMES of $sourceFrameCount frames")
            }
            Log.d(TAG, "Finished decoding. Total frames: ${frames.size}")

            if (frames.isEmpty()) {
                Log.e(TAG, "No frames decoded from ${sourceFile.name}")
                return null
            }

            val attempts = buildEncodeAttempts(frames.size)
            for (attempt in attempts) {
                outputFile.delete()
                encoder?.release()
                encoder = null

                val frameIndices = sampleFrameIndices(frames.size, attempt.frameCount)
                encoder = WebPAnimEncoder(context, TARGET_SIZE, TARGET_SIZE).apply {
                    configure(
                        config = WebPConfig(
                            lossless = WebPConfig.COMPRESSION_LOSSY,
                            quality = attempt.quality
                        ),
                        preset = WebPPreset.WEBP_PRESET_PICTURE
                    )
                }
                frameIndices.forEach { index ->
                    encoder.addFrame(timestamps[index], frames[index])
                }

                val lastTimestamp = calculateLastTimestamp(timestamps, frameIndices)
                encoder.assemble(lastTimestamp, Uri.fromFile(outputFile))
                Log.d(TAG, "Sticker assembled: ${outputFile.length()} bytes using ${frameIndices.size} frames at q=${attempt.quality.toInt()}")

                if (outputFile.length() <= MAX_FILE_SIZE) {
                    return outputFile
                }

                Log.w(TAG, "Sticker attempt too large for WhatsApp: ${outputFile.length()} bytes")
            }

            outputFile.delete()
            oversizedMarkerFile.writeText("too_large")
            Log.w(TAG, "Sticker cannot fit under WhatsApp limit after retries: ${sourceFile.name}")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create animated sticker", e)
            return null
        } finally {
            frames.forEach { it.recycle() }
            decoder?.release()
            encoder?.release()
        }
    }

    private data class EncodeAttempt(val frameCount: Int, val quality: Float)

    private fun buildEncodeAttempts(availableFrames: Int): List<EncodeAttempt> {
        val candidates = listOf(
            EncodeAttempt(availableFrames, 70f),
            EncodeAttempt(45, 62f),
            EncodeAttempt(30, 56f),
            EncodeAttempt(20, 50f),
            EncodeAttempt(12, 45f)
        )
        return candidates
            .map { it.copy(frameCount = it.frameCount.coerceAtMost(availableFrames)) }
            .distinctBy { it.frameCount to it.quality }
            .filter { it.frameCount > 0 }
    }

    private fun sampleFrameIndices(totalFrames: Int, targetFrames: Int): List<Int> {
        if (targetFrames >= totalFrames) return (0 until totalFrames).toList()
        if (targetFrames <= 1) return listOf(0)

        return (0 until targetFrames).map { index ->
            Math.round(index * (totalFrames - 1).toFloat() / (targetFrames - 1)).coerceIn(0, totalFrames - 1)
        }.distinct()
    }

    private fun calculateLastTimestamp(timestamps: List<Long>, frameIndices: List<Int>): Long {
        val lastIndex = frameIndices.last()
        val lastTimestamp = timestamps[lastIndex]
        val previousTimestamp = frameIndices.dropLast(1).lastOrNull()?.let { timestamps[it] }
        val frameDuration = previousTimestamp?.let { (lastTimestamp - it).coerceAtLeast(40L) } ?: 100L
        return lastTimestamp + frameDuration
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
