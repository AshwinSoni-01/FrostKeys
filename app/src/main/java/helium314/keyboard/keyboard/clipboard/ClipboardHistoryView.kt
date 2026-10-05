// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.FrostedGlassHelper
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.isDarkColor
import helium314.keyboard.latin.utils.prefs

@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), View.OnClickListener,
    ClipboardDao.Listener, OnKeyEventListener,
    ClipboardHistoryRecyclerView.OnClipDismissListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
    private val pinIconId: Int
    private val keyBackgroundId: Int

    private lateinit var clipboardRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var placeholderView: TextView
    private lateinit var backButton: ImageButton
    private lateinit var clearButton: ImageButton
    private lateinit var titleView: TextView
    private lateinit var clipboardAdapter: ClipboardAdapter
    private var undoBar: View? = null
    private var undoText: TextView? = null
    private var undoButton: TextView? = null
    private var lastDismissedEntry: ClipboardHistoryEntry? = null
    private var lastDismissedPosition: Int = -1
    private val hideUndoBarRunnable = Runnable { hideUndoBar() }

    lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager

    init {
        val clipboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.ClipboardHistoryView, defStyle, R.style.ClipboardHistoryView)
        pinIconId = clipboardViewAttr.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
        clipboardViewAttr.recycle()
        @SuppressLint("UseKtx") // suggestion does not work
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settings = Settings.getValues()
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val persistentEmojiEnabled = context.prefs().getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, helium314.keyboard.latin.settings.Defaults.PREF_PERSISTENT_EMOJI_ROW)
        val emojiRowHeight = if (persistentEmojiEnabled) (41 * resources.displayMetrics.density).toInt() else 0
        val toolbarHeight = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        val finalHeight = abcHeight + emojiRowHeight + toolbarHeight + paddingTop + paddingBottom - (6 * resources.displayMetrics.density).toInt() + 1
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initialize() { // needs to be delayed for access to ClipboardStrip, which is not a child of this view
        if (this::clipboardAdapter.isInitialized) return
        val colors = Settings.getValues().mColors
        clipboardAdapter = ClipboardAdapter(clipboardLayoutParams, this).apply {
            itemBackgroundId = keyBackgroundId
            pinnedIconResId = pinIconId
        }
        placeholderView = findViewById(R.id.clipboard_empty_view)
        clipboardRecyclerView = findViewById<ClipboardHistoryRecyclerView>(R.id.clipboard_list).apply {
            val colCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
            layoutManager = StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation") // "no cache" should be fine according to warning in https://developer.android.com/reference/android/view/ViewGroup#setPersistentDrawingCache(int)
            persistentDrawingCache = PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = this@ClipboardHistoryView.placeholderView
            clipDismissListener = this@ClipboardHistoryView
        }
        backButton = findViewById(R.id.clipboard_back_button)
        clearButton = findViewById(R.id.clipboard_clear_button)
        titleView = findViewById(R.id.clipboard_title)
        undoBar = findViewById(R.id.clipboard_undo_bar)
        undoText = findViewById(R.id.clipboard_undo_text)
        undoButton = findViewById(R.id.clipboard_undo_button)
    }

    private fun setupUndoBar(colors: Colors) {
        val bar = undoBar ?: return
        val text = undoText
        val button = undoButton

        val isDark = KeyboardTheme.isDarkThemeActive(context)
        val pillRadius = 24.dpToPx(resources).toFloat()

        // Clip strictly to pill shape outline
        bar.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        bar.clipToOutline = true

        // Consume touches inside the pill bounds so clicks never fall through to clipboard items beneath
        bar.setOnClickListener { /* Consume click */ }

        // Solid pill-shaped toast background without outline stroke
        val toastBgColor = if (isDark) {
            val funcBg = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
            if (isDarkColor(funcBg)) ColorUtils.blendARGB(funcBg, Color.WHITE, 0.12f) else 0xFF2C2D30.toInt()
        } else {
            val funcBg = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
            if (!isDarkColor(funcBg)) ColorUtils.blendARGB(funcBg, Color.BLACK, 0.08f) else 0xFFE9EAEC.toInt()
        }
        val bgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = pillRadius
            setColor(toastBgColor)
        }
        bar.background = bgDrawable

        // Text styling: pure white in dark mode, pure black in light mode
        text?.setTextColor(if (isDark) Color.WHITE else Color.BLACK)

        // Solid accent pill button matching keyboard special keys
        val accentColor = colors.get(ColorType.SPECIAL_KEY_BACKGROUND)
        val buttonPill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            setColor(accentColor)
        }
        val rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(if (isDark) Color.WHITE else Color.BLACK, 0x40)
        )
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 1000f
            setColor(Color.BLACK)
        }
        button?.background = RippleDrawable(rippleColor, buttonPill, mask)

        // Pure white text in dark mode, pure black in light mode
        button?.setTextColor(if (isDark) Color.WHITE else Color.BLACK)

        if (text != null) KeyboardTypeface.applyToTextView(text)
        if (button != null) KeyboardTypeface.applyToTextView(button)
    }

    private fun setupHeader(colors: Colors) {
        val stripColor = colors.get(ColorType.STRIP_BACKGROUND)
        findViewById<LinearLayout>(R.id.clipboard_toolbar).setBackgroundColor(stripColor)
        
        setupHeaderButton(backButton, colors)
        setupHeaderButton(clearButton, colors)
        
        val clearIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.CLEAR_CLIPBOARD.name, context)
        clearButton.setImageDrawable(clearIcon)

        KeyboardTypeface.applyToTextView(
            titleView,
            titleView.text,
            android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        )
        titleView.setTextColor(colors.get(ColorType.KEY_TEXT))
        titleView.setOnClickListener { /* Consume clicks */ }
        
        backButton.setOnClickListener(this)
        clearButton.setOnClickListener(this)
    }

    private fun setupHeaderButton(button: ImageButton, colors: Colors) {
        button.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))
        button.background = createHeaderButtonBackground(colors)
        button.scaleType = ImageView.ScaleType.CENTER
        button.setPadding(0, 0, 0, 0)
    }

    private fun createHeaderButtonBackground(colors: Colors): RippleDrawable {
        val circle = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
            colors.setColor(this, ColorType.SPECIAL_KEY_BACKGROUND)
        }
        val rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(colors.get(ColorType.FUNCTIONAL_KEY_TEXT), 0x33)
        )
        val inset = 3.dpToPx(resources)
        val content = InsetDrawable(circle, inset, inset, inset, inset)
        
        val maskCircle = GradientDrawable().apply {
            this.shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.BLACK)
        }
        val mask = InsetDrawable(maskCircle, inset, inset, inset, inset)

        return RippleDrawable(
            rippleColor,
            content,
            mask
        )
    }

    private fun setupClipKey(params: KeyDrawParams) {
        clipboardAdapter.apply {
            itemBackgroundId = keyBackgroundId
            itemTypeFace = params.mTypeface
            itemTextColor = params.mTextColor
            itemTextSize = params.mLabelSize.toFloat()
        }
    }



    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardId.ELEMENT_CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        clipboardHistoryManager = historyManager
        initialize()
        val settings = Settings.getInstance()
        setupHeader(settings.current.mColors)
        setupUndoBar(settings.current.mColors)
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        clipboardAdapter.clipboardHistoryManager = historyManager

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        setupClipKey(params)
        setupBottomRowKeyboard(editorInfo, keyboardActionListener)

        placeholderView.apply {
            KeyboardTypeface.applyToTextView(this)
            setTextColor(params.mTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, params.mLabelSize.toFloat() * 2)
        }
        clipboardRecyclerView.apply {
            adapter = clipboardAdapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth

            // set side padding
            val keyboardAttr = context.obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
            val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            val rightPadding =  (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            keyboardAttr.recycle()
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }


    }

    fun stopClipboardHistory() {
        hideUndoBar()
        removeCallbacks(hideUndoBarRunnable)
        undoBar?.removeCallbacks(hideUndoBarRunnable)
        if (!this::clipboardAdapter.isInitialized) return
        clipboardRecyclerView.adapter = null
        clipboardHistoryManager.setHistoryChangeListener(null)
        clipboardAdapter.clipboardHistoryManager = null
    }

    override fun onClipDismissed(entry: ClipboardHistoryEntry, originalPosition: Int) {
        showUndoBar(entry, originalPosition)
    }

    private fun showUndoBar(entry: ClipboardHistoryEntry, position: Int) {
        lastDismissedEntry = entry
        lastDismissedPosition = position

        val bar = undoBar ?: return
        val text = undoText
        val button = undoButton

        removeCallbacks(hideUndoBarRunnable)
        bar.removeCallbacks(hideUndoBarRunnable)

        text?.text = context.getString(R.string.clipboard_clip_deleted)
        button?.setOnClickListener {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
            val clipToRestore = lastDismissedEntry
            val targetPos = lastDismissedPosition
            if (clipToRestore != null) {
                val restoredPos = clipboardHistoryManager.restoreEntry(clipToRestore, targetPos)
                if (restoredPos >= 0) {
                    clipboardAdapter.notifyItemInserted(restoredPos)
                    clipboardRecyclerView.smoothScrollToPosition(restoredPos)
                }
                lastDismissedEntry = null
                lastDismissedPosition = -1
            }
            hideUndoBar()
        }

        if (bar.visibility != View.VISIBLE) {
            bar.alpha = 0f
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(180).start()
        }
        bar.postDelayed(hideUndoBarRunnable, 10000)
    }

    private fun hideUndoBar() {
        val bar = undoBar ?: return
        if (bar.visibility == View.VISIBLE) {
            bar.animate().alpha(0f).setDuration(180).withEndAction {
                bar.visibility = View.GONE
            }.start()
        }
    }

    override fun onClick(view: View) {
        if (view === backButton) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        } else if (view === clearButton) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            clipboardHistoryManager.clearHistory()
        }
    }

    override fun onKeyDown(clipId: Long) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, true, HapticEvent.KEY_PRESS)
    }

    override fun onKeyUp(clipId: Long) {
        val clipContent = clipboardHistoryManager.getHistoryEntryContent(clipId)
        if (clipContent != null && !clipboardHistoryManager.pasteHistoryEntry(clipContent)) {
            keyboardActionListener.onTextInput(clipContent.text)
        }
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun onClipInserted(position: Int) {
        clipboardAdapter.notifyItemInserted(position)
        clipboardRecyclerView.smoothScrollToPosition(position)
    }

    override fun onClipsRemoved(position: Int, count: Int) {
        clipboardAdapter.notifyItemRangeRemoved(position, count)
    }

    override fun onClipMoved(oldPosition: Int, newPosition: Int) {
        clipboardAdapter.notifyItemMoved(oldPosition, newPosition)
        clipboardAdapter.notifyItemChanged(newPosition)
        if (newPosition < oldPosition) clipboardRecyclerView.smoothScrollToPosition(newPosition)
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        // The setting can only be changed from a settings screen, but adding it to this listener seems necessary: https://github.com/HeliBorg/HeliBoard/pull/1903#issuecomment-3478424606
        if (::clipboardHistoryManager.isInitialized && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            // Ensure settings are reloaded first
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            clipboardHistoryManager.sortHistoryEntries()
            clipboardAdapter.notifyDataSetChanged()
        }
    }
}
