package helium314.keyboard.keyboard.internal

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

class SparkleDustView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class DustColor(
        val r: Int,
        val g: Int,
        val b: Int
    )

    private data class AmbientDustParticle(
        var x: Float,
        var y: Float,
        val radius: Float,
        val opacity: Float,
        val speed: Float,
        val drift: Float,
        val phase: Float,
        val depth: Float,
        val color: DustColor
    )

    private data class StreamDustParticle(
        var u: Float,
        val lane: Int,
        val radius: Float,
        val opacity: Float,
        val phase: Float,
        val speed: Float,
        val depth: Float,
        val color: DustColor
    )

    private val random = Random.Default

    private val ambientDust = mutableListOf<AmbientDustParticle>()
    private val streamDust = mutableListOf<StreamDustParticle>()

    private val dustPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var animationTime = 0f
    private var lastFrameUptimeMs = 0L

    private val dustColors = listOf(
        DustColor(255, 244, 210), // warm sunlight
        DustColor(165, 232, 255), // soft cyan
        DustColor(220, 180, 255), // lavender
        DustColor(255, 176, 122), // warm peach
        DustColor(160, 195, 255)  // soft blue
    )

    init {
        setWillNotDraw(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            dustPaint.blendMode = BlendMode.SCREEN
        } else {
            @Suppress("DEPRECATION")
            dustPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(w, h, oldw, oldh)

        if (w <= 0 || h <= 0) return

        createParticles(w.toFloat(), h.toFloat())
    }

    private fun createParticles(width: Float, height: Float) {
        ambientDust.clear()
        streamDust.clear()

        val areaScale = ((width * height) / (980f * 620f))
            .coerceIn(0.75f, 1.35f)

        val ambientCount = (780 * areaScale).toInt()
        val streamCount = (980 * areaScale).toInt()

        repeat(ambientCount) {
            ambientDust += AmbientDustParticle(
                x = random.nextFloat() * width,
                y = random.nextFloat() * height,
                radius = randomRange(0.18f, 0.96f),
                opacity = randomRange(0.035f, 0.235f),
                speed = randomRange(0.045f, 0.235f),
                drift = randomRange(0f, (PI * 2).toFloat()),
                phase = randomRange(0f, (PI * 2).toFloat()),
                depth = randomRange(0.25f, 1f),
                color = randomDustColor()
            )
        }

        repeat(streamCount) {
            streamDust += StreamDustParticle(
                u = random.nextFloat(),
                lane = random.nextInt(0, 7),
                radius = randomRange(0.16f, 0.84f),
                opacity = randomRange(0.03f, 0.23f),
                phase = randomRange(0f, (PI * 2).toFloat()),
                speed = randomRange(0.012f, 0.04f),
                depth = randomRange(0.3f, 1f),
                color = randomDustColor()
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return
        if (!isShown || windowVisibility != VISIBLE || alpha <= 0f) {
            lastFrameUptimeMs = 0L
            return
        }

        val now = SystemClock.uptimeMillis()
        if (lastFrameUptimeMs != 0L) {
            val elapsed = now - lastFrameUptimeMs
            if (elapsed < FRAME_INTERVAL_MS) {
                postInvalidateDelayed(FRAME_INTERVAL_MS - elapsed)
                return
            }
            animationTime += (elapsed / TARGET_FRAME_MS) * ANIMATION_STEP_PER_FRAME
        } else {
            animationTime += ANIMATION_STEP_PER_FRAME
        }
        lastFrameUptimeMs = now

        drawStreamDust(canvas)
        drawAmbientDust(canvas)

        postInvalidateDelayed(FRAME_INTERVAL_MS)
    }

    /**
     * These are the denser particles that flow along invisible soft wave lanes.
     * They create that Samsung-live-wallpaper-style dust current.
     */
    private fun drawStreamDust(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        for (particle in streamDust) {
            particle.u += particle.speed * 0.006f

            if (particle.u > 1.04f) {
                particle.u = -0.04f
            }

            val x = particle.u * (w + 180f) - 90f

            val y =
                ribbonY(
                    x = x,
                    lane = particle.lane,
                    time = animationTime,
                    height = h
                ) +
                    sin(animationTime * 0.45f + particle.phase) * 14f +
                    (particle.depth - 0.5f) * 52f

            val breathe =
                (sin(animationTime * (0.75f + particle.depth) + particle.phase) + 1f) * 0.5f

            val lightSweep = max(
                0f,
                sin((x / w) * (PI.toFloat() * 2f) - animationTime * 0.72f + particle.lane)
            )

            val alpha =
                particle.opacity * (0.45f + breathe * 0.55f) +
                    lightSweep * 0.08f * particle.depth

            val radius =
                particle.radius * (0.75f + breathe * 0.38f + lightSweep * 0.34f)

            drawDustDot(
                canvas = canvas,
                x = x,
                y = y,
                radius = radius,
                alpha = min(0.46f, alpha),
                color = particle.color
            )
        }
    }

    /**
     * These are the loose floating particles.
     * They drift slowly everywhere and fade in/out independently.
     */
    private fun drawAmbientDust(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        for (particle in ambientDust) {
            val flowX =
                sin((particle.y + animationTime * 20f) * 0.011f + particle.phase) * 0.16f +
                    cos(animationTime * 0.14f + particle.depth * 5f) * 0.08f

            val flowY =
                cos((particle.x + animationTime * 18f) * 0.01f + particle.phase) * 0.12f +
                    sin(animationTime * 0.12f + particle.depth * 4f) * 0.06f

            particle.x += flowX * particle.speed + cos(particle.drift) * 0.018f
            particle.y += flowY * particle.speed + sin(particle.drift) * 0.014f

            if (particle.x < -8f) particle.x = w + 8f
            if (particle.x > w + 8f) particle.x = -8f
            if (particle.y < -8f) particle.y = h + 8f
            if (particle.y > h + 8f) particle.y = -8f

            val fade =
                (sin(animationTime * (0.5f + particle.depth * 0.9f) + particle.phase) + 1f) * 0.5f

            val catchLight = max(
                0f,
                sin(animationTime * 0.95f + particle.phase * 1.8f + particle.x * 0.009f)
            )

            val alpha =
                particle.opacity * (0.38f + fade * 0.56f) +
                    catchLight * 0.055f * particle.depth

            val radius =
                particle.radius * (0.78f + fade * 0.2f + catchLight * 0.22f)

            drawDustDot(
                canvas = canvas,
                x = particle.x,
                y = particle.y,
                radius = radius,
                alpha = min(0.4f, alpha),
                color = particle.color
            )
        }
    }

    private fun drawDustDot(
        canvas: Canvas,
        x: Float,
        y: Float,
        radius: Float,
        alpha: Float,
        color: DustColor
    ) {
        val finalAlpha = (alpha.coerceIn(0f, 1f) * DUST_ALPHA_BOOST * 255).toInt()
            .coerceIn(0, 255)

        dustPaint.color = Color.argb(
            finalAlpha,
            color.r,
            color.g,
            color.b
        )

        canvas.drawCircle(
            x,
            y,
            radius,
            dustPaint
        )
    }

    /**
     * Invisible flowing wave path.
     * The stream dust follows this so it feels suspended inside moving liquid.
     */
    private fun ribbonY(
        x: Float,
        lane: Int,
        time: Float,
        height: Float
    ): Float {
        val base = height * (0.16f + lane * 0.105f)

        return base +
            sin(x * 0.0065f + time * (0.25f + lane * 0.022f) + lane * 1.1f) *
            (38f + lane * 1.5f) +
            cos(x * 0.012f - time * 0.18f + lane) *
            24f
    }

    private fun randomDustColor(): DustColor {
        return dustColors[random.nextInt(dustColors.size)]
    }

    private fun randomRange(
        min: Float,
        max: Float
    ): Float {
        return min + random.nextFloat() * (max - min)
    }

    private companion object {
        private const val DUST_ALPHA_BOOST = 8.00f
        private const val FRAME_INTERVAL_MS = 33L
        private const val TARGET_FRAME_MS = 16.6667f
        private const val ANIMATION_STEP_PER_FRAME = 0.01f
    }
}
