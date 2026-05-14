// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.FrameLayout
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getStringResourceOrName

class AccessPointMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var keyboardActionListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER
    private val grid: GridLayout

    init {
        fitsSystemWindows = true
        LayoutInflater.from(context).inflate(R.layout.access_point_menu, this, true)
        grid = findViewById(R.id.access_point_grid)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settings = Settings.getValues()
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val persistentEmojiEnabled = context.prefs().getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW)
        val emojiRowHeight = if (persistentEmojiEnabled) (41 * resources.displayMetrics.density).toInt() else 0
        val finalHeight = abcHeight + emojiRowHeight + paddingTop + paddingBottom
        val constrainedHeightSpec = MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, constrainedHeightSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    fun populateMenu() {
        grid.removeAllViews()
        val prefs = context.prefs()
        val enabledKeys = getEnabledToolbarKeys(prefs)

        val inflater = LayoutInflater.from(context)
        for (key in enabledKeys) {
            val tile = inflater.inflate(R.layout.menu_tile_item, grid, false)
            val iconView = tile.findViewById<ImageButton>(R.id.menu_tile_icon)
            val labelView = tile.findViewById<TextView>(R.id.menu_tile_label)

            iconView.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
            labelView.text = key.name.lowercase().getStringResourceOrName("", context)

            // Whole tile is the touch target, not just the icon
            tile.setOnClickListener {
                val code = getCodeForToolbarKey(key)
                if (code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.UNSPECIFIED) {
                    keyboardActionListener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
                }
                if (code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.CLIPBOARD &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.EMOJI &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.NUMPAD) {
                    KeyboardSwitcher.getInstance().setAlphabetKeyboard()
                }
            }
            // Icon is non-interactive; touch is handled by parent tile
            iconView.isClickable = false
            iconView.isFocusable = false
            grid.addView(tile)
        }
    }
}
