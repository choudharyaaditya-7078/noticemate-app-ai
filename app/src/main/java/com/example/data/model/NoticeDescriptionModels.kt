package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

enum class NoticeLanguage(val label: String, val badgeLabel: String) {
    ENGLISH("English", "EN"),
    HINDI("हिंदी", "HI")
}

enum class ExtractionSourceMethod(val displayLabel: String) {
    DIRECT_TEXT("Direct Text Input"),
    PDF_SELECTABLE_TEXT("PDF Selectable Text"),
    PDF_OCR_FALLBACK("PDF OCR Fallback"),
    PDF_HYBRID_TEXT_AND_OCR("PDF Selectable Text + OCR"),
    PDF_MULTIMODAL_VISION("PDF Vision Read"),
    IMAGE_NOTICE("Image Notice Read")
}

data class PdfExtractionConfig(
    val maxFileSizeBytes: Long = 15L * 1024L * 1024L, // 15 MB
    val maxPageCount: Int = 15,
    val maxProcessingTimeMs: Long = 60_000L, // 60 seconds safety ceiling
    val maxImageDimensionPx: Int = 1200, // Crisp resolution for 6 GB RAM phones
    val minSelectableCharsPerPage: Int = 20,
    val maxNoticeCharsForAnalysis: Int = 24_000
)

data class PdfExtractionResult(
    val extractedText: String,
    val method: ExtractionSourceMethod,
    val totalPageCount: Int,
    val processedPageCount: Int,
    val ocrPageCount: Int,
    val emptyPageCount: Int,
    val rotatedPageCount: Int,
    val limitWarning: String? = null
)

/**
 * Typed validation states for NoticeBuddy's notice-only validation gate (Phase 5).
 * Ensures only authentic notices proceed to summarization while clearly distinguishing
 * confirmed non-notices, uncertain/blurry inputs, unreadable documents, and API errors.
 */
enum class NoticeValidationStatus {
    VALID_NOTICE,
    NOT_A_NOTICE,
    NEEDS_CLEARER_INPUT,
    UNREADABLE_DOCUMENT,
    CLASSIFICATION_ERROR
}

enum class DetectedDocumentCategory(
    val displayLabelEn: String,
    val displayLabelHi: String,
    val isNoticeCategory: Boolean
) {
    GOVERNMENT_NOTIFICATION("Government Notification", "सरकारी अधिसूचना", true),
    SCHOOL_OR_COLLEGE_CIRCULAR("School / College Circular", "स्कूल / कॉलेज परिपत्र", true),
    SCHOLARSHIP_NOTICE("Scholarship Notice", "छात्रवृत्ति सूचना", true),
    OFFICE_NOTICE("Office Notice / Memo", "कार्यालय सूचना / ज्ञापन", true),
    PUBLIC_ANNOUNCEMENT("Public Announcement", "सार्वजनिक घोषणा", true),
    OFFICIAL_INSTRUCTION("Official Instruction / Schedule", "आधिकारिक निर्देश / समय-सारणी", true),
    ESSAY_OR_ACADEMIC_PAPER("Essay / Academic Article", "निबंध / शैक्षणिक लेख", false),
    NOVEL_OR_LITERARY_TEXT("Novel / Story / Literary Work", "उपन्यास / कहानी / साहित्यिक पाठ", false),
    INVOICE_OR_BILL("Invoice / Receipt / Bill", "चालान / रसीद / बिल", false),
    RESUME_OR_CV("Resume / Curriculum Vitae", "रेज़्यूमे / बायोडाटा (CV)", false),
    IRRELEVANT_DOCUMENT("Unrelated Non-Notice Document", "गैर-नोटिस दस्तावेज़", false),
    INSUFFICIENT_OR_BLURRY("Unclear or Insufficient Content", "अस्पष्ट या अपर्याप्त सामग्री", false),
    UNREADABLE("Unreadable or Blank Document", "अपठनीय या खाली दस्तावेज़", false),
    CLASSIFIER_UNAVAILABLE("Classification Service Error", "वर्गीकरण सेवा त्रुटि", false);

    fun displayLabel(language: NoticeLanguage): String {
        return when (language) {
            NoticeLanguage.HINDI -> displayLabelHi
            NoticeLanguage.ENGLISH -> displayLabelEn
        }
    }
}

/**
 * Result of the pre-analysis Notice Validation Gate.
 * Normal notice summarization MUST NOT proceed unless [isAllowedForNoticeAnalysis] is true.
 */
