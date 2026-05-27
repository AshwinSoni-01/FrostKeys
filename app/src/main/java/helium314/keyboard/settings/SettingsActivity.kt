// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import helium314.keyboard.compat.locale
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.InputAttributes
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.BackButton
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.latin.utils.ExecutorUtils
import helium314.keyboard.latin.utils.GestureDataGatheringSettings
import helium314.keyboard.latin.utils.JniUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import helium314.keyboard.latin.utils.cleanUnusedMainDicts
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.NewDictionaryDialog
import helium314.keyboard.settings.screens.gesturedata.END_DATE_EPOCH_MILLIS
import helium314.keyboard.settings.screens.gesturedata.TWO_WEEKS_IN_MILLIS
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// todo: with compose, app startup is slower and UI needs some "warmup" time to be snappy
//  maybe baseline profiles help?
//  https://developer.android.com/codelabs/android-baseline-profiles-improve
//  https://developer.android.com/codelabs/jetpack-compose-performance#2
//  https://developer.android.com/topic/performance/baselineprofiles/overview
// todo: consider viewModel, at least for LanguageScreen and ColorsScreen it might help making them less awkward and complicated
open class SettingsActivity : ComponentActivity(), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs by lazy { this.prefs() }
    val prefChanged = MutableStateFlow(0) // simple counter, as the only relevant information is that something changed
    fun prefChanged() = prefChanged.value++
    private val dictUriFlow = MutableStateFlow<Uri?>(null)
    private val cachedDictionaryFile by lazy { File(this.cacheDir.path + File.separator + "temp_dict") }
    private val crashReportFiles = MutableStateFlow<List<File>>(emptyList())
    private var paused = true

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val lastUpdateTime = packageInfo.lastUpdateTime
            val savedLastUpdateTime = prefs.getLong("pref_telegram_last_app_update_time", 0L)
            if (lastUpdateTime > savedLastUpdateTime) {
                prefs.edit()
                    .putLong("pref_telegram_last_app_update_time", lastUpdateTime)
                    .putBoolean("pref_telegram_popup_v2_dismissed", false)
                    .putBoolean("pref_telegram_joined", false)
                    .apply()
            }
        }
        if (Settings.getValues() == null) {
            val inputAttributes = InputAttributes(EditorInfo(), false, packageName)
            Settings.getInstance().loadSettings(this, resources.configuration.locale(), inputAttributes)
        }
        ExecutorUtils.getBackgroundExecutor(ExecutorUtils.KEYBOARD).execute { cleanUnusedMainDicts(this) }
        crashReportFiles.value = findCrashReports(!BuildConfig.DEBUG && !DebugFlags.DEBUG_ENABLED)
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        if (!UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm))
            KeyboardIconsSet.instance.loadIcons(this) // otherwise we may crash when displaying toolbar keys

        settingsContainer = SettingsContainer(this)

        val spellchecker = intent?.getBooleanExtra("spellchecker", false) ?: false

        val cv = ComposeView(context = this)
        setContentView(cv)
        cv.setContent {
            Theme {
                Surface {
                    val dictUri by dictUriFlow.collectAsState()
                    val crashReports by crashReportFiles.collectAsState()
                    val crashFilePicker = filePicker { saveCrashReports(it) }
                    var showWelcomeWizard by rememberSaveable { mutableStateOf(
                        !UncachedInputMethodManagerUtils.isThisImeCurrent(this, imm)
                                || !UncachedInputMethodManagerUtils.isThisImeEnabled(this, imm)
                    ) }
                    val popupDismissed = prefs.getBoolean("pref_telegram_popup_v2_dismissed", false)
                    val telegramJoined = prefs.getBoolean("pref_telegram_joined", false)
                    var showTelegramPopup by rememberSaveable {
                        mutableStateOf(!popupDismissed && !telegramJoined)
                    }
                    if (spellchecker)
                        Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { innerPadding ->
                            Column(Modifier.padding(innerPadding)) {
                                TopAppBar(
                                    title = { Text(stringResource(R.string.android_spell_checker_settings)) },
                                    windowInsets = WindowInsets(0),
                                    navigationIcon = {
                                        BackButton { this@SettingsActivity.finish() }
                                    },
                                )
                                settingsContainer[Settings.PREF_USE_CONTACTS]!!.Preference()
                                settingsContainer[Settings.PREF_USE_APPS]!!.Preference()
                                settingsContainer[Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE]!!.Preference()
                                settingsContainer[Settings.PREF_SPELLCHECK_SUGGEST]!!.Preference()
                            }
                        }
                    else {
                        SettingsNavHost(onClickBack = { this.finish() })
                        if (showWelcomeWizard) {
                            WelcomeWizard(close = { showWelcomeWizard = false }, finish = this::finish)
                        } else if (crashReports.isNotEmpty()) {
                            ConfirmationDialog(
                                cancelButtonText = "ignore",
                                onDismissRequest = { crashReportFiles.value = emptyList() },
                                neutralButtonText = "delete",
                                onNeutral = { crashReports.forEach { it.delete() }; crashReportFiles.value = emptyList() },
                                confirmButtonText = "get",
                                onConfirmed = {
                                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                    intent.addCategory(Intent.CATEGORY_OPENABLE)
                                    intent.putExtra(Intent.EXTRA_TITLE, "crash_reports.zip")
                                    intent.type = "application/zip"
                                    crashFilePicker.launch(intent)
                                },
                                content = { Text("Crash report files found") },
                            )
                        } else {
                            if (JniUtils.sHaveGestureLib && System.currentTimeMillis() < END_DATE_EPOCH_MILLIS + TWO_WEEKS_IN_MILLIS) {
                                GestureDataGatheringSettings.GestureDataPromotionReminderDialog()
                            }
                             if (showTelegramPopup) {
                                  androidx.compose.ui.window.Dialog(
                                      onDismissRequest = {},
                                      properties = androidx.compose.ui.window.DialogProperties(
                                          dismissOnBackPress = false,
                                          dismissOnClickOutside = false
                                      )
                                  ) {
                                     Surface(
                                         shape = RoundedCornerShape(24.dp),
                                         color = MaterialTheme.colorScheme.surface,
                                         contentColor = androidx.compose.material3.contentColorFor(MaterialTheme.colorScheme.surface),
                                         modifier = Modifier.widthIn(min = 280.dp, max = 340.dp)
                                     ) {
                                         Column(
                                             modifier = Modifier.padding(24.dp),
                                             horizontalAlignment = Alignment.CenterHorizontally
                                         ) {
                                              Image(
                                                  painter = painterResource(R.drawable.ic_telegram),
                                                  contentDescription = "Telegram Logo",
                                                  modifier = Modifier
                                                      .size(96.dp)
                                                      .padding(bottom = 16.dp)
                                              )
                                             Text(
                                                 text = "Stay Connected",
                                                 style = MaterialTheme.typography.titleLarge,
                                                 fontWeight = FontWeight.Bold,
                                                 color = MaterialTheme.colorScheme.onSurface,
                                                 textAlign = TextAlign.Center,
                                                 modifier = Modifier.padding(bottom = 12.dp)
                                             )
                                             Text(
                                                 text = "Join my Telegram channel to get update announcements, early sneak peeks, and a chance to share your feedback and ideas. Be part of the app’s active development journey! :)",
                                                 style = MaterialTheme.typography.bodyMedium,
                                                 color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                 textAlign = TextAlign.Center,
                                                 modifier = Modifier.padding(bottom = 24.dp)
                                             )
                                             Row(
                                                 modifier = Modifier.fillMaxWidth(),
                                                 horizontalArrangement = Arrangement.spacedBy(8.dp)
                                             ) {
                                                 OutlinedButton(
                                                     onClick = {
                                                         prefs.edit().putBoolean("pref_telegram_popup_v2_dismissed", true).apply()
                                                         showTelegramPopup = false
                                                     },
                                                     shape = RoundedCornerShape(50),
                                                     modifier = Modifier.weight(1f),
                                                     border = androidx.compose.foundation.BorderStroke(
                                                         width = 1.dp,
                                                         color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                                     ),
                                                     colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                                         contentColor = MaterialTheme.colorScheme.onSurface
                                                     ),
                                                     contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                                                 ) {
                                                     Text(
                                                         text = "Maybe Later",
                                                         fontWeight = FontWeight.Medium
                                                     )
                                                 }

                                                 Button(
                                                     onClick = {
                                                         val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/FrostKeys"))
                                                         startActivity(intent)
                                                         prefs.edit()
                                                             .putBoolean("pref_telegram_popup_v2_dismissed", true)
                                                             .putBoolean("pref_telegram_joined", true)
                                                             .apply()
                                                         showTelegramPopup = false
                                                     },
                                                     shape = RoundedCornerShape(50),
                                                     modifier = Modifier.weight(1f),
                                                     colors = ButtonDefaults.buttonColors(
                                                         containerColor = MaterialTheme.colorScheme.primary,
                                                         contentColor = MaterialTheme.colorScheme.onPrimary
                                                     ),
                                                     contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
                                                 ) {
                                                     Text(
                                                         text = "Join Now",
                                                         fontWeight = FontWeight.Bold
                                                     )
                                                 }
                                             }
                                         }
                                     }
                                 }
                             }
                        }
                    }
                    if (dictUri != null) {
                        NewDictionaryDialog(
                            onDismissRequest = { dictUriFlow.value = null },
                            cachedFile = cachedDictionaryFile,
                            mainLocale = null
                        )
                    }
                }
            }
        }

        if (intent?.action == Intent.ACTION_VIEW) {
            intent?.data?.let {
                cachedDictionaryFile.delete()
                FileUtils.copyContentUriToNewFile(it, this, cachedDictionaryFile)
                dictUriFlow.value = it
            }
            intent = null
        }

        enableEdgeToEdge()
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onPause() {
        super.onPause()
        setForceTheme(null, null)
        paused = true
    }

    override fun onResume() {
        super.onResume()
        paused = false
        if (clickedTelegramJoin) {
            clickedTelegramJoin = false
            prefs.edit().putBoolean("pref_telegram_joined", true).apply()
        }
    }

    fun setForceTheme(theme: String?, night: Boolean?) {
        if (paused) return
        if (forceTheme == theme && forceNight == night)
            return
        forceTheme = theme
        forceNight = night
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    private fun findCrashReports(onlyUnprotected: Boolean): List<File> {
        val unprotected = DeviceProtectedUtils.getFilesDir(this)?.listFiles().orEmpty()
        if (onlyUnprotected)
            return unprotected.filter { it.name.startsWith("crash_report") }

        val dir = getExternalFilesDir(null)
        val allFiles = dir?.listFiles()?.toList().orEmpty() + unprotected
        return allFiles.filter { it.name.startsWith("crash_report") }
    }

    private fun saveCrashReports(uri: Uri) {
        val files = findCrashReports(false)
        if (files.isEmpty()) return
        runCatching {
            contentResolver.openOutputStream(uri)?.use { output ->
                BufferedOutputStream(output).use { bos ->
                    ZipOutputStream(bos).use { z ->
                        for (file in files) {
                            FileInputStream(file).use { f ->
                                z.putNextEntry(ZipEntry(file.name))
                                FileUtils.copyStreamToOtherStream(f, z)
                                z.closeEntry()
                            }
                        }
                    }
                }
                for (file in files) {
                    file.delete()
                }
            }
        }
    }

    companion object {
        // public write so compose previews can show the screens
        // having it in a companion object is not ideal as it will stay in memory even after settings are closed
        // but it's small enough to not care
        lateinit var settingsContainer: SettingsContainer

        var forceNight: Boolean? = null
        var forceTheme: String? = null
        var clickedTelegramJoin = false
    }

    override fun onSharedPreferenceChanged(prefereces: SharedPreferences?, key: String?) {
        prefChanged()
    }
}

// duplicate of SettingsActivity so we can launch it when the app icon is disabled in Android 9 and older
class SettingsActivity2 : SettingsActivity()
