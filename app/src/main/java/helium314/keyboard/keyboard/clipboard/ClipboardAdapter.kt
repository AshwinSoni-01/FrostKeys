// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.isGone
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import coil.load
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.KeyBackgroundUtils
import helium314.keyboard.latin.settings.Settings

enum class FilterMode {
    ALL,
    PINNED_ONLY,
    RECENTS_ONLY
}

sealed class ClipboardItem {
    data class Header(val isPinnedHeader: Boolean) : ClipboardItem()
    data class Clip(val entry: ClipboardHistoryEntry) : ClipboardItem()
}

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener,
       val filterMode: FilterMode = FilterMode.ALL
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null

    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    private val items = mutableListOf<ClipboardItem>()

    companion object {
        private const val VIEW_TYPE_TEXT = 0
        private const val VIEW_TYPE_IMAGE = 1
        private const val VIEW_TYPE_HEADER = 2
        private const val ROUNDED_ENTRY_RADIUS_DP = 18f
        private const val DEFAULT_IMAGE_RADIUS_DP = 8f
    }

    fun updateItems(newEntries: List<ClipboardHistoryEntry>, pinnedFirst: Boolean) {
        val newItems = buildDisplayItems(newEntries, pinnedFirst)
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = newItems[newItemPosition]
                return when {
                    old is ClipboardItem.Header && new is ClipboardItem.Header -> old.isPinnedHeader == new.isPinnedHeader
                    old is ClipboardItem.Clip && new is ClipboardItem.Clip -> old.entry.id == new.entry.id
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val old = items[oldItemPosition]
                val new = newItems[newItemPosition]
                return when {
                    old is ClipboardItem.Header && new is ClipboardItem.Header -> true
                    old is ClipboardItem.Clip && new is ClipboardItem.Clip -> {
                        old.entry.timeStamp == new.entry.timeStamp &&
                        old.entry.isPinned == new.entry.isPinned &&
                        old.entry.text == new.entry.text
                    }
                    else -> false
                }
            }
        })
        items.clear()
        items.addAll(newItems)
        diffResult.dispatchUpdatesTo(this)
    }

    private fun buildDisplayItems(entries: List<ClipboardHistoryEntry>, pinnedFirst: Boolean): List<ClipboardItem> {
        if (entries.isEmpty()) return emptyList()

        return when (filterMode) {
            FilterMode.PINNED_ONLY -> {
                entries.filter { it.isPinned }
                    .sortedByDescending { it.timeStamp }
                    .map { ClipboardItem.Clip(it) }
            }
            FilterMode.RECENTS_ONLY -> {
                entries.filter { !it.isPinned }
                    .sortedByDescending { it.timeStamp }
                    .map { ClipboardItem.Clip(it) }
            }
            FilterMode.ALL -> {
                val pinnedClips = entries.filter { it.isPinned }.sortedByDescending { it.timeStamp }
                val recentClips = entries.filter { !it.isPinned }.sortedByDescending { it.timeStamp }

                val result = mutableListOf<ClipboardItem>()
                if (pinnedFirst) {
                    if (pinnedClips.isNotEmpty()) {
                        result.add(ClipboardItem.Header(isPinnedHeader = true))
                        pinnedClips.forEach { result.add(ClipboardItem.Clip(it)) }
                    }
                    if (recentClips.isNotEmpty()) {
                        result.add(ClipboardItem.Header(isPinnedHeader = false))
                        recentClips.forEach { result.add(ClipboardItem.Clip(it)) }
                    }
                } else {
                    if (recentClips.isNotEmpty()) {
                        result.add(ClipboardItem.Header(isPinnedHeader = false))
                        recentClips.forEach { result.add(ClipboardItem.Clip(it)) }
                    }
                    if (pinnedClips.isNotEmpty()) {
                        result.add(ClipboardItem.Header(isPinnedHeader = true))
                        pinnedClips.forEach { result.add(ClipboardItem.Clip(it)) }
                    }
                }
                result
            }
        }
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    fun getItem(position: Int): ClipboardItem? = items.getOrNull(position)

    fun getClipEntryAt(position: Int): ClipboardHistoryEntry? {
        val item = items.getOrNull(position)
        return (item as? ClipboardItem.Clip)?.entry
    }

    fun canRemove(position: Int): Boolean {
        val item = items.getOrNull(position) ?: return false
        return when (item) {
            is ClipboardItem.Header -> false
            is ClipboardItem.Clip -> !item.entry.isPinned
        }
    }

    fun findPositionOfClip(clipId: Long): Int {
        return items.indexOfFirst { it is ClipboardItem.Clip && it.entry.id == clipId }
    }

    fun findFirstRecentPosition(): Int {
        return items.indexOfFirst { it is ClipboardItem.Clip && !it.entry.isPinned }
    }

    override fun getItemViewType(position: Int): Int {
        return when (val item = items.getOrNull(position)) {
            is ClipboardItem.Header -> VIEW_TYPE_HEADER
            is ClipboardItem.Clip -> {
                val imageClip = item.entry.text.let { ClipboardHistoryManager.decodeImageHistoryClip(it) }
                if (imageClip != null) VIEW_TYPE_IMAGE else VIEW_TYPE_TEXT
            }
            null -> VIEW_TYPE_TEXT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_HEADER -> {
                val view = inflater.inflate(R.layout.clipboard_header_item, parent, false)
                HeaderViewHolder(view)
            }
            VIEW_TYPE_IMAGE -> {
                val view = inflater.inflate(R.layout.clipboard_entry_image_key, parent, false)
                ViewHolder(parent, view)
            }
            else -> {
                val view = inflater.inflate(R.layout.clipboard_entry_key, parent, false)
                ViewHolder(parent, view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items.getOrNull(position)) {
            is ClipboardItem.Header -> (holder as? HeaderViewHolder)?.bind(item.isPinnedHeader, position)
            is ClipboardItem.Clip -> (holder as? ViewHolder)?.setContent(item.entry)
            null -> {}
        }
    }

    override fun getItemCount() = items.size

    inner class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.clipboard_header_title)

        init {
            val lp = itemView.layoutParams
            if (lp is StaggeredGridLayoutManager.LayoutParams) {
                lp.isFullSpan = true
            }
            itemView.isClickable = false
            itemView.isFocusable = false
        }

        fun bind(isPinnedHeader: Boolean, position: Int) {
            val lp = itemView.layoutParams
            if (lp is StaggeredGridLayoutManager.LayoutParams) {
                lp.isFullSpan = true
            }
            val titleText = if (isPinnedHeader) {
                itemView.context.getString(R.string.clipboard_header_pinned)
            } else {
                itemView.context.getString(R.string.clipboard_header_recents)
            }
            titleView.text = titleText
            KeyboardTypeface.applyToTextView(titleView, titleText, itemTypeFace ?: Typeface.DEFAULT_BOLD)

            val colors = Settings.getValues().mColors
            val textColor = colors.get(ColorType.KEY_TEXT)
            titleView.setTextColor(ColorUtils.setAlphaComponent(textColor, 0xDD))

            val density = itemView.resources.displayMetrics.density
            val topPad = if (position == 0) (4 * density).toInt() else (10 * density).toInt()
            val bottomPad = (3 * density).toInt()
            val sidePad = (10 * density).toInt()
            titleView.setPadding(sidePad, topPad, sidePad, bottomPad)
        }
    }

    inner class ViewHolder(
            val parent: ViewGroup,
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnLongClickListener {

        private val imageView: ImageView
        private val contentView: TextView?

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                setBackgroundResource(itemBackgroundId)
                isHapticFeedbackEnabled = false
            }
            val colors = Settings.getValues().mColors
            val usesRoundedCards = colors.themeStyle == KeyboardTheme.STYLE_ROUNDED
                    || colors.themeStyle == KeyboardTheme.STYLE_CIRCLE
            if (usesRoundedCards) {
                view.background = createRoundedClipboardEntryBackground(view)
            } else {
                colors.setBackground(view, ColorType.KEY_BACKGROUND)
            }
            imageView = view.findViewById(R.id.clipboard_entry_image)
            configureImagePreviewCorners(usesRoundedCards)
            contentView = view.findViewById<TextView>(R.id.clipboard_entry_content)?.apply {
                KeyboardTypeface.applyToTextView(this, null, itemTypeFace ?: android.graphics.Typeface.DEFAULT)
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            clipboardLayoutParams.setItemProperties(view)
        }

        private fun createRoundedClipboardEntryBackground(view: View): StateListDrawable {
            val colors = Settings.getValues().mColors
            val normalColor = KeyBackgroundUtils.fillColorFor(colors, ColorType.KEY_BACKGROUND)
            val pressedColor = pressedEntryColor(colors.get(ColorType.KEY_BACKGROUND))
            val radius = ROUNDED_ENTRY_RADIUS_DP * view.resources.displayMetrics.density
            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), roundedRectDrawable(pressedColor, radius))
                addState(intArrayOf(android.R.attr.state_selected), roundedRectDrawable(pressedColor, radius))
                addState(intArrayOf(), roundedRectDrawable(normalColor, radius))
            }
        }

        private fun roundedRectDrawable(color: Int, radius: Float) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

        private fun pressedEntryColor(normalColor: Int): Int {
            val base = if (ColorUtils.calculateLuminance(normalColor) < 0.5) Color.WHITE else Color.BLACK
            return ColorUtils.blendARGB(normalColor, base, 0.10f)
        }

        private fun configureImagePreviewCorners(usesRoundedCards: Boolean) {
            val density = imageView.resources.displayMetrics.density
            val radius = if (usesRoundedCards) {
                val margin = (imageView.layoutParams as? ViewGroup.MarginLayoutParams)?.leftMargin ?: 0
                ROUNDED_ENTRY_RADIUS_DP * density - margin
            } else {
                DEFAULT_IMAGE_RADIUS_DP * density
            }.coerceAtLeast(0f)
            imageView.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            imageView.clipToOutline = true
        }

        fun setContent(historyEntry: ClipboardHistoryEntry?) {
            itemView.tag = historyEntry?.id
            val imageClip = historyEntry?.text?.let { ClipboardHistoryManager.decodeImageHistoryClip(it) }

            // Adjust StaggeredGridLayoutManager parameters so it stays in one column
            val lp = itemView.layoutParams
            if (lp is StaggeredGridLayoutManager.LayoutParams) {
                lp.isFullSpan = false
            }

            if (imageClip != null) {
                imageView.isGone = false
                imageView.load(imageClip.uri) {
                    allowHardware(false)
                }
                contentView?.text = imageClip.label
                contentView?.let { KeyboardTypeface.applyToTextView(it, imageClip.label, itemTypeFace ?: android.graphics.Typeface.DEFAULT) }

                // Enforce dimension: square for grid, capped banner height for list view
                if (parent.width > 0) {
                    val layoutManager = (parent as? RecyclerView)?.layoutManager
                    val spanCount = when (layoutManager) {
                        is StaggeredGridLayoutManager -> layoutManager.spanCount
                        else -> 2
                    }
                    val totalWidth = parent.width - parent.paddingLeft - parent.paddingRight
                    val itemWidth = totalWidth / spanCount
                    val itemHeight = if (spanCount == 1) {
                        (120 * parent.resources.displayMetrics.density).toInt()
                    } else {
                        itemWidth
                    }
                    if (lp != null) {
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        lp.height = itemHeight
                        itemView.layoutParams = lp
                    }
                } else {
                    if (lp != null) {
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        itemView.layoutParams = lp
                    }
                }
            } else {
                imageView.isGone = true
                imageView.setImageDrawable(null)
                val text = historyEntry?.text?.take(1000) // truncate displayed text for performance reasons
                contentView?.text = text
                contentView?.let { KeyboardTypeface.applyToTextView(it, text, itemTypeFace ?: android.graphics.Typeface.DEFAULT) }

                // Reset text items back to standard wrap_content height
                if (lp != null) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    itemView.layoutParams = lp
                }
            }
        }


        override fun onClick(view: View) {
            keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            clipboardHistoryManager?.toggleClipPinned(view.tag as Long)
            return true
        }
    }
}
