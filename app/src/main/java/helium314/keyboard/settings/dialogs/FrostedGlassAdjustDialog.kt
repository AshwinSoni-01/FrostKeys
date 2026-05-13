// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FrostedGlassAdjustDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val coroutineScope = rememberCoroutineScope()

    var blurRadius by remember { mutableStateOf(prefs.getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS)) }
    var keyTransparency by remember { mutableStateOf(prefs.getInt(Settings.PREF_FROSTED_KEY_TRANSPARENCY, Defaults.PREF_FROSTED_KEY_TRANSPARENCY)) }
    var bgTransparency by remember { mutableStateOf(prefs.getInt(Settings.PREF_FROSTED_BG_TRANSPARENCY, Defaults.PREF_FROSTED_BG_TRANSPARENCY)) }
    var colorBlend by remember { mutableStateOf(prefs.getInt(Settings.PREF_FROSTED_COLOR_BLEND, Defaults.PREF_FROSTED_COLOR_BLEND)) }
    var saturation by remember { mutableStateOf(prefs.getInt(Settings.PREF_FROSTED_SATURATION, Defaults.PREF_FROSTED_SATURATION)) }

    val testFieldState = rememberTextFieldState("", TextRange(0))

    var selectedImageUri by remember { 
        mutableStateOf(
            prefs.getString("pref_frosted_preview_wallpaper_uri", null)?.let { 
                try { android.net.Uri.parse(it) } catch (e: Throwable) { null }
            }
        ) 
    }
    var wallpaperBitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(selectedImageUri) {
        if (selectedImageUri != null) {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(selectedImageUri!!)?.use { stream ->
                        BitmapFactory.decodeStream(stream)?.let { bmp ->
                            bmp.asImageBitmap()
                        }
                    }
                } catch (e: Throwable) {
                    null
                }
            }
            if (bitmap != null) {
                wallpaperBitmap = bitmap
                prefs.edit().putString("pref_frosted_preview_wallpaper_uri", selectedImageUri!!.toString()).commit()
            } else {
                selectedImageUri = null
                wallpaperBitmap = null
                prefs.edit().remove("pref_frosted_preview_wallpaper_uri").commit()
            }
        } else {
            wallpaperBitmap = null
            prefs.edit().remove("pref_frosted_preview_wallpaper_uri").commit()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val view = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(view) {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (window != null) {
                window.setDimAmount(0f)
                window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    window.setFlags(
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    )
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            // Full screen background image behind dialog and keyboard
            if (wallpaperBitmap != null) {
                Image(
                    bitmap = wallpaperBitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // Dialog content container
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .systemBarsPadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.95f)
                        .wrapContentHeight()
                        .padding(16.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth()
                            .wrapContentHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = stringResource(R.string.theme_frosted_glass_settings_header),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        val focusRequester = remember { FocusRequester() }
                        OutlinedTextField(
                            state = testFieldState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester)
                                .padding(bottom = 12.dp),
                            placeholder = { Text(stringResource(R.string.frosted_glass_test_input_title)) },
                            lineLimits = TextFieldLineLimits.SingleLine,
                        )

                        // 1. Wallpaper Picker Row (styled like a slider row, placed first)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = MaterialTheme.shapes.medium)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Start
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("🖼️", style = MaterialTheme.typography.bodyLarge)
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (selectedImageUri == null) "Test with your wallpaper" else "Change wallpaper",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }

                            if (selectedImageUri != null && wallpaperBitmap != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Image(
                                        bitmap = wallpaperBitmap!!,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(MaterialTheme.shapes.small)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    IconButton(
                                        onClick = {
                                            selectedImageUri = null
                                            wallpaperBitmap = null
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Text(
                                            text = "✕",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }

                        // Slider 1: Blur Radius
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_blur_radius_title)}: $blurRadius px",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = blurRadius.toFloat(),
                                onValueChange = { newValue ->
                                    blurRadius = newValue.toInt()
                                    prefs.edit().putInt(Settings.PREF_FROSTED_BLUR_RADIUS, newValue.toInt()).commit()
                                    KeyboardSwitcher.getInstance().updateLiveFrostedGlassColors()
                                },
                                valueRange = 10f..150f
                            )
                        }

                        // Slider 2: Key Transparency
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_key_transparency_title)}: ${(100 * keyTransparency / 255)}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = keyTransparency.toFloat(),
                                onValueChange = { newValue ->
                                    keyTransparency = newValue.toInt()
                                    prefs.edit().putInt(Settings.PREF_FROSTED_KEY_TRANSPARENCY, newValue.toInt()).commit()
                                    KeyboardSwitcher.getInstance().updateLiveFrostedGlassColors()
                                },
                                valueRange = 0f..255f
                            )
                        }

                        // Slider 3: Background Transparency
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_bg_transparency_title)}: ${(100 * bgTransparency / 255)}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = bgTransparency.toFloat(),
                                onValueChange = { newValue ->
                                    bgTransparency = newValue.toInt()
                                    prefs.edit().putInt(Settings.PREF_FROSTED_BG_TRANSPARENCY, newValue.toInt()).commit()
                                    KeyboardSwitcher.getInstance().updateLiveFrostedGlassColors()
                                },
                                valueRange = 0f..255f
                            )
                        }

                        // Slider 4: Wallpaper Color Blend
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_color_blend_title)}: $colorBlend%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = colorBlend.toFloat(),
                                onValueChange = { newValue ->
                                    colorBlend = newValue.toInt()
                                    prefs.edit().putInt(Settings.PREF_FROSTED_COLOR_BLEND, newValue.toInt()).commit()
                                    KeyboardSwitcher.getInstance().updateLiveFrostedGlassColors()
                                },
                                valueRange = 0f..100f
                            )
                        }

                        // Slider 5: Saturation Boost
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_saturation_title)}: $saturation%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = saturation.toFloat(),
                                onValueChange = { newValue ->
                                    saturation = newValue.toInt()
                                    prefs.edit().putInt(Settings.PREF_FROSTED_SATURATION, newValue.toInt()).commit()
                                    KeyboardSwitcher.getInstance().updateLiveFrostedGlassColors()
                                },
                                valueRange = 50f..250f
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    blurRadius = Defaults.PREF_FROSTED_BLUR_RADIUS
                                    keyTransparency = Defaults.PREF_FROSTED_KEY_TRANSPARENCY
                                    bgTransparency = Defaults.PREF_FROSTED_BG_TRANSPARENCY
                                    colorBlend = Defaults.PREF_FROSTED_COLOR_BLEND
                                    saturation = Defaults.PREF_FROSTED_SATURATION

                                    prefs.edit()
                                        .putInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS)
                                        .putInt(Settings.PREF_FROSTED_KEY_TRANSPARENCY, Defaults.PREF_FROSTED_KEY_TRANSPARENCY)
                                        .putInt(Settings.PREF_FROSTED_BG_TRANSPARENCY, Defaults.PREF_FROSTED_BG_TRANSPARENCY)
                                        .putInt(Settings.PREF_FROSTED_COLOR_BLEND, Defaults.PREF_FROSTED_COLOR_BLEND)
                                        .putInt(Settings.PREF_FROSTED_SATURATION, Defaults.PREF_FROSTED_SATURATION)
                                        .commit()

                                    KeyboardSwitcher.getInstance().updateLiveFrostedGlassColors()
                                }
                            ) {
                                Text(stringResource(R.string.button_reset))
                            }

                            Button(
                                onClick = onDismissRequest
                            ) {
                                Text(stringResource(android.R.string.ok))
                            }
                        }

                        LaunchedEffect(Unit) {
                            focusRequester.requestFocus()
                        }
                    }
                }
            }
        }
    }
}
