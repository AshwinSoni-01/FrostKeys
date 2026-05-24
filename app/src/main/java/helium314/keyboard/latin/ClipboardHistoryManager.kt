// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Outline
import android.net.Uri
import android.text.InputType
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
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
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import java.io.File
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import android.database.ContentObserver
import android.provider.MediaStore
import android.content.ContentUris

class ClipboardHistoryManager(
        private val latinIME: LatinIME
) : ClipboardManager.OnPrimaryClipChangedListener {

    private lateinit var clipboardManager: ClipboardManager
    private var clipboardSuggestionView: View? = null
    private var clipboardDao: ClipboardDao? = null
    private var screenshotObserver: ContentObserver? = null
    private var lastProcessedImageUri: String? = null

    fun onCreate() {
        clipboardManager = latinIME.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.addPrimaryClipChangedListener(this)
        clipboardDao = ClipboardDao.getInstance(latinIME)
        if (latinIME.mSettings.current.mClipboardHistoryEnabled)
            fetchPrimaryClip()
        registerScreenshotObserver()
    }

    fun onDestroy() {
        clipboardManager.removePrimaryClipChangedListener(this)
        unregisterScreenshotObserver()
    }

    private fun registerScreenshotObserver() {
        if (screenshotObserver != null) return
        
        screenshotObserver = object : ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                if (!latinIME.mSettings.current.mShowScreenshotsInClipboard || !latinIME.mSettings.current.mClipboardHistoryEnabled) return
                if (uri != null) {
                    checkForNewScreenshot(uri)
                } else {
                    checkForNewScreenshot(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }
            }
        }
        
        try {
            latinIME.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
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

    private fun checkForNewScreenshot(targetUri: Uri) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED
                )
                
                val cursor = if (targetUri.toString().startsWith(MediaStore.Images.Media.EXTERNAL_CONTENT_URI.toString())) {
                    if (targetUri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI) {
                        latinIME.contentResolver.query(
                            targetUri,
                            projection,
                            null,
                            null,
                            "${MediaStore.Images.Media.DATE_ADDED} DESC LIMIT 1"
                        )
                    } else {
                        latinIME.contentResolver.query(targetUri, projection, null, null, null)
                    }
                } else {
                    null
                }
                
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val idIndex = c.getColumnIndex(MediaStore.Images.Media._ID)
                        val nameIndex = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val dateIndex = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                        
                        if (idIndex != -1 && nameIndex != -1 && dateIndex != -1) {
                            val id = c.getLong(idIndex)
                            val name = c.getString(nameIndex) ?: ""
                            val dateAdded = c.getLong(dateIndex)
                            
                            val itemUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id
                            )
                            
                            // Check if this image was added very recently (e.g. within 15 seconds)
                            val nowSec = System.currentTimeMillis() / 1000L
                            val isRecent = (nowSec - dateAdded) < 15L
                            
                            // Check if the display name indicates it is a screenshot
                            val isScreenshot = name.contains("screenshot", ignoreCase = true)
                            
                            if (isRecent && isScreenshot && itemUri.toString() != lastProcessedImageUri) {
                                lastProcessedImageUri = itemUri.toString()
                                val mimeType = latinIME.contentResolver.getType(itemUri) ?: "image/png"
                                
                                val imageClip = RecentClip.Image(
                                    uri = itemUri,
                                    mimeType = mimeType,
                                    label = "Screenshot",
                                    timeStamp = System.currentTimeMillis()
                                )
                                
                                val cachedUri = cacheImageClip(imageClip)
                                if (cachedUri != null) {
                                    val timeStamp = System.currentTimeMillis()
                                    clipboardDao?.addClip(
                                        timeStamp,
                                        false,
                                        encodeImageHistoryClip(cachedUri, imageClip.mimeType, imageClip.label)
                                    )
                                    
                                    val clipData = android.content.ClipData.newUri(
                                        latinIME.contentResolver,
                                        "Screenshot",
                                        cachedUri
                                    )
                                    
                                    latinIME.mHandler.post {
                                        try {
                                            clipboardManager.setPrimaryClip(clipData)
                                        } catch (e: Exception) {
                                            Log.e("ClipboardHistoryManager", "Failed to update system clipboard", e)
                                        }
                                        refreshClipboardSuggestion()
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ClipboardHistoryManager", "Failed checking for new screenshot", e)
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
        latinIME.commitKlipyContent(cachedUri, clip.label, clip.mimeType)
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, feedbackView, HapticEvent.KEY_PRESS)
        chipView.isGone = true
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
        latinIME.commitKlipyContent(clip.uri, clip.label, clip.mimeType)
        return true
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
