package helium314.keyboard.keyboard.internal

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.util.AttributeSet
import android.os.SystemClock
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.viewpager2.widget.ViewPager2
import androidx.recyclerview.widget.RecyclerView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.event.HapticEvent
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

@SuppressLint("ViewConstructor")
class AiWritingToolsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    lateinit var keyboardActionListener: KeyboardActionListener
    private var inputConnection: InputConnection? = null
    private var userText: String = ""
    private var aiVariations: List<String> = emptyList()

    private lateinit var viewPager: ViewPager2
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var copyButton: Button
    private var glowAnimator: ObjectAnimator? = null
    private var glowShiftAnimator: ValueAnimator? = null
    private lateinit var glowBorder: View
    private var selectedTool: String? = null
    private var isGenerating = false
    private var generationSequence = 0L

    companion object {
        private const val DELIMITER = "---VAR---"
        private const val GLOW_FRAME_INTERVAL_MS = 33L
        private val AI_PROMPTS = mapOf(
            "Fix Grammar" to "Fix all grammar, punctuation, and spelling errors in the following text. Keep the original style.",
            "Proofread" to "Carefully proofread the following text for grammar, spelling, clarity, and flow. Suggest improvements while keeping the original intent.",
            "Rewrite" to "Rewrite the following text to make it more engaging and concise.",
            "Professional" to "Rewrite the following text in a professional, formal business tone.",
            "Friendly" to "Rewrite the following text in a warm, friendly, and informal tone.",
            "Old English" to "Rewrite the following text in an Old English/Shakespearean style. Use archaic vocabulary and grammar where appropriate (e.g., thou, thee, thy, -eth endings).",
            "Smart Reply" to "Based on the following received message, suggest three short, appropriate replies."
        )
    }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.ai_writing_tools_view, this, true)
        setupUI()
    }

    private inner class AiOutputAdapter : RecyclerView.Adapter<AiOutputAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.tv_ai_output)
            val useButton: Button = view.findViewById(R.id.btn_use_this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ai_output, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            var text = aiVariations.getOrNull(position) ?: ""

            // Empty state restoration logic - now applies to all pages
            val isInitial = aiVariations.all { it.isBlank() }
            if (isInitial) {
                text = if (userText.isBlank()) {
                    "Please write something first to use AI writing tools."
                } else {
                    "Select a tool above to begin..."
                }
            }

            val isThinking = text == "Thinking..." || text.isBlank()
            val isError = text.startsWith("Error:")
            val isPlaceholder = text.contains("Please write something first") ||
                               text.contains("Select a tool above")

            holder.textView.text = text
            val style = if (isPlaceholder) android.graphics.Typeface.ITALIC else android.graphics.Typeface.NORMAL
            KeyboardTypeface.applyToTextView(holder.textView, text, android.graphics.Typeface.defaultFromStyle(style))

            // Dynamic theme-aware coloring with high contrast for readability
            val isNight = ResourceUtils.isNight(context.resources)
            val bg = holder.itemView.background as? android.graphics.drawable.GradientDrawable
            if (isNight) {
                bg?.setColor(Color.parseColor("#40FFFFFF")) // 25% white overlay for high contrast
                val color = if (isPlaceholder || isThinking) Color.parseColor("#D0FFFFFF") else Color.WHITE
                holder.textView.setTextColor(color)
            } else {
                bg?.setColor(Color.parseColor("#26000000")) // 15% black overlay to define the well
                val color = if (isPlaceholder || isThinking) Color.parseColor("#A0000000") else Color.BLACK
                holder.textView.setTextColor(color)
            }

            if (isNight) {
                holder.useButton.setTextColor(Color.WHITE)
            }
            KeyboardTypeface.applyToTextView(holder.useButton)

            holder.useButton.visibility = if (!isThinking && !isError && !isPlaceholder && text.isNotBlank()) View.VISIBLE else View.GONE
            holder.useButton.setOnClickListener { view ->
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    val textToUse = aiVariations.getOrNull(currentPos) ?: ""
                    if (textToUse.isNotBlank() && textToUse != "Thinking..." && !isPlaceholder) {
                        // VIRTUAL_KEY haptic feedback
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        onReplaceClicked(textToUse)
                    }
                }
            }
        }

        override fun getItemCount(): Int = 3
    }

    private inner class RepeatListener(
        private val initialInterval: Int,
        private val normalInterval: Int,
        private val clickListener: OnClickListener
    ) : OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var touchedView: View? = null
        private var repeatCount = 0

        private val runnable = object : Runnable {
            override fun run() {
                touchedView?.let {
                    if (it.isAttachedToWindow && it.isEnabled) {
                        repeatCount++
                        it.tag = repeatCount
                        clickListener.onClick(it)
                        handler.postDelayed(this, normalInterval.toLong())
                    } else {
                        handler.removeCallbacks(this)
                    }
                }
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(runnable)
                    touchedView = v
                    v.isPressed = true
                    repeatCount = 0
                    v.tag = repeatCount
                    clickListener.onClick(v)
                    handler.postDelayed(runnable, initialInterval.toLong())
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (touchedView != null && !isPointInView(v, event.x, event.y)) {
                        handler.removeCallbacks(runnable)
                        v.isPressed = false
                        touchedView = null
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(runnable)
                    v.isPressed = false
                    touchedView = null
                    return true
                }
            }
            return false
        }

        private fun isPointInView(view: View, x: Float, y: Float): Boolean {
            return x >= 0 && x <= view.width && y >= 0 && y <= view.height
        }
    }

    private fun updateButtonStates(ic: InputConnection? = null) {
        val connection = ic ?: getLatinIME()?.currentInputConnection
        val extractedText = connection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.text?.toString()
        val hasText = !extractedText.isNullOrBlank()
        val canUseTools = hasText && !isGenerating

        val isNight = ResourceUtils.isNight(context.resources)
        val disabledAlpha = if (isNight) 0.8f else 0.5f

        val toolButtons = listOf(
            R.id.btn_tool_proofread to "Proofread",
            R.id.btn_tool_fix_grammar to "Fix Grammar",
            R.id.btn_tool_rewrite to "Rewrite",
            R.id.btn_tone_professional to "Professional",
            R.id.btn_tone_friendly to "Friendly",
            R.id.btn_tool_old_english to "Old English"
        )

        val colors = Settings.getValues().mColors
        val keyText = colors.get(ColorType.KEY_TEXT)
        val accentColor = colors.get(ColorType.ACTION_KEY_ICON)

        val keyboardViewAttr = context.obtainStyledAttributes(null, R.styleable.KeyboardView, R.attr.keyboardViewStyle, R.style.KeyboardView)

        for ((id, toolName) in toolButtons) {
            findViewById<Button>(id)?.apply {
                isEnabled = canUseTools
                alpha = if (canUseTools) 1.0f else disabledAlpha
                isClickable = canUseTools
                isFocusable = canUseTools

                val isSelected = selectedTool == toolName
                val colorType = if (isSelected) ColorType.SPECIAL_KEY_BACKGROUND else ColorType.KEY_BACKGROUND

                val isRoundedStyle = colors.themeStyle == KeyboardTheme.STYLE_ROUNDED || colors.themeStyle == KeyboardTheme.STYLE_CIRCLE
                if (isRoundedStyle) {
                    val shape = android.graphics.drawable.GradientDrawable()
                    if (colors.themeStyle == KeyboardTheme.STYLE_CIRCLE) {
                        shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                    } else {
                        shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        shape.cornerRadius = 1000f
                    }
                    shape.setColor(android.graphics.Color.WHITE)
                    colors.setColor(shape, colorType)
                    background = shape
                } else {
                    background = colors.selectAndColorDrawable(keyboardViewAttr, colorType)
                }

                setTextColor(if (isSelected) accentColor else keyText)
                val style = if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                KeyboardTypeface.applyToTextView(this, text, android.graphics.Typeface.defaultFromStyle(style))
            }
        }

        findViewById<View>(R.id.btn_delete_ai)?.apply {
            isEnabled = hasText
            alpha = if (hasText) 1.0f else disabledAlpha
            val isRoundedStyle = colors.themeStyle == KeyboardTheme.STYLE_ROUNDED || colors.themeStyle == KeyboardTheme.STYLE_CIRCLE
            if (isRoundedStyle) {
                val shape = android.graphics.drawable.GradientDrawable()
                if (colors.themeStyle == KeyboardTheme.STYLE_CIRCLE) {
                    shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                } else {
                    shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    shape.cornerRadius = 1000f
                }
                shape.setColor(android.graphics.Color.WHITE)
                colors.setColor(shape, ColorType.KEY_BACKGROUND)
                background = shape
            } else {
                background = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.KEY_BACKGROUND)
            }
        }

        copyButton.apply {
            alpha = if (isEnabled) 1.0f else disabledAlpha
            val isRoundedStyle = colors.themeStyle == KeyboardTheme.STYLE_ROUNDED || colors.themeStyle == KeyboardTheme.STYLE_CIRCLE
            if (isRoundedStyle) {
                val shape = android.graphics.drawable.GradientDrawable()
                if (colors.themeStyle == KeyboardTheme.STYLE_CIRCLE) {
                    shape.shape = android.graphics.drawable.GradientDrawable.OVAL
                } else {
                    shape.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    shape.cornerRadius = 1000f
                }
                shape.setColor(android.graphics.Color.WHITE)
                colors.setColor(shape, ColorType.KEY_BACKGROUND)
                background = shape
            } else {
                background = colors.selectAndColorDrawable(keyboardViewAttr, ColorType.KEY_BACKGROUND)
            }
        }

        keyboardViewAttr.recycle()
    }

    private fun getLatinIME(): helium314.keyboard.latin.LatinIME? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is helium314.keyboard.latin.LatinIME) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? helium314.keyboard.latin.LatinIME
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyThemeFixes()
        updateButtonStates()
        viewPager.adapter?.notifyDataSetChanged()
    }

    private fun applyThemeFixes() {
        val colors = Settings.getValues().mColors

        // Apply custom font to all persistent UI elements
        findViewById<TextView>(R.id.tv_ai_title)?.let {
            KeyboardTypeface.applyToTextView(it, it.text, android.graphics.Typeface.defaultFromStyle(android.graphics.Typeface.BOLD))
            it.setTextColor(colors.get(ColorType.KEY_TEXT))
        }

        findViewById<ImageButton>(R.id.btn_back_ai)?.let {
            it.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))
            it.background = createBackButtonBackground(colors)
            it.setPadding(0, 0, 0, 0)
            it.scaleType = ImageView.ScaleType.CENTER
        }

        val buttonsToFix = listOf(
            R.id.btn_tool_fix_grammar,
            R.id.btn_tool_proofread,
            R.id.btn_tool_rewrite,
            R.id.btn_tone_professional,
            R.id.btn_tone_friendly,
            R.id.btn_tool_old_english,
            R.id.btn_copy_text
        )
        for (id in buttonsToFix) {
            findViewById<Button>(id)?.let {
                KeyboardTypeface.applyToTextView(it)
            }
        }
        findViewById<ImageButton>(R.id.btn_delete_ai)?.let {
            it.imageTintList = ColorStateList.valueOf(colors.get(ColorType.KEY_TEXT))
        }
    }

    private fun createBackButtonBackground(colors: helium314.keyboard.latin.common.Colors): RippleDrawable {
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
            colors.setColor(this, ColorType.SPECIAL_KEY_BACKGROUND)
        }
        val rippleColor = ColorStateList.valueOf(
            ColorUtils.setAlphaComponent(colors.get(ColorType.FUNCTIONAL_KEY_TEXT), 0x33)
        )
        val horizontalInset = 3.dpToPx(resources)
        val verticalInset = 3.dpToPx(resources)
        val content = InsetDrawable(circle, horizontalInset, verticalInset, horizontalInset, verticalInset)
        return RippleDrawable(rippleColor, content, content.constantState?.newDrawable()?.mutate())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopGlowAnimation(immediate = true)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView == this && visibility != View.VISIBLE) {
            stopGlowAnimation(immediate = true)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE) {
            stopGlowAnimation(immediate = true)
        }
    }

    private fun setupUI() {
        viewPager = findViewById(R.id.vp_ai_carousel)
        indicatorContainer = findViewById(R.id.ll_page_indicators)
        copyButton = findViewById(R.id.btn_copy_text)
        glowBorder = findViewById(R.id.glow_border)

        viewPager.adapter = AiOutputAdapter()
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicators(position)
            }
        })

        // Bind Tool Buttons
        findViewById<Button>(R.id.btn_tool_fix_grammar).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Fix Grammar", AI_PROMPTS["Fix Grammar"] ?: "")
        }
        findViewById<Button>(R.id.btn_tool_proofread).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Proofread", AI_PROMPTS["Proofread"] ?: "")
        }
        findViewById<Button>(R.id.btn_tool_rewrite).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Rewrite", AI_PROMPTS["Rewrite"] ?: "")
        }
        findViewById<Button>(R.id.btn_tone_professional).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Professional", AI_PROMPTS["Professional"] ?: "")
        }
        findViewById<Button>(R.id.btn_tone_friendly).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Friendly", AI_PROMPTS["Friendly"] ?: "")
        }
        findViewById<Button>(R.id.btn_tool_old_english).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onToolClicked("Old English", AI_PROMPTS["Old English"] ?: "")
        }
        findViewById<ImageButton>(R.id.btn_back_ai).setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onCloseClicked()
        }

        val deleteBtn = findViewById<ImageButton>(R.id.btn_delete_ai)
        val deleteClickListener = OnClickListener { view ->
            val isRepeat = (view.tag as? Int ?: 0) > 0
            deleteOneCharacter(view, isRepeat)
            updateButtonStates()
        }
        deleteBtn.isLongClickable = false
        deleteBtn.setOnTouchListener(RepeatListener(400, 50, deleteClickListener))

        copyButton.setOnClickListener { view ->
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, view, HapticEvent.KEY_PRESS)
            onCopyClicked()
        }
    }

    private fun updateIndicators(position: Int) {
        indicatorContainer.removeAllViews()
        val density = resources.displayMetrics.density
        // Always show 3 indicators as per Master Fix
        for (i in 0 until 3) {
            val isActive = i == position
            val dot = View(context).apply {
                val dotHeight = (8 * density).toInt()
                val dotWidth = if (isActive) (24 * density).toInt() else (8 * density).toInt()
                val hasText = aiVariations.getOrNull(i)?.isNotBlank() == true

                layoutParams = LinearLayout.LayoutParams(dotWidth, dotHeight).apply {
                    setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
                }

                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 100f * density

                    val typedValue = TypedValue()
                    val colorAttr = if (isActive) android.R.attr.colorAccent else android.R.attr.textColorSecondary
                    context.theme.resolveAttribute(colorAttr, typedValue, true)
                    setColor(typedValue.data)
                }

                // Add subtle alpha to inactive empty dots (View alpha)
                if (!isActive && !hasText) {
                    alpha = 0.3f
                }
            }
            indicatorContainer.addView(dot)
        }
    }

    fun onOpen(connection: InputConnection?) {
        this.inputConnection = connection
        val selectedText = connection?.getSelectedText(0)?.toString()
        if (!selectedText.isNullOrBlank()) {
            this.userText = selectedText
        } else {
            val extracted = connection?.getExtractedText(ExtractedTextRequest(), 0)
            this.userText = extracted?.text?.toString() ?: ""
        }
        // Pad with empty variations on open
        aiVariations = listOf("", "", "")
        selectedTool = null
        isGenerating = false
        generationSequence++
        viewPager.adapter?.notifyDataSetChanged()
        updateIndicators(0)
        updateButtonStates(connection)
        copyButton.isEnabled = false
    }

    fun onClose() {
        inputConnection = null
        isGenerating = false
        generationSequence++
        stopGlowAnimation()
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun onCloseClicked() {
        if (::keyboardActionListener.isInitialized) {
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }
    }

    private fun deleteOneCharacter(view: View, isRepeat: Boolean) {
        val ic = inputConnection ?: getLatinIME()?.currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        val beforeCursor = ic.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        if (selectedText.isNullOrEmpty() && beforeCursor.isEmpty()) return

        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(
            KeyCode.DELETE,
            view,
            if (isRepeat) HapticEvent.KEY_REPEAT else HapticEvent.KEY_PRESS
        )

        if (!selectedText.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }

        val deleteLength = Character.charCount(beforeCursor.codePointBefore(beforeCursor.length))
        ic.deleteSurroundingText(deleteLength, 0)
    }

    private fun onToolClicked(toolName: String, prompt: String) {
        if (isGenerating) return
        if (userText.isBlank()) {
            Toast.makeText(context, "No text to process", Toast.LENGTH_SHORT).show()
            return
        }
        isGenerating = true
        generationSequence++
        val requestGeneration = generationSequence
        selectedTool = toolName

        // Show thinking in all 3 pages or just first? User wants strictly 3 pages.
        aiVariations = listOf("Thinking...", "", "")
        copyButton.isEnabled = false
        updateButtonStates()
        viewPager.adapter?.notifyDataSetChanged()
        updateIndicators(0)
        startGlowAnimation()

        GeminiService.generateText(context, prompt, userText) { result, exception ->
            if (requestGeneration != generationSequence || inputConnection == null || !isAttachedToWindow) {
                return@generateText
            }
            isGenerating = false
            stopGlowAnimation()
            if (exception != null) {
                aiVariations = listOf("Error: ${exception.message}", "", "")
                copyButton.isEnabled = false
            } else if (result != null) {
                val rawVariations = result.split(DELIMITER)
                    .map { it.trim() }
                    .filter { it.isNotBlank() }

                // Pad to exactly 3
                val padded = mutableListOf<String>()
                padded.addAll(rawVariations.take(3))
                while (padded.size < 3) {
                    padded.add(if (padded.isEmpty()) "No variations returned." else "")
                }
                aiVariations = padded
                copyButton.isEnabled = true
            }
            updateButtonStates()
            viewPager.adapter?.notifyDataSetChanged()
            viewPager.setCurrentItem(0, false)
            updateIndicators(0)
        }
    }

    private fun onReplaceClicked(text: String) {
        if (text.isNotBlank() && text != "Thinking...") {
            val ic = inputConnection ?: getLatinIME()?.currentInputConnection
            if (ic != null) {
                // 1. Select all text in the input field
                ic.performContextMenuAction(android.R.id.selectAll)

                // 2. Overwrite the selection with AI text
                ic.commitText(text, 1)

                Toast.makeText(context, "Text replaced", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onCopyClicked() {
        val currentPosition = viewPager.currentItem
        val text = aiVariations.getOrNull(currentPosition)
        if (!text.isNullOrBlank() && text != "Thinking...") {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AI Result", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGlowAnimation() {
        if (!::glowBorder.isInitialized) return

        glowBorder.animate().cancel()
        glowAnimator?.cancel()
        glowShiftAnimator?.cancel()

        glowBorder.visibility = View.VISIBLE
        glowBorder.alpha = 0f
        glowBorder.bringToFront()
        glowBorder.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val shiftingGradient = FluidGlowGradientDrawable(
            createGlowGradientColors(),
            cornerRadius = 22.dpToPx(resources).toFloat()
        )
        glowBorder.background = shiftingGradient

        glowAnimator = ObjectAnimator.ofFloat(glowBorder, "alpha", 0.38f, 0.82f).apply {
            duration = 1800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }

        var lastFrameTime = 0L
        glowShiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 13000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                if (!isAttachedToWindow || windowVisibility != View.VISIBLE || !glowBorder.isShown) {
                    stopGlowAnimation(immediate = true)
                    return@addUpdateListener
                }

                val now = SystemClock.uptimeMillis()
                if (now - lastFrameTime >= GLOW_FRAME_INTERVAL_MS) {
                    lastFrameTime = now
                    shiftingGradient.progress = it.animatedValue as Float
                }
            }
            start()
        }
    }

    private fun createGlowGradientColors(): IntArray {
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val isNight = KeyboardTheme.isDarkThemeActive(context)
            intArrayOf(
                ContextCompat.getColor(context, if (isNight) android.R.color.system_accent1_200 else android.R.color.system_accent1_600),
                ContextCompat.getColor(context, if (isNight) android.R.color.system_accent2_300 else android.R.color.system_accent2_500),
                ContextCompat.getColor(context, if (isNight) android.R.color.system_accent3_200 else android.R.color.system_accent3_500)
            )
        } else {
            val keyboardColors = Settings.getValues().mColors
            intArrayOf(
                keyboardColors.get(ColorType.ACTION_KEY_BACKGROUND),
                keyboardColors.get(ColorType.SPECIAL_KEY_BACKGROUND),
                keyboardColors.get(ColorType.GESTURE_TRAIL)
            )
        }.map { polishGlowColor(it) }

        return intArrayOf(colors[0], colors[1], colors[2], colors[0])
    }

    private fun polishGlowColor(color: Int): Int {
        val hsl = FloatArray(3)
        val isNight = KeyboardTheme.isDarkThemeActive(context)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * if (isNight) 1.35f else 1.24f).coerceIn(0.46f, 1f)
        hsl[2] = if (isNight) hsl[2].coerceIn(0.6f, 0.86f) else hsl[2].coerceIn(0.4f, 0.66f)
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), 255)
    }

    private fun stopGlowAnimation(immediate: Boolean = false) {
        if (!::glowBorder.isInitialized) return

        glowAnimator?.cancel()
        glowAnimator = null
        glowShiftAnimator?.cancel()
        glowShiftAnimator = null

        if (immediate) {
            glowBorder.animate().cancel()
            glowBorder.alpha = 0f
            glowBorder.visibility = View.GONE
            glowBorder.background = null
            glowBorder.setLayerType(View.LAYER_TYPE_NONE, null)
            return
        }

        glowBorder.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                glowBorder.visibility = View.GONE
                glowBorder.background = null
                glowBorder.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            .start()
    }
}

