package helium314.keyboard.latin

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

internal class StaticSparkleDustOverlay {

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
        val colorIndex: Int
    )

    private data class StreamDustParticle(
        var u: Float,
        val lane: Int,
        val radius: Float,
        val opacity: Float,
        val phase: Float,
        val speed: Float,
        val depth: Float,
        val colorIndex: Int
    )

    private val random = Random.Default
    private val ambientDust = mutableListOf<AmbientDustParticle>()
    private val streamDust = mutableListOf<StreamDustParticle>()

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            blendMode = BlendMode.SCREEN
        } else {
            @Suppress("DEPRECATION")
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
        }
    }

    private var cachedBitmap: Bitmap? = null
    private var cachedWidth = -1
    private var cachedHeight = -1
    private var cachedAlphaBoost = -1f
    private var renderAlphaBoost = 1f
    private var particlesWidth = -1
    private var particlesHeight = -1
    private var cachedColorSignature = 0
    private var cachedUseScreenBlend = true
    private var sparkleColors = defaultSparkleColors

    fun draw(canvas: Canvas, width: Int, height: Int, alphaBoost: Float, colors: IntArray, useScreenBlend: Boolean) {
        if (width <= 0 || height <= 0) return

        val clampedAlphaBoost = alphaBoost.coerceIn(MIN_ALPHA_BOOST, MAX_ALPHA_BOOST)
        val colorSignature = colors.contentHashCode()
        if (cachedColorSignature != colorSignature) {
            sparkleColors = colors.toSparkleColors().ifEmpty { defaultSparkleColors }
            cachedColorSignature = colorSignature
            cachedAlphaBoost = -1f
        }
        cachedUseScreenBlend = useScreenBlend

        val bitmap = cachedBitmap
        if (bitmap == null || cachedWidth != width || cachedHeight != height) {
            rebuildBitmap(width, height, clampedAlphaBoost)
        } else if (cachedAlphaBoost != clampedAlphaBoost) {
            redrawBitmap(clampedAlphaBoost)
        }

        cachedBitmap?.let {
            configureOverlayBlend()
            canvas.drawBitmap(it, 0f, 0f, overlayPaint)
        }
    }

    private fun configureOverlayBlend() {
        if (cachedUseScreenBlend) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                overlayPaint.blendMode = BlendMode.SCREEN
                overlayPaint.xfermode = null
            } else {
                @Suppress("DEPRECATION")
                overlayPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                overlayPaint.blendMode = null
            }
            @Suppress("DEPRECATION")
            overlayPaint.xfermode = null
        }
    }

    fun clear() {
        cachedBitmap?.recycle()
        cachedBitmap = null
        cachedWidth = -1
        cachedHeight = -1
        cachedAlphaBoost = -1f
        particlesWidth = -1
        particlesHeight = -1
        cachedColorSignature = 0
        cachedUseScreenBlend = true
        sparkleColors = defaultSparkleColors
        ambientDust.clear()
        streamDust.clear()
    }

    private fun rebuildBitmap(width: Int, height: Int, alphaBoost: Float) {
        cachedBitmap?.recycle()
        cachedWidth = width
        cachedHeight = height
        if (particlesWidth != width || particlesHeight != height) {
            createParticles(width.toFloat(), height.toFloat())
            particlesWidth = width
            particlesHeight = height
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        cachedBitmap = bitmap
        redrawBitmap(alphaBoost)
    }

    private fun redrawBitmap(alphaBoost: Float) {
        val bitmap = cachedBitmap ?: return
        cachedAlphaBoost = alphaBoost
        renderAlphaBoost = alphaBoost
        val bitmapCanvas = Canvas(bitmap)
        bitmap.eraseColor(Color.TRANSPARENT)
        drawStreamDust(bitmapCanvas, cachedWidth.toFloat(), cachedHeight.toFloat())
        drawAmbientDust(bitmapCanvas, cachedWidth.toFloat(), cachedHeight.toFloat())
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
                colorIndex = randomColorIndex()
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
                colorIndex = randomColorIndex()
            )
        }
    }

    private fun drawStreamDust(canvas: Canvas, width: Float, height: Float) {
        for (particle in streamDust) {
            val u = particle.u + particle.speed * 0.006f
            val x = u * (width + 180f) - 90f

            val y =
                ribbonY(
                    x = x,
                    lane = particle.lane,
                    time = STATIC_ANIMATION_TIME,
                    height = height
                ) +
                    sin(STATIC_ANIMATION_TIME * 0.45f + particle.phase) * 14f +
                    (particle.depth - 0.5f) * 52f

            val breathe =
                (sin(STATIC_ANIMATION_TIME * (0.75f + particle.depth) + particle.phase) + 1f) * 0.5f

            val lightSweep = max(
                0f,
                sin((x / width) * (PI.toFloat() * 2f) - STATIC_ANIMATION_TIME * 0.72f + particle.lane)
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
                color = sparkleColors[particle.colorIndex % sparkleColors.size]
            )
        }
    }

    private fun drawAmbientDust(canvas: Canvas, width: Float, height: Float) {
        for (particle in ambientDust) {
            val flowX =
                sin((particle.y + STATIC_ANIMATION_TIME * 20f) * 0.011f + particle.phase) * 0.16f +
                    cos(STATIC_ANIMATION_TIME * 0.14f + particle.depth * 5f) * 0.08f

            val flowY =
                cos((particle.x + STATIC_ANIMATION_TIME * 18f) * 0.01f + particle.phase) * 0.12f +
                    sin(STATIC_ANIMATION_TIME * 0.12f + particle.depth * 4f) * 0.06f

            val x = wrap(
                particle.x + flowX * particle.speed + cos(particle.drift) * 0.018f,
                width
            )
            val y = wrap(
                particle.y + flowY * particle.speed + sin(particle.drift) * 0.014f,
                height
            )

            val fade =
                (sin(STATIC_ANIMATION_TIME * (0.5f + particle.depth * 0.9f) + particle.phase) + 1f) * 0.5f

            val catchLight = max(
                0f,
                sin(STATIC_ANIMATION_TIME * 0.95f + particle.phase * 1.8f + x * 0.009f)
            )

            val alpha =
                particle.opacity * (0.38f + fade * 0.56f) +
                    catchLight * 0.055f * particle.depth

            val radius =
                particle.radius * (0.78f + fade * 0.2f + catchLight * 0.22f)

            drawDustDot(
                canvas = canvas,
                x = x,
                y = y,
                radius = radius,
                alpha = min(0.4f, alpha),
                color = sparkleColors[particle.colorIndex % sparkleColors.size]
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
        val finalAlpha = (alpha.coerceIn(0f, 1f) * renderAlphaBoost * 255).toInt()
            .coerceIn(0, 255)

        dotPaint.color = Color.argb(
            finalAlpha,
            color.r,
            color.g,
            color.b
        )

        canvas.drawCircle(
            x,
            y,
            radius,
            dotPaint
        )
    }

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

    private fun wrap(value: Float, maxValue: Float): Float {
        return when {
            value < -8f -> maxValue + 8f
            value > maxValue + 8f -> -8f
            else -> value
        }
    }

    private fun randomColorIndex(): Int {
        return random.nextInt(sparkleColors.size)
    }

    private fun randomRange(
        min: Float,
        max: Float
    ): Float {
        return min + random.nextFloat() * (max - min)
    }

    private companion object {
        private const val MIN_ALPHA_BOOST = 1f
        private const val MAX_ALPHA_BOOST = 20f
        private const val STATIC_ANIMATION_TIME = 0.01f
        private val defaultSparkleColors = listOf(
            DustColor(255, 244, 210),
            DustColor(165, 232, 255),
            DustColor(220, 180, 255),
            DustColor(255, 176, 122),
            DustColor(160, 195, 255)
        )

        private fun IntArray.toSparkleColors(): List<DustColor> {
            return distinct().map { color ->
                DustColor(
                    r = Color.red(color),
                    g = Color.green(color),
                    b = Color.blue(color)
                )
            }
        }
    }
}
