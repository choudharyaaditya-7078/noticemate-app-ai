package com.example.ui.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BilingualDisplayMode
import com.example.data.model.GroundedQaTurn
import com.example.data.model.NoticeDocument
import com.example.data.model.SampleNoticeCase
import com.example.data.model.SampleNotices
import com.example.data.remote.FastRouterService
import com.example.data.repository.NoticeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface NoticesUiState {
    data object Loading : NoticesUiState
    data class Success(val notices: List<NoticeDocument>) : NoticesUiState
    data class Error(val message: String) : NoticesUiState
}

data class NoticeWorkspaceState(
    val activePipelineStep: Int = 5,
    val rawInputText: String = SampleNotices.ALL.first().rawNoticeText,
    val attachedBitmap: Bitmap? = null,
    val activeNotice: NoticeDocument = SampleNotices.ALL.first().prebuiltDocument,
    val selectedQuoteHighlight: String? = SampleNotices.ALL.first().prebuiltDocument.purposeQuote,
    val bilingualMode: BilingualDisplayMode = BilingualDisplayMode.SIDE_BY_SIDE,
    val isAnalyzing: Boolean = false,
    val isExtractingOcr: Boolean = false,
    val isSaving: Boolean = false,
    val isAskingQa: Boolean = false,
    val isFastRouterKeyConfigured: Boolean = false,
    val statusMessage: String? = null
)

