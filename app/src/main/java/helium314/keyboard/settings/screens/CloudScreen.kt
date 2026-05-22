// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference
import okhttp3.Request
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun CloudScreen(onClickBack: () -> Unit) {
    val context = LocalContext.current
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.cloud_features),
        settings = emptyList(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.cloud_intro_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(R.string.gemini_get_key_btn))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://klipy.com/api-overview#overview"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text(text = stringResource(R.string.klipy_get_key_btn))
                    }
                }
            }

            val settingsList = listOf(
                CloudManager.PREF_ENABLE_CLOUD_FEATURES,
                CloudManager.PREF_GEMINI_API_KEY,
                CloudManager.PREF_KLIPY_API_KEY,
                Settings.PREF_SEND_GIFS_AS_STICKERS,
                CloudManager.PREF_TEST_CONNECTION
            )
            settingsList.forEach { key ->
                SettingsActivity.settingsContainer[key]?.Preference()
            }
        }
    }
}

fun createCloudSettings(context: Context) = listOf(
    Setting(
        context,
        CloudManager.PREF_ENABLE_CLOUD_FEATURES,
        R.string.cloud_features,
        R.string.cloud_features_summary,
    ) {
        SwitchPreference(it, false)
    },
    Setting(
        context,
        CloudManager.PREF_GEMINI_API_KEY,
        R.string.gemini_api_key,
        R.string.gemini_api_key_summary,
    ) {
        TextInputPreference(it, "", isPassword = true)
    },
    Setting(
        context,
        CloudManager.PREF_KLIPY_API_KEY,
        R.string.klipy_api_key,
        R.string.klipy_api_key_summary,
    ) {
        TextInputPreference(it, "", isPassword = true)
    },
    Setting(
        context,
        Settings.PREF_SEND_GIFS_AS_STICKERS,
        R.string.send_gifs_as_stickers,
        R.string.send_gifs_as_stickers_summary,
    ) {
        SwitchPreference(it, Defaults.PREF_SEND_GIFS_AS_STICKERS)
    },
    Setting(
        context,
        CloudManager.PREF_TEST_CONNECTION,
        R.string.test_connection,
        R.string.test_connection_summary,
    ) { setting ->
        Preference(
            name = setting.title,
            description = setting.description,
            onClick = {
                Toast.makeText(context, "Testing...", Toast.LENGTH_SHORT).show()

                Thread {
                    try {
                        val request = Request.Builder()
                            .url("https://httpbin.org/get")
                            .build()

                        val response = CloudManager.executeRequest(
                            context,
                            CloudManager.CloudFeature.TEST_CONNECTION,
                            request
                        )

                        val message = if (response != null && response.isSuccessful) {
                            "SUCCESS: Connected to the internet!"
                        } else {
                            "ERROR: Request failed."
                        }

                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: SecurityException) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "BLOCKED: Gatekeeper intercepted request.", Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, "FAILED: No network available.", Toast.LENGTH_LONG).show()
                        }
                    }
                }.start()
            }
        )
    }
)
