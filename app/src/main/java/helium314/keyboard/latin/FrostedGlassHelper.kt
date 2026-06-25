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
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

object FrostedGlassHelper {

    private const val TAG = "KBoardBlur"
    private const val SAMSUNG_EXTENSION_FLAG_BLUR = 0x10
    private const val ANDROID_15_API = 35
    private const val SAMSUNG_BLUR_PRESET_DARK = 130
    private const val SAMSUNG_BLUR_PRESET_LIGHT = 115
    private const val NATIVE_BLUR_HIDE_CLEANUP_DELAY_MS = 250L
    private val failedSamsungSemBlurModes = mutableSetOf<String>()
    private var loggedSamsungLegacyApplyUnavailable = false
    private var loggedSamsungLegacyClearUnavailable = false
    private val windowsWithAppliedFrostedGlass: MutableSet<Window> =
        Collections.newSetFromMap(WeakHashMap<Window, Boolean>())
    private val windowsWithResizeOverlayBlurSuppressed: MutableSet<Window> =
        Collections.newSetFromMap(WeakHashMap<Window, Boolean>())
    private val defaultBlurStates: MutableMap<Window, DefaultBlurState> =
        Collections.synchronizedMap(WeakHashMap<Window, DefaultBlurState>())
    private val nativeBlurStates: MutableMap<Window, NativeBlurState> =
        Collections.synchronizedMap(WeakHashMap<Window, NativeBlurState>())

    private data class DefaultBlurState(
        val enabled: Boolean,
        val radius: Int,
        val backgroundColor: Int,
        val backgroundBlurOnly: Boolean,
        val cornerRadiusPx: Float
    )

    private class NativeBlurState {
        var generation = 0
        var ready = false
        var decorView: View? = null
        var inputView: View? = null
        var cornerRadiusPx = -1f
        var blurRadius = -1
        var pendingCleanup: Runnable? = null
        var pendingCleanupDecorView: View? = null
    }

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
        var setColorCurvePresetMethod: Method? = null
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
                setRadiusMethod = findMethod(bc, listOf("hidden_setRadius", "setRadius"), Int::class.javaPrimitiveType!!)
                setBackgroundColorMethod = findMethod(bc, listOf("hidden_setBackgroundColor", "setBackgroundColor"), Int::class.javaPrimitiveType!!)
                setBackgroundCornerRadiusMethod = findMethod(bc, listOf("hidden_setBackgroundCornerRadius", "setBackgroundCornerRadius"), Float::class.javaPrimitiveType!!)
                setBlurModeMethod = findMethod(bc, listOf("hidden_setBlurMode", "setBlurMode"), Int::class.javaPrimitiveType!!)
                setColorCurvePresetMethod = findMethod(bc, listOf("setColorCurvePreset"), Int::class.javaPrimitiveType!!)
                buildMethod = findNoArgMethod(bc, listOf("hidden_build", "build"))

                // Cache View method
                semSetBlurInfoMethod = findMethod(
                    View::class.java,
                    listOf("hidden_semSetBlurInfo", "semSetBlurInfo"),
                    sbi
                )

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