class NoticeMateViewModel(
    private val repository: NoticeRepository,
    private val fastRouterService: FastRouterService,
    private val currentUserId: String
) : ViewModel() {

    val savedNoticesState: StateFlow<NoticesUiState> = repository.observeNotices(currentUserId)
        .map<List<NoticeDocument>, NoticesUiState> { NoticesUiState.Success(it) }
        .catch { error ->
            Log.w(TAG, "Error observing saved notices", error)
            emit(NoticesUiState.Error(error.message ?: "Failed to load saved notices"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = NoticesUiState.Loading
        )

    private val _workspaceState = MutableStateFlow(
        NoticeWorkspaceState(
            isFastRouterKeyConfigured = fastRouterService.isApiKeyConfigured()
        )
    )
    val workspaceState: StateFlow<NoticeWorkspaceState> = _workspaceState.asStateFlow()

    fun updateRawInputText(newText: String) {
        _workspaceState.update {
            it.copy(
                rawInputText = newText,
                activePipelineStep = 2,
                statusMessage = null
            )
        }
    }

    fun selectPipelineStep(step: Int) {
        _workspaceState.update {
            it.copy(activePipelineStep = step.coerceIn(1, 5))
        }
    }

    fun setBilingualDisplayMode(mode: BilingualDisplayMode) {
        _workspaceState.update { it.copy(bilingualMode = mode) }
    }

    fun inspectEvidenceQuote(quote: String?) {
        _workspaceState.update {
            it.copy(selectedQuoteHighlight = quote?.takeIf { q -> q.isNotBlank() })
        }
    }

    fun clearStatusMessage() {
        _workspaceState.update { it.copy(statusMessage = null) }
    }

    fun onNoticeImageSelected(bitmap: Bitmap) {
        viewModelScope.launch {
            _workspaceState.update {
                it.copy(
                    attachedBitmap = bitmap,
                    activePipelineStep = 2,
                    isExtractingOcr = true,
                    statusMessage = "Step 2: Extracting text from notice image..."
                )
            }
            val currentText = _workspaceState.value.rawInputText
            val ocrResult = fastRouterService.extractTextFromImageWithGemma4(bitmap, currentText)
            val extractedText = ocrResult.getOrDefault(currentText)
            _workspaceState.update {
                it.copy(
                    rawInputText = extractedText,
                    isExtractingOcr = false,
                    statusMessage = "OCR extraction complete. Running Gemma 4 grounding & gap validation..."
                )
            }
            analyzeCurrentNotice()
        }
    }

    fun clearAttachedImage() {
        _workspaceState.update { it.copy(attachedBitmap = null) }
    }

    fun loadSampleNotice(sample: SampleNoticeCase) {
        _workspaceState.update {
            it.copy(
                activePipelineStep = 5,
                rawInputText = sample.rawNoticeText,
                attachedBitmap = null,
                activeNotice = sample.prebuiltDocument,
                selectedQuoteHighlight = sample.prebuiltDocument.purposeQuote,
                statusMessage = "Loaded '${sample.title}' into 5-step pipeline."
            )
        }
    }

    fun analyzeCurrentNotice() {
        val rawText = _workspaceState.value.rawInputText.trim()
        if (rawText.isBlank()) {
            _workspaceState.update {
                it.copy(statusMessage = "Please enter or scan a notice first.")
            }
            return
        }

        viewModelScope.launch {
            _workspaceState.update {
                it.copy(
                    isAnalyzing = true,
                    activePipelineStep = 3,
                    statusMessage = "Step 3 & 4: Running Gemma 4 interpretation & evidence verification..."
                )
            }
            val bitmap = _workspaceState.value.attachedBitmap
            val result = fastRouterService.analyzeNoticeWithGemma4(rawText, bitmap)
            result.fold(
                onSuccess = { analyzedDoc ->
                    _workspaceState.update {
                        it.copy(
                            isAnalyzing = false,
                            activePipelineStep = 5,
                            activeNotice = analyzedDoc,
                            selectedQuoteHighlight = analyzedDoc.purposeQuote.ifBlank { analyzedDoc.deadlineQuote },
                            statusMessage = "Analysis verified (${analyzedDoc.groundingScore}% evidence grounded)."
                        )
                    }
                },
                onFailure = { error ->
                    _workspaceState.update {
                        it.copy(
                            isAnalyzing = false,
                            statusMessage = error.localizedMessage ?: "Analysis failed"
                        )
                    }
                }
            )
        }
    }

    fun toggleChecklistTask(taskIndex: Int) {
        val currentDoc = _workspaceState.value.activeNotice
        val currentSet = currentDoc.completedIndices.map { it.toInt() }.toMutableSet()
        if (taskIndex in currentSet) {
            currentSet.remove(taskIndex)
        } else {
            currentSet.add(taskIndex)
        }
        val updatedIndices = currentSet.sorted().map { it.toLong() }
        val updatedDoc = currentDoc.copy(completedIndices = updatedIndices)

        _workspaceState.update {
            it.copy(activeNotice = updatedDoc)
        }

        if (updatedDoc.id.isNotBlank()) {
            viewModelScope.launch {
                repository.updateChecklistCompletion(updatedDoc.id, updatedIndices)
            }
        }
    }

    fun saveActiveNoticeToCloud() {
        val currentDoc = _workspaceState.value.activeNotice
        viewModelScope.launch {
            _workspaceState.update { it.copy(isSaving = true, statusMessage = null) }
            val result = repository.saveNotice(currentDoc)
            result.fold(
                onSuccess = { savedId ->
                    _workspaceState.update {
                        it.copy(
                            isSaving = false,
                            activeNotice = currentDoc.copy(id = savedId, userId = currentUserId),
                            statusMessage = "Notice & bilingual checklist synced to cloud!"
                        )
                    }
                },
                onFailure = { err ->
                    _workspaceState.update {
                        it.copy(
                            isSaving = false,
                            statusMessage = "Could not save notice: ${err.localizedMessage}"
                        )
                    }
                }
            )
        }
    }

    fun askGroundedQuestion(question: String) {
        val cleanQuestion = question.trim()
        if (cleanQuestion.isBlank()) return
        val currentDoc = _workspaceState.value.activeNotice

        viewModelScope.launch {
            _workspaceState.update { it.copy(isAskingQa = true) }
            val result = fastRouterService.askGroundedQuestionWithGemma4(
                rawNoticeText = currentDoc.rawNoticeText,
                question = cleanQuestion
            )
            result.fold(
                onSuccess = { qaTurn: GroundedQaTurn ->
                    val newEntries = (currentDoc.qaEntries + qaTurn.serialize()).takeLast(50)
                    val updatedDoc = currentDoc.copy(qaEntries = newEntries)
                    _workspaceState.update {
                        it.copy(
                            isAskingQa = false,
                            activeNotice = updatedDoc,
                            selectedQuoteHighlight = qaTurn.supportingQuote.takeIf { q -> q.isNotBlank() }
                                ?: it.selectedQuoteHighlight
                        )
                    }
                    if (updatedDoc.id.isNotBlank()) {
                        repository.updateQaEntries(updatedDoc.id, newEntries)
                    }
                },
                onFailure = { err ->
                    _workspaceState.update {
                        it.copy(
                            isAskingQa = false,
                            statusMessage = "Q&A error: ${err.localizedMessage}"
                        )
                    }
                }
            )
        }
    }

    fun openSavedNotice(notice: NoticeDocument) {
        _workspaceState.update {
            it.copy(
                activePipelineStep = 5,
                rawInputText = notice.rawNoticeText,
                attachedBitmap = null,
                activeNotice = notice,
                selectedQuoteHighlight = notice.purposeQuote,
                statusMessage = "Opened saved notice '${notice.title}'."
            )
        }
    }

    fun deleteSavedNotice(noticeId: String) {
        viewModelScope.launch {
            repository.deleteNotice(noticeId)
            if (_workspaceState.value.activeNotice.id == noticeId) {
                _workspaceState.update {
                    it.copy(
                        activeNotice = it.activeNotice.copy(id = ""),
                        statusMessage = "Removed notice from cloud storage."
                    )
                }
            }
        }
    }

    private companion object {
        const val TAG = "NoticeMateVM"
        const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
