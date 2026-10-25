package com.example.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDiagnosticLogger
import com.example.data.local.AppThemeMode
import com.example.data.local.NoticeInputType
import com.example.data.local.RecentNoticeEntry
import com.example.data.local.RecentNoticeStorage
import com.example.data.model.ExtractionSourceMethod
import com.example.data.model.NoticeDescriptionResult
import com.example.data.model.NoticeLanguage
import com.example.data.model.PdfExtractionResult
import com.example.data.pdf.PdfNoticeExtractor
import com.example.data.remote.NoticeAnalysisService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class NoticeAppRoute {
    HOME,
    RECENT_NOTICES,
    ENTER_TEXT_INPUT,
    ENTER_TEXT_CONFIRMATION,
    UPLOAD_PDF_SELECT,
    UPLOAD_PDF_CONFIRMATION,
    UPLOAD_IMAGE_SELECT,
    UPLOAD_IMAGE_CONFIRMATION,
    SETTINGS,
    SETTINGS_API_KEYS,
    SETTINGS_MODEL,
    SETTINGS_DEBUG_LOGS
}

data class SelectedPdfDraft(
    val fileName: String,
    val fileSizeBytes: Long,
    val uriString: String = "",
    val inMemoryBytes: ByteArray? = null
)

data class SelectedImageDraft(
    val fileName: String,
    val fileSizeBytes: Long,
    val mimeType: String = "image/jpeg",
    val uriString: String = "",
    val inMemoryBytes: ByteArray? = null
)

data class HomePhaseUiState(
    val currentRoute: NoticeAppRoute = NoticeAppRoute.HOME,
    val backStack: List<NoticeAppRoute> = emptyList(),
    val isProfileMenuOpen: Boolean = false,
    val themeMode: AppThemeMode = AppThemeMode.NIGHT,
    val selectedLanguage: NoticeLanguage = NoticeLanguage.ENGLISH,
    val draftNoticeText: String = "",
    val textValidationError: String? = null,
    val selectedPdfDraft: SelectedPdfDraft? = null,
    val pdfValidationError: String? = null,
    val selectedImageDraft: SelectedImageDraft? = null,
    val imageValidationError: String? = null,
    val activeConfirmedEntry: RecentNoticeEntry? = null,
    val recentNotices: List<RecentNoticeEntry> = emptyList(),
    val isProcessing: Boolean = false,
    val processingStageMessage: String? = null,
    val analysisError: String? = null,
    val lastPdfExtractionResult: PdfExtractionResult? = null
)

