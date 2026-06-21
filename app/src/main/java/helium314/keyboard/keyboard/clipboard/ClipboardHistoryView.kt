// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
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
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.prefs

@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), View.OnClickListener,
    ClipboardDao.Listener, OnKeyEventListener,
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

    private fun initialize() {
        if (this::clipboardAdapter.isInitialized) return
        clipboardAdapter = ClipboardAdapter(clipboardLayoutParams, this).apply {
            itemBackgroundId = keyBackgroundId
            pinnedIconResId = pinIconId
        }
        placeholderView = findViewById(R.id.clipboard_empty_view)
        titleView = findViewById(R.id.clipboard_title)
        backButton = findViewById(R.id.clipboard_back_button)
        clearButton = findViewById(R.id.clipboard_clear_button)
        clipboardRecyclerView = findViewById<ClipboardHistoryRecyclerView>(R.id.clipboard_list).apply {
            val colCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
            layoutManager = StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation") // "no cache" should be fine according to warning in https://developer.android.com/reference/android/view/ViewGroup#setPersistentDrawingCache(int)
            persistentDrawingCache = PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = this@ClipboardHistoryView.placeholderView
        }
    }

    private fun setupClipKey(params: KeyDrawParams) {
        clipboardAdapter.apply {
            itemBackgroundId = keyBackgroundId
            itemTypeFace = params.mTypeface
            itemTextColor = params.mTextColor
            itemTextSize = params.mLabelSize.toFloat()
        }
    }

    private fun setupHeader() {
        val colors = Settings.getValues().mColors
        KeyboardTypeface.applyToTextView(
            titleView,
            titleView.text,
            android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD)
        )
        titleView.setTextColor(colors.get(ColorType.KEY_TEXT))

        setupHeaderButton(backButton, colors)
        backButton.setOnClickListener(this)

        setupHeaderButton(clearButton, colors)
        clearButton.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.CLEAR_CLIPBOARD.name, context))
        clearButton.setOnClickListener(this)
    }

    private fun setupHeaderButton(button: ImageButton, colors: Colors) {
        button.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))
        button.background = createHeaderButtonBackground(colors)
        button.setPadding(0, 0, 0, 0)
        button.scaleType = ImageView.ScaleType.CENTER
    }

    private fun createHeaderButtonBackground(colors: Colors): RippleDrawable {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            colors.setColor(this, ColorType.SPECIAL_KEY_BACKGROUND)
        }
        val rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(colors.get(ColorType.FUNCTIONAL_KEY_TEXT), 0x33)
        )
        val inset = 3.dpToPx(resources)
        val content = InsetDrawable(shape, inset, inset, inset, inset)
        return RippleDrawable(rippleColor, content, content.constantState?.newDrawable()?.mutate())
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
        setupHeader()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        clipboardAdapter.clipboardHistoryManager = historyManager

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        val settings = Settings.getInstance()
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
        if (!this::clipboardAdapter.isInitialized) return
        clipboardRecyclerView.adapter = null
        clipboardHistoryManager.setHistoryChangeListener(null)
        clipboardAdapter.clipboardHistoryManager = null
    }

    override fun onClick(view: View) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        val code = when (view.id) {
            R.id.clipboard_back_button -> KeyCode.ALPHA
            R.id.clipboard_clear_button -> KeyCode.CLIPBOARD_CLEAR_HISTORY
            else -> KeyCode.UNSPECIFIED
        }
        if (code != KeyCode.UNSPECIFIED) {
            keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
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
