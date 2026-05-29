// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin.common

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.graphics.BlendModeColorFilterCompat
import androidx.core.graphics.BlendModeCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.toBitmap
import helium314.keyboard.keyboard.KeyboardTheme.Companion.STYLE_CIRCLE
import helium314.keyboard.keyboard.KeyboardTheme.Companion.STYLE_HOLO
import helium314.keyboard.keyboard.KeyboardTheme.Companion.STYLE_MATERIAL
import helium314.keyboard.keyboard.KeyboardTheme.Companion.STYLE_ROUNDED
import helium314.keyboard.latin.common.ColorType.*
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.adjustLuminosityAndKeepAlpha
import helium314.keyboard.latin.utils.brighten
import helium314.keyboard.latin.utils.brightenOrDarken
import helium314.keyboard.latin.utils.darken
import helium314.keyboard.latin.utils.isBrightColor
import helium314.keyboard.latin.utils.isDarkColor
import java.util.EnumMap
import androidx.core.graphics.drawable.toDrawable

interface Colors {
    // these theme parameters should no be in here, but are still used
    /** used in KeyboardView for label placement */
    val themeStyle: String
    /** used in parser to decide background of ZWNJ key */
    val hasKeyBorders: Boolean
    val isFrosted: Boolean get() = false

    /** use to check whether colors have changed, for colors (in)directly derived from context,
     *  e.g. night mode or potentially changing system colors */
    fun haveColorsChanged(context: Context): Boolean = false

    /** get the colorInt */
    @ColorInt fun get(color: ColorType): Int

    /** get the colorInt for pressed state */
    @ColorInt fun getPressed(color: ColorType): Int

    /** apply a color to the [drawable], may be through color filter or tint (with or without state list) */
    fun setColor(drawable: Drawable, color: ColorType)

    /** set a foreground color to the [view] */
    fun setColor(view: ImageView, color: ColorType)

    /** set a background to the [view], may replace or adjust existing background */
    fun setBackground(view: View, color: ColorType)

    /** returns a colored drawable selected from [attr], which must contain using R.styleable.KeyboardView_* */
    fun selectAndColorDrawable(attr: TypedArray, color: ColorType): Drawable {
        val drawable = when (color) {
            KEY_BACKGROUND, MORE_SUGGESTIONS_WORD_BACKGROUND, ACTION_KEY_POPUP_KEYS_BACKGROUND, POPUP_KEYS_BACKGROUND ->
                attr.getDrawable(R.styleable.KeyboardView_keyBackground)
            FUNCTIONAL_KEY_BACKGROUND -> attr.getDrawable(R.styleable.KeyboardView_functionalKeyBackground)
            SPACE_BAR_BACKGROUND -> {
                if (hasKeyBorders) attr.getDrawable(R.styleable.KeyboardView_spacebarBackground)
                else attr.getDrawable(R.styleable.KeyboardView_spacebarNoBorderBackground)
            }
            ACTION_KEY_BACKGROUND -> {
                if (themeStyle == STYLE_HOLO && hasKeyBorders) // no borders has a very small pressed drawable otherwise
                    attr.getDrawable(R.styleable.KeyboardView_functionalKeyBackground)
                else
                    attr.getDrawable(R.styleable.KeyboardView_keyBackground)
            }
            else -> null // keyBackground
        }?.mutate() ?: attr.getDrawable(R.styleable.KeyboardView_keyBackground)?.mutate()!! // keyBackground always exists

        setColor(drawable, color)
        return drawable
    }
}

@RequiresApi(Build.VERSION_CODES.S)
class DynamicColors(context: Context, override val themeStyle: String, override val hasKeyBorders: Boolean, private var keyboardBackground: Drawable? = null) : Colors {

    private val isNight = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private val accent = getAccent(context)
    private val gesture = getGesture(context)
    private val background = getBackground(context)
    private val keyBackground = getKeyBackground(context)
    private val functionalKey = getFunctionalKey(context)
    private val spaceBar = getKeyBackground(context)
    private val keyText = getKeyText(context)
    private val keyHintText = getKeyHintText(context)
    private val spaceBarText = getSpaceBarText(context)

    private fun getAccent(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_accent1_100)
        else ContextCompat.getColor(context, android.R.color.system_accent1_200)
    private fun getGesture(context: Context) = if (isNight) accent
        else ContextCompat.getColor(context, android.R.color.system_accent1_600)
    private fun getBackground(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_neutral1_900)
        else ContextCompat.getColor(context, android.R.color.system_neutral1_50)
    private fun getKeyBackground(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_neutral1_800)
        else  ContextCompat.getColor(context, android.R.color.system_neutral1_0)
    private fun getFunctionalKey(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_accent2_300)
        else ContextCompat.getColor(context, android.R.color.system_accent2_100)
    private fun getKeyText(context: Context) = if (isNight) ContextCompat.getColor(context, android.R.color.system_neutral1_50)
        else ContextCompat.getColor(context, android.R.color.system_accent3_900)
    private fun getKeyHintText(context: Context) = if (isNight) keyText
        else ContextCompat.getColor(context, android.R.color.system_accent3_700)
    private fun getSpaceBarText(context: Context) = if (isNight) ColorUtils.setAlphaComponent(ContextCompat.getColor(context, android.R.color.system_neutral1_50), 127)
        else ColorUtils.setAlphaComponent(ContextCompat.getColor(context, android.R.color.system_accent3_700), 127)

