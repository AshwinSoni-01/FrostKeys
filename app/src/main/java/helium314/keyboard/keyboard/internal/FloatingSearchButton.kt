package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.utils.dpToPx

class FloatingSearchButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageButton(context, attrs, defStyleAttr) {

    private val cornerRadius = 16.dpToPx(resources).toFloat()
    private val blurRenderer = FloatingButtonBlurHelper.BlurBackdropRenderer()
    private val attachedRecyclerViews = mutableListOf<RecyclerView>()
    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dx != 0 || dy != 0) {
                invalidate()
            }
        }
    }

    init {
        scaleType = ScaleType.CENTER
        contentDescription = context.getString(R.string.spoken_description_search)
        elevation = 6.dpToPx(resources).toFloat()
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                outline.alpha = 0.6f
            }
        }
        clipToOutline = true
        isClickable = true
        isFocusable = true
    }

    fun applyTheme(colors: Colors) {
        blurRenderer.backgroundColor = colors.get(ColorType.MAIN_BACKGROUND)
        background = FloatingButtonBlurHelper.createBackgroundDrawable(context, colors, cornerRadius)

        val iconColor = colors.get(ColorType.FUNCTIONAL_KEY_TEXT)
        val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_SEARCH_KEY, context)
            ?: ContextCompat.getDrawable(context, R.drawable.sym_keyboard_search_lxx)?.mutate()
        if (icon != null) {
            icon.setTint(iconColor)
            setImageDrawable(icon)
        }
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        blurRenderer.drawBlurBackdrop(this, canvas, cornerRadius)
        super.draw(canvas)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attachScrollListeners()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (attachedRecyclerViews.isEmpty()) {
            attachScrollListeners()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        detachScrollListeners()
        blurRenderer.clear()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) {
            detachScrollListeners()
            blurRenderer.clear()
        } else if (isAttachedToWindow) {
            attachScrollListeners()
            invalidate()
        }
    }

    private fun attachScrollListeners() {
        detachScrollListeners()
        attachedRecyclerViews.addAll(FloatingButtonBlurHelper.attachScrollListeners(this, scrollListener))
    }

    private fun detachScrollListeners() {
        attachedRecyclerViews.forEach { it.removeOnScrollListener(scrollListener) }
        attachedRecyclerViews.clear()
    }

    override fun performClick(): Boolean {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
            KeyCode.NOT_SPECIFIED,
            this,
            HapticEvent.KEY_PRESS
        )
        return super.performClick()
    }
}
