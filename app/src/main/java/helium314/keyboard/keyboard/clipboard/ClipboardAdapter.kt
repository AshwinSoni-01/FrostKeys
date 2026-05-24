// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isGone
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import coil.load
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import androidx.core.graphics.ColorUtils

class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener
) : RecyclerView.Adapter<ClipboardAdapter.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    companion object {
        private const val VIEW_TYPE_TEXT = 0
        private const val VIEW_TYPE_IMAGE = 1
        private const val ROUNDED_ENTRY_RADIUS_DP = 18f
        private const val DEFAULT_IMAGE_RADIUS_DP = 8f
    }

    override fun getItemViewType(position: Int): Int {
        val entry = getItem(position)
        val imageClip = entry?.text?.let { ClipboardHistoryManager.decodeImageHistoryClip(it) }
        return if (imageClip != null) VIEW_TYPE_IMAGE else VIEW_TYPE_TEXT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (viewType == VIEW_TYPE_IMAGE) {
            R.layout.clipboard_entry_image_key
        } else {
            R.layout.clipboard_entry_key
        }
        val view = LayoutInflater.from(parent.context)
                .inflate(layoutId, parent, false)
        return ViewHolder(parent, view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.setContent(getItem(position))
    }

    private fun getItem(position: Int) = clipboardHistoryManager?.getHistoryEntry(position)

    override fun getItemCount() = clipboardHistoryManager?.getHistorySize() ?: 0

    inner class ViewHolder(
            val parent: ViewGroup,
            view: View
    ) : RecyclerView.ViewHolder(view), View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val pinnedIconView: ImageView
        private val imageView: ImageView
        private val contentView: TextView?

        init {
            view.apply {
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
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
            pinnedIconView = view.findViewById<ImageView>(R.id.clipboard_entry_pinned_icon).apply {
                visibility = View.GONE
                setImageResource(pinnedIconResId)
            }
            imageView = view.findViewById(R.id.clipboard_entry_image)
            configureImagePreviewCorners(usesRoundedCards)
            contentView = view.findViewById<TextView>(R.id.clipboard_entry_content)?.apply {
                typeface = itemTypeFace
                setTextColor(itemTextColor)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, itemTextSize)
            }
            clipboardLayoutParams.setItemProperties(view)
            colors.setColor(pinnedIconView, ColorType.CLIPBOARD_PIN)
        }

        private fun createRoundedClipboardEntryBackground(view: View): StateListDrawable {
            val colors = Settings.getValues().mColors
            val normalColor = colors.get(ColorType.KEY_BACKGROUND)
            val pressedColor = pressedEntryColor(normalColor)
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
                imageView.load(imageClip.uri)
                contentView?.text = imageClip.label

                // Enforce perfectly square dimension
                if (parent.width > 0) {
                    val layoutManager = (parent as? RecyclerView)?.layoutManager
                    val spanCount = when (layoutManager) {
                        is StaggeredGridLayoutManager -> layoutManager.spanCount
                        else -> 2
                    }
                    val itemWidth = (parent.width - parent.paddingLeft - parent.paddingRight) / spanCount
                    if (lp != null) {
                        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                        lp.height = itemWidth
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
                contentView?.text = historyEntry?.text?.take(1000) // truncate displayed text for performance reasons

                // Reset text items back to standard wrap_content height
                if (lp != null) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    itemView.layoutParams = lp
                }
            }
            pinnedIconView.visibility = if (historyEntry?.isPinned == true) View.VISIBLE else View.GONE
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
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