    override fun haveColorsChanged(context: Context) =
        accent != getAccent(context)
                || gesture != getGesture(context)
                || background != getBackground(context)
                || keyBackground != getKeyBackground(context)
                || functionalKey != getFunctionalKey(context)
                || keyText != getKeyText(context)
                || keyHintText != getKeyHintText(context)
                || spaceBarText != getSpaceBarText(context)

    private val navBar: Int
    /** brightened or darkened variant of [background], to be used if exact background color would be
     *  bad contrast, e.g. popup keys popup or no border space bar */
    private val adjustedBackground: Int
    /** further brightened or darkened variant of [adjustedBackground] */
    private val doubleAdjustedBackground: Int
    /** brightened or darkened variant of [keyText] */
    private val adjustedKeyText: Int

    private val backgroundFilter: ColorFilter
    private val adjustedBackgroundFilter: ColorFilter
    private val keyTextFilter: ColorFilter
    private val accentColorFilter: ColorFilter
    /** color filter for the white action key icons in material theme, switches to gray if necessary for contrast */
    private val actionKeyIconColorFilter: ColorFilter?

    private val backgroundStateList: ColorStateList
    private val keyStateList: ColorStateList
    private val functionalKeyStateList: ColorStateList
    private val actionKeyStateList: ColorStateList
    private val spaceBarStateList: ColorStateList
    private val adjustedBackgroundStateList: ColorStateList
    private val stripBackgroundList: ColorStateList
    private val toolbarKeyStateList = activatedStateList(
        keyText,
        if (isBrightColor(keyText)) darken(darken(keyText))
        else brighten(brighten(keyText))
    )

    /** darkened variant of [accent] because the accent color is always light for dynamic colors */
    private val adjustedAccent: Int = darken(accent)
    /** further darkened variant of [adjustedAccent] */
    private val doubleAdjustedAccent: Int = darken(adjustedAccent)

    /** darkened variant of [functionalKey] used in day mode */
    private val adjustedFunctionalKey: Int = darken(functionalKey)
    /** further darkened variant of [adjustedFunctionalKey] */
    private val doubleAdjustedFunctionalKey: Int = darken(adjustedFunctionalKey)

    /** brightened variant of [keyBackground] used in night mode */
    private val adjustedKeyBackground: Int = brighten(keyBackground)
    /** further brightened variant of [adjustedKeyBackground] */
    private val doubleAdjustedKeyBackground: Int = brighten(adjustedKeyBackground)
    private var backgroundSetupDone = false

    init {
        accentColorFilter = colorFilter(doubleAdjustedAccent)

        if (themeStyle == STYLE_HOLO && keyboardBackground == null) {
            val darkerBackground = adjustLuminosityAndKeepAlpha(background, -0.2f)
            navBar = darkerBackground
            keyboardBackground = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(background, darkerBackground))
            backgroundSetupDone = true
        } else {
            navBar = background
        }

        // todo (idea): make better use of the states?
        //  could also use / create StateListDrawables in colors (though that's a style than a color...)
        //  this would better allow choosing e.g. cornered/rounded drawables for poup keys or moreSuggestions
        backgroundFilter = colorFilter(background)
        adjustedKeyText = brightenOrDarken(keyText, true)

        if (isDarkColor(background)) {
            adjustedBackground = brighten(background)
            doubleAdjustedBackground = brighten(adjustedBackground)
        } else {
            adjustedBackground = darken(background)
            doubleAdjustedBackground = darken(adjustedBackground)
        }
        adjustedBackgroundStateList =
            if (themeStyle == STYLE_HOLO) {
                pressedStateList(accent, adjustedBackground)
            } else if (isNight) {
                if (hasKeyBorders) pressedStateList(doubleAdjustedAccent, keyBackground)
                else pressedStateList(adjustedAccent, adjustedKeyBackground)
            } else {
                pressedStateList(accent, Color.WHITE)
            }

        val stripBackground = if (keyboardBackground == null && !hasKeyBorders) {
            if (isDarkColor(background)) 0x16ffffff else 0x11000000
        } else {
            Color.TRANSPARENT
        }
        val pressedStripElementBackground = if (keyboardBackground == null) adjustedBackground
        else if (isDarkColor(background)) 0x22ffffff else 0x11000000
        stripBackgroundList = pressedStateList(pressedStripElementBackground, stripBackground)

        adjustedBackgroundFilter =
            if (themeStyle == STYLE_HOLO) colorFilter(adjustedBackground)
            else colorFilter(keyBackground)

        if (hasKeyBorders) {
            backgroundStateList =
                if (!isNight) pressedStateList(adjustedFunctionalKey, background)
                else pressedStateList(adjustedKeyBackground, background)

            keyStateList =
                if (!isNight) pressedStateList(adjustedBackground, keyBackground)
                else pressedStateList(adjustedKeyBackground, keyBackground)

            functionalKeyStateList =
                if (!isNight) pressedStateList(doubleAdjustedFunctionalKey, functionalKey)
                else pressedStateList(functionalKey, doubleAdjustedKeyBackground)

            actionKeyStateList =
                if (!isNight) pressedStateList(gesture, accent)
                else pressedStateList(doubleAdjustedAccent, accent)

            spaceBarStateList =
                if (themeStyle == STYLE_HOLO) pressedStateList(spaceBar, spaceBar)
                else keyStateList

        } else {
            // need to set color to background if key borders are disabled, or there will be ugly keys
            backgroundStateList =
                if (!isNight) pressedStateList(adjustedFunctionalKey, background)
                else pressedStateList(adjustedKeyBackground, background)

            keyStateList =
                if (!isNight) pressedStateList(adjustedFunctionalKey, Color.TRANSPARENT)
                else pressedStateList(functionalKey, Color.TRANSPARENT)

            functionalKeyStateList =
                if (themeStyle == STYLE_HOLO) pressedStateList(functionalKey, Color.TRANSPARENT)
                else keyStateList

            actionKeyStateList =
                if (themeStyle == STYLE_HOLO) pressedStateList(accent, Color.TRANSPARENT)
                else if (!isNight) pressedStateList(gesture, accent)
                else pressedStateList(doubleAdjustedAccent, accent)

            spaceBarStateList =
                if (!isNight) pressedStateList(gesture, adjustedFunctionalKey)
                else pressedStateList(adjustedKeyBackground, spaceBar)
        }
        keyTextFilter = colorFilter(keyText)

