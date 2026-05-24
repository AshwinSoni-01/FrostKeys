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
    private var screenshotInFlightUri: String? = null

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
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
                val screenshot = queryLatestScreenshot() ?: return@launch
                val uriString = screenshot.uri.toString()
                if (!claimScreenshotUri(uriString)) return@launch

                val imageClip = RecentClip.Image(
                    uri = screenshot.uri,
                    mimeType = screenshot.mimeType,
                    label = "Screenshot",
                    timeStamp = System.currentTimeMillis()
                )

                val cachedUri = cacheImageClip(imageClip)
                val dao = clipboardDao
                if (cachedUri != null && dao != null) {
                    val timeStamp = System.currentTimeMillis()
                    dao.addClip(
                        timeStamp,
                        false,
                        encodeImageHistoryClip(cachedUri, imageClip.mimeType, imageClip.label)
                    )
                    completeScreenshotUri(uriString, screenshot.dateAdded, true)
                    refreshClipboardSuggestion()
                } else {
                    completeScreenshotUri(uriString, screenshot.dateAdded, false)
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

    private fun queryLatestScreenshot(): ScreenshotMedia? {
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
            if (idIndex == -1 || dateIndex == -1 || mimeIndex == -1 || pathIndex == -1) return null

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
                return ScreenshotMedia(itemUri, mimeType, cursor.getLong(dateIndex))
            }
        }
        return null
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
        val lastPersistedUri = latinIME.prefs().getString(PREF_LAST_SCREENSHOT_MEDIA_URI, null)
        if (uriString == lastProcessedImageUri || uriString == screenshotInFlightUri || uriString == lastPersistedUri) {
            false
        } else {
            screenshotInFlightUri = uriString
            true
        }
    }

    private fun completeScreenshotUri(uriString: String, dateAdded: Long, processed: Boolean) {
        synchronized(this) {
            if (processed) lastProcessedImageUri = uriString
            if (screenshotInFlightUri == uriString) screenshotInFlightUri = null
        }
        if (processed) {
            latinIME.prefs().edit {
                putString(PREF_LAST_SCREENSHOT_MEDIA_URI, uriString)
                putLong(PREF_LAST_SCREENSHOT_DATE_ADDED, dateAdded)
            }
        }
    }

    override fun onPrimaryClipChanged() {
        dontShowCurrentSuggestion = false
        // Make sure we read clipboard history content only if history settings is set.
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
        }
        refreshClipboardSuggestion()
    }

    fun updatePrimaryClip() {
        syncScreenshotObserver()
        if (latinIME.mSettings.current.mClipboardHistoryEnabled) {
            fetchPrimaryClip()
        }
    }

    private fun fetchPrimaryClip() {
        try {
            val clipData = clipboardManager.primaryClip ?: return
            if (clipData.itemCount == 0) return
            val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
            if (latinIME.mSettings.current.mShowScreenshotsInClipboard) {
                recentImageClip(clipData)?.let { imageClip ->
                    val cachedUri = cacheImageClip(imageClip) ?: return
                    clipboardDao?.addClip(timeStamp, false, encodeImageHistoryClip(cachedUri, imageClip.mimeType, imageClip.label))
                    return
                }
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
        val mimeType = description?.imageMimeType()
            ?: latinIME.contentResolver.getType(uri)?.takeIf { ClipDescription.compareMimeTypes(it, "image/*") }
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
        return RecentClip.Image(uri, mimeType, label, ClipboardManagerCompat.getClipTimestamp(clipData))
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
        val clipData = clipboardManager.primaryClip ?: return null
        if (clipData.itemCount == 0) return null
        val description = clipData.description ?: return null
        val timeStamp = ClipboardManagerCompat.getClipTimestamp(clipData)
        if (System.currentTimeMillis() - timeStamp > RECENT_TIME_MILLIS) return null
        val inputType = editorInfo?.inputType ?: InputType.TYPE_NULL

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
        val pasted = latinIME.commitKlipyContent(cachedUri, clip.label, clip.mimeType)
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, feedbackView, HapticEvent.KEY_PRESS)
        if (pasted) chipView.isGone = true
    }

    private fun cacheImageClip(clip: RecentClip.Image): Uri? {
        val extension = when (clip.mimeType) {
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "png"
        }
        val clipboardDir = File(latinIME.cacheDir, "clipboard").apply { mkdirs() }
        val imageFile = File(clipboardDir, "clip_${clip.timeStamp}.$extension")
        if (!imageFile.exists() || imageFile.length() == 0L) {
            try {
                latinIME.contentResolver.openInputStream(clip.uri)?.use { input ->
                    imageFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return null
            } catch (e: Exception) {
                Log.e("ClipboardHistoryManager", "Failed to cache image clipboard clip", e)
                return null
            }
        }
        return FileProvider.getUriForFile(latinIME, "${latinIME.packageName}.fileprovider", imageFile)
    }

    fun pasteHistoryEntry(entry: ClipboardHistoryEntry): Boolean {
        val clip = decodeImageHistoryClip(entry.text) ?: return false
        return latinIME.commitKlipyContent(clip.uri, clip.label, clip.mimeType)
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
        private const val IMAGE_HISTORY_PREFIX = "\u0001image\t"
        private const val PREF_LAST_SCREENSHOT_MEDIA_URI = "clipboard_last_screenshot_media_uri"
        private const val PREF_LAST_SCREENSHOT_DATE_ADDED = "clipboard_last_screenshot_date_added"
        private const val SCREENSHOT_RECENT_WINDOW_SECONDS = 30L
        private const val MAX_SCREENSHOT_ROWS_TO_CHECK = 5
        private val SCREENSHOT_RELATIVE_PATHS = listOf(
            "Pictures/Screenshots/",
            "DCIM/Screenshots/",
            "Screenshots/"
        )
        private var dontShowCurrentSuggestion: Boolean = false
        const val RECENT_TIME_MILLIS = 3 * 60 * 1000L // 3 minutes (for clipboard suggestions)

        data class ImageHistoryClip(val uri: Uri, val mimeType: String, val label: String)

        fun decodeImageHistoryClip(text: String): ImageHistoryClip? {
            if (!text.startsWith(IMAGE_HISTORY_PREFIX)) return null
            val parts = text.removePrefix(IMAGE_HISTORY_PREFIX).split('\t', limit = 3)
            if (parts.size != 3) return null
            return ImageHistoryClip(Uri.parse(parts[0]), parts[1], parts[2])
        }

        fun encodeImageHistoryClip(uri: Uri, mimeType: String, label: String): String {
            val safeLabel = label.replace('\t', ' ').replace('\n', ' ')
            return "$IMAGE_HISTORY_PREFIX$uri\t$mimeType\t$safeLabel"
        }
    }
}
