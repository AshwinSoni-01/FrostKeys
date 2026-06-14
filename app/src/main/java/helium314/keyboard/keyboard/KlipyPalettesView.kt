// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.PopupWindow
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.os.ConfigurationCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.viewpager2.widget.ViewPager2
import android.widget.ProgressBar
import android.widget.ImageView
import android.view.KeyEvent
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.Suggest
import helium314.keyboard.latin.SuggestedWords
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.common.InputPointers
import helium314.keyboard.latin.database.KlipyHistoryDao
import helium314.keyboard.latin.database.KlipyHistoryDao.KlipyItem
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.stickers.AnimatedStickerProcessor
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.TextCommitDiagnostics
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.UUID
import androidx.core.graphics.ColorUtils

@SuppressLint("CustomViewStyleable")
class KlipyPalettesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = R.attr.emojiPalettesViewStyle
) : LinearLayout(context, attrs, defStyle) {

    private lateinit var gifsRecyclerView: RecyclerView
    private lateinit var stickersRecyclerView: RecyclerView
    private lateinit var gifsPage: KlipyTabPage
    private lateinit var stickersPage: KlipyTabPage
    private lateinit var viewPager: ViewPager2
    private lateinit var emptyState: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var clearHistoryButton: TextView
    private lateinit var clearHistoryConfirmOverlay: View
    private lateinit var clearHistoryConfirmTitle: TextView
    private lateinit var searchEditText: EditText
    private lateinit var pinnedGifsAdapter: KlipyAdapter
    private lateinit var pinnedStickersAdapter: KlipyAdapter
    private lateinit var gifsAdapter: KlipyAdapter
    private lateinit var stickersAdapter: KlipyAdapter
    private lateinit var historyDao: KlipyHistoryDao

    private lateinit var recentSearchesContainer: View
    private lateinit var recentSearchesCard: View
    private val recentSearchItems = ArrayList<View>()
    private val recentSearchTexts = ArrayList<TextView>()
    private val recentSearchIcons = ArrayList<ImageView>()
    private val recentSearchArrows = ArrayList<ImageView>()
    private val recentSearchSeparators = ArrayList<View>()

    private var currentTab = KlipyHistoryDao.TYPE_GIF
    private var isSearchActive = false
    private var lastGifSearch: MutableList<KlipyItem> = mutableListOf()
    private var lastStickerSearch: MutableList<KlipyItem> = mutableListOf()
    private var searchQuery = ""
    private var isUpdatingSearchText = false
    private var cachedCustomerId: String? = null
    private var currentPage = 1
    private var isLoadingMore = false
    private var hasMorePages = true

    private var isSearchMode = false
    private var currentSearchKeyboardElementId = KeyboardId.ELEMENT_ALPHABET
    private var mainEditorInfo: EditorInfo? = null
    private var mainListener: KeyboardActionListener? = null
    private var keyboardLayoutSet: KeyboardLayoutSet? = null

    lateinit var keyboardActionListener: KeyboardActionListener
    private var viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var searchJob: Job? = null
    private var mediaPruneJob: Job? = null
    private var historyActionPopup: PopupWindow? = null
    private var historyPopupBackdropView: RecyclerView? = null
    private var isInitialized = false
    private var activeSendJobs = 0
    private val inFlightStickerJobs = ConcurrentHashMap<String, Deferred<File?>>()
    private var stickerProcessorDispatcher = createStickerProcessorDispatcher()
    private var isStickerProcessorDispatcherClosed = false
    private val searchTextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

        override fun afterTextChanged(s: Editable?) {
            if (isUpdatingSearchText) return
            searchQuery = s?.toString().orEmpty()
            updateSearchQueryUI()
            updateRecentSearchesUI()
        }
    }

    private fun getLatinIME(): LatinIME? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is LatinIME) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private const val MAX_STICKER_FILE_BYTES = 500 * 1024
        private const val INLINE_PANEL_HEIGHT_DP = 500f
        private const val TAP_SCALE = 0.96f
        private const val TAP_ALPHA = 0.86f
        private const val TAP_PRESS_DURATION_MS = 70L
        private const val TAP_RELEASE_DURATION_MS = 120L
        private const val MEDIA_PRUNE_IDLE_DELAY_MS = 2500L
        private const val PREF_KLIPY_HISTORY_LONG_PRESS_HINT_DISMISSED = "klipy_history_long_press_hint_dismissed"
        private const val VIEW_TYPE_SECTION_HEADING = 20
        private const val VIEW_TYPE_HISTORY_HINT = 21
        private const val VIEW_TYPE_PINNED_ROW = 22

        private fun markFullSpan(view: View) {
            val layoutParams = view.layoutParams
            if (layoutParams is StaggeredGridLayoutManager.LayoutParams) {
                layoutParams.isFullSpan = true
            }
        }

        private fun createStickerProcessorDispatcher(): ExecutorCoroutineDispatcher {
            return Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "KlipyStickerProcessor").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val requestedHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            INLINE_PANEL_HEIGHT_DP,
            resources.displayMetrics
        ).toInt()
        val availableHeight = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.AT_MOST, MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            else -> Int.MAX_VALUE
        }
        val finalHeight = requestedHeight.coerceAtMost(availableHeight)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY))
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), finalHeight)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopKlipyPalettes()
        viewScope.cancel()
        viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        stickerProcessorDispatcher.close()
        isStickerProcessorDispatcherClosed = true
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startKlipyPalettes(
        keyVisualAttr: KeyVisualAttributes?,
        editorInfo: EditorInfo,
        keyboardActionListener: KeyboardActionListener
    ) {
        TextCommitDiagnostics.stage("KlipySearch.startKlipyPalettes", "searchMode=$isSearchMode")
        ensureViewScopeActive()
        ensureStickerProcessorDispatcherActive()
        this.keyboardActionListener = keyboardActionListener
        this.mainEditorInfo = editorInfo
        this.mainListener = keyboardActionListener
        this.keyboardLayoutSet = null
        initialize()

        // Reset search states when opening
        isSearchMode = false
        isSearchActive = false
        setSearchText("", 0)
        lastGifSearch.clear()
        lastStickerSearch.clear()
        updateSearchQueryUI()
        hideClearHistoryConfirmation()

        findViewById<View>(R.id.klipyHeader)?.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE
        pinnedGifsAdapter.resumeVisibleResources()
        pinnedStickersAdapter.resumeVisibleResources()
        gifsAdapter.resumeVisibleResources()
        stickersAdapter.resumeVisibleResources()
        showClearHistoryButton()
        viewPager.setCurrentItem(tabPosition(currentTab), false)
        updateTabSelection(currentTab)

        loadHistory()
        scheduleKlipyMediaPrune()
    }

    fun stopKlipyPalettes() {
        TextCommitDiagnostics.stage("KlipySearch.stopKlipyPalettes", "searchMode=$isSearchMode")
        searchJob?.cancel()
        searchJob = null
        inFlightStickerJobs.values.forEach { it.cancel() }
        inFlightStickerJobs.clear()
        viewScope.coroutineContext.cancelChildren()
        isLoadingMore = false
        activeSendJobs = 0
        if (isInitialized) {
            loadingIndicator.visibility = View.GONE
            dismissHistoryActionPopup()
            hideClearHistoryConfirmation()
            pinnedGifsAdapter.releaseVisibleResources()
            pinnedStickersAdapter.releaseVisibleResources()
            gifsAdapter.releaseVisibleResources()
            stickersAdapter.releaseVisibleResources()
            if (isSearchMode) {
                exitSearchMode(triggerSearch = false)
            }
        }
        mainListener?.let {
            PointerTracker.setKeyboardActionListener(it)
        }
        KeyboardSwitcher.getInstance().logTypingListenerInvariant("KlipySearch.stopKlipyPalettes", true)
    }

    fun isSearchMode(): Boolean = isSearchMode

    fun exitSearchMode() {
        exitSearchMode(triggerSearch = false)
    }

    private fun ensureViewScopeActive() {
        if (!viewScope.isActive) {
            viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
    }

    private fun ensureStickerProcessorDispatcherActive() {
        if (isStickerProcessorDispatcherClosed) {
            stickerProcessorDispatcher = createStickerProcessorDispatcher()
            isStickerProcessorDispatcherClosed = false
        }
    }

    private fun initialize() {
        if (isInitialized) return
        historyDao = KlipyHistoryDao.getInstance(context)

        viewPager = findViewById(R.id.klipyViewPager)
        emptyState = findViewById(R.id.emptyState)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        clearHistoryButton = findViewById(R.id.clearKlipyHistoryButton)
        clearHistoryConfirmOverlay = findViewById(R.id.clearHistoryConfirmOverlay)
        clearHistoryConfirmTitle = findViewById(R.id.clearHistoryConfirmTitle)
        searchEditText = findViewById(R.id.dummySearchTextView)

        recentSearchesContainer = findViewById(R.id.recentSearchesContainer)
        recentSearchesCard = findViewById(R.id.recentSearchesCard)

        recentSearchItems.clear()
        recentSearchTexts.clear()
        recentSearchIcons.clear()
        recentSearchArrows.clear()
        recentSearchSeparators.clear()

        recentSearchItems.add(findViewById(R.id.recentSearchItem1))
        recentSearchItems.add(findViewById(R.id.recentSearchItem2))
        recentSearchItems.add(findViewById(R.id.recentSearchItem3))

        recentSearchTexts.add(findViewById(R.id.recentSearchText1))
        recentSearchTexts.add(findViewById(R.id.recentSearchText2))
        recentSearchTexts.add(findViewById(R.id.recentSearchText3))

        recentSearchIcons.add(findViewById(R.id.recentSearchIcon1))
        recentSearchIcons.add(findViewById(R.id.recentSearchIcon2))
        recentSearchIcons.add(findViewById(R.id.recentSearchIcon3))

        recentSearchArrows.add(findViewById(R.id.recentSearchArrow1))
        recentSearchArrows.add(findViewById(R.id.recentSearchArrow2))
        recentSearchArrows.add(findViewById(R.id.recentSearchArrow3))

        recentSearchSeparators.add(findViewById(R.id.recentSearchSeparator1))
        recentSearchSeparators.add(findViewById(R.id.recentSearchSeparator2))

        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
        ).toInt()

        gifsPage = createKlipyTabPage(padding, KlipyHistoryDao.TYPE_GIF)
        stickersPage = createKlipyTabPage(padding, KlipyHistoryDao.TYPE_STICKER)
        gifsRecyclerView = gifsPage.historyRecyclerView
        stickersRecyclerView = stickersPage.historyRecyclerView
        pinnedGifsAdapter = gifsPage.pinnedAdapter
        pinnedStickersAdapter = stickersPage.pinnedAdapter
        gifsAdapter = gifsPage.historyAdapter
        stickersAdapter = stickersPage.historyAdapter

        viewPager.adapter = KlipyViewPagerAdapter(gifsPage.root, stickersPage.root)
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            private var lastSelectedPosition = -1

            override fun onPageSelected(position: Int) {
                lastSelectedPosition = position
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE && lastSelectedPosition != -1) {
                    val selectedTab = if (lastSelectedPosition == 0) KlipyHistoryDao.TYPE_GIF else KlipyHistoryDao.TYPE_STICKER
                    onTabSelected(selectedTab)
                }
            }
        })

        setupRecyclerViews()
        setupSearchEditText()
        setupClickListeners()
        applyTheme()

        isInitialized = true
    }

    private fun createKlipyTabPage(contentPadding: Int, type: String): KlipyTabPage {
        val isStickersMode = type == KlipyHistoryDao.TYPE_STICKER
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val pinnedAdapter = KlipyAdapter(
            emptyList(),
            isStickersMode = isStickersMode,
            forceSquareCells = true,
            onItemLongClick = ::onHistoryItemLongPressed
        ) { item -> onItemUsed(item) }
        val historyAdapter = KlipyAdapter(
            emptyList(),
            isStickersMode = isStickersMode,
            onItemLongClick = ::onHistoryItemLongPressed
        ) { item -> onItemUsed(item) }
        val pinnedHeadingAdapter = KlipySectionAdapter(
            context,
            context.getString(R.string.klipy_section_pinned),
            isHint = false
        )
        val pinnedRowAdapter = KlipyPinnedRowAdapter(
            context,
            pinnedAdapter,
            KlipyHistoryDao.MAX_PINNED_HISTORY,
            contentPadding
        )
        val historyHeadingAdapter = KlipySectionAdapter(
            context,
            context.getString(R.string.klipy_section_history),
            isHint = false
        )
        val historyHintAdapter = KlipySectionAdapter(
            context,
            context.getString(R.string.klipy_history_long_press_hint),
            isHint = true
        )
        val historyRecyclerView = RecyclerView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(contentPadding, contentPadding, contentPadding, contentPadding)
            clipToPadding = false
            isVerticalScrollBarEnabled = true
        }

        root.addView(historyRecyclerView)

        return KlipyTabPage(
            root = root,
            historyRecyclerView = historyRecyclerView,
            pinnedAdapter = pinnedAdapter,
            historyAdapter = historyAdapter,
            pinnedHeadingAdapter = pinnedHeadingAdapter,
            pinnedRowAdapter = pinnedRowAdapter,
            historyHeadingAdapter = historyHeadingAdapter,
            historyHintAdapter = historyHintAdapter
        )
    }

    private fun getRecentSearches(): List<String> {
        val prefs = context.prefs()
        val keySuffix = if (currentTab == KlipyHistoryDao.TYPE_GIF) "gif" else "sticker"
        val jsonStr = prefs.getString("klipy_recent_searches_$keySuffix", null)
        if (jsonStr == null) {
            // Tab-specific popular premium defaults!
            return if (currentTab == KlipyHistoryDao.TYPE_GIF) {
                listOf("love", "lol", "dance")
            } else {
                listOf("happy", "cat", "wow")
            }
        }
        return try {
            json.decodeFromString<List<String>>(jsonStr)
        } catch (e: Exception) {
            if (currentTab == KlipyHistoryDao.TYPE_GIF) listOf("love", "lol", "dance") else listOf("happy", "cat", "wow")
        }
    }

    private fun saveRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = getRecentSearches().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val limited = current.take(3)
        try {
            val jsonStr = json.encodeToString(limited)
            val keySuffix = if (currentTab == KlipyHistoryDao.TYPE_GIF) "gif" else "sticker"
            context.prefs().edit().putString("klipy_recent_searches_$keySuffix", jsonStr).apply()
        } catch (e: Exception) {
            Log.e("KlipyPalettesView", "Failed to save recent search", e)
        }
    }

    private fun updateRecentSearchesUI() {
        if (!::recentSearchesContainer.isInitialized) return

        if (!isSearchMode || searchQuery.isNotEmpty()) {
            recentSearchesContainer.visibility = View.GONE
            return
        }

        val recents = getRecentSearches()
        if (recents.isEmpty()) {
            recentSearchesContainer.visibility = View.GONE
            return
        }

        recentSearchesContainer.visibility = View.VISIBLE

        for (i in 0 until 3) {
            val item = recentSearchItems.getOrNull(i) ?: continue
            val text = recentSearchTexts.getOrNull(i) ?: continue
            val separator = recentSearchSeparators.getOrNull(i)

            if (i < recents.size) {
                val term = recents[i]
                text.text = term
                item.visibility = View.VISIBLE
                item.setOnClickListener {
                    performTapFeedback(item)
                    setSearchText(term, term.length)
                    exitSearchMode(triggerSearch = true)
                }

                if (separator != null) {
                    separator.visibility = if (i < recents.size - 1) View.VISIBLE else View.GONE
                }
            } else {
                item.visibility = View.GONE
                if (separator != null) {
                    separator.visibility = View.GONE
                }
            }
        }
    }

    private fun setupRecyclerViews() {
        val staggeredLayoutManager = StaggeredGridLayoutManager(
            KlipyHistoryDao.GIF_HISTORY_COLUMNS,
            StaggeredGridLayoutManager.VERTICAL
        ).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_MOVE_ITEMS_BETWEEN_SPANS
        }
        gifsRecyclerView.layoutManager = staggeredLayoutManager
        gifsRecyclerView.adapter = createHistoryConcatAdapter(gifsPage)
        gifsRecyclerView.setItemViewCacheSize(2)
        gifsRecyclerView.addOnScrollListener(createPaginationScrollListener())

        val stickerLayoutManager = GridLayoutManager(context, KlipyHistoryDao.STICKER_HISTORY_COLUMNS)
        stickersRecyclerView.layoutManager = stickerLayoutManager
        stickersRecyclerView.adapter = createHistoryConcatAdapter(stickersPage)
        stickerLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val adapter = stickersRecyclerView.adapter ?: return 1
                return if (adapter.getItemViewType(position) == KlipyAdapter.VIEW_TYPE_MEDIA) {
                    1
                } else {
                    KlipyHistoryDao.STICKER_HISTORY_COLUMNS
                }
            }
        }
        stickersRecyclerView.itemAnimator = null // prevent ghosting with translucent backgrounds
        stickersRecyclerView.setItemViewCacheSize(2)
        stickersRecyclerView.setHasFixedSize(true)
        stickersRecyclerView.addOnScrollListener(createPaginationScrollListener())
    }

    private fun createHistoryConcatAdapter(page: KlipyTabPage): ConcatAdapter {
        val config = ConcatAdapter.Config.Builder()
            .setIsolateViewTypes(false)
            .setStableIdMode(ConcatAdapter.Config.StableIdMode.ISOLATED_STABLE_IDS)
            .build()
        return ConcatAdapter(
            config,
            page.pinnedHeadingAdapter,
            page.pinnedRowAdapter,
            page.historyHeadingAdapter,
            page.historyHintAdapter,
            page.historyAdapter
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSearchEditText() {
        searchEditText.apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            setSingleLine(true)
            showSoftInputOnFocus = false
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = false
            addTextChangedListener(searchTextWatcher)
            setOnFocusChangeListener { _, hasFocus ->
                isCursorVisible = isSearchMode && hasFocus
            }
            installSearchTapTarget(this, moveCursorToEnd = false)
            setOnEditorActionListener { _, actionId, event ->
                val isEnterUp = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP
                if (actionId == EditorInfo.IME_ACTION_SEARCH || isEnterUp) {
                    syncSearchQueryFromField()
                    exitSearchMode(triggerSearch = true)
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun createPaginationScrollListener(): RecyclerView.OnScrollListener {
        return object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    dismissHistoryActionPopup()
                }
                val page = pageForRecyclerView(recyclerView) ?: return
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    page.pinnedAdapter.setAnimationsRunning(true)
                    page.historyAdapter.setAnimationsRunning(true)
                } else {
                    page.pinnedAdapter.setAnimationsRunning(false)
                    page.historyAdapter.setAnimationsRunning(false)
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy <= 0 || !isSearchActive || isLoadingMore || !hasMorePages) return

                val layoutManager = recyclerView.layoutManager ?: return
                val totalItemCount = layoutManager.itemCount
                val lastVisibleItem = when (layoutManager) {
                    is StaggeredGridLayoutManager -> {
                        val positions = layoutManager.findLastVisibleItemPositions(null)
                        positions.maxOrNull() ?: 0
                    }
                    is GridLayoutManager -> layoutManager.findLastVisibleItemPosition()
                    else -> return
                }

                if (lastVisibleItem >= totalItemCount - 4) {
                    loadMoreResults()
                }
            }
        }
    }

    private fun loadMoreResults() {
        if (isLoadingMore || !hasMorePages || searchQuery.isBlank()) return
        if (!CloudManager.isFeatureAllowed(context, CloudManager.CloudFeature.KLIPY_MEDIA)) return
        val tabAtRequest = currentTab
        isLoadingMore = true
        currentPage++

        viewScope.launch {
            try {
                val (results, hasMore) = withContext(Dispatchers.IO) {
                    fetchSearchResults(searchQuery, currentPage, tabAtRequest)
                }

                hasMorePages = hasMore

                if (results.isNotEmpty()) {
                    val currentList = if (tabAtRequest == KlipyHistoryDao.TYPE_GIF) lastGifSearch else lastStickerSearch
                    currentList.addAll(results)
                    if (currentTab == tabAtRequest) {
                        val activeAdapter = if (tabAtRequest == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
                        activeAdapter.updateItems(currentList.toList())
                    }
                }
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun onItemUsed(item: KlipyItem) {
        historyDao.addHistory(item.id, item.url, currentTab, item.width, item.height, item.previewUrl)
        scheduleKlipyMediaPrune()

        viewScope.launch {
            showLoading()
            try {
                val isSticker = currentTab == KlipyHistoryDao.TYPE_STICKER
                val sendGifAsSticker = currentTab == KlipyHistoryDao.TYPE_GIF && shouldSendGifsAsStickers()
                val sendAsSticker = isSticker || sendGifAsSticker

                val isCloudAllowed = CloudManager.isFeatureAllowed(context, CloudManager.CloudFeature.KLIPY_MEDIA)
                if (!isCloudAllowed) {
                    val cacheId = getStickerProcessingCacheId(item.id, isSticker)
                    val cachedFile = getUsableCachedProcessedStickerFile(cacheId) ?: if (!sendAsSticker) {
                        val storageDir = if (isSticker) {
                            File(context.filesDir, "stickers/klipy")
                        } else {
                            File(context.cacheDir, "klipy")
                        }
                        val suffix = if (isSticker) ".webp" else ".gif"
                        val file = File(storageDir, "klipy_${item.id}${suffix}")
                        if (file.exists()) file else null
                    } else null

                    if (cachedFile != null) {
                        if (sendAsSticker) {
                            val contentUri = "content://${context.packageName}.stickercontentprovider/stickers/klipy/${cachedFile.name}".toUri()
                            getLatinIME()?.commitKlipyContent(contentUri, if (isSticker) "Sticker" else "GIF", "image/webp.wasticker")
                        } else {
                            sendNormalGif(cachedFile)
                        }
                    } else {
                        Toast.makeText(context, "Cloud features are disabled. Please enable them in settings.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                if (sendAsSticker) {
                    val processedFile = getOrStartStickerProcessingJob(item, isSticker).await()

                    if (processedFile != null) {
                        val mimeType = "image/webp.wasticker"
                        val label = if (isSticker) "Sticker" else "GIF"

                        try {
                            val contentUri = "content://${context.packageName}.stickercontentprovider/stickers/klipy/${processedFile.name}".toUri()
                            getLatinIME()?.commitKlipyContent(contentUri, label, mimeType)
                        } catch (e: Exception) {
                            Log.e("KlipyPalettesView", "Failed to get URI for file", e)
                            Toast.makeText(context.applicationContext, "Error sharing file", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Processing failed", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val rawFile = withContext(Dispatchers.IO) {
                        downloadAndPrepareFile(item.url, item.id, isSticker)
                    }

                    if (rawFile != null) {
                        sendNormalGif(rawFile)
                    } else {
                        Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                hideLoading()
            }
        }
    }

    private fun pageForRecyclerView(recyclerView: RecyclerView): KlipyTabPage? {
        return when (recyclerView) {
            gifsRecyclerView -> gifsPage
            stickersRecyclerView -> stickersPage
            else -> null
        }
    }

    private fun onHistoryItemLongPressed(item: KlipyItem, anchor: View): Boolean {
        if (isSearchActive || isSearchMode) return false
        performTapFeedback(anchor)
        dismissLongPressHistoryHint()
        showHistoryActionPopup(item, anchor, currentTab)
        return true
    }

    private fun showHistoryActionPopup(item: KlipyItem, anchor: View, type: String) {
        dismissHistoryActionPopup()

        val colors = Settings.getValues().mColors
        val textColor = colors.get(ColorType.TOOL_BAR_KEY)
        val backgroundColor = ColorUtils.setAlphaComponent(
            colors.get(ColorType.SPECIAL_KEY_BACKGROUND),
            232
        )
        val cornerRadius = 14f * resources.displayMetrics.density
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(backgroundColor)
                setStroke(1.dpToPx(resources), ColorUtils.setAlphaComponent(textColor, 36))
                this.cornerRadius = cornerRadius
            }
            setPadding(4.dpToPx(resources), 4.dpToPx(resources), 4.dpToPx(resources), 4.dpToPx(resources))
        }

        val pinAction = createHistoryPopupAction(
            text = context.getString(if (item.isPinned) R.string.klipy_action_unpin else R.string.klipy_action_pin),
            textColor = textColor
        ) {
            if (item.isPinned) {
                historyDao.unpinHistory(item.id, type)
            } else {
                historyDao.pinHistory(item.id, type)
            }
            dismissHistoryActionPopup()
            loadHistory()
            scheduleKlipyMediaPrune()
        }
        val deleteAction = createHistoryPopupAction(
            text = context.getString(R.string.klipy_action_delete_from_history),
            textColor = textColor
        ) {
            historyDao.deleteHistory(item.id, type)
            dismissHistoryActionPopup()
            loadHistory()
            scheduleKlipyMediaPrune()
        }

        content.addView(pinAction)
        content.addView(deleteAction)

        val backdropView = if (type == KlipyHistoryDao.TYPE_GIF) gifsRecyclerView else stickersRecyclerView
        applyHistoryPopupBackdrop(backdropView)

        historyActionPopup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = 8.dpToPx(resources).toFloat()
            setOnDismissListener {
                if (historyActionPopup === this) {
                    historyActionPopup = null
                }
                clearHistoryPopupBackdrop()
            }
            showAsDropDown(anchor, 0, -anchor.height)
        }
    }

    private fun createHistoryPopupAction(text: String, textColor: Int, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(176.dpToPx(resources), 40.dpToPx(resources))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(12.dpToPx(resources), 0, 12.dpToPx(resources), 0)
            this.text = text
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            KeyboardTypeface.applyToTextView(this)
            isClickable = true
            isFocusable = true
            installTapAnimation(this)
            setOnClickListener {
                performTapFeedback(this)
                onClick()
            }
        }
    }

    private fun dismissHistoryActionPopup() {
        historyActionPopup?.dismiss()
        historyActionPopup = null
        clearHistoryPopupBackdrop()
    }

    private fun applyHistoryPopupBackdrop(recyclerView: RecyclerView) {
        clearHistoryPopupBackdrop()
        historyPopupBackdropView = recyclerView
        if (isHistoryPopupBlurAvailable(recyclerView)) {
            recyclerView.setRenderEffect(
                RenderEffect.createBlurEffect(
                    8f * resources.displayMetrics.density,
                    8f * resources.displayMetrics.density,
                    Shader.TileMode.CLAMP
                )
            )
        } else {
            recyclerView.foreground = ColorDrawable(ColorUtils.setAlphaComponent(Color.BLACK, 42))
        }
    }

    private fun isHistoryPopupBlurAvailable(recyclerView: RecyclerView): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && recyclerView.isHardwareAccelerated
    }

    private fun clearHistoryPopupBackdrop() {
        val backdropView = historyPopupBackdropView ?: return
        if (isHistoryPopupBlurAvailable(backdropView)) {
            backdropView.setRenderEffect(null)
        }
        backdropView.foreground = null
        historyPopupBackdropView = null
    }

    private fun dismissLongPressHistoryHint() {
        if (!context.prefs().getBoolean(PREF_KLIPY_HISTORY_LONG_PRESS_HINT_DISMISSED, false)) {
            context.prefs().edit().putBoolean(PREF_KLIPY_HISTORY_LONG_PRESS_HINT_DISMISSED, true).apply()
        }
        if (::gifsPage.isInitialized) gifsPage.historyHintAdapter.setVisible(false)
        if (::stickersPage.isInitialized) stickersPage.historyHintAdapter.setVisible(false)
    }

    private fun showLoading() {
        activeSendJobs++
        loadingIndicator.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        activeSendJobs = (activeSendJobs - 1).coerceAtLeast(0)
        loadingIndicator.visibility = if (activeSendJobs > 0) View.VISIBLE else View.GONE
    }

    private fun getOrStartStickerProcessingJob(item: KlipyItem, isSticker: Boolean): Deferred<File?> {
        ensureStickerProcessorDispatcherActive()

        val cacheId = getStickerProcessingCacheId(item.id, isSticker)
        inFlightStickerJobs[cacheId]?.takeUnless { it.isCancelled }?.let { return it }

        val deferred = viewScope.async(stickerProcessorDispatcher) {
            getUsableCachedProcessedStickerFile(cacheId)?.let { return@async it }

            val rawFile = withContext(Dispatchers.IO) {
                downloadAndPrepareFile(item.url, cacheId, isSticker)
            } ?: return@async null

            val processedFile = AnimatedStickerProcessor(context.applicationContext)
                .createWhatsAppAnimatedSticker(rawFile)

            if (processedFile != null && processedFile.length() > MAX_STICKER_FILE_BYTES) {
                processedFile.delete()
                return@async null
            }
            processedFile
        }
        inFlightStickerJobs[cacheId] = deferred
        deferred.invokeOnCompletion {
            inFlightStickerJobs.remove(cacheId, deferred)
        }
        return deferred
    }

    private fun getStickerProcessingCacheId(id: String, isSticker: Boolean): String {
        val typePrefix = if (isSticker) "sticker" else "gif"
        return "${typePrefix}_$id"
    }

    private fun getUsableCachedProcessedStickerFile(cacheId: String): File? {
        val file = File(context.filesDir, "stickers/klipy/animated_klipy_${cacheId}.webp")
        if (!file.exists()) return null
        if (file.length() > MAX_STICKER_FILE_BYTES) {
            file.delete()
            return null
        }
        return file
    }

    private fun scheduleKlipyMediaPrune() {
        mediaPruneJob?.cancel()
        mediaPruneJob = viewScope.launch(Dispatchers.IO) {
            delay(MEDIA_PRUNE_IDLE_DELAY_MS)
            pruneStaleKlipyMediaFiles()
        }
    }

    private fun pruneStaleKlipyMediaFiles() {
        val gifIds = historyDao.getHistory(KlipyHistoryDao.TYPE_GIF).map { it.id }.toSet()
        val stickerIds = historyDao.getHistory(KlipyHistoryDao.TYPE_STICKER).map { it.id }.toSet()

        val keptGifCacheFiles = mutableSetOf<String>()
        gifIds.forEach { id ->
            keptGifCacheFiles.add("klipy_${id}.gif")
            keptGifCacheFiles.add("klipy_gif_${id}.gif")
        }
        deleteStaleKlipyFiles(
            directory = File(context.cacheDir, "klipy"),
            keptFileNames = keptGifCacheFiles
        ) { name ->
            name.startsWith("klipy_") && name.endsWith(".gif")
        }

        val keptStickerFiles = mutableSetOf<String>()
        gifIds.forEach { id ->
            keptStickerFiles.add("animated_klipy_gif_${id}.webp")
        }
        stickerIds.forEach { id ->
            keptStickerFiles.add("klipy_${id}.webp")
            keptStickerFiles.add("klipy_sticker_${id}.webp")
            keptStickerFiles.add("animated_klipy_sticker_${id}.webp")
        }
        deleteStaleKlipyFiles(
            directory = File(context.filesDir, "stickers/klipy"),
            keptFileNames = keptStickerFiles
        ) { name ->
            (name.startsWith("klipy_") || name.startsWith("animated_klipy_")) && name.endsWith(".webp")
        }
    }

    private fun deleteStaleKlipyFiles(
        directory: File,
        keptFileNames: Set<String>,
        isGeneratedKlipyFile: (String) -> Boolean
    ) {
        if (!directory.isDirectory) return
        directory.listFiles()?.forEach { file ->
            if (file.isFile && isGeneratedKlipyFile(file.name) && file.name !in keptFileNames) {
                if (!file.delete()) {
                    Log.w("KlipyPalettesView", "Failed to delete stale Klipy file: ${file.absolutePath}")
                }
            }
        }
    }

    private fun sendNormalGif(gifFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", gifFile)
            getLatinIME()?.commitKlipyContent(contentUri, "GIF", "image/gif")
        } catch (e: Exception) {
            Log.e("KlipyPalettesView", "Failed to share GIF normally", e)
            Toast.makeText(context.applicationContext, "Error sharing GIF", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun downloadAndPrepareFile(url: String, id: String, isSticker: Boolean): File? {
        if (!CloudManager.isFeatureAllowed(context, CloudManager.CloudFeature.KLIPY_MEDIA)) {
            return null
        }
        val client = CloudManager.client
        val request = Request.Builder().url(url).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val storageDir = if (isSticker) {
                    File(context.filesDir, "stickers/klipy").apply { mkdirs() }
                } else {
                    File(context.cacheDir, "klipy").apply { mkdirs() }
                }
                val suffix = if (isSticker) ".webp" else ".gif"
                val file = File(storageDir, "klipy_${id}${suffix}")

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }

                Log.d("KlipyPalettesView", "Downloaded ${if (isSticker) "sticker" else "GIF"} to ${file.absolutePath}")

                file
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("KlipyPalettesView", "Failed to download/prepare Klipy item", e)
            null
        }
    }

    fun selectTab(tab: String) {
        initialize()
        if (normalizeTab(tab) == KlipyHistoryDao.TYPE_STICKER) {
            selectStickersTab()
        } else {
            selectGifsTab()
        }
    }

    private fun selectGifsTab() {
        if (viewPager.currentItem != 0) {
            viewPager.setCurrentItem(0, false)
        }
        onTabSelected(KlipyHistoryDao.TYPE_GIF)
    }

    private fun selectStickersTab() {
        if (viewPager.currentItem != 1) {
            viewPager.setCurrentItem(1, false)
        }
        onTabSelected(KlipyHistoryDao.TYPE_STICKER)
    }

    private fun normalizeTab(tab: String): String {
        return when (tab.trim().uppercase()) {
            KlipyHistoryDao.TYPE_STICKER, "STICKERS" -> KlipyHistoryDao.TYPE_STICKER
            else -> KlipyHistoryDao.TYPE_GIF
        }
    }

    private fun tabPosition(tab: String): Int {
        return if (tab == KlipyHistoryDao.TYPE_STICKER) 1 else 0
    }

    private fun updateTabSelection(tab: String) {
        findViewById<TextView>(R.id.tabGifs)?.isSelected = (tab == KlipyHistoryDao.TYPE_GIF)
        findViewById<TextView>(R.id.tabStickers)?.isSelected = (tab == KlipyHistoryDao.TYPE_STICKER)
    }

    private fun onTabSelected(tab: String) {
        val activeAdapter = if (tab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
        val shouldLoad = currentTab != tab || activeAdapter.itemCount == 0
        dismissHistoryActionPopup()
        hideClearHistoryConfirmation()
        currentTab = tab
        updateTabSelection(tab)
        updateClearHistoryButton()
        if (shouldLoad) {
            loadHistory()
        }
    }

    private fun loadHistory() {
        val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
        val activePinnedAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) pinnedGifsAdapter else pinnedStickersAdapter

        if (isSearchActive) {
            setHistorySectionsVisible(currentTab, showPinned = false, showHistoryHeading = false)
            val searchResults = if (currentTab == KlipyHistoryDao.TYPE_GIF) lastGifSearch else lastStickerSearch
            if (searchResults.isNotEmpty()) {
                activeAdapter.updateItems(searchResults.toList())
                emptyState.visibility = View.GONE
                return
            } else if (searchQuery.isNotEmpty()) {
                performSearch(searchQuery)
                return
            }
        }

        val pinnedHistory = historyDao.getPinnedHistory(currentTab)
        val history = historyDao.getRecentHistory(currentTab)
        activePinnedAdapter.updateItems(pinnedHistory)
        activeAdapter.updateItems(history)
        setHistorySectionsVisible(
            currentTab,
            showPinned = pinnedHistory.isNotEmpty(),
            showHistoryHeading = history.isNotEmpty()
        )
        emptyState.text = context.getString(R.string.no_recent_items)
        emptyState.visibility = if (pinnedHistory.isEmpty() && history.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun setHistorySectionsVisible(tab: String, showPinned: Boolean, showHistoryHeading: Boolean) {
        val page = if (tab == KlipyHistoryDao.TYPE_GIF) gifsPage else stickersPage
        page.pinnedHeadingAdapter.setVisible(showPinned)
        page.pinnedRowAdapter.setVisible(showPinned)
        page.historyHeadingAdapter.setVisible(showHistoryHeading)
        page.historyHintAdapter.setVisible(
            showHistoryHeading && !context.prefs().getBoolean(PREF_KLIPY_HISTORY_LONG_PRESS_HINT_DISMISSED, false)
        )
    }

    private fun performSearch(query: String) {
        if (query.isBlank()) {
            isSearchActive = false
            setSearchText("", 0)
            lastGifSearch.clear()
            lastStickerSearch.clear()
            loadHistory()
            return
        }

        isSearchActive = true
        searchQuery = query
        val tabAtRequest = currentTab
        updateSearchQueryUI()
        currentPage = 1
        hasMorePages = true

        if (query.isNotBlank()) {
            saveRecentSearch(query)
        }

        if (!CloudManager.isFeatureAllowed(context, CloudManager.CloudFeature.KLIPY_MEDIA)) {
            loadingIndicator.visibility = View.GONE
            val activeAdapter = if (tabAtRequest == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
            activeAdapter.updateItems(emptyList())
            setHistorySectionsVisible(tabAtRequest, showPinned = false, showHistoryHeading = false)
            emptyState.text = "Cloud features are disabled. Please enable them in settings to search."
            emptyState.visibility = View.VISIBLE
            return
        }

        loadingIndicator.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        searchJob?.cancel()
        searchJob = viewScope.launch {
            val (results, hasMore) = withContext(Dispatchers.IO) {
                fetchSearchResults(query, 1, tabAtRequest)
            }

            if (tabAtRequest == KlipyHistoryDao.TYPE_GIF) {
                lastGifSearch.clear()
                lastGifSearch.addAll(results)
            } else {
                lastStickerSearch.clear()
                lastStickerSearch.addAll(results)
            }

            hasMorePages = hasMore

            if (currentTab != tabAtRequest) return@launch

            loadingIndicator.visibility = View.GONE
            val activeAdapter = if (tabAtRequest == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
            activeAdapter.updateItems(results)
            setHistorySectionsVisible(tabAtRequest, showPinned = false, showHistoryHeading = false)

            val activeRecyclerView = if (tabAtRequest == KlipyHistoryDao.TYPE_GIF) gifsRecyclerView else stickersRecyclerView
            activeRecyclerView.scrollToPosition(0)

            if (results.isEmpty()) {
                emptyState.text = context.getString(R.string.no_results_found)
                emptyState.visibility = View.VISIBLE
            } else {
                emptyState.visibility = View.GONE
            }
        }
    }

    private fun fetchSearchResults(query: String, page: Int = 1, tab: String = currentTab): Pair<List<KlipyItem>, Boolean> {
        if (!CloudManager.isFeatureAllowed(context, CloudManager.CloudFeature.KLIPY_MEDIA)) {
            return Pair(emptyList(), false)
        }
        val apiKey = CloudManager.getKlipyApiKey(context)
        val endpoint = if (tab == KlipyHistoryDao.TYPE_GIF) "gifs" else "stickers"

        val encodedQuery = try {
            java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        } catch (e: Exception) {
            query
        }

        val customerId = cachedCustomerId ?: run {
            val id = context.prefs().getString("klipy_customer_id", null) ?: run {
                val newId = UUID.randomUUID().toString()
                context.prefs().edit().putString("klipy_customer_id", newId).apply()
                newId
            }
            cachedCustomerId = id
            id
        }

        val locale = ConfigurationCompat.getLocales(resources.configuration)[0]?.country?.lowercase() ?: "us"

        val url = "https://api.klipy.com/api/v1/$apiKey/$endpoint/search".toHttpUrlOrNull()?.newBuilder()
            ?.addEncodedQueryParameter("q", encodedQuery)
            ?.addQueryParameter("page", page.toString())
            ?.addQueryParameter("per_page", "24")
            ?.addQueryParameter("customer_id", customerId)
            ?.addQueryParameter("locale", locale)
            ?.addQueryParameter("content_filter", "medium")
            ?.addQueryParameter("format_filter", "webp,gif,png")
            ?.build() ?: return Pair(emptyList(), false)

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .build()

        val client = CloudManager.client
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("KlipyPalettesView", "Search request failed with code: ${response.code}")
                    return Pair(emptyList(), false)
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    Log.e("KlipyPalettesView", "Empty response body")
                    return Pair(emptyList(), false)
                }

                val apiResponse = try {
                    json.decodeFromString<KlipySearchResponse>(body)
                } catch (e: Exception) {
                    Log.e("KlipyPalettesView", "Failed to parse Klipy response: ${e.message}")
                    return Pair(emptyList(), false)
                }

                if (!apiResponse.result) {
                    Log.w("KlipyPalettesView", "API returned result=false")
                    return Pair(emptyList(), false)
                }

                val rawList = apiResponse.data.data
                if (rawList.isEmpty()) {
                    Log.i("KlipyPalettesView", "API returned no results for query: $query")
                    return Pair(emptyList(), false)
                }

                if (rawList.isNotEmpty()) {
                    Log.d("KlipyPalettesView", "Raw JSON sample: ${body.substringBefore("\"data\":[")}\"data\":[${body.substringAfter("\"data\":[").take(1000)}")
                }

                val hasMore = rawList.size >= 24
                val sendAsGif = tab == KlipyHistoryDao.TYPE_GIF && !shouldSendGifsAsStickers()

                val items = rawList.mapNotNull { item ->
                    val gifPreviewUrl = item.file.sm?.gif?.url?.takeIf { it.isNotBlank() }
                        ?: item.file.hd.gif.url.takeIf { it.isNotBlank() }
                    val webpPreviewUrl = item.file.sm?.webp?.url?.takeIf { it.isNotBlank() }
                        ?: item.file.hd.webp?.url?.takeIf { it.isNotBlank() }
                    val previewUrl = gifPreviewUrl ?: webpPreviewUrl

                    val hdUrl = if (sendAsGif) {
                        item.file.hd.gif.url.takeIf { it.isNotBlank() }
                    } else {
                        item.file.hd.webp?.url?.takeIf { it.isNotBlank() }
                    }

                    if (hdUrl == null) {
                        Log.d("KlipyPalettesView", "Skipped item ${item.id} - No sendable HD URL available")
                        return@mapNotNull null
                    }

                    val resolvedWidth = item.width
                        ?: item.file.hd.webp?.width
                        ?: item.file.hd.gif.width
                        ?: item.file.sm?.webp?.width
                        ?: item.file.sm?.gif?.width
                        ?: 200

                    val resolvedHeight = item.height
                        ?: item.file.hd.webp?.height
                        ?: item.file.hd.gif.height
                        ?: item.file.sm?.webp?.height
                        ?: item.file.sm?.gif?.height
                        ?: 200

                    KlipyItem(
                        id = item.id.toString(),
                        url = hdUrl,
                        width = resolvedWidth,
                        height = resolvedHeight,
                        previewUrl = previewUrl
                    )
                }
                Pair(items, hasMore)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("KlipyPalettesView", "Search request failed: ${e.message}", e)
            Pair(emptyList(), false)
        }
    }

    private fun shouldSendGifsAsStickers(): Boolean {
        return context.prefs().getBoolean(Settings.PREF_SEND_GIFS_AS_STICKERS, Defaults.PREF_SEND_GIFS_AS_STICKERS)
    }

    private fun setupClickListeners() {
        setFeedbackClickListener(findViewById(R.id.tabGifs)) {
            selectGifsTab()
        }
        setFeedbackClickListener(findViewById(R.id.tabStickers)) {
            selectStickersTab()
        }
        installSearchTapTarget(findViewById(R.id.dummySearchBox), moveCursorToEnd = true)
        setFeedbackClickListener(findViewById(R.id.clearSearchButton)) {
            setSearchText("", 0)
            if (isSearchMode) {
                focusSearchEditText(moveCursorToEnd = false)
            } else {
                performSearch("")
            }
        }
        setFeedbackClickListener(findViewById(R.id.clearKlipyHistoryButton)) {
            showClearHistoryConfirmation()
        }
        findViewById<View>(R.id.clearHistoryConfirmOverlay)?.setOnClickListener {
            hideClearHistoryConfirmation()
        }
        setFeedbackClickListener(findViewById(R.id.cancelClearHistoryButton)) {
            hideClearHistoryConfirmation()
        }
        setFeedbackClickListener(findViewById(R.id.confirmClearHistoryButton)) {
            hideClearHistoryConfirmation()
            clearCurrentHistory()
        }
        setFeedbackClickListener(findViewById(R.id.klipyBackButton)) {
            if (!handleBackPress()) {
                KeyboardSwitcher.getInstance().setAlphabetKeyboard()
            }
        }
        setFeedbackClickListener(findViewById(R.id.klipySearchBackButton)) {
            handleBackPress()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installSearchTapTarget(view: View?, moveCursorToEnd: Boolean) {
        if (view == null) return
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isSearchMode) {
                        performTapFeedback(touchedView)
                        enterSearchMode(moveCursorToEnd)
                        return@setOnTouchListener true
                    }
                    if (touchedView.isEnabled) {
                        touchedView.animate().cancel()
                        touchedView.animate()
                            .scaleX(TAP_SCALE)
                            .scaleY(TAP_SCALE)
                            .alpha(TAP_ALPHA)
                            .setDuration(TAP_PRESS_DURATION_MS)
                            .start()
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> {
                    touchedView.animate().cancel()
                    touchedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(TAP_RELEASE_DURATION_MS)
                        .start()
                }
            }
            false
        }
        view.setOnClickListener { clickedView ->
            performTapFeedback(clickedView)
            if (!isSearchMode) {
                enterSearchMode(moveCursorToEnd)
            } else {
                focusSearchEditText(moveCursorToEnd)
                updateSearchQueryUI()
            }
        }
    }

    private fun setFeedbackClickListener(view: View?, onClick: (View) -> Unit) {
        if (view == null) return
        installTapAnimation(view)
        view.setOnClickListener { clickedView ->
            performTapFeedback(clickedView)
            onClick(clickedView)
        }
    }

    private fun performTapFeedback(view: View) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
            KeyCode.NOT_SPECIFIED,
            view,
            HapticEvent.KEY_PRESS
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun installTapAnimation(view: View) {
        view.setOnTouchListener { touchedView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (touchedView.isEnabled) {
                        touchedView.animate().cancel()
                        touchedView.animate()
                            .scaleX(TAP_SCALE)
                            .scaleY(TAP_SCALE)
                            .alpha(TAP_ALPHA)
                            .setDuration(TAP_PRESS_DURATION_MS)
                            .start()
                    }
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_OUTSIDE -> {
                    touchedView.animate().cancel()
                    touchedView.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(TAP_RELEASE_DURATION_MS)
                        .start()
                }
            }
            false
        }
    }

    private fun applyTheme() {
        try {
            val colors = Settings.getValues().mColors
            val header = findViewById<View>(R.id.klipyHeader)
            if (header != null) {
                colors.setBackground(header, ColorType.STRIP_BACKGROUND)
            }
            val backButton = findViewById<ImageButton>(R.id.klipyBackButton)
            if (backButton != null) {
                colors.setColor(backButton, ColorType.FUNCTIONAL_KEY_TEXT)
                backButton.background = createBackButtonBackground(colors)
                backButton.setPadding(0, 0, 0, 0)
                backButton.scaleType = ImageView.ScaleType.CENTER
            }
            val searchBackButton = findViewById<ImageButton>(R.id.klipySearchBackButton)
            if (searchBackButton != null) {
                colors.setColor(searchBackButton, ColorType.FUNCTIONAL_KEY_TEXT)
                searchBackButton.background = createBackButtonBackground(colors)
                searchBackButton.setPadding(0, 0, 0, 0)
                searchBackButton.scaleType = ImageView.ScaleType.CENTER
            }
            val tabGifs = findViewById<TextView>(R.id.tabGifs)
            if (tabGifs != null) {
                val toolBarColor = colors.get(ColorType.TOOL_BAR_KEY)
                tabGifs.setTextColor(toolBarColor)
                val drawables = tabGifs.compoundDrawablesRelative
                drawables[0]?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it.mutate(), toolBarColor) }
                KeyboardTypeface.applyToTextView(tabGifs)
            }
            val tabStickers = findViewById<TextView>(R.id.tabStickers)
            if (tabStickers != null) {
                val toolBarColor = colors.get(ColorType.TOOL_BAR_KEY)
                tabStickers.setTextColor(toolBarColor)
                val drawables = tabStickers.compoundDrawablesRelative
                drawables[0]?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it.mutate(), toolBarColor) }
                KeyboardTypeface.applyToTextView(tabStickers)
            }

            // Theme search bar elements
            val searchBarContainer = findViewById<View>(R.id.searchBarContainer)
            if (searchBarContainer != null) {
                colors.setBackground(searchBarContainer, ColorType.STRIP_BACKGROUND)
            }
            val dummySearchTextView = findViewById<EditText>(R.id.dummySearchTextView)
            if (dummySearchTextView != null) {
                val toolBarColor = colors.get(ColorType.TOOL_BAR_KEY)
                dummySearchTextView.setTextColor(toolBarColor)
                dummySearchTextView.setHintTextColor((toolBarColor and 0x00FFFFFF) or 0x80000000.toInt())
                KeyboardTypeface.applyToTextView(dummySearchTextView)
            }
            val searchIcon = findViewById<ImageView>(R.id.searchIcon)
            if (searchIcon != null) {
                colors.setColor(searchIcon, ColorType.TOOL_BAR_KEY)
            }
            val clearSearchButton = findViewById<ImageButton>(R.id.clearSearchButton)
            if (clearSearchButton != null) {
                colors.setColor(clearSearchButton, ColorType.TOOL_BAR_KEY)
            }
            val clearHistoryButton = findViewById<TextView>(R.id.clearKlipyHistoryButton)
            if (clearHistoryButton != null) {
                clearHistoryButton.setTextColor(colors.get(ColorType.TOOL_BAR_KEY))
                KeyboardTypeface.applyToTextView(clearHistoryButton)
            }
            val clearHistoryConfirmTitle = findViewById<TextView>(R.id.clearHistoryConfirmTitle)
            if (clearHistoryConfirmTitle != null) {
                clearHistoryConfirmTitle.setTextColor(colors.get(ColorType.TOOL_BAR_KEY))
                KeyboardTypeface.applyToTextView(clearHistoryConfirmTitle)
            }
            val confirmClearHistoryButton = findViewById<TextView>(R.id.confirmClearHistoryButton)
            if (confirmClearHistoryButton != null) {
                confirmClearHistoryButton.setTextColor(colors.get(ColorType.TOOL_BAR_KEY))
                KeyboardTypeface.applyToTextView(confirmClearHistoryButton)
            }
            val cancelClearHistoryButton = findViewById<TextView>(R.id.cancelClearHistoryButton)
            if (cancelClearHistoryButton != null) {
                cancelClearHistoryButton.setTextColor(colors.get(ColorType.TOOL_BAR_KEY))
                KeyboardTypeface.applyToTextView(cancelClearHistoryButton)
            }

            findViewById<TextView>(R.id.emptyState)?.let {
                KeyboardTypeface.applyToTextView(it)
            }

            if (::gifsPage.isInitialized && ::stickersPage.isInitialized) {
                val headingColor = ColorUtils.setAlphaComponent(colors.get(ColorType.TOOL_BAR_KEY), 190)
                listOf(gifsPage, stickersPage).forEach { page ->
                    page.pinnedHeadingAdapter.setTextColor(headingColor)
                    page.historyHeadingAdapter.setTextColor(headingColor)
                    page.historyHintAdapter.setTextColor(ColorUtils.setAlphaComponent(colors.get(ColorType.TOOL_BAR_KEY), 145))
                }
            }

            if (::recentSearchesContainer.isInitialized) {
                val density = resources.displayMetrics.density
                val cornerRadius = 16f * density
                val recentBgColor = colors.get(ColorType.SPECIAL_KEY_BACKGROUND)
                
                val hsl = FloatArray(3)
                ColorUtils.colorToHSL(recentBgColor, hsl)
                hsl[1] = hsl[1] * 0.35f // Make it less saturated (scale saturation down by 65%)
                hsl[2] = hsl[2] * 0.45f // Make it significantly darker (reduce lightness by 55%)
                val desaturatedBgColor = ColorUtils.HSLToColor(hsl)
                val transparentBgColor = ColorUtils.setAlphaComponent(desaturatedBgColor, 140) // Make the card more transparent

                val roundedBg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    this.cornerRadius = cornerRadius
                    setColor(transparentBgColor)
                }
                recentSearchesCard.background = roundedBg

                val toolBarColor = colors.get(ColorType.TOOL_BAR_KEY)

                for (i in 0 until 3) {
                    recentSearchTexts.getOrNull(i)?.setTextColor(toolBarColor)
                    recentSearchTexts.getOrNull(i)?.let { KeyboardTypeface.applyToTextView(it) }
                    
                    recentSearchIcons.getOrNull(i)?.let { icon ->
                        icon.setColorFilter(toolBarColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                    
                    recentSearchArrows.getOrNull(i)?.let { arrow ->
                        val fadedColor = ColorUtils.setAlphaComponent(toolBarColor, 100)
                        arrow.setColorFilter(fadedColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                    
                    val sepColor = ColorUtils.setAlphaComponent(toolBarColor, 40)
                    recentSearchSeparators.getOrNull(i)?.setBackgroundColor(sepColor)
                }
            }
        } catch (e: Exception) {
            Log.e("KlipyPalettesView", "Failed to apply theme", e)
        }
    }

    private fun createBackButtonBackground(colors: Colors): RippleDrawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            colors.setColor(this, ColorType.SPECIAL_KEY_BACKGROUND)
        }
        val rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(colors.get(ColorType.FUNCTIONAL_KEY_TEXT), 0x33)
        )
        val horizontalInset = 3.dpToPx(resources)
        val verticalInset = 3.dpToPx(resources)
        val content = InsetDrawable(circle, horizontalInset, verticalInset, horizontalInset, verticalInset)
        return RippleDrawable(rippleColor, content, content.constantState?.newDrawable()?.mutate())
    }

    private fun showClearHistoryButton() {
        if (!::clearHistoryButton.isInitialized) return
        updateClearHistoryButton()
        clearHistoryButton.visibility = View.VISIBLE
        findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)?.visibility = View.GONE
    }

    private fun updateClearHistoryButton() {
        if (!::clearHistoryButton.isInitialized) return
        clearHistoryButton.text = context.getString(
            if (currentTab == KlipyHistoryDao.TYPE_GIF) {
                R.string.klipy_clear_gif_history
            } else {
                R.string.klipy_clear_sticker_history
            }
        )
    }

    private fun showClearHistoryConfirmation() {
        if (!::clearHistoryConfirmOverlay.isInitialized) return
        dismissHistoryActionPopup()
        clearHistoryConfirmTitle.text = context.getString(
            if (currentTab == KlipyHistoryDao.TYPE_GIF) {
                R.string.klipy_clear_history_confirm_title_gif
            } else {
                R.string.klipy_clear_history_confirm_title_sticker
            }
        )
        clearHistoryConfirmOverlay.visibility = View.VISIBLE
        clearHistoryButton.isEnabled = false
    }

    private fun hideClearHistoryConfirmation() {
        if (!::clearHistoryConfirmOverlay.isInitialized) return
        clearHistoryConfirmOverlay.visibility = View.GONE
        if (::clearHistoryButton.isInitialized) {
            clearHistoryButton.isEnabled = true
        }
    }

    private fun clearCurrentHistory() {
        dismissHistoryActionPopup()
        historyDao.clearHistory(currentTab)
        scheduleKlipyMediaPrune()
        isSearchActive = false
        setSearchText("", 0)
        if (currentTab == KlipyHistoryDao.TYPE_GIF) {
            lastGifSearch.clear()
        } else {
            lastStickerSearch.clear()
        }
        updateSearchQueryUI()
        loadHistory()
    }

    fun handleBackPress(): Boolean {
        if (historyActionPopup != null) {
            dismissHistoryActionPopup()
            return true
        }
        if (isSearchMode) {
            exitSearchMode(triggerSearch = false)
            return true
        } else if (isSearchActive) {
            setSearchText("", 0)
            performSearch("")
            return true
        }
        return false
    }

    private fun swapKeyboardToKlipy(enter: Boolean) {
        val switcher = KeyboardSwitcher.getInstance()
        val keyboardView = switcher.mainKeyboardView ?: return
        val wrapper = switcher.wrapperView as? ViewGroup ?: return
        val placeholder = findViewById<View>(R.id.bottom_row_keyboard) ?: return

        val currentParent = keyboardView.parent as? ViewGroup
        if (enter) {
            if (currentParent != this) {
                currentParent?.removeView(keyboardView)
                val index = indexOfChild(placeholder)
                if (index >= 0) {
                    addView(keyboardView, index)
                } else {
                    addView(keyboardView)
                }
                placeholder.visibility = View.GONE
            }
            keyboardView.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            keyboardView.visibility = View.VISIBLE
            switcher.stripContainer?.visibility = View.GONE
        } else {
            if (currentParent == this) {
                removeView(keyboardView)
                wrapper.addView(keyboardView, 0)
                placeholder.visibility = View.GONE
            }
            keyboardView.visibility = View.GONE
            switcher.stripContainer?.visibility = View.GONE
        }
    }

    private fun enterSearchMode(moveCursorToEnd: Boolean = true) {
        TextCommitDiagnostics.stage("KlipySearch.enter", "moveCursorToEnd=$moveCursorToEnd wasSearchMode=$isSearchMode")
        isSearchMode = true
        dismissHistoryActionPopup()
        pinnedGifsAdapter.setAnimationsRunning(false)
        pinnedStickersAdapter.setAnimationsRunning(false)
        gifsAdapter.setAnimationsRunning(false)
        stickersAdapter.setAnimationsRunning(false)
        findViewById<View>(R.id.klipyHeader)?.visibility = View.GONE
        findViewById<View>(R.id.klipySearchBackButton)?.visibility = View.VISIBLE
        viewPager.visibility = View.GONE
        emptyState.visibility = View.GONE
        hideClearHistoryConfirmation()
        clearHistoryButton.visibility = View.GONE

        swapKeyboardToKlipy(enter = true)
        focusSearchEditText(moveCursorToEnd)
        updateSearchQueryUI()
        updateRecentSearchesUI()
        currentSearchKeyboardElementId = KeyboardId.ELEMENT_ALPHABET
        updateSearchKeyboard()
    }

    private fun exitSearchMode(triggerSearch: Boolean) {
        TextCommitDiagnostics.stage("KlipySearch.exit", "triggerSearch=$triggerSearch wasSearchMode=$isSearchMode")
        syncSearchQueryFromField()
        isSearchMode = false
        if (::searchEditText.isInitialized) {
            searchEditText.isCursorVisible = false
            TextCommitDiagnostics.stage("KlipySearch.clearFocus", "hadFocus=${searchEditText.hasFocus()}")
            searchEditText.clearFocus()
        }
        findViewById<View>(R.id.klipyHeader)?.visibility = View.VISIBLE
        findViewById<View>(R.id.klipySearchBackButton)?.visibility = View.GONE
        viewPager.visibility = View.VISIBLE
        pinnedGifsAdapter.setAnimationsRunning(true)
        pinnedStickersAdapter.setAnimationsRunning(true)
        gifsAdapter.setAnimationsRunning(true)
        stickersAdapter.setAnimationsRunning(true)
        showClearHistoryButton()
        updateSearchQueryUI()
        updateRecentSearchesUI()

        swapKeyboardToKlipy(enter = false)
        KeyboardSwitcher.getInstance().mainKeyboardView?.setKeyboardActionListener(mainListener)
        KeyboardSwitcher.getInstance().logTypingListenerInvariant("KlipySearch.exit", true)

        if (triggerSearch) {
            performSearch(searchQuery)
        } else {
            loadHistory()
        }
    }

    private fun updateSearchKeyboard() {
        val switcher = KeyboardSwitcher.getInstance()
        val keyboardView = switcher.mainKeyboardView ?: return
        TextCommitDiagnostics.stage("KlipySearch.updateSearchKeyboard", "elementId=$currentSearchKeyboardElementId")
        keyboardView.setKeyboardActionListener(searchKeyboardListener)
        PointerTracker.switchTo(keyboardView)
        when (currentSearchKeyboardElementId) {
            KeyboardId.ELEMENT_ALPHABET -> switcher.setAlphabetKeyboard()
            KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED -> switcher.setAlphabetManualShiftedKeyboard()
            KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED -> switcher.setAlphabetShiftLockedKeyboard()
            KeyboardId.ELEMENT_SYMBOLS -> switcher.setSymbolsKeyboard()
            KeyboardId.ELEMENT_SYMBOLS_SHIFTED -> switcher.setSymbolsShiftedKeyboard()
            else -> switcher.setAlphabetKeyboard()
        }
    }

    private fun updateSearchQueryUI() {
        val clearSearchButton = findViewById<View>(R.id.clearSearchButton)
        if (::searchEditText.isInitialized) {
            if (searchEditText.text.toString() != searchQuery) {
                val selection = searchEditText.selectionStart.takeIf { it >= 0 } ?: searchQuery.length
                setSearchText(searchQuery, selection.coerceIn(0, searchQuery.length))
                return
            }
            searchEditText.hint = context.getString(R.string.klipy_search_hint)
            searchEditText.isCursorVisible = isSearchMode && searchEditText.hasFocus()
        }
        clearSearchButton?.visibility = if (searchQuery.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun setSearchText(text: String, selection: Int = text.length) {
        searchQuery = text
        if (::searchEditText.isInitialized) {
            val safeSelection = selection.coerceIn(0, text.length)
            if (searchEditText.text.toString() != text) {
                isUpdatingSearchText = true
                try {
                    searchEditText.text.replace(0, searchEditText.text.length, text)
                } finally {
                    isUpdatingSearchText = false
                }
            }
            if (searchEditText.selectionStart != safeSelection || searchEditText.selectionEnd != safeSelection) {
                searchEditText.setSelection(safeSelection)
            }
        }
        updateSearchQueryUI()
        updateRecentSearchesUI()
    }

    private fun syncSearchQueryFromField() {
        if (::searchEditText.isInitialized) {
            searchQuery = searchEditText.text.toString()
        }
        updateSearchQueryUI()
    }

    private fun focusSearchEditText(moveCursorToEnd: Boolean) {
        if (!::searchEditText.isInitialized) return
        TextCommitDiagnostics.stage("KlipySearch.requestFocus", "moveCursorToEnd=$moveCursorToEnd hadFocus=${searchEditText.hasFocus()}")
        searchEditText.requestFocus()
        searchEditText.isCursorVisible = isSearchMode
        if (moveCursorToEnd) {
            searchEditText.setSelection(searchEditText.text.length)
        }
    }

    private fun searchSelectionRange(): IntRange? {
        if (!::searchEditText.isInitialized) return null
        val selectionStart = searchEditText.selectionStart
        val selectionEnd = searchEditText.selectionEnd
        if (selectionStart < 0 || selectionEnd < 0) return null
        val start = minOf(selectionStart, selectionEnd).coerceIn(0, searchEditText.text.length)
        val end = maxOf(selectionStart, selectionEnd).coerceIn(0, searchEditText.text.length)
        return start..end
    }

    private fun insertSearchText(text: String) {
        if (text.isEmpty()) return
        if (!::searchEditText.isInitialized) {
            setSearchText(searchQuery + text)
            return
        }
        val range = searchSelectionRange() ?: (searchEditText.text.length..searchEditText.text.length)
        searchEditText.text.replace(range.first, range.last, text)
        searchEditText.setSelection(range.first + text.length)
        syncSearchQueryFromField()
    }

    private fun deleteSearchTextBeforeCursor() {
        if (!::searchEditText.isInitialized) return
        val editable = searchEditText.text
        if (editable.isEmpty()) return
        val range = searchSelectionRange() ?: return
        if (range.first != range.last) {
            editable.delete(range.first, range.last)
            searchEditText.setSelection(range.first)
            syncSearchQueryFromField()
            return
        }
        if (range.first <= 0) return
        val deleteFrom = editable.toString().offsetByCodePoints(range.first, -1)
        editable.delete(deleteFrom, range.first)
        searchEditText.setSelection(deleteFrom)
        syncSearchQueryFromField()
    }

    private fun moveSearchCursorByCodePoints(delta: Int) {
        if (delta == 0 || !::searchEditText.isInitialized) return
        val text = searchEditText.text.toString()
        if (text.isEmpty()) return
        val range = searchSelectionRange() ?: return
        val newPosition = if (range.first != range.last) {
            if (delta < 0) range.first else range.last
        } else try {
            text.offsetByCodePoints(range.first, delta)
        } catch (_: IndexOutOfBoundsException) {
            if (delta < 0) 0 else text.length
        }
        searchEditText.setSelection(newPosition.coerceIn(0, text.length))
        updateSearchQueryUI()
    }

    private fun handleSearchCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        when (primaryCode) {
            KeyCode.DELETE -> {
                deleteSearchTextBeforeCursor()
            }
            Constants.CODE_ENTER -> {
                syncSearchQueryFromField()
                exitSearchMode(triggerSearch = true)
            }
            KeyCode.ARROW_LEFT -> moveSearchCursorByCodePoints(-1)
            KeyCode.ARROW_RIGHT -> moveSearchCursorByCodePoints(1)
            KeyCode.MOVE_START_OF_PAGE, KeyCode.PAGE_UP -> {
                if (::searchEditText.isInitialized) {
                    searchEditText.setSelection(0)
                    updateSearchQueryUI()
                }
            }
            KeyCode.MOVE_END_OF_PAGE, KeyCode.PAGE_DOWN -> {
                if (::searchEditText.isInitialized) {
                    searchEditText.setSelection(searchEditText.text.length)
                    updateSearchQueryUI()
                }
            }
            KeyCode.SHIFT -> {
                currentSearchKeyboardElementId = when (currentSearchKeyboardElementId) {
                    KeyboardId.ELEMENT_ALPHABET -> KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED
                    KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED -> KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED
                    KeyboardId.ELEMENT_ALPHABET_SHIFT_LOCKED -> KeyboardId.ELEMENT_ALPHABET
                    KeyboardId.ELEMENT_SYMBOLS -> KeyboardId.ELEMENT_SYMBOLS_SHIFTED
                    KeyboardId.ELEMENT_SYMBOLS_SHIFTED -> KeyboardId.ELEMENT_SYMBOLS
                    else -> KeyboardId.ELEMENT_ALPHABET
                }
                updateSearchKeyboard()
            }
            KeyCode.SYMBOL, KeyCode.SYMBOL_ALPHA -> {
                currentSearchKeyboardElementId = when (currentSearchKeyboardElementId) {
                    KeyboardId.ELEMENT_SYMBOLS, KeyboardId.ELEMENT_SYMBOLS_SHIFTED -> KeyboardId.ELEMENT_ALPHABET
                    else -> KeyboardId.ELEMENT_SYMBOLS
                }
                updateSearchKeyboard()
            }
            KeyCode.ALPHA -> {
                currentSearchKeyboardElementId = KeyboardId.ELEMENT_ALPHABET
                updateSearchKeyboard()
            }
            else -> {
                if (primaryCode >= 32) {
                    insertSearchText(primaryCode.toChar().toString())
                    if (currentSearchKeyboardElementId == KeyboardId.ELEMENT_ALPHABET_MANUAL_SHIFTED) {
                        currentSearchKeyboardElementId = KeyboardId.ELEMENT_ALPHABET
                        updateSearchKeyboard()
                    }
                }
            }
        }
    }

    private val searchKeyboardListener = object : KeyboardActionListener.Adapter() {
        override fun onPressKey(primaryCode: Int, repeatCount: Int, isSinglePointer: Boolean, hapticEvent: HapticEvent?) {
            mainListener?.onPressKey(primaryCode, repeatCount, isSinglePointer, hapticEvent)
        }

        override fun onLongPressKey(primaryCode: Int) {
            mainListener?.onLongPressKey(primaryCode)
        }

        override fun onReleaseKey(primaryCode: Int, withSliding: Boolean) {
            mainListener?.onReleaseKey(primaryCode, withSliding)
        }

        override fun onKeyDown(keyCode: Int, keyEvent: KeyEvent?): Boolean {
            return mainListener?.onKeyDown(keyCode, keyEvent) ?: false
        }

        override fun onKeyUp(keyCode: Int, keyEvent: KeyEvent?): Boolean {
            return mainListener?.onKeyUp(keyCode, keyEvent) ?: false
        }

        override fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
            handleSearchCodeInput(primaryCode, x, y, isKeyRepeat)
        }

        override fun onTextInput(text: String?) {
            if (text != null) {
                insertSearchText(text)
            }
        }

        override fun onStartBatchInput() {
            if (::searchEditText.isInitialized) {
                searchEditText.isCursorVisible = true
            }
            updateSearchQueryUI()
        }

        override fun onEndBatchInput(batchPointers: InputPointers?) {
            if (batchPointers == null) return
            val keyboard = KeyboardSwitcher.getInstance().mainKeyboardView?.keyboard
            getLatinIME()?.getKlipySearchGestureSuggestion(
                batchPointers,
                keyboard,
                object : Suggest.OnGetSuggestedWordsCallback {
                    override fun onGetSuggestedWords(suggestedWords: SuggestedWords?) {
                        val gesturedWord = suggestedWords
                            ?.takeUnless { it.isEmpty() }
                            ?.getWord(0)
                            ?.takeIf { it.isNotBlank() }
                        if (gesturedWord != null) {
                            post { insertSearchText(gesturedWord) }
                        }
                    }
                }
            )
        }

        override fun onMoveDeletePointer(steps: Int) {
            if (!isSearchMode || !::searchEditText.isInitialized) return
            val text = searchEditText.text ?: return
            val cursorEnd = searchEditText.selectionEnd.coerceIn(0, text.length)
            val cursorStart = searchEditText.selectionStart.coerceIn(0, text.length)
            // On first swipe step, anchor the end at the cursor position and move start left
            val newStart = (cursorStart + steps).coerceIn(0, text.length)
            if (newStart > cursorEnd) return
            searchEditText.setSelection(newStart, cursorEnd)
        }

        override fun onUpWithDeletePointerActive() {
            if (!isSearchMode || !::searchEditText.isInitialized) return
            val selStart = searchEditText.selectionStart
            val selEnd = searchEditText.selectionEnd
            if (selStart < 0 || selEnd < 0 || selStart >= selEnd) return
            searchEditText.text.delete(selStart, selEnd)
            searchEditText.setSelection(selStart)
            syncSearchQueryFromField()
        }

        override fun onCancelBatchInput() {
            updateSearchQueryUI()
        }

        override fun onHorizontalSpaceSwipe(steps: Int): Boolean {
            if (!isSearchMode || !::searchEditText.isInitialized) return false
            moveSearchCursorByCodePoints(steps)
            return true
        }

        override fun onVerticalSpaceSwipe(steps: Int): Boolean {
            // No vertical cursor movement in a single-line search field
            return false
        }

        override fun onEndSpaceSwipe() {
            // Nothing to finalize
        }
    }

    private data class KlipyTabPage(
        val root: LinearLayout,
        val historyRecyclerView: RecyclerView,
        val pinnedAdapter: KlipyAdapter,
        val historyAdapter: KlipyAdapter,
        val pinnedHeadingAdapter: KlipySectionAdapter,
        val pinnedRowAdapter: KlipyPinnedRowAdapter,
        val historyHeadingAdapter: KlipySectionAdapter,
        val historyHintAdapter: KlipySectionAdapter
    )

    private class KlipySectionAdapter(
        private val context: Context,
        private val text: String,
        private val isHint: Boolean
    ) : RecyclerView.Adapter<KlipySectionAdapter.ViewHolder>() {
        private var isVisible = false
        private var textColor = Color.WHITE

        init {
            setHasStableIds(true)
        }

        class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun getItemId(position: Int): Long = text.hashCode().toLong()

        fun setVisible(visible: Boolean) {
            if (isVisible == visible) return
            isVisible = visible
            if (visible) {
                notifyItemInserted(0)
            } else {
                notifyItemRemoved(0)
            }
        }

        fun setTextColor(color: Int) {
            textColor = color
            if (isVisible) notifyItemChanged(0)
        }

        override fun getItemCount(): Int = if (isVisible) 1 else 0

        override fun getItemViewType(position: Int): Int {
            return if (isHint) VIEW_TYPE_HISTORY_HINT else VIEW_TYPE_SECTION_HEADING
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val resources = parent.resources
            val textView = TextView(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                includeFontPadding = false
                setPadding(
                    10.dpToPx(resources),
                    if (isHint) 0 else 8.dpToPx(resources),
                    6.dpToPx(resources),
                    if (isHint) 6.dpToPx(resources) else 4.dpToPx(resources)
                )
                KeyboardTypeface.applyToTextView(this)
            }
            return ViewHolder(textView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            markFullSpan(holder.itemView)
            holder.textView.text = text
            holder.textView.setTextColor(textColor)
        }
    }

    private class KlipyPinnedRowAdapter(
        private val context: Context,
        private val pinnedAdapter: KlipyAdapter,
        private val spanCount: Int,
        private val contentPadding: Int
    ) : RecyclerView.Adapter<KlipyPinnedRowAdapter.ViewHolder>() {
        private var isVisible = false

        init {
            setHasStableIds(true)
        }

        class ViewHolder(val recyclerView: RecyclerView) : RecyclerView.ViewHolder(recyclerView)

        override fun getItemId(position: Int): Long = VIEW_TYPE_PINNED_ROW.toLong()

        fun setVisible(visible: Boolean) {
            if (isVisible == visible) return
            isVisible = visible
            if (visible) {
                notifyItemInserted(0)
            } else {
                notifyItemRemoved(0)
            }
        }

        override fun getItemCount(): Int = if (isVisible) 1 else 0

        override fun getItemViewType(position: Int): Int = VIEW_TYPE_PINNED_ROW

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val recyclerView = RecyclerView(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0, 0, 0, contentPadding)
                clipToPadding = false
                isNestedScrollingEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutManager = GridLayoutManager(context, spanCount)
                adapter = pinnedAdapter
                itemAnimator = null
                setHasFixedSize(true)
            }
            return ViewHolder(recyclerView)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            markFullSpan(holder.itemView)
        }
    }

    private class KlipyViewPagerAdapter(
        private val gifsView: View,
        private val stickersView: View
    ) : RecyclerView.Adapter<KlipyViewPagerAdapter.ViewHolder>() {
        class ViewHolder(val view: View) : RecyclerView.ViewHolder(view)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = if (viewType == 0) gifsView else stickersView
            (view.parent as? ViewGroup)?.removeView(view)
            return ViewHolder(view)
        }
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {}
        override fun getItemViewType(position: Int): Int = position
        override fun getItemCount(): Int = 2
    }

    @Serializable
    private data class KlipySearchResponse(val result: Boolean, val data: KlipyDataContainer)

    @Serializable
    private data class KlipyDataContainer(val data: List<KlipyApiItem>)

    @Serializable
    private data class KlipyApiItem(
        val id: Long,
        val slug: String? = null,
        val title: String? = null,
        val file: KlipyFileInfo,
        val width: Int? = null,
        val height: Int? = null
    )

    @Serializable
    private data class KlipyFileInfo(val hd: KlipyHdInfo, val sm: KlipySmInfo? = null)

    @Serializable
    private data class KlipyHdInfo(val gif: KlipyUrlInfo, val webp: KlipyUrlInfo? = null)

    @Serializable
    private data class KlipySmInfo(val gif: KlipyUrlInfo, val webp: KlipyUrlInfo? = null)

    @Serializable
    private data class KlipyUrlInfo(
        val url: String,
        val width: Int? = null,
        val height: Int? = null
    )
}
