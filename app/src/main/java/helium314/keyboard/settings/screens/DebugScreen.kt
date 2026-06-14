// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.DictionaryDumpBroadcastReceiver
import helium314.keyboard.latin.DictionaryFacilitator
import helium314.keyboard.latin.R
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceCategory
import helium314.keyboard.latin.utils.previewDark
import androidx.core.content.edit

@Composable
fun DebugScreen(
    onClickBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val settings = createDebugSettings(ctx)
    val items = listOfNotNull(
        if (!BuildConfig.DEBUG) DebugSettings.PREF_SHOW_DEBUG_SETTINGS else null,
        DebugSettings.PREF_DEBUG_MODE,
        DebugSettings.PREF_SHOW_SUGGESTION_INFOS,
        DebugSettings.PREF_FORCE_NON_DISTINCT_MULTITOUCH,
        DebugSettings.PREF_SLIDING_KEY_INPUT_PREVIEW,
        DebugSettings.PREF_TEXT_COMMIT_DIAGNOSTICS,
        DebugSettings.PREF_TEXT_COMMIT_EXPERIMENT_MODE,
        R.string.prefs_dump_dynamic_dicts
    ) + DictionaryFacilitator.DYNAMIC_DICTIONARY_TYPES.map { DebugSettings.PREF_KEY_DUMP_DICT_PREFIX + it }
    SearchSettingsScreen(
        onClickBack = {
            if (needsRestart) {
                val intent = Intent.makeRestartActivityTask(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.component)
                intent.setPackage(ctx.packageName)
                ctx.startActivity(intent)
                Runtime.getRuntime().exit(0)
            }
            onClickBack()
        },
        title = stringResource(R.string.debug_settings_title),
        settings = emptyList()
    ) {
        // the preferences are not in SettingsContainer, so set content instead
        LazyColumn {
            items(items, key = { it }) { item ->
                if (item is Int) PreferenceCategory(stringResource(item))
                else settings.first { it.key == item }.Preference()
            }
        }
    }
}

private var needsRestart = false

private fun createDebugSettings(context: Context) = listOf(
    Setting(context, DebugSettings.PREF_SHOW_DEBUG_SETTINGS, R.string.prefs_show_debug_settings) { setting ->
        val prefs = LocalContext.current.prefs()
        SwitchPreference(setting, false)
        { if (!it) prefs.edit { putBoolean(DebugSettings.PREF_DEBUG_MODE, false) } }
    },
    Setting(context, DebugSettings.PREF_DEBUG_MODE, R.string.prefs_debug_mode) { setting ->
        val prefs = LocalContext.current.prefs()
        SwitchPreference(
            name = setting.title,
            key = setting.key,
            description = stringResource(R.string.version_text, BuildConfig.VERSION_NAME),
            default = Defaults.PREF_DEBUG_MODE,
        ) {
            if (!it) {
                prefs.edit {
                    putBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, false)
                    putBoolean(DebugSettings.PREF_TEXT_COMMIT_DIAGNOSTICS, false)
                    putString(DebugSettings.PREF_TEXT_COMMIT_EXPERIMENT_MODE,
                        Defaults.PREF_TEXT_COMMIT_EXPERIMENT_MODE)
                }
                DebugFlags.setTextCommitDiagnosticsEnabled(false)
                DebugFlags.setTextCommitExperimentMode(Defaults.PREF_TEXT_COMMIT_EXPERIMENT_MODE)
            }
            needsRestart = true
        }
    },
    Setting(context, DebugSettings.PREF_SHOW_SUGGESTION_INFOS, R.string.prefs_show_suggestion_infos) {
        SwitchPreference(it, Defaults.PREF_SHOW_SUGGESTION_INFOS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, DebugSettings.PREF_FORCE_NON_DISTINCT_MULTITOUCH, R.string.prefs_force_non_distinct_multitouch) {
        SwitchPreference(it, Defaults.PREF_FORCE_NON_DISTINCT_MULTITOUCH) { needsRestart = true }
    },
    Setting(context, DebugSettings.PREF_SLIDING_KEY_INPUT_PREVIEW, R.string.sliding_key_input_preview, R.string.sliding_key_input_preview_summary) { def ->
        SwitchPreference(def, Defaults.PREF_SLIDING_KEY_INPUT_PREVIEW)
    },
    Setting(context, DebugSettings.PREF_TEXT_COMMIT_DIAGNOSTICS, R.string.prefs_text_commit_diagnostics, R.string.prefs_text_commit_diagnostics_summary) {
        SwitchPreference(it, Defaults.PREF_TEXT_COMMIT_DIAGNOSTICS) { enabled ->
            DebugFlags.setTextCommitDiagnosticsEnabled(enabled)
        }
    },
    Setting(context, DebugSettings.PREF_TEXT_COMMIT_EXPERIMENT_MODE, R.string.prefs_text_commit_experiment_mode, R.string.prefs_text_commit_experiment_mode_summary) {
        val items = listOf(
            stringResource(R.string.text_commit_experiment_mode_normal_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_NORMAL,
            stringResource(R.string.text_commit_experiment_mode_telegram_no_batch_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_NO_BATCH,
            stringResource(R.string.text_commit_experiment_mode_telegram_no_batch_combined_separator_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_NO_BATCH_COMBINED_SEPARATOR,
            stringResource(R.string.text_commit_experiment_mode_telegram_raw_commit_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_RAW_COMMIT,
            stringResource(R.string.text_commit_experiment_mode_compat_raw_commit_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_COMPAT_RAW_COMMIT,
            stringResource(R.string.text_commit_experiment_mode_compat_shadow_suggestions_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_COMPAT_SHADOW_SUGGESTIONS,
            stringResource(R.string.text_commit_experiment_mode_raw_commit_all_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_RAW_COMMIT_ALL,
            stringResource(R.string.text_commit_experiment_mode_telegram_internal_compose_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_INTERNAL_COMPOSE,
            stringResource(R.string.text_commit_experiment_mode_compat_internal_compose_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_COMPAT_INTERNAL_COMPOSE,
            stringResource(R.string.text_commit_experiment_mode_internal_compose_all_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_INTERNAL_COMPOSE_ALL,
            stringResource(R.string.text_commit_experiment_mode_telegram_shadow_compose_entry) to DebugSettings.TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_SHADOW_COMPOSE
        )
        ListPreference(it, items, Defaults.PREF_TEXT_COMMIT_EXPERIMENT_MODE) { mode ->
            DebugFlags.setTextCommitExperimentMode(mode)
        }
    },
) + DictionaryFacilitator.DYNAMIC_DICTIONARY_TYPES.map { type ->
    Setting(context, DebugSettings.PREF_KEY_DUMP_DICT_PREFIX + type, R.string.button_default) {
        val ctx = LocalContext.current
        Preference(
            name = "Dump $type dictionary",
            onClick = {
                val intent = Intent(DictionaryDumpBroadcastReceiver.DICTIONARY_DUMP_INTENT_ACTION)
                intent.setPackage(context.packageName)
                intent.putExtra(DictionaryDumpBroadcastReceiver.DICTIONARY_NAME_KEY, type)
                ctx.sendBroadcast(intent)
            }
        )
    }
}

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            DebugScreen { }
        }
    }
}
