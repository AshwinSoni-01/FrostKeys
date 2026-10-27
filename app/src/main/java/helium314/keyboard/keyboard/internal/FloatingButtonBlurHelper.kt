package helium314.keyboard.keyboard.internal

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.dpToPx

object FloatingButtonBlurHelper {

    fun createBackgroundDrawable(
        context: Context,
        colors: Colors,
        cornerRadius: Float
    ): RippleDrawable {
        val baseColor = colors.get(ColorType.SPECIAL_KEY_BACKGROUND)
        val backgroundColor = ColorUtils.setAlphaComponent(baseColor, 115)

        val baseShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(backgroundColor)
        }

        val maskShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
            setColor(Color.BLACK)
        }

        val iconColor = colors.get(ColorType.FUNCTIONAL_KEY_TEXT)
        val rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(iconColor, 0x33)
        )
        return RippleDrawable(rippleColor, baseShape, maskShape)
    }

    fun findUnderlyingView(button: View): View? {
        val parentGroup = button.parent as? ViewGroup ?: return null
        for (i in 0 until parentGroup.childCount) {
            val child = parentGroup.getChildAt(i)
            if (child === button) continue
            if (child.visibility != View.VISIBLE) continue
            if (child is ViewPager2) {
                val innerRv = child.getChildAt(0) as? RecyclerView
                if (innerRv != null) {
                    val currentItem = child.currentItem
                    val pageView = innerRv.findViewHolderForAdapterPosition(currentItem)?.itemView
                        ?: (0 until innerRv.childCount)
                            .map { innerRv.getChildAt(it) }
                            .firstOrNull { it.visibility == View.VISIBLE && it.left < child.width && it.right > 0 }
                    if (pageView != null) {
                        val nestedRv = findVisibleRecyclerView(pageView)
                        if (nestedRv != null) return nestedRv
                    }
                }
                return innerRv ?: child
            } else if (child is RecyclerView) {
                return child
            }
        }
        return null
    }

    private fun findVisibleRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup && view.visibility == View.VISIBLE) {
            for (i in 0 until view.childCount) {
                val found = findVisibleRecyclerView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    fun attachScrollListeners(view: View, listener: RecyclerView.OnScrollListener): List<RecyclerView> {
        val parentGroup = view.parent as? ViewGroup ?: return emptyList()
        val attached = mutableListOf<RecyclerView>()
        fun attachRecursive(v: View) {
            if (v === view) return
            if (v is RecyclerView) {
                v.removeOnScrollListener(listener)
                v.addOnScrollListener(listener)
                attached.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    attachRecursive(v.getChildAt(i))
                }
            }
        }
        for (i in 0 until parentGroup.childCount) {
            attachRecursive(parentGroup.getChildAt(i))
        }
        return attached
    }

    class BlurBackdropRenderer {
        private var blurBitmap: Bitmap? = null
        private var bitmapCanvas: Canvas? = null
        private var renderNodeHolder: Any? = null
        private var currentBlurRadius = 0f
        var backgroundColor: Int = Color.TRANSPARENT

        fun drawBlurBackdrop(
            button: View,
            canvas: Canvas,
            cornerRadius: Float
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            if (!canvas.isHardwareAccelerated) return
            if (button.width <= 0 || button.height <= 0) return

            val sibling = findUnderlyingView(button) ?: return

            try {
                Api31Impl.draw(button, sibling, canvas, cornerRadius, this)
            } catch (_: Throwable) {
            }
        }

        fun clear() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    (renderNodeHolder as? RenderNode)?.discardDisplayList()
                } catch (_: Throwable) {
                }
            }
            renderNodeHolder = null
            currentBlurRadius = 0f
            blurBitmap?.recycle()
            blurBitmap = null
            bitmapCanvas = null
        }

        internal fun getOrCreateBitmap(width: Int, height: Int): Pair<Bitmap, Canvas>? {
            var bm = blurBitmap
            var c = bitmapCanvas
            if (bm == null || bm.width != width || bm.height != height || bm.isRecycled || c == null) {
                bm?.recycle()
                try {
                    bm = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    c = Canvas(bm)
                    blurBitmap = bm
                    bitmapCanvas = c
                } catch (_: Throwable) {
                    return null
                }
            }
            return Pair(bm, c)
        }

        internal fun getOrCreateRenderNode(blurRadius: Float): RenderNode? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
            var node = renderNodeHolder as? RenderNode
            if (node == null || currentBlurRadius != blurRadius) {
                try {
                    node?.discardDisplayList()
                } catch (_: Throwable) {
                }
                node = RenderNode("FloatingButtonBlur").apply {
                    setRenderEffect(
                        RenderEffect.createBlurEffect(
                            blurRadius,
                            blurRadius,
                            Shader.TileMode.CLAMP
                        )
                    )
                }
                currentBlurRadius = blurRadius
                renderNodeHolder = node
            }
            return node
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31Impl {
        private val blurPath = Path()
        private val blurRect = RectF()
        private val buttonLoc = IntArray(2)
        private val siblingLoc = IntArray(2)

        fun draw(
            button: View,
            sibling: View,
            canvas: Canvas,
            cornerRadius: Float,
            renderer: BlurBackdropRenderer
        ) {
            val density = button.resources.displayMetrics.density
            val pad = (24f * density).toInt()
            val blurRadius = (24f * density).coerceAtLeast(40f)

            val snapshotWidth = button.width + 2 * pad
            val snapshotHeight = button.height + 2 * pad

            val (bitmap, bCanvas) = renderer.getOrCreateBitmap(snapshotWidth, snapshotHeight) ?: return
            val node = renderer.getOrCreateRenderNode(blurRadius) ?: return

            // Compute precise relative coordinates between button and underlying sibling
            button.getLocationInWindow(buttonLoc)
            sibling.getLocationInWindow(siblingLoc)
            val buttonLeftInSibling = if (buttonLoc[0] != 0 || buttonLoc[1] != 0 || siblingLoc[0] != 0 || siblingLoc[1] != 0) {
                buttonLoc[0] - siblingLoc[0]
            } else {
                button.left - sibling.left
            }
            val buttonTopInSibling = if (buttonLoc[0] != 0 || buttonLoc[1] != 0 || siblingLoc[0] != 0 || siblingLoc[1] != 0) {
                buttonLoc[1] - siblingLoc[1]
            } else {
                button.top - sibling.top
            }

            val cropLeft = buttonLeftInSibling - pad
            val cropTop = buttonTopInSibling - pad

            // 1. Fill with solid background so text/emojis are completely obscured rather than transparent
            val solidBg = if (renderer.backgroundColor != Color.TRANSPARENT) {
                ColorUtils.setAlphaComponent(renderer.backgroundColor, 255)
            } else {
                try {
                    ColorUtils.setAlphaComponent(
                        Settings.getValues().mColors.get(ColorType.MAIN_BACKGROUND),
                        255
                    )
                } catch (_: Throwable) {
                    Color.BLACK
                }
            }
            bCanvas.drawColor(solidBg)

            // 2. Snapshot the padded region of the underlying view
            val bSave = bCanvas.save()
            try {
                bCanvas.clipRect(0, 0, snapshotWidth, snapshotHeight)
                bCanvas.translate(-cropLeft.toFloat(), -cropTop.toFloat())
                try {
                    sibling.draw(bCanvas)
                } catch (_: Throwable) {
                }
            } finally {
                bCanvas.restoreToCount(bSave)
            }

            // 3. Record snapshot into leaf RenderNode to apply GPU BlurEffect
            node.setPosition(0, 0, snapshotWidth, snapshotHeight)
            val recordingCanvas = node.beginRecording(snapshotWidth, snapshotHeight)
            try {
                recordingCanvas.drawBitmap(bitmap, 0f, 0f, null)
            } finally {
                node.endRecording()
            }

            // 4. Render blurred node onto destination canvas, strictly clipped to rounded-rect outline
            val cSave = canvas.save()
            try {
                blurPath.reset()
                blurRect.set(0f, 0f, button.width.toFloat(), button.height.toFloat())
                blurPath.addRoundRect(blurRect, cornerRadius, cornerRadius, Path.Direction.CW)
                canvas.clipPath(blurPath)
                canvas.translate(-pad.toFloat(), -pad.toFloat())
                canvas.drawRenderNode(node)
            } finally {
                canvas.restoreToCount(cSave)
            }
        }
    }
}
