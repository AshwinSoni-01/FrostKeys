package helium314.keyboard.keyboard.resize

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils

class KeyboardResizeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var mainKeyboardFrame: View? = null
    private var keyboardWrapper: View? = null
    private var keyboardSwitcher: KeyboardSwitcher? = null

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * resources.displayMetrics.density
    }

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val buttonBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 14 * resources.displayMetrics.density
        typeface = Typeface.DEFAULT_BOLD
    }

    private val accentColor: Int by lazy { resolveAccentColor() }

    private val handleWidth = 40 * resources.displayMetrics.density
    private val handleHeight = 6 * resources.displayMetrics.density
    private val handleTouchWidth = 96 * resources.displayMetrics.density
    private val handleTouchHeight = 48 * resources.displayMetrics.density

    private val buttonHeight = 48 * resources.displayMetrics.density
    private val buttonMinWidth = 112 * resources.displayMetrics.density
    private val buttonPillRadius = 24 * resources.displayMetrics.density
    private val buttonGap = 24 * resources.displayMetrics.density

    private val topHandleRect = RectF()
    private val bottomHandleRect = RectF()
    private val resetBtnRect = RectF()
    private val doneBtnRect = RectF()

    private var activeHandle: HandleType = HandleType.NONE
    private var currentScale = 1.0f
    private var dragStartY = 0f
    private var dragStartScale = 1.0f
    private var dragDefaultHeight = 1.0f
    private var dragStartContentsHeight = 0
    private var isDragging = false
    // The target height during drag, used only to size the overlay itself.
    // The keyboard content stays static until finger release.
    private var dragTargetHeight = -1

    private val resetIcon: Drawable? by lazy { ContextCompat.getDrawable(context, R.drawable.ic_undo_rounded)?.mutate() }
    private val doneIcon: Drawable? by lazy { ContextCompat.getDrawable(context, R.drawable.sym_keyboard_done_rounded)?.mutate() }

    private enum class HandleType { NONE, TOP, BOTTOM, RESET, DONE }

    fun init(mainKeyboardFrame: View, switcher: KeyboardSwitcher) {
        this.mainKeyboardFrame = mainKeyboardFrame
        this.keyboardWrapper = mainKeyboardFrame.findViewById(R.id.keyboard_view_wrapper)
        this.keyboardSwitcher = switcher
        outlinePaint.color = ColorUtils.setAlphaComponent(accentColor, 210)
        handlePaint.color = ColorUtils.setAlphaComponent(accentColor, 230)
        buttonBgPaint.color = ColorUtils.setAlphaComponent(accentColor, 255)
        scrimPaint.color = ColorUtils.setAlphaComponent(accentColor, 20)
        textPaint.color = if (ColorUtils.calculateLuminance(accentColor) > 0.5) Color.BLACK else Color.WHITE
        resetIcon?.setTint(textPaint.color)
        doneIcon?.setTint(textPaint.color)
    }

    fun show() {
        visibility = VISIBLE
        currentScale = Settings.getInstance().current.mKeyboardHeightScale
        dragTargetHeight = -1
        invalidate()
    }

    fun hide() {
        visibility = GONE
        activeHandle = HandleType.NONE
        dragTargetHeight = -1
    }

    override fun onDraw(canvas: Canvas) {
        val left = 0f
        val top = 0f
        val right = width.toFloat()
        val bottom = height.toFloat()
        val cornerRadius = Settings.getInstance().current.mKeyboardCornerRadiusDp * resources.displayMetrics.density

        // Draw background and outline clipped to rounded corners
        // The view is positioned above the navigation bar by its parent RoundedKeyboardFrameView
        canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, scrimPaint)
        canvas.drawRoundRect(left, top, right, bottom, cornerRadius, cornerRadius, outlinePaint)

        // Handles
        val centerX = (left + right) / 2f
        topHandleRect.set(centerX - handleWidth / 2f, top + 8 * resources.displayMetrics.density, centerX + handleWidth / 2f, top + 8 * resources.displayMetrics.density + handleHeight)
        canvas.drawRoundRect(topHandleRect, handleHeight / 2f, handleHeight / 2f, handlePaint)

        bottomHandleRect.set(centerX - handleWidth / 2f, bottom - 8 * resources.displayMetrics.density - handleHeight, centerX + handleWidth / 2f, bottom - 8 * resources.displayMetrics.density)
        canvas.drawRoundRect(bottomHandleRect, handleHeight / 2f, handleHeight / 2f, handlePaint)

        // Buttons
        val centerY = (top + bottom) / 2f
        val resetText = context.getString(R.string.button_reset)
        val doneText = context.getString(R.string.label_done_key)

        val resetTextWidth = textPaint.measureText(resetText)
        val doneTextWidth = textPaint.measureText(doneText)

        val iconAndGapWidth = 34 * resources.displayMetrics.density
        val resetWidth = maxOf(buttonMinWidth, resetTextWidth + iconAndGapWidth + 48 * resources.displayMetrics.density)
        val doneWidth = maxOf(buttonMinWidth, doneTextWidth + iconAndGapWidth + 48 * resources.displayMetrics.density)

        val totalButtonsWidth = resetWidth + buttonGap + doneWidth
        val startX = centerX - totalButtonsWidth / 2f

        resetBtnRect.set(startX, centerY - buttonHeight / 2f, startX + resetWidth, centerY + buttonHeight / 2f)
        drawButton(canvas, resetBtnRect, resetIcon, resetText)

        val doneStartX = startX + resetWidth + buttonGap
        doneBtnRect.set(doneStartX, centerY - buttonHeight / 2f, doneStartX + doneWidth, centerY + buttonHeight / 2f)
        drawButton(canvas, doneBtnRect, doneIcon, doneText)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        val rawY = event.rawY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = when {
                    isPointInHandle(x, y, topHandleRect) -> HandleType.TOP
                    isPointInHandle(x, y, bottomHandleRect) -> HandleType.BOTTOM
                    resetBtnRect.contains(x, y) -> HandleType.RESET
                    doneBtnRect.contains(x, y) -> HandleType.DONE
                    else -> HandleType.NONE
                }
                if (activeHandle != HandleType.NONE) {
                    dragStartY = rawY
                    dragStartScale = currentScale

                    // Capture actual pixel height of the frame at the start of the drag
                    dragStartContentsHeight = mainKeyboardFrame?.height ?: 0

                    dragDefaultHeight = ResourceUtils.getDefaultKeyboardHeight(
                        resources,
                        Settings.getValues().mShowsNumberRow
                    ).toFloat().coerceAtLeast(1.0f)
                    isDragging = (activeHandle == HandleType.TOP || activeHandle == HandleType.BOTTOM)
                    performFeedback(HapticEvent.KEY_PRESS)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val deltaPx = rawY - dragStartY

                    // The keyboard is anchored at the bottom.
                    // Dragging the TOP handle UP (negative deltaPx) increases height.
                    // Dragging the BOTTOM handle DOWN (positive deltaPx) increases height.
                    val deltaHeight = if (activeHandle == HandleType.TOP) -deltaPx else deltaPx

                    // Calculate what the new scale would be based on this pixel change
                    val newScale = dragStartScale + (deltaHeight / dragDefaultHeight)
                    val clampedScale = newScale.coerceIn(Settings.KEYBOARD_HEIGHT_SCALE_MIN, Settings.KEYBOARD_HEIGHT_SCALE_MAX)

                    if (kotlin.math.abs(clampedScale - currentScale) > 0.0001f) {
                        currentScale = clampedScale
                        // Calculate visual height for the overlay based on the new scale.
                        // This ensures it perfectly matches what the keyboard will be after reload.
                        val visualHeightDelta = (currentScale - dragStartScale) * dragDefaultHeight
                        dragTargetHeight = (dragStartContentsHeight + visualHeightDelta).toInt()

                        requestLayout()
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                if (activeHandle == HandleType.RESET) {
                    Settings.getInstance().resetHeightScale()
                    currentScale = Settings.getInstance().current.mKeyboardHeightScale
                    keyboardSwitcher?.onKeyboardHeightScaleChanged()
                    invalidate()
                    performFeedback(HapticEvent.KEY_PRESS)
                } else if (activeHandle == HandleType.DONE) {
                    hide()
                    performFeedback(HapticEvent.KEY_PRESS)
                } else if (isDragging) {
                    // Write the new scale and rebuild the keyboard on release.
                    // The keyboard content was static during the drag; now it
                    // reloads to match the final size.
                    dragTargetHeight = -1
                    Settings.getInstance().writeHeightScale(currentScale)
                    keyboardSwitcher?.onKeyboardHeightScaleChanged()
                    invalidate()
                }
                activeHandle = HandleType.NONE
                isDragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    dragTargetHeight = -1
                    requestLayout()
                    invalidate()
                }
                activeHandle = HandleType.NONE
                isDragging = false
            }
        }
        return true
    }


    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // During a drag, use the target height so the overlay tracks the finger.
        // The keyboard content itself is not resized until finger release.
        if (dragTargetHeight > 0) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dragTargetHeight)
            return
        }
        // Match the main keyboard frame's height to cover the entire keyboard area.
        val frame = mainKeyboardFrame
        if (frame != null && frame.visibility != GONE) {
            val h = frame.measuredHeight
            if (h > 0) {
                setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
                return
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, icon: Drawable?, text: String) {
        canvas.drawRoundRect(rect, buttonPillRadius, buttonPillRadius, buttonBgPaint)

        val iconSize = 20 * resources.displayMetrics.density
        val iconLeft = rect.left + 24 * resources.displayMetrics.density
        val iconTop = rect.centerY() - iconSize / 2f
        icon?.setBounds(iconLeft.toInt(), iconTop.toInt(), (iconLeft + iconSize).toInt(), (iconTop + iconSize).toInt())
        icon?.draw(canvas)

        val oldAlign = textPaint.textAlign
        textPaint.textAlign = Paint.Align.LEFT
        val textY = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, iconLeft + iconSize + 14 * resources.displayMetrics.density, textY, textPaint)
        textPaint.textAlign = oldAlign
    }

    private fun resolveAccentColor(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            return ContextCompat.getColor(
                context,
                if (isNight) android.R.color.system_accent1_100 else android.R.color.system_accent1_200
            )
        }
        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)
        return typedValue.data
    }

    private fun isPointInHandle(x: Float, y: Float, handleRect: RectF): Boolean {
        val touchRect = RectF(
            handleRect.centerX() - handleTouchWidth / 2f,
            handleRect.centerY() - handleTouchHeight / 2f,
            handleRect.centerX() + handleTouchWidth / 2f,
            handleRect.centerY() + handleTouchHeight / 2f
        )
        return touchRect.contains(x, y)
    }

    private fun performFeedback(event: HapticEvent) {
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, event)
    }
}
