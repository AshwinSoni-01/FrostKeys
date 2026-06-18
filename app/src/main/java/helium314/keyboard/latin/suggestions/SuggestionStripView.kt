/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.DragEvent
import android.view.View.DragShadowBuilder
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.KeyBackgroundUtils
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.MAX_PINNED_TOOLBAR_KEYS
import helium314.keyboard.latin.utils.TOOLBAR_DRAG_CLIP_LABEL
import helium314.keyboard.latin.utils.ToolbarDragSource
import helium314.keyboard.latin.utils.ToolbarDragState
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.getToolbarKeyFromDragData
import helium314.keyboard.latin.utils.pinToolbarKeyAt
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removeFirst
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import helium314.keyboard.latin.utils.updateToolbarButtonActivatedState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import androidx.core.view.isGone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            KeyboardTypeface.applyToTextView(word)
            colors.setBackground(word, ColorType.STRIP_BACKGROUND)
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            KeyboardTypeface.applyToTextView(info)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // toolbar views, drawables and setup
    private val toolbar: ViewGroup = findViewById(R.id.toolbar)
    private val toolbarContainer: View = findViewById(R.id.toolbar_container)
    private val suggestionsMiddleContainer: ViewGroup = findViewById(R.id.suggestions_middle_container)
    private val pinnedKeys: ViewGroup = findViewById(R.id.pinned_keys_container)
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val persistentToolbarKey: ImageButton = findViewById(R.id.persistent_toolbar_key)
    private val accessPointTriggerBtn: ImageButton = findViewById(R.id.access_point_trigger_btn)
    private val toolbarExpandKey: ImageButton? = null
    private val incognitoIcon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.INCOGNITO.name, context)
    private val toolbarArrowIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
    private val enabledToolKeyBackground = GradientDrawable()
    private var direction = 1 // 1 if LTR, -1 if RTL
    private val suggestionsChipScroll: View = findViewById(R.id.suggestions_chip_scroll)
    private val suggestionsChipStrip: ViewGroup = findViewById(R.id.suggestions_chip_strip)

    private val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
        LinearLayout.LayoutParams.MATCH_PARENT
    )

    private var suggestedWords = SuggestedWords.getEmptyInstance()
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private var isPinnedToolbarDragActive = false
    private var isAccessPointMenuOpen = false
    private var pinnedDropPreviewKey: ToolbarKey? = null
    private var pinnedDropPreviewSource: ToolbarDragSource? = null
    private var pinnedDropPreviewRequestedIndex = -1
    private var pinnedDropPreviewIndex = -1
    private val dragTargetScreenLocation = IntArray(2)
    private val pinnedScreenLocation = IntArray(2)
    private val pinnedSlotViewScratch = ArrayList<View>(MAX_PINNED_TOOLBAR_KEYS)
    private var lastPinnedKeysWidth = 0

    init {
        val colors = Settings.getValues().mColors

        // expand key
        toolbarExpandKey?.let {
            val toolbarHeight = min(it.layoutParams.height, resources.getDimension(R.dimen.config_suggestions_strip_height).toInt())
            it.layoutParams.height = toolbarHeight
            it.layoutParams.width = toolbarHeight // we want it square
            colors.setBackground(it, ColorType.STRIP_BACKGROUND) // necessary because background is re-used for defaultToolbarBackground
            colors.setColor(it, ColorType.TOOL_BAR_EXPAND_KEY)
            colors.setColor(it.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)
            it.isVisible = false
        }

        // background indicator for pinned keys
        val color = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000 // ignore alpha (in Java this is more readable 0xFF000000)
        enabledToolKeyBackground.colors = intArrayOf(color, Color.TRANSPARENT)
        enabledToolKeyBackground.gradientType = GradientDrawable.RADIAL_GRADIENT
        enabledToolKeyBackground.gradientRadius = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f

        // toolbar keys setup
        findViewById<ImageButton>(R.id.access_point_trigger_btn)?.let {
            setupKey(it, colors)
            applyAlphabetIconTint(it, colors)
            applySpecialKeyCircleBackground(it, colors)
        }
        val pinnedDropListener = View.OnDragListener { target, event -> onPinnedToolbarDrag(target, event) }
        suggestionsMiddleContainer.setOnDragListener(pinnedDropListener)
        suggestionsStrip.setOnDragListener(pinnedDropListener)
        suggestionsChipScroll.setOnDragListener(pinnedDropListener)
        pinnedKeys.setOnDragListener(pinnedDropListener)

        updateKeys()
    }

    private lateinit var listener: Listener
    private var startIndexOfMoreSuggestions = 0
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && toolbarContainer.visibility != VISIBLE && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
            toolbarExpandKey?.scaleX = (if (toolbarContainer.visibility != VISIBLE) 1f else -1f) * direction
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
        suggestionsChipStrip.layoutDirection = newLayoutDirection
        pinnedKeys.layoutDirection = newLayoutDirection
    }

    fun setToolbarVisibility(toolbarVisible: Boolean) {
        toolbarContainer.isVisible = false
        updateSuggestionContainersVisibility(true)

        if (DEBUG_SUGGESTIONS) {
            for (view in debugInfoViews) {
                view.visibility = if (suggestionsStrip.isVisible) VISIBLE else GONE
            }
        }

        toolbarExpandKey?.scaleX = direction.toFloat()
        // Restore access point icon when returning to normal state
        setAccessPointIcon(isMenuOpen = false)
    }

    fun showPinnedToolbarKeys() {
        toolbarContainer.isVisible = false
        populatePinnedKeys()
        suggestionsStrip.isVisible = false
        suggestionsChipScroll.isVisible = false
        pinnedKeys.isVisible = pinnedKeys.childCount > 0
        // Swap access point icon to back arrow while menu is open
        setAccessPointIcon(isMenuOpen = true)
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        isExternalSuggestionVisible = false
        clear()
        setRtl(isRtlLanguage)
        suggestedWords = suggestions
        updateSuggestionContainersVisibility(true)
        startIndexOfMoreSuggestions =
            if (!shouldShowSuggestionContent(suggestedWords)) {
                suggestedWords.size()
            } else if (shouldUseChipSuggestions(suggestedWords)) {
                suggestionsChipScroll.scrollTo(0, 0)
                layoutHelper.layoutChipsAndReturnStartIndexOfMoreSuggestions(
                    context, suggestedWords, suggestionsChipStrip
                )
            } else {
                layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
                    context, suggestedWords, suggestionsStrip, this
                )
            }
        updateKeys()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true
        updateSuggestionContainersVisibility(true)

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = toolbarKeyLayoutParams
            setupKey(closeButton, Settings.getValues().mColors)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else {
            suggestionsStrip.addView(view)
        }

        if (Settings.getValues().mAutoHideToolbar) setToolbarVisibility(false)
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(pinnedKeys, key)
        setToolbarButtonsActivatedStateOnPrefChange(toolbar, key)
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE
            || key == Settings.PREF_PINNED_TOOLBAR_KEYS
            || key == Settings.PREF_PERSISTENT_TOOLBAR_KEY)
            GlobalScope.launch {
                delay(10)
                withContext(Dispatchers.Main) {
                    updateKeys()
                }
            }
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        if (view === this)
            updateSuggestionContainersVisibility(visibility == VISIBLE && !toolbarContainer.isVisible)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
        pinnedKeys.post {
            val width = pinnedKeys.width
            if (width > 0 && width != lastPinnedKeysWidth && !isPinnedToolbarDragActive) {
                lastPinnedKeysWidth = width
                populatePinnedKeys()
                updateSuggestionContainersVisibility(!toolbarContainer.isVisible)
            }
        }
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        if (view.id == R.id.access_point_trigger_btn) {
            KeyboardSwitcher.getInstance().onToggleKeyboard(KeyboardSwitcher.KeyboardSwitchState.ACCESS_POINT)
            // Update icon based on resulting state (back arrow when menu is open, grid when closed)
            val isMenuOpen = KeyboardSwitcher.getInstance().isShowingAccessPointMenu
            setAccessPointIcon(isMenuOpen = isMenuOpen)
            return
        }
        val tag = view.tag
        if (tag is ToolbarKey) {
            val code = getCodeForToolbarKey(tag)
            if (code != KeyCode.UNSPECIFIED) {
                Log.d(TAG, "click toolbar key $tag")
                listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
                return
            }
        }

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            if (tag >= suggestedWords.size()) {
                return
            }
            val wordInfo = suggestedWords.getInfo(tag)
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view)
            return true
        }
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    private fun onLongClickToolbarKey(view: View) {
        val tag = view.tag as? ToolbarKey ?: return
        if (view.parent === pinnedKeys) {
            startPinnedToolbarDrag(view, tag)
            return
        }
        val longClickCode = getCodeForToolbarKeyLongClick(tag)
        if (longClickCode != KeyCode.UNSPECIFIED) {
            listener.onCodeInput(longClickCode, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // no need for View#performClick, we only return false mostly anyway
    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        var showIcon = true
        if (wordView.tag is Int) {
            val index = wordView.tag as Int
            val type = suggestedWords.getInfo(index).mSourceDict
            if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED)
                showIcon = false
        }
        if (showIcon) {
            val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)!!
            Settings.getValues().mColors.setColor(icon, ColorType.REMOVE_SUGGESTION_ICON)
            val w = icon.intrinsicWidth
            val h = icon.intrinsicHeight
            wordView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            wordView.ellipsize = TextUtils.TruncateAt.END
            val downOk = AtomicBoolean(false)
            wordView.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_UP && downOk.get()) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        removeSuggestion(wordView)
                        wordView.cancelLongPress()
                        wordView.isPressed = false
                        return@setOnTouchListener true
                    }
                } else if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        downOk.set(true)
                    }
                }
                false
            }
        }
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(wordView)
            return true
        }
        return showMoreSuggestions()
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size() <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        if (index >= suggestedWords.size()) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        val text = info.mSourceDict.mDictType + ":" + info.mSourceDict.mLocale
        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(wordView: TextView) {
        val word = wordView.text.toString()
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val suggestedWordInfos = ArrayList<SuggestedWordInfo>()
        for (i in 0..<suggestedWords.size()) {
            val info = suggestedWords.getInfo(i)
            if (info.word != word) suggestedWordInfos.add(info)
        }
        suggestedWords.mRawSuggestions?.removeFirst { it.word == word }

        val newSuggestedWords = SuggestedWords(
            suggestedWordInfos, suggestedWords.mRawSuggestions, suggestedWords.typedWordInfo, suggestedWords.mTypedWordValid,
            suggestedWords.mWillAutoCorrect, suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber
        )
        setSuggestions(newSuggestedWords, direction != 1)
        updateSuggestionContainersVisibility(true)

        // Show the toolbar if no suggestions are left and the "Auto show toolbar" setting is enabled
        if (this.suggestedWords.isEmpty && Settings.getValues().mAutoShowToolbar) {
            setToolbarVisibility(true)
        }
    }

    private fun clear() {
        suggestionsStrip.removeAllViews()
        suggestionsChipStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        if (!toolbarContainer.isVisible)
            updateSuggestionContainersVisibility(true)
        dismissMoreSuggestionsPanel()
        for (word in wordViews) {
            word.setOnTouchListener(null)
        }
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    fun updateVoiceKey() {
        val show = Settings.getValues().mShowsVoiceInputKey
        toolbar.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        pinnedKeys.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        if (persistentToolbarKey.tag == ToolbarKey.VOICE) {
            persistentToolbarKey.isVisible = show
        }
    }

    private fun updateKeys() {
        val settingsValues = Settings.getValues()
        toolbarContainer.isVisible = false
        toolbarExpandKey?.isVisible = false
        toolbarExpandKey?.setOnClickListener(null)
        updatePersistentToolbarKey()
        populatePinnedKeys()
        updateVoiceKey()
        updateSuggestionContainersVisibility(true)
        // Restore access point icon to default grid when menu is closed
        setAccessPointIcon(isMenuOpen = false)
    }

    private fun shouldUseChipSuggestions(words: SuggestedWords = suggestedWords): Boolean {
        return Settings.getValues().mUseFiveWordSuggestionChips && shouldShowSuggestionContent(words)
    }

    private fun shouldShowSuggestionContent(words: SuggestedWords = suggestedWords): Boolean {
        return !words.isEmpty && !words.isPunctuationSuggestions && words.getWordCountToShow() > 0
    }

    private fun updateSuggestionContainersVisibility(showSuggestions: Boolean) {
        if (isPinnedToolbarDragActive) {
            suggestionsStrip.isVisible = false
            suggestionsChipScroll.isVisible = false
            pinnedKeys.isVisible = true
            return
        }
        if (isAccessPointMenuOpen) {
            suggestionsStrip.isVisible = false
            suggestionsChipScroll.isVisible = false
            pinnedKeys.isVisible = pinnedKeys.childCount > 0
            return
        }
        val toolbarMode = Settings.getValues().mToolbarMode
        val allowPinnedKeys = toolbarMode == ToolbarMode.EXPANDABLE || toolbarMode == ToolbarMode.TOOLBAR_KEYS
        val allowSuggestions = toolbarMode == ToolbarMode.EXPANDABLE || toolbarMode == ToolbarMode.SUGGESTION_STRIP
        val showSuggestionContent = showSuggestions && allowSuggestions &&
                (isExternalSuggestionVisible || shouldShowSuggestionContent())
        val showChips = showSuggestionContent && !isExternalSuggestionVisible && shouldUseChipSuggestions()
        suggestionsStrip.isVisible = showSuggestionContent && !showChips
        suggestionsChipScroll.isVisible = showChips
        pinnedKeys.isVisible = showSuggestions && allowPinnedKeys && !showSuggestionContent && pinnedKeys.childCount > 0
    }

    private fun populatePinnedKeys(
        previewSlots: List<Any>? = null,
    ) {
        pinnedKeys.removeAllViews()
        val persistentKey = Settings.getValues().mPersistentToolbarKey
        val slots = previewSlots ?: getPinnedToolbarKeys(context.prefs(), persistentKey)
        val colors = Settings.getValues().mColors
        if (slots.isEmpty()) return
        val slotWidth = pinnedButtonWidth(slots.size)
        pinnedKeys.addView(View(context), pinnedSpacerLayoutParams())
        for (slot in slots) {
            if (slot === PinnedDropPlaceholder) {
                pinnedKeys.addView(createPinnedDropPlaceholder(colors), pinnedSlotLayoutParams(slotWidth))
                pinnedKeys.addView(View(context), pinnedSpacerLayoutParams())
                continue
            }
            val key = slot as? ToolbarKey ?: continue
            val button = createToolbarKey(context, key)
            button.layoutParams = pinnedSlotLayoutParams(slotWidth)
            setupKey(button, colors)
            applyAlphabetIconTint(button, colors)
            applyToolbarPillBackground(button, colors)
            button.setOnClickListener(this)
            button.setOnLongClickListener(this)
            pinnedKeys.addView(button)
            pinnedKeys.addView(View(context), pinnedSpacerLayoutParams())
        }
    }

    private fun updatePersistentToolbarKey() {
        val settingsValues = Settings.getValues()
        val colors = settingsValues.mColors
        val key = settingsValues.mPersistentToolbarKey
        persistentToolbarKey.tag = key
        persistentToolbarKey.contentDescription = key.name.lowercase().getStringResourceOrName("", context)
        persistentToolbarKey.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
        setupKey(persistentToolbarKey, colors)
        applyAlphabetIconTint(persistentToolbarKey, colors)
        applySpecialKeyCircleBackground(persistentToolbarKey, colors)
        updateToolbarButtonActivatedState(persistentToolbarKey)
        persistentToolbarKey.isVisible = key != ToolbarKey.VOICE || settingsValues.mShowsVoiceInputKey
    }

    fun updateThemeColors(colors: Colors) {
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        findViewById<ImageButton>(R.id.access_point_trigger_btn)?.let {
            applyAlphabetIconTint(it, colors)
            applySpecialKeyCircleBackground(it, colors)
        }
        updatePersistentToolbarKey()
        val childCount = pinnedKeys.childCount
        for (i in 0 until childCount) {
            val child = pinnedKeys.getChildAt(i) as? ImageButton ?: continue
            applyAlphabetIconTint(child, colors)
            applyToolbarPillBackground(child, colors)
        }
        invalidate()
    }

    private fun onPinnedToolbarDrag(target: View, event: DragEvent): Boolean {
        val key = getToolbarKeyFromDragData(event.localState, event.clipData) ?: return false
        val dragState = event.localState as? ToolbarDragState
        val dragSource = dragState?.source ?: ToolbarDragSource.ACCESS_POINT_MENU
        if (!canDropToolbarKeyInPinnedRow(key)) return false

        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                true
            }
            DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                val index = pinnedDropIndexFromEvent(target, event, dragSource)
                if (index == null) {
                    clearPinnedDropPreview()
                } else {
                    showPinnedDropPreview(key, index, dragSource)
                }
                true
            }
            DragEvent.ACTION_DROP -> {
                if (pinnedDropPreviewIndex >= 0) {
                    val pinned = pinToolbarKeyAt(context.prefs(), key, pinnedDropPreviewIndex)
                    populatePinnedKeys(pinned)
                    KeyboardSwitcher.getInstance().accessPointMenuView?.populateMenu()
                    AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_PRESS)
                }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                dragState?.sourceView?.visibility = VISIBLE
                clearPinnedDropPreview()
                true
            }
            else -> true
        }
    }

    private fun canDropToolbarKeyInPinnedRow(key: ToolbarKey): Boolean {
        val toolbarMode = Settings.getValues().mToolbarMode
        val pinnedRowEnabled = toolbarMode == ToolbarMode.EXPANDABLE || toolbarMode == ToolbarMode.TOOLBAR_KEYS
        return pinnedRowEnabled && key != ToolbarKey.CLOSE_HISTORY && key != Settings.getValues().mPersistentToolbarKey
    }

    private fun pinnedDropIndexFromEvent(target: View, event: DragEvent, source: ToolbarDragSource): Int? {
        val slotViews = pinnedSlotViews()
        if (slotViews.isEmpty()) return if (source == ToolbarDragSource.ACCESS_POINT_MENU) 0 else null

        target.getLocationOnScreen(dragTargetScreenLocation)
        pinnedKeys.getLocationOnScreen(pinnedScreenLocation)
        val xInPinned = event.x + dragTargetScreenLocation[0] - pinnedScreenLocation[0]

        for (i in slotViews.indices) {
            val child = slotViews[i]
            if (xInPinned >= child.left.toFloat() && xInPinned <= child.right.toFloat()) {
                return visualIndexToPinnedIndex(i, slotViews.size)
            }
        }

        val physicalIndex = when {
            xInPinned < slotViews.first().left -> 0
            xInPinned > slotViews.last().right -> {
                if (slotViews.size >= MAX_PINNED_TOOLBAR_KEYS && source == ToolbarDragSource.ACCESS_POINT_MENU) return null
                slotViews.size
            }
            else -> {
                val gapIndex = slotViews.indexOfFirst { xInPinned < it.left }
                if (gapIndex >= 0) gapIndex else slotViews.size
            }
        }
        return visualIndexToPinnedIndex(physicalIndex, slotViews.size)
    }

    private fun showPinnedDropPreview(key: ToolbarKey, requestedIndex: Int, source: ToolbarDragSource) {
        if (pinnedDropPreviewKey == key
            && pinnedDropPreviewSource == source
            && pinnedDropPreviewRequestedIndex == requestedIndex
            && pinnedKeys.childCount > 0
        ) {
            return
        }
        val current = getPinnedToolbarKeys(context.prefs(), Settings.getValues().mPersistentToolbarKey).toMutableList()
        val previewSlots = if (source == ToolbarDragSource.PINNED_ROW) {
            current.remove(key)
            current.toMutableList<Any>().apply {
                add(requestedIndex.coerceIn(0, size), PinnedDropPlaceholder)
            }.take(MAX_PINNED_TOOLBAR_KEYS)
        } else {
            current.remove(key)
            val slots = current.toMutableList<Any>()
            slots.add(requestedIndex.coerceIn(0, slots.size), PinnedDropPlaceholder)
            slots.take(MAX_PINNED_TOOLBAR_KEYS)
        }
        val previewIndex = previewSlots.indexOf(PinnedDropPlaceholder).coerceAtLeast(0)
        if (pinnedDropPreviewKey == key && pinnedDropPreviewIndex == previewIndex && pinnedKeys.childCount > 0) {
            return
        }
        isPinnedToolbarDragActive = true
        pinnedDropPreviewKey = key
        pinnedDropPreviewSource = source
        pinnedDropPreviewRequestedIndex = requestedIndex
        pinnedDropPreviewIndex = previewIndex
        populatePinnedKeys(previewSlots)
        updateSuggestionContainersVisibility(true)
    }

    private fun pinnedSlotLayoutParams(width: Int): LinearLayout.LayoutParams {
        val stripHeight = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        val circleVisualSize = 40.dpToPx(resources) - 2 * 3.dpToPx(resources) // matches circle buttons: 40dp - 3dp inset each side = 34dp
        return LinearLayout.LayoutParams(width, circleVisualSize).apply {
            val verticalMargin = (stripHeight - circleVisualSize) / 2
            topMargin = verticalMargin
            bottomMargin = verticalMargin
        }
    }

    private fun pinnedButtonWidth(slotCount: Int): Int {
        val circleVisualSize = 40.dpToPx(resources) - 2 * 3.dpToPx(resources) // 34dp to match circle buttons
        val desiredWidth = (circleVisualSize * 1.58f).toInt()
        val availableWidth = pinnedKeys.width.takeIf { it > 0 } ?: suggestionsMiddleContainer.width
        if (availableWidth <= 0) return desiredWidth
        val visibleSlotCount = slotCount.coerceIn(1, MAX_PINNED_TOOLBAR_KEYS)
        val totalGaps = visibleSlotCount + 1
        val reservedGapWidth = 4.dpToPx(resources) * totalGaps
        val maxWidthForSlots = ((availableWidth - reservedGapWidth) / visibleSlotCount).coerceAtLeast(1)
        val minimumWidth = 32.dpToPx(resources).coerceAtMost(maxWidthForSlots)
        return desiredWidth.coerceAtMost(maxWidthForSlots).coerceAtLeast(minimumWidth)
    }

    private fun pinnedSpacerLayoutParams() =
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

    private fun createPinnedDropPlaceholder(colors: Colors): View {
        return View(context).apply {
            tag = PinnedDropPlaceholder
            background = createToolbarPillBackground(colors)
            alpha = 0.45f
        }
    }

    private fun pinnedSlotViews(): List<View> {
        pinnedSlotViewScratch.clear()
        for (i in 0 until pinnedKeys.childCount) {
            val child = pinnedKeys.getChildAt(i)
            if (child.tag is ToolbarKey || child.tag === PinnedDropPlaceholder) pinnedSlotViewScratch.add(child)
        }
        return pinnedSlotViewScratch
    }

    private fun visualIndexToPinnedIndex(physicalIndex: Int, slotCount: Int): Int {
        return if (pinnedKeys.layoutDirection == LAYOUT_DIRECTION_RTL) {
            (slotCount - physicalIndex).coerceIn(0, slotCount)
        } else {
            physicalIndex.coerceIn(0, slotCount)
        }
    }

    private fun clearPinnedDropPreview() {
        if (!isPinnedToolbarDragActive && pinnedDropPreviewKey == null) return
        isPinnedToolbarDragActive = false
        pinnedDropPreviewKey = null
        pinnedDropPreviewSource = null
        pinnedDropPreviewRequestedIndex = -1
        pinnedDropPreviewIndex = -1
        updateKeys()
    }

    private fun startPinnedToolbarDrag(view: View, key: ToolbarKey) {
        val clipData = ClipData.newPlainText(TOOLBAR_DRAG_CLIP_LABEL, key.name)
        view.visibility = INVISIBLE
        view.startDragAndDrop(clipData, DragShadowBuilder(view), ToolbarDragState(key, ToolbarDragSource.PINNED_ROW, view), 0)
    }

    private fun applyToolbarPillBackground(view: ImageButton, colors: Colors) {
        applySuggestionStripButtonIconSizing(view)
        view.background = createToolbarPillBackground(colors)
    }

    private fun applySpecialKeyCircleBackground(view: ImageButton, colors: Colors) {
        applySuggestionStripButtonIconSizing(view)
        view.background = createSpecialKeyCircleBackground(colors)
    }

    private fun applyAlphabetIconTint(view: ImageButton, colors: Colors) {
        view.clearColorFilter()
        view.imageTintMode = PorterDuff.Mode.SRC_IN
        view.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))
    }

    private fun applySuggestionStripButtonIconSizing(view: ImageButton) {
        view.setMinimumWidth(0)
        view.setMinimumHeight(0)
        view.setPadding(0, 0, 0, 0)
        view.scaleType = android.widget.ImageView.ScaleType.CENTER
        val drawable = view.drawable ?: return
        if (drawable is FixedSizeDrawable) return
        val tint = view.imageTintList
        view.setImageDrawable(FixedSizeDrawable(drawable.mutate(), 20.dpToPx(resources)))
        view.imageTintList = tint
    }

    private fun createToolbarPillBackground(colors: Colors): Drawable {
        val circleVisualSize = 40.dpToPx(resources) - 2 * 3.dpToPx(resources) // 34dp to match circle buttons
        val horizontalInset = 2.dpToPx(resources)
        val verticalInset = 0 // height is now controlled by layout params, no extra inset needed
        val cornerRadius = when (colors.themeStyle) {
            KeyboardTheme.STYLE_HOLO -> 0f
            KeyboardTheme.STYLE_MATERIAL -> 8.dpToPx(resources).toFloat()
            KeyboardTheme.STYLE_ROUNDED, KeyboardTheme.STYLE_CIRCLE -> circleVisualSize / 2f
            else -> 8.dpToPx(resources).toFloat()
        }
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(KeyBackgroundUtils.fillColorFor(colors, ColorType.KEY_BACKGROUND))
            setCornerRadius(cornerRadius)
        }
        return InsetDrawable(background, horizontalInset, verticalInset, horizontalInset, verticalInset)
    }

    private fun createSpecialKeyCircleBackground(colors: Colors): Drawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            colors.setColor(this, ColorType.SPECIAL_KEY_BACKGROUND)
        }
        val horizontalInset = 3.dpToPx(resources)
        val verticalInset = 3.dpToPx(resources)
        return InsetDrawable(circle, horizontalInset, verticalInset, horizontalInset, verticalInset)
    }

    private fun setupKey(view: ImageButton, colors: Colors) {
        view.setOnClickListener(this)
        view.setOnLongClickListener(this)
        colors.setColor(view, ColorType.TOOL_BAR_KEY)
        colors.setBackground(view, ColorType.STRIP_BACKGROUND)
    }

    private fun setAccessPointIcon(isMenuOpen: Boolean) {
        isAccessPointMenuOpen = isMenuOpen
        val colors = Settings.getValues().mColors
        val drawable = if (isMenuOpen) {
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_arrow_back)?.mutate()
        } else {
            androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_access_point_grid)?.mutate()
        } ?: return
        accessPointTriggerBtn.setImageDrawable(drawable)
        applyAlphabetIconTint(accessPointTriggerBtn, colors)
        applySuggestionStripButtonIconSizing(accessPointTriggerBtn)
        updateSuggestionContainersVisibility(true)
    }

    companion object {
        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val TAG = SuggestionStripView::class.java.simpleName
        private val PinnedDropPlaceholder = Any()
    }

    private class FixedSizeDrawable(
        private val wrapped: Drawable,
        private val size: Int
    ) : Drawable() {
        override fun draw(canvas: Canvas) {
            val currentBounds = bounds
            val left = currentBounds.left + (currentBounds.width() - size) / 2
            val top = currentBounds.top + (currentBounds.height() - size) / 2
            wrapped.setBounds(left, top, left + size, top + size)
            wrapped.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            wrapped.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            wrapped.colorFilter = colorFilter
        }

        override fun setTint(tintColor: Int) {
            wrapped.setTint(tintColor)
        }

        override fun setTintList(tint: ColorStateList?) {
            wrapped.setTintList(tint)
        }

        override fun setTintMode(tintMode: PorterDuff.Mode?) {
            wrapped.setTintMode(tintMode)
        }

        override fun isStateful(): Boolean = wrapped.isStateful

        override fun onStateChange(state: IntArray): Boolean = wrapped.setState(state)

        override fun getIntrinsicWidth(): Int = size

        override fun getIntrinsicHeight(): Int = size

        override fun mutate(): Drawable {
            wrapped.mutate()
            return this
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = wrapped.opacity
    }
}
