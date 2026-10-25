package com.example.data.remote

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.AppDiagnosticLogger
import com.example.data.model.ExtractionSourceMethod
import com.example.data.model.NoticeDescriptionResult
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class NoticeAnalysisException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Backend gateway contract for executing text and single-pass multimodal notice analysis.
 */
interface NoticeAiGateway {
    fun isConfigured(): Boolean

    suspend fun completeNoticeAnalysisPrompt(
        systemPrompt: String,
        userPrompt: String
    ): Result<String>

    suspend fun completeMultimodalNoticeAnalysisPrompt(
        systemPrompt: String,
        userPrompt: String,
        bitmaps: List<Bitmap>
    ): Result<String> = completeNoticeAnalysisPrompt(systemPrompt, userPrompt)
}

/**
 * Low-latency composite AI gateway that:
 * 1. Uses Firebase AI Logic (`gemini-3.5-flash-lite` and `gemini-3.8-flash` with structured JSON
 *    schema via `Schema.obj`) for both text and single-pass multimodal image/PDF vision analysis.
 * 2. Falls back to `HttpNoticeAiGateway` if configured via environment / BuildConfig.
 * 3. Returns a clear, honest failure if all cloud AI calls fail, never generating fake descriptions.
 */
class ResilientNoticeAiGateway(
    private val httpFallbackGateway: HttpNoticeAiGateway = HttpNoticeAiGateway()
) : NoticeAiGateway {

    private val structuredSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "shortTitle" to Schema.string("Short, clear 4-8 word title in simple English."),
                "titleHi" to Schema.string("Short, clear 4-8 word title in simple Hindi (Devanagari)."),
                "summary" to Schema.string("Very simple 1-2 sentence explanation in plain English for a normal user."),
                "summaryHi" to Schema.string("Very simple 1-2 sentence explanation in plain Hindi (Devanagari)."),
                "purpose" to Schema.string("Clear explanation of the purpose of the notice."),
                "eligibility" to Schema.string("Who can apply and eligibility in simple English, or 'Not stated in the notice'."),
                "eligibilityHi" to Schema.string("Who can apply and eligibility in simple Hindi (Devanagari)."),
                "importantDates" to Schema.array(
                    Schema.string(),
                    "Each stated date or deadline in simple English (e.g., '15 Sep - 4 Nov 2026: Online Application Window'), or 'Not stated in the notice'."
                ),
                "importantDatesHi" to Schema.array(
                    Schema.string(),
                    "Each stated date or deadline in simple Hindi (Devanagari)."
                ),
                "requiredDocuments" to Schema.array(
                    Schema.string(),
                    "Each required document in simple English, or 'Not stated in the notice'."
                ),
                "requiredDocumentsHi" to Schema.array(
                    Schema.string(),
                    "Each required document in simple Hindi (Devanagari)."
                ),
                "contactDetails" to Schema.string("Stated contact person, office, email, phone, or official website, or 'Not stated in the notice'."),
                "nextSteps" to Schema.array(
                    Schema.string(),
                    "Simple step-by-step actions the reader should take next in plain English."
                ),
                "nextStepsHi" to Schema.array(
                    Schema.string(),
                    "Simple step-by-step actions the reader should take next in plain Hindi (Devanagari)."
                ),
                "missingOrAmbiguousInfo" to Schema.array(
                    Schema.string(),
                    "Explicit list of missing or unstated details."
                ),
                "factsVsInterpretationsNote" to Schema.string("Brief note distinguishing facts stated directly in the notice from reasonable interpretations.")
            )
        )
    }

    private val structuredGenerationConfig by lazy {
        generationConfig {
            responseMimeType = "application/json"
            responseSchema = structuredSchema
            temperature = 0.1f
        }
    }

    private val flashLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = structuredGenerationConfig
        )
    }

    private val flashModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = structuredGenerationConfig
        )
    }

    override fun isConfigured(): Boolean = true

    override suspend fun completeNoticeAnalysisPrompt(
        systemPrompt: String,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val combinedPrompt = "$systemPrompt\n\n$userPrompt"
        var lastError: Exception? = null

        // 1. Primary: Ultra-fast Firebase AI Logic (gemini-3.5-flash-lite -> gemini-3.8-flash)
        if (firebaseAiHealthyInSession || !httpFallbackGateway.isConfigured()) {
            val startMs = System.currentTimeMillis()
            AppDiagnosticLogger.info("AI", "Starting text notice analysis via Firebase AI Logic (${userPrompt.length} prompt chars)...")
            try {
                val response = withTimeout(35_000L) {
                    runCatching {
                        flashLiteModel.generateContent(combinedPrompt)
                    }.getOrElse {
                        flashModel.generateContent(combinedPrompt)
                    }
                }
                val text = response.text.orEmpty().trim()
                if (text.isNotBlank() && text.contains("{")) {
                    firebaseAiHealthyInSession = true
                    val elapsed = System.currentTimeMillis() - startMs
                    AppDiagnosticLogger.info("AI", "Firebase AI text analysis succeeded in ${elapsed}ms (${text.length} response chars).")
                    return@withContext Result.success(text)
                }
            } catch (e: TimeoutCancellationException) {
                lastError = NoticeAnalysisException("Notice analysis timed out. Please check your internet connection and try again.", e)
                AppDiagnosticLogger.error("AI", "Firebase AI text generation timed out", e)
                Log.e(TAG, "Firebase AI generation timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firebaseAiHealthyInSession = false
                lastError = e
                AppDiagnosticLogger.warn("AI", "Firebase AI text request failed, checking fallback gateway", e)
                Log.e(TAG, "Firebase AI Logic text request failed: ${e.message}", e)
            }
        }

        // 2. Fallback: Configured HTTP gateway (if user provided an API key)
        if (httpFallbackGateway.isConfigured()) {
            val httpStartMs = System.currentTimeMillis()
            AppDiagnosticLogger.info("AI", "Trying HTTP fallback gateway for text notice analysis...")
            val httpResult = httpFallbackGateway.completeNoticeAnalysisPrompt(systemPrompt, userPrompt)
            if (httpResult.isSuccess) {
                val elapsed = System.currentTimeMillis() - httpStartMs
                AppDiagnosticLogger.info("AI", "HTTP fallback text analysis succeeded in ${elapsed}ms.")
                return@withContext httpResult
            }
            val httpErr = httpResult.exceptionOrNull()
            if (httpErr is Exception) {
                lastError = httpErr
                AppDiagnosticLogger.error("AI", "HTTP fallback text analysis failed", httpErr)
            }
        }

        Result.failure(
            lastError ?: NoticeAnalysisException(
                "Unable to complete notice analysis at this time. Please check your network connection and try again."
            )
        )
    }

    override suspend fun completeMultimodalNoticeAnalysisPrompt(
        systemPrompt: String,
        userPrompt: String,
        bitmaps: List<Bitmap>
    ): Result<String> = withContext(Dispatchers.IO) {
        if (bitmaps.isEmpty()) {
            return@withContext completeNoticeAnalysisPrompt(systemPrompt, userPrompt)
        }

        val combinedPrompt = "$systemPrompt\n\n$userPrompt"
        var lastError: Exception? = null

        // 1. Single-pass multimodal vision analysis via Firebase AI Logic (gemini-3.8-flash / gemini-3.5-flash-lite)
        if (firebaseAiHealthyInSession || !httpFallbackGateway.isConfigured()) {
            val startMs = System.currentTimeMillis()
            AppDiagnosticLogger.info("AI", "Starting multimodal vision analysis (${bitmaps.size} page bitmaps)...")
            try {
                val multimodalContent = content {
                    bitmaps.take(4).forEach { bmp ->
                        if (!bmp.isRecycled) {
                            image(bmp)
                        }
                    }
                    text(combinedPrompt)
                }

                val response = withTimeout(40_000L) {
                    runCatching {
                        flashModel.generateContent(multimodalContent)
                    }.getOrElse {
                        flashLiteModel.generateContent(multimodalContent)
                    }
                }
                val text = response.text.orEmpty().trim()
                if (text.isNotBlank() && text.contains("{")) {
                    firebaseAiHealthyInSession = true
                    val elapsed = System.currentTimeMillis() - startMs
                    AppDiagnosticLogger.info("AI", "Firebase multimodal vision analysis succeeded in ${elapsed}ms.")
                    return@withContext Result.success(text)
                }
            } catch (e: TimeoutCancellationException) {
                lastError = NoticeAnalysisException("Notice image analysis timed out. Please check your internet connection and try again.", e)
                AppDiagnosticLogger.error("AI", "Firebase multimodal vision generation timed out", e)
                Log.e(TAG, "Firebase multimodal generation timed out", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firebaseAiHealthyInSession = false
                lastError = e
                AppDiagnosticLogger.warn("AI", "Firebase multimodal vision request failed, checking fallback gateway", e)
                Log.e(TAG, "Firebase multimodal request failed: ${e.message}", e)
            }
        }

        // 2. Fallback: Multimodal HTTP gateway if configured
        if (httpFallbackGateway.isConfigured()) {
            val httpStartMs = System.currentTimeMillis()
            AppDiagnosticLogger.info("AI", "Trying HTTP fallback gateway for multimodal vision analysis...")
            val httpResult = httpFallbackGateway.completeMultimodalNoticeAnalysisPrompt(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                bitmaps = bitmaps
            )
            if (httpResult.isSuccess) {
                val elapsed = System.currentTimeMillis() - httpStartMs
                AppDiagnosticLogger.info("AI", "HTTP fallback multimodal vision analysis succeeded in ${elapsed}ms.")
                return@withContext httpResult
            }
            val httpErr = httpResult.exceptionOrNull()
            if (httpErr is Exception) {
                lastError = httpErr
                AppDiagnosticLogger.error("AI", "HTTP fallback multimodal vision analysis failed", httpErr)
            }
        }

        Result.failure(
            lastError ?: NoticeAnalysisException(
                "Unable to read and analyze the notice image at this time. Please check your network connection and try again."
            )
        )
    }

    companion object {
        private const val TAG = "ResilientNoticeAi"

        @Volatile
        internal var firebaseAiHealthyInSession: Boolean = true
    }
}

/**
 * Direct HTTP gateway using environment / BuildConfig credentials.
 */
class HttpNoticeAiGateway(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val explicitApiKey: String? = null,
    private val explicitBaseUrl: String? = null
) : NoticeAiGateway {

    internal fun resolveCredentials(): Pair<String, String> {
        if (explicitApiKey != null) {
            val trimmed = explicitApiKey.trim()
            return if (isValidKey(trimmed)) {
                val base = explicitBaseUrl?.trim()?.takeIf { it.isNotBlank() } ?: DEFAULT_GATEWAY_BASE_URL
                trimmed to base
            } else {
                "" to DEFAULT_GATEWAY_BASE_URL
            }
        }

        val envDeepSeek = runCatching { System.getenv("DEEPSEEK_API_KEY") }.getOrNull().orEmpty().trim()
        if (isValidKey(envDeepSeek)) {
            val base = if (envDeepSeek.startsWith("sk-v1-")) DEFAULT_GATEWAY_BASE_URL else DEEPSEEK_DIRECT_BASE_URL
            return envDeepSeek to base
        }

        val envFastRouter = runCatching { System.getenv("FASTROUTER_API_KEY") }.getOrNull().orEmpty().trim()
        if (isValidKey(envFastRouter)) {
            return envFastRouter to DEFAULT_GATEWAY_BASE_URL
        }

        val buildDeepSeek = runCatching { BuildConfig.DEEPSEEK_API_KEY }.getOrNull().orEmpty().trim()
        if (isValidKey(buildDeepSeek)) {
            val base = if (buildDeepSeek.startsWith("sk-v1-")) DEFAULT_GATEWAY_BASE_URL else DEEPSEEK_DIRECT_BASE_URL
            return buildDeepSeek to base
        }

        val buildFastRouter = runCatching { BuildConfig.FASTROUTER_API_KEY }.getOrNull().orEmpty().trim()
        if (isValidKey(buildFastRouter)) {
            return buildFastRouter to DEFAULT_GATEWAY_BASE_URL
        }

        return "" to DEFAULT_GATEWAY_BASE_URL
    }

    private fun isValidKey(candidate: String): Boolean {
        return candidate.isNotBlank() &&
            candidate != "YOUR_FASTROUTER_API_KEY" &&
            candidate != "YOUR_DEEPSEEK_API_KEY" &&
            candidate != "MY_FASTROUTER_API_KEY" &&
            candidate != "null"
    }

    override fun isConfigured(): Boolean {
        val (key, _) = resolveCredentials()
        return key.isNotBlank()
    }

    override suspend fun completeNoticeAnalysisPrompt(
        systemPrompt: String,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val (apiKey, baseUrl) = resolveCredentials()
        if (apiKey.isBlank()) {
            return@withContext Result.failure(
                NoticeAnalysisException(
                    "AI service API key is not configured. Please configure your API key in the environment (.env / Secrets panel) and try again."
                )
            )
        }

        val candidateModels = if (baseUrl == DEEPSEEK_DIRECT_BASE_URL) {
            listOf("deepseek-chat")
        } else {
            listOf(
                "google/gemini-2.0-flash-001",
                "deepseek/deepseek-chat",
                "openai/gpt-4o-mini",
                "deepseek-ai/deepseek-v3",
                "google/gemma-4-31b-it"
            )
        }

        var lastFailure: Exception? = null
        for (modelId in candidateModels) {
            val attempt = executeSingleCompletionRequest(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt
            )
            if (attempt.isSuccess) {
                return@withContext attempt
            }
            val err = attempt.exceptionOrNull()
            lastFailure = err as? Exception ?: NoticeAnalysisException("Notice analysis request failed.")
            val isModelRoutingError = err?.message?.contains("model_not_supported") == true
            if (!isModelRoutingError) {
                break
            }
        }

        Result.failure(
            lastFailure ?: NoticeAnalysisException(
                "Unable to complete notice analysis at this time. Please try again."
            )
        )
    }

    override suspend fun completeMultimodalNoticeAnalysisPrompt(
        systemPrompt: String,
        userPrompt: String,
        bitmaps: List<Bitmap>
    ): Result<String> = withContext(Dispatchers.IO) {
        val (apiKey, baseUrl) = resolveCredentials()
        if (apiKey.isBlank() || baseUrl == DEEPSEEK_DIRECT_BASE_URL || bitmaps.isEmpty()) {
            return@withContext completeNoticeAnalysisPrompt(systemPrompt, userPrompt)
        }

        val contentArray = JSONArray().apply {
            put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", "$systemPrompt\n\n$userPrompt")
                }
            )
            bitmaps.take(3).forEach { bitmap ->
                if (!bitmap.isRecycled) {
                    val jpegBytes = ByteArrayOutputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 76, out)
                        out.toByteArray()
                    }
                    val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
                    put(
                        JSONObject().apply {
                            put("type", "image_url")
                            put(
                                "image_url",
                                JSONObject().apply {
                                    put("url", "data:image/jpeg;base64,$base64Jpeg")
                                }
                            )
                        }
                    )
                }
            }
        }

        val visionModels = listOf(
            "google/gemini-2.0-flash-001",
            "openai/gpt-4o-mini"
        )

        for (modelId in visionModels) {
            val res = runCatching {
                val messages = JSONArray().apply {
                    put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        }
                    )
                }
                val payload = JSONObject().apply {
                    put("model", modelId)
                    put("messages", messages)
                    put("temperature", 0.1)
                }
                val request = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val rawBody = response.body?.string().orEmpty()
                    val root = JSONObject(rawBody)
                    root.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("message")
                        ?.optString("content")
                        .orEmpty()
                        .trim()
                }
            }.getOrNull()

            if (!res.isNullOrBlank() && res.contains("{")) {
                return@withContext Result.success(res)
            }
        }

        completeNoticeAnalysisPrompt(systemPrompt, userPrompt)
    }

    suspend fun transcribePageBitmapViaVision(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val (apiKey, baseUrl) = resolveCredentials()
        if (apiKey.isBlank() || baseUrl == DEEPSEEK_DIRECT_BASE_URL) {
            return@withContext ""
        }

        val jpegBytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 76, out)
            out.toByteArray()
        }
        val base64Jpeg = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val dataUrl = "data:image/jpeg;base64,$base64Jpeg"

        val visionModels = listOf(
            "google/gemini-2.0-flash-001",
            "openai/gpt-4o-mini"
        )

        for (modelId in visionModels) {
            val text = executeVisionOcrRequest(
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelId = modelId,
                imageDataUrl = dataUrl
            )
            if (text.isNotBlank()) {
                return@withContext text
            }
        }
        ""
    }

    private fun executeVisionOcrRequest(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        imageDataUrl: String
    ): String {
        return try {
            val contentArray = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("type", "text")
                        put(
                            "text",
                            "Transcribe all visible text (including Hindi/Devanagari and English text, reference numbers, dates, tables, and contact details) from this notice page accurately. Return ONLY the transcribed text."
                        )
                    }
                )
                put(
                    JSONObject().apply {
                        put("type", "image_url")
                        put(
                            "image_url",
                            JSONObject().apply {
                                put("url", imageDataUrl)
                            }
                        )
                    }
                )
            }

            val messages = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", contentArray)
                    }
                )
            }

            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", 0.0)
            }

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ""
                val rawBody = response.body?.string().orEmpty()
                val root = JSONObject(rawBody)
                root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                    .trim()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Vision HTTP OCR attempt with $modelId failed: ${e.message}")
            ""
        }
    }

    private fun executeSingleCompletionRequest(
        baseUrl: String,
        apiKey: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String
    ): Result<String> {
        return try {
            val messages = JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                )
                put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
                    }
                )
            }

            val payload = JSONObject().apply {
                put("model", modelId)
                put("messages", messages)
                put("temperature", 0.1)
            }

            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    if (response.code == 401 || response.code == 403) {
                        return Result.failure(
                            NoticeAnalysisException(
                                "Authentication with the AI analysis service failed (HTTP ${response.code}). Please verify your configured API key."
                            )
                        )
                    }
                    if (response.code == 429) {
                        return Result.failure(
                            NoticeAnalysisException(
                                "The AI analysis service rate limit was reached (HTTP 429). Please wait a moment and try again."
                            )
                        )
                    }
                    if (response.code == 400 || response.code == 404 || response.code == 422) {
                        val lowerBody = rawBody.lowercase()
                        if (lowerBody.contains("model") || lowerBody.contains("not found") || lowerBody.contains("invalid") || lowerBody.contains("provider")) {
                            return Result.failure(
                                NoticeAnalysisException("model_not_supported: The notice analysis request could not be processed (HTTP ${response.code}).")
                            )
                        }
                    }
                    return Result.failure(
                        NoticeAnalysisException(
                            "The notice analysis service returned an error (HTTP ${response.code}). Please try again later."
                        )
                    )
                }

                val root = JSONObject(rawBody)
                val content = root.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                    .trim()

                if (content.isBlank()) {
                    Result.failure(
                        NoticeAnalysisException("The AI service returned an empty response. Please try again.")
                    )
                } else {
                    Result.success(content)
                }
            }
        } catch (e: SocketTimeoutException) {
            Result.failure(
                NoticeAnalysisException(
                    "The notice analysis request timed out. Please check your network connection and try again.",
                    e
                )
            )
        } catch (e: IOException) {
            Result.failure(
                NoticeAnalysisException(
                    "Network error while contacting the notice analysis service. Please check your connection and try again.",
                    e
                )
            )
        } catch (e: Exception) {
            Result.failure(
                NoticeAnalysisException(
                    "An unexpected error occurred while processing the notice response.",
                    e
                )
            )
        }
    }

    companion object {
        private const val TAG = "HttpNoticeAiGateway"
        private const val DEFAULT_GATEWAY_BASE_URL = "https://api.fastrouter.ai/api/v1"
        private const val DEEPSEEK_DIRECT_BASE_URL = "https://api.deepseek.com/v1"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}

