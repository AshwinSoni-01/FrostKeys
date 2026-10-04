// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodSubtype
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import helium314.keyboard.compat.ImeCompat
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.dpToPx

/**
 * iOS-style controls below the main key grid.
 *
 * A long press opens a compact radial-like chooser. Keeping the gesture on the
 * original button gives us the required press -> slide -> release behavior.
 */
class KeyboardCornerActionsView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : FrameLayout(context, attrs) {

    private enum class Action { EMOJI, IME }

    private data class Option(
        val action: () -> Unit,
        val icon: Drawable,
        val description: String,
        val tintIcon: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = 320L

    private var inputMethodService: InputMethodService? = null
    private var activeAction: Action? = null
    private var longPressTriggered = false
    private var movedBeforeLongPress = false
    private var popup: LinearLayout? = null
    private var popupOptions = emptyList<Option>()
    private var selectedOption = -1
    private var downRawX = 0f
    private var downRawY = 0f

    private val longPressRunnable = Runnable {
        val action = activeAction ?: return@Runnable
        if (!movedBeforeLongPress) {
            longPressTriggered = true
            showPopup(action)
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        }
    }

    fun bindInputMethodService(service: InputMethodService) {
        inputMethodService = service
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val emoji = findViewById<ImageButton>(R.id.keyboard_corner_emoji)
        val ime = findViewById<ImageButton>(R.id.keyboard_corner_ime)
        applyFooterIcon(emoji, ToolbarKey.EMOJI, "Emoji")
        applyFooterIcon(ime, ToolbarKey.VOICE, "Switch input method")
        setupButton(emoji, Action.EMOJI)
        setupButton(ime, Action.IME)
    }

    private fun applyFooterIcon(button: ImageButton, key: ToolbarKey, description: String) {
        button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context))
        button.imageTintList = ColorStateList.valueOf(
            Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        )
        button.contentDescription = description
    }

    private fun setupButton(button: ImageButton, action: Action) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeAction = action
                    longPressTriggered = false
                    movedBeforeLongPress = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    button.alpha = 0.65f
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!longPressTriggered && !movedBeforeLongPress &&
                        dx * dx + dy * dy > touchSlop * touchSlop
                    ) {
                        movedBeforeLongPress = true
                        handler.removeCallbacks(longPressRunnable)
                    }
                    if (longPressTriggered) updateSelection(event.rawX, event.rawY)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    button.alpha = 1f
                    if (longPressTriggered) {
                        val index = findOptionAt(event.rawX, event.rawY)
                        if (index >= 0) popupOptions[index].action()
                        hidePopup()
                    } else if (!movedBeforeLongPress) {
                        performTap(action)
                    }
                    activeAction = null
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    button.alpha = 1f
                    hidePopup()
                    activeAction = null
                    true
                }

                else -> false
            }
        }
    }

    private fun performTap(action: Action) {
        when (action) {
            Action.EMOJI -> KeyboardSwitcher.getInstance().setEmojiKeyboard()
            Action.IME -> {
                val service = inputMethodService ?: return
                runCatching { ImeCompat.run { service.switchInputMethod() } }
            }
        }
    }

    private fun showPopup(action: Action) {
        hidePopup()
        popupOptions = when (action) {
            Action.EMOJI -> emojiOptions()
            Action.IME -> imeOptions()
        }
        if (popupOptions.isEmpty()) return

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(
                6.dpToPx(resources),
                6.dpToPx(resources),
                6.dpToPx(resources),
                6.dpToPx(resources)
            )
            background = popupBackground()
            elevation = 10.dpToPx(resources).toFloat()
        }
        popup = panel
        selectedOption = -1

        popupOptions.forEachIndexed { _, option ->
            val button = ImageButton(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    48.dpToPx(resources),
                    48.dpToPx(resources)
                )
                background = optionBackground(false)
                setImageDrawable(option.icon)
                imageTintList = if (option.tintIcon) {
                    ColorStateList.valueOf(Settings.getValues().mColors.get(ColorType.KEY_TEXT))
                } else {
                    null
                }
                contentDescription = option.description
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(
                    9.dpToPx(resources),
                    9.dpToPx(resources),
                    9.dpToPx(resources),
                    9.dpToPx(resources)
                )
            }
            panel.addView(button)
        }

        val width = (popupOptions.size * 48 + 12).dpToPx(resources)
        val lp = LayoutParams(width, 60.dpToPx(resources)).apply {
            topMargin = -58.dpToPx(resources)
            leftMargin = if (action == Action.EMOJI) {
                6.dpToPx(resources)
            } else {
                (widthFromView() - width - 6.dpToPx(resources)).coerceAtLeast(6.dpToPx(resources))
            }
        }
        addView(panel, lp)
    }

    private fun popupBackground(): GradientDrawable {
        val base = Settings.getValues().mColors.get(ColorType.KEY_BACKGROUND)
        return GradientDrawable().apply {
            setColor(ColorUtils.setAlphaComponent(base, 242))
            cornerRadius = 30.dpToPx(resources).toFloat()
            setStroke(
                1.dpToPx(resources),
                ColorUtils.setAlphaComponent(
                    Settings.getValues().mColors.get(ColorType.KEY_TEXT),
                    28
                )
            )
        }
    }

    private fun optionBackground(selected: Boolean): GradientDrawable {
        val color = Settings.getValues().mColors.get(ColorType.SPECIAL_KEY_BACKGROUND)
        return GradientDrawable().apply {
            setColor(if (selected) ColorUtils.setAlphaComponent(color, 180) else Color.TRANSPARENT)
            cornerRadius = 24.dpToPx(resources).toFloat()
        }
    }

    private fun updateSelection(rawX: Float, rawY: Float) {
        val index = findOptionAt(rawX, rawY)
        if (index == selectedOption) return
        selectedOption = index
        popup?.let { panel ->
            for (i in 0 until panel.childCount) {
                (panel.getChildAt(i) as? ImageButton)?.background =
                    optionBackground(i == selectedOption)
            }
        }
    }

    private fun findOptionAt(rawX: Float, rawY: Float): Int {
        val panel = popup ?: return -1
        val rect = Rect()
        for (i in 0 until panel.childCount) {
            val button = panel.getChildAt(i) as? ImageButton ?: continue
            if (button.getGlobalVisibleRect(rect) &&
                rect.contains(rawX.toInt(), rawY.toInt())
            ) {
                return i
            }
        }
        return -1
    }

    private fun hidePopup() {
        popup?.let(::removeView)
        popup = null
        popupOptions = emptyList()
        selectedOption = -1
    }

    private fun widthFromView(): Int = width

    private fun emojiOptions(): List<Option> {
        val colors = Settings.getValues().mColors
        val clipboard = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.CLIPBOARD.name, context)
        val toolbar = context.getDrawable(R.drawable.ic_access_point_grid)
            ?: return emptyList()
        clipboard.setTint(colors.get(ColorType.KEY_TEXT))
        toolbar.setTint(colors.get(ColorType.KEY_TEXT))

        return listOf(
            Option(
                action = { KeyboardSwitcher.getInstance().setClipboardKeyboard() },
                icon = clipboard,
                description = "Clipboard",
                tintIcon = false
            ),
            Option(
                action = { KeyboardSwitcher.getInstance().setAccessPointKeyboard() },
                icon = toolbar,
                description = "Toolbar",
                tintIcon = false
            )
        )
    }

    private fun imeOptions(): List<Option> {
        val service = inputMethodService ?: return emptyList()
        val rim = runCatching { RichInputMethodManager.getInstance() }.getOrNull()
            ?: return emptyList()
        val currentId = runCatching { rim.inputMethodInfoOfThisIme.id }.getOrNull()
        val currentSubtype = rim.currentSubtype.rawSubtype
        val pm = context.packageManager

        return rim.inputMethodManager.enabledInputMethodList.mapNotNull { imi ->
            val subtype = preferredSubtype(rim, imi, currentId, currentSubtype) ?: return@mapNotNull null
            Option(
                action = { ImeCompat.run { service.switchInputMethodAndSubtype(imi, subtype) } },
                icon = imi.loadIcon(pm),
                description = imi.loadLabel(pm)?.toString()?.ifBlank { "Input method" } ?: "Input method",
                tintIcon = false
            )
        }
    }

    private fun preferredSubtype(
        rim: RichInputMethodManager,
        imi: InputMethodInfo,
        currentId: String?,
        currentSubtype: InputMethodSubtype
    ): InputMethodSubtype? {
        val enabled = rim.getEnabledInputMethodSubtypes(imi, true).filterNot { it.isAuxiliary }
        if (enabled.isEmpty()) return null
        if (imi.id == currentId) {
            enabled.firstOrNull { it == currentSubtype }?.let { return it }
        }
        return enabled.first()
    }
}
