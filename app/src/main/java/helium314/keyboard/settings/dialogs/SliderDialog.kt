// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliderDialog(
    onDismissRequest: () -> Unit,
    onDone: (Float) -> Unit,
    initialValue: Float,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    showDefault: Boolean = false,
    onDefault: () -> Unit = { },
    onValueChanged: (Float) -> Unit = { },
    title: (@Composable () -> Unit)? = null,
    intermediateSteps: Int? = null,
    positionString: (@Composable (Float) -> String) = { it.toString() },
) {
    var sliderPosition by remember { mutableFloatStateOf(initialValue) }
    val haptic = LocalHapticFeedback.current
    var lastHapticValue by remember { mutableFloatStateOf(initialValue) }

    ThreeButtonAlertDialog(
        onDismissRequest = onDismissRequest,
        neutralButtonText = if (showDefault) stringResource(R.string.button_default) else null,
        onNeutral = { onDismissRequest(); onDefault() },
        onConfirmed = { onDone(sliderPosition) },
        modifier = modifier,
        title = title,
        content = {
            CompositionLocalProvider(
                LocalTextStyle provides MaterialTheme.typography.bodyLarge
            ) {
                Column {
                    val stepsCount = intermediateSteps ?: 0
                    val interactionSource = remember { MutableInteractionSource() }
                    Slider(
                        value = sliderPosition,
                        onValueChange = {
                            sliderPosition = it
                            if (stepsCount > 0) {
                                val stepVal = (range.endInclusive - range.start) / (stepsCount + 1)
                                val snappedVal = (range.start + ((it - range.start) / stepVal + 0.5f).toInt() * stepVal).coerceIn(range)
                                if (kotlin.math.abs(snappedVal - lastHapticValue) > 0.01f) {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    lastHapticValue = snappedVal
                                }
                            } else {
                                if (it.toInt() != lastHapticValue.toInt()) {
                                    haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    lastHapticValue = it.toInt().toFloat()
                                }
                            }
                        },
                        onValueChangeFinished = { onValueChanged(sliderPosition) },
                        valueRange = range,
                        steps = stepsCount,
                        interactionSource = interactionSource,
                        thumb = { _ ->
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(28.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(2.dp)
                                    )
                            )
                        }
                    )
                    Text(positionString(sliderPosition))
                }
            }
        },
    )
}

@Preview
@Composable
private fun PreviewSliderDialog() {
    Theme(previewDark) {
        SliderDialog(
            onDismissRequest = { },
            onDone = { },
            initialValue = 100f,
            range = 0f..500f,
            title = { Text("move it") },
            showDefault = true
        )
    }
}