private class FluidGlowGradientDrawable(
    private val colors: IntArray,
    private val cornerRadius: Float
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sparklePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clipPath = Path()
    private val fluidPalette = buildFluidPalette(colors)
    private val blobs = List(8) { index ->
        val color = fluidPalette[(index * 3) % fluidPalette.size]
        Blob(
            color = color,
            gradientColors = intArrayOf(
                withAlpha(color, 235),
                withAlpha(color, 150),
                withAlpha(color, 56),
                Color.TRANSPARENT
            ),
            xPhase = Random.nextFloat() * FULL_TURN,
            yPhase = Random.nextFloat() * FULL_TURN,
            radiusPhase = Random.nextFloat() * FULL_TURN,
            speed = 0.58f + Random.nextFloat() * 0.78f,
            xDrift = 0.20f + Random.nextFloat() * 0.28f,
            yDrift = 0.20f + Random.nextFloat() * 0.26f,
            radius = 0.20f + Random.nextFloat() * 0.16f
        )
    }
    private val sparkles = List(10) {
        Sparkle(
            x = Random.nextFloat(),
            y = Random.nextFloat(),
            phase = Random.nextFloat() * FULL_TURN,
            speed = 0.7f + Random.nextFloat() * 1.35f,
            size = 0.9f + Random.nextFloat() * 1.8f
        )
    }

    var progress: Float = 0f
        set(value) {
            field = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return

        rect.set(bounds)
        val width = rect.width()
        val height = rect.height()
        val turn = progress * FULL_TURN
        val centerX = rect.centerX() + sin(turn * 0.22f) * width * 0.06f
        val centerY = rect.centerY() + cos(turn * 0.19f) * height * 0.06f
        val angle = turn * 0.07f + sin(turn * 0.18f) * 0.42f
        val radius = hypot(width, height) * 0.72f
        val dx = cos(angle) * radius
        val dy = sin(angle) * radius

        clipPath.reset()
        clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clipPath)

        paint.shader = LinearGradient(
            centerX - dx,
            centerY - dy,
            centerX + dx,
            centerY + dy,
            fluidPalette,
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(rect, paint)
        paint.shader = null

        for (blob in blobs) {
            drawBlob(canvas, blob, turn, width, height)
        }
        drawSparkles(canvas, turn, width, height)

        canvas.restore()
    }

    private fun drawBlob(canvas: Canvas, blob: Blob, turn: Float, width: Float, height: Float) {
        val blobTurn = turn * blob.speed
        val x = rect.centerX() +
                sin(blobTurn + blob.xPhase) * width * blob.xDrift +
                cos(blobTurn * 0.53f + blob.yPhase) * width * 0.08f
        val y = rect.centerY() +
                cos(blobTurn * 0.88f + blob.yPhase) * height * blob.yDrift +
                sin(blobTurn * 0.47f + blob.xPhase) * height * 0.08f
        val radius = hypot(width, height) * blob.radius *
                (0.86f + 0.14f * sin(blobTurn * 0.58f + blob.radiusPhase))

        paint.shader = RadialGradient(
            x,
            y,
            radius,
            blob.gradientColors,
            BLOB_GRADIENT_STOPS,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, radius, paint)
        paint.shader = null
    }

    private fun drawSparkles(canvas: Canvas, turn: Float, width: Float, height: Float) {
        sparklePaint.shader = null
        sparklePaint.color = Color.WHITE

        for (sparkle in sparkles) {
            val twinkle = ((sin(turn * sparkle.speed + sparkle.phase) + 1f) * 0.5f)
            if (twinkle < 0.42f) continue

            val driftX = sin(turn * 0.41f + sparkle.phase) * width * 0.018f
            val driftY = cos(turn * 0.36f + sparkle.phase) * height * 0.018f
            val x = rect.left + sparkle.x * width + driftX
            val y = rect.top + sparkle.y * height + driftY
            val size = sparkle.size * (0.72f + twinkle * 0.58f)
            sparklePaint.alpha = (44f * twinkle * twinkle).toInt().coerceIn(0, 72)

            canvas.drawCircle(x, y, size, sparklePaint)
            canvas.drawLine(x - size * 2.2f, y, x + size * 2.2f, y, sparklePaint)
            canvas.drawLine(x, y - size * 2.2f, x, y + size * 2.2f, sparklePaint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return ColorUtils.setAlphaComponent(color, alpha.coerceIn(0, 255))
    }

    private fun buildFluidPalette(colors: IntArray): IntArray {
        val base = colors.distinct().ifEmpty { listOf(Color.WHITE) }
        val palette = mutableListOf<Int>()
        for (i in base.indices) {
            val current = base[i]
            val next = base[(i + 1) % base.size]
            val hueOffset = if (i % 2 == 0) 20f else -18f
            palette.add(current)
            palette.add(shiftPaletteColor(current, hueOffset, 1.08f, 0.02f))
            palette.add(boostPaletteColor(ColorUtils.blendARGB(current, next, 0.36f), 1.18f, 0.04f))
        }
        palette.add(shiftPaletteColor(ColorUtils.blendARGB(base.first(), base.last(), 0.5f), -24f, 1.2f, 0.05f))
        return palette.toIntArray()
    }

    private fun boostPaletteColor(color: Int, saturationBoost: Float, lightnessBoost: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * saturationBoost).coerceIn(0f, 1f)
        hsl[2] = (hsl[2] + lightnessBoost).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun shiftPaletteColor(color: Int, hueOffset: Float, saturationBoost: Float, lightnessBoost: Float): Int {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(color, hsl)
        hsl[0] = (hsl[0] + hueOffset + 360f) % 360f
        hsl[1] = (hsl[1] * saturationBoost).coerceIn(0f, 1f)
        hsl[2] = (hsl[2] + lightnessBoost).coerceIn(0f, 1f)
        return ColorUtils.HSLToColor(hsl)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha.coerceIn(0, 255)
        sparklePaint.alpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        private const val FULL_TURN = (PI * 2).toFloat()
        private val BLOB_GRADIENT_STOPS = floatArrayOf(0f, 0.46f, 0.78f, 1f)
    }

    private data class Blob(
        val color: Int,
        val gradientColors: IntArray,
        val xPhase: Float,
        val yPhase: Float,
        val radiusPhase: Float,
        val speed: Float,
        val xDrift: Float,
        val yDrift: Float,
        val radius: Float
    )

    private data class Sparkle(
        val x: Float,
        val y: Float,
        val phase: Float,
        val speed: Float,
        val size: Float
    )
}
