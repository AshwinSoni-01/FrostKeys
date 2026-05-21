// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.KlipyHistoryDao
import helium314.keyboard.latin.database.KlipyHistoryDao.KlipyItem
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.stickers.AnimatedStickerProcessor
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ResourceUtils
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
    private lateinit var gifsAdapter: KlipyAdapter
    private lateinit var stickersAdapter: KlipyAdapter
    private lateinit var historyDao: KlipyHistoryDao

    private var currentTab = KlipyHistoryDao.TYPE_GIF
    private var isSearchActive = false
    private var lastGifSearch: MutableList<KlipyItem> = mutableListOf()
    private var lastStickerSearch: MutableList<KlipyItem> = mutableListOf()
    private var searchQuery = ""
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

        private fun createStickerProcessorDispatcher(): ExecutorCoroutineDispatcher {
            return Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "KlipyStickerProcessor").apply { isDaemon = true }
            }.asCoroutineDispatcher()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val settings = Settings.getValues()
        val abcHeight = ResourceUtils.getKeyboardHeight(resources, settings)
        val persistentEmojiEnabled = context.prefs().getBoolean(Settings.PREF_PERSISTENT_EMOJI_ROW, Defaults.PREF_PERSISTENT_EMOJI_ROW)
        val emojiRowHeight = if (persistentEmojiEnabled) (41 * resources.displayMetrics.density).toInt() else 0
        val finalHeight = abcHeight + emojiRowHeight + paddingTop + paddingBottom
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
        initialize()

        // Reset search states when opening
        isSearchMode = false
        isSearchActive = false
        searchQuery = ""
        lastGifSearch.clear()
        lastStickerSearch.clear()
        updateSearchQueryUI()

        findViewById<View>(R.id.klipyHeader)?.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE
        gifsAdapter.resumeVisibleResources()
        stickersAdapter.resumeVisibleResources()

        setupBottomRowKeyboard(editorInfo, keyboardActionListener)
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
            gifsAdapter.releaseVisibleResources()
            stickersAdapter.releaseVisibleResources()
        }
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
        historyDao.addHistory(item.id, item.url, currentTab, item.width, item.height)

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
        if (tab == KlipyHistoryDao.TYPE_STICKER) {
            selectStickersTab()
        } else {
            selectGifsTab()
        }
    }

    private fun selectGifsTab() {
        if (viewPager.currentItem != 0) {
            viewPager.setCurrentItem(0, true)
        } else {
            onTabSelected(KlipyHistoryDao.TYPE_GIF)
        }
    }

    private fun selectStickersTab() {
        if (viewPager.currentItem != 1) {
            viewPager.setCurrentItem(1, true)
        } else {
            onTabSelected(KlipyHistoryDao.TYPE_STICKER)
        }
    }

    private fun onTabSelected(tab: String) {
        if (currentTab != tab || gifsAdapter.itemCount == 0 || stickersAdapter.itemCount == 0) {
            currentTab = tab
            findViewById<TextView>(R.id.tabGifs)?.isSelected = (tab == KlipyHistoryDao.TYPE_GIF)
            findViewById<TextView>(R.id.tabStickers)?.isSelected = (tab == KlipyHistoryDao.TYPE_STICKER)
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
            searchQuery = ""
            lastGifSearch.clear()
            lastStickerSearch.clear()
            loadHistory()
            return
        }

        isSearchActive = true
        searchQuery = query
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
                val useGifUrl = currentTab == KlipyHistoryDao.TYPE_GIF && !shouldSendGifsAsStickers()

                val items = rawList.mapNotNull { item ->
                    val previewUrl = if (useGifUrl) {
                        item.file.sm?.gif?.url ?: item.file.hd.gif.url
                    } else {
                        item.file.sm?.webp?.url ?: item.file.hd.webp?.url
                    }

                    val hdUrl = if (useGifUrl) {
                        item.file.hd.gif.url
                    } else {
                        item.file.hd.webp?.url
                    }

                    if (hdUrl == null) {
                        Log.d("KlipyPalettesView", "Skipped item ${item.id} - No HD WebP available for sticker send mode")
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
        findViewById<TextView>(R.id.tabGifs)?.setOnClickListener {
            selectGifsTab()
        }
        findViewById<TextView>(R.id.tabStickers)?.setOnClickListener {
            selectStickersTab()
        }
        findViewById<View>(R.id.dummySearchBox)?.setOnClickListener {
            if (!isSearchMode) {
                enterSearchMode()
            }
        }
        findViewById<View>(R.id.clearSearchButton)?.setOnClickListener {
            searchQuery = ""
            updateSearchQueryUI()
            if (!isSearchMode) {
                performSearch("")
            }
        }
        findViewById<View>(R.id.klipyBackButton)?.setOnClickListener {
            if (isSearchMode) {
                exitSearchMode(triggerSearch = false)
            } else if (isSearchActive) {
                searchQuery = ""
                updateSearchQueryUI()
                performSearch("")
            } else {
                keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
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
                colors.setColor(backButton, ColorType.TOOL_BAR_KEY)
            }
            val tabGifs = findViewById<TextView>(R.id.tabGifs)
            if (tabGifs != null) {
                val toolBarColor = colors.get(ColorType.TOOL_BAR_KEY)
                tabGifs.setTextColor(toolBarColor)
                val drawables = tabGifs.compoundDrawablesRelative
                drawables[0]?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it.mutate(), toolBarColor) }
            }
            val tabStickers = findViewById<TextView>(R.id.tabStickers)
            if (tabStickers != null) {
                val toolBarColor = colors.get(ColorType.TOOL_BAR_KEY)
                tabStickers.setTextColor(toolBarColor)
                val drawables = tabStickers.compoundDrawablesRelative
                drawables[0]?.let { androidx.core.graphics.drawable.DrawableCompat.setTint(it.mutate(), toolBarColor) }
            }

            // Theme search bar elements
            val searchBarContainer = findViewById<View>(R.id.searchBarContainer)
            if (searchBarContainer != null) {
                colors.setBackground(searchBarContainer, ColorType.STRIP_BACKGROUND)
            }
            val dummySearchTextView = findViewById<TextView>(R.id.dummySearchTextView)
            if (dummySearchTextView != null) {
                val toolBarColor = colors.get(ColorType.TOOL_BAR_KEY)
                dummySearchTextView.setTextColor(toolBarColor)
                dummySearchTextView.setHintTextColor((toolBarColor and 0x00FFFFFF) or 0x80000000.toInt())
            }
            val searchIcon = findViewById<ImageView>(R.id.searchIcon)
            if (searchIcon != null) {
                colors.setColor(searchIcon, ColorType.TOOL_BAR_KEY)
            }
            val clearSearchButton = findViewById<ImageButton>(R.id.clearSearchButton)
            if (clearSearchButton != null) {
                colors.setColor(clearSearchButton, ColorType.TOOL_BAR_KEY)
            }
        } catch (e: Exception) {
            Log.e("KlipyPalettesView", "Failed to apply theme", e)
        }
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        this.keyboardLayoutSet = kls
        val keyboard = kls.getKeyboard(KeyboardId.ELEMENT_EMOJI_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
    }

    private fun enterSearchMode() {
        isSearchMode = true
        gifsAdapter.setAnimationsRunning(false)
        stickersAdapter.setAnimationsRunning(false)
        findViewById<View>(R.id.klipyHeader)?.visibility = View.GONE
        viewPager.visibility = View.GONE
        emptyState.visibility = View.GONE
        currentSearchKeyboardElementId = KeyboardId.ELEMENT_ALPHABET
        updateSearchKeyboard()
    }

    private fun exitSearchMode(triggerSearch: Boolean) {
        isSearchMode = false
        findViewById<View>(R.id.klipyHeader)?.visibility = View.VISIBLE
        viewPager.visibility = View.VISIBLE
        gifsAdapter.setAnimationsRunning(true)
        stickersAdapter.setAnimationsRunning(true)
        
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        val listener = mainListener ?: keyboardActionListener
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = keyboardLayoutSet ?: KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, mainEditorInfo)
        val keyboard = kls.getKeyboard(KeyboardId.ELEMENT_EMOJI_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)

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
        val kls = keyboardLayoutSet ?: KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, mainEditorInfo)
        val keyboard = kls.getKeyboard(currentSearchKeyboardElementId)
        keyboardView.setKeyboard(keyboard)
    }

    private fun updateSearchQueryUI() {
        val dummySearchTextView = findViewById<TextView>(R.id.dummySearchTextView)
        val clearSearchButton = findViewById<View>(R.id.clearSearchButton)
        if (searchQuery.isNotEmpty()) {
            dummySearchTextView?.text = searchQuery
            clearSearchButton?.visibility = View.VISIBLE
        } else {
            dummySearchTextView?.text = ""
            dummySearchTextView?.hint = context.getString(R.string.klipy_search_hint)
            clearSearchButton?.visibility = View.GONE
        }
    }

    private fun handleSearchCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean) {
        when (primaryCode) {
            KeyCode.DELETE -> {
                if (searchQuery.isNotEmpty()) {
                    searchQuery = searchQuery.substring(0, searchQuery.length - 1)
                    updateSearchQueryUI()
                }
            }
            Constants.CODE_ENTER -> {
                exitSearchMode(triggerSearch = true)
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
                    searchQuery += primaryCode.toChar()
                    updateSearchQueryUI()
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
                searchQuery += text
                updateSearchQueryUI()
            }
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
