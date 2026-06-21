// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

class RoundedKeyboardFrameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val clipPath = Path()
    private val outlineClipPath = Path()
    private val clipRect = RectF()
    private var lastWidth = -1
    private var lastHeight = -1
    private var lastRadiusPx = -1f

    private var cachedRadiusPx = -1f
    private val staticDustOverlay = StaticSparkleDustOverlay()
    private val prefListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Settings.PREF_KEYBOARD_CORNER_RADIUS) {
            cachedRadiusPx = -1f
            invalidateOutline()
            postInvalidate()
        }
    }

    init {
        // Also clip via HWUI so hardware-layered descendants respect the rounded keyboard shape.
        // Android 13+ supports path outlines, which lets us keep only the top corners rounded.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radiusPx = if (cachedRadiusPx >= 0f) cachedRadiusPx else keyboardCornerRadiusPx()
                    if (view.width <= 0 || view.height <= 0 || radiusPx <= 0f) {
                        outline.setRect(0, 0, view.width, view.height)
                        return
                    }
                    outlineClipPath.reset()
                    outlineClipPath.addRoundRect(
                        0f,
                        0f,
                        view.width.toFloat(),
                        view.height.toFloat(),
                        floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f),
                        Path.Direction.CW
                    )
                    outline.setPath(outlineClipPath)
                }
            }
            clipToOutline = true
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.prefs().registerOnSharedPreferenceChangeListener(prefListener)
        cachedRadiusPx = -1f
        invalidateOutline()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        context.prefs().unregisterOnSharedPreferenceChangeListener(prefListener)
        staticDustOverlay.clear()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rebuild outline as soon as the IME view gets its real size.
        lastWidth = -1
        lastHeight = -1
        invalidateOutline()
        postInvalidateOnAnimation()
    }

    override fun draw(canvas: Canvas) {
        if (cachedRadiusPx < 0f) {
            cachedRadiusPx = keyboardCornerRadiusPx()
        }
        val radiusPx = cachedRadiusPx
        if (radiusPx <= 0f || width <= 0 || height <= 0) {
            super.draw(canvas)
            return
        }

        updateClipPath(radiusPx)
        val saveCount = canvas.save()
        canvas.clipPath(clipPath)
        super.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (shouldDrawStaticDust()) {
            staticDustOverlay.draw(
                canvas,
                width,
                height,
                staticDustAlpha(),
                staticSparkleColors(),
                staticSparkleUseScreenBlend()
            )
        } else {
            staticDustOverlay.clear()
        }
        super.dispatchDraw(canvas)
    }

    private fun keyboardCornerRadiusPx(): Float {
        val radiusDp = Settings.readKeyboardCornerRadius(context.prefs()).coerceIn(
                Settings.KEYBOARD_CORNER_RADIUS_MIN_DP,
                Settings.KEYBOARD_CORNER_RADIUS_MAX_DP
            )
        return radiusDp * resources.displayMetrics.density
    }

    private fun shouldDrawStaticDust(): Boolean {
        if (Defaults.LIMIT_EXPENSIVE_RENDERING) return false
        val isFrosted = runCatching { Settings.getValues()?.mColors?.isFrosted == true }.getOrDefault(false)
        return isFrosted && staticDustEnabled()
    }

    private fun staticDustEnabled(): Boolean {
        return KeyboardTheme.livePreviewValues?.dustEnabled
            ?: context.prefs().getBoolean(Settings.PREF_FROSTED_DUST_ENABLED, Defaults.PREF_FROSTED_DUST_ENABLED)
    }

    private fun staticDustAlpha(): Float {
        KeyboardTheme.livePreviewValues?.dustAlpha?.let {
            return renderSparkleAlpha(it)
        }

        val prefs = context.prefs()
        val alpha = if (KeyboardTheme.isDarkThemeActive(context)) {
            prefs.getFloat(
                Settings.PREF_FROSTED_DUST_ALPHA_NIGHT,
                prefs.getFloat(Settings.PREF_FROSTED_DUST_ALPHA, Defaults.PREF_FROSTED_DUST_ALPHA_NIGHT)
            )
        } else {
            prefs.getFloat(Settings.PREF_FROSTED_DUST_ALPHA, Defaults.PREF_FROSTED_DUST_ALPHA)
        }
        return renderSparkleAlpha(alpha)
    }

    private fun renderSparkleAlpha(alpha: Float): Float {
        val isDark = KeyboardTheme.isDarkThemeActive(context)
        val sliderAlpha = alpha.coerceIn(MIN_SPARKLE_ALPHA, MAX_SPARKLE_ALPHA)
        if (isDark) {
            return sliderAlpha
        }

        val progress = (sliderAlpha - MIN_SPARKLE_ALPHA) / (MAX_SPARKLE_ALPHA - MIN_SPARKLE_ALPHA)
        return MIN_SPARKLE_ALPHA + progress * (MAX_LIGHT_THEME_SPARKLE_ALPHA - MIN_SPARKLE_ALPHA)
            .coerceIn(MIN_SPARKLE_ALPHA, MAX_LIGHT_THEME_SPARKLE_ALPHA)
    }

    private fun staticSparkleColors(): IntArray {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return intArrayOf(
                Color.rgb(255, 244, 210),
                Color.rgb(165, 232, 255),
                Color.rgb(220, 180, 255),
                Color.rgb(255, 176, 122),
                Color.rgb(160, 195, 255)
            )
        }

        val isDark = KeyboardTheme.isDarkThemeActive(context)
        return intArrayOf(
            ContextCompat.getColor(context, android.R.color.system_accent1_100),
            ContextCompat.getColor(context, android.R.color.system_accent2_100),
            ContextCompat.getColor(context, android.R.color.system_accent3_100),
            ContextCompat.getColor(context, android.R.color.system_accent1_200),
            ContextCompat.getColor(context, android.R.color.system_accent2_200)
        ).map { polishSparkleColor(it, isDark) }.toIntArray()
    }

    private fun staticSparkleUseScreenBlend(): Boolean {
        return true
    }

    private fun polishSparkleColor(color: Int, isDark: Boolean): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * if (isDark) 1.4f else 1.15f).coerceIn(if (isDark) 0.72f else 0.48f, 1f)
        hsl[2] = if (isDark) hsl[2].coerceIn(0.76f, 0.95f) else hsl[2].coerceIn(0.84f, 0.97f)
        val polished = ColorUtils.HSLToColor(hsl)
        return if (isDark) polished else ColorUtils.blendARGB(polished, Color.WHITE, 0.28f)
    }

    private fun updateClipPath(radiusPx: Float) {
        if (lastWidth == width && lastHeight == height && lastRadiusPx == radiusPx) {
            return
        }

        lastWidth = width
        lastHeight = height
        lastRadiusPx = radiusPx
        clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(
            clipRect,
            floatArrayOf(radiusPx, radiusPx, radiusPx, radiusPx, 0f, 0f, 0f, 0f),
            Path.Direction.CW
        )
    }

    private companion object {
        private const val MIN_SPARKLE_ALPHA = 1f
        private const val MAX_SPARKLE_ALPHA = 10f
        private const val MAX_LIGHT_THEME_SPARKLE_ALPHA = 20f
    }
}
