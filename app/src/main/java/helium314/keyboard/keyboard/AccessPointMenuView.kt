// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.content.ClipData
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.DragEvent
import android.view.LayoutInflater
import android.view.View
import android.view.View.DragShadowBuilder
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.FrameLayout
import android.content.Intent
import androidx.core.graphics.drawable.DrawableCompat
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.TOOLBAR_DRAG_CLIP_LABEL
import helium314.keyboard.latin.utils.ToolbarDragSource
import helium314.keyboard.latin.utils.ToolbarDragState
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPersistentToolbarKey
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.getToolbarKeyFromDragData
import helium314.keyboard.latin.utils.removePinnedKey

class AccessPointMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var keyboardActionListener: KeyboardActionListener = KeyboardActionListener.EMPTY_LISTENER
    private val grid: GridLayout
    private var accessDropPreviewView: View? = null
    private var accessDropPreviewIndex = -1
    private var ownGridDragIndex = -1
    private val dragTargetScreenLocation = IntArray(2)
    private val gridScreenLocation = IntArray(2)

    init {
        LayoutInflater.from(context).inflate(R.layout.access_point_menu, this, true)
        grid = findViewById(R.id.access_point_grid)
        val dropPinnedKeyListener = View.OnDragListener { target, event -> onPinnedKeyDropFromStrip(target, event) }
        setOnDragListener(dropPinnedKeyListener)
        grid.setOnDragListener(dropPinnedKeyListener)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener) {
        keyboardActionListener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settings = Settings.getValues()
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val finalHeight = abcHeight + paddingTop + paddingBottom
        val constrainedHeightSpec = MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, constrainedHeightSpec)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    fun populateMenu() {
        grid.removeAllViews()
        val prefs = context.prefs()
        val persistentKey = getPersistentToolbarKey(prefs)
        val pinnedRowEnabled = Settings.getValues().mToolbarMode != ToolbarMode.HIDDEN
        val pinnedKeys = if (pinnedRowEnabled) getPinnedToolbarKeys(prefs, persistentKey).toSet() else emptySet()
        val enabledKeys = getEnabledToolbarKeys(prefs).filterNot { it == persistentKey || it in pinnedKeys }

        val inflater = LayoutInflater.from(context)
        for (key in enabledKeys) {
            val tile = inflater.inflate(R.layout.menu_tile_item, grid, false)
            tile.tag = key
            try {
                tile.setBackgroundResource(android.R.color.transparent)
                val iconView = tile.findViewById<ImageButton>(R.id.menu_tile_icon)
                val colors = Settings.getValues().mColors
                if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_ROUNDED || colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                    val drawable = android.graphics.drawable.GradientDrawable()
                    if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                        drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                        val lp = iconView.layoutParams
                        lp.width = lp.height
                        iconView.layoutParams = lp
                    } else {
                        drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        drawable.cornerRadius = 1000f
                        val lp = iconView.layoutParams
                        lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        iconView.layoutParams = lp
                    }
                    drawable.setColor(android.graphics.Color.WHITE)
                    colors.setColor(drawable, ColorType.KEY_BACKGROUND)
                    iconView.background = drawable
                } else {
                    val keyboardViewAttr = context.obtainStyledAttributes(null, R.styleable.KeyboardView, R.attr.keyboardViewStyle, R.style.KeyboardView)
                    iconView.background = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.KEY_BACKGROUND)
                    keyboardViewAttr.recycle()
                }
                val labelView = tile.findViewById<TextView>(R.id.menu_tile_label)

                var drawable: android.graphics.drawable.Drawable? = null
                try {
                    drawable = KeyboardIconsSet.instance.getNewDrawable(key.name, context)
                } catch (e: Exception) {
                    android.util.Log.e("AccessPointMenuView", "Failed to load drawable for ${key.name}", e)
                }
                if (drawable == null) {
                    drawable = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_settings_default)
                }

                val keyboardTextColor = Settings.getValues().mColors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT)
                applyKeyTextIcon(iconView, drawable, keyboardTextColor)

                labelView.text = key.name.lowercase().getStringResourceOrName("", context)
                labelView.setTextColor(keyboardTextColor)
                KeyboardTypeface.applyToTextView(labelView)

                // Icon is non-interactive; touch is handled by parent tile
                iconView.isClickable = false
                iconView.isFocusable = false
            } catch (e: android.content.res.Resources.NotFoundException) {
                android.util.Log.e("AccessPointMenuView", "Resource not found for tile ${key.name}", e)
            }

            // Whole tile is the touch target, not just the icon
            tile.setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.NOT_SPECIFIED, tile, HapticEvent.KEY_PRESS)
                val code = getCodeForToolbarKey(key)
                if (code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.UNSPECIFIED) {
                    keyboardActionListener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
                }
                if (code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.CLIPBOARD &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.AI_TOOLS &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.EMOJI &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.NUMPAD &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.GIFS &&
                    code != helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.STICKERS) {
                    KeyboardSwitcher.getInstance().setAlphabetKeyboard()
                }
            }

            tile.setOnLongClickListener { v ->
                AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(v, HapticEvent.KEY_LONG_PRESS)
                val clipData = ClipData.newPlainText(TOOLBAR_DRAG_CLIP_LABEL, key.name)
                val shadow = DragShadowBuilder(v)
                v.visibility = View.INVISIBLE
                v.startDragAndDrop(clipData, shadow, ToolbarDragState(key, ToolbarDragSource.ACCESS_POINT_MENU, v), 0)
                true
            }

            tile.setOnDragListener { v, event ->
                val dragState = event.localState as? ToolbarDragState
                val draggedView = dragState?.sourceView ?: event.localState as? View
                val ownGridDraggedView = draggedView?.takeIf {
                    dragState?.source == ToolbarDragSource.ACCESS_POINT_MENU && it.parent === grid
                }
                when (event.action) {
                    DragEvent.ACTION_DRAG_STARTED -> getToolbarKeyFromDragData(event.localState, event.clipData) != null
                    DragEvent.ACTION_DRAG_ENTERED -> {
                        if (ownGridDraggedView != null && ownGridDraggedView != v) {
                            previewOwnGridMove(ownGridDraggedView, grid.indexOfChild(v))
                        } else if (dragState?.source == ToolbarDragSource.PINNED_ROW) {
                            previewPinnedKeyInAccessMenu(dragState.key, grid.indexOfChild(v))
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_LOCATION -> {
                        if (dragState?.source == ToolbarDragSource.PINNED_ROW) {
                            previewPinnedKeyInAccessMenu(dragState.key, grid.indexOfChild(v))
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_EXITED -> {
                        v.alpha = 1.0f
                        true
                    }
                    DragEvent.ACTION_DROP -> {
                        if (ownGridDraggedView != null) {
                            grid.post {
                                for (i in 0 until grid.childCount) {
                                    grid.getChildAt(i).alpha = 1.0f
                                }
                                ownGridDraggedView.visibility = View.VISIBLE
                                saveToolbarKeyOrder(grid)
                            }
                        } else if (dragState?.source == ToolbarDragSource.PINNED_ROW) {
                            commitPinnedKeyDropToAccess(dragState.key)
                        } else {
                            grid.post {
                                for (i in 0 until grid.childCount) {
                                    grid.getChildAt(i).alpha = 1.0f
                                }
                            }
                        }
                        true
                    }
                    DragEvent.ACTION_DRAG_ENDED -> {
                        grid.post {
                            for (i in 0 until grid.childCount) {
                                grid.getChildAt(i).alpha = 1.0f
                            }
                            if (draggedView != null) {
                                draggedView.visibility = View.VISIBLE
                            }
                            ownGridDragIndex = -1
                            if (dragState?.source == ToolbarDragSource.PINNED_ROW) {
                                clearAccessDropPreview()
                            }
                        }
                        true
                    }
                    else -> false
                }
            }

            grid.addView(tile)
        }
    }

    private fun onPinnedKeyDropFromStrip(target: View, event: DragEvent): Boolean {
        val dragState = event.localState as? ToolbarDragState ?: return false
        if (dragState.source != ToolbarDragSource.PINNED_ROW) return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DRAG_ENTERED -> {
                alpha = 0.92f
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                previewPinnedKeyInAccessMenu(dragState.key, accessDropIndexFromEvent(target, event))
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                alpha = 1.0f
                true
            }
            DragEvent.ACTION_DROP -> {
                alpha = 1.0f
                commitPinnedKeyDropToAccess(dragState.key)
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                alpha = 1.0f
                clearAccessDropPreview()
                true
            }
            else -> true
        }
    }

    private fun commitPinnedKeyDropToAccess(key: ToolbarKey) {
        removePinnedKey(context.prefs(), key)
        accessDropPreviewView?.let { if (it.parent === grid) saveToolbarKeyOrder(grid) }
        clearAccessDropPreview()
        populateMenu()
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_PRESS)
    }

    private fun previewPinnedKeyInAccessMenu(key: ToolbarKey, index: Int) {
        val preview = accessDropPreviewView ?: createAccessDropPreviewTile(key).also {
            accessDropPreviewView = it
        }
        val targetIndex = index.coerceIn(0, grid.childCount)
        if (preview.parent === grid && accessDropPreviewIndex == targetIndex) return
        accessDropPreviewIndex = targetIndex
        if (preview.parent !== grid) {
            grid.addView(preview, targetIndex)
            return
        }
        moveGridChildToIndex(preview, targetIndex)
    }

    private fun clearAccessDropPreview() {
        accessDropPreviewView?.let {
            if (it.parent === grid) grid.removeView(it)
        }
        accessDropPreviewView = null
        accessDropPreviewIndex = -1
    }

    private fun previewOwnGridMove(child: View, index: Int) {
        val targetIndex = index.coerceIn(0, grid.childCount - 1)
        if (ownGridDragIndex == targetIndex) return
        ownGridDragIndex = targetIndex
        moveGridChildToIndex(child, targetIndex)
    }

    private fun moveGridChildToIndex(child: View, index: Int) {
        val currentIndex = grid.indexOfChild(child)
        val targetIndex = index.coerceIn(0, grid.childCount - 1)
        if (currentIndex < 0 || currentIndex == targetIndex) return
        grid.removeView(child)
        grid.addView(child, targetIndex)
    }

    private fun accessDropIndexFromEvent(target: View, event: DragEvent): Int {
        target.getLocationOnScreen(dragTargetScreenLocation)
        grid.getLocationOnScreen(gridScreenLocation)
        val xInGrid = event.x + dragTargetScreenLocation[0] - gridScreenLocation[0]
        val yInGrid = event.y + dragTargetScreenLocation[1] - gridScreenLocation[1]
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (xInGrid >= child.left.toFloat() && xInGrid <= child.right.toFloat()
                && yInGrid >= child.top.toFloat() && yInGrid <= child.bottom.toFloat()
            ) {
                return i
            }
        }
        return grid.childCount
    }

    private fun createAccessDropPreviewTile(key: ToolbarKey): View {
        val tile = LayoutInflater.from(context).inflate(R.layout.menu_tile_item, grid, false)
        tile.tag = key
        tile.alpha = 0.42f
        tile.isClickable = false
        tile.isFocusable = false
        val iconView = tile.findViewById<ImageButton>(R.id.menu_tile_icon)
        val labelView = tile.findViewById<TextView>(R.id.menu_tile_label)
        val keyboardTextColor = Settings.getValues().mColors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT)
        applyKeyTextIcon(iconView, KeyboardIconsSet.instance.getNewDrawable(key.name, context), keyboardTextColor)
        labelView.text = key.name.lowercase().getStringResourceOrName("", context)
        labelView.setTextColor(keyboardTextColor)
        KeyboardTypeface.applyToTextView(labelView)
        return tile
    }

    private fun saveToolbarKeyOrder(grid: GridLayout) {
        val prefs = context.prefs()
        val allPrefString = prefs.getString(Settings.PREF_TOOLBAR_KEYS, helium314.keyboard.latin.utils.defaultToolbarPref) ?: return
        val allEntries = allPrefString.split(Constants.Separators.ENTRY).toMutableList()

        val reorderedEnabled = mutableListOf<ToolbarKey>()
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i)
            val key = tile.tag as? ToolbarKey
            if (key != null) {
                reorderedEnabled.add(key)
            }
        }

        val newEntries = mutableListOf<String>()
        for (key in reorderedEnabled) {
            newEntries.add("${key.name}${Constants.Separators.KV}true")
        }
        for (entry in allEntries) {
            val name = entry.substringBefore(Constants.Separators.KV)
            if (reorderedEnabled.none { it.name == name }) {
                newEntries.add(entry)
            }
        }
        prefs.edit().putString(Settings.PREF_TOOLBAR_KEYS, newEntries.joinToString(Constants.Separators.ENTRY)).commit()
        Settings.getInstance().onSharedPreferenceChanged(prefs, Settings.PREF_TOOLBAR_KEYS)
    }

    fun updateThemeColors(colors: helium314.keyboard.latin.common.Colors) {
        val keyboardViewAttr = context.obtainStyledAttributes(null, R.styleable.KeyboardView, R.attr.keyboardViewStyle, R.style.KeyboardView)
        for (i in 0 until grid.childCount) {
            val tile = grid.getChildAt(i)
            val iconView = tile.findViewById<ImageButton>(R.id.menu_tile_icon)
            if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_ROUNDED || colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                val drawable = android.graphics.drawable.GradientDrawable()
                if (colors.themeStyle == helium314.keyboard.keyboard.KeyboardTheme.STYLE_CIRCLE) {
                    drawable.shape = android.graphics.drawable.GradientDrawable.OVAL
                    val lp = iconView.layoutParams
                    lp.width = lp.height
                    iconView.layoutParams = lp
                } else {
                    drawable.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    drawable.cornerRadius = 1000f
                    val lp = iconView.layoutParams
                    lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    iconView.layoutParams = lp
            }
            drawable.setColor(android.graphics.Color.WHITE)
            colors.setColor(drawable, ColorType.KEY_BACKGROUND)
            iconView.background = drawable
        } else {
                val lp = iconView.layoutParams
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                iconView.layoutParams = lp
                iconView.background = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.KEY_BACKGROUND)
            }

            val labelView = tile.findViewById<TextView>(R.id.menu_tile_label)
            val keyboardTextColor = colors.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT)
            labelView.setTextColor(keyboardTextColor)
            KeyboardTypeface.applyToTextView(labelView)

            val drawable = iconView.drawable
            if (drawable != null) {
                applyKeyTextIcon(iconView, drawable, keyboardTextColor)
            }
        }
        keyboardViewAttr.recycle()
    }

    private fun applyKeyTextIcon(iconView: ImageButton, icon: Drawable?, keyboardTextColor: Int) {
        if (icon == null) {
            iconView.setImageDrawable(null)
            return
        }
        val tintedIcon = DrawableCompat.wrap(icon.mutate())
        DrawableCompat.setTint(tintedIcon, keyboardTextColor)
        DrawableCompat.setTintMode(tintedIcon, PorterDuff.Mode.SRC_IN)
        iconView.clearColorFilter()
        iconView.imageTintMode = PorterDuff.Mode.SRC_IN
        iconView.imageTintList = ColorStateList.valueOf(keyboardTextColor)
        iconView.setImageDrawable(tintedIcon)
    }
}
