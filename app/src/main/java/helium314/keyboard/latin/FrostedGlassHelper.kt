package helium314.keyboard.latin

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.updateSoftInputWindowLayoutParameters
import helium314.keyboard.settings.SettingsActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Locale

object FrostedGlassHelper {

    private const val TAG = "KBoardBlur"
    private const val SAMSUNG_EXTENSION_FLAG_BLUR = 0x10
    private val failedSamsungSemBlurModes = mutableSetOf<String>()

    // =========================================================================
    // LAZY STATICS: Pre-resolve reflection references ONCE and cache them forever
    // =========================================================================
    private val samsungSemBlurSupported: Boolean by lazy {
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                !isKnownFrostedGlassBlurUnsupportedDevice() &&
                SemBlurInfoReflect.initialized
    }

    private object SemBlurInfoReflect {
        var initialized = false
            private set

        var semBlurInfoClass: Class<*>? = null
        var builderClass: Class<*>? = null
        var builderIntConstructor: Constructor<*>? = null
        var builderNoArgConstructor: Constructor<*>? = null
        
        // Cached Methods
        var setRadiusMethod: Method? = null
        var setBackgroundColorMethod: Method? = null
        var setBackgroundCornerRadiusMethod: Method? = null
        var setBlurModeMethod: Method? = null
        var buildMethod: Method? = null
        var semSetBlurInfoMethod: Method? = null

        // Pre-resolved non-captured modes sorted by preference
        var cachedCandidates = listOf<SemBlurMode>()

        init {
            try {
                val sbi = Class.forName("android.view.SemBlurInfo")
                val bc = Class.forName("android.view.SemBlurInfo\$Builder")
                semBlurInfoClass = sbi
                builderClass = bc

                // Resolve constructors
                builderIntConstructor = runCatching { 
                    bc.getDeclaredConstructor(Int::class.javaPrimitiveType!!).apply { isAccessible = true } 
                }.getOrNull()
                builderNoArgConstructor = runCatching { 
                    bc.getDeclaredConstructor().apply { isAccessible = true } 
                }.getOrNull()

                // Cache builder methods
                setRadiusMethod = findMethod(bc, listOf("setRadius", "hidden_setRadius"), Int::class.javaPrimitiveType!!)
                setBackgroundColorMethod = findMethod(bc, listOf("setBackgroundColor", "hidden_setBackgroundColor"), Int::class.javaPrimitiveType!!)
                setBackgroundCornerRadiusMethod = findMethod(bc, listOf("setBackgroundCornerRadius", "hidden_setBackgroundCornerRadius"), Float::class.javaPrimitiveType!!)
                setBlurModeMethod = findMethod(bc, listOf("setBlurMode", "hidden_setBlurMode"), Int::class.javaPrimitiveType!!)
                buildMethod = bc.getMethod("build").apply { isAccessible = true }

                // Cache View method
                semSetBlurInfoMethod = runCatching { 
                    View::class.java.getMethod("semSetBlurInfo", sbi) 
                }.recoverCatching { 
                    View::class.java.getDeclaredMethod("semSetBlurInfo", sbi) 
                }.getOrNull()?.apply { isAccessible = true }

                // Parse and pre-cache modes
                val modes = mutableListOf<SemBlurMode>()
                val allFields = sbi.fields + sbi.declaredFields
                for (field in allFields) {
                    if (field.name.startsWith("BLUR_MODE_")) {
                        runCatching {
                            field.isAccessible = true
                            modes.add(SemBlurMode(field.name, field.getInt(null)))
                        }
                    }
                }
                val distinctModes = modes.distinctBy { it.name }.sortedBy { it.value }
                
                // Select candidates
                val unavailable = setOf("BLUR_MODE_WINDOW_CAPTURED", "BLUR_MODE_CAPTURED")
                val preferred = listOf("BLUR_MODE_CANVAS", "BLUR_MODE_BACKGROUND", "BLUR_MODE_WINDOW")
                
                val preferredModes = preferred.mapNotNull { name -> distinctModes.firstOrNull { it.name == name } }
                val remainingModes = distinctModes.filter { it.name !in preferred && it.name !in unavailable }
                
                cachedCandidates = (preferredModes + remainingModes).distinctBy { it.name }
                if (cachedCandidates.isEmpty()) {
                    cachedCandidates = listOf(SemBlurMode("BLUR_MODE_WINDOW", 0))
                }
                
                initialized = semSetBlurInfoMethod != null && (builderIntConstructor != null || builderNoArgConstructor != null)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize Samsung SemBlurInfo reflection framework", e)
            }
        }

