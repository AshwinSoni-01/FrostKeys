package helium314.keyboard.keyboard.internal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.latin.R
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.utils.dpToPx

class FloatingDeleteButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ImageButton(context, attrs, defStyleAttr) {

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var keyboardActionListener: KeyboardActionListener? = null

    private var mStartX = 0f
    private var mInHorizontalSwipe = false
    private val stepWidth = 12.dpToPx(resources).toFloat()
    private var repeatCount = 0
    private var isPressedState = false
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

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val listener = keyboardActionListener
            if (!mInHorizontalSwipe && isPressedState && listener != null) {
                repeatCount++
                listener.onPressKey(KeyCode.DELETE, repeatCount, true, HapticEvent.KEY_REPEAT)
                listener.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, true)
                listener.onReleaseKey(KeyCode.DELETE, false)
                repeatHandler.postDelayed(this, 50)
            }
        }
    }

    init {
        scaleType = ScaleType.CENTER
        contentDescription = context.getString(R.string.spoken_description_delete)
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
        isLongClickable = false
    }

    fun init(listener: KeyboardActionListener?, colors: Colors) {
        this.keyboardActionListener = listener
        applyTheme(colors)
    }

    fun setKeyboardActionListener(listener: KeyboardActionListener?) {
        this.keyboardActionListener = listener
    }

    fun applyTheme(colors: Colors) {
        blurRenderer.backgroundColor = colors.get(ColorType.MAIN_BACKGROUND)
        background = FloatingButtonBlurHelper.createBackgroundDrawable(context, colors, cornerRadius)

        val iconColor = colors.get(ColorType.FUNCTIONAL_KEY_TEXT)
        val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_DELETE_KEY, context)
            ?: ContextCompat.getDrawable(context, R.drawable.sym_keyboard_delete_lxx)?.mutate()
        if (icon != null) {
            icon.setTint(iconColor)
            setImageDrawable(icon)
        }
        invalidate()
    }

    override fun draw(canvas: Canvas) {
        val underlying = FloatingButtonBlurHelper.findUnderlyingView(this)
        if (underlying is RecyclerView && !attachedRecyclerViews.contains(underlying)) {
            underlying.removeOnScrollListener(scrollListener)
            underlying.addOnScrollListener(scrollListener)
            attachedRecyclerViews.add(underlying)
        }
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
        repeatHandler.removeCallbacks(repeatRunnable)
        isPressedState = false
        isPressed = false
        if (mInHorizontalSwipe) {
            mInHorizontalSwipe = false
            keyboardActionListener?.onUpWithDeletePointerActive()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) {
            detachScrollListeners()
            blurRenderer.clear()
            repeatHandler.removeCallbacks(repeatRunnable)
            isPressedState = false
            isPressed = false
            if (mInHorizontalSwipe) {
                mInHorizontalSwipe = false
                keyboardActionListener?.onUpWithDeletePointerActive()
            }
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

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = keyboardActionListener ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                mStartX = event.x
                mInHorizontalSwipe = false
                isPressedState = true
                isPressed = true
                repeatCount = 0

                listener.onPressKey(KeyCode.DELETE, 0, true, HapticEvent.KEY_PRESS)
                listener.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                listener.onReleaseKey(KeyCode.DELETE, false)

                repeatHandler.postDelayed(repeatRunnable, 400)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPressedState) {
                    val dX = event.x - mStartX
                    val steps = (dX / stepWidth).toInt()
                    if (steps != 0) {
                        if (!mInHorizontalSwipe) {
                            repeatHandler.removeCallbacks(repeatRunnable)
                            mInHorizontalSwipe = true
                        }
                        mStartX += steps * stepWidth
                        listener.onMoveDeletePointer(steps)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                repeatHandler.removeCallbacks(repeatRunnable)
                isPressed = false
                isPressedState = false
                if (mInHorizontalSwipe) {
                    mInHorizontalSwipe = false
                    listener.onUpWithDeletePointerActive()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