                initialized = buildMethod != null &&
                        semSetBlurInfoMethod != null &&
                        (builderIntConstructor != null || builderNoArgConstructor != null)
                Log.d(
                    TAG,
                    "Samsung SemBlurInfo reflection initialized=$initialized " +
                            "viewMethod=${semSetBlurInfoMethod?.name} " +
                            "buildMethod=${buildMethod?.name} " +
                            "presetMethod=${setColorCurvePresetMethod?.name}"
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize Samsung SemBlurInfo reflection framework", e)
            }
        }

        private fun findNoArgMethod(clazz: Class<*>, names: List<String>): Method? {
            for (name in names) {
                val method = runCatching { clazz.getDeclaredMethod(name) }
                    .recoverCatching { clazz.getMethod(name) }
                    .getOrNull()
                if (method != null) {
                    method.isAccessible = true
                    return method
                }
            }
            return null
        }

        private fun findMethod(clazz: Class<*>, names: List<String>, paramType: Class<*>): Method? {
            for (name in names) {
                val method = runCatching { clazz.getDeclaredMethod(name, paramType) }
                    .recoverCatching { clazz.getMethod(name, paramType) }
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
    fun setResizeOverlayBlurSuppressed(
        service: InputMethodService,
        inputView: View?,
        suppressed: Boolean,
        restoreEnabled: Boolean
    ) {
        val window = service.window?.window ?: return
        if (suppressed) {
            windowsWithResizeOverlayBlurSuppressed.add(window)
            configureFrostedGlassInternal(service, inputView, false, allowDelayedNativeCleanup = false)
        } else {
            windowsWithResizeOverlayBlurSuppressed.remove(window)
            configureFrostedGlassInternal(service, inputView, restoreEnabled, allowDelayedNativeCleanup = true)
        }
    }

    @JvmStatic
    fun configureFrostedGlass(service: InputMethodService, inputView: View?, enable: Boolean) {
        configureFrostedGlassInternal(service, inputView, enable, allowDelayedNativeCleanup = true)
    }

    private fun configureFrostedGlassInternal(
        service: InputMethodService,
        inputView: View?,
        enable: Boolean,
        allowDelayedNativeCleanup: Boolean
    ) {
        val window = service.window?.window ?: return
        val nativeState = nativeBlurState(window)
        val generation = ++nativeState.generation
        val overrideMode = service.prefs().getString(Settings.PREF_BLUR_RENDER_OVERRIDE, "auto")
        val isSamsungDevice = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val shouldTrySamsungBlur = isSamsungDevice || overrideMode == "force_samsung"

        if (enable && windowsWithResizeOverlayBlurSuppressed.contains(window)) {
            configureFrostedGlassInternal(service, inputView, false, allowDelayedNativeCleanup = false)
            return
        }

        if (!enable) {
            val hadAppliedFrostedGlass = windowsWithAppliedFrostedGlass.contains(window)
            val hadDefaultBlurEnabled = defaultBlurStates[window]?.enabled == true
            if (!hadAppliedFrostedGlass && !hadDefaultBlurEnabled && !hasNativeBlurFlag(window)) {
                cancelPendingNativeBlurCleanup(nativeState)
                clearNativeBlurReady(nativeState)
                return
            }

            constrainImeWindowToKeyboardBounds(service, window, inputView)
            if (allowDelayedNativeCleanup &&
                    service.isInputViewShown &&
                    scheduleNativeBlurCleanupIfReady(service, window, inputView, nativeState, generation)) {
                Log.i(TAG, "Frosted glass disabled. Scheduled native blur cleanup.")
                return
            }

            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            windowsWithAppliedFrostedGlass.remove(window)
            val nativeBlurChanged = applyDefaultBlur(service, window, false)
            if (shouldTrySamsungBlur) {
                applySamsungSemBlur(window, inputView, false)
                applySamsungLegacyBlur(window, false)
            }
            if (nativeBlurChanged || shouldTrySamsungBlur) {
                Log.i(TAG, "Frosted glass disabled. Cleared blur state.")
            }
            return
        }

        if (!service.isInputViewShown) {
            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            windowsWithAppliedFrostedGlass.remove(window)
            applyDefaultBlur(service, window, false, force = true)
            Log.d(TAG, "Skipped frosted blur enable while IME input view is hidden.")
            return
        }

        constrainImeWindowToKeyboardBounds(service, window, inputView)
        val blurAvailable = isSystemBlurAvailable(service)
        val shouldUseSolidFallback = overrideMode == "force_solid" || (!blurAvailable && !shouldTrySamsungBlur)

        if (shouldUseSolidFallback) {
            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            val nativeBlurChanged = applyDefaultBlur(service, window, false, solidFallbackColor(service))
            if (shouldTrySamsungBlur) {
                clearSamsungSemBlur(samsungBlurTarget(inputView))
                applySamsungLegacyBlur(window, false)
            }
            applySolidFallbackBackground(service, window, inputView)
            windowsWithAppliedFrostedGlass.add(window)
            if (nativeBlurChanged) {
                Log.i(TAG, "Frosted glass blur unavailable or forced solid. Applied opaque frosted fallback.")
            }
            return
        }

        when (overrideMode) {
            "force_native" -> {
                restoreFrostedThemeBackground(service, inputView)
                if (applyNativeBlur(service, window, inputView, nativeState, generation)) {
                    Log.i(TAG, "OVERRIDE: Force Native Android Blur requested via window.setBackgroundBlurRadius.")
                }
            }
            "force_samsung" -> {
                cancelPendingNativeBlurCleanup(nativeState)
                clearNativeBlurReady(nativeState)
                if (applySamsungSemBlur(window, inputView, true, force = true)) {
                    Log.i(TAG, "OVERRIDE: Force Samsung Proprietary Blur applied successfully via SemBlurInfo.")
                } else if (applySamsungLegacyBlur(window, true)) {
                    Log.i(TAG, "OVERRIDE: Force Samsung Proprietary Blur fell back to legacy semAddExtensionFlags.")
                } else {
                    applySolidFallbackBackground(service, window, inputView)
                    Log.i(TAG, "OVERRIDE: Force Samsung Proprietary Blur failed; applied opaque frosted fallback.")
                }
            }
            else -> {
                // "auto" mode - The existing logic
                if (isSamsungDevice) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        cancelPendingNativeBlurCleanup(nativeState)
                        clearNativeBlurReady(nativeState)
                        if (applySamsungSemBlur(window, inputView, true)) {
                            Log.i(TAG, "AUTO: Samsung device detected (SDK >= S). Applied SemBlurInfo successfully.")
                        } else if (applySamsungLegacyBlur(window, true)) {
                            Log.i(TAG, "AUTO: Samsung SemBlurInfo unavailable. Fell back to legacy semAddExtensionFlags.")
                        } else {
                            applySolidFallbackBackground(service, window, inputView)
                            Log.i(TAG, "AUTO: Samsung proprietary blur unavailable. Applied opaque frosted fallback.")
                        }
                    } else {
                        cancelPendingNativeBlurCleanup(nativeState)
                        clearNativeBlurReady(nativeState)
                        restoreFrostedThemeBackground(service, inputView)
                        val nativeBlurChanged = applyDefaultBlur(service, window, true)
                        val legacyBlurChanged = applySamsungLegacyBlur(window, true)
                        if (nativeBlurChanged || legacyBlurChanged) {
                            Log.i(TAG, "AUTO: Older Samsung device detected. Applied Legacy semAddExtensionFlags successfully.")
                        }
                    }
                } else {
                    restoreFrostedThemeBackground(service, inputView)
                    if (applyNativeBlur(service, window, inputView, nativeState, generation)) {
                        Log.i(TAG, "AUTO: Non-Samsung device detected. Requested Native Android window blur.")
                    }
                }
            }
        }
        windowsWithAppliedFrostedGlass.add(window)
    }

    private fun hasNativeBlurFlag(window: Window): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return (window.attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0
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

    private fun applySamsungSemBlur(window: Window, inputView: View?, enable: Boolean, force: Boolean = false): Boolean {
        val context = window.context
        val target = samsungBlurTarget(inputView)

        Log.d(TAG, "Samsung SDK ${Build.VERSION.SDK_INT}: using SemBlurInfo path; enable=$enable")

        clearNativeBackgroundBlur(window)
        window.setBackgroundDrawable(roundedWindowBackground(context, Color.TRANSPARENT))
        defaultBlurStates.remove(window)
        inputView?.setBackgroundColor(Color.TRANSPARENT)

        if (!enable) {
            val cleared = clearSamsungSemBlur(target)
            restoreFrostedThemeBackground(context, inputView)
            return cleared
        }

        if (target == null) {
            Log.e(TAG, "SemBlurInfo target is null; trying next Samsung blur fallback")
            return false
        }

        if (!force && isKnownFrostedGlassBlurUnsupportedDevice()) {
            Log.d(TAG, "Known unsupported Samsung blur device (${Build.MODEL}); trying next Samsung blur fallback")
            return false
        }

        if (!isSamsungSemBlurAvailable(force)) {
            Log.e(TAG, "Samsung SemBlurInfo is unsupported on this device environment; trying next Samsung blur fallback")
            return false
        }

        try {
            val candidates = SemBlurInfoReflect.cachedCandidates

            for (mode in candidates) {
                val standardKey = "standard:${mode.name}"
                if (standardKey !in failedSamsungSemBlurModes &&
                        tryApplySamsungSemBlurMode(context, target, mode, usePreset = false)) {
                    return true
                }

                val presetKey = "preset:${mode.name}"
                if (Build.VERSION.SDK_INT >= ANDROID_15_API &&
                        SemBlurInfoReflect.setColorCurvePresetMethod != null &&
                        presetKey !in failedSamsungSemBlurModes &&
                        tryApplySamsungSemBlurMode(context, target, mode, usePreset = true)) {
                    return true
                }
            }

            Log.e(TAG, "No Samsung SemBlurInfo mode applied; trying next Samsung blur fallback")
            return false
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to apply Samsung SemBlurInfo blur; trying next Samsung blur fallback", e)
            return false
        }
    }

    private fun isSamsungSemBlurAvailable(force: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!force) return samsungSemBlurSupported
        return SemBlurInfoReflect.initialized
    }

    private fun tryApplySamsungSemBlurMode(
        context: Context,
        target: View,
        mode: SemBlurMode,
        usePreset: Boolean
    ): Boolean {
        val attempt = if (usePreset) "preset" else "standard"
        val failedKey = "$attempt:${mode.name}"
        return try {
            val builder = createSamsungSemBlurBuilder(mode)
            val tint = samsungBlurTint(context)
            val cornerRadiusPx = Settings.readKeyboardCornerRadius(context.prefs()) * context.resources.displayMetrics.density

            if (usePreset) {
                SemBlurInfoReflect.setColorCurvePresetMethod!!.invoke(builder, samsungBlurPreset(context))
            } else {
                SemBlurInfoReflect.setRadiusMethod?.invoke(builder, blurRadius(context))
            }
            SemBlurInfoReflect.setBackgroundColorMethod?.invoke(builder, tint)
            SemBlurInfoReflect.setBackgroundCornerRadiusMethod?.invoke(builder, cornerRadiusPx)

            val blurInfo = SemBlurInfoReflect.buildMethod!!.invoke(builder)
            target.setBackgroundColor(Color.TRANSPARENT)
            SemBlurInfoReflect.semSetBlurInfoMethod!!.invoke(target, blurInfo)
            Log.d(
                TAG,
                "Applied Samsung SemBlurInfo $attempt mode=${mode.name}(${mode.value}) " +
                        "viewMethod=${SemBlurInfoReflect.semSetBlurInfoMethod?.name} " +
                        "buildMethod=${SemBlurInfoReflect.buildMethod?.name} " +
                        "target=${target.javaClass.simpleName} size=${target.width}x${target.height}"
            )
            true
        } catch (modeError: Throwable) {
            failedSamsungSemBlurModes.add(failedKey)
            Log.e(TAG, "SemBlurInfo $attempt mode ${mode.name}(${mode.value}) failed; trying next candidate", modeError)
            false
        }
    }

    private fun createSamsungSemBlurBuilder(mode: SemBlurMode): Any {
        val intConstructor = SemBlurInfoReflect.builderIntConstructor
        if (intConstructor != null) {
            return intConstructor.newInstance(mode.value)
        }
        val builder = SemBlurInfoReflect.builderNoArgConstructor!!.newInstance()
        SemBlurInfoReflect.setBlurModeMethod?.invoke(builder, mode.value)
        return builder
    }

    private fun samsungBlurPreset(context: Context): Int {
        return if (isNight(context)) SAMSUNG_BLUR_PRESET_DARK else SAMSUNG_BLUR_PRESET_LIGHT
    }

    private fun applySamsungLegacyBlur(window: Window, enable: Boolean): Boolean {
        val params = window.attributes
        try {
            if (enable) {
                val semAddExtensionFlags = findReflectedMethod(
                    params.javaClass,
                    listOf("semAddExtensionFlags"),
                    Int::class.javaPrimitiveType!!
                )
                if (semAddExtensionFlags != null) {
                    semAddExtensionFlags.invoke(params, SAMSUNG_EXTENSION_FLAG_BLUR)
                    window.attributes = params
                    Log.d(TAG, "Applied Samsung legacy semAddExtensionFlags blur")
                    return true
                }
                if (!loggedSamsungLegacyApplyUnavailable) {
                    loggedSamsungLegacyApplyUnavailable = true
                    Log.d(TAG, "Samsung legacy semAddExtensionFlags unavailable; skipping legacy blur apply.")
                }
            } else {
                val semClearExtensionFlags = findReflectedMethod(
                    params.javaClass,
                    listOf("semClearExtensionFlags"),
                    Int::class.javaPrimitiveType!!
                )
                if (semClearExtensionFlags != null) {
                    semClearExtensionFlags.invoke(params, SAMSUNG_EXTENSION_FLAG_BLUR)
                    window.attributes = params
                    Log.d(TAG, "Cleared Samsung legacy blur flag via semClearExtensionFlags")
                    return true
                }

                val field = findReflectedField(params.javaClass, "semExtensionFlags")
                if (field != null) {
                    val currentFlags = field.getInt(params)
                    field.setInt(params, currentFlags and SAMSUNG_EXTENSION_FLAG_BLUR.inv())
                    window.attributes = params
                    Log.d(TAG, "Cleared Samsung legacy blur flag")
                    return true
                }
                if (!loggedSamsungLegacyClearUnavailable) {
                    loggedSamsungLegacyClearUnavailable = true
                    Log.d(TAG, "Samsung legacy semExtensionFlags clear path unavailable; skipped legacy blur clear.")
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update Samsung legacy blur flag via reflection; trying remaining blur fallback", e)
        }
        return false
    }

    private fun findReflectedMethod(clazz: Class<*>, names: List<String>, vararg paramTypes: Class<*>): Method? {
        for (name in names) {
            val method = runCatching { clazz.getDeclaredMethod(name, *paramTypes) }
                .recoverCatching { clazz.getMethod(name, *paramTypes) }
                .getOrNull()
            if (method != null) {
                method.isAccessible = true
                return method
            }
        }
        return null
    }

    private fun findReflectedField(clazz: Class<*>, name: String): java.lang.reflect.Field? {
        return runCatching { clazz.getDeclaredField(name) }
            .recoverCatching { clazz.getField(name) }
            .getOrNull()
            ?.apply { isAccessible = true }
    }

    private fun applyDefaultBlur(
        service: InputMethodService,
        window: Window,
        enable: Boolean,
        backgroundColor: Int = Color.TRANSPARENT,
        force: Boolean = false
    ): Boolean {
        val targetRadius = if (enable) {
            blurRadius(service)
        } else {
            0
        }
        val backgroundBlurOnly = service.prefs().getBoolean(
            Settings.PREF_NATIVE_BACKGROUND_BLUR_ONLY,
            Defaults.PREF_NATIVE_BACKGROUND_BLUR_ONLY
        )
        val desiredState = DefaultBlurState(
            enabled = enable,
            radius = targetRadius,
            backgroundColor = backgroundColor,
            backgroundBlurOnly = backgroundBlurOnly,
            cornerRadiusPx = keyboardCornerRadiusPx(service)
        )
        if (!force && defaultBlurStates[window] == desiredState) {
            return false
        }

        // AOSP background blur reads its corner radius from a uniform round-rect outline.
        // Per-corner radii produce a path outline, which native background blur treats as radius 0.
        window.setBackgroundDrawable(
            roundedWindowBackground(
                service,
                backgroundColor,
                topOnlyCorners = !enable
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val params = window.attributes
            window.setBackgroundBlurRadius(targetRadius)
            Log.d(TAG, "window.setBackgroundBlurRadius successfully called without throwing an exception.")

            var layoutParamsChanged = false
            val blurFlag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            if ((params.flags and blurFlag) != 0) {
                params.flags = params.flags and blurFlag.inv()
                layoutParamsChanged = true
            }
            if (params.blurBehindRadius != 0) {
                params.setBlurBehindRadius(0)
                layoutParamsChanged = true
            }
            if (layoutParamsChanged) {
                window.attributes = params
            }
        }

        defaultBlurStates[window] = desiredState
        return true
    }

    private fun clearNativeBackgroundBlur(window: Window): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

        var layoutParamsChanged = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.setBackgroundBlurRadius(0)

        val params = window.attributes
        if (params.blurBehindRadius != 0) {
            params.setBlurBehindRadius(0)
            layoutParamsChanged = true
        }
        if (layoutParamsChanged) {
            window.attributes = params
        }
        return true
    }

    private fun applyNativeBlur(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        generation: Int
    ): Boolean {
        val cornerRadiusPx = keyboardCornerRadiusPx(service)
        val targetBlurRadius = blurRadius(service)
        if (reuseReadyNativeBlurIfPossible(service, window, inputView, nativeState, cornerRadiusPx, targetBlurRadius)) {
            Log.d(TAG, "Reasserted active native blur through rounded window background.")
            return false
        }

        cancelPendingNativeBlurCleanup(nativeState)
        clearNativeBlurReady(nativeState)
        applyDefaultBlur(service, window, false, force = true)
        scheduleNativeBlurEnable(service, window, inputView, nativeState, generation, cornerRadiusPx, targetBlurRadius)
        return true
    }

    private fun scheduleNativeBlurEnable(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        generation: Int,
        cornerRadiusPx: Float,
        targetBlurRadius: Int
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val decorView = window.decorView
        // Post activation so AOSP samples the uniform background outline during the next predraw.
        decorView.post {
            if (generation != nativeState.generation ||
                    !isFrostedTheme(service) ||
                    !service.isInputViewShown) {
                return@post
            }
            applyDefaultBlur(service, window, true, force = true)
            markNativeBlurReady(window, inputView, nativeState, cornerRadiusPx, targetBlurRadius)
            Log.d(TAG, "Native Android window blur enabled via window.setBackgroundBlurRadius.")
            samsungBlurTarget(inputView)?.invalidateOutline()
            inputView?.invalidate()
            decorView.invalidate()
        }
    }

    private fun scheduleNativeBlurCleanupIfReady(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        generation: Int
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        if (!canReuseNativeBlur(window, inputView, nativeState, keyboardCornerRadiusPx(service), blurRadius(service))) return false

        cancelPendingNativeBlurCleanup(nativeState)
        val decorView = window.decorView
        val cleanup = Runnable {
            nativeState.pendingCleanup = null
            nativeState.pendingCleanupDecorView = null
            if (generation != nativeState.generation) return@Runnable
            applyDefaultBlur(service, window, false, force = true)
            clearNativeBlurReady(nativeState)
            windowsWithAppliedFrostedGlass.remove(window)
            samsungBlurTarget(inputView)?.invalidateOutline()
            inputView?.invalidate()
            decorView.invalidate()
            Log.d(TAG, "Delayed native blur cleanup completed.")
        }
        nativeState.pendingCleanup = cleanup
        nativeState.pendingCleanupDecorView = decorView
        decorView.postDelayed(cleanup, NATIVE_BLUR_HIDE_CLEANUP_DELAY_MS)
        return true
    }

    private fun reuseReadyNativeBlurIfPossible(
        service: InputMethodService,
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        cornerRadiusPx: Float,
        targetBlurRadius: Int
    ): Boolean {
        if (!canReuseNativeBlur(window, inputView, nativeState, cornerRadiusPx, targetBlurRadius)) return false
        if (nativeState.pendingCleanup != null) {
            cancelPendingNativeBlurCleanup(nativeState)
            clearNativeBlurReady(nativeState)
            applyDefaultBlur(service, window, false, force = true)
            samsungBlurTarget(inputView)?.invalidateOutline()
            inputView?.invalidate()
            window.decorView.invalidate()
            Log.d(TAG, "Pending native blur cleanup found; cleared blur instead of re-enabling during hide.")
            return true
        }
        cancelPendingNativeBlurCleanup(nativeState)
        applyDefaultBlur(service, window, true, force = true)
        markNativeBlurReady(window, inputView, nativeState, cornerRadiusPx, targetBlurRadius)
        samsungBlurTarget(inputView)?.invalidateOutline()
        inputView?.invalidate()
        window.decorView.invalidate()
        return true
    }

    private fun canReuseNativeBlur(
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        cornerRadiusPx: Float,
        targetBlurRadius: Int
    ): Boolean {
        return nativeState.ready &&
                nativeState.decorView === window.decorView &&
                nativeState.inputView === inputView &&
                nativeState.cornerRadiusPx == cornerRadiusPx &&
                nativeState.blurRadius == targetBlurRadius
    }

    private fun markNativeBlurReady(
        window: Window,
        inputView: View?,
        nativeState: NativeBlurState,
        cornerRadiusPx: Float,
        targetBlurRadius: Int
    ) {
        nativeState.ready = true
        nativeState.decorView = window.decorView
        nativeState.inputView = inputView
        nativeState.cornerRadiusPx = cornerRadiusPx
        nativeState.blurRadius = targetBlurRadius
    }

    private fun clearNativeBlurReady(nativeState: NativeBlurState) {
        nativeState.ready = false
        nativeState.decorView = null
        nativeState.inputView = null
        nativeState.cornerRadiusPx = -1f
        nativeState.blurRadius = -1
    }

    private fun cancelPendingNativeBlurCleanup(nativeState: NativeBlurState) {
        val cleanup = nativeState.pendingCleanup
        val decorView = nativeState.pendingCleanupDecorView
        if (cleanup != null && decorView != null) {
            decorView.removeCallbacks(cleanup)
        }
        nativeState.pendingCleanup = null
        nativeState.pendingCleanupDecorView = null
    }

    private fun nativeBlurState(window: Window): NativeBlurState {
        return synchronized(nativeBlurStates) {
            nativeBlurStates.getOrPut(window) { NativeBlurState() }
        }
    }

    private fun roundedWindowBackground(
        context: Context,
        color: Int,
        topOnlyCorners: Boolean = true
    ): GradientDrawable {
        val radiusPx = keyboardCornerRadiusPx(context)
        return GradientDrawable().apply {
            setColor(color)
            if (topOnlyCorners) {
                cornerRadii = floatArrayOf(
                    radiusPx, radiusPx,
                    radiusPx, radiusPx,
                    0f, 0f,
                    0f, 0f
                )
            } else {
                // Uniform radius lets AOSP pass a non-zero corner radius to BackgroundBlurDrawable.
                setCornerRadius(radiusPx)
            }
        }
    }

    private fun keyboardCornerRadiusPx(context: Context): Float {
        return Settings.readKeyboardCornerRadius(context.prefs()) * context.resources.displayMetrics.density
    }

    private fun samsungBlurTarget(inputView: View?): View? {
        return inputView?.findViewById<View?>(R.id.main_keyboard_frame) ?: inputView
    }

    private fun clearSamsungSemBlur(target: View?): Boolean {
        if (target == null || SemBlurInfoReflect.semSetBlurInfoMethod == null) return false
        try {
            SemBlurInfoReflect.semSetBlurInfoMethod!!.invoke(target, null)
            Log.d(TAG, "Cleared Samsung SemBlurInfo blur")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to clear Samsung SemBlurInfo blur", e)
        }
        return false
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
    @android.annotation.SuppressLint("InlinedApi")
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
