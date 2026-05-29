// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.preferences

import android.app.Activity
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.utils.DeviceProtectedUtils
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.dialogs.ConfirmationDialog
import helium314.keyboard.settings.dialogs.InfoDialog
import java.io.File

private fun isEmojiFontFile(file: File): Boolean {
    return runCatching {
        java.io.FileInputStream(file).use { fis ->
            val header = ByteArray(12)
            if (fis.read(header) != 12) return false
            val numTables = ((header[4].toInt() and 0xFF) shl 8) or (header[5].toInt() and 0xFF)
            if (numTables <= 0 || numTables > 120) return false
            
            val recordBuffer = ByteArray(16)
            for (i in 0 until numTables) {
                if (fis.read(recordBuffer) != 16) return false
                val tag = String(recordBuffer, 0, 4, Charsets.US_ASCII)
                if (tag == "CBDT" || tag == "CBLC" || tag == "sbix" || tag == "COLR" || tag == "CPAL" || tag == "SVG ") {
                    return true
                }
            }
            false
        }
    }.getOrDefault(false)
}

@Composable
fun CustomFontPreference(setting: Setting, fontFile: File, title: Int) {
    val ctx = LocalContext.current
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showErrorDialog by rememberSaveable { mutableStateOf(false) }
    var showWarningDialog by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = it.data?.data ?: return@rememberLauncherForActivityResult
        val tempFile = File(DeviceProtectedUtils.getFilesDir(ctx), "temp_file")
        FileUtils.copyContentUriToNewFile(uri, ctx, tempFile)
        try {
            Typeface.createFromFile(tempFile)
            val isCustomFont = fontFile.name == "custom_font"
            val isCustomEmojiFont = fontFile.name == "custom_emoji_font"
            val isEmoji = isEmojiFontFile(tempFile)
            if ((isCustomFont && isEmoji) || (isCustomEmojiFont && !isEmoji)) {
                showWarningDialog = true
            } else {
                fontFile.delete()
                tempFile.renameTo(fontFile)
                KeyboardTypeface.clearCache()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            }
        } catch (_: Exception) {
            showErrorDialog = true
            tempFile.delete()
        }
    }
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("*/*")
    Preference(
        name = setting.title,
        onClick = {
            if (fontFile.exists())
                showDialog = true
            else launcher.launch(intent)
        },
    )
    if (showDialog)
        ConfirmationDialog(
            onDismissRequest = { showDialog = false },
            onConfirmed = { launcher.launch(intent) },
            onNeutral = {
                showDialog = false
                fontFile.delete()
                KeyboardTypeface.clearCache()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            neutralButtonText = stringResource(R.string.delete),
            confirmButtonText = stringResource(R.string.load),
            title = { Text(stringResource(title)) }
        )
    if (showErrorDialog)
        InfoDialog(stringResource(R.string.file_read_error)) { showErrorDialog = false }
    if (showWarningDialog) {
        val tempFile = File(DeviceProtectedUtils.getFilesDir(ctx), "temp_file")
        val warningStringRes = if (fontFile.name == "custom_emoji_font") {
            R.string.custom_emoji_font_safeguard_warning
        } else {
            R.string.custom_font_safeguard_warning
        }
        ConfirmationDialog(
            onDismissRequest = {
                showWarningDialog = false
                tempFile.delete()
            },
            onConfirmed = {
                showWarningDialog = false
                fontFile.delete()
                tempFile.renameTo(fontFile)
                KeyboardTypeface.clearCache()
                KeyboardSwitcher.getInstance().setThemeNeedsReload()
            },
            title = { Text(stringResource(title)) },
            content = { Text(stringResource(warningStringRes)) }
        )
    }
}
