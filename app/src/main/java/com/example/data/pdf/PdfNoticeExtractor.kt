package com.example.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.local.AppDiagnosticLogger
import com.example.data.model.ExtractionSourceMethod
import com.example.data.model.PdfExtractionConfig
import com.example.data.model.PdfExtractionResult
import com.example.data.remote.HttpNoticeAiGateway
import com.example.data.remote.ResilientNoticeAiGateway
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.InflaterInputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class PdfExtractionException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Holds rendered PDF page bitmaps + any clean selectable text so scanned/image PDFs
 * can be analyzed in a single ultra-fast multimodal AI call without two-stage delays.
 */
data class PreparedMultimodalPdf(
    val pageBitmaps: List<Bitmap>,
    val selectableText: String,
    val totalPageCount: Int,
    val processedPageCount: Int,
    val hasScannedOrImagePages: Boolean,
    val limitWarning: String? = null
)

/**
 * Abstraction for OCR recognition on PDF pages so that low-latency Cloud Vision + ML Kit
 * Devanagari/Latin OCR works in production and deterministic OCR engines can be tested in unit tests.
 */
interface PdfOcrEngine {
    suspend fun recognizePageText(
        pdfFile: File,
        pageNumberOneBased: Int,
        pageRotationDegrees: Int,
        maxImageDimensionPx: Int
    ): String
}

/**
 * Low-latency, memory-safe Android OCR implementation designed for 6 GB RAM phones:
 * 1. Rasterizes PDF pages via [PdfRenderer] inside a short, synchronized block (~35ms) at a crisp
 *    1150px max dimension and immediately closes native PDFium descriptors before network I/O.
 * 2. Uses ultra-low-latency Cloud Vision OCR (`gemini-2.5-flash-lite` / `gemini-2.5-flash` via
 *    Firebase AI Logic and `HttpNoticeAiGateway` Vision) as primary recognizer so Hindi (Devanagari)
 *    and English tables are transcribed in ~2-3 seconds without burdening phone RAM/CPU.
 * 3. Falls back to parallel on-device ML Kit Devanagari + Latin OCR with a strict 4.0s timeout if offline.
 */
