package helium314.keyboard.latin

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.updateSoftInputWindowLayoutParameters
import helium314.keyboard.settings.SettingsActivity

object FrostedGlassHelper {

    @JvmStatic
    fun isFrostedTheme(context: Context): Boolean {
        val prefs = context.prefs()
        val isNight = SettingsActivity.forceNight
            ?: (ResourceUtils.isNight(context.resources) && prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT))
        val themeName = SettingsActivity.forceTheme ?: if (isNight)
            prefs.getString(Settings.PREF_THEME_COLORS_NIGHT, Defaults.PREF_THEME_COLORS_NIGHT)
        else
            prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS)
        return themeName?.contains("frosted", ignoreCase = true) == true
    }

    @JvmStatic
    fun configureFrostedGlass(service: InputMethodService, inputView: View?, enable: Boolean) {
        val window = service.window?.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (enable) {
                service.updateSoftInputWindowLayoutParameters(inputView, true)
                val params = window.attributes
                params.width = WindowManager.LayoutParams.MATCH_PARENT
                params.height = WindowManager.LayoutParams.WRAP_CONTENT
                params.gravity = Gravity.BOTTOM
                window.attributes = params

                val blurRadius = service.prefs().getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS)
                window.setBackgroundBlurRadius(blurRadius)
                window.attributes.flags = window.attributes.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                window.attributes = window.attributes
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            } else {
                service.updateSoftInputWindowLayoutParameters(inputView, false)
                window.setBackgroundBlurRadius(0)
                window.setBackgroundDrawable(null)
            }
        } else {
            service.updateSoftInputWindowLayoutParameters(inputView, false)
        }
    }
}