data class NoticeValidationGateResult(
    val status: NoticeValidationStatus,
    val category: DetectedDocumentCategory,
    val confidence: Double,
    val userMessageEn: String,
    val userMessageHi: String,
    val technicalDetail: String = ""
) {
    val isAllowedForNoticeAnalysis: Boolean
        get() = status == NoticeValidationStatus.VALID_NOTICE

    fun displayStatusBadge(language: NoticeLanguage): String {
        return when (status) {
            NoticeValidationStatus.VALID_NOTICE ->
                if (language == NoticeLanguage.HINDI) "मान्य नोटिस" else "Valid Notice"
            NoticeValidationStatus.NOT_A_NOTICE ->
                if (language == NoticeLanguage.HINDI) "नोटिस नहीं है" else "Not a Notice"
            NoticeValidationStatus.NEEDS_CLEARER_INPUT ->
                if (language == NoticeLanguage.HINDI) "अधिक स्पष्ट दस्तावेज़ आवश्यक" else "Needs Clearer Input"
            NoticeValidationStatus.UNREADABLE_DOCUMENT ->
                if (language == NoticeLanguage.HINDI) "अपठनीय दस्तावेज़" else "Unreadable Document"
            NoticeValidationStatus.CLASSIFICATION_ERROR ->
                if (language == NoticeLanguage.HINDI) "सत्यापन सेवा त्रुटि" else "Verification Error"
        }
    }

    fun displayHeading(language: NoticeLanguage): String {
        return when (status) {
            NoticeValidationStatus.VALID_NOTICE ->
                if (language == NoticeLanguage.HINDI) "मान्य नोटिस सामग्री" else "Verified Notice Content"
            NoticeValidationStatus.NOT_A_NOTICE ->
                if (language == NoticeLanguage.HINDI) "NoticeBuddy केवल नोटिस का विश्लेषण करता है" else "NoticeBuddy Only Analyses Notices"
            NoticeValidationStatus.NEEDS_CLEARER_INPUT ->
                if (language == NoticeLanguage.HINDI) "कृपया अधिक स्पष्ट या उपयुक्त दस्तावेज़ प्रदान करें" else "Please Provide a Clearer or Suitable Notice"
            NoticeValidationStatus.UNREADABLE_DOCUMENT ->
                if (language == NoticeLanguage.HINDI) "दस्तावेज़ पढ़ा नहीं जा सका" else "Document Could Not Be Read"
            NoticeValidationStatus.CLASSIFICATION_ERROR ->
                if (language == NoticeLanguage.HINDI) "दस्तावेज़ सत्यापन पूरा नहीं हो सका" else "Could Not Verify Document Type"
        }
    }

    fun displayMessage(language: NoticeLanguage): String {
        return when (language) {
            NoticeLanguage.HINDI -> userMessageHi.ifBlank { userMessageEn }
            NoticeLanguage.ENGLISH -> userMessageEn
        }
    }

    fun displayCategoryLabel(language: NoticeLanguage): String {
        return category.displayLabel(language)
    }
}

/**
 * Phase 6 Typed Request/Pipeline Error Categories:
 * Explicitly distinguishes:
 * - [NO_INTERNET]: Device is offline or connected to a network without working internet access, or connection dropped.
 * - [UPLOAD_FAILURE]: Selected PDF/image file could not be opened, copied, decoded, or read locally.
 * - [REQUEST_TIMEOUT]: Classification or AI analysis request timed out waiting for the server.
 * - [AI_SERVICE_UNAVAILABLE]: Internet is connected, but the AI backend is unavailable, rate-limited, or returned an error.
 */
enum class NoticeRequestErrorCategory(
    val badgeEn: String,
    val badgeHi: String,
    val titleEn: String,
    val titleHi: String,
    val isSafeToRetry: Boolean = true
) {
    NO_INTERNET(
        badgeEn = "No Internet",
        badgeHi = "इंटरनेट नहीं है",
        titleEn = "No Internet Connection",
        titleHi = "इंटरनेट कनेक्शन उपलब्ध नहीं है",
        isSafeToRetry = true
    ),
    UPLOAD_FAILURE(
        badgeEn = "Upload / File Error",
        badgeHi = "फ़ाइल अपलोड त्रुटि",
        titleEn = "Document Upload or Read Failed",
        titleHi = "दस्तावेज़ पढ़ने या अपलोड करने में विफल",
        isSafeToRetry = true
    ),
    REQUEST_TIMEOUT(
        badgeEn = "Request Timeout",
        badgeHi = "समय समाप्त (Timeout)",
        titleEn = "Analysis Request Timed Out",
        titleHi = "विश्लेषण अनुरोध का समय समाप्त हो गया",
        isSafeToRetry = true
    ),
    AI_SERVICE_UNAVAILABLE(
        badgeEn = "AI Service Unavailable",
        badgeHi = "AI सेवा अनुपलब्ध",
        titleEn = "AI Analysis Service Unavailable",
        titleHi = "AI विश्लेषण सेवा वर्तमान में अनुपलब्ध है",
        isSafeToRetry = true
    );

    fun displayBadge(language: NoticeLanguage): String {
        return if (language == NoticeLanguage.HINDI) badgeHi else badgeEn
    }

    fun displayTitle(language: NoticeLanguage): String {
        return if (language == NoticeLanguage.HINDI) titleHi else titleEn
    }
}

enum class FailedPipelineStage(val labelEn: String, val labelHi: String) {
    DOCUMENT_UPLOAD_OR_READ("Document Upload & Reading", "दस्तावेज़ अपलोड और पठन"),
    NOTICE_CLASSIFICATION("Notice Verification", "नोटिस सत्यापन"),
    NOTICE_ANALYSIS("AI Notice Analysis", "AI नोटिस विश्लेषण")
}

/**
 * Structured request error state that preserves user progress and clearly communicates
 * what failed (no internet vs. upload failure vs. timeout vs. AI service unavailable)
 * and whether the operation can be safely retried.
 */
data class NoticeRequestError(
    val category: NoticeRequestErrorCategory,
    val stage: FailedPipelineStage,
    val messageEn: String,
    val messageHi: String,
    val guidanceEn: String,
    val guidanceHi: String,
    val canRetrySafely: Boolean = category.isSafeToRetry
) {
    fun displayBadge(language: NoticeLanguage): String = category.displayBadge(language)

    fun displayTitle(language: NoticeLanguage): String = category.displayTitle(language)

    fun displayMessage(language: NoticeLanguage): String {
        return if (language == NoticeLanguage.HINDI) messageHi.ifBlank { messageEn } else messageEn
    }

    fun displayGuidance(language: NoticeLanguage): String {
        return if (language == NoticeLanguage.HINDI) guidanceHi.ifBlank { guidanceEn } else guidanceEn
    }
}