/**
 * Generates grounded, simple, bilingual (English + Hindi) notice descriptions from text,
 * scanned/image PDFs, or direct notice images.
 */
class NoticeAnalysisService(
    private val aiGateway: NoticeAiGateway = ResilientNoticeAiGateway(),
    private val maxNoticeChars: Int = DEFAULT_MAX_NOTICE_CHARS,
    private val hardRejectChars: Int = DEFAULT_HARD_REJECT_CHARS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    /**
     * Single-pass multimodal analysis for scanned/image PDFs and direct notice photos.
     * Sends the rendered page/notice bitmaps (and any available text) directly to the multimodal model
     * in one fast request.
     */
    suspend fun generateNoticeDescriptionFromMultimodal(
        pageBitmaps: List<Bitmap>,
        supplementalText: String = "",
        extractionMethod: ExtractionSourceMethod = ExtractionSourceMethod.PDF_MULTIMODAL_VISION,
        existingLimitWarning: String? = null
    ): Result<NoticeDescriptionResult> = withContext(ioDispatcher) {
        if (pageBitmaps.isEmpty() && supplementalText.isBlank()) {
            return@withContext Result.failure(
                NoticeAnalysisException("No readable content or image found in the selected notice.")
            )
        }

        val cleanedText = supplementalText.trim().take(maxNoticeChars)
        val userPrompt = buildString {
            appendLine("Read the attached notice image(s) carefully (including any Hindi/Devanagari and English tables, dates, and instructions) and generate a simple, clear JSON explanation in both Simple English and Simple Hindi.")
            if (cleanedText.isNotBlank()) {
                appendLine()
                appendLine("SUPPLEMENTAL EXTRACTED TEXT FROM DOCUMENT:")
                appendLine("\"\"\"")
                appendLine(cleanedText)
                appendLine("\"\"\"")
            }
        }

        val gatewayResult = aiGateway.completeMultimodalNoticeAnalysisPrompt(
            systemPrompt = GROUNDED_DESCRIPTION_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            bitmaps = pageBitmaps
        )

        gatewayResult.fold(
            onSuccess = { rawContent ->
                parseGroundedResponse(
                    rawResponse = rawContent,
                    extractionMethod = extractionMethod,
                    combinedWarning = existingLimitWarning
                )
            },
            onFailure = { err ->
                val cleanMessage = err.message
                    ?.replace("model_not_supported: ", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Failed to read and analyze the notice."
                Result.failure(NoticeAnalysisException(cleanMessage, err))
            }
        )
    }

    suspend fun generateNoticeDescription(
        rawNoticeText: String,
        extractionMethod: ExtractionSourceMethod = ExtractionSourceMethod.DIRECT_TEXT,
        existingLimitWarning: String? = null
    ): Result<NoticeDescriptionResult> = withContext(ioDispatcher) {
        val cleaned = rawNoticeText.trim()
        if (cleaned.isBlank()) {
            return@withContext Result.failure(
                NoticeAnalysisException("Notice text cannot be empty. Please provide valid notice text.")
            )
        }

        if (cleaned.length > hardRejectChars) {
            return@withContext Result.failure(
                NoticeAnalysisException(
                    "Notice text is too large (${cleaned.length} characters; maximum supported is $hardRejectChars characters). Please submit a shorter notice."
                )
            )
        }

        val warnings = mutableListOf<String>()
        if (!existingLimitWarning.isNullOrBlank()) {
            warnings.add(existingLimitWarning)
        }

        val textForPrompt = if (cleaned.length > maxNoticeChars) {
            warnings.add(
                "Length limit reached: Only the first $maxNoticeChars of ${cleaned.length} characters were analyzed."
            )
            cleaned.take(maxNoticeChars)
        } else {
            cleaned
        }

        val userPrompt = """
            Analyze the following notice and generate a clear, simple JSON description in both Simple English and Simple Hindi (हिंदी).
            
            SOURCE NOTICE TEXT:
            \"\"\"
            $textForPrompt
            \"\"\"
        """.trimIndent()

        val gatewayResult = aiGateway.completeNoticeAnalysisPrompt(
            systemPrompt = GROUNDED_DESCRIPTION_SYSTEM_PROMPT,
            userPrompt = userPrompt
        )

        gatewayResult.fold(
            onSuccess = { rawContent ->
                parseGroundedResponse(
                    rawResponse = rawContent,
                    extractionMethod = extractionMethod,
                    combinedWarning = warnings.joinToString(" ").ifBlank { null }
                )
            },
            onFailure = { err ->
                val cleanMessage = err.message
                    ?.replace("model_not_supported: ", "")
                    ?.takeIf { it.isNotBlank() }
                    ?: "Failed to generate notice description."
                Result.failure(NoticeAnalysisException(cleanMessage, err))
            }
        )
    }

    internal fun parseGroundedResponse(
        rawResponse: String,
        extractionMethod: ExtractionSourceMethod,
        combinedWarning: String?
    ): Result<NoticeDescriptionResult> {
        return try {
            val jsonCandidate = extractJsonObjectString(rawResponse)
            if (jsonCandidate.isBlank() || !jsonCandidate.startsWith("{")) {
                return Result.failure(
                    NoticeAnalysisException(
                        "Received an invalid response format from the analysis service. Please try again."
                    )
                )
            }

            val obj = JSONObject(jsonCandidate)
            val summary = obj.optString("summary", "").trim()
            val purpose = obj.optString("purpose", "").trim()

            if (summary.isBlank() && purpose.isBlank()) {
                return Result.failure(
                    NoticeAnalysisException(
                        "The analysis response was incomplete (missing summary and purpose). Please try again."
                    )
                )
            }

            fun JSONArray?.toGroundedList(defaultIfEmpty: List<String>): List<String> {
                if (this == null) return defaultIfEmpty
                val items = mutableListOf<String>()
                for (i in 0 until length()) {
                    val item = optString(i, "").trim()
                    if (item.isNotBlank()) {
                        items.add(item)
                    }
                }
                return items.ifEmpty { defaultIfEmpty }
            }

            val notStated = NoticeDescriptionResult.NOT_STATED
            val shortTitle = obj.optString("shortTitle", "").trim()
            val titleHi = obj.optString("titleHi", "").trim()
            val summaryHi = obj.optString("summaryHi", "").trim()
            val eligibility = obj.optString("eligibility", notStated).trim().ifBlank { notStated }
            val eligibilityHi = obj.optString("eligibilityHi", "").trim()
            val contactDetails = obj.optString("contactDetails", notStated).trim().ifBlank { notStated }
            val importantDates = obj.optJSONArray("importantDates").toGroundedList(listOf(notStated))
            val importantDatesHi = obj.optJSONArray("importantDatesHi").toGroundedList(emptyList())
            val requiredDocuments = obj.optJSONArray("requiredDocuments").toGroundedList(listOf(notStated))
            val requiredDocumentsHi = obj.optJSONArray("requiredDocumentsHi").toGroundedList(emptyList())
            val nextSteps = obj.optJSONArray("nextSteps").toGroundedList(listOf(notStated))
            val nextStepsHi = obj.optJSONArray("nextStepsHi").toGroundedList(emptyList())
            val missingOrAmbiguous = obj.optJSONArray("missingOrAmbiguousInfo")
                .toGroundedList(emptyList())
                .toMutableList()

            if (importantDates == listOf(notStated) &&
                missingOrAmbiguous.none { it.contains("date", ignoreCase = true) || it.contains("deadline", ignoreCase = true) }
            ) {
                missingOrAmbiguous.add("Specific dates or deadlines are not stated in the notice.")
            }
            if (contactDetails.equals(notStated, ignoreCase = true) &&
                missingOrAmbiguous.none { it.contains("contact", ignoreCase = true) || it.contains("link", ignoreCase = true) }
            ) {
                missingOrAmbiguous.add("Official contact details or links are not stated in the notice.")
            }

            val factsNote = obj.optString(
                "factsVsInterpretationsNote",
                "Facts above are stated directly in the notice. Unstated or ambiguous details are explicitly marked rather than assumed."
            ).trim().ifBlank {
                "Facts above are stated directly in the notice. Unstated or ambiguous details are explicitly marked rather than assumed."
            }

            Result.success(
                NoticeDescriptionResult(
                    shortTitle = shortTitle,
                    titleHi = titleHi,
                    summary = summary.ifBlank { purpose },
                    summaryHi = summaryHi,
                    purpose = purpose.ifBlank { summary },
                    eligibility = eligibility,
                    eligibilityHi = eligibilityHi,
                    importantDates = importantDates,
                    importantDatesHi = importantDatesHi,
                    requiredDocuments = requiredDocuments,
                    requiredDocumentsHi = requiredDocumentsHi,
                    contactDetails = contactDetails,
                    nextSteps = nextSteps,
                    nextStepsHi = nextStepsHi,
                    missingOrAmbiguousInfo = missingOrAmbiguous,
                    factsVsInterpretationsNote = factsNote,
                    verificationDisclaimer = NoticeDescriptionResult.DEFAULT_VERIFICATION_DISCLAIMER,
                    extractionMethod = extractionMethod,
                    limitWarning = combinedWarning,
                    analyzedAtMillis = System.currentTimeMillis()
                )
            )
        } catch (e: Exception) {
            Result.failure(
                NoticeAnalysisException(
                    "Could not parse the notice analysis response. Please try again.",
                    e
                )
            )
        }
    }

    private fun extractJsonObjectString(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            trimmed.substring(start, end + 1)
        } else {
            ""
        }
    }

    companion object {
        const val DEFAULT_MAX_NOTICE_CHARS = 24_000
        const val DEFAULT_HARD_REJECT_CHARS = 100_000

        val GROUNDED_DESCRIPTION_SYSTEM_PROMPT = """
            You are NoticeMate AI, a helpful assistant that explains official notices in very simple, everyday words for students and regular users.
            You MUST provide the explanation in BOTH Simple English AND Simple Hindi (हिंदी) in the JSON fields.

            RULES FOR CLARITY & ACCURACY:
            1. Keep every bullet short, direct, and easy for a basic user to understand at a glance—avoid complicated jargon.
            2. Ground every detail strictly in the provided notice image or text. Never invent dates, eligibility, documents, or fees.
            3. If any field is not stated in the notice, write "Not stated in the notice" in English (and "नोटिस में उल्लेख नहीं है" in Hindi).
            4. Preserve exact dates, deadlines, amounts, and portal names accurately.

            Respond ONLY with a valid JSON object matching this exact structure:
            {
              "shortTitle": "Short 4-8 word title in simple English",
              "titleHi": "Short 4-8 word title in simple Hindi (हिंदी)",
              "summary": "1-2 simple sentences in plain English explaining what this notice is about.",
              "summaryHi": "1-2 simple sentences in plain Hindi (हिंदी) explaining what this notice is about.",
              "purpose": "Clear purpose of the notice.",
              "eligibility": "Who this notice is for / who can apply in simple English, or 'Not stated in the notice'.",
              "eligibilityHi": "Who this notice is for / who can apply in simple Hindi, or 'नोटिस में उल्लेख नहीं है'.",
              "importantDates": ["Date/Deadline — what happens on this date (in simple English)"],
              "importantDatesHi": ["तारीख/अंतिम तिथि — इस तारीख को क्या करना है (सरल हिंदी में)"],
              "requiredDocuments": ["Required document in simple English, or 'Not stated in the notice'"],
              "requiredDocumentsHi": ["आवश्यक दस्तावेज़ सरल हिंदी में, या 'नोटिस में उल्लेख नहीं है'"],
              "contactDetails": "Website, office, or contact stated in the notice, or 'Not stated in the notice'.",
              "nextSteps": ["Step 1 in simple English", "Step 2 in simple English"],
              "nextStepsHi": ["कदम 1 सरल हिंदी में", "कदम 2 सरल हिंदी में"],
              "missingOrAmbiguousInfo": ["Any missing detail"],
              "factsVsInterpretationsNote": "Stated facts are taken directly from the notice."
            }
        """.trimIndent()
    }
}
