// SPDX-License-Identifier: GPL-3.0-only
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardId
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.FloatingDeleteButton
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
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
    private val keyBackgroundId: Int

    private lateinit var clipboardRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var placeholderView: TextView
    private lateinit var backButton: ImageButton
    private lateinit var viewModeButton: ImageButton
    private lateinit var clearButton: ImageButton
    private lateinit var titleView: TextView
    private lateinit var composeTabsView: ComposeView
    private lateinit var viewPager: ViewPager2

    private lateinit var clipboardAdapter: ClipboardAdapter
    private lateinit var pinnedAdapter: ClipboardAdapter
    private lateinit var recentsAdapter: ClipboardAdapter

    private lateinit var pinnedPage: FrameLayout
    private lateinit var recentsPage: FrameLayout
    private lateinit var pinnedRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var recentsRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var pinnedPlaceholderView: TextView
    private lateinit var recentsPlaceholderView: TextView

    private val selectedTabState = mutableIntStateOf(0)
    private val pinnedFirstState = mutableStateOf(false)
    private var currentViewPagerPinnedFirst: Boolean? = null
    private var composeLifecycleOwner: ComposeLifecycleOwner? = null
    private val colorsState = mutableStateOf<Colors?>(null)
    private var isListView = false

    private var undoBar: View? = null
    private var undoText: TextView? = null
    private var undoButton: TextView? = null
    private var lastDismissedEntry: ClipboardHistoryEntry? = null
    private var lastDismissedPosition: Int = -1
    private val hideUndoBarRunnable = Runnable { hideUndoBar() }
    private var floatingBackspaceButton: FloatingDeleteButton? = null

    lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager

    init {
        @SuppressLint("UseKtx") // suggestion does not work
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        isListView = context.prefs().getBoolean(Settings.PREF_CLIPBOARD_AS_LIST, Defaults.PREF_CLIPBOARD_AS_LIST)
    }

    private fun getLatinIME(): LatinIME? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is LatinIME) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    override fun onAttachedToWindow() {
        val latinIME = getLatinIME()
        if (latinIME != null) {
            val root = rootView
            if (root != null) {
                root.setViewTreeLifecycleOwner(latinIME)
                root.setViewTreeSavedStateRegistryOwner(latinIME)
                root.setViewTreeViewModelStoreOwner(latinIME)
            }
            setViewTreeLifecycleOwner(latinIME)
            setViewTreeSavedStateRegistryOwner(latinIME)
            setViewTreeViewModelStoreOwner(latinIME)
        }
        super.onAttachedToWindow()
        if (Settings.getValues().mClipboardShowTabs) {
            if (composeLifecycleOwner == null) {
                setupComposeButtonGroup()
            } else {
                composeLifecycleOwner?.start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        composeLifecycleOwner?.stop()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (composeLifecycleOwner == null && Settings.getValues().mClipboardShowTabs) {
            setupComposeButtonGroup()
        }
        val settings = Settings.getValues()
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val persistentEmojiEnabled = context.prefs().getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, Defaults.PREF_PERSISTENT_EMOJI_ROW)
        val emojiRowHeight = if (persistentEmojiEnabled) (41 * resources.displayMetrics.density).toInt() else 0
        val toolbarHeight = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height)
        val tabsHeight = if (settings.mClipboardShowTabs) (42 * resources.displayMetrics.density).toInt() else 0
        val finalHeight = abcHeight + emojiRowHeight + toolbarHeight + tabsHeight + paddingTop + paddingBottom - (6 * resources.displayMetrics.density).toInt() + 1
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initialize() { // needs to be delayed for access to ClipboardStrip, which is not a child of this view
        if (this::clipboardAdapter.isInitialized) return
        clipboardAdapter = ClipboardAdapter(clipboardLayoutParams, this, FilterMode.ALL).apply {
            itemBackgroundId = keyBackgroundId
        }
        pinnedAdapter = ClipboardAdapter(clipboardLayoutParams, this, FilterMode.PINNED_ONLY).apply {
            itemBackgroundId = keyBackgroundId
        }
        recentsAdapter = ClipboardAdapter(clipboardLayoutParams, this, FilterMode.RECENTS_ONLY).apply {
            itemBackgroundId = keyBackgroundId
        }

        val defaultColCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
        val initialSpanCount = if (isListView) 1 else defaultColCount

        placeholderView = findViewById(R.id.clipboard_empty_view)
        clipboardRecyclerView = findViewById<ClipboardHistoryRecyclerView>(R.id.clipboard_list).apply {
            layoutManager = StaggeredGridLayoutManager(initialSpanCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation") // "no cache" should be fine according to warning in https://developer.android.com/reference/android/view/ViewGroup#setPersistentDrawingCache(int)
            persistentDrawingCache = PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = this@ClipboardHistoryView.placeholderView
            clipDismissListener = this@ClipboardHistoryView
        }

        val (pPage, pRecycler, pEmpty) = createClipboardPage(pinnedAdapter, initialSpanCount)
        pinnedPage = pPage
        pinnedRecyclerView = pRecycler
        pinnedPlaceholderView = pEmpty

        val (rPage, rRecycler, rEmpty) = createClipboardPage(recentsAdapter, initialSpanCount)
        recentsPage = rPage
        recentsRecyclerView = rRecycler
        recentsPlaceholderView = rEmpty

        val pinnedFirst = Settings.getValues()?.mClipboardHistoryPinnedFirst ?: false
        pinnedFirstState.value = pinnedFirst
        currentViewPagerPinnedFirst = pinnedFirst
        val pages = if (pinnedFirst) listOf(pinnedPage, recentsPage) else listOf(recentsPage, pinnedPage)

        viewPager = findViewById<ViewPager2>(R.id.clipboard_view_pager).apply {
            isUserInputEnabled = false
            adapter = ClipboardViewPagerAdapter(pages)
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    selectedTabState.intValue = position
                }
            })
        }

        backButton = findViewById(R.id.clipboard_back_button)
        viewModeButton = findViewById(R.id.clipboard_view_mode_button)
        clearButton = findViewById(R.id.clipboard_clear_button)
        titleView = findViewById(R.id.clipboard_title)
        composeTabsView = findViewById(R.id.compose_clipboard_tabs)
        undoBar = findViewById(R.id.clipboard_undo_bar)
        undoText = findViewById(R.id.clipboard_undo_text)
        undoButton = findViewById(R.id.clipboard_undo_button)
        floatingBackspaceButton = findViewById(R.id.clipboard_floating_backspace)
    }

    private fun createClipboardPage(adapter: ClipboardAdapter, spanCount: Int): Triple<FrameLayout, ClipboardHistoryRecyclerView, TextView> {
        val pageLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val emptyView = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
            visibility = View.INVISIBLE
            text = "¯\\_(ツ)_/¯"
        }
        val recyclerView = ClipboardHistoryRecyclerView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            layoutManager = StaggeredGridLayoutManager(spanCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation")
            persistentDrawingCache = ViewGroup.PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = emptyView
            clipDismissListener = this@ClipboardHistoryView
            this.adapter = adapter
        }
        pageLayout.addView(emptyView)
        pageLayout.addView(recyclerView)
        return Triple(pageLayout, recyclerView, emptyView)
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
        colorsState.value = colors
        val stripColor = colors.get(ColorType.STRIP_BACKGROUND)
        findViewById<LinearLayout>(R.id.clipboard_toolbar).setBackgroundColor(stripColor)
        
        setupHeaderButton(backButton, colors)
        setupHeaderButton(viewModeButton, colors)
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
        viewModeButton.setOnClickListener(this)
        clearButton.setOnClickListener(this)
        updateViewModeButtonUI()
        floatingBackspaceButton?.applyTheme(colors)
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

    private fun selectTab(position: Int) {
        if (::viewPager.isInitialized && viewPager.currentItem != position) {
            viewPager.setCurrentItem(position, true)
        }
        selectedTabState.intValue = position
    }

    private fun updateTabsMode(showTabs: Boolean) {
        titleView.visibility = View.VISIBLE
        if (showTabs) {
            composeTabsView.visibility = View.VISIBLE
            clipboardRecyclerView.visibility = View.GONE
            placeholderView.visibility = View.GONE
            viewPager.visibility = View.VISIBLE
        } else {
            composeTabsView.visibility = View.GONE
            viewPager.visibility = View.GONE
            clipboardRecyclerView.visibility = View.VISIBLE
            if (this::clipboardAdapter.isInitialized) {
                if (clipboardAdapter.itemCount == 0) {
                    placeholderView.visibility = View.VISIBLE
                    clipboardRecyclerView.visibility = View.INVISIBLE
                } else {
                    placeholderView.visibility = View.INVISIBLE
                    clipboardRecyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun toggleViewMode() {
        isListView = !isListView
        context.prefs().edit().putBoolean(Settings.PREF_CLIPBOARD_AS_LIST, isListView).apply()
        applyViewMode()
    }

    private fun applyViewMode() {
        updateViewModeButtonUI()
        val defaultColCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
        val targetSpanCount = if (isListView) 1 else defaultColCount
        (clipboardRecyclerView.layoutManager as? StaggeredGridLayoutManager)?.spanCount = targetSpanCount
        if (::pinnedRecyclerView.isInitialized) {
            (pinnedRecyclerView.layoutManager as? StaggeredGridLayoutManager)?.spanCount = targetSpanCount
        }
        if (::recentsRecyclerView.isInitialized) {
            (recentsRecyclerView.layoutManager as? StaggeredGridLayoutManager)?.spanCount = targetSpanCount
        }
        if (::clipboardAdapter.isInitialized) {
            clipboardAdapter.notifyItemRangeChanged(0, clipboardAdapter.itemCount)
        }
        if (::pinnedAdapter.isInitialized) {
            pinnedAdapter.notifyItemRangeChanged(0, pinnedAdapter.itemCount)
        }
        if (::recentsAdapter.isInitialized) {
            recentsAdapter.notifyItemRangeChanged(0, recentsAdapter.itemCount)
        }
    }

    private fun updateViewModeButtonUI() {
        if (!::viewModeButton.isInitialized) return
        if (isListView) {
            viewModeButton.setImageResource(R.drawable.ic_view_grid)
            viewModeButton.contentDescription = context.getString(R.string.clipboard_switch_to_grid_view)
        } else {
            viewModeButton.setImageResource(R.drawable.ic_view_list)
            viewModeButton.contentDescription = context.getString(R.string.clipboard_switch_to_list_view)
        }
    }

    private fun setupComposeButtonGroup() {
        val composeView = findViewById<ComposeView>(R.id.compose_clipboard_tabs) ?: return
        val latinIME = getLatinIME()
        val owner = composeLifecycleOwner ?: ComposeLifecycleOwner().also { composeLifecycleOwner = it }
        if (latinIME != null) {
            composeView.setViewTreeLifecycleOwner(latinIME)
            composeView.setViewTreeSavedStateRegistryOwner(latinIME)
            composeView.setViewTreeViewModelStoreOwner(latinIME)
        } else {
            composeView.setViewTreeLifecycleOwner(owner)
            composeView.setViewTreeSavedStateRegistryOwner(owner)
            composeView.setViewTreeViewModelStoreOwner(owner)
        }
        owner.start()
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        composeView.setContent {
            val selectedTab by selectedTabState
            val keyboardColors = colorsState.value ?: Settings.getValues().mColors
            val isNight = ResourceUtils.isNight(context.resources)

            val primaryVal = androidx.compose.ui.graphics.Color(keyboardColors.get(ColorType.SPECIAL_KEY_BACKGROUND))
            val onPrimaryVal = androidx.compose.ui.graphics.Color(keyboardColors.get(ColorType.ACTION_KEY_ICON))
            val surfaceVal = androidx.compose.ui.graphics.Color(keyboardColors.get(ColorType.KEY_BACKGROUND))
            val onSurfaceVal = androidx.compose.ui.graphics.Color(keyboardColors.get(ColorType.KEY_TEXT))

            val themeStyle = keyboardColors.themeStyle
            val isSquareStyle = themeStyle == KeyboardTheme.STYLE_MATERIAL || themeStyle == KeyboardTheme.STYLE_HOLO

            val leadingShape = if (isSquareStyle) {
                RoundedCornerShape(8.dp)
            } else {
                RoundedCornerShape(topStart = CornerSize(50), bottomStart = CornerSize(50), topEnd = CornerSize(8.dp), bottomEnd = CornerSize(8.dp))
            }

            val middleShape = RoundedCornerShape(8.dp)

            val trailingShape = if (isSquareStyle) {
                RoundedCornerShape(8.dp)
            } else {
                RoundedCornerShape(topStart = CornerSize(8.dp), bottomStart = CornerSize(8.dp), topEnd = CornerSize(50), bottomEnd = CornerSize(50))
            }

            val colorScheme = if (isNight) {
                androidx.compose.material3.darkColorScheme(
                    primary = primaryVal,
                    onPrimary = onPrimaryVal,
                    surface = surfaceVal,
                    onSurface = onSurfaceVal,
                    secondaryContainer = surfaceVal,
                    onSecondaryContainer = onSurfaceVal,
                    surfaceVariant = surfaceVal,
                    onSurfaceVariant = onSurfaceVal,
                    outline = androidx.compose.ui.graphics.Color.Transparent,
                    outlineVariant = androidx.compose.ui.graphics.Color.Transparent
                )
            } else {
                androidx.compose.material3.lightColorScheme(
                    primary = primaryVal,
                    onPrimary = onPrimaryVal,
                    surface = surfaceVal,
                    onSurface = onSurfaceVal,
                    secondaryContainer = surfaceVal,
                    onSecondaryContainer = onSurfaceVal,
                    surfaceVariant = surfaceVal,
                    onSurfaceVariant = onSurfaceVal,
                    outline = androidx.compose.ui.graphics.Color.Transparent,
                    outlineVariant = androidx.compose.ui.graphics.Color.Transparent
                )
            }

            val customFontFamily = KeyboardTypeface.customFontFamily()

            MaterialTheme(colorScheme = colorScheme) {
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(
                        fontFamily = customFontFamily,
                        fontSize = 14.sp
                    )
                ) {
                    ButtonGroup(
                        overflowIndicator = {},
                        expandedRatio = 0f,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        val pinnedFirst by pinnedFirstState
                        val tabs = if (pinnedFirst) {
                            listOf(
                                Triple(0, context.getString(R.string.clipboard_header_pinned), R.drawable.ic_clipboard_pin_rounded),
                                Triple(1, context.getString(R.string.clipboard_header_recents), R.drawable.ic_emoji_recents)
                            )
                        } else {
                            listOf(
                                Triple(0, context.getString(R.string.clipboard_header_recents), R.drawable.ic_emoji_recents),
                                Triple(1, context.getString(R.string.clipboard_header_pinned), R.drawable.ic_clipboard_pin_rounded)
                            )
                        }
                        tabs.forEachIndexed { index, (tabIndex, displayText, iconRes) ->
                            val isSelected = selectedTab == tabIndex
                            customItem(
                                buttonGroupContent = {
                                    val buttonShapes = when (index) {
                                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes(
                                            shape = leadingShape,
                                            pressedShape = RoundedCornerShape(4.dp),
                                            checkedShape = RoundedCornerShape(percent = 50)
                                        )
                                        tabs.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes(
                                            shape = trailingShape,
                                            pressedShape = RoundedCornerShape(4.dp),
                                            checkedShape = RoundedCornerShape(percent = 50)
                                        )
                                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes(
                                            shape = middleShape,
                                            pressedShape = RoundedCornerShape(4.dp),
                                            checkedShape = RoundedCornerShape(percent = 50)
                                        )
                                    }
                                    ToggleButton(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                                                    KeyCode.NOT_SPECIFIED,
                                                    composeView,
                                                    HapticEvent.KEY_PRESS
                                                )
                                                selectTab(tabIndex)
                                            }
                                        },
                                        shapes = buttonShapes,
                                        colors = ToggleButtonDefaults.toggleButtonColors(
                                            containerColor = surfaceVal,
                                            contentColor = onSurfaceVal,
                                            checkedContainerColor = primaryVal,
                                            checkedContentColor = onPrimaryVal
                                        ),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            androidx.compose.material3.Icon(
                                                painter = androidx.compose.ui.res.painterResource(iconRes),
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(displayText)
                                        }
                                    }
                                },
                                menuContent = { _ -> }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setupClipKey(params: KeyDrawParams) {
        listOf(clipboardAdapter, pinnedAdapter, recentsAdapter).forEach { adapter ->
            adapter.apply {
                itemBackgroundId = keyBackgroundId
                itemTypeFace = params.mTypeface
                itemTextColor = params.mTextColor
                itemTextSize = params.mLabelSize.toFloat()
            }
        }
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        if (!Settings.getValues().mRemovePanelBottomRow) {
            PointerTracker.switchTo(keyboardView)
        }
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardId.ELEMENT_CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    private fun applyBottomRowVisibility() {
        val removeBottomRow = Settings.getValues().mRemovePanelBottomRow
        val bottomRow = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        if (removeBottomRow) {
            bottomRow?.visibility = View.GONE
            floatingBackspaceButton?.visibility = View.VISIBLE
        } else {
            bottomRow?.visibility = View.VISIBLE
            floatingBackspaceButton?.visibility = View.GONE
        }
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        // TODO: Should use LAYER_TYPE_SOFTWARE when hardware acceleration is off?
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun updateViewPagerPages(pinnedFirst: Boolean) {
        if (!::viewPager.isInitialized) return
        pinnedFirstState.value = pinnedFirst
        if (currentViewPagerPinnedFirst != pinnedFirst || viewPager.adapter == null) {
            currentViewPagerPinnedFirst = pinnedFirst
            val pages = if (pinnedFirst) listOf(pinnedPage, recentsPage) else listOf(recentsPage, pinnedPage)
            viewPager.adapter = ClipboardViewPagerAdapter(pages)
        }
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
        val showTabs = settings.current.mClipboardShowTabs
        setupHeader(settings.current.mColors)
        setupUndoBar(settings.current.mColors)
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        clipboardAdapter.clipboardHistoryManager = historyManager
        pinnedAdapter.clipboardHistoryManager = historyManager
        recentsAdapter.clipboardHistoryManager = historyManager

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        setupClipKey(params)
        setupBottomRowKeyboard(editorInfo, keyboardActionListener)
        floatingBackspaceButton?.init(keyboardActionListener, settings.current.mColors)
        applyBottomRowVisibility()

        listOf(placeholderView, pinnedPlaceholderView, recentsPlaceholderView).forEach { pv ->
            pv.apply {
                KeyboardTypeface.applyToTextView(this)
                setTextColor(params.mTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, params.mLabelSize.toFloat() * 2)
            }
        }

        val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
        val keyboardAttr = context.obtainStyledAttributes(
            null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
        val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
            keyboardWidth, keyboardWidth, 0f)
                * settings.current.mSidePaddingScale).toInt()
        val rightPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
            keyboardWidth, keyboardWidth, 0f)
                * settings.current.mSidePaddingScale).toInt()
        keyboardAttr.recycle()

        clipboardRecyclerView.apply {
            adapter = clipboardAdapter
            layoutParams.width = keyboardWidth
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }
        pinnedRecyclerView.apply {
            adapter = pinnedAdapter
            layoutParams.width = keyboardWidth
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }
        recentsRecyclerView.apply {
            adapter = recentsAdapter
            layoutParams.width = keyboardWidth
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }
        val pinnedFirst = settings.current.mClipboardHistoryPinnedFirst
        updateViewPagerPages(pinnedFirst)

        if (showTabs) {
            if (composeLifecycleOwner == null) {
                setupComposeButtonGroup()
            }
            selectedTabState.intValue = 0
            viewPager.setCurrentItem(0, false)
        }
        updateTabsMode(showTabs)
        applyViewMode()

        context.prefs().registerOnSharedPreferenceChangeListener(this)
        refreshClipboardItems()
    }

    private fun refreshClipboardItems(scrollToPos: Int? = null) {
        if (!::clipboardAdapter.isInitialized || !::clipboardHistoryManager.isInitialized) return
        val entries = clipboardHistoryManager.getAllEntries()
        val pinnedFirst = Settings.getValues()?.mClipboardHistoryPinnedFirst ?: false
        clipboardAdapter.updateItems(entries, pinnedFirst)
        pinnedAdapter.updateItems(entries, true)
        recentsAdapter.updateItems(entries, false)
        if (scrollToPos != null && scrollToPos in 0 until clipboardAdapter.itemCount) {
            clipboardRecyclerView.smoothScrollToPosition(scrollToPos)
        }
    }

    fun stopClipboardHistory() {
        hideUndoBar()
        removeCallbacks(hideUndoBarRunnable)
        undoBar?.removeCallbacks(hideUndoBarRunnable)
        if (!this::clipboardAdapter.isInitialized) return
        context.prefs().unregisterOnSharedPreferenceChangeListener(this)
        clipboardRecyclerView.adapter = null
        pinnedRecyclerView.adapter = null
        recentsRecyclerView.adapter = null
        clipboardHistoryManager.setHistoryChangeListener(null)
        clipboardAdapter.clipboardHistoryManager = null
        pinnedAdapter.clipboardHistoryManager = null
        recentsAdapter.clipboardHistoryManager = null
        clipboardAdapter.clear()
        pinnedAdapter.clear()
        recentsAdapter.clear()
        composeLifecycleOwner?.stop()
        floatingBackspaceButton?.setKeyboardActionListener(null)
        currentViewPagerPinnedFirst = null
        if (::viewPager.isInitialized) {
            viewPager.adapter = null
        }
    }

    override fun onClipDismissed(entry: ClipboardHistoryEntry, originalPosition: Int) {
        refreshClipboardItems()
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
                    refreshClipboardItems()
                    val adapterPos = clipboardAdapter.findPositionOfClip(clipToRestore.id)
                    if (adapterPos >= 0) {
                        clipboardRecyclerView.smoothScrollToPosition(adapterPos)
                    }
                    val recentsPos = recentsAdapter.findPositionOfClip(clipToRestore.id)
                    if (recentsPos >= 0) {
                        recentsRecyclerView.smoothScrollToPosition(recentsPos)
                    }
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
        } else if (view === viewModeButton) {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            toggleViewMode()
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
        post {
            refreshClipboardItems()
            val recentPos = clipboardAdapter.findFirstRecentPosition()
            if (recentPos >= 0) {
                clipboardRecyclerView.smoothScrollToPosition(recentPos)
            }
            val recentsPagePos = recentsAdapter.findFirstRecentPosition()
            if (recentsPagePos >= 0) {
                recentsRecyclerView.smoothScrollToPosition(recentsPagePos)
            }
        }
    }

    override fun onClipsRemoved(position: Int, count: Int) {
        post {
            refreshClipboardItems()
        }
    }

    override fun onClipMoved(oldPosition: Int, newPosition: Int) {
        post {
            refreshClipboardItems()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        // The setting can only be changed from a settings screen, but adding it to this listener seems necessary: https://github.com/HeliBorg/HeliBoard/pull/1903#issuecomment-3478424606
        if (::clipboardHistoryManager.isInitialized && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            // Ensure settings are reloaded first
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            val pinnedFirst = Settings.getValues().mClipboardHistoryPinnedFirst
            updateViewPagerPages(pinnedFirst)
            selectedTabState.intValue = 0
            if (::viewPager.isInitialized) {
                viewPager.setCurrentItem(0, false)
            }
            clipboardHistoryManager.sortHistoryEntries()
            refreshClipboardItems()
        } else if (key == Settings.PREF_CLIPBOARD_SHOW_TABS) {
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            val showTabs = Settings.getValues().mClipboardShowTabs
            if (showTabs && composeLifecycleOwner == null) {
                setupComposeButtonGroup()
            }
            updateTabsMode(showTabs)
            refreshClipboardItems()
            requestLayout()
        } else if (key == Settings.PREF_REMOVE_PANEL_BOTTOM_ROW) {
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            applyBottomRowVisibility()
            requestLayout()
        }
    }

    private class ClipboardViewPagerAdapter(
        private val pages: List<View>
    ) : RecyclerView.Adapter<ClipboardViewPagerAdapter.ViewHolder>() {
        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = pages[viewType]
            (view.parent as? ViewGroup)?.removeView(view)
            return ViewHolder(view)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
        override fun getItemViewType(position: Int): Int = position
        override fun getItemCount(): Int = pages.size
    }

    private class ComposeLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateRegistryController = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()

        init {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun start() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun stop() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }

        fun destroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }

        override val lifecycle: Lifecycle
            get() = lifecycleRegistry

        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateRegistryController.savedStateRegistry

        override val viewModelStore: ViewModelStore
            get() = store
    }
}