        actionKeyIconColorFilter = when {
            themeStyle == STYLE_HOLO -> keyTextFilter
            // the white icon may not have enough contrast, and can't be adjusted by the user
            isBrightColor(accent) -> colorFilter(Color.DKGRAY)
            else -> null
        }
    }

    override fun get(color: ColorType): Int = when (color) {
        TOOL_BAR_KEY_ENABLED_BACKGROUND, EMOJI_CATEGORY_SELECTED, ACTION_KEY_BACKGROUND,
        CLIPBOARD_PIN, SHIFT_KEY_ICON, ENTER_KEY_BACKGROUND -> accent
        AUTOFILL_BACKGROUND_CHIP -> if (isNight) adjustLuminosityAndKeepAlpha(background, 0.15f) else adjustedBackground
        GESTURE_PREVIEW, POPUP_KEYS_BACKGROUND, MORE_SUGGESTIONS_BACKGROUND -> adjustedBackground
        KEY_PREVIEW_BACKGROUND -> adjustedBackground
        TOOL_BAR_EXPAND_KEY_BACKGROUND -> if (!isNight) accent else doubleAdjustedBackground
        GESTURE_TRAIL -> gesture
        KEY_TEXT, SUGGESTION_AUTO_CORRECT, REMOVE_SUGGESTION_ICON, EMOJI_KEY_TEXT, KEY_PREVIEW_TEXT, POPUP_KEY_TEXT,
            KEY_ICON, POPUP_KEY_ICON, ONE_HANDED_MODE_BUTTON, EMOJI_CATEGORY, TOOL_BAR_KEY, FUNCTIONAL_KEY_TEXT -> keyText
        KEY_HINT_TEXT -> keyHintText
        SPACE_BAR_TEXT -> spaceBarText
        FUNCTIONAL_KEY_BACKGROUND, SPECIAL_KEY_BACKGROUND -> if (!isNight) functionalKey else doubleAdjustedKeyBackground
        SPACE_BAR_BACKGROUND -> spaceBar
        MORE_SUGGESTIONS_WORD_BACKGROUND, MAIN_BACKGROUND -> background
        KEY_BACKGROUND -> keyBackground
        ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackground else accent
        STRIP_BACKGROUND -> if (!hasKeyBorders && themeStyle == STYLE_MATERIAL) adjustedBackground else background
        CLIPBOARD_SUGGESTION_BACKGROUND -> doubleAdjustedBackground
        NAVIGATION_BAR -> navBar
        MORE_SUGGESTIONS_HINT, SUGGESTED_WORD, SUGGESTION_TYPED_WORD, SUGGESTION_VALID_WORD -> adjustedKeyText
        ACTION_KEY_ICON, TOOL_BAR_EXPAND_KEY -> Color.WHITE
        else -> color.default()
    }

    override fun getPressed(color: ColorType): Int = when (color) {
        MORE_SUGGESTIONS_WORD_BACKGROUND, MAIN_BACKGROUND -> if (!isNight) adjustedFunctionalKey else adjustedKeyBackground
        KEY_BACKGROUND -> if (hasKeyBorders) (if (!isNight) adjustedBackground else adjustedKeyBackground) else (if (!isNight) adjustedFunctionalKey else functionalKey)
        FUNCTIONAL_KEY_BACKGROUND, SPECIAL_KEY_BACKGROUND -> if (hasKeyBorders) (if (!isNight) doubleAdjustedFunctionalKey else functionalKey) else (if (themeStyle == STYLE_HOLO) functionalKey else getPressed(KEY_BACKGROUND))
        ACTION_KEY_BACKGROUND, ENTER_KEY_BACKGROUND -> if (hasKeyBorders) (if (!isNight) gesture else doubleAdjustedAccent) else (if (themeStyle == STYLE_HOLO) accent else (if (!isNight) gesture else doubleAdjustedAccent))
        SPACE_BAR_BACKGROUND -> if (themeStyle == STYLE_HOLO) spaceBar else getPressed(KEY_BACKGROUND)
        POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) accent else (if (isNight) (if (hasKeyBorders) doubleAdjustedAccent else adjustedAccent) else accent)
        STRIP_BACKGROUND -> if (keyboardBackground == null) adjustedBackground else (if (isDarkColor(background)) 0x22ffffff else 0x11000000)
        ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) getPressed(POPUP_KEYS_BACKGROUND) else getPressed(ACTION_KEY_BACKGROUND)
        else -> brightenOrDarken(get(color), true)
    }

    override fun setColor(drawable: Drawable, color: ColorType) {
        val colorStateList = when (color) {
            MORE_SUGGESTIONS_WORD_BACKGROUND -> backgroundStateList
            KEY_BACKGROUND -> keyStateList
            FUNCTIONAL_KEY_BACKGROUND, SPECIAL_KEY_BACKGROUND -> functionalKeyStateList
            ACTION_KEY_BACKGROUND, ENTER_KEY_BACKGROUND -> actionKeyStateList
            SPACE_BAR_BACKGROUND -> spaceBarStateList
            POPUP_KEYS_BACKGROUND -> adjustedBackgroundStateList
            STRIP_BACKGROUND -> stripBackgroundList
            ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackgroundStateList
                else actionKeyStateList
            TOOL_BAR_KEY -> toolbarKeyStateList
            else -> null // use color filter
        }
        if (colorStateList == null) {
            drawable.colorFilter = getColorFilter(color)
            return
        }
        DrawableCompat.setTintMode(drawable, PorterDuff.Mode.MULTIPLY)
        DrawableCompat.setTintList(drawable, colorStateList)
    }

    override fun setColor(view: ImageView, color: ColorType) {
        if (color == TOOL_BAR_KEY) {
            setColor(view.drawable, color)
            return
        }
        view.colorFilter = getColorFilter(color)
    }

    private fun getColorFilter(color: ColorType): ColorFilter? = when (color) {
        EMOJI_CATEGORY_SELECTED, CLIPBOARD_PIN, SHIFT_KEY_ICON -> accentColorFilter
        REMOVE_SUGGESTION_ICON, EMOJI_CATEGORY, KEY_TEXT,
            KEY_ICON, ONE_HANDED_MODE_BUTTON, TOOL_BAR_KEY, TOOL_BAR_EXPAND_KEY -> keyTextFilter
        KEY_PREVIEW_BACKGROUND -> colorFilter(get(KEY_PREVIEW_BACKGROUND))
        ACTION_KEY_ICON -> actionKeyIconColorFilter
        else -> colorFilter(get(color))
    }

    override fun setBackground(view: View, color: ColorType) {
        if (view.background == null)
            view.setBackgroundColor(Color.WHITE) // set white to make the color filters work
        when (color) {
            KEY_PREVIEW_BACKGROUND -> view.background.colorFilter = getColorFilter(KEY_PREVIEW_BACKGROUND)
            FUNCTIONAL_KEY_BACKGROUND, KEY_BACKGROUND, MORE_SUGGESTIONS_WORD_BACKGROUND, SPACE_BAR_BACKGROUND, STRIP_BACKGROUND -> setColor(view.background, color)
            ONE_HANDED_MODE_BUTTON -> setColor(view.background, if (keyboardBackground == null) MAIN_BACKGROUND else STRIP_BACKGROUND)
            MORE_SUGGESTIONS_BACKGROUND -> {
                view.background.colorFilter = getColorFilter(MORE_SUGGESTIONS_BACKGROUND)
                if (themeStyle == STYLE_ROUNDED || themeStyle == STYLE_CIRCLE)
                    applyPillShape(view.background)
            }
            POPUP_KEYS_BACKGROUND -> {
                if (themeStyle != STYLE_HOLO) {
                    setColor(view.background, POPUP_KEYS_BACKGROUND)
                    if (themeStyle == STYLE_ROUNDED || themeStyle == STYLE_CIRCLE)
                        applyPillShape(view.background)
                } else view.background.colorFilter = adjustedBackgroundFilter
            }
            MAIN_BACKGROUND -> {
                if (keyboardBackground != null) {
                    if (!backgroundSetupDone) {
                        keyboardBackground = keyboardBackground!!.toBitmap(view.width, view.height).toDrawable(view.context.resources)
                        backgroundSetupDone = true
                    }
                    view.background = keyboardBackground
                } else {
                    view.background.colorFilter = backgroundFilter
                }
            }
            else -> view.background.colorFilter = backgroundFilter
        }
    }
}