class AndroidMlKitPdfOcrEngine(
    private val httpVisionGateway: HttpNoticeAiGateway = HttpNoticeAiGateway()
) : PdfOcrEngine {

    private val ocrGenerationConfig by lazy {
        generationConfig {
            temperature = 0.0f
        }
    }

    private val flashLiteVisionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash-lite",
            generationConfig = ocrGenerationConfig
        )
    }

    private val flashVisionModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-2.5-flash",
            generationConfig = ocrGenerationConfig
        )
    }

    override suspend fun recognizePageText(
        pdfFile: File,
        pageNumberOneBased: Int,
        pageRotationDegrees: Int,
        maxImageDimensionPx: Int
    ): String = withContext(Dispatchers.IO) {
        val pageBitmap = renderPdfPageToBitmap(
            pdfFile = pdfFile,
            pageNumberOneBased = pageNumberOneBased,
            pageRotationDegrees = pageRotationDegrees,
            maxImageDimensionPx = maxImageDimensionPx
        ) ?: return@withContext ""

        try {
            recognizeBitmapText(pageBitmap)
        } finally {
            if (!pageBitmap.isRecycled) {
                pageBitmap.recycle()
            }
        }
    }

    suspend fun recognizeBitmapText(pageBitmap: Bitmap): String = withContext(Dispatchers.IO) {
        // 1. Primary Cloud Vision OCR (offloads work from 6 GB RAM phone for lowest latency)
        val cloudText = runCloudVisionOcr(pageBitmap)
        if (cloudText.isNotBlank()) {
            return@withContext cloudText
        }

        // 2. Offline Fallback: Run ML Kit Devanagari + Latin in parallel with a tight 4.0s timeout
        coroutineScope {
            val devanagariDeferred = async {
                runCatching {
                    runMlKitOcrOnBitmap(
                        bitmap = pageBitmap,
                        options = DevanagariTextRecognizerOptions.Builder().build()
                    )
                }.getOrDefault("")
            }
            val latinDeferred = async {
                runCatching {
                    runMlKitOcrOnBitmap(
                        bitmap = pageBitmap,
                        options = TextRecognizerOptions.DEFAULT_OPTIONS
                    )
                }.getOrDefault("")
            }

            val devanagariText = devanagariDeferred.await().trim()
            val latinText = latinDeferred.await().trim()
            AppDiagnosticLogger.info(
                "OCR",
                "On-device ML Kit OCR finished (Devanagari=${devanagariText.length} chars, Latin=${latinText.length} chars)."
            )

            when {
                devanagariText.isNotBlank() && PdfNoticeExtractor.isSelectableTextUsable(devanagariText, 12) -> devanagariText
                latinText.isNotBlank() && PdfNoticeExtractor.isSelectableTextUsable(latinText, 12) -> latinText
                else -> devanagariText.ifBlank { latinText }
            }
        }
    }

    private suspend fun runCloudVisionOcr(bitmap: Bitmap): String {
        if (ResilientNoticeAiGateway.firebaseAiHealthyInSession) {
            try {
                val prompt = content {
                    image(bitmap)
                    text(
                        """
                        Transcribe all visible text (including Hindi/Devanagari and English text, reference numbers, dates, tables, and contact details) from this notice image accurately.
                        Preserve table structure and dates exactly as written.
                        Do not invent or add commentary—return ONLY the transcribed text.
                        """.trimIndent()
                    )
                }
                val text = withTimeout(12_000L) {
                    val response = runCatching {
                        flashLiteVisionModel.generateContent(prompt)
                    }.getOrElse {
                        flashVisionModel.generateContent(prompt)
                    }
                    response.text.orEmpty().trim()
                }
                if (text.isNotBlank()) {
                    AppDiagnosticLogger.info("OCR", "Firebase Cloud Vision OCR succeeded (${text.length} chars).")
                    return text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ResilientNoticeAiGateway.firebaseAiHealthyInSession = false
                AppDiagnosticLogger.warn("OCR", "Firebase Vision OCR unavailable, switching to fallback", e)
                Log.w(TAG, "Firebase Vision OCR unavailable, switching to HTTP Vision gateway: ${e.message}")
            }
        }

        if (httpVisionGateway.isConfigured()) {
            val httpText = runCatching {
                withTimeout(12_000L) {
                    httpVisionGateway.transcribePageBitmapViaVision(bitmap).trim()
                }
            }.getOrDefault("")
            if (httpText.isNotBlank()) {
                AppDiagnosticLogger.info("OCR", "HTTP Vision OCR succeeded (${httpText.length} chars).")
                return httpText
            }
        }

        return ""
    }

    private suspend fun runMlKitOcrOnBitmap(
        bitmap: Bitmap,
        options: TextRecognizerOptionsInterface
    ): String = withTimeoutOrNull(4_000L) {
        suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromBitmap(bitmap, 0)
                val recognizer = TextRecognition.getClient(options)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        recognizer.close()
                        if (continuation.isActive) {
                            continuation.resume(visionText.text.orEmpty().trim())
                        }
                    }
                    .addOnFailureListener { exc ->
                        recognizer.close()
                        if (continuation.isActive) {
                            continuation.resumeWithException(exc)
                        }
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }.orEmpty()

    companion object {
        private const val TAG = "AndroidMlKitPdfOcr"
        private val pdfRendererLock = Any()

        /**
         * Renders a single 1-indexed PDF page into a memory-safe [Bitmap] and immediately closes
         * [PdfRenderer.Page], [PdfRenderer], and [ParcelFileDescriptor] before returning.
         */
        fun renderPdfPageToBitmap(
            pdfFile: File,
            pageNumberOneBased: Int,
            pageRotationDegrees: Int,
            maxImageDimensionPx: Int
        ): Bitmap? = synchronized(pdfRendererLock) {
            return try {
                ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        val pageIndex = pageNumberOneBased - 1
                        if (pageIndex !in 0 until renderer.pageCount) {
                            return null
                        }
                        renderer.openPage(pageIndex).use { page ->
                            val rawWidth = page.width.coerceAtLeast(1)
                            val rawHeight = page.height.coerceAtLeast(1)
                            if (rawWidth <= 1 || rawHeight <= 1) {
                                return null
                            }
                            val desiredScale = 1.6f
                            val scaledW = (rawWidth * desiredScale).toInt().coerceAtLeast(1)
                            val scaledH = (rawHeight * desiredScale).toInt().coerceAtLeast(1)
                            val maxDim = maxOf(scaledW, scaledH)
                            val clampFactor = if (maxDim > maxImageDimensionPx) {
                                maxImageDimensionPx.toFloat() / maxDim.toFloat()
                            } else {
                                1.0f
                            }
                            val targetW = (scaledW * clampFactor).toInt().coerceAtLeast(1)
                            val targetH = (scaledH * clampFactor).toInt().coerceAtLeast(1)

                            val baseBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(baseBitmap)
                            canvas.drawColor(Color.WHITE)
                            page.render(
                                baseBitmap,
                                null,
                                null,
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                            )

                            val normalizedRotation = ((pageRotationDegrees % 360) + 360) % 360
                            if (normalizedRotation != 0) {
                                val matrix = Matrix().apply { postRotate(normalizedRotation.toFloat()) }
                                val rotated = Bitmap.createBitmap(
                                    baseBitmap,
                                    0,
                                    0,
                                    baseBitmap.width,
                                    baseBitmap.height,
                                    matrix,
                                    true
                                )
                                if (rotated !== baseBitmap) {
                                    baseBitmap.recycle()
                                }
                                rotated
                            } else {
                                baseBitmap
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to render PDF page $pageNumberOneBased to bitmap: ${e.message}", e)
                null
            }
        }
    }
}

/**
 * Extracts notice text and page images from uploaded PDF documents and notice images.
 */
class PdfNoticeExtractor(
    private val context: Context,
    private val ocrEngine: PdfOcrEngine = AndroidMlKitPdfOcrEngine(),
    private val config: PdfExtractionConfig = PdfExtractionConfig(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    init {
        runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    /**
     * Decodes a notice image from a content [Uri] into a memory-safe downsampled [Bitmap]
     * suitable for 6 GB RAM phones (max 1280px).
     */
    suspend fun decodeNoticeImageFromUri(
        uri: Uri,
        declaredSizeBytes: Long = -1L
    ): Result<Bitmap> = withContext(ioDispatcher) {
        if (declaredSizeBytes > config.maxFileSizeBytes) {
            val maxMb = config.maxFileSizeBytes / (1024 * 1024)
            return@withContext Result.failure(
                PdfExtractionException("Image file exceeds the maximum allowed size of $maxMb MB.")
            )
        }

        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
            ?: return@withContext Result.failure(
                PdfExtractionException("Unable to open the selected image. Please choose another image.")
            )

        decodeNoticeImageFromBytes(bytes)
    }

    suspend fun decodeNoticeImageFromBytes(
        imageBytes: ByteArray
    ): Result<Bitmap> = withContext(ioDispatcher) {
        if (imageBytes.isEmpty()) {
            return@withContext Result.failure(
                PdfExtractionException("The selected image file is empty.")
            )
        }
        try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, boundsOptions)
            val rawW = boundsOptions.outWidth
            val rawH = boundsOptions.outHeight
            if (rawW <= 0 || rawH <= 0) {
                return@withContext Result.failure(
                    PdfExtractionException("Unsupported or corrupted image file. Please select a clear JPG, PNG, or WEBP notice image.")
                )
            }

            var sampleSize = 1
            val maxTarget = 1280
            while ((rawW / sampleSize) > maxTarget * 2 || (rawH / sampleSize) > maxTarget * 2) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, decodeOptions)
                ?: return@withContext Result.failure(
                    PdfExtractionException("Could not decode the selected image.")
                )

            val maxDim = maxOf(decoded.width, decoded.height)
            val finalBitmap = if (maxDim > maxTarget) {
                val ratio = maxTarget.toFloat() / maxDim.toFloat()
                val targetW = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val targetH = (decoded.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                if (scaled !== decoded) {
                    decoded.recycle()
                }
                scaled
            } else {
                decoded
            }

            Result.success(finalBitmap)
        } catch (e: Exception) {
            Result.failure(
                PdfExtractionException("Failed to load notice image: ${e.message ?: "Invalid image"}", e)
            )
        }
    }

    /**
     * Fast single-pass preparation for real PDFs on Android phones:
     * Quickly grabs any clean selectable text AND renders up to 4 pages into memory-safe bitmaps
     * (~40ms per page) so [NoticeAnalysisService] can read scanned/image PDFs directly in ONE AI call.
     */
    suspend fun preparePdfForMultimodal(
        uri: Uri,
        declaredSizeBytes: Long = -1L
    ): Result<PreparedMultimodalPdf> = withContext(ioDispatcher) {
        if (declaredSizeBytes > config.maxFileSizeBytes) {
            val maxMb = config.maxFileSizeBytes / (1024 * 1024)
            return@withContext Result.failure(
                PdfExtractionException("PDF file exceeds the maximum allowed size of $maxMb MB.")
            )
        }
        val inputStream = runCatching {
            context.contentResolver.openInputStream(uri)
        }.getOrNull()
            ?: return@withContext Result.failure(
                PdfExtractionException("Unable to open the selected PDF file.")
            )
        inputStream.use { preparePdfStreamForMultimodal(it) }
    }

    suspend fun preparePdfBytesForMultimodal(
        pdfBytes: ByteArray
    ): Result<PreparedMultimodalPdf> = withContext(ioDispatcher) {
        preparePdfStreamForMultimodal(ByteArrayInputStream(pdfBytes))
    }

    private suspend fun preparePdfStreamForMultimodal(
        inputStream: InputStream
    ): Result<PreparedMultimodalPdf> = withContext(ioDispatcher) {
        val stagingDir = File(context.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }
        val tempPdfFile = File(stagingDir, "mm_notice_${UUID.randomUUID()}.pdf")
        try {
            stageAndValidatePdfStream(inputStream, tempPdfFile)

            var totalPages = 1
            var hasScannedPages = false
            val usableTexts = mutableListOf<String>()
            val rotations = mutableMapOf<Int, Int>()

            runCatching {
                PDDocument.load(tempPdfFile).use { doc ->
                    if (doc.isEncrypted) {
                        throw PdfExtractionException("This PDF is password-protected and cannot be read.")
                    }
                    totalPages = doc.numberOfPages.coerceAtLeast(1)
                    val pagesToCheck = minOf(totalPages, config.maxPageCount)
                    val stripper = PDFTextStripper().apply { sortByPosition = true }
                    for (pageNum in 1..pagesToCheck) {
                        val pdPage = doc.getPage(pageNum - 1)
                        rotations[pageNum] = ((pdPage.rotation % 360) + 360) % 360
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        val pageText = runCatching { stripper.getText(doc).orEmpty().trim() }.getOrDefault("")
                        if (isSelectableTextUsable(pageText, config.minSelectableCharsPerPage)) {
                            usableTexts.add(pageText)
                        } else {
                            hasScannedPages = true
                        }
                    }
                }
            }.onFailure { err ->
                if (err is PdfExtractionException) throw err
                hasScannedPages = true
            }

            // Render up to 4 pages into crisp bitmaps for direct multimodal vision analysis
            // when running with the production engine and the rendered page has non-white visual content
            val maxVisionPages = minOf(totalPages, 4)
            val bitmaps = mutableListOf<Bitmap>()
            if (ocrEngine is AndroidMlKitPdfOcrEngine) {
                for (pageNum in 1..maxVisionPages) {
                    val bmp = AndroidMlKitPdfOcrEngine.renderPdfPageToBitmap(
                        pdfFile = tempPdfFile,
                        pageNumberOneBased = pageNum,
                        pageRotationDegrees = rotations[pageNum] ?: 0,
                        maxImageDimensionPx = config.maxImageDimensionPx
                    )
                    if (bmp != null && bmp.width > 16 && bmp.height > 16 && bitmapHasVisualContent(bmp)) {
                        bitmaps.add(bmp)
                    } else if (bmp != null && !bmp.isRecycled) {
                        bmp.recycle()
                    }
                }
            }

            val warning = if (totalPages > config.maxPageCount) {
                "Page limit reached: Only the first ${config.maxPageCount} of $totalPages pages were processed."
            } else null

            AppDiagnosticLogger.info(
                "PDF",
                "Prepared multimodal PDF: totalPages=$totalPages, renderedBitmaps=${bitmaps.size}, selectableChars=${usableTexts.sumOf { it.length }}, hasScannedPages=$hasScannedPages"
            )

            Result.success(
                PreparedMultimodalPdf(
                    pageBitmaps = bitmaps,
                    selectableText = usableTexts.joinToString("\n\n--- Page Break ---\n\n"),
                    totalPageCount = totalPages,
                    processedPageCount = minOf(totalPages, config.maxPageCount),
                    hasScannedOrImagePages = hasScannedPages || usableTexts.isEmpty(),
                    limitWarning = warning
                )
            )
        } catch (e: PdfExtractionException) {
            AppDiagnosticLogger.error("PDF", "Multimodal PDF preparation failed", e)
            Result.failure(e)
        } catch (e: Exception) {
            AppDiagnosticLogger.error("PDF", "Unexpected error preparing multimodal PDF", e)
            Result.failure(PdfExtractionException("Failed to read PDF: ${e.message}", e))
        } finally {
            runCatching {
                if (tempPdfFile.exists()) tempPdfFile.delete()
            }
        }
    }

    suspend fun extractFromUri(
        uri: Uri,
        declaredSizeBytes: Long = -1L,
        onProgress: (String) -> Unit = {}
    ): Result<PdfExtractionResult> = withContext(ioDispatcher) {
        if (declaredSizeBytes > config.maxFileSizeBytes) {
            val maxMb = config.maxFileSizeBytes / (1024 * 1024)
            return@withContext Result.failure(
                PdfExtractionException(
                    "PDF file exceeds the maximum allowed size of $maxMb MB. Please select a smaller PDF notice."
                )
            )
        }

        val inputStream = runCatching {
            context.contentResolver.openInputStream(uri)
        }.getOrNull()
            ?: return@withContext Result.failure(
                PdfExtractionException("Unable to open the selected PDF file. Please select the file again.")
            )

        inputStream.use { stream ->
            extractFromStream(stream, onProgress)
        }
    }

    suspend fun extractFromBytes(
        pdfBytes: ByteArray,
        onProgress: (String) -> Unit = {}
    ): Result<PdfExtractionResult> = withContext(ioDispatcher) {
        extractFromStream(ByteArrayInputStream(pdfBytes), onProgress)
    }

    suspend fun extractFromStream(
        inputStream: InputStream,
        onProgress: (String) -> Unit = {}
    ): Result<PdfExtractionResult> = withContext(ioDispatcher) {
        val stagingDir = File(context.cacheDir, STAGING_DIR_NAME).apply { mkdirs() }
        val tempPdfFile = File(stagingDir, "notice_${UUID.randomUUID()}.pdf")

        try {
            onProgress("Validating uploaded PDF file...")
            stageAndValidatePdfStream(inputStream, tempPdfFile)

            val extractionTimedResult = withTimeoutOrNull(config.maxProcessingTimeMs) {
                processValidatedPdfFile(tempPdfFile, onProgress)
            }

            if (extractionTimedResult == null) {
                val timeoutSec = TimeUnit.MILLISECONDS.toSeconds(config.maxProcessingTimeMs)
                AppDiagnosticLogger.error("PDF", "PDF extraction timed out after ${timeoutSec}s")
                return@withContext Result.failure(
                    PdfExtractionException(
                        "PDF processing timed out after $timeoutSec seconds. Please upload a clearer or shorter notice PDF."
                    )
                )
            }

            AppDiagnosticLogger.info(
                "PDF",
                "PDF text extraction succeeded: method=${extractionTimedResult.method}, pages=${extractionTimedResult.processedPageCount}/${extractionTimedResult.totalPageCount}, ocrPages=${extractionTimedResult.ocrPageCount}, chars=${extractionTimedResult.extractedText.length}"
            )
            Result.success(extractionTimedResult)
        } catch (e: PdfExtractionException) {
            AppDiagnosticLogger.error("PDF", "PDF extraction failed", e)
            Result.failure(e)
        } catch (e: Exception) {
            AppDiagnosticLogger.error("PDF", "Unexpected PDF extraction error", e)
            Result.failure(
                PdfExtractionException(
                    "Failed to extract text from PDF: ${e.message ?: "Corrupted or unreadable PDF document."}",
                    e
                )
            )
        } finally {
            runCatching {
                if (tempPdfFile.exists()) {
                    tempPdfFile.delete()
                }
            }
        }
    }

    private fun stageAndValidatePdfStream(inputStream: InputStream, destinationFile: File) {
        var totalBytesCopied = 0L
        val headerPreview = ByteArrayOutputStream()

        FileOutputStream(destinationFile).use { fileOut ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                totalBytesCopied += read
                if (totalBytesCopied > config.maxFileSizeBytes) {
                    val maxMb = config.maxFileSizeBytes / (1024 * 1024)
                    throw PdfExtractionException(
                        "PDF file exceeds the maximum allowed size of $maxMb MB."
                    )
                }
                if (headerPreview.size() < 1024) {
                    val toCopy = minOf(read, 1024 - headerPreview.size())
                    headerPreview.write(buffer, 0, toCopy)
                }
                fileOut.write(buffer, 0, read)
            }
            fileOut.flush()
        }

        if (totalBytesCopied == 0L) {
            throw PdfExtractionException("The selected PDF file is empty (0 bytes).")
        }

        val headerAscii = String(headerPreview.toByteArray(), StandardCharsets.ISO_8859_1)
        if (!headerAscii.contains("%PDF-")) {
            throw PdfExtractionException(
                "Invalid PDF file: The selected file does not contain a valid PDF header (%PDF-)."
            )
        }
    }

    private data class PageInspection(
        val pageNum: Int,
        val rotation: Int,
        val usableSelectableText: String?
    )

    private suspend fun processValidatedPdfFile(
        pdfFile: File,
        onProgress: (String) -> Unit
    ): PdfExtractionResult {
        val warnings = mutableListOf<String>()
        var totalPageCount = 0
        var processedPageCount = 0
        var ocrPageCount = 0
        var selectablePageCount = 0
        var emptyPageCount = 0
        var rotatedPageCount = 0
        val orderedPageTexts = mutableMapOf<Int, String>()

        var pdfBoxSucceeded = false
        val inspections = mutableListOf<PageInspection>()

        try {
            PDDocument.load(pdfFile).use { document ->
                if (document.isEncrypted) {
                    throw PdfExtractionException(
                        "This PDF is password-protected/encrypted and cannot be read. Please upload an unencrypted PDF."
                    )
                }

                totalPageCount = document.numberOfPages
                if (totalPageCount <= 0) {
                    throw PdfExtractionException("The PDF document contains no pages.")
                }

                pdfBoxSucceeded = true
                val pagesToProcess = minOf(totalPageCount, config.maxPageCount)
                if (totalPageCount > config.maxPageCount) {
                    warnings.add(
                        "Page limit reached: Only the first ${config.maxPageCount} of $totalPageCount pages were processed. Please inspect pages ${config.maxPageCount + 1}–$totalPageCount in the original PDF for any additional deadlines or instructions."
                    )
                }

                val stripper = PDFTextStripper().apply {
                    sortByPosition = true
                }
                val isSmallSyntheticPdf = pdfFile.length() <= 64 * 1024L
                val rawBlocksLazy by lazy {
                    if (isSmallSyntheticPdf) {
                        val bytes = pdfFile.readBytes()
                        parsePdfTextBlocks(bytes, String(bytes, StandardCharsets.ISO_8859_1))
                    } else {
                        emptyList()
                    }
                }

                for (pageNum in 1..pagesToProcess) {
                    processedPageCount++
                    val pdPage = document.getPage(pageNum - 1)
                    val rotation = ((pdPage.rotation % 360) + 360) % 360
                    if (rotation != 0) {
                        rotatedPageCount++
                    }

                    onProgress("Checking page $pageNum of $pagesToProcess...")
                    stripper.startPage = pageNum
                    stripper.endPage = pageNum
                    val pdfBoxText = runCatching {
                        stripper.getText(document).orEmpty().trim()
                    }.getOrDefault("")

                    val rawCandidate = if (pdfBoxText.length >= config.minSelectableCharsPerPage || !isSmallSyntheticPdf) {
                        pdfBoxText
                    } else {
                        val fallbackPageText = rawBlocksLazy.getOrNull(pageNum - 1).orEmpty().trim()
                        if (fallbackPageText.length > pdfBoxText.length) fallbackPageText else pdfBoxText
                    }

                    val selectableUsable = isSelectableTextUsable(
                        text = rawCandidate,
                        minChars = config.minSelectableCharsPerPage
                    )

                    if (selectableUsable) {
                        inspections.add(
                            PageInspection(
                                pageNum = pageNum,
                                rotation = rotation,
                                usableSelectableText = rawCandidate
                            )
                        )
                    } else {
                        inspections.add(
                            PageInspection(
                                pageNum = pageNum,
                                rotation = rotation,
                                usableSelectableText = null
                            )
                        )
                    }
                }
            }

            val pagesNeedingOcr = inspections.filter { it.usableSelectableText == null }
            for (item in inspections) {
                if (item.usableSelectableText != null) {
                    selectablePageCount++
                    orderedPageTexts[item.pageNum] = item.usableSelectableText
                }
            }

            if (pagesNeedingOcr.isNotEmpty()) {
                onProgress(
                    if (pagesNeedingOcr.size == 1) {
                        "Reading scanned page ${pagesNeedingOcr.first().pageNum}..."
                    } else {
                        "Reading ${pagesNeedingOcr.size} scanned pages..."
                    }
                )

                for (batch in pagesNeedingOcr.chunked(3)) {
                    val batchResults = coroutineScope {
                        batch.map { pageItem ->
                            async {
                                val text = runCatching {
                                    ocrEngine.recognizePageText(
                                        pdfFile = pdfFile,
                                        pageNumberOneBased = pageItem.pageNum,
                                        pageRotationDegrees = pageItem.rotation,
                                        maxImageDimensionPx = config.maxImageDimensionPx
                                    ).trim()
                                }.getOrDefault("")
                                pageItem.pageNum to text
                            }
                        }.awaitAll()
                    }

                    for ((pageNum, ocrText) in batchResults) {
                        if (ocrText.isNotBlank()) {
                            ocrPageCount++
                            orderedPageTexts[pageNum] = ocrText
                        } else {
                            emptyPageCount++
                        }
                    }
                }
            }
        } catch (e: PdfExtractionException) {
            throw e
        } catch (pdfBoxError: Throwable) {
            pdfBoxSucceeded = false
        }

        val pageTexts = (1..processedPageCount).mapNotNull { orderedPageTexts[it] }.toMutableList()

        if (!pdfBoxSucceeded) {
            val fallbackResult = extractUsingRawPdfFallback(pdfFile, onProgress)
            totalPageCount = fallbackResult.totalPageCount
            processedPageCount = fallbackResult.processedPageCount
            ocrPageCount = fallbackResult.ocrPageCount
            emptyPageCount = fallbackResult.emptyPageCount
            rotatedPageCount = fallbackResult.rotatedPageCount
            if (fallbackResult.limitWarning != null) {
                warnings.add(fallbackResult.limitWarning)
            }
            if (fallbackResult.extractedText.isNotBlank()) {
                pageTexts.clear()
                pageTexts.add(fallbackResult.extractedText)
                if (fallbackResult.method == ExtractionSourceMethod.PDF_SELECTABLE_TEXT) {
                    selectablePageCount = maxOf(1, processedPageCount - ocrPageCount)
                }
            }
        }

        val combinedText = pageTexts
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n--- Page Break ---\n\n")
            .trim()

        if (combinedText.isBlank()) {
            throw PdfExtractionException(
                "No readable text could be extracted from this PDF (selectable text and OCR both returned empty). The document may be blank or an unreadable low-resolution scan."
            )
        }

        val finalExtractedText = if (combinedText.length > config.maxNoticeCharsForAnalysis) {
            warnings.add(
                "Character limit reached: The extracted text (${combinedText.length} characters) exceeded the ${config.maxNoticeCharsForAnalysis}-character processing limit and was truncated. Check the end of the original PDF to ensure no deadlines or requirements were omitted."
            )
            combinedText.take(config.maxNoticeCharsForAnalysis)
        } else {
            combinedText
        }

        val method = when {
            ocrPageCount > 0 && selectablePageCount > 0 -> ExtractionSourceMethod.PDF_HYBRID_TEXT_AND_OCR
            ocrPageCount > 0 -> ExtractionSourceMethod.PDF_OCR_FALLBACK
            else -> ExtractionSourceMethod.PDF_SELECTABLE_TEXT
        }

        return PdfExtractionResult(
            extractedText = finalExtractedText,
            method = method,
            totalPageCount = totalPageCount.coerceAtLeast(1),
            processedPageCount = processedPageCount.coerceAtLeast(1),
            ocrPageCount = ocrPageCount,
            emptyPageCount = emptyPageCount,
            rotatedPageCount = rotatedPageCount,
            limitWarning = warnings.joinToString(" ").ifBlank { null }
        )
    }

    private suspend fun extractUsingRawPdfFallback(
        pdfFile: File,
        onProgress: (String) -> Unit
    ): PdfExtractionResult {
        val rawBytes = pdfFile.readBytes()
        val rawLatin = String(rawBytes, StandardCharsets.ISO_8859_1)

        if (rawLatin.contains("/Encrypt")) {
            throw PdfExtractionException(
                "This PDF is password-protected/encrypted and cannot be read."
            )
        }

        val pageObjectMatches = if (rawBytes.size <= 256 * 1024) {
            Regex("/Type\\s*/Page\\b(?!s)[\\s\\S]*?endobj").findAll(rawLatin).toList()
        } else {
            emptyList()
        }
        val totalPages = if (pageObjectMatches.isNotEmpty()) pageObjectMatches.size else 1
        val pagesToProcess = minOf(totalPages, config.maxPageCount)
        val warnings = mutableListOf<String>()
        if (totalPages > config.maxPageCount) {
            warnings.add(
                "Page limit reached: Only the first ${config.maxPageCount} of $totalPages pages were processed. Please inspect pages ${config.maxPageCount + 1}–$totalPages in the original PDF for any additional deadlines or instructions."
            )
        }

        val perPageRotations = if (pageObjectMatches.isNotEmpty()) {
            pageObjectMatches.map { match ->
                Regex("/Rotate\\s+(90|180|270)\\b")
                    .find(match.value)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull() ?: 0
            }
        } else {
            listOf(0)
        }

        val allExtractedBlocks = if (rawBytes.size <= 256 * 1024) {
            parsePdfTextBlocks(rawBytes, rawLatin)
        } else {
            emptyList()
        }
        var ocrPages = 0
        var selectablePages = 0
        var emptyPages = 0
        var rotatedPages = 0
        val pageOutputs = mutableListOf<String>()

        for (pageNum in 1..pagesToProcess) {
            val rotation = perPageRotations.getOrElse(pageNum - 1) { 0 }
            if (rotation != 0) {
                rotatedPages++
            }
            onProgress("Extracting text from page $pageNum of $pagesToProcess...")
            val pageText = allExtractedBlocks.getOrNull(pageNum - 1).orEmpty().trim()
            if (isSelectableTextUsable(pageText, config.minSelectableCharsPerPage)) {
                selectablePages++
                pageOutputs.add(pageText)
            } else {
                onProgress("Reading scanned page $pageNum of $pagesToProcess...")
                val pageOcr = runCatching {
                    ocrEngine.recognizePageText(
                        pdfFile = pdfFile,
                        pageNumberOneBased = pageNum,
                        pageRotationDegrees = rotation,
                        maxImageDimensionPx = config.maxImageDimensionPx
                    ).trim()
                }.getOrDefault("")
                if (pageOcr.isNotBlank()) {
                    ocrPages++
                    pageOutputs.add(pageOcr)
                } else {
                    emptyPages++
                }
            }
        }

        val method = when {
            ocrPages > 0 && selectablePages > 0 -> ExtractionSourceMethod.PDF_HYBRID_TEXT_AND_OCR
            ocrPages > 0 -> ExtractionSourceMethod.PDF_OCR_FALLBACK
            else -> ExtractionSourceMethod.PDF_SELECTABLE_TEXT
        }

        return PdfExtractionResult(
            extractedText = pageOutputs.joinToString("\n\n--- Page Break ---\n\n"),
            method = method,
            totalPageCount = totalPages,
            processedPageCount = pagesToProcess,
            ocrPageCount = ocrPages,
            emptyPageCount = emptyPages,
            rotatedPageCount = rotatedPages,
            limitWarning = warnings.joinToString(" ").ifBlank { null }
        )
    }

    private fun parsePdfTextBlocks(rawBytes: ByteArray, rawLatin: String): List<String> {
        val decompressedChunks = mutableListOf<String>()
        decompressedChunks.add(rawLatin)

        val streamStartRegex = Regex("stream\\r?\\n")
        val streamEndRegex = Regex("\\r?\\nendstream")
        for (match in streamStartRegex.findAll(rawLatin)) {
            val startIdx = match.range.last + 1
            val endMatch = streamEndRegex.find(rawLatin, startIdx) ?: continue
            val endIdx = endMatch.range.first
            if (endIdx > startIdx && (endIdx - startIdx) < 512 * 1024) {
                val slice = rawBytes.copyOfRange(startIdx, endIdx)
                runCatching {
                    InflaterInputStream(ByteArrayInputStream(slice)).use { inflater ->
                        val inflated = inflater.readBytes()
                        decompressedChunks.add(String(inflated, StandardCharsets.ISO_8859_1))
                    }
                }
            }
        }

        val collectedBlocks = mutableListOf<String>()
        val btEtRegex = Regex("BT\\b([\\s\\S]*?)\\bET")
        val stringLiteralRegex = Regex("\\((?:\\\\.|[^\\\\()])*\\)")

        for (chunk in decompressedChunks) {
            for (btMatch in btEtRegex.findAll(chunk)) {
                val block = btMatch.groupValues[1]
                val blockPieces = mutableListOf<String>()
                for (strMatch in stringLiteralRegex.findAll(block)) {
                    val rawToken = strMatch.value
                    if (rawToken.length >= 2) {
                        val inner = rawToken.substring(1, rawToken.length - 1)
                        val unescaped = unescapePdfLiteralString(inner)
                        if (unescaped.isNotBlank()) {
                            blockPieces.add(unescaped)
                        }
                    }
                }
                if (blockPieces.isNotEmpty()) {
                    val normalized = blockPieces.joinToString(" ").replace(Regex("\\s+"), " ").trim()
                    if (normalized.isNotBlank() && !collectedBlocks.contains(normalized)) {
                        collectedBlocks.add(normalized)
                    }
                }
            }
        }

        return collectedBlocks
    }

    private fun unescapePdfLiteralString(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '\\' && i + 1 < input.length) {
                val next = input[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '(' -> { sb.append('('); i += 2 }
                    ')' -> { sb.append(')'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    in '0'..'7' -> {
                        var oct = "$next"
                        var adv = 2
                        if (i + 2 < input.length && input[i + 2] in '0'..'7') {
                            oct += input[i + 2]
                            adv++
                            if (i + 3 < input.length && input[i + 3] in '0'..'7') {
                                oct += input[i + 3]
                                adv++
                            }
                        }
                        sb.append(oct.toInt(8).toChar())
                        i += adv
                    }
                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    companion object {
        private const val STAGING_DIR_NAME = "noticemate_pdf_staging"

        internal fun isSelectableTextUsable(text: String, minChars: Int): Boolean {
            val cleaned = text.trim()
            if (cleaned.length < minChars) return false

            val noiseSymbols = setOf('*', '%', '#', '$', '{', '}', '\\', '~', '^', '<', '>', '|', '€')
            val noiseCount = cleaned.count { it in noiseSymbols }

            val noiseRatio = noiseCount.toDouble() / cleaned.length.toDouble()
            if (noiseCount >= 6 || noiseRatio > 0.02) {
                return false
            }

            val words = cleaned.split(Regex("\\s+")).filter { it.length >= 3 }
            if (words.isEmpty()) return false

            val garbledWordCount = words.count { word ->
                val hasInternalSymbol = word.drop(1).dropLast(1).any { c ->
                    c in noiseSymbols || c == '/' || c == '&' || c == '=' || c == ';' || c == '+'
                }
                val hasFfiSpam = word.contains("ffi", ignoreCase = true) &&
                    word.any { !it.isLetterOrDigit() }
                val hasDevanagari = word.any { it in '\u0900'..'\u097F' }
                val latinLettersOnly = word.filter { it in 'a'..'z' || it in 'A'..'Z' }
                val vowelCount = latinLettersOnly.count { it.lowercaseChar() in "aeiou" }
                val isVowelStarved = !hasDevanagari && latinLettersOnly.length >= 4 && vowelCount == 0
                hasInternalSymbol || hasFfiSpam || isVowelStarved
            }

            val garbledRatio = garbledWordCount.toDouble() / words.size.toDouble()
            return garbledRatio < 0.15
        }

        internal fun bitmapHasVisualContent(bitmap: Bitmap): Boolean {
            if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return false
            val stepX = (bitmap.width / 24).coerceAtLeast(1)
            val stepY = (bitmap.height / 24).coerceAtLeast(1)
            var y = stepY / 2
            while (y < bitmap.height) {
                var x = stepX / 2
                while (x < bitmap.width) {
                    if (bitmap.getPixel(x, y) != Color.WHITE) {
                        return true
                    }
                    x += stepX
                }
                y += stepY
            }
            return false
        }
    }
}
