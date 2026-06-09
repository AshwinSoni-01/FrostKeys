// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.load
import coil.dispose
import coil.size.Scale
import coil.memory.MemoryCache
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.KlipyHistoryDao

class KlipyAdapter(
    private var items: List<KlipyHistoryDao.KlipyItem>,
    private val isStickersMode: Boolean = false,
    private val forceSquareCells: Boolean = false,
    private val onItemLongClick: ((KlipyHistoryDao.KlipyItem, View) -> Boolean)? = null,
    private val onItemClick: (KlipyHistoryDao.KlipyItem) -> Unit
) : RecyclerView.Adapter<KlipyAdapter.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_MEDIA = 1
        private const val TAP_SCALE = 0.97f
        private const val TAP_ALPHA = 0.90f
        private const val TAP_PRESS_DURATION_MS = 70L
        private const val TAP_RELEASE_DURATION_MS = 120L
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long {
        return items[position].id.hashCode().toLong()
    }

    override fun getItemViewType(position: Int): Int = VIEW_TYPE_MEDIA

    class ViewHolder(view: View, val parent: ViewGroup, val isStickersMode: Boolean) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.klipyImage)
        var pendingLoadRunnable: Runnable? = null
        var releasedWhileDetached: Boolean = false

        init {
            val radius = 8f * view.context.resources.displayMetrics.density
            if (isStickersMode) {
                // Strictly unchanged logic for Stickers: round the corners of imageView only
                imageView.clipToOutline = true
                imageView.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
            } else {
                // Isolated fix for GIFs: round the corners of parent FrameLayout (view/itemView)
                view.clipToOutline = true
                view.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, v.width, v.height, radius)
                    }
                }
                // Double safety: also make sure the child imageView has clipToOutline
                imageView.clipToOutline = true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.klipy_item, parent, false)

        // Decorative semi-transparent white rounded background for sticker grid only
        if (isStickersMode || forceSquareCells) {
            val density = parent.context.resources.displayMetrics.density

            // Tighter grid spacing for stickers and pinned rows.
            val margin = (2f * density).toInt()
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.setMargins(margin, margin, margin, margin)
        }

        if (isStickersMode) {
            val density = parent.context.resources.displayMetrics.density
            val cornerRadius = 8f * density
            val bgDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadii = floatArrayOf(
                    cornerRadius, cornerRadius, cornerRadius, cornerRadius,
                    cornerRadius, cornerRadius, cornerRadius, cornerRadius
                )
                setColor(0xFFFFFFFF.toInt()) // fully opaque white (testing)
            }
            view.background = bgDrawable
            val pad = (1f * density).toInt()
            view.setPadding(pad, pad, pad, pad)
            view.findViewById<ImageView>(R.id.klipyImage).scaleType = ImageView.ScaleType.FIT_CENTER
        } else if (forceSquareCells) {
            view.findViewById<ImageView>(R.id.klipyImage).scaleType = ImageView.ScaleType.CENTER_CROP
        }

        return ViewHolder(view, parent, isStickersMode)
    }

    private var imageLoader: ImageLoader? = null
    private var areAnimationsRunning = true
    private var isImageLoadingEnabled = true
    private var needsReloadAfterRelease = false
    private val activeViews = mutableSetOf<ViewHolder>()

    fun setAnimationsRunning(running: Boolean) {
        if (areAnimationsRunning == running) return
        areAnimationsRunning = running
        for (holder in activeViews) {
            val drawable = holder.imageView.drawable
            if (drawable is Animatable) {
                if (running) {
                    drawable.start()
                } else {
                    drawable.stop()
                }
            }
        }
    }

    fun releaseVisibleResources() {
        areAnimationsRunning = false
        isImageLoadingEnabled = false
        needsReloadAfterRelease = items.isNotEmpty()
        for (holder in activeViews.toList()) {
            releaseHolder(holder, clearImage = true)
        }
    }

    fun resumeVisibleResources() {
        isImageLoadingEnabled = true
        if (needsReloadAfterRelease) {
            needsReloadAfterRelease = false
            areAnimationsRunning = true
            for (holder in activeViews.toList()) {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notifyItemChanged(position)
                }
            }
        } else {
            setAnimationsRunning(true)
        }
    }

    private fun releaseHolder(holder: ViewHolder, clearImage: Boolean, disposeRequest: Boolean = true) {
        holder.pendingLoadRunnable?.let { holder.itemView.removeCallbacks(it) }
        holder.pendingLoadRunnable = null
        val drawable = holder.imageView.drawable
        (drawable as? Animatable)?.stop()
        if (disposeRequest) {
            holder.imageView.dispose()
        }
        if (clearImage) {
            holder.imageView.setImageDrawable(null)
        }
    }

    private fun getImageLoader(context: android.content.Context): ImageLoader {
        return imageLoader ?: synchronized(this) {
            imageLoader ?: ImageLoader.Builder(context.applicationContext)
                .components {
                    add(GifDecoder.Factory())
                }
                .memoryCache {
                    MemoryCache.Builder(context.applicationContext)
                        .maxSizePercent(0.10)
                        .build()
                }
                .build().also { imageLoader = it }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.releasedWhileDetached = false

        // Prevent StaggeredGridLayoutManager from making items full-span
        val lp = holder.itemView.layoutParams
        if (lp is StaggeredGridLayoutManager.LayoutParams) {
            lp.isFullSpan = false
        }

        var targetWidth = 0
        var targetHeight = 0
        var hasDimensions = false

        val parent = holder.parent
        if (parent.width > 0) {
            val layoutManager = (parent as? RecyclerView)?.layoutManager
            val spanCount = when (layoutManager) {
                is GridLayoutManager -> layoutManager.spanCount
                is StaggeredGridLayoutManager -> layoutManager.spanCount
                else -> 1
            }
            val itemWidth = (parent.width - parent.paddingLeft - parent.paddingRight) / spanCount
            val widthRatio = if (item.width > 0) item.height.toFloat() / item.width.toFloat() else 1f
            targetHeight = if (isStickersMode || forceSquareCells) {
                // Subtract horizontal margins so the cell is visually square
                // (MATCH_PARENT width = itemWidth - margins, so height must match)
                val marginLp = holder.itemView.layoutParams as? ViewGroup.MarginLayoutParams
                val hMargins = (marginLp?.leftMargin ?: 0) + (marginLp?.rightMargin ?: 0)
                (itemWidth - hMargins).coerceAtLeast(50)
            } else {
                (itemWidth * widthRatio).toInt().coerceIn(50, itemWidth * 3)
            }
            targetWidth = itemWidth
            hasDimensions = true

            val itemLp = holder.itemView.layoutParams
            itemLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            itemLp.height = targetHeight
            holder.itemView.layoutParams = itemLp

            val imageLayoutParams = holder.imageView.layoutParams
            imageLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            imageLayoutParams.height = if (isStickersMode || forceSquareCells) targetHeight else ViewGroup.LayoutParams.MATCH_PARENT
            holder.imageView.layoutParams = imageLayoutParams
        } else {
            val itemLp = holder.itemView.layoutParams
            itemLp.width = ViewGroup.LayoutParams.MATCH_PARENT
            itemLp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            holder.itemView.layoutParams = itemLp

            val imageLayoutParams = holder.imageView.layoutParams
            imageLayoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            imageLayoutParams.height = if (isStickersMode || forceSquareCells) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
            holder.imageView.layoutParams = imageLayoutParams
        }

        // Cancel any pending load runnable to avoid memory leaks or duplicate loads
        holder.pendingLoadRunnable?.let { holder.itemView.removeCallbacks(it) }

        // Setup our custom SubtleCircularProgressDrawable as a premium, lightweight placeholder
        val placeholder = SubtleCircularProgressDrawable().apply {
            if (areAnimationsRunning) start()
        }
        holder.imageView.setImageDrawable(placeholder)

        if (!isImageLoadingEnabled) {
            setItemListeners(holder.itemView, item)
            return
        }

        val loader = getImageLoader(holder.itemView.context)
        val imageUrl = item.previewUrl ?: item.url

        val runnable = Runnable {
            holder.pendingLoadRunnable = null
            if (holder.bindingAdapterPosition == position) {
                holder.imageView.load(imageUrl, loader) {
                    scale(Scale.FIT)
                    if (hasDimensions && targetWidth > 0 && targetHeight > 0) {
                        size(targetWidth, targetHeight)
                    }
                    listener(
                        onSuccess = { _, result ->
                            val drawable = result.drawable
                            if (drawable is Animatable) {
                                if (areAnimationsRunning) {
                                    drawable.start()
                                } else {
                                    drawable.stop()
                                }
                            }
                        }
                    )
                }
            }
        }
        holder.pendingLoadRunnable = runnable

        // Use a longer debounce delay when flinging/settling to prevent massive CPU decode spikes
        val recyclerView = parent as? RecyclerView
        val isFlinging = recyclerView?.scrollState == RecyclerView.SCROLL_STATE_SETTLING
        val delay = if (isFlinging) 150L else 40L

        holder.itemView.postDelayed(runnable, delay)

        setItemListeners(holder.itemView, item)
    }

    private fun setItemListeners(view: View, item: KlipyHistoryDao.KlipyItem) {
        installTapAnimation(view)
        view.setOnClickListener {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
                KeyCode.NOT_SPECIFIED,
                it,
                HapticEvent.KEY_PRESS
            )
            onItemClick(item)
        }
        view.setOnLongClickListener(
            if (onItemLongClick == null) {
                null
            } else {
                View.OnLongClickListener { touchedView ->
                    onItemLongClick.invoke(item, touchedView)
                }
            }
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

    override fun getItemCount() = items.size

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        activeViews.add(holder)
        if (holder.releasedWhileDetached && isImageLoadingEnabled) {
            holder.releasedWhileDetached = false
            holder.itemView.post {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notifyItemChanged(position)
                }
            }
            return
        }
        holder.releasedWhileDetached = false
        val drawable = holder.imageView.drawable
        if (areAnimationsRunning && drawable is Animatable) {
            drawable.start()
        }
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        activeViews.remove(holder)
        holder.releasedWhileDetached = true
        releaseHolder(holder, clearImage = true, disposeRequest = true)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        activeViews.remove(holder)
        releaseHolder(holder, clearImage = true)
    }

    fun updateItems(newItems: List<KlipyHistoryDao.KlipyItem>) {
        val oldItems = items
        if (oldItems == newItems) return
        items = newItems
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos].id == newItems[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                oldItems[oldPos] == newItems[newPos]
        })
        diffResult.dispatchUpdatesTo(this)
    }
}

class SubtleCircularProgressDrawable : Drawable(), Animatable, Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = 0x66888888 // mid-tone grey with 40% opacity (looks excellent on light/dark themes)
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var angle = 0f
    private var isRunning = false

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        val size = Math.min(bounds.width(), bounds.height()) * 0.35f
        if (size <= 0) return

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)

        canvas.save()
        canvas.rotate(angle, cx, cy)
        canvas.drawArc(rect, 0f, 270f, false, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    @Suppress("DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    // Animatable implementation
    override fun start() {
        if (!isRunning) {
            isRunning = true
            scheduleSelf(this, SystemClock.uptimeMillis() + 16)
        }
    }

    override fun stop() {
        if (isRunning) {
            isRunning = false
            unscheduleSelf(this)
        }
    }

    override fun isRunning(): Boolean = isRunning

    override fun run() {
        if (isRunning) {
            angle = (angle + 6f) % 360f // Rotate 6 degrees every frame (~60 FPS)
            invalidateSelf()
            scheduleSelf(this, SystemClock.uptimeMillis() + 16)
        }
    }
}