class DefaultColors (
    override val themeStyle: String,
    override val hasKeyBorders: Boolean,
    private val accent: Int,
    private val background: Int,
    private val keyBackground: Int,
    private val functionalKey: Int,
    private val spaceBar: Int,
    private val keyText: Int,
    private val keyHintText: Int,
    private val suggestionText: Int = keyText,
    private val spaceBarText: Int = keyHintText,
    private val gesture: Int = accent,
    private var keyboardBackground: Drawable? = null,
    override val isFrosted: Boolean = false,
    private val keyBorderColor: Int? = null,
    private val specialKeyBackground: Int = functionalKey,
    private val enterKeyBackground: Int = functionalKey,
    private val systemAccent: Int? = null,
    private val systemNeutral: Int? = null,
) : Colors {
    private val navBar: Int
    /** brightened or darkened variant of [background], to be used if exact background color would be
     *  bad contrast, e.g. popup keys popup or no border space bar */
    private val adjustedBackground: Int
    /** further brightened or darkened variant of [adjustedBackground] */
    private val doubleAdjustedBackground: Int
    private val adjustedSuggestionText = brightenOrDarken(suggestionText, true)

    private val backgroundFilter = colorFilter(background)
    private val adjustedBackgroundFilter: ColorFilter
    private val keyTextFilter: ColorFilter
    private val suggestionTextFilter = colorFilter(suggestionText)
    private val accentColorFilter = colorFilter(accent)

    /** color filter for the white action key icons in material theme, switches to gray if necessary for contrast */
    private val actionKeyIconColorFilter: ColorFilter?

    private val backgroundStateList: ColorStateList
    private val keyStateList: ColorStateList
    private val functionalKeyStateList: ColorStateList
    private val actionKeyStateList: ColorStateList
    private val spaceBarStateList: ColorStateList
    private val specialKeyStateList: ColorStateList
    private val enterKeyStateList: ColorStateList
    private val adjustedBackgroundStateList: ColorStateList
    private val stripBackgroundList: ColorStateList
    private val toolbarKeyStateList = activatedStateList(
        suggestionText,
        if (isBrightColor(suggestionText)) darken(darken(suggestionText))
        else brighten(brighten(suggestionText))
    )
    private var backgroundSetupDone = false

    init {
        if (isDarkColor(background)) {
            adjustedBackground = brighten(background)
            doubleAdjustedBackground = brighten(adjustedBackground)
        } else {
            adjustedBackground = darken(background)
            doubleAdjustedBackground = darken(adjustedBackground)
        }
        adjustedBackgroundStateList = if (isFrosted) {
            pressedStateList(
                ColorUtils.setAlphaComponent(doubleAdjustedBackground, 200),
                ColorUtils.setAlphaComponent(adjustedBackground, 200)
            )
        } else {
            pressedStateList(doubleAdjustedBackground, adjustedBackground)
        }

        val stripBackground: Int
        val pressedStripElementBackground: Int
        if (isFrosted) {
            stripBackground = Color.TRANSPARENT
            pressedStripElementBackground = if (isDarkColor(background)) 0x22ffffff
                else 0x11000000
        } else if (keyboardBackground != null || (themeStyle == STYLE_HOLO && hasKeyBorders)) {
            stripBackground = Color.TRANSPARENT
            pressedStripElementBackground = if (isDarkColor(background)) 0x22ffffff // assume background is similar to the background color
                else 0x11000000
        } else if (hasKeyBorders) {
            stripBackground = background
            pressedStripElementBackground = adjustedBackground
        } else {
            stripBackground = adjustedBackground
            pressedStripElementBackground = doubleAdjustedBackground
        }
        stripBackgroundList = pressedStateList(pressedStripElementBackground, stripBackground)

        if (themeStyle == STYLE_HOLO && keyboardBackground == null) {
            val darkerBackground = adjustLuminosityAndKeepAlpha(background, -0.2f)
            navBar = darkerBackground
            keyboardBackground = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(background, darkerBackground))
            backgroundSetupDone = true
        } else {
            navBar = background
        }

        adjustedBackgroundFilter = if (isFrosted) {
            colorFilter(ColorUtils.setAlphaComponent(adjustedBackground, 200))
        } else {
            colorFilter(adjustedBackground)
        }
        if (hasKeyBorders) {
            backgroundStateList = pressedStateList(brightenOrDarken(background, true), background)
            keyStateList = if (themeStyle == STYLE_HOLO) pressedStateList(keyBackground, keyBackground)
                else pressedStateList(brightenOrDarken(keyBackground, true), keyBackground)
            functionalKeyStateList = pressedStateList(brightenOrDarken(functionalKey, true), functionalKey)
            actionKeyStateList = if (themeStyle == STYLE_HOLO) functionalKeyStateList
                else pressedStateList(brightenOrDarken(accent, true), accent)
            spaceBarStateList = if (themeStyle == STYLE_HOLO) pressedStateList(spaceBar, spaceBar)
                else pressedStateList(brightenOrDarken(spaceBar, true), spaceBar)
            specialKeyStateList = pressedStateList(brightenOrDarken(specialKeyBackground, true), specialKeyBackground)
            enterKeyStateList = pressedStateList(brightenOrDarken(enterKeyBackground, true), enterKeyBackground)
        } else {
            // need to set color to background if key borders are disabled, or there will be ugly keys
            backgroundStateList = pressedStateList(brightenOrDarken(background, true), background)
            keyStateList = pressedStateList(keyBackground, Color.TRANSPARENT)
            functionalKeyStateList = keyStateList
            actionKeyStateList = if (themeStyle == STYLE_HOLO) functionalKeyStateList
                else pressedStateList(brightenOrDarken(accent, true), accent)
            spaceBarStateList = pressedStateList(brightenOrDarken(spaceBar, true), spaceBar)
            specialKeyStateList = pressedStateList(brightenOrDarken(specialKeyBackground, true), specialKeyBackground)
            enterKeyStateList = pressedStateList(brightenOrDarken(enterKeyBackground, true), enterKeyBackground)
        }
        keyTextFilter = colorFilter(keyText)
        actionKeyIconColorFilter = when {
            themeStyle == STYLE_HOLO -> keyTextFilter
            // the white icon may not have enough contrast, and can't be adjusted by the user
            isBrightColor(accent) -> colorFilter(Color.DKGRAY)
            else -> null
        }
    }

    override fun get(color: ColorType): Int = when (color) {
        TOOL_BAR_KEY_ENABLED_BACKGROUND, EMOJI_CATEGORY_SELECTED, ACTION_KEY_BACKGROUND,
            CLIPBOARD_PIN, SHIFT_KEY_ICON -> accent
        SPECIAL_KEY_BACKGROUND -> specialKeyBackground
        ENTER_KEY_BACKGROUND -> enterKeyBackground
        AUTOFILL_BACKGROUND_CHIP -> if (isColorDark(background)) adjustLuminosityAndKeepAlpha(background, 0.15f) else (if (themeStyle == STYLE_MATERIAL && !hasKeyBorders) background else adjustedBackground)
        GESTURE_PREVIEW -> adjustedBackground
        POPUP_KEYS_BACKGROUND, MORE_SUGGESTIONS_BACKGROUND -> if (isFrosted) ColorUtils.setAlphaComponent(adjustedBackground, 230) else adjustedBackground
        KEY_PREVIEW_BACKGROUND -> if (isFrosted) ColorUtils.setAlphaComponent(adjustedBackground, 230) else adjustedBackground
        TOOL_BAR_EXPAND_KEY_BACKGROUND, CLIPBOARD_SUGGESTION_BACKGROUND -> doubleAdjustedBackground
        GESTURE_TRAIL -> gesture
        KEY_TEXT, REMOVE_SUGGESTION_ICON, FUNCTIONAL_KEY_TEXT, KEY_ICON, EMOJI_KEY_TEXT,
            POPUP_KEY_TEXT, POPUP_KEY_ICON, KEY_PREVIEW_TEXT -> keyText
        KEY_HINT_TEXT -> keyHintText
        SPACE_BAR_TEXT -> spaceBarText
        FUNCTIONAL_KEY_BACKGROUND -> functionalKey
        SPACE_BAR_BACKGROUND -> spaceBar
        MORE_SUGGESTIONS_WORD_BACKGROUND, MAIN_BACKGROUND -> background
        KEY_BACKGROUND -> keyBackground
        ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackground else accent
        STRIP_BACKGROUND -> if (isFrosted) Color.TRANSPARENT else if (!hasKeyBorders && themeStyle == STYLE_MATERIAL) adjustedBackground else background
        NAVIGATION_BAR -> navBar
        SUGGESTION_AUTO_CORRECT, EMOJI_CATEGORY, TOOL_BAR_KEY, TOOL_BAR_EXPAND_KEY, ONE_HANDED_MODE_BUTTON -> suggestionText
        MORE_SUGGESTIONS_HINT, SUGGESTED_WORD, SUGGESTION_TYPED_WORD, SUGGESTION_VALID_WORD -> adjustedSuggestionText
        ACTION_KEY_ICON -> Color.WHITE
        else -> color.default()
    }

    override fun getPressed(color: ColorType): Int = when (color) {
        MORE_SUGGESTIONS_WORD_BACKGROUND, MAIN_BACKGROUND -> brightenOrDarken(background, true)
        KEY_BACKGROUND -> if (hasKeyBorders) (if (themeStyle == STYLE_HOLO) keyBackground else brightenOrDarken(keyBackground, true)) else keyBackground
        FUNCTIONAL_KEY_BACKGROUND -> if (hasKeyBorders) brightenOrDarken(functionalKey, true) else keyBackground
        SPECIAL_KEY_BACKGROUND -> brightenOrDarken(specialKeyBackground, true)
        ENTER_KEY_BACKGROUND -> brightenOrDarken(enterKeyBackground, true)
        ACTION_KEY_BACKGROUND -> if (themeStyle == STYLE_HOLO) getPressed(FUNCTIONAL_KEY_BACKGROUND) else brightenOrDarken(accent, true)
        SPACE_BAR_BACKGROUND -> if (themeStyle == STYLE_HOLO) spaceBar else brightenOrDarken(spaceBar, true)
        POPUP_KEYS_BACKGROUND -> if (isFrosted) ColorUtils.setAlphaComponent(doubleAdjustedBackground, 200) else doubleAdjustedBackground
        ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) getPressed(POPUP_KEYS_BACKGROUND) else getPressed(ACTION_KEY_BACKGROUND)
        STRIP_BACKGROUND -> {
            if (isFrosted) (if (isDarkColor(background)) 0x22ffffff else 0x11000000)
            else if (keyboardBackground != null || (themeStyle == STYLE_HOLO && hasKeyBorders)) (if (isDarkColor(background)) 0x22ffffff else 0x11000000)
            else if (hasKeyBorders) adjustedBackground
            else doubleAdjustedBackground
        }
        else -> brightenOrDarken(get(color), true)
    }

    override fun setColor(drawable: Drawable, color: ColorType) {
        val colorStateList = when (color) {
            MORE_SUGGESTIONS_WORD_BACKGROUND -> backgroundStateList
            KEY_BACKGROUND -> keyStateList
            FUNCTIONAL_KEY_BACKGROUND -> functionalKeyStateList
            SPECIAL_KEY_BACKGROUND -> specialKeyStateList
            ACTION_KEY_BACKGROUND -> actionKeyStateList
            ENTER_KEY_BACKGROUND -> enterKeyStateList
            SPACE_BAR_BACKGROUND -> spaceBarStateList
            POPUP_KEYS_BACKGROUND -> adjustedBackgroundStateList
            STRIP_BACKGROUND -> stripBackgroundList
            ACTION_KEY_POPUP_KEYS_BACKGROUND -> if (themeStyle == STYLE_HOLO) adjustedBackgroundStateList
                else actionKeyStateList
            TOOL_BAR_KEY -> toolbarKeyStateList
            else -> null // use color filter
        }
        if (colorStateList == null) {
            drawable.colorFilter = getColorFilter(color)
            return
        }
        DrawableCompat.setTintMode(drawable, PorterDuff.Mode.MULTIPLY)
        DrawableCompat.setTintList(drawable, colorStateList)
    }

    override fun setColor(view: ImageView, color: ColorType) {
        if (color == TOOL_BAR_KEY) {
            setColor(view.drawable, color)
            return
        }
        view.colorFilter = getColorFilter(color)
    }

    override fun setBackground(view: View, color: ColorType) {
        if (view.background == null)
            view.setBackgroundColor(Color.WHITE) // set white to make the color filters work
        val bg = view.background
        val shouldFilter = bg != null && bg.javaClass.simpleName != "BackgroundBlurDrawable"
        when (color) {
            KEY_PREVIEW_BACKGROUND -> if (shouldFilter) bg.colorFilter = getColorFilter(KEY_PREVIEW_BACKGROUND)
            POPUP_KEYS_BACKGROUND -> {
                if (shouldFilter) bg.colorFilter = adjustedBackgroundFilter
                if (themeStyle == STYLE_ROUNDED || themeStyle == STYLE_CIRCLE)
                    applyPillShape(bg)
            }
            FUNCTIONAL_KEY_BACKGROUND, KEY_BACKGROUND, MORE_SUGGESTIONS_WORD_BACKGROUND, SPACE_BAR_BACKGROUND, STRIP_BACKGROUND, CLIPBOARD_SUGGESTION_BACKGROUND -> setColor(bg, color)
            ONE_HANDED_MODE_BUTTON -> setColor(bg, if (keyboardBackground == null) MAIN_BACKGROUND else STRIP_BACKGROUND)
            MORE_SUGGESTIONS_BACKGROUND -> {
                if (shouldFilter) bg.colorFilter = getColorFilter(MORE_SUGGESTIONS_BACKGROUND)
                if (themeStyle == STYLE_ROUNDED || themeStyle == STYLE_CIRCLE)
                    applyPillShape(bg)
            }
            MAIN_BACKGROUND -> {
                if (keyboardBackground != null) {
                    if (!backgroundSetupDone) {
                        keyboardBackground = keyboardBackground!!.toBitmap(view.width, view.height).toDrawable(view.context.resources)
                        backgroundSetupDone = true
                    }
                    view.background = keyboardBackground
                } else {
                    if (shouldFilter) bg.colorFilter = backgroundFilter
                }
            }
            else -> if (shouldFilter) bg.colorFilter = backgroundFilter
        }
    }

    override fun haveColorsChanged(context: Context): Boolean {
        if (!isFrosted || Build.VERSION.SDK_INT < Build.VERSION_CODES.S || systemAccent == null || systemNeutral == null) {
            return false
        }
        val isNight = helium314.keyboard.keyboard.KeyboardTheme.isDarkThemeActive(context)
        val currentAccent = if (isNight) ContextCompat.getColor(context, android.R.color.system_accent1_100)
            else ContextCompat.getColor(context, android.R.color.system_accent1_200)
        val currentNeutral = if (isNight) ContextCompat.getColor(context, android.R.color.system_neutral1_900)
            else ContextCompat.getColor(context, android.R.color.system_neutral1_50)
        return currentAccent != systemAccent || currentNeutral != systemNeutral
    }

    private fun getColorFilter(color: ColorType): ColorFilter? = when (color) {
        EMOJI_CATEGORY_SELECTED, CLIPBOARD_PIN, SHIFT_KEY_ICON -> accentColorFilter
        KEY_TEXT, KEY_ICON -> keyTextFilter
        REMOVE_SUGGESTION_ICON, EMOJI_CATEGORY, ONE_HANDED_MODE_BUTTON, TOOL_BAR_KEY, TOOL_BAR_EXPAND_KEY -> suggestionTextFilter
        KEY_PREVIEW_BACKGROUND -> colorFilter(get(KEY_PREVIEW_BACKGROUND))
        ACTION_KEY_ICON -> actionKeyIconColorFilter
        else -> colorFilter(get(color)) // create color filter (not great for performance, so the frequently used filters should be stored)
    }
}