class HomePhaseViewModel(
    private val storage: RecentNoticeStorage,
    private val analysisService: NoticeAnalysisService = NoticeAnalysisService(),
    private val pdfExtractor: PdfNoticeExtractor? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomePhaseUiState(
            themeMode = storage.getThemeMode(),
            recentNotices = storage.loadRecentNotices()
        )
    )
    val uiState: StateFlow<HomePhaseUiState> = _uiState.asStateFlow()

    fun navigateTo(target: NoticeAppRoute) {
        _uiState.update { state ->
            if (state.currentRoute == target) {
                state.copy(isProfileMenuOpen = false)
            } else {
                state.copy(
                    currentRoute = target,
                    backStack = state.backStack + state.currentRoute,
                    isProfileMenuOpen = false,
                    textValidationError = null,
                    pdfValidationError = null,
                    imageValidationError = null
                )
            }
        }
    }

    fun onHomeButtonClicked() {
        _uiState.update { state ->
            if (state.currentRoute == NoticeAppRoute.HOME) {
                state.copy(isProfileMenuOpen = false)
            } else {
                state.copy(
                    currentRoute = NoticeAppRoute.HOME,
                    backStack = emptyList(),
                    isProfileMenuOpen = false,
                    textValidationError = null,
                    pdfValidationError = null,
                    imageValidationError = null
                )
            }
        }
    }

    fun navigateBack(): Boolean {
        val current = _uiState.value
        if (current.isProfileMenuOpen) {
            _uiState.update { it.copy(isProfileMenuOpen = false) }
            return true
        }
        if (current.currentRoute == NoticeAppRoute.HOME) {
            return false
        }
        val previous = current.backStack.lastOrNull() ?: NoticeAppRoute.HOME
        val remainingStack = if (current.backStack.isNotEmpty()) {
            current.backStack.dropLast(1)
        } else {
            emptyList()
        }
        _uiState.update {
            it.copy(
                currentRoute = previous,
                backStack = remainingStack,
                textValidationError = null,
                pdfValidationError = null,
                imageValidationError = null
            )
        }
        return true
    }

    fun toggleProfileMenu() {
        _uiState.update { it.copy(isProfileMenuOpen = !it.isProfileMenuOpen) }
    }

    fun dismissProfileMenu() {
        _uiState.update { it.copy(isProfileMenuOpen = false) }
    }

    fun openSettingsFromProfile() {
        navigateTo(NoticeAppRoute.SETTINGS)
    }

    fun toggleThemeMode() {
        val nextMode = if (_uiState.value.themeMode == AppThemeMode.NIGHT) {
            AppThemeMode.DAY
        } else {
            AppThemeMode.NIGHT
        }
        storage.setThemeMode(nextMode)
        _uiState.update { it.copy(themeMode = nextMode) }
    }

    fun setThemeMode(mode: AppThemeMode) {
        storage.setThemeMode(mode)
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setNoticeLanguage(language: NoticeLanguage) {
        _uiState.update { it.copy(selectedLanguage = language) }
    }

    fun toggleNoticeLanguage() {
        val next = if (_uiState.value.selectedLanguage == NoticeLanguage.ENGLISH) {
            NoticeLanguage.HINDI
        } else {
            NoticeLanguage.ENGLISH
        }
        _uiState.update { it.copy(selectedLanguage = next) }
    }

    // --- Saved Notice Deletion ---

    fun deleteSavedNotice(entryId: String) {
        val updatedRecents = storage.deleteNotice(entryId)
        _uiState.update { state ->
            val clearedActive = if (state.activeConfirmedEntry?.id == entryId) {
                null
            } else {
                state.activeConfirmedEntry
            }
            state.copy(
                recentNotices = updatedRecents,
                activeConfirmedEntry = clearedActive
            )
        }
    }

    fun clearAllSavedNotices() {
        storage.clearAll()
        _uiState.update { state ->
            state.copy(
                recentNotices = emptyList(),
                activeConfirmedEntry = null
            )
        }
    }

    // --- Enter Notice Text & AI Notice Description ---

    fun updateDraftNoticeText(newText: String) {
        _uiState.update {
            it.copy(
                draftNoticeText = newText,
                textValidationError = null
            )
        }
    }

    fun submitNoticeText(): Boolean {
        if (_uiState.value.isProcessing) {
            return false
        }

        val currentText = _uiState.value.draftNoticeText
        if (currentText.isBlank()) {
            _uiState.update {
                it.copy(textValidationError = "Please enter or paste notice text before continuing.")
            }
            return false
        }

        if (currentText.trim().length > NoticeAnalysisService.DEFAULT_HARD_REJECT_CHARS) {
            _uiState.update {
                it.copy(
                    textValidationError = "Notice text is too long (${currentText.trim().length} characters; max ${NoticeAnalysisService.DEFAULT_HARD_REJECT_CHARS}). Please shorten the text."
                )
            }
            return false
        }

        val savedEntry = storage.saveOrUpdateTextNotice(currentText)
        val updatedRecents = storage.loadRecentNotices()
        _uiState.update { state ->
            state.copy(
                currentRoute = NoticeAppRoute.ENTER_TEXT_CONFIRMATION,
                backStack = state.backStack + state.currentRoute,
                textValidationError = null,
                activeConfirmedEntry = savedEntry,
                recentNotices = updatedRecents,
                isProcessing = true,
                processingStageMessage = "Summarizing notice in simple English & हिंदी...",
                analysisError = null
            )
        }

        analyzeTextEntryAsync(
            entryId = savedEntry.id,
            noticeText = savedEntry.textContent,
            extractionMethod = ExtractionSourceMethod.DIRECT_TEXT,
            limitWarning = null
        )
        return true
    }

    private fun analyzeTextEntryAsync(
        entryId: String,
        noticeText: String,
        extractionMethod: ExtractionSourceMethod,
        limitWarning: String?,
        extractedPdfTextToSave: String? = null
    ) {
        viewModelScope.launch(mainDispatcher) {
            val result = analysisService.generateNoticeDescription(
                rawNoticeText = noticeText,
                extractionMethod = extractionMethod,
                existingLimitWarning = limitWarning
            )
            result.fold(
                onSuccess = { description: NoticeDescriptionResult ->
                    val updatedEntry = storage.attachDescriptionToEntry(
                        entryId = entryId,
                        description = description,
                        extractedPdfText = extractedPdfTextToSave
                    )
                    val refreshedRecents = storage.loadRecentNotices()
                    _uiState.update { state ->
                        state.copy(
                            activeConfirmedEntry = updatedEntry ?: state.activeConfirmedEntry?.copy(
                                descriptionResult = description
                            ),
                            recentNotices = refreshedRecents,
                            isProcessing = false,
                            processingStageMessage = null,
                            analysisError = null
                        )
                    }
                },
                onFailure = { err ->
                    AppDiagnosticLogger.error("ANALYSIS", "Text notice analysis failed", err)
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            analysisError = err.message ?: "Failed to generate notice description."
                        )
                    }
                }
            )
        }
    }

    // --- Upload Notice PDF (Fast Single-Pass Multimodal Vision + OCR Fallback) ---

    fun onPdfFilePicked(
        fileName: String?,
        mimeType: String?,
        fileSizeBytes: Long,
        uriString: String = "",
        inMemoryBytes: ByteArray? = null
    ) {
        if (fileName == null && mimeType == null && uriString.isBlank() && inMemoryBytes == null) {
            return
        }

        val resolvedName = fileName?.trim().orEmpty()
        val isPdfMime = mimeType?.equals("application/pdf", ignoreCase = true) == true
        val isPdfExtension = resolvedName.lowercase().endsWith(".pdf")

        if (!isPdfMime && !isPdfExtension) {
            _uiState.update {
                it.copy(
                    pdfValidationError = "Unsupported file format. Please select a PDF (.pdf) file only."
                )
            }
            return
        }

        val maxAllowedBytes = 15L * 1024L * 1024L
        if (fileSizeBytes > maxAllowedBytes) {
            _uiState.update {
                it.copy(
                    pdfValidationError = "Selected PDF exceeds the 15 MB size limit. Please select a smaller PDF."
                )
            }
            return
        }

        val safeFileName = if (resolvedName.isNotBlank()) resolvedName else "Selected_Notice.pdf"
        AppDiagnosticLogger.info(
            "PICKER",
            "Selected PDF file: name=$safeFileName, size=$fileSizeBytes bytes, mime=${mimeType ?: "application/pdf"}"
        )
        _uiState.update {
            it.copy(
                selectedPdfDraft = SelectedPdfDraft(
                    fileName = safeFileName,
                    fileSizeBytes = fileSizeBytes,
                    uriString = uriString,
                    inMemoryBytes = inMemoryBytes
                ),
                pdfValidationError = null,
                analysisError = null
            )
        }
    }

    fun onPdfPickerCancelled() {
        // Graceful no-op
    }

    fun removeSelectedPdf() {
        _uiState.update {
            it.copy(
                selectedPdfDraft = null,
                pdfValidationError = null,
                analysisError = null,
                lastPdfExtractionResult = null
            )
        }
    }

    fun submitSelectedPdf(): Boolean {
        if (_uiState.value.isProcessing) {
            return false
        }

        val draft = _uiState.value.selectedPdfDraft
        if (draft == null) {
            _uiState.update {
                it.copy(pdfValidationError = "Please select a valid PDF file before continuing.")
            }
            return false
        }

        val savedEntry = storage.saveOrUpdatePdfNotice(
            fileName = draft.fileName,
            fileSizeBytes = draft.fileSizeBytes,
            uriString = draft.uriString
        )
        val updatedRecents = storage.loadRecentNotices()
        _uiState.update { state ->
            state.copy(
                currentRoute = NoticeAppRoute.UPLOAD_PDF_CONFIRMATION,
                backStack = state.backStack + state.currentRoute,
                pdfValidationError = null,
                activeConfirmedEntry = savedEntry,
                recentNotices = updatedRecents,
                isProcessing = pdfExtractor != null,
                processingStageMessage = if (pdfExtractor != null) "Reading PDF pages and generating simple summary..." else null,
                analysisError = null,
                lastPdfExtractionResult = null
            )
        }

        if (pdfExtractor != null) {
            extractAndAnalyzePdfAsync(savedEntry.id, draft)
        }
        return true
    }

    private fun extractAndAnalyzePdfAsync(
        entryId: String,
        draft: SelectedPdfDraft
    ) {
        val extractor = pdfExtractor ?: return
        viewModelScope.launch(mainDispatcher) {
            // Fast Single-Pass Multimodal Path for real Android devices:
            // If the PDF has scanned/image pages and PdfRenderer renders valid page bitmaps,
            // analyze the rendered bitmaps + selectable text directly in ONE single AI call.
            val multimodalPrep = when {
                draft.inMemoryBytes != null -> extractor.preparePdfBytesForMultimodal(draft.inMemoryBytes)
                draft.uriString.isNotBlank() -> {
                    val parsedUri = runCatching { Uri.parse(draft.uriString) }.getOrNull()
                    if (parsedUri != null) {
                        extractor.preparePdfForMultimodal(parsedUri, draft.fileSizeBytes)
                    } else null
                }
                else -> null
            }?.getOrNull()

            if (multimodalPrep != null && multimodalPrep.pageBitmaps.isNotEmpty() && multimodalPrep.hasScannedOrImagePages) {
                try {
                    _uiState.update {
                        it.copy(processingStageMessage = "Analyzing notice image pages in one fast pass...")
                    }
                    val mmResult = analysisService.generateNoticeDescriptionFromMultimodal(
                        pageBitmaps = multimodalPrep.pageBitmaps,
                        supplementalText = multimodalPrep.selectableText,
                        extractionMethod = ExtractionSourceMethod.PDF_MULTIMODAL_VISION,
                        existingLimitWarning = multimodalPrep.limitWarning
                    )
                    if (mmResult.isSuccess) {
                        val description = mmResult.getOrThrow()
                        val syntheticExtraction = PdfExtractionResult(
                            extractedText = multimodalPrep.selectableText.ifBlank { description.summary },
                            method = ExtractionSourceMethod.PDF_MULTIMODAL_VISION,
                            totalPageCount = multimodalPrep.totalPageCount,
                            processedPageCount = multimodalPrep.processedPageCount,
                            ocrPageCount = multimodalPrep.pageBitmaps.size,
                            emptyPageCount = 0,
                            rotatedPageCount = 0,
                            limitWarning = multimodalPrep.limitWarning
                        )
                        val updatedEntry = storage.attachDescriptionToEntry(
                            entryId = entryId,
                            description = description,
                            extractedPdfText = multimodalPrep.selectableText.ifBlank { description.summary }
                        )
                        val refreshedRecents = storage.loadRecentNotices()
                        _uiState.update { state ->
                            state.copy(
                                activeConfirmedEntry = updatedEntry ?: state.activeConfirmedEntry?.copy(
                                    descriptionResult = description
                                ),
                                recentNotices = refreshedRecents,
                                lastPdfExtractionResult = syntheticExtraction,
                                isProcessing = false,
                                processingStageMessage = null,
                                analysisError = null
                            )
                        }
                        return@launch
                    }
                } finally {
                    multimodalPrep.pageBitmaps.forEach { bmp ->
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                }
            } else {
                multimodalPrep?.pageBitmaps?.forEach { bmp ->
                    if (!bmp.isRecycled) bmp.recycle()
                }
            }

            // Standard Extraction + OCR Pipeline (handles selectable PDFs & unit test PDFs)
            val extractionResult = when {
                draft.inMemoryBytes != null -> {
                    extractor.extractFromBytes(draft.inMemoryBytes) { progressMsg ->
                        _uiState.update { it.copy(processingStageMessage = progressMsg) }
                    }
                }
                draft.uriString.isNotBlank() -> {
                    val parsedUri = runCatching { Uri.parse(draft.uriString) }.getOrNull()
                    if (parsedUri == null) {
                        Result.failure(IllegalArgumentException("Invalid PDF file URI."))
                    } else {
                        extractor.extractFromUri(
                            uri = parsedUri,
                            declaredSizeBytes = draft.fileSizeBytes
                        ) { progressMsg ->
                            _uiState.update { it.copy(processingStageMessage = progressMsg) }
                        }
                    }
                }
                else -> {
                    Result.failure(
                        IllegalArgumentException("No readable PDF stream was provided for extraction.")
                    )
                }
            }

            extractionResult.fold(
                onSuccess = { extracted: PdfExtractionResult ->
                    val updatedWithText = storage.attachExtractedPdfText(entryId, extracted.extractedText)
                    val refreshedRecents = storage.loadRecentNotices()
                    _uiState.update { state ->
                        state.copy(
                            activeConfirmedEntry = updatedWithText ?: state.activeConfirmedEntry?.copy(
                                extractedPdfText = extracted.extractedText
                            ),
                            recentNotices = refreshedRecents,
                            lastPdfExtractionResult = extracted,
                            processingStageMessage = "Summarizing notice in simple English & हिंदी..."
                        )
                    }

                    analyzeTextEntryAsync(
                        entryId = entryId,
                        noticeText = extracted.extractedText,
                        extractionMethod = extracted.method,
                        limitWarning = extracted.limitWarning,
                        extractedPdfTextToSave = extracted.extractedText
                    )
                },
                onFailure = { err ->
                    AppDiagnosticLogger.error("PDF", "PDF extraction failed for ${draft.fileName}", err)
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            analysisError = err.message ?: "Failed to extract text from the selected PDF."
                        )
                    }
                }
            )
        }
    }

    // --- Upload Notice Image (Direct Photo / Screenshot Notice Reading) ---

    fun onImageFilePicked(
        fileName: String?,
        mimeType: String?,
        fileSizeBytes: Long,
        uriString: String = "",
        inMemoryBytes: ByteArray? = null
    ) {
        if (fileName == null && mimeType == null && uriString.isBlank() && inMemoryBytes == null) {
            return
        }

        val resolvedName = fileName?.trim().orEmpty()
        val lowerName = resolvedName.lowercase()
        val isImageMime = mimeType?.startsWith("image/", ignoreCase = true) == true
        val isImageExt = lowerName.endsWith(".jpg") ||
            lowerName.endsWith(".jpeg") ||
            lowerName.endsWith(".png") ||
            lowerName.endsWith(".webp") ||
            lowerName.endsWith(".heic")

        if (!isImageMime && !isImageExt && uriString.isBlank() && inMemoryBytes == null) {
            _uiState.update {
                it.copy(imageValidationError = "Please select a valid image file (JPG, PNG, or WEBP).")
            }
            return
        }

        val maxAllowedBytes = 15L * 1024L * 1024L
        if (fileSizeBytes > maxAllowedBytes) {
            _uiState.update {
                it.copy(imageValidationError = "Selected image exceeds the 15 MB limit. Please choose a smaller photo.")
            }
            return
        }

        val safeName = if (resolvedName.isNotBlank()) resolvedName else "Notice_Photo.jpg"
        AppDiagnosticLogger.info(
            "PICKER",
            "Selected Image file: name=$safeName, size=$fileSizeBytes bytes, mime=${mimeType ?: "image/jpeg"}"
        )
        _uiState.update {
            it.copy(
                selectedImageDraft = SelectedImageDraft(
                    fileName = safeName,
                    fileSizeBytes = fileSizeBytes,
                    mimeType = mimeType ?: "image/jpeg",
                    uriString = uriString,
                    inMemoryBytes = inMemoryBytes
                ),
                imageValidationError = null,
                analysisError = null
            )
        }
    }

    fun removeSelectedImage() {
        _uiState.update {
            it.copy(
                selectedImageDraft = null,
                imageValidationError = null,
                analysisError = null
            )
        }
    }

    fun submitSelectedImage(): Boolean {
        if (_uiState.value.isProcessing) {
            return false
        }

        val draft = _uiState.value.selectedImageDraft
        if (draft == null) {
            _uiState.update {
                it.copy(imageValidationError = "Please select a notice image or photo first.")
            }
            return false
        }

        val savedEntry = storage.saveOrUpdateImageNotice(
            fileName = draft.fileName,
            fileSizeBytes = draft.fileSizeBytes,
            uriString = draft.uriString
        )
        val updatedRecents = storage.loadRecentNotices()
        _uiState.update { state ->
            state.copy(
                currentRoute = NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION,
                backStack = state.backStack + state.currentRoute,
                imageValidationError = null,
                activeConfirmedEntry = savedEntry,
                recentNotices = updatedRecents,
                isProcessing = pdfExtractor != null,
                processingStageMessage = if (pdfExtractor != null) "Reading notice image and generating simple summary..." else null,
                analysisError = null
            )
        }

        if (pdfExtractor != null) {
            analyzeImageDraftAsync(savedEntry.id, draft)
        }
        return true
    }

    private fun analyzeImageDraftAsync(
        entryId: String,
        draft: SelectedImageDraft
    ) {
        val extractor = pdfExtractor ?: return
        viewModelScope.launch(mainDispatcher) {
            val bitmapResult = when {
                draft.inMemoryBytes != null -> extractor.decodeNoticeImageFromBytes(draft.inMemoryBytes)
                draft.uriString.isNotBlank() -> {
                    val uri = runCatching { Uri.parse(draft.uriString) }.getOrNull()
                    if (uri == null) {
                        Result.failure(IllegalArgumentException("Invalid image URI."))
                    } else {
                        extractor.decodeNoticeImageFromUri(uri, draft.fileSizeBytes)
                    }
                }
                else -> Result.failure(IllegalArgumentException("No readable image provided."))
            }

            bitmapResult.fold(
                onSuccess = { bitmap ->
                    try {
                        _uiState.update {
                            it.copy(processingStageMessage = "Summarizing notice image in simple English & हिंदी...")
                        }
                        val analysisResult = analysisService.generateNoticeDescriptionFromMultimodal(
                            pageBitmaps = listOf(bitmap),
                            supplementalText = "",
                            extractionMethod = ExtractionSourceMethod.IMAGE_NOTICE
                        )
                        analysisResult.fold(
                            onSuccess = { description ->
                                val updatedEntry = storage.attachDescriptionToEntry(
                                    entryId = entryId,
                                    description = description,
                                    extractedPdfText = description.summary
                                )
                                val refreshedRecents = storage.loadRecentNotices()
                                _uiState.update { state ->
                                    state.copy(
                                        activeConfirmedEntry = updatedEntry ?: state.activeConfirmedEntry?.copy(
                                            descriptionResult = description
                                        ),
                                        recentNotices = refreshedRecents,
                                        isProcessing = false,
                                        processingStageMessage = null,
                                        analysisError = null
                                    )
                                }
                            },
                            onFailure = { err ->
                                _uiState.update { state ->
                                    state.copy(
                                        isProcessing = false,
                                        processingStageMessage = null,
                                        analysisError = err.message ?: "Failed to read notice image."
                                    )
                                }
                            }
                        )
                    } finally {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                },
                onFailure = { err ->
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            analysisError = err.message ?: "Could not open the selected image."
                        )
                    }
                }
            )
        }
    }

    fun retryCurrentNoticeAnalysis() {
        if (_uiState.value.isProcessing) return
        val activeEntry = _uiState.value.activeConfirmedEntry ?: return

        when (activeEntry.inputType) {
            NoticeInputType.TEXT -> {
                val textToAnalyze = activeEntry.textContent.ifBlank { _uiState.value.draftNoticeText }
                if (textToAnalyze.isBlank()) return
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingStageMessage = "Retrying notice analysis...",
                        analysisError = null
                    )
                }
                analyzeTextEntryAsync(
                    entryId = activeEntry.id,
                    noticeText = textToAnalyze,
                    extractionMethod = ExtractionSourceMethod.DIRECT_TEXT,
                    limitWarning = null
                )
            }

            NoticeInputType.PDF -> {
                if (activeEntry.extractedPdfText.isNotBlank()) {
                    val lastMethod = _uiState.value.lastPdfExtractionResult?.method
                        ?: ExtractionSourceMethod.PDF_SELECTABLE_TEXT
                    val lastWarn = _uiState.value.lastPdfExtractionResult?.limitWarning
                    _uiState.update {
                        it.copy(
                            isProcessing = true,
                            processingStageMessage = "Retrying notice summary...",
                            analysisError = null
                        )
                    }
                    analyzeTextEntryAsync(
                        entryId = activeEntry.id,
                        noticeText = activeEntry.extractedPdfText,
                        extractionMethod = lastMethod,
                        limitWarning = lastWarn,
                        extractedPdfTextToSave = activeEntry.extractedPdfText
                    )
                } else {
                    val draft = _uiState.value.selectedPdfDraft ?: SelectedPdfDraft(
                        fileName = activeEntry.pdfFileName.ifBlank { activeEntry.title },
                        fileSizeBytes = activeEntry.pdfFileSizeBytes,
                        uriString = activeEntry.pdfUriString
                    )
                    _uiState.update {
                        it.copy(
                            isProcessing = true,
                            processingStageMessage = "Retrying PDF reading and analysis...",
                            analysisError = null
                        )
                    }
                    extractAndAnalyzePdfAsync(activeEntry.id, draft)
                }
            }

            NoticeInputType.IMAGE -> {
                val draft = _uiState.value.selectedImageDraft ?: SelectedImageDraft(
                    fileName = activeEntry.pdfFileName.ifBlank { activeEntry.title },
                    fileSizeBytes = activeEntry.pdfFileSizeBytes,
                    uriString = activeEntry.pdfUriString
                )
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingStageMessage = "Retrying notice image reading...",
                        analysisError = null
                    )
                }
                analyzeImageDraftAsync(activeEntry.id, draft)
            }
        }
    }

    // --- Reopening Saved Notices ---

    fun openRecentNotice(entry: RecentNoticeEntry) {
        when (entry.inputType) {
            NoticeInputType.TEXT -> {
                val route = if (entry.descriptionResult != null) {
                    NoticeAppRoute.ENTER_TEXT_CONFIRMATION
                } else {
                    NoticeAppRoute.ENTER_TEXT_INPUT
                }
                _uiState.update { state ->
                    state.copy(
                        draftNoticeText = entry.textContent,
                        activeConfirmedEntry = entry,
                        currentRoute = route,
                        backStack = state.backStack + state.currentRoute,
                        textValidationError = null,
                        analysisError = null
                    )
                }
            }

            NoticeInputType.PDF -> {
                val route = if (entry.descriptionResult != null) {
                    NoticeAppRoute.UPLOAD_PDF_CONFIRMATION
                } else {
                    NoticeAppRoute.UPLOAD_PDF_SELECT
                }
                _uiState.update { state ->
                    state.copy(
                        selectedPdfDraft = SelectedPdfDraft(
                            fileName = entry.pdfFileName.ifBlank { entry.title },
                            fileSizeBytes = entry.pdfFileSizeBytes,
                            uriString = entry.pdfUriString
                        ),
                        activeConfirmedEntry = entry,
                        currentRoute = route,
                        backStack = state.backStack + state.currentRoute,
                        pdfValidationError = null,
                        analysisError = null
                    )
                }
            }

            NoticeInputType.IMAGE -> {
                val route = if (entry.descriptionResult != null) {
                    NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION
                } else {
                    NoticeAppRoute.UPLOAD_IMAGE_SELECT
                }
                _uiState.update { state ->
                    state.copy(
                        selectedImageDraft = SelectedImageDraft(
                            fileName = entry.pdfFileName.ifBlank { entry.title },
                            fileSizeBytes = entry.pdfFileSizeBytes,
                            uriString = entry.pdfUriString
                        ),
                        activeConfirmedEntry = entry,
                        currentRoute = route,
                        backStack = state.backStack + state.currentRoute,
                        imageValidationError = null,
                        analysisError = null
                    )
                }
            }
        }
    }

    companion object {
        const val DISPLAY_MODEL_NAME = "Gemma 4 31B"
    }
}
