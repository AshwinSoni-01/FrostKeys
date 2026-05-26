package helium314.keyboard.keyboard.internal

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
    private var isReplacingSelection = false
    private var isExecutingReplacement = false

    private lateinit var viewPager: ViewPager2
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var copyButton: Button
    private var glowAnimator: ObjectAnimator? = null
    private var glowShiftAnimator: ValueAnimator? = null
    private var sparkleFadeAnimator: ValueAnimator? = null
    private lateinit var glowBorder: View
    private lateinit var sparkleDust: SparkleDustView
    private var selectedTool: String? = null
    private var isGenerating = false
    private var generationSequence = 0L
    private var resultRevealGeneration = 0L
    private val revealedResultPositions = mutableSetOf<Int>()

    companion object {
        private const val DELIMITER = "---VAR---"
        private const val GLOW_FRAME_INTERVAL_MS = 33L
        private const val MIN_RESULT_ANIMATION_MS = 4_000L
        private const val GLOW_FADE_OUT_MS = 1_200L
        private const val TEXT_REVEAL_FRAME_MS = 22L
        private const val TEXT_REVEAL_FADE_IN_MS = 600L
        private const val APPLIED_CHECK_START_SCALE = 0.86f
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
        private var appliedPosition: Int = RecyclerView.NO_POSITION
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val textView: TextView = view.findViewById(R.id.tv_ai_output)
            val useButton: Button = view.findViewById(R.id.btn_use_this)
            private val appliedOverlay: View = view.findViewById(R.id.applied_feedback_overlay)
            private val appliedCheck: ImageView = view.findViewById(R.id.applied_feedback_check)
            private var revealRunnable: Runnable? = null

            fun cancelTextReveal() {
                revealRunnable?.let { textView.removeCallbacks(it) }
                revealRunnable = null
                textView.animate().cancel()
                textView.alpha = 1f
            }

            fun resetAppliedFeedback() {
                appliedOverlay.animate().cancel()
                appliedCheck.animate().cancel()
                appliedOverlay.alpha = 0f
                appliedOverlay.visibility = View.GONE
                appliedOverlay.background = null
                appliedCheck.alpha = 0f
                appliedCheck.scaleX = APPLIED_CHECK_START_SCALE
                appliedCheck.scaleY = APPLIED_CHECK_START_SCALE
                appliedCheck.visibility = View.GONE
                appliedCheck.background = null
            }

            fun playAppliedFeedback(accentColor: Int) {
                resetAppliedFeedback()

                appliedOverlay.background = createAppliedOverlayBackground(accentColor)
                appliedCheck.background = createAppliedCheckBackground(accentColor)
                appliedOverlay.visibility = View.VISIBLE
                appliedCheck.visibility = View.VISIBLE
                appliedOverlay.bringToFront()
                appliedCheck.bringToFront()

                appliedOverlay.animate()
                    .alpha(0.24f)
                    .setStartDelay(0L)
                    .setDuration(240L)
                    .withEndAction {
                        appliedOverlay.animate()
                            .alpha(0f)
                            .setStartDelay(1_100L)
                            .setDuration(1_400L)
                            .withEndAction {
                                appliedOverlay.visibility = View.GONE
                                appliedOverlay.background = null
                            }
                            .start()
                    }
                    .start()

                appliedCheck.animate()
                    .alpha(1f)
                    .scaleX(1.04f)
                    .scaleY(1.04f)
                    .setStartDelay(60L)
                    .setDuration(280L)
                    .withEndAction {
                        appliedCheck.animate()
                            .alpha(0f)
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setStartDelay(1_000L)
                            .setDuration(900L)
                            .withEndAction {
                                appliedCheck.visibility = View.GONE
                                appliedCheck.background = null
                                appliedCheck.scaleX = APPLIED_CHECK_START_SCALE
                                appliedCheck.scaleY = APPLIED_CHECK_START_SCALE
                            }
                            .start()
                    }
                    .start()
            }

            fun revealText(fullText: String) {
                cancelTextReveal()

                val totalCodePoints = fullText.codePointCount(0, fullText.length)
                if (totalCodePoints <= 0) {
                    textView.text = fullText
                    return
                }

                var visibleCodePoints = 0
                val step = textRevealStep(totalCodePoints)
                textView.text = ""
                textView.alpha = 0f
                textView.animate()
                    .alpha(1f)
                    .setDuration(TEXT_REVEAL_FADE_IN_MS)
                    .start()

                val runnable = object : Runnable {
                    override fun run() {
                        if (!textView.isAttachedToWindow) {
                            textView.text = fullText
                            textView.alpha = 1f
                            revealRunnable = null
                            return
                        }

                        visibleCodePoints = (visibleCodePoints + step).coerceAtMost(totalCodePoints)
                        val endIndex = fullText.offsetByCodePoints(0, visibleCodePoints)
                        textView.text = fullText.substring(0, endIndex)

                        if (visibleCodePoints < totalCodePoints) {
                            textView.postDelayed(this, TEXT_REVEAL_FRAME_MS)
                        } else {
                            revealRunnable = null
                        }
                    }
                }
                revealRunnable = runnable
                textView.postDelayed(runnable, TEXT_REVEAL_FRAME_MS)
            }
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

            holder.cancelTextReveal()
            holder.resetAppliedFeedback()
            val shouldRevealText = resultRevealGeneration == generationSequence &&
                    !isThinking &&
                    !isError &&
                    !isPlaceholder &&
                    text.isNotBlank() &&
                    revealedResultPositions.add(position)
            if (shouldRevealText) {
                holder.revealText(text)
            } else {
                holder.textView.text = text
            }
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

            val isApplied = position == appliedPosition

            if (isNight) {
                holder.useButton.setTextColor(Color.WHITE)
            } else {
                holder.useButton.setTextColor(Color.BLACK)
            }
            // Update button text: "Applied" for the chosen card, "Use this" for others
            if (isApplied) {
                holder.useButton.text = context.getString(R.string.ai_applied)
            } else {
                holder.useButton.text = context.getString(R.string.ai_use_this)
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
                        holder.playAppliedFeedback(Settings.getValues().mColors.get(ColorType.ACTION_KEY_BACKGROUND))
                        // Update applied state without triggering a full rebind on currentPos,
                        // which would call resetAppliedFeedback() and kill the animation.
                        // Instead, update the button text directly on the live holder.
                        val oldApplied = appliedPosition
                        appliedPosition = currentPos
                        holder.useButton.text = context.getString(R.string.ai_applied)
                        KeyboardTypeface.applyToTextView(holder.useButton)
                        // Only notify the old position to reset its button text back to "Use this"
                        if (oldApplied != RecyclerView.NO_POSITION && oldApplied != currentPos) {
                            notifyItemChanged(oldApplied)
                        }
                    }
                }
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.cancelTextReveal()
            holder.resetAppliedFeedback()
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = 3

        fun resetAppliedPosition() {
            val old = appliedPosition
            appliedPosition = RecyclerView.NO_POSITION
            if (old != RecyclerView.NO_POSITION) {
                notifyItemChanged(old)
            }
        }
    }

    private fun textRevealStep(totalCodePoints: Int): Int {
        return when {
            totalCodePoints <= 220 -> 1
            totalCodePoints <= 600 -> 2
            else -> 3
        }
    }

    private fun createAppliedOverlayBackground(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 20.dpToPx(resources).toFloat()
            setColor(ColorUtils.setAlphaComponent(accentColor, 120))
        }
    }

    private fun createAppliedCheckBackground(accentColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ColorUtils.setAlphaComponent(accentColor, 220))
        }
    }

    private inner class DeleteTouchListener : OnTouchListener {
        private val handler = Handler(Looper.getMainLooper())
        private var mStartX = 0f
        private var mInHorizontalSwipe = false
        private val stepWidth = 12.dpToPx(resources).toFloat()
        private var repeatCount = 0
        private var isPressedState = false

        private val repeatRunnable = object : Runnable {
            override fun run() {
                if (!mInHorizontalSwipe && isPressedState && ::keyboardActionListener.isInitialized) {
                    repeatCount++
                    keyboardActionListener.onPressKey(KeyCode.DELETE, repeatCount, true, HapticEvent.KEY_REPEAT)
                    keyboardActionListener.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, true)
                    keyboardActionListener.onReleaseKey(KeyCode.DELETE, false)
                    handler.postDelayed(this, 50)
                }
            }
        }

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (!::keyboardActionListener.isInitialized) return false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    handler.removeCallbacks(repeatRunnable)
                    mStartX = event.x
                    mInHorizontalSwipe = false
                    isPressedState = true
                    v.isPressed = true
                    repeatCount = 0

                    keyboardActionListener.onPressKey(KeyCode.DELETE, 0, true, HapticEvent.KEY_PRESS)
                    keyboardActionListener.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
                    keyboardActionListener.onReleaseKey(KeyCode.DELETE, false)

                    handler.postDelayed(repeatRunnable, 400)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isPressedState) {
                        val dX = event.x - mStartX
                        val steps = (dX / stepWidth).toInt()
                        if (steps != 0) {
                            if (!mInHorizontalSwipe) {
                                handler.removeCallbacks(repeatRunnable)
                                mInHorizontalSwipe = true
                            }
                            mStartX += steps * stepWidth
                            keyboardActionListener.onMoveDeletePointer(steps)
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(repeatRunnable)
                    v.isPressed = false
                    isPressedState = false
                    if (mInHorizontalSwipe) {
                        mInHorizontalSwipe = false
                        keyboardActionListener.onUpWithDeletePointerActive()
                    }
                    updateButtonStates()
                    return true
                }
            }
            return false
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
            setTextColor(if (isNight) Color.WHITE else Color.BLACK)
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
                if (id == R.id.btn_copy_text) {
                    it.setTextColor(if (ResourceUtils.isNight(context.resources)) Color.WHITE else Color.BLACK)
                }
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
        sparkleDust = findViewById(R.id.sparkle_dust)

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
        deleteBtn.isLongClickable = false
        deleteBtn.setOnTouchListener(DeleteTouchListener())

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
        if (isExecutingReplacement) {
            updateButtonStates(connection)
            return
        }
        val selectedText = connection?.getSelectedText(0)?.toString()
        if (!selectedText.isNullOrBlank()) {
            this.userText = selectedText
            this.isReplacingSelection = true
        } else {
            val extracted = connection?.getExtractedText(ExtractedTextRequest(), 0)
            this.userText = extracted?.text?.toString() ?: ""
            this.isReplacingSelection = false
        }
        // Pad with empty variations on open
        aiVariations = listOf("", "", "")
        selectedTool = null
        isGenerating = false
        generationSequence++
        resultRevealGeneration = 0L
        revealedResultPositions.clear()
        viewPager.adapter?.notifyDataSetChanged()
        updateIndicators(0)
        updateButtonStates(connection)
        copyButton.isEnabled = false
    }

    fun onClose() {
        inputConnection = null
        isGenerating = false
        generationSequence++
        isExecutingReplacement = false
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



    private fun onToolClicked(toolName: String, prompt: String) {
        if (isGenerating) return
        if (userText.isBlank()) {
            Toast.makeText(context, "No text to process", Toast.LENGTH_SHORT).show()
            return
        }
        isGenerating = true
        generationSequence++
        val requestGeneration = generationSequence
        val generationStartedAtMs = SystemClock.uptimeMillis()
        selectedTool = toolName
        resultRevealGeneration = 0L
        revealedResultPositions.clear()
        (viewPager.adapter as? AiOutputAdapter)?.resetAppliedPosition()

        // Show thinking in all 3 pages or just first? User wants strictly 3 pages.
        aiVariations = listOf("Thinking...", "", "")
        copyButton.isEnabled = false
        updateButtonStates()
        viewPager.adapter?.notifyDataSetChanged()
        updateIndicators(0)
        startGlowAnimation()

        GeminiService.generateText(context, prompt, userText) { result, exception ->
            val showResponse = Runnable {
                if (requestGeneration != generationSequence || inputConnection == null || !isAttachedToWindow) {
                    return@Runnable
                }
                isGenerating = false
                stopGlowAnimation()
                if (exception != null) {
                    resultRevealGeneration = 0L
                    revealedResultPositions.clear()
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
                    resultRevealGeneration = requestGeneration
                    revealedResultPositions.clear()
                    aiVariations = padded
                    copyButton.isEnabled = true
                }
                updateButtonStates()
                viewPager.adapter?.notifyDataSetChanged()
                viewPager.setCurrentItem(0, false)
                updateIndicators(0)
            }

            val remainingAnimationMs = if (exception == null && result != null) {
                MIN_RESULT_ANIMATION_MS - (SystemClock.uptimeMillis() - generationStartedAtMs)
            } else {
                0L
            }
            if (remainingAnimationMs > 0L) {
                postDelayed(showResponse, remainingAnimationMs)
            } else {
                showResponse.run()
            }
        }
    }

    private fun onReplaceClicked(text: String) {
        if (text.isNotBlank() && text != "Thinking...") {
            val ic = inputConnection ?: getLatinIME()?.currentInputConnection
            if (ic != null) {
                isExecutingReplacement = true
                ic.beginBatchEdit()
                try {
                    if (isReplacingSelection) {
                        // Replace only the selected text in Android
                        ic.commitText(text, 1)
                    } else {
                        // Replace the entire field content
                        val before = ic.getTextBeforeCursor(10000, 0)?.length ?: 0
                        val after = ic.getTextAfterCursor(10000, 0)?.length ?: 0
                        ic.deleteSurroundingText(before, after)
                        ic.commitText(text, 1)
                    }
                } finally {
                    ic.endBatchEdit()
                    postDelayed({ isExecutingReplacement = false }, 1000)
                }

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
        if (!::glowBorder.isInitialized || !::sparkleDust.isInitialized) return

        glowBorder.animate().cancel()
        sparkleDust.animate().cancel()
        glowAnimator?.cancel()
        glowShiftAnimator?.cancel()
        sparkleFadeAnimator?.cancel()

        val shiftingGradient = FluidGlowGradientDrawable(
            createGlowGradientColors(),
            cornerRadius = 22.dpToPx(resources).toFloat()
        )
        glowBorder.background = shiftingGradient

        // Set alpha to 0f BEFORE making it visible to avoid single-frame flash of old alpha
        glowBorder.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        glowBorder.alpha = 0f
        glowBorder.visibility = View.VISIBLE
        glowBorder.bringToFront()

        sparkleDust.setLayerType(View.LAYER_TYPE_NONE, null)
        sparkleDust.overallAlpha = 0f
        sparkleDust.alpha = 1f // keep View's own alpha fully opaque to bypass ViewPropertyAnimator layer quirks
        sparkleDust.visibility = View.VISIBLE
        sparkleDust.bringToFront()
        sparkleDust.startDustAnimation()

        // Fade in smoothly to exactly 0.48f, which matches the starting value of the pulsing loop
        glowBorder.animate()
            .alpha(0.48f)
            .setDuration(480)
            .withEndAction {
                // Pulse seamlessly between 0.48f and 0.82f with zero jumps
                glowAnimator = ObjectAnimator.ofFloat(glowBorder, "alpha", 0.48f, 0.82f).apply {
                    duration = 1800
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    start()
                }
            }
            .start()

        sparkleFadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 480
            addUpdateListener {
                sparkleDust.overallAlpha = it.animatedValue as Float
            }
            start()
        }

        var lastFrameTime = 0L
        glowShiftAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 13000
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener {
                if (!isAttachedToWindow) {
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
            intArrayOf(
                ContextCompat.getColor(context, android.R.color.system_accent1_600),
                ContextCompat.getColor(context, android.R.color.system_accent2_500),
                ContextCompat.getColor(context, android.R.color.system_accent3_500)
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
        ColorUtils.colorToHSL(color, hsl)
        hsl[1] = (hsl[1] * 1.24f).coerceIn(0.46f, 1f)
        hsl[2] = hsl[2].coerceIn(0.4f, 0.66f)
        return ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(hsl), 255)
    }

    private fun stopGlowAnimation(immediate: Boolean = false) {
        if (!::glowBorder.isInitialized) return

        glowAnimator?.cancel()
        glowAnimator = null
        glowShiftAnimator?.cancel()
        glowShiftAnimator = null
        sparkleFadeAnimator?.cancel()
        sparkleFadeAnimator = null

        if (immediate) {
            glowBorder.animate().cancel()
            glowBorder.alpha = 0f
            glowBorder.visibility = View.GONE
            glowBorder.background = null
            glowBorder.setLayerType(View.LAYER_TYPE_NONE, null)
            if (::sparkleDust.isInitialized) {
                sparkleDust.animate().cancel()
                sparkleDust.stopDustAnimation()
                sparkleDust.overallAlpha = 0f
                sparkleDust.alpha = 1f
                sparkleDust.visibility = View.GONE
            }
            return
        }

        if (::sparkleDust.isInitialized) {
            val startAlpha = sparkleDust.overallAlpha
            sparkleFadeAnimator = ValueAnimator.ofFloat(startAlpha, 0f).apply {
                duration = GLOW_FADE_OUT_MS
                addUpdateListener {
                    sparkleDust.overallAlpha = it.animatedValue as Float
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        sparkleDust.stopDustAnimation()
                        sparkleDust.visibility = View.GONE
                    }
                })
                start()
            }
        }

        glowBorder.animate()
            .alpha(0f)
            .setDuration(GLOW_FADE_OUT_MS)
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
}