class AllColors(private val colorMap: EnumMap<ColorType, Int>, override val themeStyle: String, override val hasKeyBorders: Boolean, backgroundImage: Drawable?) : Colors {
    private var keyboardBackground: Drawable? = backgroundImage
    private val stateListMap = EnumMap<ColorType, ColorStateList>(ColorType::class.java)
    private var backgroundSetupDone = false
    private val colorFilters = hashMapOf<ColorType, ColorFilter>()
    override fun get(color: ColorType): Int {
        val baseColor = colorMap[color] ?: color.default()
        return when (color) {
            AUTOFILL_BACKGROUND_CHIP -> if (isColorDark(get(MAIN_BACKGROUND))) adjustLuminosityAndKeepAlpha(baseColor, 0.15f) else baseColor
            POPUP_KEYS_BACKGROUND, MORE_SUGGESTIONS_BACKGROUND -> if (isFrosted) ColorUtils.setAlphaComponent(baseColor, 200) else baseColor
            KEY_PREVIEW_BACKGROUND -> if (isFrosted) ColorUtils.setAlphaComponent(baseColor, 200) else baseColor
            else -> baseColor
        }
    }

    override fun getPressed(color: ColorType): Int {
        val baseColor = brightenOrDarken(get(color), true)
        return when (color) {
            POPUP_KEYS_BACKGROUND, MORE_SUGGESTIONS_BACKGROUND -> if (isFrosted) ColorUtils.setAlphaComponent(baseColor, 200) else baseColor
            KEY_PREVIEW_BACKGROUND -> if (isFrosted) ColorUtils.setAlphaComponent(baseColor, 200) else baseColor
            else -> baseColor
        }
    }

