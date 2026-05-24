// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.SubtypeLocaleUtils.displayName
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS

@Composable
fun MainSettingsScreen(
    onClickAbout: () -> Unit,
    onClickTextCorrection: () -> Unit,
    onClickPreferences: () -> Unit,
    onClickToolbar: () -> Unit,
    onClickGestureTyping: () -> Unit,
    onClickDataGathering: () -> Unit,
    onClickAdvanced: () -> Unit,
    onClickAppearance: () -> Unit,
    onClickLanguage: () -> Unit,
    onClickLayouts: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickCloud: () -> Unit,
    onClickBack: () -> Unit,
) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.ime_settings),
        settings = emptyList(),
    ) {
        val enabledSubtypes = remember { SubtypeSettings.getEnabledSubtypes(true) }
        val enabledSubtypeNames = remember(enabledSubtypes) {
            enabledSubtypes.joinToString(", ") { it.displayName() }
        }
        val showDataGathering = remember {
            JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS
        }
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            LazyColumn(contentPadding = innerPadding) {
                item("quick_setup") {
                    QuickSetupCard(
                        onClickGestureTyping = onClickGestureTyping,
                        onClickDictionaries = onClickDictionaries,
                        onClickCloud = onClickCloud,
                    )
                }

                item("language") {
                    Preference(
                        name = stringResource(R.string.language_and_layouts_title),
                        description = enabledSubtypeNames,
                        onClick = onClickLanguage,
                        icon = R.drawable.ic_settings_languages
                    ) { NextScreenIcon() }
                }
                item("preferences") {
                    Preference(
                        name = stringResource(R.string.settings_screen_preferences),
                        onClick = onClickPreferences,
                        icon = R.drawable.ic_settings_preferences
                    ) { NextScreenIcon() }
                }
                item("appearance") {
                    Preference(
                        name = stringResource(R.string.settings_screen_appearance),
                        onClick = onClickAppearance,
                        icon = R.drawable.ic_settings_appearance
                    ) { NextScreenIcon() }
                }
                item("toolbar") {
                    Preference(
                        name = stringResource(R.string.settings_screen_toolbar),
                        onClick = onClickToolbar,
                        icon = R.drawable.ic_settings_toolbar
                    ) { NextScreenIcon() }
                }
                item("cloud") {
                    Preference(
                        name = stringResource(R.string.cloud_features),
                        onClick = onClickCloud,
                        icon = R.drawable.ic_settings_advanced
                    ) { NextScreenIcon() }
                }
                item("gesture_typing") {
                    Preference(
                        name = stringResource(R.string.settings_screen_gesture),
                        description = if (JniUtils.sHaveGestureLib) null else stringResource(R.string.gesture_not_loaded_summary),
                        onClick = onClickGestureTyping,
                        icon = R.drawable.ic_settings_gesture
                    ) { NextScreenIcon() }
                }
                if (showDataGathering) {
                    item("data_gathering") {
                        Preference(
                            name = stringResource(R.string.gesture_data_screen),
                            onClick = onClickDataGathering,
                            icon = R.drawable.ic_settings_gesture
                        ) { NextScreenIcon() }
                    }
                }
                item("correction") {
                    Preference(
                        name = stringResource(R.string.settings_screen_correction),
                        onClick = onClickTextCorrection,
                        icon = R.drawable.ic_settings_correction
                    ) { NextScreenIcon() }
                }
                item("layouts") {
                    Preference(
                        name = stringResource(R.string.settings_screen_secondary_layouts),
                        onClick = onClickLayouts,
                        icon = R.drawable.ic_ime_switcher
                    ) { NextScreenIcon() }
                }
                item("dictionaries") {
                    Preference(
                        name = stringResource(R.string.dictionary_settings_category),
                        onClick = onClickDictionaries,
                        icon = R.drawable.ic_dictionary
                    ) { NextScreenIcon() }
                }
                item("advanced") {
                    Preference(
                        name = stringResource(R.string.settings_screen_advanced),
                        onClick = onClickAdvanced,
                        icon = R.drawable.ic_settings_advanced
                    ) { NextScreenIcon() }
                }
                item("about") {
                    Preference(
                        name = stringResource(R.string.settings_screen_about),
                        onClick = onClickAbout,
                        icon = R.drawable.ic_settings_about
                    ) { NextScreenIcon() }
                }
            }
        }
    }
}

