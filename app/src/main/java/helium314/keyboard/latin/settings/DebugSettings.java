/*
 * Copyright (C) 2010 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.settings;

/**
 * Debug settings for the application.
 */
public final class DebugSettings {
    public static final String PREF_DEBUG_MODE = "debug_mode";
    public static final String PREF_FORCE_NON_DISTINCT_MULTITOUCH = "force_non_distinct_multitouch";
    public static final String PREF_SLIDING_KEY_INPUT_PREVIEW = "sliding_key_input_preview";
    public static final String PREF_SHOW_DEBUG_SETTINGS = "show_debug_settings";
    public static final String PREF_KEY_DUMP_DICT_PREFIX = "dump_dictionaries";
    public static final String PREF_TEXT_COMMIT_DIAGNOSTICS = "text_commit_diagnostics";
    public static final String PREF_TEXT_COMMIT_EXPERIMENT_MODE = "text_commit_experiment_mode";

    public static final String TEXT_COMMIT_EXPERIMENT_MODE_NORMAL = "normal";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_NO_BATCH =
            "telegram_no_batch";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_NO_BATCH_COMBINED_SEPARATOR =
            "telegram_no_batch_combined_separator";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_RAW_COMMIT =
            "telegram_raw_commit";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_COMPAT_RAW_COMMIT =
            "compat_raw_commit";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_COMPAT_SHADOW_SUGGESTIONS =
            "compat_shadow_suggestions";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_RAW_COMMIT_ALL =
            "raw_commit_all";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_INTERNAL_COMPOSE =
            "telegram_internal_compose";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_COMPAT_INTERNAL_COMPOSE =
            "compat_internal_compose";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_INTERNAL_COMPOSE_ALL =
            "internal_compose_all";
    public static final String TEXT_COMMIT_EXPERIMENT_MODE_TELEGRAM_SHADOW_COMPOSE =
            "telegram_shadow_compose";

    public static final String PREF_SHOW_SUGGESTION_INFOS = "show_suggestion_infos";
    private DebugSettings() {
        // This class is not publicly instantiable.
    }
}