    override fun setColor(drawable: Drawable, color: ColorType) {
        val colorStateList = stateListMap.getOrPut(color) { pressedStateList(brightenOrDarken(get(color), true), get(color)) }
        DrawableCompat.setTintMode(drawable, PorterDuff.Mode.MULTIPLY)
        DrawableCompat.setTintList(drawable, colorStateList)
    }

    override fun setColor(view: ImageView, color: ColorType) {
        if (color == TOOL_BAR_KEY) {
            setColor(view.drawable, color)
            return
        }
        view.colorFilter = getColorFilter(color)
    }

    override fun setBackground(view: View, color: ColorType) {
        if (view.background == null)
            view.setBackgroundColor(Color.WHITE) // set white to make the color filters work
        when (color) {
            ONE_HANDED_MODE_BUTTON -> setColor(view.background, MAIN_BACKGROUND) // button has no separate background color
            MORE_SUGGESTIONS_BACKGROUND, POPUP_KEYS_BACKGROUND -> {
                setColor(view.background, color)
                if (themeStyle == STYLE_ROUNDED || themeStyle == STYLE_CIRCLE)
                    applyPillShape(view.background)
            }
            MAIN_BACKGROUND -> {
                if (keyboardBackground != null) {
                    if (!backgroundSetupDone) {
                        keyboardBackground = keyboardBackground!!.toBitmap(view.width, view.height).toDrawable(view.context.resources)
                        backgroundSetupDone = true
                    }
                    view.background = keyboardBackground
                } else {
                    setColor(view.background, color)
                }
            }
            else -> setColor(view.background, color)
        }
    }

