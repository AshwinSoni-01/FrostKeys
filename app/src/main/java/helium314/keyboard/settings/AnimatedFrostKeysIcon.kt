// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.graphics.Matrix
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AnimatedFrostKeysIcon(
    modifier: Modifier = Modifier,
    isAnimating: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary
) {
    val baseShapes = remember { getMaterialShapesList() }
    val baseShapesShuffledList = remember {
        List(10) { baseShapes.shuffled() }
    }

    val keyConfigs = remember {
        listOf(
            // Row 1
            KeyConfig(centerX = 260.0f, centerY = 360.0f, width = 101.0f, height = 101.0f),
            KeyConfig(centerX = 420.0f, centerY = 360.0f, width = 100.0f, height = 100.0f),
            KeyConfig(centerX = 580.0f, centerY = 360.0f, width = 105.0f, height = 105.0f),
            KeyConfig(centerX = 740.0f, centerY = 360.0f, width = 101.0f, height = 101.0f),
            // Row 2
            KeyConfig(centerX = 260.0f, centerY = 505.0f, width = 109.0f, height = 100.0f),
            KeyConfig(centerX = 420.0f, centerY = 505.0f, width = 101.0f, height = 101.0f),
            KeyConfig(centerX = 580.0f, centerY = 505.0f, width = 109.0f, height = 109.0f),
            KeyConfig(centerX = 740.0f, centerY = 505.0f, width = 99.0f, height = 103.0f),
            // Row 3
            KeyConfig(centerX = 275.0f, centerY = 645.0f, width = 101.0f, height = 101.0f),
            KeyConfig(centerX = 725.0f, centerY = 645.0f, width = 101.0f, height = 101.0f)
        )
    }

    var currentShapeIndex by remember { mutableStateOf(0) }
    val progress = remember { Animatable(0f) }

    LaunchedEffect(isAnimating) {
        if (isAnimating) {
            while (true) {
                val job = launch {
                    progress.animateTo(
                        targetValue = 1f,
                        animationSpec = spring(
                            dampingRatio = 0.7f,
                            stiffness = 70f,
                            visibilityThreshold = 0.1f
                        )
                    )
                }
                delay(2300) // One transition for each inhale and exhale breath (2300ms)
                job.join()
                currentShapeIndex = (currentShapeIndex + 1) % baseShapes.size
                progress.snapTo(0f)
            }
        }
    }

    val morphs = remember(currentShapeIndex) {
        List(10) { index ->
            val shuffled = baseShapesShuffledList[index]
            val currentShape = shuffled[currentShapeIndex]
            val nextShape = shuffled[(currentShapeIndex + 1) % shuffled.size]
            Morph(currentShape, nextShape)
        }
    }

    val androidPath = remember { AndroidPath() }
    val matrix = remember { Matrix() }
    val rectF = remember { RectF() }
    val chinPath = remember { Path() }

    Canvas(modifier = modifier) {
        val scale = size.width / 1000f

        // 1. Draw static border
        drawRoundRect(
            color = tint,
            topLeft = Offset(137.5f * scale, 262.5f * scale),
            size = Size(725f * scale, 475f * scale),
            cornerRadius = CornerRadius(100f * scale),
            style = Stroke(width = 25f * scale)
        )

        // 2. Draw spacebar
        drawRoundRect(
            color = tint,
            topLeft = Offset(375f * scale, 605f * scale),
            size = Size(250f * scale, 80f * scale),
            cornerRadius = CornerRadius(40f * scale)
        )

        // 3. Draw chin path
        chinPath.reset()
        chinPath.moveTo(174f * scale, 730f * scale)
        chinPath.lineTo(826f * scale, 730f * scale)
        chinPath.cubicTo(
            826f * scale, 752.091f * scale,
            808.091f * scale, 770f * scale,
            786f * scale, 770f * scale
        )
        chinPath.lineTo(214f * scale, 770f * scale)
        chinPath.cubicTo(
            191.909f * scale, 770f * scale,
            174f * scale, 752.091f * scale,
            174f * scale, 730f * scale
        )
        chinPath.close()
        drawPath(path = chinPath, color = tint)

        // 4. Draw each morphing key (different shapes, synchronized transitions)
        val progressVal = progress.value
        // Clockwise rotation of 90 degrees during transition, settling at 0 degrees (upright)
        val rotation = (progressVal - 1f) * 90f

        keyConfigs.forEachIndexed { index, config ->
            androidPath.reset()
            val morph = morphs[index]
            morph.toPath(progressVal, androidPath)
            androidPath.computeBounds(rectF, true)

            val currentWidth = rectF.width()
            val currentHeight = rectF.height()
            val currentCenterX = rectF.centerX()
            val currentCenterY = rectF.centerY()

            if (currentWidth > 0f && currentHeight > 0f) {
                matrix.reset()
                // Translate center of shape to (0, 0)
                matrix.postTranslate(-currentCenterX, -currentCenterY)
                // Scale to fit the config dimensions exactly
                matrix.postScale(
                    config.width * scale / currentWidth,
                    config.height * scale / currentHeight
                )
                // Rotate by current rotation (bouncy clockwise spin settling at 0 degrees)
                matrix.postRotate(rotation)
                // Translate to the config center
                matrix.postTranslate(config.centerX * scale, config.centerY * scale)

                androidPath.transform(matrix)
                val composePath = androidPath.asComposePath()
                drawPath(path = composePath, color = tint)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun getMaterialShapesList(): List<RoundedPolygon> {
    return listOf(
        MaterialShapes.Circle,
        MaterialShapes.Square,
        MaterialShapes.Triangle,
        MaterialShapes.Diamond,
        MaterialShapes.Pentagon,
        MaterialShapes.Pill,
        MaterialShapes.Oval,
        MaterialShapes.Arch,
        MaterialShapes.Arrow,
        MaterialShapes.Boom,
        MaterialShapes.Bun,
        MaterialShapes.Burst,
        MaterialShapes.ClamShell,
        MaterialShapes.Clover4Leaf,
        MaterialShapes.Clover8Leaf,
        MaterialShapes.Cookie4Sided,
        MaterialShapes.Cookie6Sided,
        MaterialShapes.Cookie7Sided,
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Cookie12Sided,
        MaterialShapes.Fan,
        MaterialShapes.Flower,
        MaterialShapes.Gem,
        MaterialShapes.Ghostish,
        MaterialShapes.Heart,
        MaterialShapes.PixelCircle,
        MaterialShapes.PixelTriangle,
        MaterialShapes.Puffy,
        MaterialShapes.PuffyDiamond,
        MaterialShapes.SemiCircle,
        MaterialShapes.Slanted,
        MaterialShapes.SoftBoom,
        MaterialShapes.SoftBurst,
        MaterialShapes.Sunny,
        MaterialShapes.VerySunny
    )
}

private class KeyConfig(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float
)
