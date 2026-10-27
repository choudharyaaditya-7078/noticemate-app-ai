package com.example.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDiagnosticLogger
import com.example.data.local.AppThemeMode
import com.example.data.local.NoticeInputType
import com.example.data.local.RecentNoticeEntry
import com.example.data.local.RecentNoticeStorage
import com.example.data.model.CameraPermissionState
import com.example.data.model.CameraQualityGuidance
import com.example.data.model.CameraScanUiState
import com.example.data.model.CameraSessionStage
import com.example.data.model.CapturedNoticePhoto
import com.example.data.model.CropRectNormalized
import com.example.data.model.DetectedDocumentQuad
import com.example.data.model.DocumentDetectionUiState
import com.example.data.model.DocumentFrameAnalysisResult
import com.example.data.model.ExtractionSourceMethod
import com.example.data.model.FailedPipelineStage
import com.example.data.model.NoticeDescriptionResult
import com.example.data.model.NoticeLanguage
import com.example.data.model.NoticePageEditState
import com.example.data.model.NoticeRequestError
import com.example.data.model.NoticeValidationGateResult
import com.example.data.model.NoticeValidationStatus
import com.example.data.model.PageOcrPreviewStatus
import com.example.data.model.PdfExtractionResult
import com.example.data.model.ScannedNoticePage
import com.example.data.pdf.PdfNoticeExtractor
import com.example.data.remote.ControllableNetworkConnectivityMonitor
import com.example.data.remote.NetworkConnectivityMonitor
import com.example.data.remote.NetworkConnectivityStatus
import com.example.data.remote.NoticeAnalysisService
import com.example.data.remote.NoticeErrorClassifier
import com.example.data.remote.NoticeValidationGate
import com.example.data.remote.NoticeValidationGateException
import com.example.ui.camera.NoticeDocumentEdgeAnalyzer
import com.example.ui.camera.NoticeDocumentStabilityTracker
import com.example.ui.camera.NoticePageImageCorrector
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
    SCAN_NOTICE_CAMERA,
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
    val inMemoryBytes: ByteArray? = null,
    val pageBytesList: List<ByteArray> = emptyList(),
    val pageFileNames: List<String> = emptyList(),
    val preExtractedPageTexts: Map<Int, String> = emptyMap()
) {
    val pageCount: Int
        get() = when {
            pageBytesList.isNotEmpty() -> pageBytesList.size
            inMemoryBytes != null || uriString.isNotBlank() -> 1
            else -> 0
        }

    fun effectivePageBytesList(): List<ByteArray> {
        return when {
            pageBytesList.isNotEmpty() -> pageBytesList
            inMemoryBytes != null -> listOf(inMemoryBytes)
            else -> emptyList()
        }
    }
}

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
    val requestError: NoticeRequestError? = null,
    val validationGateResult: NoticeValidationGateResult? = null,
    val lastPdfExtractionResult: PdfExtractionResult? = null,
    val networkStatus: NetworkConnectivityStatus = NetworkConnectivityStatus.ONLINE,
    val cameraScanState: CameraScanUiState = CameraScanUiState()
)