/**
 * Phase 7 & 8 Camera-Scanning Workflow State Models:
 * Represents permission states, camera preview/capture/review lifecycle stages,
 * flash availability, device rotation, document-edge detection, image-quality guidance,
 * temporal stability tracking, auto-capture state, and captured photo review state.
 */
enum class CameraPermissionState {
    UNKNOWN,
    GRANTED,
    DENIED
}

enum class CameraSessionStage {
    INITIALIZING,
    PREVIEW_READY,
    CAPTURING,
    REVIEWING_CAPTURE,
    CAMERA_ERROR
}

/**
 * Normalized 2D coordinate in `[0f, 1f]` preview space.
 */
data class NormalizedPoint(
    val x: Float,
    val y: Float
)

/**
 * Detected 4-corner quadrilateral of a physical notice in normalized `[0f, 1f]` coordinates.
 */
data class DetectedDocumentQuad(
    val topLeft: NormalizedPoint,
    val topRight: NormalizedPoint,
    val bottomRight: NormalizedPoint,
    val bottomLeft: NormalizedPoint,
    val confidence: Float = 0.9f,
    val areaRatio: Float = 0.45f
)

/**
 * Phase 8 Live Image-Quality & Framing Guidance States:
 * Covers empty/undetected scenes, poor lighting, glare, cut-off edges, tilt/angle,
 * blur, hand movement stabilization, and ready/locked auto-capture state.
 */
enum class CameraQualityGuidance(
    val badgeEn: String,
    val badgeHi: String,
    val guidanceEn: String,
    val guidanceHi: String,
    val isWarning: Boolean,
    val isAcceptableQuality: Boolean
) {
    NO_DOCUMENT_DETECTED(
        badgeEn = "Searching for Notice",
        badgeHi = "नोटिस खोजा जा रहा है",
        guidanceEn = "Place the physical notice inside the frame so all 4 edges are visible",
        guidanceHi = "भौतिक नोटिस को फ्रेम के भीतर रखें ताकि चारों किनारे स्पष्ट दिखें",
        isWarning = false,
        isAcceptableQuality = false
    ),
    POOR_LIGHTING(
        badgeEn = "Low Lighting",
        badgeHi = "कम रोशनी",
        guidanceEn = "Lighting is too dim — move to brighter light or turn on Flash",
        guidanceHi = "रोशनी कम है — बेहतर रोशनी में जाएं या फ्लैश चालू करें",
        isWarning = true,
        isAcceptableQuality = false
    ),
    GLARE_DETECTED(
        badgeEn = "Glare Detected",
        badgeHi = "चमक (Glare) मिली",
        guidanceEn = "Bright glare on notice — tilt phone slightly to reduce reflection",
        guidanceHi = "नोटिस पर चमक है — प्रतिबिंब कम करने के लिए फोन थोड़ा झुकाएं",
        isWarning = true,
        isAcceptableQuality = false
    ),
    CUT_OFF_EDGES(
        badgeEn = "Edges Cut Off",
        badgeHi = "किनारे कट रहे हैं",
        guidanceEn = "Document edges are cut off — move phone slightly further back",
        guidanceHi = "नोटिस के किनारे कट रहे हैं — फोन को थोड़ा पीछे ले जाएं",
        isWarning = true,
        isAcceptableQuality = false
    ),
    TILTED_OR_ANGLED(
        badgeEn = "Angle / Tilt Warning",
        badgeHi = "तिरछा कोण (Tilt)",
        guidanceEn = "Document is angled — hold phone straight and parallel to the notice",
        guidanceHi = "नोटिस तिरछा है — फोन को नोटिस के सीधा और समानांतर रखें",
        isWarning = true,
        isAcceptableQuality = false
    ),
    BLURRY(
        badgeEn = "Blur Detected",
        badgeHi = "धुंधली छवि (Blur)",
        guidanceEn = "Notice text looks blurry — hold steady or tap to let camera focus",
        guidanceHi = "अक्षर धुंधले दिख रहे हैं — फोकस के लिए फोन स्थिर रखें",
        isWarning = true,
        isAcceptableQuality = false
    ),
    HOLD_STEADY_MOVEMENT(
        badgeEn = "Hold Steady",
        badgeHi = "स्थिर रखें",
        guidanceEn = "Notice detected — hold phone steady to lock & auto-capture",
        guidanceHi = "नोटिस मिल गया — ऑटो-कैप्चर के लिए फोन को स्थिर रखें",
        isWarning = false,
        isAcceptableQuality = true
    ),
    READY_LOCKED(
        badgeEn = "Notice Locked",
        badgeHi = "नोटिस लॉक हो गया",
        guidanceEn = "Notice edges sharp & stable — ready to capture!",
        guidanceHi = "नोटिस स्पष्ट और स्थिर है — कैप्चर के लिए तैयार!",
        isWarning = false,
        isAcceptableQuality = true
    );

    fun displayBadge(language: NoticeLanguage): String {
        return if (language == NoticeLanguage.HINDI) badgeHi else badgeEn
    }

    fun displayGuidance(language: NoticeLanguage): String {
        return if (language == NoticeLanguage.HINDI) guidanceHi else guidanceEn
    }
}

/**
 * Single-frame computer vision evaluation produced by `NoticeDocumentEdgeAnalyzer`.
 */