@Composable
private fun QuickSetupCard(
    onClickGestureTyping: () -> Unit,
    onClickDictionaries: () -> Unit,
    onClickCloud: () -> Unit,
) {
    val ctx = LocalContext.current
    val b = (ctx.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")

    val enabledSubtypes = remember(b?.value) { SubtypeSettings.getEnabledSubtypes(true) }

    // Completion states:
    val isGestureComplete = remember(b?.value) { JniUtils.sHaveGestureLib }
    val isDictionaryComplete = remember(b?.value, enabledSubtypes) {
        enabledSubtypes.isNotEmpty() && enabledSubtypes.all { subtype ->
            val (userDicts, hasInternal) = getUserAndInternalDictionaries(ctx, subtype.locale())
            hasInternal || userDicts.isNotEmpty()
        }
    }
    val isCloudComplete = remember(b?.value) {
        ctx.prefs().getBoolean("pref_enable_cloud_features", false)
    }

    val allStepsComplete = isGestureComplete && isDictionaryComplete && isCloudComplete

    var isExpanded by rememberSaveable { mutableStateOf(false) }

    val accentColor = if (allStepsComplete) Color(0xFF388E3C) else MaterialTheme.colorScheme.primary
    val containerColor = if (allStepsComplete) Color(0xFF4CAF50).copy(alpha = 0.12f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = allStepsComplete) { isExpanded = !isExpanded }
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    painter = painterResource(if (allStepsComplete) R.drawable.ic_setup_check else R.drawable.ic_settings_about_wiki),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = if (allStepsComplete) "Setup is complete!" else stringResource(R.string.quick_setup_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.weight(1f)
                )
                if (allStepsComplete) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_left),
                        contentDescription = if (isExpanded) "Collapse" else "Expand",
                        tint = accentColor,
                        modifier = Modifier
                            .size(24.dp)
                            .rotate(if (isExpanded) 90f else -90f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (allStepsComplete) "All essential settings have been successfully configured." else stringResource(R.string.quick_setup_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(
                visible = !allStepsComplete || isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))

                    QuickSetupStep(
                        icon = R.drawable.ic_settings_gesture,
                        title = stringResource(R.string.quick_setup_gesture_title),
                        description = stringResource(R.string.quick_setup_gesture_desc),
                        onClick = onClickGestureTyping,
                        isComplete = isGestureComplete,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    QuickSetupStep(
                        icon = R.drawable.ic_dictionary,
                        title = stringResource(R.string.quick_setup_dict_title),
                        description = stringResource(R.string.quick_setup_dict_desc),
                        onClick = onClickDictionaries,
                        isComplete = isDictionaryComplete,
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    QuickSetupStep(
                        icon = R.drawable.ic_settings_advanced,
                        title = stringResource(R.string.quick_setup_cloud_title),
                        description = stringResource(R.string.quick_setup_cloud_desc),
                        onClick = onClickCloud,
                        isComplete = isCloudComplete,
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickSetupStep(
    icon: Int,
    title: String,
    description: String,
    onClick: () -> Unit,
    isComplete: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (isComplete) Color(0xFF388E3C) else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (isComplete) Color(0xFF388E3C) else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = if (isComplete) Color(0xFF388E3C).copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isComplete) {
            Icon(
                painter = painterResource(R.drawable.ic_setup_check),
                contentDescription = "Complete",
                tint = Color(0xFF388E3C),
                modifier = Modifier.size(20.dp)
            )
        } else {
            NextScreenIcon()
        }
    }
}

@Preview
@Composable
private fun PreviewScreen() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            MainSettingsScreen({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
        }
    }
}
