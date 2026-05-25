// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.TextView
import androidx.compose.ui.text.font.FontFamily
import helium314.keyboard.latin.common.isEmoji
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.TypefaceUtils

object KeyboardTypeface {
    private const val EMOJI_PROBE_TEXT_SIZE = 64f
    private const val MAX_JOINED_EMOJI_WIDTH_RATIO = 1.85f
    private const val VARIATION_SELECTOR_16 = "\uFE0F"
    private val lock = Any()
    private val emojiProbePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var cachedCustomTypeface: Typeface? = null
    private var cachedCustomFontFamily: FontFamily? = null
    @Volatile
    private var customTypefaceLoaded = false

    private var cachedEmojiTypeface: Typeface? = null
    @Volatile
    private var emojiTypefaceLoaded = false

    private fun loadCustomTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomFontFile(context))
        }.getOrNull()
    }

    private fun loadCustomEmojiTypeface(context: Context): Typeface? {
        return runCatching {
            Typeface.createFromFile(Settings.getCustomEmojiFontFile(context))
        }.getOrNull()
    }

    @JvmStatic
    fun customTypeface(): Typeface? {
        if (customTypefaceLoaded) return cachedCustomTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!customTypefaceLoaded) {
                cachedCustomTypeface = loadCustomTypeface(context)
                cachedCustomFontFamily = cachedCustomTypeface?.let(::FontFamily)
                customTypefaceLoaded = true
            }
            return cachedCustomTypeface
        }
    }

    @JvmStatic
    fun emojiTypeface(): Typeface? {
        if (emojiTypefaceLoaded) return cachedEmojiTypeface
        val context = Settings.getCurrentContext() ?: return null
        synchronized(lock) {
            if (!emojiTypefaceLoaded) {
                cachedEmojiTypeface = loadCustomEmojiTypeface(context)
                emojiTypefaceLoaded = true
            }
            return cachedEmojiTypeface
        }
    }

    @JvmStatic
    fun customFontFamily(): FontFamily? {
        if (!customTypefaceLoaded) customTypeface()
        return cachedCustomFontFamily
    }

    @JvmStatic
    fun resolve(
        text: CharSequence?,
        defaultTypeface: Typeface = Typeface.DEFAULT,
    ): Typeface {
        val emojiTypeface = emojiTypeface()
        if (emojiTypeface != null && text != null && isEmoji(text)) {
            return if (canUseCustomEmojiTypeface(text, emojiTypeface)) emojiTypeface else defaultTypeface
        }
        val custom = customTypeface() ?: return defaultTypeface
        return if (defaultTypeface.style != Typeface.NORMAL) {
            Typeface.create(custom, defaultTypeface.style)
        } else {
            custom
        }
    }

    @JvmStatic
    fun applyToTextView(textView: TextView) {
        applyToTextView(textView, textView.text, Typeface.DEFAULT)
    }

    @JvmStatic
    fun applyToTextView(textView: TextView, text: CharSequence?, defaultTypeface: Typeface) {
        textView.typeface = resolve(text, defaultTypeface = defaultTypeface)
    }

    @JvmStatic
    fun labelForDrawing(text: String, resolvedTypeface: Typeface?): String {
        val emojiTypeface = cachedEmojiTypeface ?: return text
        if (resolvedTypeface != emojiTypeface || !text.contains(VARIATION_SELECTOR_16)) return text
        val normalized = normalizeEmojiForCustomFont(text)
        return if (canRenderCustomEmoji(normalized, emojiTypeface)) normalized else text
    }

    private fun canUseCustomEmojiTypeface(text: CharSequence, typeface: Typeface): Boolean {
        val emoji = text.toString()
        if (canRenderCustomEmoji(emoji, typeface)) return true
        if (!emoji.contains(VARIATION_SELECTOR_16)) return false
        return canRenderCustomEmoji(normalizeEmojiForCustomFont(emoji), typeface)
    }

    private fun normalizeEmojiForCustomFont(text: String): String {
        return text.replace(VARIATION_SELECTOR_16, "")
    }

    private fun canRenderCustomEmoji(emoji: String, typeface: Typeface): Boolean {
        synchronized(emojiProbePaint) {
            emojiProbePaint.typeface = typeface
            emojiProbePaint.textSize = EMOJI_PROBE_TEXT_SIZE
            if (!emojiProbePaint.hasGlyph(emoji)) return false

            val codePointCount = emoji.codePointCount(0, emoji.length)
            if (codePointCount <= 1) return true

            val referenceWidth = emojiProbePaint.measureText("😀")
                .takeIf { it > 0f } ?: EMOJI_PROBE_TEXT_SIZE
            val emojiWidth = emojiProbePaint.measureText(emoji)
            return emojiWidth <= referenceWidth * MAX_JOINED_EMOJI_WIDTH_RATIO
        }
    }

    @JvmStatic
    fun clearCache() {
        synchronized(lock) {
            cachedCustomTypeface = null
            cachedCustomFontFamily = null
            customTypefaceLoaded = false
            cachedEmojiTypeface = null
            emojiTypefaceLoaded = false
            TypefaceUtils.clearCache()
        }
    }
}