class HomePhaseViewModel(
    private val storage: RecentNoticeStorage,
    private val analysisService: NoticeAnalysisService = NoticeAnalysisService(),
    private val pdfExtractor: PdfNoticeExtractor? = null,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val networkMonitor: NetworkConnectivityMonitor = ControllableNetworkConnectivityMonitor()
) : ViewModel() {

    private var activeProcessingJob: Job? = null
    private var activePipelineStage: FailedPipelineStage = FailedPipelineStage.NOTICE_CLASSIFICATION
    private val frameSequenceCounter = AtomicLong(1000L)
    private val pageIdCounter = AtomicLong(1L)

    private val _uiState = MutableStateFlow(
        HomePhaseUiState(
            themeMode = storage.getThemeMode(),
            recentNotices = storage.loadRecentNotices(),
            networkStatus = networkMonitor.checkCurrentConnectivity()
        )
    )
    val uiState: StateFlow<HomePhaseUiState> = _uiState.asStateFlow()

    init {
        networkMonitor.startMonitoring()
        viewModelScope.launch(mainDispatcher) {
            networkMonitor.status.collect { newStatus ->
                val previousStatus = _uiState.value.networkStatus
                _uiState.update { state ->
                    state.copy(networkStatus = newStatus)
                }

                // If connection is lost while an AI verification or analysis request is in flight,
                // abort the hung network wait and surface a clear NO_INTERNET error while preserving all state.
                if (!newStatus.hasInternetAccess &&
                    _uiState.value.isProcessing &&
                    activePipelineStage != FailedPipelineStage.DOCUMENT_UPLOAD_OR_READ
                ) {
                    val stageAtLoss = activePipelineStage
                    activeProcessingJob?.cancel(
                        CancellationException("Connection lost during $stageAtLoss")
                    )
                    activeProcessingJob = null
                    val offlineError = NoticeErrorClassifier.buildNoInternetError(
                        networkStatus = newStatus,
                        stage = stageAtLoss,
                        customDetail = "Network connection lost while analyzing notice."
                    )
                    AppDiagnosticLogger.warn(
                        "NETWORK",
                        "Connection lost mid-operation ($stageAtLoss): transitioning to NO_INTERNET state."
                    )
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            requestError = offlineError,
                            analysisError = offlineError.messageEn
                        )
                    }
                } else if (newStatus == NetworkConnectivityStatus.ONLINE &&
                    previousStatus != NetworkConnectivityStatus.ONLINE
                ) {
                    AppDiagnosticLogger.info("NETWORK", "Internet connectivity restored.")
                }
            }
        }
    }

    override fun onCleared() {
        networkMonitor.stopMonitoring()
        super.onCleared()
    }

    fun refreshNetworkConnectivity(): NetworkConnectivityStatus {
        val current = networkMonitor.checkCurrentConnectivity()
        _uiState.update { it.copy(networkStatus = current) }
        return current
    }

    private fun isOperationInFlight(): Boolean {
        return _uiState.value.isProcessing || activeProcessingJob?.isActive == true
    }

    private fun cleanupCameraStateForNavigation(
        currentCamera: CameraScanUiState,
        enteringCameraRoute: Boolean
    ): CameraScanUiState {
        return if (enteringCameraRoute) {
            currentCamera.copy(
                sessionStage = when {
                    currentCamera.capturedPhoto != null -> CameraSessionStage.REVIEWING_CAPTURE
                    currentCamera.permissionState == CameraPermissionState.GRANTED ->
                        if (currentCamera.isCameraBound) CameraSessionStage.PREVIEW_READY else CameraSessionStage.INITIALIZING
                    else -> CameraSessionStage.INITIALIZING
                },
                detectionState = DocumentDetectionUiState(),
                lastCaptureWasAuto = false,
                cameraErrorMessage = null
            )
        } else {
            currentCamera.copy(
                isCameraBound = false,
                isFlashEnabled = false,
                detectionState = DocumentDetectionUiState(),
                lastCaptureWasAuto = false,
                capturedPhoto = null,
                scannedPages = emptyList(),
                selectedPageIndex = 0,
                retakingPageId = null,
                isOcrCheckRunning = false,
                sessionStage = CameraSessionStage.INITIALIZING,
                cameraErrorMessage = null
            )
        }
    }

    fun navigateTo(target: NoticeAppRoute) {
        _uiState.update { state ->
            if (state.currentRoute == target) {
                state.copy(isProfileMenuOpen = false)
            } else {
                val updatedCamera = if (state.currentRoute == NoticeAppRoute.SCAN_NOTICE_CAMERA ||
                    target == NoticeAppRoute.SCAN_NOTICE_CAMERA
                ) {
                    cleanupCameraStateForNavigation(
                        currentCamera = state.cameraScanState,
                        enteringCameraRoute = target == NoticeAppRoute.SCAN_NOTICE_CAMERA
                    )
                } else {
                    state.cameraScanState
                }
                state.copy(
                    currentRoute = target,
                    backStack = state.backStack + state.currentRoute,
                    isProfileMenuOpen = false,
                    textValidationError = null,
                    pdfValidationError = null,
                    imageValidationError = null,
                    cameraScanState = updatedCamera
                )
            }
        }
    }

    fun onHomeButtonClicked() {
        _uiState.update { state ->
            if (state.currentRoute == NoticeAppRoute.HOME) {
                state.copy(isProfileMenuOpen = false)
            } else {
                val updatedCamera = if (state.currentRoute == NoticeAppRoute.SCAN_NOTICE_CAMERA) {
                    cleanupCameraStateForNavigation(
                        currentCamera = state.cameraScanState.copy(capturedPhoto = null),
                        enteringCameraRoute = false
                    )
                } else {
                    state.cameraScanState
                }
                state.copy(
                    currentRoute = NoticeAppRoute.HOME,
                    backStack = emptyList(),
                    isProfileMenuOpen = false,
                    textValidationError = null,
                    pdfValidationError = null,
                    imageValidationError = null,
                    cameraScanState = updatedCamera
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
        // If user is on the Camera screen reviewing a captured photo, Back returns to live camera preview (Retake)
        if (current.currentRoute == NoticeAppRoute.SCAN_NOTICE_CAMERA &&
            current.cameraScanState.capturedPhoto != null
        ) {
            onRetakeCapturedPhoto()
            return true
        }
        // If user is on the Camera screen in live preview after tapping "+ Add Page" or "Retake"
        // while existing pages are still held in session, Back returns to the multi-page review screen
        if (current.currentRoute == NoticeAppRoute.SCAN_NOTICE_CAMERA &&
            current.cameraScanState.capturedPhoto == null &&
            current.cameraScanState.scannedPages.isNotEmpty()
        ) {
            return onReturnToMultiPageReview()
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
        _uiState.update { state ->
            val updatedCamera = if (state.currentRoute == NoticeAppRoute.SCAN_NOTICE_CAMERA ||
                previous == NoticeAppRoute.SCAN_NOTICE_CAMERA
            ) {
                cleanupCameraStateForNavigation(
                    currentCamera = state.cameraScanState,
                    enteringCameraRoute = previous == NoticeAppRoute.SCAN_NOTICE_CAMERA
                )
            } else {
                state.cameraScanState
            }
            state.copy(
                currentRoute = previous,
                backStack = remainingStack,
                textValidationError = null,
                pdfValidationError = null,
                imageValidationError = null,
                cameraScanState = updatedCamera
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
        if (isOperationInFlight()) {
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
        val netStatus = networkMonitor.checkCurrentConnectivity()

        if (!netStatus.hasInternetAccess) {
            val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                networkStatus = netStatus,
                stage = FailedPipelineStage.NOTICE_CLASSIFICATION
            )
            _uiState.update { state ->
                state.copy(
                    currentRoute = NoticeAppRoute.ENTER_TEXT_CONFIRMATION,
                    backStack = state.backStack + state.currentRoute,
                    textValidationError = null,
                    activeConfirmedEntry = savedEntry,
                    recentNotices = updatedRecents,
                    isProcessing = false,
                    processingStageMessage = null,
                    analysisError = offlineErr.messageEn,
                    requestError = offlineErr,
                    validationGateResult = null,
                    networkStatus = netStatus
                )
            }
            return true
        }

        _uiState.update { state ->
            state.copy(
                currentRoute = NoticeAppRoute.ENTER_TEXT_CONFIRMATION,
                backStack = state.backStack + state.currentRoute,
                textValidationError = null,
                activeConfirmedEntry = savedEntry,
                recentNotices = updatedRecents,
                isProcessing = true,
                processingStageMessage = "Verifying document is an official notice...",
                analysisError = null,
                requestError = null,
                validationGateResult = null,
                networkStatus = netStatus
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
        extractedPdfTextToSave: String? = null,
        preValidatedGate: NoticeValidationGateResult? = null
    ) {
        activePipelineStage = FailedPipelineStage.NOTICE_CLASSIFICATION
        activeProcessingJob = viewModelScope.launch(mainDispatcher) {
            try {
                val initialNet = networkMonitor.checkCurrentConnectivity()
                if (!initialNet.hasInternetAccess) {
                    val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                        networkStatus = initialNet,
                        stage = FailedPipelineStage.NOTICE_CLASSIFICATION
                    )
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            requestError = offlineErr,
                            analysisError = offlineErr.messageEn,
                            networkStatus = initialNet
                        )
                    }
                    return@launch
                }

                val gateResult = if (preValidatedGate != null && preValidatedGate.isAllowedForNoticeAnalysis) {
                    preValidatedGate
                } else {
                    _uiState.update {
                        it.copy(processingStageMessage = "Verifying document is an official notice...")
                    }
                    // Phase 5 Enforcement Point: Distinct document-classification step BEFORE normal notice analysis
                    analysisService.validateNoticeDocument(
                        rawDocumentText = noticeText,
                        extractionMethod = extractionMethod
                    )
                }

                if (!gateResult.isAllowedForNoticeAnalysis) {
                    AppDiagnosticLogger.info(
                        "GATE",
                        "Document blocked before notice summarization: status=${gateResult.status}, category=${gateResult.category}"
                    )
                    val isClassifierError = gateResult.status == NoticeValidationStatus.CLASSIFICATION_ERROR
                    val classifiedErr = if (isClassifierError) {
                        NoticeErrorClassifier.classifyPipelineFailure(
                            stage = FailedPipelineStage.NOTICE_CLASSIFICATION,
                            rawMessage = gateResult.userMessageEn,
                            networkStatus = networkMonitor.checkCurrentConnectivity()
                        )
                    } else {
                        null
                    }
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            validationGateResult = gateResult,
                            requestError = classifiedErr,
                            analysisError = classifiedErr?.messageEn
                        )
                    }
                    return@launch
                }

                val postGateNet = networkMonitor.checkCurrentConnectivity()
                if (!postGateNet.hasInternetAccess) {
                    val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                        networkStatus = postGateNet,
                        stage = FailedPipelineStage.NOTICE_ANALYSIS
                    )
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            validationGateResult = gateResult,
                            requestError = offlineErr,
                            analysisError = offlineErr.messageEn,
                            networkStatus = postGateNet
                        )
                    }
                    return@launch
                }

                activePipelineStage = FailedPipelineStage.NOTICE_ANALYSIS
                _uiState.update {
                    it.copy(
                        validationGateResult = gateResult,
                        processingStageMessage = "Summarizing notice in simple English & हिंदी..."
                    )
                }

                val result = analysisService.generateNoticeDescription(
                    rawNoticeText = noticeText,
                    extractionMethod = extractionMethod,
                    existingLimitWarning = limitWarning,
                    preValidatedGateResult = gateResult
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
                                analysisError = null,
                                requestError = null,
                                validationGateResult = gateResult
                            )
                        }
                    },
                    onFailure = { err ->
                        AppDiagnosticLogger.error("ANALYSIS", "Text notice analysis failed", err)
                        val gateEx = err as? NoticeValidationGateException
                        val isNonErrorGateBlock = gateEx != null &&
                            gateEx.gateResult.status != NoticeValidationStatus.CLASSIFICATION_ERROR
                        val classifiedErr = if (isNonErrorGateBlock) {
                            null
                        } else {
                            NoticeErrorClassifier.classifyPipelineFailure(
                                stage = FailedPipelineStage.NOTICE_ANALYSIS,
                                rawMessage = err.message ?: "Failed to generate notice description.",
                                throwable = err,
                                networkStatus = networkMonitor.checkCurrentConnectivity()
                            )
                        }
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                processingStageMessage = null,
                                validationGateResult = gateEx?.gateResult ?: state.validationGateResult,
                                requestError = classifiedErr,
                                analysisError = classifiedErr?.messageEn
                            )
                        }
                    }
                )
            } finally {
                activeProcessingJob = null
            }
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
                analysisError = null,
                requestError = null,
                validationGateResult = null
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
                requestError = null,
                validationGateResult = null,
                lastPdfExtractionResult = null
            )
        }
    }

    fun submitSelectedPdf(): Boolean {
        if (isOperationInFlight()) {
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
        val netStatus = networkMonitor.checkCurrentConnectivity()

        if (pdfExtractor == null) {
            val offlineErr = if (!netStatus.hasInternetAccess) {
                NoticeErrorClassifier.buildNoInternetError(
                    networkStatus = netStatus,
                    stage = FailedPipelineStage.NOTICE_CLASSIFICATION
                )
            } else {
                null
            }
            _uiState.update { state ->
                state.copy(
                    currentRoute = NoticeAppRoute.UPLOAD_PDF_CONFIRMATION,
                    backStack = state.backStack + state.currentRoute,
                    pdfValidationError = null,
                    activeConfirmedEntry = savedEntry,
                    recentNotices = updatedRecents,
                    isProcessing = false,
                    processingStageMessage = null,
                    analysisError = offlineErr?.messageEn,
                    requestError = offlineErr,
                    validationGateResult = null,
                    lastPdfExtractionResult = null,
                    networkStatus = netStatus
                )
            }
            return true
        }

        _uiState.update { state ->
            state.copy(
                currentRoute = NoticeAppRoute.UPLOAD_PDF_CONFIRMATION,
                backStack = state.backStack + state.currentRoute,
                pdfValidationError = null,
                activeConfirmedEntry = savedEntry,
                recentNotices = updatedRecents,
                isProcessing = true,
                processingStageMessage = "Reading PDF pages and verifying notice content...",
                analysisError = null,
                requestError = null,
                validationGateResult = null,
                lastPdfExtractionResult = null,
                networkStatus = netStatus
            )
        }

        extractAndAnalyzePdfAsync(savedEntry.id, draft)
        return true
    }

    private fun extractAndAnalyzePdfAsync(
        entryId: String,
        draft: SelectedPdfDraft
    ) {
        val extractor = pdfExtractor ?: return
        activePipelineStage = FailedPipelineStage.DOCUMENT_UPLOAD_OR_READ
        activeProcessingJob = viewModelScope.launch(mainDispatcher) {
            try {
                val currentNet = networkMonitor.checkCurrentConnectivity()
                // Fast Single-Pass Multimodal Path for real Android devices when online and default OCR engine is used:
                val multimodalPrep = if (!extractor.usesCustomOcrEngine && currentNet.hasInternetAccess) {
                    when {
                        draft.inMemoryBytes != null -> extractor.preparePdfBytesForMultimodal(draft.inMemoryBytes)
                        draft.uriString.isNotBlank() -> {
                            val parsedUri = runCatching { Uri.parse(draft.uriString) }.getOrNull()
                            if (parsedUri != null) {
                                extractor.preparePdfForMultimodal(parsedUri, draft.fileSizeBytes)
                            } else null
                        }
                        else -> null
                    }?.getOrNull()
                } else {
                    null
                }

                if (multimodalPrep != null && multimodalPrep.pageBitmaps.isNotEmpty() && multimodalPrep.hasScannedOrImagePages) {
                    try {
                        activePipelineStage = FailedPipelineStage.NOTICE_CLASSIFICATION
                        _uiState.update {
                            it.copy(processingStageMessage = "Verifying PDF pages are an official notice...")
                        }
                        val mmGateResult = analysisService.validateMultimodalNoticeDocument(
                            pageBitmaps = multimodalPrep.pageBitmaps,
                            supplementalText = multimodalPrep.selectableText,
                            extractionMethod = ExtractionSourceMethod.PDF_MULTIMODAL_VISION
                        )
                        if (!mmGateResult.isAllowedForNoticeAnalysis &&
                            mmGateResult.status == NoticeValidationStatus.NOT_A_NOTICE
                        ) {
                            _uiState.update { state ->
                                state.copy(
                                    isProcessing = false,
                                    processingStageMessage = null,
                                    validationGateResult = mmGateResult,
                                    analysisError = null,
                                    requestError = null
                                )
                            }
                            return@launch
                        }

                        if (mmGateResult.isAllowedForNoticeAnalysis) {
                            activePipelineStage = FailedPipelineStage.NOTICE_ANALYSIS
                            _uiState.update {
                                it.copy(
                                    validationGateResult = mmGateResult,
                                    processingStageMessage = "Analyzing notice image pages in one fast pass..."
                                )
                            }
                            val mmResult = analysisService.generateNoticeDescriptionFromMultimodal(
                                pageBitmaps = multimodalPrep.pageBitmaps,
                                supplementalText = multimodalPrep.selectableText,
                                extractionMethod = ExtractionSourceMethod.PDF_MULTIMODAL_VISION,
                                existingLimitWarning = multimodalPrep.limitWarning,
                                preValidatedGateResult = mmGateResult
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
                                        analysisError = null,
                                        requestError = null,
                                        validationGateResult = mmGateResult
                                    )
                                }
                                return@launch
                            }
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

                // Standard Extraction + OCR Pipeline (handles selectable PDFs, scanned PDFs, and unit test PDFs)
                activePipelineStage = FailedPipelineStage.DOCUMENT_UPLOAD_OR_READ
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
                        val netAfterExtract = networkMonitor.checkCurrentConnectivity()

                        if (!netAfterExtract.hasInternetAccess) {
                            val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                                networkStatus = netAfterExtract,
                                stage = FailedPipelineStage.NOTICE_CLASSIFICATION
                            )
                            _uiState.update { state ->
                                state.copy(
                                    activeConfirmedEntry = updatedWithText ?: state.activeConfirmedEntry?.copy(
                                        extractedPdfText = extracted.extractedText
                                    ),
                                    recentNotices = refreshedRecents,
                                    lastPdfExtractionResult = extracted,
                                    isProcessing = false,
                                    processingStageMessage = null,
                                    requestError = offlineErr,
                                    analysisError = offlineErr.messageEn,
                                    networkStatus = netAfterExtract
                                )
                            }
                            return@fold
                        }

                        _uiState.update { state ->
                            state.copy(
                                activeConfirmedEntry = updatedWithText ?: state.activeConfirmedEntry?.copy(
                                    extractedPdfText = extracted.extractedText
                                ),
                                recentNotices = refreshedRecents,
                                lastPdfExtractionResult = extracted,
                                processingStageMessage = "Verifying document is an official notice..."
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
                        val errMsg = err.message ?: "Failed to extract text from the selected PDF."
                        val isBlankScan = errMsg.contains("No readable text", ignoreCase = true)
                        val unreadableGate = if (isBlankScan) {
                            NoticeValidationGate.buildUnreadableResult(detail = errMsg)
                        } else {
                            null
                        }
                        val uploadError = NoticeErrorClassifier.buildUploadFailureError(
                            rawMessage = errMsg,
                            throwable = err
                        )
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                processingStageMessage = null,
                                validationGateResult = unreadableGate,
                                requestError = uploadError,
                                analysisError = uploadError.messageEn
                            )
                        }
                    }
                )
            } finally {
                if (!_uiState.value.isProcessing) {
                    activeProcessingJob = null
                }
            }
        }
    }

    // --- Upload Notice Image (Direct Photo / Screenshot / Camera-Scanned Notice Reading) ---

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
        _uiState.update { state ->
            val cameFromScanner = state.currentRoute == NoticeAppRoute.SCAN_NOTICE_CAMERA
            val nextRoute = if (cameFromScanner) NoticeAppRoute.UPLOAD_IMAGE_SELECT else state.currentRoute
            val nextBackStack = if (cameFromScanner) {
                if (state.backStack.lastOrNull() == NoticeAppRoute.UPLOAD_IMAGE_SELECT) {
                    state.backStack.dropLast(1)
                } else {
                    state.backStack
                }
            } else {
                state.backStack
            }
            val nextCameraState = if (cameFromScanner) {
                cleanupCameraStateForNavigation(
                    currentCamera = state.cameraScanState.copy(capturedPhoto = null),
                    enteringCameraRoute = false
                )
            } else {
                state.cameraScanState
            }
            state.copy(
                currentRoute = nextRoute,
                backStack = nextBackStack,
                selectedImageDraft = SelectedImageDraft(
                    fileName = safeName,
                    fileSizeBytes = fileSizeBytes,
                    mimeType = mimeType ?: "image/jpeg",
                    uriString = uriString,
                    inMemoryBytes = inMemoryBytes
                ),
                imageValidationError = null,
                analysisError = null,
                requestError = null,
                validationGateResult = null,
                cameraScanState = nextCameraState
            )
        }
    }

    fun removeSelectedImage() {
        _uiState.update {
            it.copy(
                selectedImageDraft = null,
                imageValidationError = null,
                analysisError = null,
                requestError = null,
                validationGateResult = null
            )
        }
    }

    // --- Phase 7: Camera-Scanning Workflow State & Lifecycle Management ---

    fun openCameraScanner() {
        navigateTo(NoticeAppRoute.SCAN_NOTICE_CAMERA)
    }

    fun onCameraPermissionChecked(isGranted: Boolean) {
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            if (isGranted) {
                state.copy(
                    cameraScanState = currentCam.copy(
                        permissionState = CameraPermissionState.GRANTED,
                        sessionStage = if (currentCam.capturedPhoto != null) {
                            CameraSessionStage.REVIEWING_CAPTURE
                        } else if (currentCam.isCameraBound) {
                            CameraSessionStage.PREVIEW_READY
                        } else {
                            CameraSessionStage.INITIALIZING
                        }
                    )
                )
            } else if (currentCam.permissionState == CameraPermissionState.GRANTED) {
                state.copy(
                    cameraScanState = currentCam.copy(
                        permissionState = CameraPermissionState.DENIED,
                        isCameraBound = false,
                        isFlashEnabled = false
                    )
                )
            } else {
                state
            }
        }
    }

    fun onCameraPermissionResult(isGranted: Boolean) {
        AppDiagnosticLogger.info("CAMERA", "Camera permission result: granted=$isGranted")
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            if (isGranted) {
                state.copy(
                    cameraScanState = currentCam.copy(
                        permissionState = CameraPermissionState.GRANTED,
                        sessionStage = if (currentCam.capturedPhoto != null) {
                            CameraSessionStage.REVIEWING_CAPTURE
                        } else {
                            CameraSessionStage.INITIALIZING
                        },
                        cameraErrorMessage = null
                    )
                )
            } else {
                state.copy(
                    cameraScanState = currentCam.copy(
                        permissionState = CameraPermissionState.DENIED,
                        permissionDeniedCount = currentCam.permissionDeniedCount + 1,
                        isCameraBound = false,
                        isFlashEnabled = false,
                        cameraErrorMessage = null
                    )
                )
            }
        }
    }

    fun onCameraPreviewInitializing() {
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            if (currentCam.capturedPhoto != null) {
                state
            } else {
                state.copy(
                    cameraScanState = currentCam.copy(
                        sessionStage = CameraSessionStage.INITIALIZING,
                        cameraErrorMessage = null
                    )
                )
            }
        }
    }

    fun onCameraPreviewBound(isFlashSupported: Boolean = true) {
        AppDiagnosticLogger.info("CAMERA", "Camera preview bound (flashSupported=$isFlashSupported)")
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            val resolvedFlashEnabled = if (isFlashSupported) currentCam.isFlashEnabled else false
            val resolvedStage = if (currentCam.capturedPhoto != null) {
                CameraSessionStage.REVIEWING_CAPTURE
            } else {
                CameraSessionStage.PREVIEW_READY
            }
            state.copy(
                cameraScanState = currentCam.copy(
                    permissionState = CameraPermissionState.GRANTED,
                    isCameraBound = true,
                    isFlashSupported = isFlashSupported,
                    isFlashEnabled = resolvedFlashEnabled,
                    sessionStage = resolvedStage,
                    cameraErrorMessage = null
                )
            )
        }
    }

    fun onCameraPreviewUnbound() {
        AppDiagnosticLogger.info("CAMERA", "Camera preview unbound and resources released")
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            state.copy(
                cameraScanState = currentCam.copy(
                    isCameraBound = false,
                    isFlashEnabled = false,
                    detectionState = DocumentDetectionUiState()
                )
            )
        }
    }

    fun onCameraError(errorMessage: String) {
        val cleanMsg = errorMessage.trim().ifBlank { "Camera is currently unavailable on this device." }
        AppDiagnosticLogger.warn("CAMERA", "Camera error: $cleanMsg")
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            state.copy(
                cameraScanState = currentCam.copy(
                    sessionStage = CameraSessionStage.CAMERA_ERROR,
                    isCameraBound = false,
                    isFlashEnabled = false,
                    detectionState = DocumentDetectionUiState(),
                    cameraErrorMessage = cleanMsg
                )
            )
        }
    }

    fun retryCameraInitialization() {
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            state.copy(
                cameraScanState = currentCam.copy(
                    sessionStage = CameraSessionStage.INITIALIZING,
                    detectionState = DocumentDetectionUiState(),
                    cameraErrorMessage = null,
                    bindGeneration = currentCam.bindGeneration + 1
                )
            )
        }
    }

    fun toggleCameraFlash(): Boolean {
        val currentCam = _uiState.value.cameraScanState
        if (!currentCam.isFlashSupported || !currentCam.isCameraBound) {
            _uiState.update { state ->
                state.copy(
                    cameraScanState = state.cameraScanState.copy(isFlashEnabled = false)
                )
            }
            return false
        }
        val nextFlash = !currentCam.isFlashEnabled
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(isFlashEnabled = nextFlash)
            )
        }
        return nextFlash
    }

    /**
     * Phase 8: Toggles intelligent Auto-Capture ON or OFF while preserving manual capture fallback.
     */
    fun toggleAutoCapture(): Boolean {
        val nextEnabled = !_uiState.value.cameraScanState.isAutoCaptureEnabled
        AppDiagnosticLogger.info("CAMERA", "Auto-capture toggled: enabled=$nextEnabled")
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    isAutoCaptureEnabled = nextEnabled
                )
            )
        }
        return nextEnabled
    }

    fun setAutoCaptureEnabled(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    isAutoCaptureEnabled = enabled
                )
            )
        }
    }

    fun resetDocumentDetectionState() {
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    detectionState = DocumentDetectionUiState()
                )
            )
        }
    }

    /**
     * Phase 8: Processes a live camera preview frame analysis result, updates the visible
     * document boundary indicator, quality guidance, and stability progress, and triggers
     * auto-capture ONCE when the document is sufficiently stable and image quality is acceptable.
     *
     * Never triggers repeatedly on the same [DocumentFrameAnalysisResult.frameSequenceId] or
     * while a capture or review is already in progress.
     *
     * @return `true` if this frame triggered an automatic capture, `false` otherwise.
     */
    fun onPreviewFrameAnalyzed(frameResult: DocumentFrameAnalysisResult): Boolean {
        if (isOperationInFlight()) return false
        val currentCam = _uiState.value.cameraScanState
        if (currentCam.permissionState != CameraPermissionState.GRANTED ||
            !currentCam.isCameraBound ||
            currentCam.sessionStage != CameraSessionStage.PREVIEW_READY ||
            currentCam.capturedPhoto != null
        ) {
            return false
        }

        // Guard against duplicate evaluation or repeated auto-capture on the exact same frame ID
        if (frameResult.frameSequenceId > 0L &&
            (frameResult.frameSequenceId == currentCam.lastAutoCapturedFrameId ||
                frameResult.frameSequenceId == currentCam.detectionState.frameSequenceId)
        ) {
            return false
        }

        val nextDetection = NoticeDocumentStabilityTracker.updateDetectionState(
            previous = currentCam.detectionState,
            frame = frameResult
        )

        val shouldAutoCapture = currentCam.isAutoCaptureEnabled &&
            nextDetection.isDocumentDetected &&
            nextDetection.isQualityAcceptable &&
            nextDetection.isStableLocked &&
            frameResult.frameSequenceId != currentCam.lastAutoCapturedFrameId

        _uiState.update { state ->
            val cam = state.cameraScanState
            if (shouldAutoCapture) {
                AppDiagnosticLogger.info(
                    "CAMERA",
                    "Auto-capture triggered on stable frame=${frameResult.frameSequenceId} " +
                        "(sharpness=${frameResult.sharpnessScore}, brightness=${frameResult.meanBrightness})"
                )
                state.copy(
                    cameraScanState = cam.copy(
                        sessionStage = CameraSessionStage.CAPTURING,
                        detectionState = nextDetection,
                        lastAutoCapturedFrameId = frameResult.frameSequenceId,
                        autoCaptureTriggeredCount = cam.autoCaptureTriggeredCount + 1,
                        lastCaptureWasAuto = true,
                        cameraErrorMessage = null
                    )
                )
            } else {
                state.copy(
                    cameraScanState = cam.copy(
                        detectionState = nextDetection
                    )
                )
            }
        }

        return shouldAutoCapture
    }

    /**
     * Phase 8 convenience method: runs [NoticeDocumentEdgeAnalyzer] on a preview [Bitmap]
     * and feeds the result into [onPreviewFrameAnalyzed].
     */
    fun analyzePreviewBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int = 0,
        frameSequenceId: Long = frameSequenceCounter.incrementAndGet()
    ): Boolean {
        val analysis = NoticeDocumentEdgeAnalyzer.analyzeBitmap(
            bitmap = bitmap,
            rotationDegrees = rotationDegrees,
            frameSequenceId = frameSequenceId
        )
        return onPreviewFrameAnalyzed(analysis)
    }

    fun switchCameraLens() {
        _uiState.update { state ->
            val currentCam = state.cameraScanState
            val nextLens = if (currentCam.lensFacing == CameraScanUiState.LENS_FACING_BACK) {
                CameraScanUiState.LENS_FACING_FRONT
            } else {
                CameraScanUiState.LENS_FACING_BACK
            }
            state.copy(
                cameraScanState = currentCam.copy(
                    lensFacing = nextLens,
                    isFlashEnabled = false,
                    isCameraBound = false,
                    sessionStage = CameraSessionStage.INITIALIZING,
                    detectionState = DocumentDetectionUiState(),
                    lastCaptureWasAuto = false,
                    cameraErrorMessage = null,
                    bindGeneration = currentCam.bindGeneration + 1
                )
            )
        }
    }

    fun onDeviceRotationChanged(rotationDegrees: Int) {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        val snapped = when (normalized) {
            in 45..134 -> 90
            in 135..224 -> 180
            in 225..314 -> 270
            else -> 0
        }
        _uiState.update { state ->
            if (state.cameraScanState.deviceRotationDegrees == snapped) {
                state
            } else {
                state.copy(
                    cameraScanState = state.cameraScanState.copy(deviceRotationDegrees = snapped)
                )
            }
        }
    }

    fun onManualCaptureStarted(): Boolean {
        if (isOperationInFlight()) return false
        val currentCam = _uiState.value.cameraScanState
        if (currentCam.permissionState != CameraPermissionState.GRANTED ||
            currentCam.sessionStage == CameraSessionStage.CAPTURING ||
            currentCam.sessionStage == CameraSessionStage.REVIEWING_CAPTURE
        ) {
            return false
        }
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    sessionStage = CameraSessionStage.CAPTURING,
                    lastCaptureWasAuto = false,
                    cameraErrorMessage = null
                )
            )
        }
        return true
    }

    fun onManualCaptureSucceeded(
        bitmap: Bitmap,
        rotationDegrees: Int = 0
    ): Boolean {
        return try {
            val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
            val uprightBitmap = if (normalizedRotation == 0) {
                bitmap
            } else {
                NoticePageImageCorrector.rotateQuarterTurnsLossless(
                    bitmap = bitmap,
                    degrees = normalizedRotation
                )
            }
            val currentCam = _uiState.value.cameraScanState
            val (evaluatedGuidance, evaluatedQuad) = NoticePageImageCorrector.analyzeCapturedBitmap(
                bitmap = uprightBitmap,
                fallbackGuidance = currentCam.detectionState.qualityGuidance,
                fallbackQuad = currentCam.detectionState.detectedQuad
            )
            val pngBytes = ByteArrayOutputStream().use { out ->
                uprightBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
            if (pngBytes.isEmpty()) {
                onManualCaptureFailed("Captured notice photo was empty. Please try capturing again.")
                return false
            }
            val width = uprightBitmap.width
            val height = uprightBitmap.height
            if (uprightBitmap !== bitmap && !uprightBitmap.isRecycled) {
                uprightBitmap.recycle()
            }
            onManualCaptureBytesSucceeded(
                pngBytes = pngBytes,
                widthPx = width,
                heightPx = height,
                rotationDegrees = normalizedRotation,
                fileName = null,
                evaluatedGuidance = evaluatedGuidance,
                evaluatedQuad = evaluatedQuad
            )
        } catch (e: Exception) {
            AppDiagnosticLogger.error("CAMERA", "Failed to process captured bitmap", e)
            onManualCaptureFailed("Could not process captured notice photo: ${e.message ?: "Encoding error"}")
            false
        }
    }

    fun onManualCaptureBytesSucceeded(
        pngBytes: ByteArray,
        widthPx: Int,
        heightPx: Int,
        rotationDegrees: Int = _uiState.value.cameraScanState.deviceRotationDegrees,
        fileName: String? = null,
        evaluatedGuidance: CameraQualityGuidance? = null,
        evaluatedQuad: DetectedDocumentQuad? = null
    ): Boolean {
        if (pngBytes.isEmpty() || widthPx <= 0 || heightPx <= 0) {
            onManualCaptureFailed("Captured notice photo could not be decoded. Please try again.")
            return false
        }
        val currentCam = _uiState.value.cameraScanState
        val timestamp = System.currentTimeMillis()
        val seqNum = pageIdCounter.getAndIncrement()
        val retakeIdx = currentCam.retakingPageId?.let { targetId ->
            currentCam.scannedPages.indexOfFirst { it.pageId == targetId }
        } ?: -1

        val displayPageNumber = if (retakeIdx >= 0) {
            retakeIdx + 1
        } else {
            currentCam.scannedPages.size + 1
        }

        val resolvedName = fileName?.trim().takeUnless { it.isNullOrBlank() }
            ?: "Scanned_Notice_Page_${displayPageNumber}_$timestamp.png"

        val resolvedGuidance = evaluatedGuidance ?: currentCam.detectionState.qualityGuidance
        val resolvedQuad = evaluatedQuad ?: currentCam.detectionState.detectedQuad
        val resolvedPageId = if (retakeIdx >= 0) {
            currentCam.scannedPages[retakeIdx].pageId
        } else {
            "notice_page_${timestamp}_$seqNum"
        }

        val newPage = ScannedNoticePage(
            pageId = resolvedPageId,
            fileName = resolvedName,
            originalPngBytes = pngBytes,
            originalWidthPx = widthPx,
            originalHeightPx = heightPx,
            currentPngBytes = pngBytes,
            widthPx = widthPx,
            heightPx = heightPx,
            baseRotationDegrees = rotationDegrees,
            capturedAtMillis = timestamp,
            wasAutoCaptured = currentCam.lastCaptureWasAuto,
            initialDetectedQuad = resolvedQuad,
            captureQualityGuidance = resolvedGuidance,
            editState = NoticePageEditState(perspectiveQuad = resolvedQuad)
        )

        val updatedPages: List<ScannedNoticePage>
        val nextSelectedIdx: Int
        if (retakeIdx >= 0) {
            val mutable = currentCam.scannedPages.toMutableList()
            mutable[retakeIdx] = newPage
            updatedPages = mutable
            nextSelectedIdx = retakeIdx
        } else {
            updatedPages = currentCam.scannedPages + newPage
            nextSelectedIdx = updatedPages.lastIndex
        }

        val activePhoto = newPage.toCapturedNoticePhoto()
        AppDiagnosticLogger.info(
            "CAMERA",
            "Captured notice page ready for review: ${newPage.fileName} (page ${nextSelectedIdx + 1}/${updatedPages.size}, ${newPage.widthPx}x${newPage.heightPx}, ${newPage.fileSizeBytes} bytes, auto=${newPage.wasAutoCaptured})"
        )
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    sessionStage = CameraSessionStage.REVIEWING_CAPTURE,
                    isCameraBound = false,
                    isFlashEnabled = false,
                    detectionState = DocumentDetectionUiState(),
                    capturedPhoto = activePhoto,
                    scannedPages = updatedPages,
                    selectedPageIndex = nextSelectedIdx,
                    retakingPageId = null,
                    cameraErrorMessage = null
                )
            )
        }
        return true
    }

    fun onManualCaptureFailed(errorMessage: String) {
        val cleanMsg = errorMessage.trim().ifBlank { "Failed to capture notice photo. Please try again." }
        AppDiagnosticLogger.warn("CAMERA", "Manual capture failed: $cleanMsg")
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    sessionStage = CameraSessionStage.CAMERA_ERROR,
                    detectionState = DocumentDetectionUiState(),
                    lastCaptureWasAuto = false,
                    cameraErrorMessage = cleanMsg
                )
            )
        }
    }

    /**
     * Phase 9: Transitions from the Review screen back to the live Camera Preview to scan and
     * append an additional page (`Page N + 1`) while preserving all already-scanned pages.
     */
    fun onStartAddAnotherPage(): Boolean {
        val currentCam = _uiState.value.cameraScanState
        if (currentCam.scannedPages.isEmpty()) return false
        AppDiagnosticLogger.info(
            "CAMERA",
            "User requested to scan another page (current pages=${currentCam.scannedPages.size})"
        )
        _uiState.update { state ->
            val cam = state.cameraScanState
            state.copy(
                cameraScanState = cam.copy(
                    capturedPhoto = null,
                    retakingPageId = null,
                    cameraErrorMessage = null,
                    detectionState = DocumentDetectionUiState(),
                    lastCaptureWasAuto = false,
                    sessionStage = if (cam.isCameraBound) {
                        CameraSessionStage.PREVIEW_READY
                    } else {
                        CameraSessionStage.INITIALIZING
                    },
                    bindGeneration = cam.bindGeneration + 1
                )
            )
        }
        return true
    }

    /**
     * Phase 9: Returns from the live Camera Preview back to the Multi-Page Review screen
     * without requiring a new capture (e.g., if the user tapped "+ Add Page" or "Retake" and
     * decided to keep the existing pages).
     */
    fun onReturnToMultiPageReview(): Boolean {
        val currentCam = _uiState.value.cameraScanState
        val activePage = currentCam.activeScannedPage ?: return false
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    sessionStage = CameraSessionStage.REVIEWING_CAPTURE,
                    isCameraBound = false,
                    isFlashEnabled = false,
                    detectionState = DocumentDetectionUiState(),
                    capturedPhoto = activePage.toCapturedNoticePhoto(),
                    retakingPageId = null,
                    cameraErrorMessage = null
                )
            )
        }
        return true
    }

    /**
     * Phase 9: Selects a specific page (`0..lastIndex`) in the multi-page thumbnail strip for
     * inspection, crop/rotate/perspective editing, OCR check, reordering, or removal.
     */
    fun onSelectScannedPage(index: Int): Boolean {
        val currentCam = _uiState.value.cameraScanState
        if (index !in currentCam.scannedPages.indices) return false
        val targetPage = currentCam.scannedPages[index]
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    selectedPageIndex = index,
                    capturedPhoto = targetPage.toCapturedNoticePhoto()
                )
            )
        }
        return true
    }

    /**
     * Phase 9: Removes the page at [index] (defaults to the currently selected page) without
     * dropping any other pages. If no pages remain, returns to the live camera preview.
     */
    fun onRemoveScannedPage(
        index: Int = _uiState.value.cameraScanState.safeSelectedPageIndex
    ): Boolean {
        val currentCam = _uiState.value.cameraScanState
        if (index !in currentCam.scannedPages.indices) return false
        val remaining = currentCam.scannedPages.toMutableList().apply { removeAt(index) }
        AppDiagnosticLogger.info(
            "CAMERA",
            "Removed scanned page at index=$index; remainingPages=${remaining.size}"
        )
        _uiState.update { state ->
            val cam = state.cameraScanState
            if (remaining.isEmpty()) {
                state.copy(
                    cameraScanState = cam.copy(
                        scannedPages = emptyList(),
                        selectedPageIndex = 0,
                        capturedPhoto = null,
                        retakingPageId = null,
                        detectionState = DocumentDetectionUiState(),
                        lastCaptureWasAuto = false,
                        sessionStage = if (cam.isCameraBound) {
                            CameraSessionStage.PREVIEW_READY
                        } else {
                            CameraSessionStage.INITIALIZING
                        },
                        bindGeneration = cam.bindGeneration + 1
                    )
                )
            } else {
                val nextIdx = index.coerceIn(0, remaining.lastIndex)
                val nextActive = remaining[nextIdx]
                state.copy(
                    cameraScanState = cam.copy(
                        scannedPages = remaining,
                        selectedPageIndex = nextIdx,
                        capturedPhoto = nextActive.toCapturedNoticePhoto(),
                        retakingPageId = null
                    )
                )
            }
        }
        return true
    }

    /**
     * Phase 9: Reorders a scanned page from [fromIndex] to [toIndex], preserving all pages
     * without dropping or duplicating any page.
     */
    fun onReorderScannedPage(fromIndex: Int, toIndex: Int): Boolean {
        val currentCam = _uiState.value.cameraScanState
        val pages = currentCam.scannedPages
        if (fromIndex !in pages.indices || toIndex !in pages.indices || fromIndex == toIndex) {
            return false
        }
        val reordered = pages.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex, moved)
        }
        val activePage = reordered[toIndex]
        AppDiagnosticLogger.info(
            "CAMERA",
            "Reordered scanned page from index=$fromIndex to index=$toIndex"
        )
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    scannedPages = reordered,
                    selectedPageIndex = toIndex,
                    capturedPhoto = activePage.toCapturedNoticePhoto()
                )
            )
        }
        return true
    }

    fun onMoveScannedPageEarlier(
        index: Int = _uiState.value.cameraScanState.safeSelectedPageIndex
    ): Boolean {
        return onReorderScannedPage(fromIndex = index, toIndex = index - 1)
    }

    fun onMoveScannedPageLater(
        index: Int = _uiState.value.cameraScanState.safeSelectedPageIndex
    ): Boolean {
        return onReorderScannedPage(fromIndex = index, toIndex = index + 1)
    }

    /**
     * Retakes the currently selected page:
     * - If only 1 page exists, clears the single capture and returns to the camera preview.
     * - If multiple pages exist, marks the selected page's [ScannedNoticePage.pageId] as
     *   [CameraScanUiState.retakingPageId] so the next capture replaces that page in-place
     *   without losing other pages or altering page order.
     */
    fun onRetakeCapturedPhoto() {
        val currentCam = _uiState.value.cameraScanState
        val isMultiPageRetake = currentCam.scannedPages.size > 1
        val targetRetakeId = if (isMultiPageRetake) {
            currentCam.activeScannedPage?.pageId
        } else {
            null
        }
        AppDiagnosticLogger.info(
            "CAMERA",
            "User requested retake (multiPage=$isMultiPageRetake, retakingPageId=$targetRetakeId); returning to camera preview"
        )
        _uiState.update { state ->
            val cam = state.cameraScanState
            state.copy(
                cameraScanState = cam.copy(
                    capturedPhoto = null,
                    scannedPages = if (isMultiPageRetake) cam.scannedPages else emptyList(),
                    selectedPageIndex = if (isMultiPageRetake) cam.safeSelectedPageIndex else 0,
                    retakingPageId = targetRetakeId,
                    cameraErrorMessage = null,
                    detectionState = DocumentDetectionUiState(),
                    lastCaptureWasAuto = false,
                    sessionStage = if (cam.isCameraBound) {
                        CameraSessionStage.PREVIEW_READY
                    } else {
                        CameraSessionStage.INITIALIZING
                    },
                    bindGeneration = cam.bindGeneration + 1
                )
            )
        }
    }

    // --- Phase 9: Non-Destructive Image Correction Controls (Rotate, Crop, Perspective, Reset) ---

    private fun updateSelectedPageWithEditState(
        transform: (ScannedNoticePage) -> NoticePageEditState
    ): Boolean {
        val currentCam = _uiState.value.cameraScanState
        val idx = currentCam.safeSelectedPageIndex
        val page = currentCam.scannedPages.getOrNull(idx) ?: return false
        val nextEditState = transform(page)
        val correctedPage = NoticePageImageCorrector.applyEditsToPage(
            page = page,
            newEditState = nextEditState
        )
        val updatedList = currentCam.scannedPages.toMutableList().apply {
            this[idx] = correctedPage
        }
        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    scannedPages = updatedList,
                    capturedPhoto = correctedPage.toCapturedNoticePhoto()
                )
            )
        }
        return true
    }

    fun onRotateSelectedPageClockwise(): Boolean {
        return updateSelectedPageWithEditState { page ->
            val nextRot = (page.editState.normalizedRotationDegrees + 90) % 360
            page.editState.copy(extraRotationDegrees = nextRot)
        }
    }

    fun onRotateSelectedPageCounterClockwise(): Boolean {
        return updateSelectedPageWithEditState { page ->
            val nextRot = (page.editState.normalizedRotationDegrees + 270) % 360
            page.editState.copy(extraRotationDegrees = nextRot)
        }
    }

    fun onToggleCropSelectedPage(): Boolean {
        return updateSelectedPageWithEditState { page ->
            val nextCrop = if (page.editState.cropRect.isCropped) {
                CropRectNormalized.FULL_PAGE
            } else {
                NoticePageImageCorrector.computeSmartCropRect(page)
            }
            page.editState.copy(cropRect = nextCrop)
        }
    }

    fun onApplyCropToSelectedPage(cropRect: CropRectNormalized): Boolean {
        return updateSelectedPageWithEditState { page ->
            page.editState.copy(cropRect = NoticePageImageCorrector.sanitizeCropRect(cropRect))
        }
    }

    fun onTogglePerspectiveCorrectionSelectedPage(): Boolean {
        return updateSelectedPageWithEditState { page ->
            page.editState.copy(
                isPerspectiveCorrected = !page.editState.isPerspectiveCorrected,
                perspectiveQuad = page.editState.perspectiveQuad ?: page.initialDetectedQuad
            )
        }
    }

    fun onResetSelectedPageEdits(): Boolean {
        return updateSelectedPageWithEditState { page ->
            NoticePageEditState(perspectiveQuad = page.initialDetectedQuad)
        }
    }

    // --- Phase 9: OCR Readability Check & Quality Guidance During Review ---

    fun onRunOcrCheckOnSelectedPage(): Boolean {
        val currentCam = _uiState.value.cameraScanState
        val idx = currentCam.safeSelectedPageIndex
        val page = currentCam.scannedPages.getOrNull(idx) ?: return false
        val extractor = pdfExtractor
        if (extractor == null) {
            val marked = page.copy(
                ocrStatus = PageOcrPreviewStatus.OCR_FAILED,
                ocrPreviewText = "",
                ocrStatusMessage = "OCR engine is not configured."
            )
            val updated = currentCam.scannedPages.toMutableList().apply { this[idx] = marked }
            _uiState.update { state ->
                state.copy(cameraScanState = state.cameraScanState.copy(scannedPages = updated))
            }
            return false
        }

        viewModelScope.launch(mainDispatcher) {
            runOcrCheckForPageIndices(listOf(idx))
        }
        return true
    }

    fun onRunOcrCheckOnAllPages(): Boolean {
        val currentCam = _uiState.value.cameraScanState
        if (currentCam.scannedPages.isEmpty()) return false
        if (pdfExtractor == null) return false
        viewModelScope.launch(mainDispatcher) {
            runOcrCheckForPageIndices(currentCam.scannedPages.indices.toList())
        }
        return true
    }

    private suspend fun runOcrCheckForPageIndices(indices: List<Int>) {
        val extractor = pdfExtractor ?: return
        _uiState.update { state ->
            val updated = state.cameraScanState.scannedPages.mapIndexed { idx, p ->
                if (idx in indices) p.copy(ocrStatus = PageOcrPreviewStatus.RUNNING, ocrStatusMessage = null) else p
            }
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    scannedPages = updated,
                    isOcrCheckRunning = true
                )
            )
        }

        for (idx in indices) {
            val page = _uiState.value.cameraScanState.scannedPages.getOrNull(idx) ?: continue
            val decodedResult = extractor.decodeNoticeImageFromBytes(page.currentPngBytes)
            val updatedPage = decodedResult.fold(
                onSuccess = { bmp ->
                    try {
                        val ocrRes = extractor.extractTextFromNoticeImageResult(bmp)
                        ocrRes.fold(
                            onSuccess = { extracted ->
                                val trimmed = extracted.trim()
                                when {
                                    trimmed.isBlank() -> page.copy(
                                        ocrStatus = PageOcrPreviewStatus.UNREADABLE,
                                        ocrPreviewText = "",
                                        ocrStatusMessage = "No readable text found on Page ${idx + 1}. Please Retake or check lighting."
                                    )
                                    trimmed.length < 20 || !PdfNoticeExtractor.isSelectableTextUsable(trimmed, 12) -> page.copy(
                                        ocrStatus = PageOcrPreviewStatus.INCOMPLETE_OR_LOW_TEXT,
                                        ocrPreviewText = trimmed,
                                        ocrStatusMessage = "Page ${idx + 1} has very little or incomplete text (${trimmed.length} chars). Check that edges are not cut off."
                                    )
                                    else -> page.copy(
                                        ocrStatus = PageOcrPreviewStatus.READABLE,
                                        ocrPreviewText = trimmed,
                                        ocrStatusMessage = "Page ${idx + 1} text verified (${trimmed.length} chars)."
                                    )
                                }
                            },
                            onFailure = { err ->
                                page.copy(
                                    ocrStatus = PageOcrPreviewStatus.OCR_FAILED,
                                    ocrPreviewText = "",
                                    ocrStatusMessage = err.message ?: "OCR check failed on Page ${idx + 1}."
                                )
                            }
                        )
                    } finally {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                },
                onFailure = { err ->
                    page.copy(
                        ocrStatus = PageOcrPreviewStatus.OCR_FAILED,
                        ocrPreviewText = "",
                        ocrStatusMessage = err.message ?: "Could not decode Page ${idx + 1} for OCR."
                    )
                }
            )

            _uiState.update { state ->
                val list = state.cameraScanState.scannedPages.toMutableList()
                if (idx in list.indices && list[idx].pageId == page.pageId) {
                    list[idx] = updatedPage
                }
                state.copy(
                    cameraScanState = state.cameraScanState.copy(
                        scannedPages = list
                    )
                )
            }
        }

        _uiState.update { state ->
            state.copy(
                cameraScanState = state.cameraScanState.copy(
                    isOcrCheckRunning = false
                )
            )
        }
    }

    /**
     * Accepts the reviewed camera capture(s) — treating 1 or multiple scanned pages as a single
     * ordered notice document without dropping or duplicating pages — and passes the document
     * into the existing NoticeBuddy pipeline (enforcing the Phase 5 notice-only validation gate
     * and Phase 6 network/error handling).
     */
    fun onAcceptCapturedPhoto(): Boolean {
        if (isOperationInFlight()) return false
        val currentCam = _uiState.value.cameraScanState
        val orderedPages = currentCam.scannedPages.distinctBy { it.pageId }.ifEmpty {
            val single = currentCam.capturedPhoto ?: return false
            listOf(
                ScannedNoticePage(
                    pageId = "page_single_${single.capturedAtMillis}",
                    fileName = single.fileName,
                    originalPngBytes = single.pngBytes,
                    originalWidthPx = single.widthPx,
                    originalHeightPx = single.heightPx,
                    currentPngBytes = single.pngBytes,
                    widthPx = single.widthPx,
                    heightPx = single.heightPx,
                    baseRotationDegrees = single.rotationDegrees,
                    capturedAtMillis = single.capturedAtMillis,
                    wasAutoCaptured = single.wasAutoCaptured,
                    captureQualityGuidance = single.captureQualityGuidance
                )
            )
        }

        if (orderedPages.isEmpty()) return false

        val firstPage = orderedPages.first()
        val combinedFileName = if (orderedPages.size == 1) {
            firstPage.fileName
        } else {
            "Scanned_Notice_${orderedPages.size}_Pages_${firstPage.capturedAtMillis}.png"
        }
        val totalSizeBytes = orderedPages.sumOf { it.fileSizeBytes }
        val preExtractedMap = orderedPages.mapIndexedNotNull { index, page ->
            if (page.ocrStatus == PageOcrPreviewStatus.READABLE && page.ocrPreviewText.isNotBlank()) {
                index to page.ocrPreviewText
            } else {
                null
            }
        }.toMap()

        _uiState.update { state ->
            state.copy(
                selectedImageDraft = SelectedImageDraft(
                    fileName = combinedFileName,
                    fileSizeBytes = totalSizeBytes,
                    mimeType = "image/png",
                    uriString = "",
                    inMemoryBytes = firstPage.currentPngBytes,
                    pageBytesList = orderedPages.map { it.currentPngBytes },
                    pageFileNames = orderedPages.map { it.fileName },
                    preExtractedPageTexts = preExtractedMap
                ),
                imageValidationError = null,
                analysisError = null,
                requestError = null,
                validationGateResult = null,
                cameraScanState = state.cameraScanState.copy(
                    capturedPhoto = null,
                    scannedPages = emptyList(),
                    selectedPageIndex = 0,
                    retakingPageId = null,
                    isOcrCheckRunning = false,
                    isCameraBound = false,
                    isFlashEnabled = false,
                    detectionState = DocumentDetectionUiState(),
                    lastCaptureWasAuto = false,
                    sessionStage = CameraSessionStage.INITIALIZING,
                    cameraErrorMessage = null
                )
            )
        }
        return submitSelectedImage()
    }

    fun submitSelectedImage(): Boolean {
        if (isOperationInFlight()) {
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
        val netStatus = networkMonitor.checkCurrentConnectivity()

        if (pdfExtractor == null) {
            val offlineErr = if (!netStatus.hasInternetAccess) {
                NoticeErrorClassifier.buildNoInternetError(
                    networkStatus = netStatus,
                    stage = FailedPipelineStage.NOTICE_CLASSIFICATION
                )
            } else {
                null
            }
            _uiState.update { state ->
                state.copy(
                    currentRoute = NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION,
                    backStack = state.backStack + state.currentRoute,
                    imageValidationError = null,
                    activeConfirmedEntry = savedEntry,
                    recentNotices = updatedRecents,
                    isProcessing = false,
                    processingStageMessage = null,
                    analysisError = offlineErr?.messageEn,
                    requestError = offlineErr,
                    validationGateResult = null,
                    networkStatus = netStatus
                )
            }
            return true
        }

        val pageCount = draft.pageCount.coerceAtLeast(1)
        val initialProgressMsg = if (pageCount > 1) {
            "Reading $pageCount scanned notice pages and verifying content..."
        } else {
            "Reading notice image and verifying content..."
        }

        _uiState.update { state ->
            state.copy(
                currentRoute = NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION,
                backStack = state.backStack + state.currentRoute,
                imageValidationError = null,
                activeConfirmedEntry = savedEntry,
                recentNotices = updatedRecents,
                isProcessing = true,
                processingStageMessage = initialProgressMsg,
                analysisError = null,
                requestError = null,
                validationGateResult = null,
                networkStatus = netStatus
            )
        }

        analyzeImageDraftAsync(savedEntry.id, draft)
        return true
    }

    private fun analyzeImageDraftAsync(
        entryId: String,
        draft: SelectedImageDraft
    ) {
        val extractor = pdfExtractor ?: return
        activePipelineStage = FailedPipelineStage.DOCUMENT_UPLOAD_OR_READ
        activeProcessingJob = viewModelScope.launch(mainDispatcher) {
            val decodedBitmaps = mutableListOf<Bitmap>()
            try {
                val pageBytesList = draft.effectivePageBytesList()
                val decodeFailure: Throwable? = if (pageBytesList.isNotEmpty()) {
                    var err: Throwable? = null
                    for ((idx, pageBytes) in pageBytesList.withIndex()) {
                        val res = extractor.decodeNoticeImageFromBytes(pageBytes)
                        if (res.isSuccess) {
                            decodedBitmaps.add(res.getOrThrow())
                        } else {
                            err = res.exceptionOrNull()
                                ?: IllegalArgumentException("Could not decode scanned notice page ${idx + 1}.")
                            break
                        }
                    }
                    err
                } else if (draft.uriString.isNotBlank()) {
                    val uri = runCatching { Uri.parse(draft.uriString) }.getOrNull()
                    if (uri == null) {
                        IllegalArgumentException("Invalid image URI.")
                    } else {
                        val res = extractor.decodeNoticeImageFromUri(uri, draft.fileSizeBytes)
                        if (res.isSuccess) {
                            decodedBitmaps.add(res.getOrThrow())
                            null
                        } else {
                            res.exceptionOrNull() ?: IllegalArgumentException("Could not decode image from URI.")
                        }
                    }
                } else {
                    IllegalArgumentException("No readable image provided.")
                }

                if (decodeFailure != null || decodedBitmaps.isEmpty()) {
                    val uploadErr = NoticeErrorClassifier.buildUploadFailureError(
                        rawMessage = decodeFailure?.message ?: "Could not open the selected image.",
                        throwable = decodeFailure
                    )
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            requestError = uploadErr,
                            analysisError = uploadErr.messageEn
                        )
                    }
                    return@launch
                }

                val totalPages = decodedBitmaps.size
                _uiState.update {
                    it.copy(
                        processingStageMessage = if (totalPages > 1) {
                            "Extracting text from $totalPages notice pages in order..."
                        } else {
                            "Extracting text from image and verifying notice content..."
                        }
                    )
                }

                // Step 1: Extract readable text per page in exact page order when custom OCR is active or pre-extracted
                val perPageTexts = mutableListOf<String>()
                val unreadablePageNumbers = mutableListOf<Int>()
                var lastOcrException: Throwable? = null

                for ((pageIdx, pageBitmap) in decodedBitmaps.withIndex()) {
                    val preExtracted = draft.preExtractedPageTexts[pageIdx]?.trim().orEmpty()
                    if (preExtracted.isNotBlank()) {
                        perPageTexts.add(preExtracted)
                    } else if (extractor.usesCustomOcrEngine) {
                        val ocrRes = extractor.extractTextFromNoticeImageResult(pageBitmap)
                        ocrRes.fold(
                            onSuccess = { text ->
                                val cleaned = text.trim()
                                if (cleaned.isNotBlank()) {
                                    perPageTexts.add(cleaned)
                                } else {
                                    perPageTexts.add("")
                                    unreadablePageNumbers.add(pageIdx + 1)
                                }
                            },
                            onFailure = { ocrErr ->
                                lastOcrException = ocrErr
                                perPageTexts.add("")
                                unreadablePageNumbers.add(pageIdx + 1)
                            }
                        )
                    } else {
                        perPageTexts.add("")
                    }
                }

                val combinedOcrText = if (totalPages == 1) {
                    perPageTexts.firstOrNull().orEmpty().trim()
                } else {
                    perPageTexts.mapIndexedNotNull { idx, pageText ->
                        val trimmed = pageText.trim()
                        if (trimmed.isNotBlank()) {
                            "[Page ${idx + 1}]\n$trimmed"
                        } else {
                            null
                        }
                    }.joinToString("\n\n--- Page Break ---\n\n").trim()
                }

                val multiPageWarning = if (totalPages > 1 && unreadablePageNumbers.isNotEmpty() && combinedOcrText.isNotBlank()) {
                    val pageLabel = unreadablePageNumbers.joinToString(", ") { "Page $it" }
                    "Incomplete page warning: $pageLabel had no readable text ($totalPages pages total) and may be blurry or incomplete. Please check the original notice for those pages."
                } else {
                    null
                }

                val imageExtractionResult = PdfExtractionResult(
                    extractedText = combinedOcrText,
                    method = ExtractionSourceMethod.IMAGE_NOTICE,
                    totalPageCount = totalPages,
                    processedPageCount = totalPages,
                    ocrPageCount = (totalPages - unreadablePageNumbers.size).coerceAtLeast(0),
                    emptyPageCount = unreadablePageNumbers.size,
                    rotatedPageCount = 0,
                    limitWarning = multiPageWarning
                )

                val updatedWithText = if (combinedOcrText.isNotBlank()) {
                    storage.attachExtractedPdfText(entryId, combinedOcrText)
                } else {
                    null
                }
                val refreshedAfterOcr = storage.loadRecentNotices()
                _uiState.update { state ->
                    state.copy(
                        activeConfirmedEntry = updatedWithText ?: state.activeConfirmedEntry?.let { entry ->
                            if (combinedOcrText.isNotBlank()) entry.copy(extractedPdfText = combinedOcrText) else entry
                        },
                        recentNotices = refreshedAfterOcr,
                        lastPdfExtractionResult = imageExtractionResult
                    )
                }

                val netStatus = networkMonitor.checkCurrentConnectivity()
                if (!netStatus.hasInternetAccess) {
                    val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                        networkStatus = netStatus,
                        stage = FailedPipelineStage.NOTICE_CLASSIFICATION
                    )
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            requestError = offlineErr,
                            analysisError = offlineErr.messageEn,
                            networkStatus = netStatus
                        )
                    }
                    return@launch
                }

                activePipelineStage = FailedPipelineStage.NOTICE_CLASSIFICATION
                // Step 2: Enforce the Phase 5 validation gate on the combined single-page or multi-page document
                val gateResult = when {
                    combinedOcrText.isNotBlank() -> analysisService.validateNoticeDocument(
                        rawDocumentText = combinedOcrText,
                        extractionMethod = ExtractionSourceMethod.IMAGE_NOTICE
                    )
                    extractor.usesCustomOcrEngine -> {
                        val detailMsg = lastOcrException?.message?.takeIf { it.isNotBlank() }
                            ?: "No readable text could be extracted from the scanned image."
                        NoticeValidationGate.buildUnreadableResult(detail = detailMsg)
                    }
                    else -> analysisService.validateMultimodalNoticeDocument(
                        pageBitmaps = decodedBitmaps,
                        supplementalText = "",
                        extractionMethod = ExtractionSourceMethod.IMAGE_NOTICE
                    )
                }

                if (!gateResult.isAllowedForNoticeAnalysis) {
                    AppDiagnosticLogger.info(
                        "GATE",
                        "Image document blocked before notice summarization: status=${gateResult.status}, category=${gateResult.category}"
                    )
                    val isClassifierErr = gateResult.status == NoticeValidationStatus.CLASSIFICATION_ERROR
                    val classifiedErr = if (isClassifierErr) {
                        NoticeErrorClassifier.classifyPipelineFailure(
                            stage = FailedPipelineStage.NOTICE_CLASSIFICATION,
                            rawMessage = gateResult.userMessageEn,
                            networkStatus = networkMonitor.checkCurrentConnectivity()
                        )
                    } else if (lastOcrException != null && gateResult.status == NoticeValidationStatus.UNREADABLE_DOCUMENT) {
                        NoticeErrorClassifier.buildUploadFailureError(
                            rawMessage = lastOcrException?.message ?: "OCR text extraction failed on the scanned page(s).",
                            throwable = lastOcrException
                        )
                    } else {
                        null
                    }
                    _uiState.update { state ->
                        state.copy(
                            isProcessing = false,
                            processingStageMessage = null,
                            validationGateResult = gateResult,
                            requestError = classifiedErr,
                            analysisError = classifiedErr?.messageEn
                        )
                    }
                    return@launch
                }

                activePipelineStage = FailedPipelineStage.NOTICE_ANALYSIS
                _uiState.update {
                    it.copy(
                        validationGateResult = gateResult,
                        processingStageMessage = if (totalPages > 1) {
                            "Summarizing $totalPages-page notice in simple English & हिंदी..."
                        } else {
                            "Summarizing notice image in simple English & हिंदी..."
                        }
                    )
                }

                val analysisResult = analysisService.generateNoticeDescriptionFromMultimodal(
                    pageBitmaps = decodedBitmaps,
                    supplementalText = combinedOcrText,
                    extractionMethod = ExtractionSourceMethod.IMAGE_NOTICE,
                    existingLimitWarning = multiPageWarning,
                    preValidatedGateResult = gateResult
                )
                analysisResult.fold(
                    onSuccess = { rawDescription ->
                        val description = if (multiPageWarning != null &&
                            rawDescription.missingOrAmbiguousInfo.none { it.contains("had no readable text") }
                        ) {
                            rawDescription.copy(
                                missingOrAmbiguousInfo = rawDescription.missingOrAmbiguousInfo + multiPageWarning,
                                limitWarning = rawDescription.limitWarning ?: multiPageWarning
                            )
                        } else {
                            rawDescription
                        }
                        val savedExtractedText = combinedOcrText.ifBlank { description.summary }
                        val updatedEntry = storage.attachDescriptionToEntry(
                            entryId = entryId,
                            description = description,
                            extractedPdfText = savedExtractedText
                        )
                        val refreshedRecents = storage.loadRecentNotices()
                        _uiState.update { state ->
                            state.copy(
                                activeConfirmedEntry = updatedEntry ?: state.activeConfirmedEntry?.copy(
                                    descriptionResult = description
                                ),
                                recentNotices = refreshedRecents,
                                lastPdfExtractionResult = imageExtractionResult,
                                isProcessing = false,
                                processingStageMessage = null,
                                analysisError = null,
                                requestError = null,
                                validationGateResult = gateResult
                            )
                        }
                    },
                    onFailure = { err ->
                        val gateEx = err as? NoticeValidationGateException
                        val isNonErrorGateBlock = gateEx != null &&
                            gateEx.gateResult.status != NoticeValidationStatus.CLASSIFICATION_ERROR
                        val classifiedErr = if (isNonErrorGateBlock) {
                            null
                        } else {
                            NoticeErrorClassifier.classifyPipelineFailure(
                                stage = FailedPipelineStage.NOTICE_ANALYSIS,
                                rawMessage = err.message ?: "Failed to read notice image.",
                                throwable = err,
                                networkStatus = networkMonitor.checkCurrentConnectivity()
                            )
                        }
                        _uiState.update { state ->
                            state.copy(
                                isProcessing = false,
                                processingStageMessage = null,
                                validationGateResult = gateEx?.gateResult ?: state.validationGateResult,
                                requestError = classifiedErr,
                                analysisError = classifiedErr?.messageEn
                            )
                        }
                    }
                )
            } finally {
                decodedBitmaps.forEach { bmp ->
                    if (!bmp.isRecycled) {
                        bmp.recycle()
                    }
                }
                activeProcessingJob = null
            }
        }
    }

    /**
     * Safely retries the last failed upload, classification, or AI analysis operation while
     * preventing duplicate in-flight submissions and preserving selected documents, extracted text,
     * and any existing results.
     */
    fun retryCurrentNoticeAnalysis(): Boolean {
        if (isOperationInFlight()) return false
        val currentState = _uiState.value
        val activeEntry = currentState.activeConfirmedEntry ?: return false
        val currentNet = networkMonitor.checkCurrentConnectivity()
        val preservedValidGate = currentState.validationGateResult
            ?.takeIf { it.isAllowedForNoticeAnalysis }

        when (activeEntry.inputType) {
            NoticeInputType.TEXT -> {
                val textToAnalyze = activeEntry.textContent.ifBlank { currentState.draftNoticeText }
                if (textToAnalyze.isBlank()) return false
                if (!currentNet.hasInternetAccess) {
                    val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                        networkStatus = currentNet,
                        stage = if (preservedValidGate != null) {
                            FailedPipelineStage.NOTICE_ANALYSIS
                        } else {
                            FailedPipelineStage.NOTICE_CLASSIFICATION
                        }
                    )
                    _uiState.update {
                        it.copy(
                            networkStatus = currentNet,
                            requestError = offlineErr,
                            analysisError = offlineErr.messageEn
                        )
                    }
                    return false
                }
                _uiState.update {
                    it.copy(
                        isProcessing = true,
                        processingStageMessage = "Retrying notice analysis...",
                        analysisError = null,
                        requestError = null,
                        validationGateResult = preservedValidGate,
                        networkStatus = currentNet
                    )
                }
                analyzeTextEntryAsync(
                    entryId = activeEntry.id,
                    noticeText = textToAnalyze,
                    extractionMethod = ExtractionSourceMethod.DIRECT_TEXT,
                    limitWarning = null,
                    preValidatedGate = preservedValidGate
                )
                return true
            }

            NoticeInputType.PDF -> {
                if (activeEntry.extractedPdfText.isNotBlank()) {
                    if (!currentNet.hasInternetAccess) {
                        val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                            networkStatus = currentNet,
                            stage = if (preservedValidGate != null) {
                                FailedPipelineStage.NOTICE_ANALYSIS
                            } else {
                                FailedPipelineStage.NOTICE_CLASSIFICATION
                            }
                        )
                        _uiState.update {
                            it.copy(
                                networkStatus = currentNet,
                                requestError = offlineErr,
                                analysisError = offlineErr.messageEn
                            )
                        }
                        return false
                    }
                    val lastMethod = currentState.lastPdfExtractionResult?.method
                        ?: ExtractionSourceMethod.PDF_SELECTABLE_TEXT
                    val lastWarn = currentState.lastPdfExtractionResult?.limitWarning
                    _uiState.update {
                        it.copy(
                            isProcessing = true,
                            processingStageMessage = "Retrying notice summary...",
                            analysisError = null,
                            requestError = null,
                            validationGateResult = preservedValidGate,
                            networkStatus = currentNet
                        )
                    }
                    analyzeTextEntryAsync(
                        entryId = activeEntry.id,
                        noticeText = activeEntry.extractedPdfText,
                        extractionMethod = lastMethod,
                        limitWarning = lastWarn,
                        extractedPdfTextToSave = activeEntry.extractedPdfText,
                        preValidatedGate = preservedValidGate
                    )
                    return true
                } else {
                    if (pdfExtractor == null && !currentNet.hasInternetAccess) {
                        val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                            networkStatus = currentNet,
                            stage = FailedPipelineStage.NOTICE_CLASSIFICATION
                        )
                        _uiState.update {
                            it.copy(
                                networkStatus = currentNet,
                                requestError = offlineErr,
                                analysisError = offlineErr.messageEn
                            )
                        }
                        return false
                    }
                    val draft = currentState.selectedPdfDraft ?: SelectedPdfDraft(
                        fileName = activeEntry.pdfFileName.ifBlank { activeEntry.title },
                        fileSizeBytes = activeEntry.pdfFileSizeBytes,
                        uriString = activeEntry.pdfUriString
                    )
                    _uiState.update {
                        it.copy(
                            isProcessing = pdfExtractor != null,
                            processingStageMessage = if (pdfExtractor != null) "Retrying PDF reading and analysis..." else null,
                            analysisError = null,
                            requestError = null,
                            validationGateResult = null,
                            networkStatus = currentNet
                        )
                    }
                    extractAndAnalyzePdfAsync(activeEntry.id, draft)
                    return true
                }
            }

            NoticeInputType.IMAGE -> {
                if (pdfExtractor == null && !currentNet.hasInternetAccess) {
                    val offlineErr = NoticeErrorClassifier.buildNoInternetError(
                        networkStatus = currentNet,
                        stage = FailedPipelineStage.NOTICE_CLASSIFICATION
                    )
                    _uiState.update {
                        it.copy(
                            networkStatus = currentNet,
                            requestError = offlineErr,
                            analysisError = offlineErr.messageEn
                        )
                    }
                    return false
                }
                val draft = currentState.selectedImageDraft ?: SelectedImageDraft(
                    fileName = activeEntry.pdfFileName.ifBlank { activeEntry.title },
                    fileSizeBytes = activeEntry.pdfFileSizeBytes,
                    uriString = activeEntry.pdfUriString
                )
                _uiState.update {
                    it.copy(
                        isProcessing = pdfExtractor != null,
                        processingStageMessage = if (pdfExtractor != null) "Retrying notice image reading..." else null,
                        analysisError = null,
                        requestError = null,
                        validationGateResult = null,
                        networkStatus = currentNet
                    )
                }
                analyzeImageDraftAsync(activeEntry.id, draft)
                return true
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
                        analysisError = null,
                        requestError = null,
                        validationGateResult = null
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
                        analysisError = null,
                        requestError = null,
                        validationGateResult = null
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
                        analysisError = null,
                        requestError = null,
                        validationGateResult = null
                    )
                }
            }
        }
    }

    companion object {
        const val DISPLAY_MODEL_NAME = "Gemma 4 31B"
    }
}