data class DocumentFrameAnalysisResult(
    val frameSequenceId: Long,
    val isDocumentDetected: Boolean,
    val detectedQuad: DetectedDocumentQuad? = null,
    val primaryGuidance: CameraQualityGuidance,
    val isQualityAcceptable: Boolean,
    val meanBrightness: Float = 0f,
    val sharpnessScore: Float = 0f,
    val glareRatio: Float = 0f,
    val tiltDegrees: Float = 0f,
    val hasCutOffEdges: Boolean = false,
    val analyzedAtMillis: Long = System.currentTimeMillis()
)

/**
 * Temporal document-detection and stability state rendered by the NoticeBuddy camera UI.
 */
data class DocumentDetectionUiState(
    val frameSequenceId: Long = 0L,
    val isDocumentDetected: Boolean = false,
    val detectedQuad: DetectedDocumentQuad? = null,
    val qualityGuidance: CameraQualityGuidance = CameraQualityGuidance.NO_DOCUMENT_DETECTED,
    val isQualityAcceptable: Boolean = false,
    val consecutiveStableFrames: Int = 0,
    val requiredStableFrames: Int = DEFAULT_REQUIRED_STABLE_FRAMES,
    val stabilityProgress: Float = 0f,
    val isStableLocked: Boolean = false,
    val meanBrightness: Float = 0f,
    val sharpnessScore: Float = 0f,
    val glareRatio: Float = 0f,
    val tiltDegrees: Float = 0f
) {
    companion object {
        const val DEFAULT_REQUIRED_STABLE_FRAMES = 4
    }
}

data class CapturedNoticePhoto(
    val fileName: String,
    val pngBytes: ByteArray,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int = 0,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val wasAutoCaptured: Boolean = false,
    val captureQualityGuidance: CameraQualityGuidance = CameraQualityGuidance.READY_LOCKED
) {
    val fileSizeBytes: Long
        get() = pngBytes.size.toLong()
}

/**
 * Normalized crop rectangle in `[0f, 1f]` page coordinates for Phase 9 non-destructive cropping.
 */
data class CropRectNormalized(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val isCropped: Boolean
        get() = left > 0.005f || top > 0.005f || right < 0.995f || bottom < 0.995f

    val widthRatio: Float
        get() = (right - left).coerceIn(0.15f, 1f)

    val heightRatio: Float
        get() = (bottom - top).coerceIn(0.15f, 1f)

    companion object {
        val FULL_PAGE = CropRectNormalized(0f, 0f, 1f, 1f)
        val TIGHT_MARGIN_CROP = CropRectNormalized(0.08f, 0.08f, 0.92f, 0.92f)
    }
}

/**
 * Non-destructive image correction settings applied to a single [ScannedNoticePage].
 */
data class NoticePageEditState(
    val extraRotationDegrees: Int = 0,
    val cropRect: CropRectNormalized = CropRectNormalized.FULL_PAGE,
    val isPerspectiveCorrected: Boolean = false,
    val perspectiveQuad: DetectedDocumentQuad? = null
) {
    val normalizedRotationDegrees: Int
        get() = ((extraRotationDegrees % 360) + 360) % 360

    val hasAnyEdits: Boolean
        get() = normalizedRotationDegrees != 0 || cropRect.isCropped || isPerspectiveCorrected

    val hasEdits: Boolean
        get() = hasAnyEdits

    val isCropped: Boolean
        get() = cropRect.isCropped
}

/**
 * Per-page OCR readability verification status during Phase 9 review.
 */
enum class PageOcrPreviewStatus(
    val badgeEn: String,
    val badgeHi: String,
    val isWarning: Boolean
) {
    NOT_RUN("OCR Ready", "OCR तैयार", false),
    RUNNING("Reading Text...", "टेक्स्ट पढ़ा जा रहा है...", false),
    READABLE("Text Readable", "टेक्स्ट पठनीय है", false),
    INCOMPLETE_OR_LOW_TEXT("Incomplete / Sparse Text", "अधूरा / कम टेक्स्ट", true),
    UNREADABLE("Unreadable Page", "अपठनीय पृष्ठ", true),
    OCR_FAILED("OCR Check Failed", "OCR जाँच विफल", true);

    fun displayBadge(language: NoticeLanguage): String {
        return if (language == NoticeLanguage.HINDI) badgeHi else badgeEn
    }
}

/**
 * Represents a single captured and optionally corrected page inside a multi-page notice scan session.
 * Preserves [originalPngBytes] so crop, rotation, and perspective adjustments remain non-destructive.
 */
