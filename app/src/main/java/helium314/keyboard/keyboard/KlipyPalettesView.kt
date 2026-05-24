// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
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
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.os.ConfigurationCompat
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
    private lateinit var viewPager: ViewPager2
    private lateinit var emptyState: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var clearHistoryButton: TextView
    private lateinit var clearHistoryConfirmOverlay: View
    private lateinit var clearHistoryConfirmTitle: TextView
    private lateinit var searchEditText: EditText
    private lateinit var gifsAdapter: KlipyAdapter
    private lateinit var stickersAdapter: KlipyAdapter
    private lateinit var historyDao: KlipyHistoryDao

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
        gifsAdapter.resumeVisibleResources()
        stickersAdapter.resumeVisibleResources()
        showClearHistoryButton()
        viewPager.setCurrentItem(tabPosition(currentTab), false)
        updateTabSelection(currentTab)

        loadHistory()
    }

    fun stopKlipyPalettes() {
        searchJob?.cancel()
        searchJob = null
        inFlightStickerJobs.values.forEach { it.cancel() }
        inFlightStickerJobs.clear()
        viewScope.coroutineContext.cancelChildren()
        isLoadingMore = false
        activeSendJobs = 0
        if (isInitialized) {
            loadingIndicator.visibility = View.GONE
            hideClearHistoryConfirmation()
            gifsAdapter.releaseVisibleResources()
            stickersAdapter.releaseVisibleResources()
            if (isSearchMode) {
                exitSearchMode(triggerSearch = false)
            }
        }
        mainListener?.let {
            PointerTracker.setKeyboardActionListener(it)
        }
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

        val padding = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 4f, resources.displayMetrics
        ).toInt()

        gifsRecyclerView = RecyclerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(padding, padding, padding, padding)
            clipToPadding = false
            isVerticalScrollBarEnabled = true
        }

        stickersRecyclerView = RecyclerView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(padding, padding, padding, padding)
            clipToPadding = false
            isVerticalScrollBarEnabled = true
        }

        viewPager.adapter = KlipyViewPagerAdapter(gifsRecyclerView, stickersRecyclerView)
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

    private fun setupRecyclerViews() {
        gifsAdapter = KlipyAdapter(emptyList()) { item -> onItemUsed(item) }
        val staggeredLayoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL).apply {
            gapStrategy = StaggeredGridLayoutManager.GAP_HANDLING_NONE
        }
        gifsRecyclerView.layoutManager = staggeredLayoutManager
        gifsRecyclerView.adapter = gifsAdapter
        gifsRecyclerView.setItemViewCacheSize(2)
        gifsRecyclerView.setHasFixedSize(true)
        gifsRecyclerView.addOnScrollListener(createPaginationScrollListener())

        stickersAdapter = KlipyAdapter(emptyList()) { item -> onItemUsed(item) }
        stickersRecyclerView.layoutManager = GridLayoutManager(context, 4)
        stickersRecyclerView.adapter = stickersAdapter
        stickersRecyclerView.setItemViewCacheSize(2)
        stickersRecyclerView.setHasFixedSize(true)
        stickersRecyclerView.addOnScrollListener(createPaginationScrollListener())
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
            installTapAnimation(this)
            setOnClickListener { clickedView ->
                performTapFeedback(clickedView)
                if (!isSearchMode) {
                    enterSearchMode(moveCursorToEnd = false)
                } else {
                    updateSearchQueryUI()
                }
            }
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
                val adapter = recyclerView.adapter as? KlipyAdapter ?: return
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    adapter.setAnimationsRunning(true)
                } else {
                    adapter.setAnimationsRunning(false)
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
        isLoadingMore = true
        currentPage++

        viewScope.launch {
            try {
                val (results, hasMore) = withContext(Dispatchers.IO) {
                    fetchSearchResults(searchQuery, currentPage)
                }

                hasMorePages = hasMore

                if (results.isNotEmpty()) {
                    val currentList = if (currentTab == KlipyHistoryDao.TYPE_GIF) lastGifSearch else lastStickerSearch
                    currentList.addAll(results)
                    val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
                    activeAdapter.updateItems(currentList.toList())
                }
            } finally {
                isLoadingMore = false
            }
        }
    }

    private fun onItemUsed(item: KlipyItem) {
        historyDao.addHistory(item.id, item.url, currentTab, item.width, item.height, item.previewUrl)

        viewScope.launch {
            showLoading()
            try {
                val isSticker = currentTab == KlipyHistoryDao.TYPE_STICKER
                val sendGifAsSticker = currentTab == KlipyHistoryDao.TYPE_GIF && shouldSendGifsAsStickers()
                val sendAsSticker = isSticker || sendGifAsSticker

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
        onTabSelected(KlipyHistoryDao.TYPE_GIF)
        if (viewPager.currentItem != 0) {
            viewPager.setCurrentItem(0, true)
        }
    }

    private fun selectStickersTab() {
        onTabSelected(KlipyHistoryDao.TYPE_STICKER)
        if (viewPager.currentItem != 1) {
            viewPager.setCurrentItem(1, true)
        }
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

        if (isSearchActive) {
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

        val history = historyDao.getHistory(currentTab)
        activeAdapter.updateItems(history)
        emptyState.text = context.getString(R.string.no_recent_items)
        emptyState.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
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
        updateSearchQueryUI()
        currentPage = 1
        hasMorePages = true

        loadingIndicator.visibility = View.VISIBLE
        emptyState.visibility = View.GONE

        searchJob?.cancel()
        searchJob = viewScope.launch {
            val (results, hasMore) = withContext(Dispatchers.IO) {
                fetchSearchResults(query, 1)
            }

            if (currentTab == KlipyHistoryDao.TYPE_GIF) {
                lastGifSearch.clear()
                lastGifSearch.addAll(results)
            } else {
                lastStickerSearch.clear()
                lastStickerSearch.addAll(results)
            }

            hasMorePages = hasMore

            loadingIndicator.visibility = View.GONE
            val activeAdapter = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsAdapter else stickersAdapter
            activeAdapter.updateItems(results)

            val activeRecyclerView = if (currentTab == KlipyHistoryDao.TYPE_GIF) gifsRecyclerView else stickersRecyclerView
            activeRecyclerView.scrollToPosition(0)

            if (results.isEmpty()) {
                emptyState.text = context.getString(R.string.no_results_found)
                emptyState.visibility = View.VISIBLE
            } else {
                emptyState.visibility = View.GONE
            }
        }
    }

    private fun fetchSearchResults(query: String, page: Int = 1): Pair<List<KlipyItem>, Boolean> {
        val apiKey = CloudManager.getKlipyApiKey(context)
        val endpoint = if (currentTab == KlipyHistoryDao.TYPE_GIF) "gifs" else "stickers"

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

                val hasMore = rawList.size >= 24
                val sendAsGif = currentTab == KlipyHistoryDao.TYPE_GIF && !shouldSendGifsAsStickers()

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

                    KlipyItem(
                        id = item.id.toString(),
                        url = hdUrl,
                        width = item.width ?: 200,
                        height = item.height ?: 200,
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
        setFeedbackClickListener(findViewById(R.id.dummySearchBox)) {
            if (!isSearchMode) {
                enterSearchMode(moveCursorToEnd = true)
            } else {
                focusSearchEditText(moveCursorToEnd = true)
            }
        }
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
            if (isSearchMode) {
                exitSearchMode(triggerSearch = false)
            } else if (isSearchActive) {
                setSearchText("", 0)
                performSearch("")
            } else {
                keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
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
        historyDao.clearHistory(currentTab)
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

    private fun enterSearchMode(moveCursorToEnd: Boolean = true) {
        isSearchMode = true
        gifsAdapter.setAnimationsRunning(false)
        stickersAdapter.setAnimationsRunning(false)
        findViewById<View>(R.id.klipyHeader)?.visibility = View.GONE
        viewPager.visibility = View.GONE
        emptyState.visibility = View.GONE
        hideClearHistoryConfirmation()
        clearHistoryButton.visibility = View.GONE
        findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)?.visibility = View.VISIBLE
        focusSearchEditText(moveCursorToEnd)
        updateSearchQueryUI()
        currentSearchKeyboardElementId = KeyboardId.ELEMENT_ALPHABET
        updateSearchKeyboard()
    }

    private fun exitSearchMode(triggerSearch: Boolean) {
        syncSearchQueryFromField()
        isSearchMode = false
        if (::searchEditText.isInitialized) {
            searchEditText.isCursorVisible = false
            searchEditText.clearFocus()
        }
        findViewById<View>(R.id.klipyHeader)?.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE
        gifsAdapter.setAnimationsRunning(true)
        stickersAdapter.setAnimationsRunning(true)
        showClearHistoryButton()
        updateSearchQueryUI()

        mainListener?.let {
            PointerTracker.setKeyboardActionListener(it)
        }

        if (triggerSearch) {
            performSearch(searchQuery)
        } else {
            loadHistory()
        }
    }

    private fun updateSearchKeyboard() {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(searchKeyboardListener)
        PointerTracker.switchTo(keyboardView)
        val kls = keyboardLayoutSet ?: KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, mainEditorInfo).also {
            keyboardLayoutSet = it
        }
        val keyboard = kls.getKeyboard(currentSearchKeyboardElementId)
        keyboardView.setKeyboard(keyboard)
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
    }

    private fun syncSearchQueryFromField() {
        if (::searchEditText.isInitialized) {
            searchQuery = searchEditText.text.toString()
        }
        updateSearchQueryUI()
    }

    private fun focusSearchEditText(moveCursorToEnd: Boolean) {
        if (!::searchEditText.isInitialized) return
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
            KeyCode.SYMBOL -> {
                currentSearchKeyboardElementId = KeyboardId.ELEMENT_SYMBOLS
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
            val keyboard = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)?.keyboard
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

        override fun onCancelBatchInput() {
            updateSearchQueryUI()
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
    private data class KlipyUrlInfo(val url: String)
}
