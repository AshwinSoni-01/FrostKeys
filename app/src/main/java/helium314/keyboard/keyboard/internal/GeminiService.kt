package helium314.keyboard.keyboard.internal

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import helium314.keyboard.latin.cloud.CloudManager
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

object GeminiService : SharedPreferences.OnSharedPreferenceChangeListener {
    private const val TAG = "GeminiService"
    private lateinit var appContext: Context
    private var isInitialized = false
    private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000 // 7 days
    private const val QUOTA_COOLDOWN_FALLBACK_MS = 60_000L
    private var lastReactiveHealTime = 0L
    private val generationLock = Any()
    private var generationInFlight = false
    private var activeGenerationId = 0L
    @Volatile private var quotaCooldownUntilMs = 0L

    fun init(context: Context) {
        if (isInitialized) return
        appContext = context.applicationContext
        val prefs = appContext.prefs()
        prefs.registerOnSharedPreferenceChangeListener(this)
        val apiKey = prefs.getString(CloudManager.PREF_GEMINI_API_KEY, "") ?: ""
        if (apiKey.isNotBlank()) {
            fetchAndCacheModels(appContext, apiKey)
        }
        isInitialized = true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == CloudManager.PREF_GEMINI_API_KEY) {
            val apiKey = sharedPreferences?.getString(key, "") ?: ""
            if (apiKey.isNotBlank() && ::appContext.isInitialized) {
                fetchAndCacheModels(appContext, apiKey, forceRefresh = true)
            }
        }
    }

    fun fetchAndCacheModels(context: Context, apiKey: String, forceRefresh: Boolean = false) {
        val prefs = context.prefs()
        val lastFetch = prefs.getLong(CloudManager.PREF_GEMINI_MODELS_LAST_FETCH, 0L)
        val now = System.currentTimeMillis()

        if (!forceRefresh && (now - lastFetch < CACHE_TTL_MS) && prefs.contains(CloudManager.PREF_CACHED_GEMINI_MODELS)) {
            Log.d(TAG, "Using cached model list (TTL not expired)")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            Log.d(TAG, "Fetching available Gemini models (forceRefresh=$forceRefresh)...")
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey"
            val request = Request.Builder().url(url).build()

            try {
                CloudManager.client.newCall(request).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && body != null) {
                        val json = JSONObject(body)
                        val modelsArray = json.getJSONArray("models")
                        val flashModels = mutableListOf<String>()

                        for (i in 0 until modelsArray.length()) {
                            val modelObj = modelsArray.getJSONObject(i)
                            val name = modelObj.getString("name").removePrefix("models/")
                            val supportedMethods = modelObj.getJSONArray("supportedGenerationMethods")

                            var supportsGenerateContent = false
                            for (j in 0 until supportedMethods.length()) {
                                if (supportedMethods.getString(j) == "generateContent") {
                                    supportsGenerateContent = true
                                    break
                                }
                            }

                            if (supportsGenerateContent && isSupportedTextFlashModel(name)) {
                                flashModels.add(name)
                            }
                        }

                        // Prioritize Lite models as they are significantly faster
                        flashModels.sortWith(compareByDescending<String> { it.contains("lite", ignoreCase = true) }.thenByDescending { it })

                        val cachedString = flashModels.joinToString(",")
                        // Synchronous commit to prevent race conditions
                        prefs.edit().apply {
                            putString(CloudManager.PREF_CACHED_GEMINI_MODELS, cachedString)
                            putLong(CloudManager.PREF_GEMINI_MODELS_LAST_FETCH, now)
                        }.commit()
                        Log.d(TAG, "Cached Gemini models: $cachedString")
                    } else {
                        Log.e(TAG, "Failed to fetch models: ${response.code} ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching Gemini models: ${e.message}")
            }
        }
    }

    fun generateText(context: Context, prompt: String, text: String, callback: (String?, Exception?) -> Unit) {
        init(context)
        val mainHandler = Handler(Looper.getMainLooper())
        if (!CloudManager.isFeatureAllowed(context, CloudManager.CloudFeature.AI_WRITING_TOOLS)) {
            mainHandler.post {
                callback(null, SecurityException("AI Writing Tools are disabled by Gatekeeper"))
            }
            return
        }

        val prefs = context.prefs()
        val apiKey = prefs.getString(CloudManager.PREF_GEMINI_API_KEY, "") ?: ""
        if (apiKey.isBlank()) {
            mainHandler.post {
                callback(null, Exception("Gemini API key is missing. Please enter it in settings."))
            }
            return
        }

        getActiveQuotaCooldownMessage()?.let { message ->
            mainHandler.post {
                callback(null, Exception(message))
            }
            return
        }

        val cachedModels = prefs.getString(CloudManager.PREF_CACHED_GEMINI_MODELS, "") ?: ""
        val modelList = sanitizeModelList(cachedModels.split(","))
        if (cachedModels.isNotBlank() && modelList.joinToString(",") != cachedModels) {
            prefs.edit().putString(CloudManager.PREF_CACHED_GEMINI_MODELS, modelList.joinToString(",")).apply()
        }

        if (modelList.isEmpty()) {
            fetchAndCacheModels(context, apiKey, forceRefresh = true)
            mainHandler.post {
                callback(null, Exception("Initializing AI Models. Please try again in a few seconds."))
            }
            return
        }

        val generationId = tryStartGeneration()
        if (generationId == null) {
            Log.d(TAG, "Ignoring duplicate generation request while another is active")
            mainHandler.post {
                callback(null, Exception("AI is already generating. Please wait for the current request to finish."))
            }
            return
        }

        val guardedCallback = { result: String?, error: Exception? ->
            finishGeneration(generationId)
            callback(result, error)
        }

        try {
            executeWithFallback(context, apiKey, prompt, text, modelList, 0, guardedCallback)
        } catch (e: Exception) {
            mainHandler.post {
                guardedCallback(null, e)
            }
        }
    }

    private fun tryStartGeneration(): Long? {
        synchronized(generationLock) {
            if (generationInFlight) return null
            generationInFlight = true
            activeGenerationId += 1L
            return activeGenerationId
        }
    }

    private fun finishGeneration(generationId: Long) {
        synchronized(generationLock) {
            if (!generationInFlight || activeGenerationId != generationId) return
            generationInFlight = false
            Log.d(TAG, "Generation request finished")
        }
    }

    private fun sanitizeModelList(models: List<String>): List<String> {
        return models
            .map { it.trim().removePrefix("models/") }
            .filter { isSupportedTextFlashModel(it) }
            .distinct()
    }

    private fun isSupportedTextFlashModel(model: String): Boolean {
        val lower = model.lowercase()
        if (!lower.contains("flash")) return false
        val unsupportedMarkers = listOf(
            "tts",
            "image",
            "imagen",
            "embedding",
            "embed",
            "live",
            "audio",
            "video"
        )
        return unsupportedMarkers.none { lower.contains(it) }
    }

    private fun getActiveQuotaCooldownMessage(): String? {
        val remainingMs = quotaCooldownUntilMs - System.currentTimeMillis()
        if (remainingMs <= 0L) {
            quotaCooldownUntilMs = 0L
            return null
        }
        return quotaMessageForDelay(remainingMs)
    }

    private fun setQuotaCooldown(delayMs: Long) {
        val safeDelayMs = delayMs.coerceAtLeast(1_000L)
        quotaCooldownUntilMs = System.currentTimeMillis() + safeDelayMs
    }

    private fun quotaMessageForDelay(delayMs: Long): String {
        val seconds = ((delayMs + 999L) / 1000L).coerceAtLeast(1L)
        return "Gemini quota is temporarily exhausted. Please retry in ${seconds}s."
    }

    private fun parseRetryDelayMs(responseBody: String?): Long? {
        if (responseBody.isNullOrBlank()) return null
        try {
            val details = JSONObject(responseBody)
                .optJSONObject("error")
                ?.optJSONArray("details")
            if (details != null) {
                for (i in 0 until details.length()) {
                    val detail = details.optJSONObject(i) ?: continue
                    val retryDelay = detail.optString("retryDelay", "")
                    parseDelayStringMs(retryDelay)?.let { return it }
                }
            }
        } catch (_: Exception) {
            // Fall through to the loose message parser below.
        }

        val retryMatch = Regex("""retry in ([0-9.]+)s""", RegexOption.IGNORE_CASE)
            .find(responseBody)
        return retryMatch?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let {
            (it * 1000.0).toLong()
        }
    }

    private fun parseDelayStringMs(value: String): Long? {
        if (value.isBlank()) return null
        val match = Regex("""([0-9.]+)\s*([a-z]*)""", RegexOption.IGNORE_CASE).matchEntire(value.trim())
            ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        val unit = match.groupValues[2].lowercase()
        val multiplier = when (unit) {
            "", "s", "sec", "secs", "second", "seconds" -> 1000.0
            "m", "min", "mins", "minute", "minutes" -> 60_000.0
            "ms", "millisecond", "milliseconds" -> 1.0
            else -> 1000.0
        }
        return (amount * multiplier).toLong()
    }

    private fun isDeveloperInstructionUnsupported(responseBody: String?): Boolean {
        return responseBody?.contains("Developer instruction is not enabled", ignoreCase = true) == true
    }

    private fun isApiKeyOrAuthError(code: Int, responseBody: String?): Boolean {
        val lower = responseBody?.lowercase().orEmpty()
        if (code == 401) return true
        if (lower.contains("api key not valid") || lower.contains("invalid api key")) return true
        if (code == 403 && (lower.contains("api key") || lower.contains("permission_denied") || lower.contains("forbidden"))) {
            return true
        }
        return false
    }

    private fun executeWithFallback(
        context: Context,
        apiKey: String,
        prompt: String,
        text: String,
        models: List<String>,
        modelIndex: Int,
        callback: (String?, Exception?) -> Unit
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        if (modelIndex >= models.size) {
            // REACTIVE HEALING with 5-minute cooldown
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastReactiveHealTime < 300000) { // 5 minutes
                mainHandler.post {
                    callback(null, Exception("Google AI is currently unavailable. Please try again later."))
                }
                return
            }
            lastReactiveHealTime = currentTime

            Log.w(TAG, "All cached models failed. Triggering reactive cache invalidation.")
            fetchAndCacheModels(context, apiKey, forceRefresh = true)

            mainHandler.post {
                callback(null, Exception("AI model list refreshed. Please tap the button again."))
            }
            return
        }

        val model = models[modelIndex]
        Log.d(TAG, "Attempting generation with model: $model (index $modelIndex)")

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val payload = try {
            JSONObject().apply {
                put("system_instruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "You are a helpful writing assistant. Give me 3 distinct variations of the requested transformation, separated by '---VAR---'. Return only the variations and delimiters. Do not add any preamble or explanation.")
                    }))
                })
                put("contents", JSONArray().put(JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply {
                        put("text", "$prompt\n\n$text")
                    }))
                }))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 1024)
                })
            }
        } catch (e: Exception) {
            mainHandler.post { callback(null, e) }
            return
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        CloudManager.client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Model $model failed: ${e.message}")
                executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    val responseBody = resp.body?.string()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "Model $model returned error ${resp.code}: $responseBody")

                        if (resp.code == 429) {
                            val retryDelayMs = parseRetryDelayMs(responseBody) ?: QUOTA_COOLDOWN_FALLBACK_MS
                            setQuotaCooldown(retryDelayMs)
                            mainHandler.post {
                                callback(null, Exception(getActiveQuotaCooldownMessage() ?: quotaMessageForDelay(retryDelayMs)))
                            }
                            return
                        }

                        if (isDeveloperInstructionUnsupported(responseBody)) {
                            executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
                            return
                        }

                        if (isApiKeyOrAuthError(resp.code, responseBody)) {
                            mainHandler.post {
                                callback(null, Exception("Invalid API Key. Please check your settings."))
                            }
                            return
                        }

                        executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
                        return
                    }

                    try {
                        val jsonResponse = JSONObject(responseBody ?: "")
                        val candidates = jsonResponse.getJSONArray("candidates")
                        if (candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            val content = candidate.getJSONObject("content")
                            val parts = content.getJSONArray("parts")
                            if (parts.length() > 0) {
                                val generatedText = parts.getJSONObject(0).getString("text")
                                mainHandler.post {
                                    callback(generatedText, null)
                                }
                                return
                            }
                        }
                        Log.w(TAG, "Model $model returned empty candidates")
                        executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse response from $model: ${e.message}")
                        executeWithFallback(context, apiKey, prompt, text, models, modelIndex + 1, callback)
                    }
                }
            }
        })
    }
}