data class ScannedNoticePage(
    val pageId: String,
    val fileName: String,
    val originalPngBytes: ByteArray,
    val originalWidthPx: Int,
    val originalHeightPx: Int,
    val currentPngBytes: ByteArray = originalPngBytes,
    val widthPx: Int = originalWidthPx,
    val heightPx: Int = originalHeightPx,
    val baseRotationDegrees: Int = 0,
    val capturedAtMillis: Long = System.currentTimeMillis(),
    val wasAutoCaptured: Boolean = false,
    val initialDetectedQuad: DetectedDocumentQuad? = null,
    val captureQualityGuidance: CameraQualityGuidance = CameraQualityGuidance.READY_LOCKED,
    val editState: NoticePageEditState = NoticePageEditState(
        perspectiveQuad = initialDetectedQuad
    ),
    val ocrStatus: PageOcrPreviewStatus = PageOcrPreviewStatus.NOT_RUN,
    val ocrPreviewText: String = "",
    val ocrStatusMessage: String? = null
) {
    val fileSizeBytes: Long
        get() = currentPngBytes.size.toLong()

    val ocrPreviewStatus: PageOcrPreviewStatus
        get() = ocrStatus

    val ocrCharacterCount: Int
        get() = ocrPreviewText.length

    val ocrWarningMessage: String?
        get() = ocrStatusMessage

    val hasImageQualityWarning: Boolean
        get() = captureQualityGuidance.isWarning

    val hasOcrWarning: Boolean
        get() = ocrStatus.isWarning

    val hasAnyWarning: Boolean
        get() = hasImageQualityWarning || hasOcrWarning

    val hasQualityOrOcrWarning: Boolean
        get() = hasAnyWarning

    fun warningSummary(language: NoticeLanguage): String? {
        val isHindi = language == NoticeLanguage.HINDI
        return when {
            ocrStatus == PageOcrPreviewStatus.UNREADABLE ->
                ocrStatusMessage ?: if (isHindi) {
                    "इस पृष्ठ से कोई पठनीय टेक्स्ट नहीं मिला — कृपया दोबारा फोटो खींचें (Retake)"
                } else {
                    "No readable text found on this page — please Retake or check focus"
                }
            ocrStatus == PageOcrPreviewStatus.INCOMPLETE_OR_LOW_TEXT ->
                ocrStatusMessage ?: if (isHindi) {
                    "इस पृष्ठ पर बहुत कम या अधूरा टेक्स्ट मिला — जाँचें कि किनारे कटे नहीं हैं"
                } else {
                    "Very little or incomplete text detected — check that page edges are not cut off"
                }
            ocrStatus == PageOcrPreviewStatus.OCR_FAILED ->
                ocrStatusMessage ?: if (isHindi) {
                    "इस पृष्ठ पर OCR परीक्षण पूरा नहीं हो सका"
                } else {
                    "OCR check could not complete on this page"
                }
            captureQualityGuidance.isWarning ->
                if (isHindi) {
                    "${captureQualityGuidance.badgeHi}: ${captureQualityGuidance.guidanceHi}"
                } else {
                    "${captureQualityGuidance.badgeEn}: ${captureQualityGuidance.guidanceEn}"
                }
            else -> null
        }
    }

    fun toCapturedNoticePhoto(): CapturedNoticePhoto = CapturedNoticePhoto(
        fileName = fileName,
        pngBytes = currentPngBytes,
        widthPx = widthPx,
        heightPx = heightPx,
        rotationDegrees = (baseRotationDegrees + editState.normalizedRotationDegrees) % 360,
        capturedAtMillis = capturedAtMillis,
        wasAutoCaptured = wasAutoCaptured,
        captureQualityGuidance = captureQualityGuidance
    )
}

data class CameraScanUiState(
    val permissionState: CameraPermissionState = CameraPermissionState.UNKNOWN,
    val permissionDeniedCount: Int = 0,
    val sessionStage: CameraSessionStage = CameraSessionStage.INITIALIZING,
    val isCameraBound: Boolean = false,
    val lensFacing: Int = LENS_FACING_BACK,
    val isFlashSupported: Boolean = true,
    val isFlashEnabled: Boolean = false,
    val isAutoCaptureEnabled: Boolean = true,
    val deviceRotationDegrees: Int = 0,
    val detectionState: DocumentDetectionUiState = DocumentDetectionUiState(),
    val lastAutoCapturedFrameId: Long = -1L,
    val autoCaptureTriggeredCount: Int = 0,
    val lastCaptureWasAuto: Boolean = false,
    val capturedPhoto: CapturedNoticePhoto? = null,
    val scannedPages: List<ScannedNoticePage> = emptyList(),
    val selectedPageIndex: Int = 0,
    val retakingPageId: String? = null,
    val isOcrCheckRunning: Boolean = false,
    val cameraErrorMessage: String? = null,
    val bindGeneration: Int = 0
) {
    val pageCount: Int
        get() = scannedPages.size

    val safeSelectedPageIndex: Int
        get() = if (scannedPages.isEmpty()) 0 else selectedPageIndex.coerceIn(0, scannedPages.lastIndex)

    val activeScannedPage: ScannedNoticePage?
        get() = scannedPages.getOrNull(safeSelectedPageIndex)

    val pagesWithWarningsCount: Int
        get() = scannedPages.count { it.hasAnyWarning }

    companion object {
        const val LENS_FACING_FRONT = 0
        const val LENS_FACING_BACK = 1
    }
}

/**
 * Grounded notice description generated by the AI backend in both Simple English and Simple Hindi.
 */