    private fun getColorFilter(color: ColorType) = colorFilters.getOrPut(color) { colorFilter(get(color)) }
}

object KeyBackgroundUtils {
    @JvmStatic
    @JvmOverloads
    @ColorInt
    fun fillColorFor(colors: Colors, color: ColorType, pressed: Boolean = false): Int {
        if (pressed) return colors.getPressed(color)
        if (colors.hasKeyBorders) return colors.get(color)
        return when (color) {
            KEY_BACKGROUND, FUNCTIONAL_KEY_BACKGROUND -> Color.TRANSPARENT
            else -> colors.get(color)
        }
    }
}

private fun colorFilter(color: Int, mode: BlendModeCompat = BlendModeCompat.MODULATE): ColorFilter {
    // using !! for the color filter because null is only returned for unsupported blend modes, which are not used
    return BlendModeColorFilterCompat.createBlendModeColorFilterCompat(color, mode)!!
}

private fun pressedStateList(pressed: Int, normal: Int): ColorStateList {
    val states = arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(-android.R.attr.state_pressed))
    return ColorStateList(states, intArrayOf(pressed, normal))
}

private fun activatedStateList(activated: Int, normal: Int): ColorStateList {
    val states = arrayOf(intArrayOf(android.R.attr.state_activated), intArrayOf(-android.R.attr.state_activated))
    return ColorStateList(states, intArrayOf(activated, normal))
}