        private fun findMethod(clazz: Class<*>, names: List<String>, paramType: Class<*>): Method? {
            for (name in names) {
                val method = runCatching { clazz.getMethod(name, paramType) }
                    .recoverCatching { clazz.getDeclaredMethod(name, paramType) }
                    .getOrNull()
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            }
            return null
        }
    }

    private data class SemBlurMode(val name: String, val value: Int)

    @JvmStatic
    fun isFrostedTheme(context: Context): Boolean {
        val prefs = context.prefs()
        var isNight = SettingsActivity.forceNight
            ?: (ResourceUtils.isNight(context.resources) && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT))
        
        // Respect theme override for live preview
        if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "light") isNight = false
        else if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "dark") isNight = true
        val themeName = SettingsActivity.forceTheme ?: if (isNight)
            prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
        else
            prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
        return themeName?.contains("frosted", ignoreCase = true) == true
    }

    @JvmStatic
    fun isBatterySaverMode(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            powerManager?.isPowerSaveMode == true
        } catch (e: Exception) {
            false
        }
    }

    @JvmStatic
    fun configureFrostedGlass(service: InputMethodService, inputView: View?, enable: Boolean) {
        val window = service.window?.window ?: return
        constrainImeWindowToKeyboardBounds(service, window, inputView)
        val overrideMode = service.prefs().getString(Settings.PREF_BLUR_RENDER_OVERRIDE, "auto")
        val blurAvailable = isSystemBlurAvailable(service)
        val shouldUseSolidFallback = enable && (overrideMode == "force_solid" || !blurAvailable)

        if (shouldUseSolidFallback) {
            applyDefaultBlur(service, window, false, solidFallbackColor(service))
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true) || overrideMode == "force_samsung") {
                clearSamsungSemBlur(samsungBlurTarget(inputView))
                applySamsungLegacyBlur(window, false)
            }
            applySolidFallbackBackground(service, window, inputView)
            Log.i(TAG, "Frosted glass blur unavailable or forced solid. Applied opaque frosted fallback.")
            return
        }

        if (!enable) {
            applyDefaultBlur(service, window, false)
            if (Build.MANUFACTURER.equals("samsung", ignoreCase = true) || overrideMode == "force_samsung") {
                applySamsungSemBlur(window, inputView, false)
                applySamsungLegacyBlur(window, false)
            }
            Log.i(TAG, "Frosted glass disabled. Cleared blur state.")
            return
        }

        when (overrideMode) {
            "force_native" -> {
                restoreFrostedThemeBackground(service, inputView)
                applyDefaultBlur(service, window, true)
                Log.i(TAG, "OVERRIDE: Force Native Android Blur applied successfully via window.setBackgroundBlurRadius.")
            }
            "force_samsung" -> {
                applySamsungSemBlur(window, inputView, true)
                Log.i(TAG, "OVERRIDE: Force Samsung Proprietary Blur applied successfully via SemBlurInfo.")
            }
            else -> {
                // "auto" mode - The existing logic
                if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        applySamsungSemBlur(window, inputView, true)
                        Log.i(TAG, "AUTO: Samsung device detected (SDK >= S). Applied SemBlurInfo successfully.")
                    } else {
                        restoreFrostedThemeBackground(service, inputView)
                        applyDefaultBlur(service, window, true)
                        applySamsungLegacyBlur(window, true)
                        Log.i(TAG, "AUTO: Older Samsung device detected. Applied Legacy semAddExtensionFlags successfully.")
                    }
                } else {
                    restoreFrostedThemeBackground(service, inputView)
                    applyDefaultBlur(service, window, true)
                    Log.i(TAG, "AUTO: Non-Samsung device detected. Applied Native Android window blur successfully.")
                }
            }
        }
    }

    private fun constrainImeWindowToKeyboardBounds(service: InputMethodService, window: Window, inputView: View?) {
        service.updateSoftInputWindowLayoutParameters(inputView, true)

        val params = window.attributes
        var changed = false
        if (params.width != WindowManager.LayoutParams.MATCH_PARENT) {
            params.width = WindowManager.LayoutParams.MATCH_PARENT
            changed = true
        }
        if (params.height != WindowManager.LayoutParams.WRAP_CONTENT) {
            params.height = WindowManager.LayoutParams.WRAP_CONTENT
            changed = true
        }
        if (params.gravity != Gravity.BOTTOM) {
            params.gravity = Gravity.BOTTOM
            changed = true
        }
        if (changed) {
            window.attributes = params
        }
    }

    private fun applySamsungSemBlur(window: Window, inputView: View?, enable: Boolean) {
        val context = window.context
        val target = samsungBlurTarget(inputView)

        Log.d(TAG, "Samsung SDK ${Build.VERSION.SDK_INT}: using SemBlurInfo path; enable=$enable")

        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.setBackgroundBlurRadius(0)
        window.setBackgroundDrawable(roundedWindowBackground(context, Color.TRANSPARENT))
        inputView?.setBackgroundColor(Color.TRANSPARENT)

        if (!enable) {
            clearSamsungSemBlur(target)
            restoreFrostedThemeBackground(context, inputView)
            return
        }

        if (target == null) {
            Log.e(TAG, "SemBlurInfo target is null; falling back to opaque frosted background")
            applySolidFallbackBackground(context, window, inputView)
            return
        }

        if (isKnownFrostedGlassBlurUnsupportedDevice()) {
            Log.d(TAG, "Known unsupported Samsung blur device (${Build.MODEL}); using opaque frosted fallback")
            applySolidFallbackBackground(context, window, inputView)
            return
        }

        if (!samsungSemBlurSupported) {
            Log.e(TAG, "Samsung SemBlurInfo is unsupported on this device environment; falling back to opaque frosted background")
            applySolidFallbackBackground(context, window, inputView)
            return
        }

        try {
            val radius = blurRadius(context)
            val tint = samsungBlurTint(context)
            val candidates = SemBlurInfoReflect.cachedCandidates

            for (mode in candidates) {
                if (mode.name in failedSamsungSemBlurModes) continue
                try {
                    val builder = if (SemBlurInfoReflect.builderIntConstructor != null) {
                        SemBlurInfoReflect.builderIntConstructor!!.newInstance(mode.value)
                    } else {
                        val b = SemBlurInfoReflect.builderNoArgConstructor!!.newInstance()
                        SemBlurInfoReflect.setBlurModeMethod?.invoke(b, mode.value)
                        b
                    }

                    SemBlurInfoReflect.setRadiusMethod?.invoke(builder, radius)
                    SemBlurInfoReflect.setBackgroundColorMethod?.invoke(builder, tint)
                    val cornerRadiusPx = Settings.readKeyboardCornerRadius(context.prefs()) * context.resources.displayMetrics.density
                    SemBlurInfoReflect.setBackgroundCornerRadiusMethod?.invoke(builder, cornerRadiusPx)

                    val blurInfo = SemBlurInfoReflect.buildMethod!!.invoke(builder)
                    target.setBackgroundColor(Color.TRANSPARENT)
                    SemBlurInfoReflect.semSetBlurInfoMethod!!.invoke(target, blurInfo)
                    Log.d(TAG, "semSetBlurInfo successfully called without throwing an exception.")
                    Log.d(TAG, "Applied SemBlurInfo mode=${mode.name}(${mode.value}) radius=$radius target=${target.javaClass.simpleName} size=${target.width}x${target.height}")
                    return
                } catch (modeError: Throwable) {
                    failedSamsungSemBlurModes.add(mode.name)
                    Log.e(TAG, "SemBlurInfo mode ${mode.name}(${mode.value}) failed; trying next candidate", modeError)
                }
            }

            Log.e(TAG, "No Samsung SemBlurInfo mode applied; falling back to opaque frosted background")
            applySolidFallbackBackground(context, window, inputView)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply Samsung SemBlurInfo blur; falling back to opaque frosted background", e)
            applySolidFallbackBackground(context, window, inputView)
        }
    }

    private fun applySamsungLegacyBlur(window: Window, enable: Boolean) {
        val params = window.attributes
        try {
            if (enable) {
                val semAddExtensionFlags = params.javaClass.getMethod("semAddExtensionFlags", Int::class.javaPrimitiveType)
                semAddExtensionFlags.invoke(params, SAMSUNG_EXTENSION_FLAG_BLUR)
                Log.d(TAG, "Applied Samsung legacy semAddExtensionFlags blur")
            } else {
                val field = params.javaClass.getField("semExtensionFlags")
                val currentFlags = field.getInt(params)
                field.setInt(params, currentFlags and SAMSUNG_EXTENSION_FLAG_BLUR.inv())
                Log.d(TAG, "Cleared Samsung legacy blur flag")
            }
            window.attributes = params
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to update Samsung legacy blur flag via reflection", e)
        }
    }

    private fun applyDefaultBlur(
        service: InputMethodService,
        window: Window,
        enable: Boolean,
        backgroundColor: Int = Color.TRANSPARENT
    ) {
        val params = window.attributes
        var changed = false

        window.setBackgroundDrawable(roundedWindowBackground(service, backgroundColor))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val targetRadius = if (enable) blurRadius(service) else 0
            window.setBackgroundBlurRadius(targetRadius)
            Log.d(TAG, "window.setBackgroundBlurRadius successfully called without throwing an exception.")

            val blurFlag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            val hasBlurFlag = (params.flags and blurFlag) != 0
            if (enable && !hasBlurFlag) {
                params.flags = params.flags or blurFlag
                changed = true
            } else if (!enable && hasBlurFlag) {
                params.flags = params.flags and blurFlag.inv()
                changed = true
            }
        }

        if (changed) {
            window.attributes = params // Applied in a single layout pass!
        }
    }

    private fun roundedWindowBackground(context: Context, color: Int): GradientDrawable {
        val radiusPx = Settings.readKeyboardCornerRadius(context.prefs()) * context.resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(
                radiusPx, radiusPx,
                radiusPx, radiusPx,
                0f, 0f,
                0f, 0f
            )
        }
    }

    private fun samsungBlurTarget(inputView: View?): View? {
        return inputView?.findViewById<View?>(R.id.main_keyboard_frame) ?: inputView
    }

    private fun clearSamsungSemBlur(target: View?) {
        if (target == null || !samsungSemBlurSupported) return
        try {
            SemBlurInfoReflect.semSetBlurInfoMethod!!.invoke(target, null)
            Log.d(TAG, "Cleared Samsung SemBlurInfo blur")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear Samsung SemBlurInfo blur", e)
        }
    }

    private fun restoreFrostedThemeBackground(context: Context, inputView: View?) {
        val target = samsungBlurTarget(inputView)
        if (target == null) return
        target.setBackgroundColor(Color.WHITE)
        val colors = runCatching { KeyboardTheme.getColorsForCurrentTheme(context) }
            .getOrNull()
            ?: Settings.getValues()?.mColors
        colors?.setBackground(target, ColorType.MAIN_BACKGROUND)
        target.invalidate()
    }

    private fun applySolidFallbackBackground(context: Context, window: Window, inputView: View?) {
        val color = solidFallbackColor(context)
        window.setBackgroundDrawable(roundedWindowBackground(context, color))
        inputView?.setBackgroundColor(Color.TRANSPARENT)
        samsungBlurTarget(inputView)?.let { target ->
            target.setBackgroundColor(color)
            target.invalidate()
        }
    }

    private fun solidFallbackColor(context: Context): Int {
        val baseColor = runCatching { KeyboardTheme.getColorsForCurrentTheme(context).get(ColorType.MAIN_BACKGROUND) }
            .getOrNull()
            ?: Settings.getValues()?.mColors?.get(ColorType.MAIN_BACKGROUND)
            ?: if (isNight(context)) Color.BLACK else Color.WHITE
        val opaqueColor = ColorUtils.setAlphaComponent(baseColor, 255)
        return if (baseColor == Color.TRANSPARENT) {
            if (isNight(context)) Color.BLACK else Color.WHITE
        } else {
            opaqueColor
        }
    }

    private fun isSystemBlurAvailable(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (isBatterySaverMode(context)) return false
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            windowManager?.isCrossWindowBlurEnabled == true
        } catch (e: Throwable) {
            Log.w(TAG, "Could not read cross-window blur availability; assuming available", e)
            true
        }
    }

    private fun blurRadius(context: Context): Int {
        val isNight = isNight(context)
        return helium314.keyboard.keyboard.KeyboardTheme.livePreviewValues?.blurRadius
            ?: if (isNight) {
                context.prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT, Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT)
            } else {
                context.prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS)
            }
    }
    /**
     * Computes the Samsung SemBlurInfo tint overlay color from user preferences.
     * This ONLY affects the color/alpha of the overlay on top of the blurred content.
     * It does NOT affect the blur mechanism itself (mode, radius, semSetBlurInfo).
     */
    private fun samsungBlurTint(context: Context): Int {
        val isNight = isNight(context)
        val prefs = context.prefs()

        // Read user's background transparency setting (0-255 alpha)
        val bgTransparency = KeyboardTheme.livePreviewValues?.bgTransparency
            ?: if (isNight) prefs.getInt(Settings.PREF_FROSTED_BG_TRANSPARENCY_NIGHT, Defaults.PREF_FROSTED_BG_TRANSPARENCY_NIGHT)
            else prefs.getInt(Settings.PREF_FROSTED_BG_TRANSPARENCY, Defaults.PREF_FROSTED_BG_TRANSPARENCY)

        // Read user's color blend setting (0-200, percentage)
        val colorBlendPct = (KeyboardTheme.livePreviewValues?.colorBlend
            ?: if (isNight) prefs.getInt(Settings.PREF_FROSTED_COLOR_BLEND_NIGHT, Defaults.PREF_FROSTED_COLOR_BLEND_NIGHT)
            else prefs.getInt(Settings.PREF_FROSTED_COLOR_BLEND, Defaults.PREF_FROSTED_COLOR_BLEND)) / 100f

        // Base tint: black for dark mode, white for light mode
        val baseColor = if (isNight) Color.BLACK else Color.WHITE

        // Blend with system wallpaper/accent color if the user has color blend > 0
        val blendedColor = if (colorBlendPct > 0f) {
            try {
                val accentRes = if (isNight) android.R.color.system_accent1_700
                    else android.R.color.system_accent1_300
                val accentColor = ContextCompat.getColor(context, accentRes)
                ColorUtils.blendARGB(baseColor, accentColor, colorBlendPct.coerceIn(0f, 1f))
            } catch (e: Exception) {
                Log.w(TAG, "Could not read system accent for Samsung blur tint; using base color", e)
                baseColor
            }
        } else {
            baseColor
        }

        // Apply the user's transparency as the alpha of the tint overlay
        val tintAlpha = bgTransparency.coerceIn(0, 255)
        return ColorUtils.setAlphaComponent(blendedColor, tintAlpha)
    }

    private fun isNight(context: Context): Boolean {
        var isNight = ResourceUtils.isNight(context.resources)
        if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "light") isNight = false
        else if (helium314.keyboard.keyboard.KeyboardTheme.themeOverride == "dark") isNight = true
        return isNight
    }

    fun shouldWarnAboutFrostedGlassBlurUnsupported(themeName: String?): Boolean {
        return themeName == KeyboardTheme.THEME_FROSTED_GLASS && isKnownFrostedGlassBlurUnsupportedDevice()
    }

    fun isKnownFrostedGlassBlurUnsupportedDevice(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return false

        val deviceInfo = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.HARDWARE)
            .joinToString(" ")
            .lowercase(Locale.US)

        return listOf("sm-m315", "m31").any { deviceInfo.contains(it) }
    }
}