data class NoticeDescriptionResult(
    val shortTitle: String = "",
    val titleHi: String = "",
    val summary: String,
    val summaryHi: String = "",
    val purpose: String,
    val eligibility: String,
    val eligibilityHi: String = "",
    val importantDates: List<String>,
    val importantDatesHi: List<String> = emptyList(),
    val requiredDocuments: List<String>,
    val requiredDocumentsHi: List<String> = emptyList(),
    val contactDetails: String,
    val nextSteps: List<String>,
    val nextStepsHi: List<String> = emptyList(),
    val missingOrAmbiguousInfo: List<String>,
    val factsVsInterpretationsNote: String = DEFAULT_FACTS_NOTE_EN,
    val verificationDisclaimer: String = DEFAULT_VERIFICATION_DISCLAIMER,
    val extractionMethod: ExtractionSourceMethod = ExtractionSourceMethod.DIRECT_TEXT,
    val limitWarning: String? = null,
    val analyzedAtMillis: Long = System.currentTimeMillis(),
    val contactDetailsHi: String = "",
    val otherConditions: List<String> = emptyList(),
    val otherConditionsHi: List<String> = emptyList(),
    val missingOrAmbiguousInfoHi: List<String> = emptyList(),
    val askNoticeBuddyQuestions: List<String> = emptyList(),
    val askNoticeBuddyQuestionsHi: List<String> = emptyList(),
    val factsVsInterpretationsNoteHi: String = DEFAULT_FACTS_NOTE_HI
) {
    fun displayTitle(language: NoticeLanguage, fallbackTitle: String): String {
        return when (language) {
            NoticeLanguage.HINDI -> titleHi.ifBlank { shortTitle.ifBlank { fallbackTitle } }
            NoticeLanguage.ENGLISH -> shortTitle.ifBlank { fallbackTitle }
        }
    }

    fun displaySummary(language: NoticeLanguage): String {
        return when (language) {
            NoticeLanguage.HINDI -> summaryHi.ifBlank { summary }
            NoticeLanguage.ENGLISH -> summary
        }
    }

    fun displayImportantDates(language: NoticeLanguage): List<String> {
        val enUnspecified = isNotSpecifiedList(importantDates)
        return when (language) {
            NoticeLanguage.HINDI -> {
                val validHi = importantDatesHi.filterNot { isNotSpecifiedValue(it) }
                when {
                    validHi.isNotEmpty() -> validHi
                    enUnspecified || isNotSpecifiedList(importantDatesHi) -> listOf(NOT_STATED_HI)
                    else -> importantDates
                }
            }
            NoticeLanguage.ENGLISH -> {
                val validEn = importantDates.filterNot { isNotSpecifiedValue(it) }
                if (validEn.isNotEmpty()) validEn else listOf(NOT_STATED)
            }
        }
    }

    fun displayEligibility(language: NoticeLanguage): String {
        val enUnspecified = isNotSpecifiedValue(eligibility)
        return when (language) {
            NoticeLanguage.HINDI -> {
                when {
                    eligibilityHi.isNotBlank() && !isNotSpecifiedValue(eligibilityHi) -> eligibilityHi
                    enUnspecified || isNotSpecifiedValue(eligibilityHi) -> NOT_STATED_HI
                    else -> eligibility
                }
            }
            NoticeLanguage.ENGLISH -> {
                if (enUnspecified) NOT_STATED else eligibility
            }
        }
    }

    fun displayRequiredDocuments(language: NoticeLanguage): List<String> {
        val enUnspecified = isNotSpecifiedList(requiredDocuments)
        return when (language) {
            NoticeLanguage.HINDI -> {
                val validHi = requiredDocumentsHi.filterNot { isNotSpecifiedValue(it) }
                when {
                    validHi.isNotEmpty() -> validHi
                    enUnspecified || isNotSpecifiedList(requiredDocumentsHi) -> listOf(NOT_STATED_HI)
                    else -> requiredDocuments
                }
            }
            NoticeLanguage.ENGLISH -> {
                val validEn = requiredDocuments.filterNot { isNotSpecifiedValue(it) }
                if (validEn.isNotEmpty()) validEn else listOf(NOT_STATED)
            }
        }
    }

    fun displayNextSteps(language: NoticeLanguage): List<String> {
        val enUnspecified = isNotSpecifiedList(nextSteps)
        return when (language) {
            NoticeLanguage.HINDI -> {
                val validHi = nextStepsHi.filterNot { isNotSpecifiedValue(it) }.map { cleanStepPrefix(it) }
                when {
                    validHi.isNotEmpty() -> validHi
                    enUnspecified || isNotSpecifiedList(nextStepsHi) -> listOf(NOT_STATED_HI)
                    else -> nextSteps.map { cleanStepPrefix(it) }
                }
            }
            NoticeLanguage.ENGLISH -> {
                val validEn = nextSteps.filterNot { isNotSpecifiedValue(it) }.map { cleanStepPrefix(it) }
                if (validEn.isNotEmpty()) validEn else listOf(NOT_STATED)
            }
        }
    }

    fun displayOtherConditions(language: NoticeLanguage): List<String> {
        val cleanEn = otherConditions.map { it.trim() }.filter { it.isNotBlank() && !isNotSpecifiedValue(it) }
        val cleanHi = otherConditionsHi.map { it.trim() }.filter { it.isNotBlank() && !isNotSpecifiedValue(it) }
        return when (language) {
            NoticeLanguage.HINDI -> cleanHi.ifEmpty { cleanEn }
            NoticeLanguage.ENGLISH -> cleanEn.ifEmpty { cleanHi }
        }
    }

    fun displayMissingOrAmbiguousInfo(language: NoticeLanguage): List<String> {
        val cleanEn = missingOrAmbiguousInfo.map { it.trim() }.filter { it.isNotBlank() && !isNotSpecifiedValue(it) }
        val cleanHi = missingOrAmbiguousInfoHi.map { it.trim() }.filter { it.isNotBlank() && !isNotSpecifiedValue(it) }
        return when (language) {
            NoticeLanguage.HINDI -> cleanHi.ifEmpty { cleanEn }
            NoticeLanguage.ENGLISH -> cleanEn.ifEmpty { cleanHi }
        }
    }

    fun displayContactDetails(language: NoticeLanguage): String {
        val enUnspecified = isNotSpecifiedValue(contactDetails)
        return when (language) {
            NoticeLanguage.HINDI -> {
                when {
                    contactDetailsHi.isNotBlank() && !isNotSpecifiedValue(contactDetailsHi) -> contactDetailsHi
                    enUnspecified -> NOT_STATED_HI
                    else -> contactDetails
                }
            }
            NoticeLanguage.ENGLISH -> if (enUnspecified) NOT_STATED else contactDetails
        }
    }

    fun displayAskNoticeBuddyQuestions(language: NoticeLanguage): List<String> {
        val cleanEn = askNoticeBuddyQuestions.map { it.trim() }.filter { it.isNotBlank() && !isNotSpecifiedValue(it) }
        val cleanHi = askNoticeBuddyQuestionsHi.map { it.trim() }.filter { it.isNotBlank() && !isNotSpecifiedValue(it) }
        return when (language) {
            NoticeLanguage.HINDI -> cleanHi.ifEmpty { cleanEn }
            NoticeLanguage.ENGLISH -> cleanEn.ifEmpty { cleanHi }
        }
    }

    fun displayFactsNote(language: NoticeLanguage): String {
        return when (language) {
            NoticeLanguage.HINDI -> factsVsInterpretationsNoteHi.ifBlank { DEFAULT_FACTS_NOTE_HI }
            NoticeLanguage.ENGLISH -> factsVsInterpretationsNote.ifBlank { DEFAULT_FACTS_NOTE_EN }
        }
    }

    fun hasStatedContactDetails(): Boolean = !isNotSpecifiedValue(contactDetails)

    fun hasOtherImportantConditions(): Boolean {
        return displayOtherConditions(NoticeLanguage.ENGLISH).isNotEmpty() ||
            displayMissingOrAmbiguousInfo(NoticeLanguage.ENGLISH).isNotEmpty() ||
            hasStatedContactDetails()
    }

    fun toJsonObject(): JSONObject {
        return JSONObject().apply {
            put("shortTitle", shortTitle)
            put("titleHi", titleHi)
            put("summary", summary)
            put("summaryHi", summaryHi)
            put("purpose", purpose)
            put("eligibility", eligibility)
            put("eligibilityHi", eligibilityHi)
            put("importantDates", JSONArray(importantDates))
            put("importantDatesHi", JSONArray(importantDatesHi))
            put("requiredDocuments", JSONArray(requiredDocuments))
            put("requiredDocumentsHi", JSONArray(requiredDocumentsHi))
            put("contactDetails", contactDetails)
            put("contactDetailsHi", contactDetailsHi)
            put("nextSteps", JSONArray(nextSteps))
            put("nextStepsHi", JSONArray(nextStepsHi))
            put("otherConditions", JSONArray(otherConditions))
            put("otherConditionsHi", JSONArray(otherConditionsHi))
            put("missingOrAmbiguousInfo", JSONArray(missingOrAmbiguousInfo))
            put("missingOrAmbiguousInfoHi", JSONArray(missingOrAmbiguousInfoHi))
            put("askNoticeBuddyQuestions", JSONArray(askNoticeBuddyQuestions))
            put("askNoticeBuddyQuestionsHi", JSONArray(askNoticeBuddyQuestionsHi))
            put("factsVsInterpretationsNote", factsVsInterpretationsNote)
            put("factsVsInterpretationsNoteHi", factsVsInterpretationsNoteHi)
            put("verificationDisclaimer", verificationDisclaimer)
            put("extractionMethod", extractionMethod.name)
            put("limitWarning", limitWarning ?: "")
            put("analyzedAtMillis", analyzedAtMillis)
        }
    }

    companion object {
        const val NOT_STATED = "Not stated in the notice"
        const val NOT_SPECIFIED_LABEL_EN = "Not specified in the notice"
        const val NOT_STATED_HI = "नोटिस में उल्लेख नहीं है"
        const val NOT_SPECIFIED_LABEL_HI = "नोटिस में उल्लेख नहीं है"
        const val DEFAULT_FACTS_NOTE_EN =
            "Stated facts are taken directly from the notice text; any ambiguities or unstated details are explicitly marked as not specified."
        const val DEFAULT_FACTS_NOTE_HI =
            "यहाँ दिए गए तथ्य सीधे नोटिस से लिए गए हैं; जो विवरण नोटिस में नहीं लिखे हैं उन्हें स्पष्ट रूप से चिह्नित किया गया है।"
        const val DEFAULT_VERIFICATION_DISCLAIMER =
            "Unverified notice: This description is based strictly on the provided notice content. Uploading or analyzing a document does not verify that it is authentic or official."

        private val UNSPECIFIED_PATTERNS = setOf(
            "not stated in the notice",
            "not stated in notice",
            "not specified in the notice",
            "not specified in notice",
            "not mentioned in the notice",
            "not mentioned in notice",
            "not provided in the notice",
            "not applicable",
            "not stated",
            "not specified",
            "not mentioned",
            "none stated",
            "none specified",
            "none",
            "n/a",
            "na",
            "nil",
            "null",
            "-",
            "—",
            "नोटिस में उल्लेख नहीं है",
            "नोटिस में उल्लेख नहीं",
            "नोटिस में निर्दिष्ट नहीं है",
            "नोटिस में निर्दिष्ट नहीं",
            "नोटिस में नहीं दिया गया है",
            "उल्लेख नहीं है",
            "निर्दिष्ट नहीं है",
            "लागू नहीं"
        )

        fun isNotSpecifiedValue(raw: String?): Boolean {
            if (raw == null) return true
            val normalized = raw.trim()
                .trim('.', ',', ';', ':', '-', '—', '"', '\'', '(', ')')
                .replace(Regex("\\s+"), " ")
                .lowercase()
            if (normalized.isBlank()) return true
            return normalized in UNSPECIFIED_PATTERNS
        }

        fun isNotSpecifiedList(items: List<String>?): Boolean {
            if (items.isNullOrEmpty()) return true
            return items.all { isNotSpecifiedValue(it) }
        }

        fun cleanStepPrefix(rawStep: String): String {
            return rawStep.trim()
                .replace(Regex("^(?:(?:Step|चरण|कदम)\\s*[0-9०-९]+[:.)\\-–—]\\s*|[0-9०-९]+[.)\\-–—]\\s*)", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { rawStep.trim() }
        }

        fun fromJsonObject(obj: JSONObject?): NoticeDescriptionResult? {
            if (obj == null) return null
            val summary = obj.optString("summary", "").trim()
            val purpose = obj.optString("purpose", "").trim()
            if (summary.isBlank() && purpose.isBlank()) return null

            fun JSONArray?.toNonBlankList(): List<String> {
                if (this == null) return emptyList()
                val list = mutableListOf<String>()
                for (i in 0 until length()) {
                    val item = optString(i, "").trim()
                    if (item.isNotBlank()) list.add(item)
                }
                return list
            }

            val methodStr = obj.optString("extractionMethod", ExtractionSourceMethod.DIRECT_TEXT.name)
            val method = runCatching { ExtractionSourceMethod.valueOf(methodStr) }
                .getOrDefault(ExtractionSourceMethod.DIRECT_TEXT)
            val rawWarning = obj.optString("limitWarning", "").trim()

            val rawEligibility = obj.optString("eligibility", NOT_STATED).trim()
            val normalizedEligibility = if (isNotSpecifiedValue(rawEligibility)) NOT_STATED else rawEligibility
            val rawContact = obj.optString("contactDetails", NOT_STATED).trim()
            val normalizedContact = if (isNotSpecifiedValue(rawContact)) NOT_STATED else rawContact

            val rawDates = obj.optJSONArray("importantDates").toNonBlankList()
            val normalizedDates = if (isNotSpecifiedList(rawDates)) listOf(NOT_STATED) else rawDates
            val rawDocs = obj.optJSONArray("requiredDocuments").toNonBlankList()
            val normalizedDocs = if (isNotSpecifiedList(rawDocs)) listOf(NOT_STATED) else rawDocs
            val rawSteps = obj.optJSONArray("nextSteps").toNonBlankList()
            val normalizedSteps = if (isNotSpecifiedList(rawSteps)) listOf(NOT_STATED) else rawSteps.map { cleanStepPrefix(it) }

            return NoticeDescriptionResult(
                shortTitle = obj.optString("shortTitle", "").trim(),
                titleHi = obj.optString("titleHi", "").trim(),
                summary = summary.ifBlank { purpose },
                summaryHi = obj.optString("summaryHi", "").trim(),
                purpose = purpose.ifBlank { NOT_STATED },
                eligibility = normalizedEligibility,
                eligibilityHi = obj.optString("eligibilityHi", "").trim(),
                importantDates = normalizedDates,
                importantDatesHi = obj.optJSONArray("importantDatesHi").toNonBlankList(),
                requiredDocuments = normalizedDocs,
                requiredDocumentsHi = obj.optJSONArray("requiredDocumentsHi").toNonBlankList(),
                contactDetails = normalizedContact,
                contactDetailsHi = obj.optString("contactDetailsHi", "").trim(),
                nextSteps = normalizedSteps,
                nextStepsHi = obj.optJSONArray("nextStepsHi").toNonBlankList().map { cleanStepPrefix(it) },
                otherConditions = obj.optJSONArray("otherConditions").toNonBlankList()
                    .filterNot { isNotSpecifiedValue(it) },
                otherConditionsHi = obj.optJSONArray("otherConditionsHi").toNonBlankList()
                    .filterNot { isNotSpecifiedValue(it) },
                missingOrAmbiguousInfo = obj.optJSONArray("missingOrAmbiguousInfo").toNonBlankList(),
                missingOrAmbiguousInfoHi = obj.optJSONArray("missingOrAmbiguousInfoHi").toNonBlankList(),
                askNoticeBuddyQuestions = obj.optJSONArray("askNoticeBuddyQuestions").toNonBlankList()
                    .filterNot { isNotSpecifiedValue(it) },
                askNoticeBuddyQuestionsHi = obj.optJSONArray("askNoticeBuddyQuestionsHi").toNonBlankList()
                    .filterNot { isNotSpecifiedValue(it) },
                factsVsInterpretationsNote = obj.optString(
                    "factsVsInterpretationsNote",
                    DEFAULT_FACTS_NOTE_EN
                ).trim().ifBlank { DEFAULT_FACTS_NOTE_EN },
                factsVsInterpretationsNoteHi = obj.optString(
                    "factsVsInterpretationsNoteHi",
                    DEFAULT_FACTS_NOTE_HI
                ).trim().ifBlank { DEFAULT_FACTS_NOTE_HI },
                verificationDisclaimer = obj.optString(
                    "verificationDisclaimer",
                    DEFAULT_VERIFICATION_DISCLAIMER
                ).trim().ifBlank { DEFAULT_VERIFICATION_DISCLAIMER },
                extractionMethod = method,
                limitWarning = rawWarning.ifBlank { null },
                analyzedAtMillis = obj.optLong("analyzedAtMillis", 0L)
            )
        }
    }
}