private fun applyPillShape(drawable: Drawable?) {
    var d = drawable ?: return
    while (d is InsetDrawable) {
        d = d.drawable ?: return
    }
    if (d is LayerDrawable) {
        for (i in 0 until d.numberOfLayers) {
            applyPillShape(d.getDrawable(i))
        }
    } else if (d is GradientDrawable) {
        d.cornerRadius = 60f
    }
}

private fun isColorDark(color: Int): Boolean {
    if (color == Color.TRANSPARENT) return true
    val r = Color.red(color)
    val g = Color.green(color)
    val b = Color.blue(color)
    val brightness = (r * r * 0.241 + g * g * 0.691 + b * b * 0.068)
    return brightness < 130 * 130
}

enum class ColorType {
    ACTION_KEY_ICON,
    ACTION_KEY_BACKGROUND,
    ACTION_KEY_POPUP_KEYS_BACKGROUND,
    AUTOFILL_BACKGROUND_CHIP,
    CLIPBOARD_PIN,
    EMOJI_CATEGORY,
    EMOJI_CATEGORY_SELECTED,
    EMOJI_KEY_TEXT,
    FUNCTIONAL_KEY_TEXT,
    FUNCTIONAL_KEY_BACKGROUND,
    GESTURE_TRAIL,
    GESTURE_PREVIEW,
    KEY_BACKGROUND,
    KEY_ICON,
    KEY_TEXT,
    KEY_HINT_TEXT,
    KEY_PREVIEW_BACKGROUND,
    KEY_PREVIEW_TEXT,
    MORE_SUGGESTIONS_HINT,
    MORE_SUGGESTIONS_BACKGROUND,
    MORE_SUGGESTIONS_WORD_BACKGROUND,
    POPUP_KEYS_BACKGROUND,
    POPUP_KEY_TEXT,
    POPUP_KEY_ICON,
    NAVIGATION_BAR,
    SHIFT_KEY_ICON,
    SPACE_BAR_BACKGROUND,
    SPACE_BAR_TEXT,
    ONE_HANDED_MODE_BUTTON,
    REMOVE_SUGGESTION_ICON,
    STRIP_BACKGROUND,
    CLIPBOARD_SUGGESTION_BACKGROUND,
    SUGGESTED_WORD,
    SUGGESTION_AUTO_CORRECT,
    SUGGESTION_TYPED_WORD,
    SUGGESTION_VALID_WORD,
    TOOL_BAR_EXPAND_KEY,
    TOOL_BAR_EXPAND_KEY_BACKGROUND,
    TOOL_BAR_KEY,
    TOOL_BAR_KEY_ENABLED_BACKGROUND,
    MAIN_BACKGROUND,
    SPECIAL_KEY_BACKGROUND,
    ENTER_KEY_BACKGROUND,
}

// this is not used any more, but we keep in case a colorMap does not get filled for whatever reason
fun ColorType.default() = ColorUtils.setAlphaComponent(name.hashCode() and 0xffffff, 255)
