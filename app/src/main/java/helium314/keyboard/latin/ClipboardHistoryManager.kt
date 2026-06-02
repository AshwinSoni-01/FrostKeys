// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.Manifest
import android.content.ContentUris
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.database.ContentObserver
import android.graphics.Outline
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.ImageView
import androidx.core.content.edit
import androidx.core.content.FileProvider
import androidx.core.view.isGone
import coil.load
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.compat.ClipboardManagerCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.isValidNumber
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.permissions.PermissionsUtil
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.prefs
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private var screenshotObserver: ContentObserver? = null
    private var lastProcessedImageUri: String? = null
    private val processedScreenshotUris = ArrayDeque<String>()
    private val screenshotInFlightUris = mutableSetOf<String>()
    @Volatile
    private var latestImageSuggestion: RecentClip.Image? = null

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        restoreProcessedScreenshotUris()
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
        syncScreenshotObserver()
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        unregisterScreenshotObserver()
    }

    private fun syncScreenshotObserver() {
        if (shouldObserveScreenshots()) registerScreenshotObserver()
        else unregisterScreenshotObserver()
    }

    private fun shouldObserveScreenshots(): Boolean {
        val settings = latinIME.mSettings.current
        return settings.mClipboardHistoryEnabled
                && settings.mShowScreenshotsInClipboard
                && hasScreenshotReadPermission()
    }

    private fun hasScreenshotReadPermission(): Boolean {
        val permission = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Manifest.permission.READ_MEDIA_IMAGES
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> Manifest.permission.READ_EXTERNAL_STORAGE
            else -> return true
        }
        return PermissionsUtil.checkAllPermissionsGranted(latinIME, permission)
    }

    private fun registerScreenshotObserver() {
        if (screenshotObserver != null) return
        
        screenshotObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                onScreenshotMediaChanged()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                onScreenshotMediaChanged()
            }

            override fun onChange(selfChange: Boolean, uris: Collection<Uri>, flags: Int) {
                super.onChange(selfChange, uris, flags)
                onScreenshotMediaChanged()
            }
        }
        
        try {
            latinIME.contentResolver.registerContentObserver(
                imageCollectionUri(),
                true,
                screenshotObserver!!
            )
        } catch (e: Exception) {
            Log.e("ClipboardHistoryManager", "Failed to register screenshot content observer", e)
        }
    }

    private fun unregisterScreenshotObserver() {
        screenshotObserver?.let {
            try {
                latinIME.contentResolver.unregisterContentObserver(it)
            } catch (e: Exception) {
                Log.e("ClipboardHistoryManager", "Failed to unregister screenshot observer", e)
            }
            screenshotObserver = null
        }
    }

    private fun onScreenshotMediaChanged() {
        if (!shouldObserveScreenshots()) {
            syncScreenshotObserver()
            return
        }
        checkForNewScreenshot()
    }

    private fun checkForNewScreenshot() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                if (!shouldObserveScreenshots()) return@launch
                val dao = clipboardDao
                var processedAny = false

                for (screenshot in queryRecentScreenshots().asReversed()) {
                    val uriString = screenshot.uri.toString()
                    if (!claimScreenshotUri(uriString)) continue

                    var processed = false
                    try {
                        val timeStamp = System.currentTimeMillis()
                        val imageClip = RecentClip.Image(
                            uri = screenshot.uri,
                            mimeType = normalizeImageMimeType(screenshot.mimeType).mimeType,
                            label = "Screenshot",
                            timeStamp = timeStamp
                        )

                        val cachedUri = cacheImageClip(imageClip)
                        if (cachedUri != null && dao != null) {
                            dao.addClip(
                                timeStamp,
                                false,
                                encodeImageHistoryClip(cachedUri, imageClip.mimeType, imageClip.label)
                            )
                            rememberImageSuggestion(imageClip.copy(uri = cachedUri))
                            processed = true
                            processedAny = true
                        }
                    } catch (e: Exception) {
                        Log.e("ClipboardHistoryManager", "Failed processing screenshot $uriString", e)
                    } finally {
                        completeScreenshotUri(uriString, screenshot.dateAdded, processed)
                    }
                }

                if (processedAny) {
                    refreshClipboardSuggestion()
                }
            } catch (e: Exception) {
                Log.e("ClipboardHistoryManager", "Failed checking for new screenshot", e)
            }
        }
    }

    private data class ScreenshotMedia(
        val uri: Uri,
        val mimeType: String,
        val dateAdded: Long
    )

    private fun queryRecentScreenshots(): List<ScreenshotMedia> {
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.MIME_TYPE
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                add(MediaStore.Images.Media.DATA)
            }
        }.toTypedArray()
        val recentSinceSeconds = System.currentTimeMillis() / 1000L - SCREENSHOT_RECENT_WINDOW_SECONDS
        val (selection, selectionArgs) = screenshotSelection(recentSinceSeconds)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC"
        val screenshots = mutableListOf<ScreenshotMedia>()
        latinIME.contentResolver.query(
            imageCollectionUri(),
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
            val dateIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
            val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                @Suppress("DEPRECATION")
                cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            }
            if (idIndex == -1 || dateIndex == -1 || mimeIndex == -1 || pathIndex == -1) return emptyList()

            var checkedRows = 0
            while (cursor.moveToNext() && checkedRows < MAX_SCREENSHOT_ROWS_TO_CHECK) {
                checkedRows++
                val path = cursor.getString(pathIndex)
                if (!isScreenshotPath(path)) continue

                val id = cursor.getLong(idIndex)
                val itemUri = ContentUris.withAppendedId(imageCollectionUri(), id)
                val mimeType = cursor.getString(mimeIndex)
                    ?.takeIf { ClipDescription.compareMimeTypes(it, "image/*") }
                    ?: latinIME.contentResolver.getType(itemUri)
                        ?.takeIf { ClipDescription.compareMimeTypes(it, "image/*") }
                    ?: continue
                screenshots.add(ScreenshotMedia(itemUri, normalizeImageMimeType(mimeType).mimeType, cursor.getLong(dateIndex)))
            }
        }
        return screenshots
    }

    private fun imageCollectionUri(): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
    }

    private fun screenshotSelection(recentSinceSeconds: Long): Pair<String, Array<String>> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pathSelection = SCREENSHOT_RELATIVE_PATHS.joinToString(" OR ") {
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            }
            val selection = "($pathSelection) AND ${MediaStore.Images.Media.DATE_ADDED} >= ? " +
                    "AND ${MediaStore.Images.Media.MIME_TYPE} LIKE ? " +
                    "AND ${MediaStore.Images.Media.IS_PENDING} = 0"
            val args = SCREENSHOT_RELATIVE_PATHS.map { "$it%" } +
                    listOf(recentSinceSeconds.toString(), "image/%")
            selection to args.toTypedArray()
        } else {
            @Suppress("DEPRECATION")
            val pathSelection = screenshotAbsoluteDirectoryPrefixes().joinToString(" OR ") {
                "${MediaStore.Images.Media.DATA} LIKE ?"
            }
            @Suppress("DEPRECATION")
            val selection = "($pathSelection) AND ${MediaStore.Images.Media.DATE_ADDED} >= ? " +
                    "AND ${MediaStore.Images.Media.MIME_TYPE} LIKE ?"
            val args = screenshotAbsoluteDirectoryPrefixes().map { "$it%" } +
                    listOf(recentSinceSeconds.toString(), "image/%")
            selection to args.toTypedArray()
        }
    }

    private fun isScreenshotPath(path: String?): Boolean {
        val normalizedPath = path?.replace('\\', '/') ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            SCREENSHOT_RELATIVE_PATHS.any { normalizedPath.startsWith(it, ignoreCase = true) }
        } else {
            screenshotAbsoluteDirectoryPrefixes()
                .map { it.replace('\\', '/') }
                .any { normalizedPath.startsWith(it, ignoreCase = true) }
        }
    }

    private fun screenshotAbsoluteDirectoryPrefixes(): List<String> {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        @Suppress("DEPRECATION")
        return listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots"),
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screenshots"),
            File(root, "Screenshots")
        ).map { it.absolutePath.trimEnd('/', '\\') + File.separator }
    }

    private fun claimScreenshotUri(uriString: String): Boolean = synchronized(this) {
        if (processedScreenshotUris.contains(uriString)
            || uriString == lastProcessedImageUri
            || screenshotInFlightUris.contains(uriString)
        ) {
            false
        } else {
            screenshotInFlightUris.add(uriString)
            true
        }
    }

    private fun completeScreenshotUri(uriString: String, dateAdded: Long, processed: Boolean) {
        val processedSnapshot = synchronized(this) {
            if (processed) rememberProcessedScreenshotUriLocked(uriString)
            screenshotInFlightUris.remove(uriString)
            if (processed) processedScreenshotUris.toList() else null
        }
        if (processedSnapshot != null) {
            persistProcessedScreenshotUris(processedSnapshot, uriString, dateAdded)
        }
    }

    private fun restoreProcessedScreenshotUris() {
        synchronized(this) {
            if (processedScreenshotUris.isNotEmpty()) return

            val persistedUris = latinIME.prefs()
                .getString(PREF_PROCESSED_SCREENSHOT_MEDIA_URIS, null)
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toList()
                .orEmpty()
            val migratedUri = latinIME.prefs().getString(PREF_LAST_SCREENSHOT_MEDIA_URI, null)
                ?.takeIf { it.isNotBlank() && it !in persistedUris }

            (persistedUris + listOfNotNull(migratedUri))
                .takeLast(MAX_PROCESSED_SCREENSHOT_URIS)
                .forEach { processedScreenshotUris.addLast(it) }
            lastProcessedImageUri = processedScreenshotUris.lastOrNull()
        }
    }

    private fun rememberProcessedScreenshotUriLocked(uriString: String) {
        processedScreenshotUris.remove(uriString)
        processedScreenshotUris.addLast(uriString)
        while (processedScreenshotUris.size > MAX_PROCESSED_SCREENSHOT_URIS) {
            processedScreenshotUris.removeFirst()
        }
        lastProcessedImageUri = uriString
    }

    private fun persistProcessedScreenshotUris(
        processedUris: List<String>,
        latestUri: String,
        dateAdded: Long
    ) {
        latinIME.prefs().edit {
            putString(PREF_PROCESSED_SCREENSHOT_MEDIA_URIS, processedUris.joinToString("\n"))
            putString(PREF_LAST_SCREENSHOT_MEDIA_URI, latestUri)
            putLong(PREF_LAST_SCREENSHOT_DATE_ADDED, dateAdded)
        }
    }

    override fun onPrimaryClipChanged() {
        dontShowCurrentSuggestion = false
        // Make sure we read clipboard history content only if history settings is set.
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip(clipChanged = true)
        }
        refreshClipboardSuggestion()
    }

    fun updatePrimaryClip() {
        syncScreenshotObserver()
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
        }
    }

    private fun fetchPrimaryClip(clipChanged: Boolean = false) {
        try {
            val clipData = clipboardManager.primaryClip ?: run {
                if (clipChanged) latestImageSuggestion = null
                return
            }
            if (clipData.itemCount == 0) {
                if (clipChanged) latestImageSuggestion = null
                return
            }
            val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
            if (latinIME.mSettings.current.mShowScreenshotsInClipboard) {
                recentImageClip(clipData)?.let { imageClip ->
                    val cachedUri = cacheImageClip(imageClip) ?: return
                    clipboardDao?.addClip(timeStamp, false, encodeImageHistoryClip(cachedUri, imageClip.mimeType, imageClip.label))
                    rememberImageSuggestion(imageClip.copy(uri = cachedUri))
                    return
                }
            }
            if (shouldReplaceLatestImageSuggestion(timeStamp, clipChanged)) {
                latestImageSuggestion = null
            }
            if (clipData.description?.hasMimeType("text/*") == false) return
            clipData.getItemAt(0)?.let { clipItem ->
                val content = clipItem.coerceToText(latinIME)
                if (TextUtils.isEmpty(content)) return
                clipboardDao?.addClip(timeStamp, false, content.toString())
            }
        } catch (e: Exception) {
            Log.e("ClipboardHistoryManager", "Failed to fetch primary clip safely", e)
        }
    }

    private fun refreshClipboardSuggestion() {
        latinIME.mHandler.post {
            if (latinIME.isInputViewShown && latinIME.hasSuggestionStripView()) {
                latinIME.setNeutralSuggestionStrip()
            }
        }
    }

    fun toggleClipPinned(id: Long) {
        clipboardDao?.togglePinned(id)
    }

    fun clearHistory() {
        clipboardDao?.clearNonPinned()
        ClipboardManagerCompat.clearPrimaryClip(clipboardManager)
        removeClipboardSuggestion()
    }

    fun canRemove(index: Int) = clipboardDao?.isPinned(index) == false

    fun removeEntry(index: Int) {
        if (canRemove(index))
            clipboardDao?.deleteClipAt(index)
    }

    fun sortHistoryEntries() {
        clipboardDao?.sort()
    }

    // We do not want to update history while user is visualizing it, so we check retention only
    // when history is about to be shown
    fun prepareClipboardHistory() = clipboardDao?.clearOldClips(true)

    fun getHistorySize() = clipboardDao?.count() ?: 0

    fun getHistoryEntry(position: Int) = clipboardDao?.getAt(position)

    fun getHistoryEntryContent(id: Long) = clipboardDao?.get(id)

    fun setHistoryChangeListener(listener: ClipboardDao.Listener?) {
        clipboardDao?.listener = listener
    }

    fun retrieveClipboardContent(): CharSequence {
        val clipData = clipboardManager.primaryClip ?: return ""
        if (clipData.itemCount == 0) return ""
        return clipData.getItemAt(0)?.coerceToText(latinIME) ?: ""
    }

    private fun isClipSensitive(inputType: Int): Boolean {
        ClipboardManagerCompat.getClipSensitivity(clipboardManager.primaryClip?.description)?.let { return it }
        return InputTypeUtils.isPasswordInputType(inputType)
    }

    private sealed class RecentClip(open val timeStamp: Long) {
        data class Text(val content: CharSequence, override val timeStamp: Long) : RecentClip(timeStamp)
        data class Image(
            val uri: Uri,
            val mimeType: String,
            val label: String,
            override val timeStamp: Long
        ) : RecentClip(timeStamp)
    }

    private fun ClipDescription.imageMimeType(): String? {
        for (i in 0 until mimeTypeCount) {
            val mimeType = getMimeType(i)
            if (ClipDescription.compareMimeTypes(mimeType, "image/*")) return mimeType
        }
        return null
    }

    private fun recentImageClip(clipData: android.content.ClipData): RecentClip.Image? {
        if (clipData.itemCount == 0) return null
        val uri = clipData.imageUri() ?: return null
        val description = clipData.description
        val descriptionMimeType = description?.imageMimeType()
            ?.takeUnless { it == "image/*" }
        val resolverMimeType = latinIME.contentResolver.getType(uri)
            ?.takeIf { ClipDescription.compareMimeTypes(it, "image/*") }
        val mimeType = descriptionMimeType ?: resolverMimeType ?: description?.imageMimeType()
            ?: return null
        val labelText = description?.label?.toString().orEmpty()
        val uriText = uri.toString()
        val label = if (
            labelText.contains("screenshot", ignoreCase = true)
            || uriText.contains("screenshot", ignoreCase = true)
        ) {
            "Screenshot"
        } else {
            "Image"
        }
        return RecentClip.Image(uri, normalizeImageMimeType(mimeType).mimeType, label, ClipboardManagerCompat.getClipTimestamp(clipData))
    }

    private fun rememberImageSuggestion(clip: RecentClip.Image) {
        latestImageSuggestion = clip
        dontShowCurrentSuggestion = false
    }

    private fun shouldReplaceLatestImageSuggestion(timeStamp: Long, clipChanged: Boolean): Boolean {
        val imageClip = latestImageSuggestion ?: return true
        return clipChanged || timeStamp >= imageClip.timeStamp
    }

    private fun latestRecentImageSuggestion(inputType: Int): RecentClip.Image? {
        if (!latinIME.mSettings.current.mShowScreenshotsInClipboard) {
            latestImageSuggestion = null
            return null
        }
        if (InputTypeUtils.isPasswordInputType(inputType) || InputTypeUtils.isNumberInputType(inputType)) {
            return null
        }
        val imageClip = latestImageSuggestion ?: return null
        if (System.currentTimeMillis() - imageClip.timeStamp > RECENT_TIME_MILLIS) {
            latestImageSuggestion = null
            return null
        }
        return imageClip
    }

    private fun android.content.ClipData.imageUri(): Uri? {
        for (i in 0 until itemCount) {
            val item = getItemAt(i) ?: continue
            item.uri?.let { return it }
            item.intent?.data?.let { return it }
        }
        return null
    }

    private fun recentClip(editorInfo: EditorInfo?): RecentClip? {
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL
        latestRecentImageSuggestion(inputType)?.let { return it }
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val description = clipData.description ?: return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null

        if (latinIME.mSettings.current.mShowScreenshotsInClipboard) recentImageClip(clipData)?.let { imageClip ->
            if (InputTypeUtils.isPasswordInputType(inputType) || InputTypeUtils.isNumberInputType(inputType)) {
                return null
            }
            return imageClip
        }

        if (!description.hasMimeType("text/*")) return null
        val content = clipData.getItemAt(0)?.coerceToText(latinIME) ?: return null
        if (TextUtils.isEmpty(content)) return null
        if (InputTypeUtils.isNumberInputType(inputType) && !content.isValidNumber()) return null
        return RecentClip.Text(content, timeStamp)
    }

    fun getClipboardSuggestionView(editorInfo: EditorInfo?, parent: ViewGroup?): View? {
        // maybe no need to create a new view
        // but a cache has to consider a few possible changes, so better don't implement without need
        clipboardSuggestionView = null

        // get the content, or return null
        if (!latinIME.mSettings.current.mSuggestClipboardContent) return null
        if (dontShowCurrentSuggestion) return null
        if (parent == null) return null
        val clip = recentClip(editorInfo) ?: return null
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL

        // create the view
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(latinIME), parent, false)
        val textView = binding.clipboardSuggestionText
        val preview = binding.clipboardSuggestionPreview
        KeyboardTypeface.applyToTextView(textView)
        val clipIcon = latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.PASTE.name.lowercase())

        when (clip) {
            is RecentClip.Text -> {
                preview.isGone = true
                textView.text = (if (isClipSensitive(inputType)) "*".repeat(clip.content.length) else clip.content)
                    .take(200) // truncate displayed text for performance reasons
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(clipIcon, null, null, null)
                textView.setOnClickListener {
                    dontShowCurrentSuggestion = true
                    latinIME.onTextInput(clip.content.toString())
                    AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, it, HapticEvent.KEY_PRESS)
                    binding.root.isGone = true
                }
            }
            is RecentClip.Image -> {
                textView.text = clip.label
                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
                preview.isGone = false
                preview.configureCircularPreview()
                preview.load(clip.uri)
                binding.root.setOnClickListener { pasteImageClip(clip, binding.root, it) }
                textView.setOnClickListener { pasteImageClip(clip, binding.root, it) }
                preview.setOnClickListener { pasteImageClip(clip, binding.root, it) }
            }
        }
        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(latinIME.mKeyboardSwitcher.keyboard.mIconsSet.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.setOnClickListener { removeClipboardSuggestion() }

        val colors = latinIME.mSettings.current.mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        if (clip is RecentClip.Text) clipIcon?.let { colors.setColor(it, ColorType.KEY_ICON) }
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)

        clipboardSuggestionView = binding.root
        return clipboardSuggestionView
    }

    private fun ImageView.configureCircularPreview() {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }

    private fun pasteImageClip(clip: RecentClip.Image, chipView: View, feedbackView: View) {
        val cachedUri = cacheImageClip(clip) ?: return
        dontShowCurrentSuggestion = true
        val pasted = latinIME.commitKlipyContent(cachedUri, clip.label, normalizeImageMimeType(clip.mimeType).mimeType)
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, feedbackView, HapticEvent.KEY_PRESS)
        if (pasted) chipView.isGone = true
    }

    private fun cacheImageClip(clip: RecentClip.Image): Uri? {
        resolveOwnClipboardCacheFile(latinIME, clip.uri)?.let { cachedFile ->
            return if (cachedFile.exists() && cachedFile.length() > 0L) clip.uri else null
        }

        val mimeInfo = normalizeImageMimeType(clip.mimeType)
        val clipboardDir = File(latinIME.cacheDir, "clipboard").apply { mkdirs() }
        val imageFile = File(clipboardDir, cacheFileNameForSource(clip.uri, clip.timeStamp, mimeInfo.mimeType))
        if (!imageFile.exists() || imageFile.length() == 0L) {
            if (!copyImageClipAtomically(clip.uri, imageFile)) {
                return null
            }
        }
        return FileProvider.getUriForFile(latinIME, "${latinIME.packageName}.fileprovider", imageFile)
    }

    fun pasteHistoryEntry(entry: ClipboardHistoryEntry): Boolean {
        val clip = decodeImageHistoryClip(entry.text) ?: return false
        return latinIME.commitKlipyContent(clip.uri, clip.label, normalizeImageMimeType(clip.mimeType).mimeType)
    }

    private fun copyImageClipAtomically(sourceUri: Uri, imageFile: File): Boolean {
        val tempFile = File.createTempFile("${imageFile.nameWithoutExtension}_", ".tmp", imageFile.parentFile)
        return try {
            latinIME.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            if (tempFile.length() == 0L) return false

            if (imageFile.exists() && !imageFile.delete()) {
                Log.e("ClipboardHistoryManager", "Failed to replace cached image clipboard clip: $imageFile")
                return false
            }
            if (!tempFile.renameTo(imageFile)) {
                tempFile.copyTo(imageFile, overwrite = true)
            }
            imageFile.length() > 0L
        } catch (e: Exception) {
            Log.e("ClipboardHistoryManager", "Failed to cache image clipboard clip", e)
            false
        } finally {
            if (tempFile.exists() && !tempFile.delete()) {
                Log.e("ClipboardHistoryManager", "Failed to delete temp image clipboard clip: $tempFile")
            }
        }
    }

    private fun removeClipboardSuggestion() {
        dontShowCurrentSuggestion = true
        val csv = clipboardSuggestionView ?: return
        if (csv.parent != null && !csv.isGone) {
            // clipboard view is shown ->
            latinIME.setNeutralSuggestionStrip()
            latinIME.mHandler.postResumeSuggestions(false)
        }
        csv.isGone = true
    }

    companion object {
        data class ImageMimeInfo(val mimeType: String, val extension: String)

        private const val PREF_PROCESSED_SCREENSHOT_MEDIA_URIS = "clipboard_processed_screenshot_media_uris"
        private const val PREF_LAST_SCREENSHOT_MEDIA_URI = "clipboard_last_screenshot_media_uri"
        private const val PREF_LAST_SCREENSHOT_DATE_ADDED = "clipboard_last_screenshot_date_added"
        private const val SCREENSHOT_RECENT_WINDOW_SECONDS = 30L
        private const val MAX_SCREENSHOT_ROWS_TO_CHECK = 10
        private const val MAX_PROCESSED_SCREENSHOT_URIS = 20
        private val SCREENSHOT_RELATIVE_PATHS = listOf(
            "Pictures/Screenshots/",
            "DCIM/Screenshots/",
            "Screenshots/"
        )
        private var dontShowCurrentSuggestion: Boolean = false
        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        fun decodeImageHistoryClip(text: String) = ClipboardImageHistoryClip.decode(text)

        fun encodeImageHistoryClip(uri: Uri, mimeType: String, label: String) =
            ClipboardImageHistoryClip.encode(uri, mimeType, label)

        internal fun normalizeImageMimeType(mimeType: String?): ImageMimeInfo {
            val normalized = mimeType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase(Locale.US)
                .orEmpty()
            return when (normalized) {
                "image/jpg", "image/pjpeg", "image/jpeg" -> ImageMimeInfo("image/jpeg", "jpg")
                "image/x-png", "image/png", "image/*" -> ImageMimeInfo("image/png", "png")
                "image/x-webp", "image/webp" -> ImageMimeInfo("image/webp", "webp")
                "image/gif" -> ImageMimeInfo("image/gif", "gif")
                "image/heic", "image/heic-sequence" -> ImageMimeInfo("image/heic", "heic")
                "image/heif", "image/heif-sequence" -> ImageMimeInfo("image/heif", "heif")
                "image/x-ms-bmp", "image/bmp" -> ImageMimeInfo("image/bmp", "bmp")
                else -> {
                    if (!normalized.startsWith("image/")) return ImageMimeInfo("image/png", "png")
                    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(normalized)
                        ?: normalized.substringAfter("image/")
                            .substringBefore('+')
                            .let(::sanitizeImageExtension)
                    ImageMimeInfo(normalized, extension.ifBlank { "img" })
                }
            }
        }

        internal fun cacheFileNameForSource(sourceUri: Uri, timeStamp: Long, mimeType: String): String {
            val extension = normalizeImageMimeType(mimeType).extension
            return "clip_${timeStamp}_${stableCacheToken(sourceUri)}.$extension"
        }

        internal fun isOwnClipboardCacheUri(context: Context, uri: Uri): Boolean {
            return resolveOwnClipboardCacheFile(context, uri) != null
        }

        private fun resolveOwnClipboardCacheFile(context: Context, uri: Uri): File? {
            if (uri.scheme != "content" || uri.authority != "${context.packageName}.fileprovider") return null
            val segments = uri.pathSegments
            if (segments.size < 3 || segments[0] != "cache" || segments[1] != "clipboard") return null
            val clipboardDir = File(context.cacheDir, "clipboard")
            val file = File(context.cacheDir, segments.drop(1).joinToString(File.separator))
            return try {
                val canonicalDir = clipboardDir.canonicalFile
                val canonicalFile = file.canonicalFile
                val dirPath = canonicalDir.path
                val filePath = canonicalFile.path
                if (filePath.startsWith("$dirPath${File.separator}")) canonicalFile else null
            } catch (_: Exception) {
                null
            }
        }

        private fun sanitizeImageExtension(extension: String): String {
            return extension
                .lowercase(Locale.US)
                .filter { it.isLetterOrDigit() }
                .take(12)
                .ifBlank { "img" }
        }

        private fun stableCacheToken(uri: Uri): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(uri.toString().toByteArray(Charsets.UTF_8))
            return digest.take(8).joinToString("") {
                java.lang.String.format(Locale.US, "%02x", it.toInt() and 0xff)
            }
        }
    }
}
