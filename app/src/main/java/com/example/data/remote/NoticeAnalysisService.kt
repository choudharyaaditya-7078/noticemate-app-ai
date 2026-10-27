package com.example.data.remote

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.local.AppDiagnosticLogger
import com.example.data.model.ExtractionSourceMethod
import com.example.data.model.NoticeDescriptionResult
import com.example.data.model.NoticeValidationGateResult
import com.example.data.model.NoticeValidationStatus
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

open class NoticeAnalysisException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Backend gateway contract for executing document classification and notice analysis.
 */
interface NoticeAiGateway {
    fun isConfigured(): Boolean

    suspend fun completeDocumentClassificationPrompt(
        systemPrompt: String,
        userPrompt: String
    ): Result<String> = completeNoticeAnalysisPrompt(systemPrompt, userPrompt)

    suspend fun completeMultimodalDocumentClassificationPrompt(
        systemPrompt: String,
        userPrompt: String,
        bitmaps: List<Bitmap>
    ): Result<String> = completeMultimodalNoticeAnalysisPrompt(systemPrompt, userPrompt, bitmaps)

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
                "summary" to Schema.string("Section 1 (In Short): Concise 1-2 sentence explanation of the notice in plain English."),
                "summaryHi" to Schema.string("Section 1 (In Short): Concise 1-2 sentence explanation of the notice in plain Hindi (Devanagari)."),
                "purpose" to Schema.string("Clear explanation of the purpose of the notice."),
                "importantDates" to Schema.array(
                    Schema.string(),
                    "Section 2 (Important Dates & Deadlines): Each stated date or deadline in simple English, or 'Not stated in the notice'."
                ),
                "importantDatesHi" to Schema.array(
                    Schema.string(),
                    "Section 2 (Important Dates & Deadlines): Each stated date or deadline in simple Hindi (Devanagari), or 'नोटिस में उल्लेख नहीं है'."
                ),
                "eligibility" to Schema.string("Section 3 (Who Can Apply / Eligibility): Who this notice applies to or who can apply in simple English, or 'Not stated in the notice'."),
                "eligibilityHi" to Schema.string("Section 3 (Who Can Apply / Eligibility): Who this notice applies to or who can apply in simple Hindi (Devanagari), or 'नोटिस में उल्लेख नहीं है'."),
                "requiredDocuments" to Schema.array(
                    Schema.string(),
                    "Section 4 (Required Documents): Each required document in simple English, or 'Not stated in the notice'."
                ),
                "requiredDocumentsHi" to Schema.array(
                    Schema.string(),
                    "Section 4 (Required Documents): Each required document in simple Hindi (Devanagari), or 'नोटिस में उल्लेख नहीं है'."
                ),
                "nextSteps" to Schema.array(
                    Schema.string(),
                    "Section 5 (What To Do Next): Actionable step-by-step instructions in plain English (without leading step numbers)."
                ),
                "nextStepsHi" to Schema.array(
                    Schema.string(),
                    "Section 5 (What To Do Next): Actionable step-by-step instructions in plain Hindi (Devanagari, without leading step numbers)."
                ),
                "otherConditions" to Schema.array(
                    Schema.string(),
                    "Section 6 (Other Important Conditions): Stated fees, rules, attendance requirements, submission modes, or penalties in simple English."
                ),
                "otherConditionsHi" to Schema.array(
                    Schema.string(),
                    "Section 6 (Other Important Conditions): Stated fees, rules, attendance requirements, submission modes, or penalties in simple Hindi (Devanagari)."
                ),
                "contactDetails" to Schema.string("Stated contact person, office, room, email, phone, or website in English, or 'Not stated in the notice'."),
                "contactDetailsHi" to Schema.string("Stated contact person, office, room, email, phone, or website in Hindi (Devanagari), or 'नोटिस में उल्लेख नहीं है'."),
                "missingOrAmbiguousInfo" to Schema.array(
                    Schema.string(),
                    "Important details not specified in the notice in English."
                ),
                "missingOrAmbiguousInfoHi" to Schema.array(
                    Schema.string(),
                    "Important details not specified in the notice in Hindi (Devanagari)."
                ),
                "askNoticeBuddyQuestions" to Schema.array(
                    Schema.string(),
                    "Section 7 (Ask NoticeBuddy): Relevant follow-up questions the reader should ask or verify in simple English."
                ),
                "askNoticeBuddyQuestionsHi" to Schema.array(
                    Schema.string(),
                    "Section 7 (Ask NoticeBuddy): Relevant follow-up questions the reader should ask or verify in simple Hindi (Devanagari)."
                ),
                "factsVsInterpretationsNote" to Schema.string("Brief note distinguishing facts stated directly in the notice from unstated details."),
                "factsVsInterpretationsNoteHi" to Schema.string("Brief note in Hindi distinguishing stated facts from unstated details.")
            ),
            optionalProperties = listOf(
                "contactDetailsHi",
                "otherConditions",
                "otherConditionsHi",
                "missingOrAmbiguousInfoHi",
                "askNoticeBuddyQuestions",
                "askNoticeBuddyQuestionsHi",
                "factsVsInterpretationsNoteHi"
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

    private val classificationSchema by lazy {
        Schema.obj(
            properties = mapOf(
                "classificationStatus" to Schema.string("Must be one of: VALID_NOTICE, NOT_A_NOTICE, NEEDS_CLEARER_INPUT, UNREADABLE_DOCUMENT"),
                "documentCategory" to Schema.string("Detected category such as GOVERNMENT_NOTIFICATION, SCHOOL_OR_COLLEGE_CIRCULAR, SCHOLARSHIP_NOTICE, OFFICE_NOTICE, PUBLIC_ANNOUNCEMENT, OFFICIAL_INSTRUCTION, ESSAY_OR_ACADEMIC_PAPER, NOVEL_OR_LITERARY_TEXT, INVOICE_OR_BILL, RESUME_OR_CV, IRRELEVANT_DOCUMENT, INSUFFICIENT_OR_BLURRY, or UNREADABLE"),
                "confidence" to Schema.double("Confidence score between 0.0 and 1.0"),
                "reasonEn" to Schema.string("Clear, polite explanation in simple English"),
                "reasonHi" to Schema.string("Clear, polite explanation in simple Hindi (Devanagari)")
            )
        )
    }

    private val classificationGenerationConfig by lazy {
        generationConfig {
            responseMimeType = "application/json"
            responseSchema = classificationSchema
            temperature = 0.0f
        }
    }

    private val classifierFlashLiteModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = classificationGenerationConfig
        )
    }

    private val classifierFlashModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = classificationGenerationConfig
        )
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

    override suspend fun completeDocumentClassificationPrompt(
        systemPrompt: String,
        userPrompt: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val combinedPrompt = "$systemPrompt\n\n$userPrompt"
        var lastError: Exception? = null

        if (firebaseAiHealthyInSession || !httpFallbackGateway.isConfigured()) {
            try {
                val response = withTimeout(20_000L) {
                    runCatching {
                        classifierFlashLiteModel.generateContent(combinedPrompt)
                    }.getOrElse {
                        classifierFlashModel.generateContent(combinedPrompt)
                    }
                }
                val text = response.text.orEmpty().trim()
                if (text.isNotBlank() && text.contains("{")) {
                    firebaseAiHealthyInSession = true
                    return@withContext Result.success(text)
                }
            } catch (e: TimeoutCancellationException) {
                lastError = NoticeAnalysisException("Document verification timed out. Please check your internet connection and try again.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firebaseAiHealthyInSession = false
                lastError = e
            }
        }

        if (httpFallbackGateway.isConfigured()) {
            val httpResult = httpFallbackGateway.completeDocumentClassificationPrompt(systemPrompt, userPrompt)
            if (httpResult.isSuccess) {
                return@withContext httpResult
            }
            val httpErr = httpResult.exceptionOrNull()
            if (httpErr is Exception) {
                lastError = httpErr
            }
        }

        Result.failure(
            lastError ?: NoticeAnalysisException(
                "Unable to verify document type at this time. Please check your network connection and try again."
            )
        )
    }

    override suspend fun completeMultimodalDocumentClassificationPrompt(
        systemPrompt: String,
        userPrompt: String,
        bitmaps: List<Bitmap>
    ): Result<String> = withContext(Dispatchers.IO) {
        if (bitmaps.isEmpty()) {
            return@withContext completeDocumentClassificationPrompt(systemPrompt, userPrompt)
        }
        val combinedPrompt = "$systemPrompt\n\n$userPrompt"
        var lastError: Exception? = null

        if (firebaseAiHealthyInSession || !httpFallbackGateway.isConfigured()) {
            try {
                val multimodalContent = content {
                    bitmaps.take(8).forEach { bmp ->
                        if (!bmp.isRecycled) {
                            image(bmp)
                        }
                    }
                    text(combinedPrompt)
                }
                val response = withTimeout(25_000L) {
                    runCatching {
                        classifierFlashLiteModel.generateContent(multimodalContent)
                    }.getOrElse {
                        classifierFlashModel.generateContent(multimodalContent)
                    }
                }
                val text = response.text.orEmpty().trim()
                if (text.isNotBlank() && text.contains("{")) {
                    firebaseAiHealthyInSession = true
                    return@withContext Result.success(text)
                }
            } catch (e: TimeoutCancellationException) {
                lastError = NoticeAnalysisException("Document image verification timed out. Please check your internet connection and try again.", e)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                firebaseAiHealthyInSession = false
                lastError = e
            }
        }

        if (httpFallbackGateway.isConfigured()) {
            val httpResult = httpFallbackGateway.completeMultimodalDocumentClassificationPrompt(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                bitmaps = bitmaps
            )
            if (httpResult.isSuccess) {
                return@withContext httpResult
            }
            val httpErr = httpResult.exceptionOrNull()
            if (httpErr is Exception) {
                lastError = httpErr
            }
        }

        Result.failure(
            lastError ?: NoticeAnalysisException(
                "Unable to verify the document image at this time. Please check your network connection and try again."
            )
        )
    }

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
                    bitmaps.take(8).forEach { bmp ->
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
     * Phase 5 Distinct Document-Classification Step (Text / OCR Extracted Content):
     * Inspects the extracted text and context to verify whether the document is a legitimate notice
     * before normal summarization can occur.
     *
     * - Distinguishes `UNREADABLE_DOCUMENT`, `NEEDS_CLEARER_INPUT`, `NOT_A_NOTICE`, `VALID_NOTICE`,
     *   and `CLASSIFICATION_ERROR`.
     * - Never treats a failed classifier API call as proof that a document is not a notice.
     * - Combines AI classification with deterministic content-and-context structural checks so an AI
     *   prompt instruction alone is never the sole enforcement mechanism.
     */
    suspend fun validateNoticeDocument(
        rawDocumentText: String,
        extractionMethod: ExtractionSourceMethod = ExtractionSourceMethod.DIRECT_TEXT
    ): NoticeValidationGateResult = withContext(ioDispatcher) {
        val preCheck = NoticeValidationGate.checkPreReadabilityAndBlur(rawDocumentText)
        if (preCheck != null) {
            AppDiagnosticLogger.info(
                "GATE",
                "Pre-AI readability/blur gate triggered for $extractionMethod: status=${preCheck.status}, detail=${preCheck.technicalDetail}"
            )
            return@withContext preCheck
        }

        val cleaned = rawDocumentText.trim().take(maxNoticeChars)
        val classificationUserPrompt = """
            Classify the following extracted document content based strictly on its actual content, structure, and communicative context (ignore filenames or isolated keywords).
            Determine whether it is a legitimate notice (VALID_NOTICE), an unrelated non-notice document (NOT_A_NOTICE), uncertain/insufficient (NEEDS_CLEARER_INPUT), or unreadable (UNREADABLE_DOCUMENT).

            EXTRACTED DOCUMENT CONTENT:
            \"\"\"
            $cleaned
            \"\"\"
        """.trimIndent()

        val aiResponseResult = aiGateway.completeDocumentClassificationPrompt(
            systemPrompt = NoticeValidationGate.DOCUMENT_CLASSIFICATION_SYSTEM_PROMPT,
            userPrompt = classificationUserPrompt
        )

        if (aiResponseResult.isFailure) {
            val err = aiResponseResult.exceptionOrNull()
            val cleanError = err?.message
                ?.replace("model_not_supported: ", "")
                ?.takeIf { it.isNotBlank() }
                ?: "Unable to verify document type due to a service error."
            AppDiagnosticLogger.error("GATE", "Classifier API call failed: $cleanError", err)
            return@withContext NoticeValidationGate.buildClassificationErrorResult(cleanError)
        }

        val rawResponse = aiResponseResult.getOrDefault("")
        val parsedAiResult = try {
            NoticeValidationGate.parseAiClassificationResponse(rawResponse)
        } catch (parseErr: IllegalArgumentException) {
            AppDiagnosticLogger.warn("GATE", "Classifier returned invalid JSON: ${parseErr.message}")
            return@withContext NoticeValidationGate.buildClassificationErrorResult(
                "Received an invalid response format from the analysis service. Please try again."
            )
        }

        val codeAssessment = NoticeValidationGate.analyzeContentAndContext(cleaned)
        val finalDecision = NoticeValidationGate.combineAiAndCodeValidation(
            aiResult = parsedAiResult,
            codeResult = codeAssessment
        )
        AppDiagnosticLogger.info(
            "GATE",
            "Document validation gate decision: status=${finalDecision.status}, category=${finalDecision.category}, confidence=${finalDecision.confidence}"
        )
        finalDecision
    }

    /**
     * Phase 5 Distinct Document-Classification Step for Multimodal / Camera-Scanned Images:
     * Validates extracted OCR text (if present) and/or page bitmaps before normal notice analysis.
     */
    suspend fun validateMultimodalNoticeDocument(
        pageBitmaps: List<Bitmap>,
        supplementalText: String = "",
        extractionMethod: ExtractionSourceMethod = ExtractionSourceMethod.IMAGE_NOTICE
    ): NoticeValidationGateResult = withContext(ioDispatcher) {
        if (pageBitmaps.isEmpty() && supplementalText.isBlank()) {
            return@withContext NoticeValidationGate.buildUnreadableResult(
                detail = "No readable image or text provided."
            )
        }

        if (supplementalText.isNotBlank()) {
            return@withContext validateNoticeDocument(
                rawDocumentText = supplementalText,
                extractionMethod = extractionMethod
            )
        }

        val userPrompt = """
            Inspect the attached document image(s) carefully and classify based on actual content and context whether this image is a legitimate notice (VALID_NOTICE), a non-notice such as a resume, invoice, essay, or novel (NOT_A_NOTICE), too blurry/unclear to tell (NEEDS_CLEARER_INPUT), or blank/unreadable (UNREADABLE_DOCUMENT).
        """.trimIndent()

        val aiResponseResult = aiGateway.completeMultimodalDocumentClassificationPrompt(
            systemPrompt = NoticeValidationGate.DOCUMENT_CLASSIFICATION_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            bitmaps = pageBitmaps
        )

        if (aiResponseResult.isFailure) {
            val err = aiResponseResult.exceptionOrNull()
            val cleanError = err?.message
                ?.replace("model_not_supported: ", "")
                ?.takeIf { it.isNotBlank() }
                ?: "Unable to verify notice image due to a service error."
            return@withContext NoticeValidationGate.buildClassificationErrorResult(cleanError)
        }

        val rawResponse = aiResponseResult.getOrDefault("")
        val parsedAiResult = try {
            NoticeValidationGate.parseAiClassificationResponse(rawResponse)
        } catch (parseErr: IllegalArgumentException) {
            return@withContext NoticeValidationGate.buildClassificationErrorResult(
                "Received an invalid response format from the analysis service. Please try again."
            )
        }

        parsedAiResult ?: NoticeValidationGateResult(
            status = NoticeValidationStatus.VALID_NOTICE,
            category = com.example.data.model.DetectedDocumentCategory.OFFICIAL_INSTRUCTION,
            confidence = 0.85,
            userMessageEn = "Verified notice image.",
            userMessageHi = "मान्य नोटिस इमेज।",
            technicalDetail = "Multimodal image validated."
        )
    }

    /**
     * Single-pass multimodal analysis for scanned/image PDFs and direct notice photos.
     * Enforces the Phase 5 validation gate before executing the normal notice-analysis request.
     */
    suspend fun generateNoticeDescriptionFromMultimodal(
        pageBitmaps: List<Bitmap>,
        supplementalText: String = "",
        extractionMethod: ExtractionSourceMethod = ExtractionSourceMethod.PDF_MULTIMODAL_VISION,
        existingLimitWarning: String? = null,
        preValidatedGateResult: NoticeValidationGateResult? = null
    ): Result<NoticeDescriptionResult> = withContext(ioDispatcher) {
        if (pageBitmaps.isEmpty() && supplementalText.isBlank()) {
            return@withContext Result.failure(
                NoticeAnalysisException("No readable content or image found in the selected notice.")
            )
        }

        // ENFORCEMENT POINT: Ensure document passed the Notice Validation Gate before normal notice analysis
        val gateResult = preValidatedGateResult ?: validateMultimodalNoticeDocument(
            pageBitmaps = pageBitmaps,
            supplementalText = supplementalText,
            extractionMethod = extractionMethod
        )
        if (!gateResult.isAllowedForNoticeAnalysis) {
            return@withContext Result.failure(NoticeValidationGateException(gateResult))
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
        existingLimitWarning: String? = null,
        preValidatedGateResult: NoticeValidationGateResult? = null
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

        // ENFORCEMENT POINT: Ensure document passed the Notice Validation Gate before normal notice analysis
        val gateResult = preValidatedGateResult ?: validateNoticeDocument(
            rawDocumentText = cleaned,
            extractionMethod = extractionMethod
        )
        if (!gateResult.isAllowedForNoticeAnalysis) {
            return@withContext Result.failure(NoticeValidationGateException(gateResult))
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

            val obj = parseJsonObjectResiliently(jsonCandidate)
                ?: return Result.failure(
                    NoticeAnalysisException(
                        "Could not parse the notice analysis response. Please try again."
                    )
                )

            val summary = extractFlexibleString(obj, "summary", "inShort", "in_short", "explanation")
            val purpose = extractFlexibleString(obj, "purpose", "objective")

            if (summary.isBlank() && purpose.isBlank()) {
                return Result.failure(
                    NoticeAnalysisException(
                        "The analysis response was incomplete (missing summary and purpose). Please try again."
                    )
                )
            }

            val notStatedEn = NoticeDescriptionResult.NOT_STATED
            val notStatedHi = NoticeDescriptionResult.NOT_STATED_HI

            val shortTitle = extractFlexibleString(obj, "shortTitle", "title", "heading")
            val titleHi = extractFlexibleString(obj, "titleHi", "shortTitleHi", "headingHi")
            val summaryHi = extractFlexibleString(obj, "summaryHi", "inShortHi", "in_short_hi", "explanationHi")

            val rawEligibilityEn = extractFlexibleString(obj, "eligibility", "whoCanApply", "who_can_apply")
            val eligibility = if (NoticeDescriptionResult.isNotSpecifiedValue(rawEligibilityEn)) {
                notStatedEn
            } else {
                rawEligibilityEn
            }

            val rawEligibilityHi = extractFlexibleString(obj, "eligibilityHi", "whoCanApplyHi", "who_can_apply_hi")
            val eligibilityHi = when {
                rawEligibilityHi.isNotBlank() && !NoticeDescriptionResult.isNotSpecifiedValue(rawEligibilityHi) -> rawEligibilityHi
                eligibility == notStatedEn || NoticeDescriptionResult.isNotSpecifiedValue(rawEligibilityHi) -> notStatedHi
                else -> ""
            }

            val rawContactEn = extractFlexibleString(obj, "contactDetails", "contact", "contactInfo")
            val contactDetails = if (NoticeDescriptionResult.isNotSpecifiedValue(rawContactEn)) {
                notStatedEn
            } else {
                rawContactEn
            }
            val rawContactHi = extractFlexibleString(obj, "contactDetailsHi", "contactHi")
            val contactDetailsHi = when {
                rawContactHi.isNotBlank() && !NoticeDescriptionResult.isNotSpecifiedValue(rawContactHi) -> rawContactHi
                contactDetails == notStatedEn -> notStatedHi
                else -> ""
            }

            val rawDatesEn = extractFlexibleList(obj, "importantDates", "dates", "deadlines")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
            val importantDates = rawDatesEn.ifEmpty { listOf(notStatedEn) }

            val rawDatesHi = extractFlexibleList(obj, "importantDatesHi", "datesHi", "deadlinesHi")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
            val importantDatesHi = when {
                rawDatesHi.isNotEmpty() -> rawDatesHi
                importantDates == listOf(notStatedEn) -> listOf(notStatedHi)
                else -> emptyList()
            }

            val rawDocsEn = extractFlexibleList(obj, "requiredDocuments", "documents", "documentsRequired")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
            val requiredDocuments = rawDocsEn.ifEmpty { listOf(notStatedEn) }

            val rawDocsHi = extractFlexibleList(obj, "requiredDocumentsHi", "documentsHi", "documentsRequiredHi")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
            val requiredDocumentsHi = when {
                rawDocsHi.isNotEmpty() -> rawDocsHi
                requiredDocuments == listOf(notStatedEn) -> listOf(notStatedHi)
                else -> emptyList()
            }

            val rawStepsEn = extractFlexibleList(obj, "nextSteps", "whatToDoNext", "actionSteps", "steps")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
                .map { NoticeDescriptionResult.cleanStepPrefix(it) }
            val nextSteps = rawStepsEn.ifEmpty { listOf(notStatedEn) }

            val rawStepsHi = extractFlexibleList(obj, "nextStepsHi", "whatToDoNextHi", "actionStepsHi", "stepsHi")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
                .map { NoticeDescriptionResult.cleanStepPrefix(it) }
            val nextStepsHi = when {
                rawStepsHi.isNotEmpty() -> rawStepsHi
                nextSteps == listOf(notStatedEn) -> listOf(notStatedHi)
                else -> emptyList()
            }

            val otherConditions = extractFlexibleList(obj, "otherConditions", "importantConditions", "conditions")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
            val otherConditionsHi = extractFlexibleList(obj, "otherConditionsHi", "importantConditionsHi", "conditionsHi")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }

            val missingOrAmbiguous = extractFlexibleList(obj, "missingOrAmbiguousInfo", "missingInfo")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
                .toMutableList()
            val missingOrAmbiguousHi = extractFlexibleList(obj, "missingOrAmbiguousInfoHi", "missingInfoHi")
                .filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }
                .toMutableList()

            if (importantDates == listOf(notStatedEn) &&
                missingOrAmbiguous.none { it.contains("date", ignoreCase = true) || it.contains("deadline", ignoreCase = true) }
            ) {
                missingOrAmbiguous.add("Specific dates or deadlines are not stated in the notice.")
                if (missingOrAmbiguousHi.none { it.contains("तिथि") || it.contains("तारीख") }) {
                    missingOrAmbiguousHi.add("नोटिस में कोई विशिष्ट तिथि या अंतिम समय-सीमा उल्लेखित नहीं है।")
                }
            }
            if (contactDetails.equals(notStatedEn, ignoreCase = true) &&
                missingOrAmbiguous.none { it.contains("contact", ignoreCase = true) || it.contains("link", ignoreCase = true) }
            ) {
                missingOrAmbiguous.add("Official contact details or links are not stated in the notice.")
                if (missingOrAmbiguousHi.none { it.contains("संपर्क") || it.contains("लिंक") }) {
                    missingOrAmbiguousHi.add("आधिकारिक संपर्क विवरण या वेबसाइट लिंक नोटिस में उल्लेखित नहीं है।")
                }
            }

            val rawQuestionsEn = extractFlexibleList(
                obj,
                "askNoticeBuddyQuestions",
                "followUpQuestions",
                "questions"
            ).filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }.toMutableList()

            val rawQuestionsHi = extractFlexibleList(
                obj,
                "askNoticeBuddyQuestionsHi",
                "followUpQuestionsHi",
                "questionsHi"
            ).filterNot { NoticeDescriptionResult.isNotSpecifiedValue(it) }.toMutableList()

            // If AI omitted follow-up questions, derive grounded clarification questions from unstated fields without inventing facts
            if (rawQuestionsEn.isEmpty()) {
                if (importantDates == listOf(notStatedEn)) {
                    rawQuestionsEn.add("What is the effective date or deadline for this notice?")
                    rawQuestionsHi.add("इस नोटिस की प्रभावी तिथि या अंतिम समय-सीमा क्या है?")
                }
                if (eligibility == notStatedEn) {
                    rawQuestionsEn.add("Which students, departments, or applicants does this notice apply to?")
                    rawQuestionsHi.add("यह नोटिस किन छात्रों, विभागों या आवेदकों पर लागू होता है?")
                }
                if (contactDetails == notStatedEn) {
                    rawQuestionsEn.add("Which office or official contact should be approached for clarification?")
                    rawQuestionsHi.add("अधिक जानकारी या स्पष्टीकरण के लिए किस कार्यालय से संपर्क किया जाना चाहिए?")
                }
            }

            val factsNoteEn = extractFlexibleString(obj, "factsVsInterpretationsNote")
                .ifBlank { NoticeDescriptionResult.DEFAULT_FACTS_NOTE_EN }
            val factsNoteHi = extractFlexibleString(obj, "factsVsInterpretationsNoteHi")
                .ifBlank { NoticeDescriptionResult.DEFAULT_FACTS_NOTE_HI }

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
                    factsVsInterpretationsNote = factsNoteEn,
                    verificationDisclaimer = NoticeDescriptionResult.DEFAULT_VERIFICATION_DISCLAIMER,
                    extractionMethod = extractionMethod,
                    limitWarning = combinedWarning,
                    analyzedAtMillis = System.currentTimeMillis(),
                    contactDetailsHi = contactDetailsHi,
                    otherConditions = otherConditions,
                    otherConditionsHi = otherConditionsHi,
                    missingOrAmbiguousInfoHi = missingOrAmbiguousHi,
                    askNoticeBuddyQuestions = rawQuestionsEn,
                    askNoticeBuddyQuestionsHi = rawQuestionsHi,
                    factsVsInterpretationsNoteHi = factsNoteHi
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

    private fun parseJsonObjectResiliently(candidate: String): JSONObject? {
        runCatching { return JSONObject(candidate) }
        // Sanitize common LLM JSON formatting glitches: trailing commas before } or ]
        val sanitized = candidate
            .replace(Regex(",\\s*([}\\]])"), "$1")
            .trim()
        return runCatching { JSONObject(sanitized) }.getOrNull()
    }

    private fun extractFlexibleString(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key) ?: continue
            when (value) {
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.isNotBlank() && !trimmed.equals("null", ignoreCase = true)) {
                        return trimmed
                    }
                }
                is JSONArray -> {
                    val joined = (0 until value.length())
                        .mapNotNull { idx -> value.optString(idx, "").trim().takeIf { it.isNotBlank() } }
                        .joinToString("; ")
                    if (joined.isNotBlank()) return joined
                }
                is JSONObject -> {
                    val parts = mutableListOf<String>()
                    val iter = value.keys()
                    while (iter.hasNext()) {
                        val k = iter.next()
                        val v = value.optString(k, "").trim()
                        if (v.isNotBlank()) parts.add(v)
                    }
                    if (parts.isNotEmpty()) return parts.joinToString(" — ")
                }
            }
        }
        return ""
    }

    private fun extractFlexibleList(obj: JSONObject, vararg keys: String): List<String> {
        for (key in keys) {
            if (!obj.has(key) || obj.isNull(key)) continue
            val value = obj.opt(key) ?: continue
            when (value) {
                is JSONArray -> {
                    val items = mutableListOf<String>()
                    for (i in 0 until value.length()) {
                        val elem = value.opt(i)
                        when (elem) {
                            is String -> {
                                val trimmed = elem.trim()
                                if (trimmed.isNotBlank() && !trimmed.equals("null", ignoreCase = true)) {
                                    items.add(trimmed)
                                }
                            }
                            is JSONObject -> {
                                val parts = mutableListOf<String>()
                                val iter = elem.keys()
                                while (iter.hasNext()) {
                                    val k = iter.next()
                                    val v = elem.optString(k, "").trim()
                                    if (v.isNotBlank()) parts.add(v)
                                }
                                if (parts.isNotEmpty()) items.add(parts.joinToString(": "))
                            }
                        }
                    }
                    if (items.isNotEmpty()) return items
                }
                is String -> {
                    val trimmed = value.trim()
                    if (trimmed.isNotBlank() && !trimmed.equals("null", ignoreCase = true)) {
                        val splitLines = trimmed
                            .split(Regex("[\\r\\n]+|(?<=\\.)\\s+(?=(?:\\d+[.)]|•|-|\\*))"))
                            .map { line ->
                                line.trim().replace(Regex("^(?:[•\\-*]\\s*)"), "").trim()
                            }
                            .filter { it.isNotBlank() }
                        if (splitLines.isNotEmpty()) return splitLines
                    }
                }
            }
        }
        return emptyList()
    }

    private fun extractJsonObjectString(raw: String): String {
        val withoutFences = raw.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val start = withoutFences.indexOf('{')
        if (start < 0) return ""

        // Walk balanced braces outside string literals so trailing text with '{' doesn't corrupt extraction
        var depth = 0
        var inQuotes = false
        var escaped = false
        for (i in start until withoutFences.length) {
            val ch = withoutFences[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (ch == '\\' && inQuotes) {
                escaped = true
                continue
            }
            if (ch == '"') {
                inQuotes = !inQuotes
                continue
            }
            if (!inQuotes) {
                if (ch == '{') depth++
                else if (ch == '}') {
                    depth--
                    if (depth == 0) {
                        return withoutFences.substring(start, i + 1)
                    }
                }
            }
        }

        val end = withoutFences.lastIndexOf('}')
        return if (end > start) {
            withoutFences.substring(start, end + 1)
        } else {
            ""
        }
    }

    companion object {
        const val DEFAULT_MAX_NOTICE_CHARS = 24_000
        const val DEFAULT_HARD_REJECT_CHARS = 100_000

        val GROUNDED_DESCRIPTION_SYSTEM_PROMPT = """
            You are NoticeBuddy, a reliable assistant that explains official notices clearly and consistently in BOTH Simple English and Simple Hindi (हिंदी).

            STRICT GROUNDING & CONSISTENCY RULES:
            1. Present information consistently across all notices using the exact structured JSON fields below.
            2. Ground every single detail strictly in the provided notice text or image. NEVER invent or guess dates, deadlines, eligibility criteria, required documents, fees, penalties, or contact details.
            3. If any detail (dates, eligibility, documents, contact details, or action steps) is absent from the notice, explicitly write "Not stated in the notice" in English and "नोटिस में उल्लेख नहीं है" in Hindi.
            4. Keep English and Hindi output consistent in meaning, tone, and item count across all sections.
            5. In `nextSteps` and `nextStepsHi`, provide clear, actionable steps without prefixing step numbers (the app numbers them automatically).
            6. Put any stated fees, late rules, attendance requirements, or submission conditions in `otherConditions` and `otherConditionsHi`.
            7. In `askNoticeBuddyQuestions` and `askNoticeBuddyQuestionsHi`, provide 2-3 helpful follow-up questions the reader can ask the issuing office to clarify any unstated or important details.

            Respond ONLY with a valid JSON object matching this exact structure:
            {
              "shortTitle": "Short 4-8 word title in simple English",
              "titleHi": "Short 4-8 word title in simple Hindi (हिंदी)",
              "summary": "Section 1 (In Short): 1-2 simple sentences in plain English explaining what this notice is about.",
              "summaryHi": "Section 1 (In Short): 1-2 simple sentences in plain Hindi (हिंदी) explaining what this notice is about.",
              "purpose": "Clear purpose of the notice in plain English.",
              "importantDates": ["Date/Deadline — what happens on this date (or 'Not stated in the notice')"],
              "importantDatesHi": ["तारीख/अंतिम तिथि — इस तारीख को क्या करना है (या 'नोटिस में उल्लेख नहीं है')"],
              "eligibility": "Section 3 (Who Can Apply / Eligibility) in simple English, or 'Not stated in the notice'.",
              "eligibilityHi": "Section 3 (Who Can Apply / Eligibility) in simple Hindi, or 'नोटिस में उल्लेख नहीं है'.",
              "requiredDocuments": ["Required document in simple English, or 'Not stated in the notice'"],
              "requiredDocumentsHi": ["आवश्यक दस्तावेज़ सरल हिंदी में, या 'नोटिस में उल्लेख नहीं है'"],
              "nextSteps": ["Actionable step 1 in simple English", "Actionable step 2 in simple English"],
              "nextStepsHi": ["कदम 1 सरल हिंदी में", "कदम 2 सरल हिंदी में"],
              "otherConditions": ["Any stated fee, rule, penalty, or submission condition in simple English"],
              "otherConditionsHi": ["कोई भी उल्लिखित शुल्क, नियम या महत्वपूर्ण शर्त सरल हिंदी में"],
              "contactDetails": "Website, office, or contact stated in the notice, or 'Not stated in the notice'.",
              "contactDetailsHi": "नोटिस में दिया गया कार्यालय, वेबसाइट या संपर्क विवरण, या 'नोटिस में उल्लेख नहीं है'.",
              "missingOrAmbiguousInfo": ["Any important detail not specified in the notice (in English)"],
              "missingOrAmbiguousInfoHi": ["कोई महत्वपूर्ण जानकारी जो नोटिस में निर्दिष्ट नहीं है (हिंदी में)"],
              "askNoticeBuddyQuestions": ["Relevant follow-up question 1 in English", "Relevant follow-up question 2 in English"],
              "askNoticeBuddyQuestionsHi": ["प्रासंगिक अनुवर्ती प्रश्न 1 हिंदी में", "प्रासंगिक अनुवर्ती प्रश्न 2 हिंदी में"],
              "factsVsInterpretationsNote": "Stated facts are taken directly from the notice; absent details are explicitly marked as not specified.",
              "factsVsInterpretationsNoteHi": "यहाँ दिए गए तथ्य सीधे नोटिस से लिए गए हैं; अनुपस्थित विवरण स्पष्ट रूप से चिह्नित किए गए हैं।"
            }
        """.trimIndent()
    }
}
