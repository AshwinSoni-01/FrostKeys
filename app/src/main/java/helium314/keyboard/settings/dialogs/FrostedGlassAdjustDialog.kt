// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.dialogs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.Color
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
import helium314.keyboard.keyboard.FrostedLiveValues
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext



private data class FrostedProfileSnapshot(
    val blurRadius: Int,
    val keyTransparency: Int,
    val bgTransparency: Int,
    val colorBlend: Int,
    val saturation: Int,
    val specialVibrancy: Int,
    val alphabetVibrancy: Int,
    val dustAlpha: Float
)

private data class FrostedSettingsSnapshot(
    val light: FrostedProfileSnapshot,
    val dark: FrostedProfileSnapshot,
    val dustEnabled: Boolean
)

@Composable
fun FrostedGlassAdjustDialog(
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.prefs()
    val coroutineScope = rememberCoroutineScope()

    var activeProfile by remember { mutableStateOf(if (helium314.keyboard.latin.utils.ResourceUtils.isNight(context.resources)) "dark" else "light") }


    // 1. Snapshot the initial state when the dialog opens
    val initialSnapshot = remember {
        val legacyDustAlpha = prefs.getFloat(Settings.PREF_FROSTED_DUST_ALPHA, Defaults.PREF_FROSTED_DUST_ALPHA)
        FrostedSettingsSnapshot(
            light = FrostedProfileSnapshot(
                blurRadius = prefs.getInt(Settings.PREF_FROSTED_BLUR_RADIUS, Defaults.PREF_FROSTED_BLUR_RADIUS),
                keyTransparency = prefs.getInt(Settings.PREF_FROSTED_KEY_TRANSPARENCY, Defaults.PREF_FROSTED_KEY_TRANSPARENCY),
                bgTransparency = prefs.getInt(Settings.PREF_FROSTED_BG_TRANSPARENCY, Defaults.PREF_FROSTED_BG_TRANSPARENCY),
                colorBlend = prefs.getInt(Settings.PREF_FROSTED_COLOR_BLEND, Defaults.PREF_FROSTED_COLOR_BLEND),
                saturation = prefs.getInt(Settings.PREF_FROSTED_SATURATION, Defaults.PREF_FROSTED_SATURATION),
                specialVibrancy = prefs.getInt(Settings.PREF_FROSTED_SPECIAL_VIBRANCY, Defaults.PREF_FROSTED_SPECIAL_VIBRANCY),
                alphabetVibrancy = prefs.getInt(Settings.PREF_FROSTED_ALPHABET_VIBRANCY, Defaults.PREF_FROSTED_ALPHABET_VIBRANCY),
                dustAlpha = legacyDustAlpha.coerceIn(1f, 10f)
            ),
            dark = FrostedProfileSnapshot(
                blurRadius = prefs.getInt(Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT, Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT),
                keyTransparency = prefs.getInt(Settings.PREF_FROSTED_KEY_TRANSPARENCY_NIGHT, Defaults.PREF_FROSTED_KEY_TRANSPARENCY_NIGHT),
                bgTransparency = prefs.getInt(Settings.PREF_FROSTED_BG_TRANSPARENCY_NIGHT, Defaults.PREF_FROSTED_BG_TRANSPARENCY_NIGHT),
                colorBlend = prefs.getInt(Settings.PREF_FROSTED_COLOR_BLEND_NIGHT, Defaults.PREF_FROSTED_COLOR_BLEND_NIGHT),
                saturation = prefs.getInt(Settings.PREF_FROSTED_SATURATION_NIGHT, Defaults.PREF_FROSTED_SATURATION_NIGHT),
                specialVibrancy = prefs.getInt(Settings.PREF_FROSTED_SPECIAL_VIBRANCY_NIGHT, Defaults.PREF_FROSTED_SPECIAL_VIBRANCY_NIGHT),
                alphabetVibrancy = prefs.getInt(Settings.PREF_FROSTED_ALPHABET_VIBRANCY_NIGHT, Defaults.PREF_FROSTED_ALPHABET_VIBRANCY_NIGHT),
                dustAlpha = prefs.getFloat(
                    Settings.PREF_FROSTED_DUST_ALPHA_NIGHT,
                    legacyDustAlpha
                ).coerceIn(1f, 10f)
            ),
            dustEnabled = prefs.getBoolean(Settings.PREF_FROSTED_DUST_ENABLED, Defaults.PREF_FROSTED_DUST_ENABLED)
        )
    }

    // 2. The temporary live state for the sliders
    var snapshot by remember { mutableStateOf(initialSnapshot) }
    var isSaved by remember { mutableStateOf(false) }

    fun applyLivePreview(
        profile: FrostedProfileSnapshot,
        dustEnabled: Boolean = snapshot.dustEnabled
    ) {
        KeyboardTheme.livePreviewValues = FrostedLiveValues(
            blurRadius = profile.blurRadius,
            keyTransparency = profile.keyTransparency,
            bgTransparency = profile.bgTransparency,
            colorBlend = profile.colorBlend,
            saturation = profile.saturation,
            specialVibrancy = profile.specialVibrancy,
            alphabetVibrancy = profile.alphabetVibrancy,
            dustEnabled = dustEnabled,
            dustAlpha = profile.dustAlpha.coerceIn(1f, 10f)
        )
        LatinIME.getInstance()?.requestFrostedLivePreviewRefresh()
    }

    fun writeSnapshotToPrefs(values: FrostedSettingsSnapshot) {
        prefs.edit()
            .putInt(Settings.PREF_FROSTED_BLUR_RADIUS, values.light.blurRadius)
            .putInt(Settings.PREF_FROSTED_KEY_TRANSPARENCY, values.light.keyTransparency)
            .putInt(Settings.PREF_FROSTED_BG_TRANSPARENCY, values.light.bgTransparency)
            .putInt(Settings.PREF_FROSTED_COLOR_BLEND, values.light.colorBlend)
            .putInt(Settings.PREF_FROSTED_SATURATION, values.light.saturation)
            .putInt(Settings.PREF_FROSTED_SPECIAL_VIBRANCY, values.light.specialVibrancy)
            .putInt(Settings.PREF_FROSTED_ALPHABET_VIBRANCY, values.light.alphabetVibrancy)
            .putFloat(Settings.PREF_FROSTED_DUST_ALPHA, values.light.dustAlpha.coerceIn(1f, 10f))
            .putInt(Settings.PREF_FROSTED_BLUR_RADIUS_NIGHT, values.dark.blurRadius)
            .putInt(Settings.PREF_FROSTED_KEY_TRANSPARENCY_NIGHT, values.dark.keyTransparency)
            .putInt(Settings.PREF_FROSTED_BG_TRANSPARENCY_NIGHT, values.dark.bgTransparency)
            .putInt(Settings.PREF_FROSTED_COLOR_BLEND_NIGHT, values.dark.colorBlend)
            .putInt(Settings.PREF_FROSTED_SATURATION_NIGHT, values.dark.saturation)
            .putInt(Settings.PREF_FROSTED_SPECIAL_VIBRANCY_NIGHT, values.dark.specialVibrancy)
            .putInt(Settings.PREF_FROSTED_ALPHABET_VIBRANCY_NIGHT, values.dark.alphabetVibrancy)
            .putFloat(Settings.PREF_FROSTED_DUST_ALPHA_NIGHT, values.dark.dustAlpha.coerceIn(1f, 10f))
            .putBoolean(Settings.PREF_FROSTED_DUST_ENABLED, values.dustEnabled)
            .apply()
    }

    fun requestFinalFrostedSync() {
        LatinIME.getInstance()?.requestFrostedHardThemeReset()
            ?: prefs.edit().putLong(Settings.PREF_FROSTED_GLASS_TRIGGER, System.currentTimeMillis()).apply()
    }

    // Helper to update current profile in snapshot and redraw the keyboard from staged values.
    fun updateCurrentProfile(update: (FrostedProfileSnapshot) -> FrostedProfileSnapshot) {
        val oldProfile = if (activeProfile == "light") snapshot.light else snapshot.dark
        val newProfile = update(oldProfile)
        
        snapshot = if (activeProfile == "light") {
            snapshot.copy(light = newProfile)
        } else {
            snapshot.copy(dark = newProfile)
        }

        applyLivePreview(newProfile)
    }

    fun updateSparklesEnabled(enabled: Boolean) {
        val newSnapshot = snapshot.copy(dustEnabled = enabled)
        snapshot = newSnapshot
        val profile = if (activeProfile == "light") newSnapshot.light else newSnapshot.dark
        applyLivePreview(profile, enabled)
    }

    // Current profile view for easier slider binding
    val currentValues = if (activeProfile == "light") snapshot.light else snapshot.dark

    // 3. The Tab Switch Effect is now handled explicitly in the Tab onClick to prevent "redraw floods"
    // during the unmounting process.

    // 4. The Rollback Mechanism
    DisposableEffect(Unit) {
        onDispose {
            if (!isSaved) {
                // User cancelled or dismissed! Roll back preferences to the snapshot
                writeSnapshotToPrefs(initialSnapshot)
            }
            
            // Clear spoofing state
            KeyboardTheme.themeOverride = null
            KeyboardTheme.livePreviewValues = null
            
            // Final catch-up refresh to sync keyboard with restored/final state
            requestFinalFrostedSync()
        }
    }

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

                        // Theme Profile Selector
                        TabRow(
                            selectedTabIndex = if (activeProfile == "light") 0 else 1,
                            modifier = Modifier.padding(bottom = 16.dp),
                            containerColor = Color.Transparent,
                            divider = {}
                        ) {
                            Tab(
                                selected = activeProfile == "light",
                                onClick = { 
                                    if (activeProfile != "light") {
                                        activeProfile = "light"
                                        KeyboardTheme.themeOverride = "light"
                                        applyLivePreview(snapshot.light)
                                    }
                                },
                                text = { Text("Light Profile") }
                            )
                            Tab(
                                selected = activeProfile == "dark",
                                onClick = { 
                                    if (activeProfile != "dark") {
                                        activeProfile = "dark"
                                        KeyboardTheme.themeOverride = "dark"
                                        applyLivePreview(snapshot.dark)
                                    }
                                },
                                text = { Text("Dark Profile") }
                            )
                        }

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
                                .padding(vertical = 12.dp)
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
                                text = "${stringResource(R.string.pref_frosted_blur_radius_title)}: ${currentValues.blurRadius} px",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = currentValues.blurRadius.toFloat(),
                                onValueChange = { newValue ->
                                    updateCurrentProfile { it.copy(blurRadius = newValue.toInt()) }
                                },
                                valueRange = 10f..150f
                            )
                        }

                        // Slider 2: Key Transparency
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_key_transparency_title)}: ${(100 * currentValues.keyTransparency / 255)}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = currentValues.keyTransparency.toFloat(),
                                onValueChange = { newValue ->
                                    updateCurrentProfile { it.copy(keyTransparency = newValue.toInt()) }
                                },
                                valueRange = 0f..255f
                            )
                        }

                        // Slider 3: Background Transparency
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_bg_transparency_title)}: ${(100 * currentValues.bgTransparency / 255)}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = currentValues.bgTransparency.toFloat(),
                                onValueChange = { newValue ->
                                    updateCurrentProfile { it.copy(bgTransparency = newValue.toInt()) }
                                },
                                valueRange = 0f..255f
                            )
                        }

                        // Slider 4: Wallpaper Color Blend
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_color_blend_title)}: ${currentValues.colorBlend}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = currentValues.colorBlend.toFloat(),
                                onValueChange = { newValue ->
                                    updateCurrentProfile { it.copy(colorBlend = newValue.toInt()) }
                                },
                                valueRange = 0f..100f
                            )
                        }

                        // Slider 5: Saturation Boost
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_saturation_title)}: ${currentValues.saturation}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = currentValues.saturation.toFloat(),
                                onValueChange = { newValue ->
                                    updateCurrentProfile { it.copy(saturation = newValue.toInt()) }
                                },
                                valueRange = 50f..250f
                            )
                        }

                        // Slider 6: Special Key Vibrance
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_special_vibrancy_title)}: ${currentValues.specialVibrancy}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = currentValues.specialVibrancy.toFloat(),
                                onValueChange = { newValue ->
                                    updateCurrentProfile { it.copy(specialVibrancy = newValue.toInt()) }
                                },
                                valueRange = 0f..500f
                            )
                        }

                        // Slider 7: Alphabet Key Vibrance
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.pref_frosted_alphabet_vibrancy_title)}: ${currentValues.alphabetVibrancy}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = currentValues.alphabetVibrancy.toFloat(),
                                onValueChange = { newValue ->
                                    updateCurrentProfile { it.copy(alphabetVibrancy = newValue.toInt()) }
                                },
                                valueRange = 0f..500f
                            )
                        }

                        if (!Defaults.LIMIT_EXPENSIVE_RENDERING) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = stringResource(R.string.pref_frosted_dust_enabled_title),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = snapshot.dustEnabled,
                                    onCheckedChange = { checked ->
                                        updateSparklesEnabled(checked)
                                    }
                                )
                            }

                            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                                Text(
                                    text = "${stringResource(R.string.pref_frosted_dust_alpha_title)}: ${
                                        String.format(java.util.Locale.US, "%.1f", currentValues.dustAlpha)
                                    }",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = currentValues.dustAlpha,
                                    onValueChange = { newValue ->
                                        updateCurrentProfile { it.copy(dustAlpha = newValue.coerceIn(1f, 10f)) }
                                    },
                                    valueRange = 1f..10f,
                                    enabled = snapshot.dustEnabled
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val resetProfile = if (activeProfile == "light") {
                                        FrostedProfileSnapshot(
                                            blurRadius = Defaults.PREF_FROSTED_BLUR_RADIUS,
                                            keyTransparency = Defaults.PREF_FROSTED_KEY_TRANSPARENCY,
                                            bgTransparency = Defaults.PREF_FROSTED_BG_TRANSPARENCY,
                                            colorBlend = Defaults.PREF_FROSTED_COLOR_BLEND,
                                            saturation = Defaults.PREF_FROSTED_SATURATION,
                                            specialVibrancy = Defaults.PREF_FROSTED_SPECIAL_VIBRANCY,
                                            alphabetVibrancy = Defaults.PREF_FROSTED_ALPHABET_VIBRANCY,
                                            dustAlpha = Defaults.PREF_FROSTED_DUST_ALPHA
                                        )
                                    } else {
                                        FrostedProfileSnapshot(
                                            blurRadius = Defaults.PREF_FROSTED_BLUR_RADIUS_NIGHT,
                                            keyTransparency = Defaults.PREF_FROSTED_KEY_TRANSPARENCY_NIGHT,
                                            bgTransparency = Defaults.PREF_FROSTED_BG_TRANSPARENCY_NIGHT,
                                            colorBlend = Defaults.PREF_FROSTED_COLOR_BLEND_NIGHT,
                                            saturation = Defaults.PREF_FROSTED_SATURATION_NIGHT,
                                            specialVibrancy = Defaults.PREF_FROSTED_SPECIAL_VIBRANCY_NIGHT,
                                            alphabetVibrancy = Defaults.PREF_FROSTED_ALPHABET_VIBRANCY_NIGHT,
                                            dustAlpha = Defaults.PREF_FROSTED_DUST_ALPHA_NIGHT
                                        )
                                    }
                                    snapshot = if (activeProfile == "light") {
                                        snapshot.copy(
                                            light = resetProfile,
                                            dustEnabled = Defaults.PREF_FROSTED_DUST_ENABLED
                                        )
                                    } else {
                                        snapshot.copy(
                                            dark = resetProfile,
                                            dustEnabled = Defaults.PREF_FROSTED_DUST_ENABLED
                                        )
                                    }
                                    applyLivePreview(
                                        resetProfile,
                                        Defaults.PREF_FROSTED_DUST_ENABLED
                                    )
                                }
                            ) {
                                Text(stringResource(R.string.button_reset))
                            }

                            TextButton(
                                onClick = onDismissRequest
                            ) {
                                Text(stringResource(android.R.string.cancel))
                            }

                            Button(
                                onClick = {
                                    isSaved = true
                                    writeSnapshotToPrefs(snapshot)
                                    onDismissRequest()
                                }
                            ) {
                                Text(stringResource(R.string.save))
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

