package helium314.keyboard.keyboard.emoji

import android.content.Context
import android.graphics.Paint
import android.os.Build
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

object SupportedEmojis {
    private val unsupportedEmojis = hashSetOf<String>()
    private var dynamicMaxSdk: Int? = null

    fun getDynamicMaxSdk(context: Context): Int {
        dynamicMaxSdk?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return Build.VERSION.SDK_INT
        val paint = Paint()
        (KeyboardTypeface.emojiTypeface() ?: KeyboardTypeface.customTypeface())
            ?.let { paint.setTypeface(it) }
        val maxApi = try {
            context.assets.open("emoji/minApi.txt").bufferedReader().use { reader ->
                reader.readLines().maxOf {
                    val s = it.split(" ")
                    val supported = s.size > 1 && paint.hasGlyph(s[1])
                    if (supported) s.first().toInt() else 0
                }
            }
        } catch (e: Exception) {
            0
        }
        val result = maxApi.coerceAtLeast(Build.VERSION.SDK_INT)
        dynamicMaxSdk = result
        return result
    }

    private fun canRenderEmoji(paint: Paint, emoji: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        try {
            if (!paint.hasGlyph(emoji)) return false
            val codePointCount = emoji.codePointCount(0, emoji.length)
            if (codePointCount <= 1) return true

            val referenceWidth = paint.measureText("😀")
                .takeIf { it > 0f } ?: 64f
            val emojiWidth = paint.measureText(emoji)
            return emojiWidth <= referenceWidth * 1.85f
        } catch (e: Exception) {
            return false
        }
    }

    fun load(context: Context) {
        dynamicMaxSdk = null
        determineMaxSdk(context)
        val maxSdk = context.prefs().getInt(Settings.PREF_EMOJI_MAX_SDK, 0)
        unsupportedEmojis.clear()
        val paint = Paint()
        paint.textSize = 64f
        (KeyboardTypeface.emojiTypeface() ?: KeyboardTypeface.customTypeface())
            ?.let { paint.setTypeface(it) }
        val threshold = Build.VERSION.SDK_INT.coerceAtMost(30)
        context.assets.open("emoji/minApi.txt").bufferedReader().use { reader ->
            reader.readLines().forEach {
                val s = it.split(" ")
                val minApi = s.first().toInt()
                if (minApi > maxSdk) {
                    unsupportedEmojis.addAll(s.drop(1))
                } else if (minApi > threshold) {
                    s.drop(1).forEach { emoji ->
                        if (!canRenderEmoji(paint, emoji)) {
                            unsupportedEmojis.add(emoji)
                        }
                    }
                }
            }
        }
    }

    private fun determineMaxSdk(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (context.prefs().contains(Settings.PREF_EMOJI_MAX_SDK)) return
        val newMax = getDynamicMaxSdk(context)
        context.prefs().edit { putInt(Settings.PREF_EMOJI_MAX_SDK, newMax) }
    }

    fun isUnsupported(emoji: String) = emoji in unsupportedEmojis
}
