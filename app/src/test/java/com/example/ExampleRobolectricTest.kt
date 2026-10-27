package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppThemeMode
import com.example.data.local.RecentNoticeStorage
import com.example.data.model.CameraPermissionState
import com.example.data.model.CameraQualityGuidance
import com.example.data.model.CameraScanUiState
import com.example.data.model.CameraSessionStage
import com.example.data.model.CropRectNormalized
import com.example.data.model.DetectedDocumentCategory
import com.example.data.model.ExtractionSourceMethod
import com.example.data.model.NoticeDescriptionResult
import com.example.data.model.NoticeLanguage
import com.example.data.model.NoticeRequestErrorCategory
import com.example.data.model.NoticeValidationStatus
import com.example.data.model.PageOcrPreviewStatus
import com.example.data.model.PdfExtractionConfig
import com.example.data.pdf.PdfNoticeExtractor
import com.example.data.pdf.PdfOcrEngine
import com.example.data.remote.ControllableNetworkConnectivityMonitor
import com.example.data.remote.HttpNoticeAiGateway
import com.example.data.remote.NetworkConnectivityStatus
import com.example.data.remote.NoticeAiGateway
import com.example.data.remote.NoticeAnalysisException
import com.example.data.remote.NoticeAnalysisService
import com.example.data.remote.NoticeValidationGate
import com.example.data.remote.NoticeValidationGateException
import com.example.ui.camera.NoticeDocumentEdgeAnalyzer
import com.example.ui.screens.NoticeMateHomeApp
import com.example.ui.viewmodel.HomePhaseViewModel
import com.example.ui.viewmodel.NoticeAppRoute
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.SocketTimeoutException
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var application: Application
    private lateinit var storage: RecentNoticeStorage

    private class FakeSuccessAiGateway(
        private val jsonResponse: String = DEFAULT_GROUNDED_JSON
    ) : NoticeAiGateway {
        override fun isConfigured(): Boolean = true

        override suspend fun completeNoticeAnalysisPrompt(
            systemPrompt: String,
            userPrompt: String
        ): Result<String> = Result.success(jsonResponse)

        override suspend fun completeMultimodalNoticeAnalysisPrompt(
            systemPrompt: String,
            userPrompt: String,
            bitmaps: List<Bitmap>
        ): Result<String> = Result.success(jsonResponse)
    }

    private class FakeFailingAiGateway(
        private val errorMessage: String = "The notice analysis request timed out. Please check your network connection and try again."
    ) : NoticeAiGateway {
        override fun isConfigured(): Boolean = true

        override suspend fun completeNoticeAnalysisPrompt(
            systemPrompt: String,
            userPrompt: String
        ): Result<String> = Result.failure(NoticeAnalysisException(errorMessage))

        override suspend fun completeMultimodalNoticeAnalysisPrompt(
            systemPrompt: String,
            userPrompt: String,
            bitmaps: List<Bitmap>
        ): Result<String> = Result.failure(NoticeAnalysisException(errorMessage))
    }

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences(RecentNoticeStorage.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        storage = RecentNoticeStorage(application)
    }

    @Test
    fun `verify initial homepage state and recent notices route navigation`() {
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )
        val state = viewModel.uiState.value
        assertEquals(NoticeAppRoute.HOME, state.currentRoute)
        assertTrue(state.recentNotices.isEmpty())
        assertEquals(AppThemeMode.NIGHT, state.themeMode)
        assertEquals(NoticeLanguage.ENGLISH, state.selectedLanguage)
        assertFalse(state.isProfileMenuOpen)

        // Navigate to dedicated Recent Notices screen from Home button
        viewModel.navigateTo(NoticeAppRoute.RECENT_NOTICES)
        assertEquals(NoticeAppRoute.RECENT_NOTICES, viewModel.uiState.value.currentRoute)
        viewModel.navigateBack()
        assertEquals(NoticeAppRoute.HOME, viewModel.uiState.value.currentRoute)
    }

    @Test
    fun `saved notice deletion and english hindi language toggle work reliably`() {
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        viewModel.updateDraftNoticeText("First Notice: Scholarship Form Deadline 30 Oct 2026.")
        assertTrue(viewModel.submitNoticeText())
        assertEquals(1, viewModel.uiState.value.recentNotices.size)
        val firstId = viewModel.uiState.value.recentNotices.first().id

        // Verify English / Hindi toggle on result
        val desc = viewModel.uiState.value.activeConfirmedEntry?.descriptionResult
        assertNotNull(desc)
        assertEquals(
            "Final Semester Project Viva scheduled for Computer Science students on 18 November 2025.",
            desc?.displaySummary(NoticeLanguage.ENGLISH)
        )
        viewModel.toggleNoticeLanguage()
        assertEquals(NoticeLanguage.HINDI, viewModel.uiState.value.selectedLanguage)
        assertEquals(
            "कंप्यूटर साइंस के छात्रों के लिए 18 नवंबर 2025 को प्रोजेक्ट वाइवा निर्धारित है।",
            desc?.displaySummary(NoticeLanguage.HINDI)
        )

        // Add second notice
        viewModel.onHomeButtonClicked()
        viewModel.updateDraftNoticeText("Second Notice: Hostel Fee Submission by 15 Nov 2026.")
        assertTrue(viewModel.submitNoticeText())
        assertEquals(2, viewModel.uiState.value.recentNotices.size)

        // Delete first notice individually
        viewModel.deleteSavedNotice(firstId)
        assertEquals(1, viewModel.uiState.value.recentNotices.size)
        assertFalse(viewModel.uiState.value.recentNotices.any { it.id == firstId })

        // Clear all remaining notices
        viewModel.clearAllSavedNotices()
        assertTrue(viewModel.uiState.value.recentNotices.isEmpty())
    }

    @Test
    fun `upload notice image decodes bitmap and performs single pass multimodal analysis`() {
        val extractor = PdfNoticeExtractor(
            context = application,
            ioDispatcher = Dispatchers.Unconfined
        )
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        // Create a valid PNG image in memory
        val sampleBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val pngBytes = ByteArrayOutputStream().use { out ->
            sampleBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_SELECT)
        viewModel.onImageFilePicked(
            fileName = "Campus_Notice_Photo.png",
            mimeType = "image/png",
            fileSizeBytes = pngBytes.size.toLong(),
            inMemoryBytes = pngBytes
        )
        assertTrue(viewModel.submitSelectedImage())

        val state = viewModel.uiState.value
        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, state.currentRoute)
        assertFalse(state.isProcessing)
        assertNull(state.analysisError)
        assertNotNull(state.activeConfirmedEntry?.descriptionResult)
        assertEquals(
            ExtractionSourceMethod.IMAGE_NOTICE,
            state.activeConfirmedEntry?.descriptionResult?.extractionMethod
        )
    }

    @Test
    fun `enter notice text generates grounded description and preserves original notice text`() {
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        viewModel.navigateTo(NoticeAppRoute.ENTER_TEXT_INPUT)
        assertEquals(NoticeAppRoute.ENTER_TEXT_INPUT, viewModel.uiState.value.currentRoute)

        // Reject empty text
        viewModel.updateDraftNoticeText("   ")
        assertFalse(viewModel.submitNoticeText())
        assertNotNull(viewModel.uiState.value.textValidationError)

        // Enter valid notice text
        val originalNotice = "Department of Computer Science: Final Semester Project Viva on 18th Nov 2025. Bring College ID Card and Project Report. Contact: hod.cs@college.edu"
        viewModel.updateDraftNoticeText(originalNotice)
        assertNull(viewModel.uiState.value.textValidationError)

        // Navigate Home and verify draft is preserved
        viewModel.onHomeButtonClicked()
        assertEquals(NoticeAppRoute.HOME, viewModel.uiState.value.currentRoute)
        assertEquals(originalNotice, viewModel.uiState.value.draftNoticeText)

        // Submit notice text and verify grounded description + original text preservation
        viewModel.navigateTo(NoticeAppRoute.ENTER_TEXT_INPUT)
        assertTrue(viewModel.submitNoticeText())
        assertEquals(NoticeAppRoute.ENTER_TEXT_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertFalse(viewModel.uiState.value.isProcessing)
        assertNull(viewModel.uiState.value.analysisError)

        val savedEntry = viewModel.uiState.value.activeConfirmedEntry
        assertNotNull(savedEntry)
        assertEquals(originalNotice, savedEntry?.textContent)
        assertNotNull(savedEntry?.descriptionResult)
        assertEquals(
            "Final Semester Project Viva scheduled for Computer Science students on 18 November 2025.",
            savedEntry?.descriptionResult?.summary
        )
        assertEquals("hod.cs@college.edu", savedEntry?.descriptionResult?.contactDetails)
        assertTrue(
            savedEntry?.descriptionResult?.verificationDisclaimer?.contains("Unverified notice") == true
        )
    }

    @Test
    fun `corrupted embedded text layer in pdf is rejected and triggers real ocr fallback`() = runBlocking {
        val corruptedEmbeddedLayer = """
            . ffi ffi**#M. Secti6n :etf, ,& 2* w%#flffire%ffi**ffi*\#
            t.rr" -..
            wrf6 I tlMTlSchotarhsipl2026-271 1 e o S &qicnr 22.07.2026
            ffi*r, " " 
            " ' , 'r€t ftq / funqn / wचाय / frrrrffiq / qrqft समन्वयक srrd.o3rrd.q+.€. विश्वविद्यालय
            *{/ff.em/glqd' 15 ftflcqr,2026 * 15 अक्टूबर 2026 q{l*lff6
        """.trimIndent()

        assertFalse(
            "Corrupted legacy-font text layer must not be treated as usable selectable text",
            PdfNoticeExtractor.isSelectableTextUsable(corruptedEmbeddedLayer, minChars = 20)
        )

        val cleanHindiOcrSample = """
            पत्रांक : IIMT/Scholarhsip/2026-27/1005 दिनांक: 22.07.2026
            विषय: उत्तर प्रदेश छात्रवृत्ति एवं शुल्क प्रतिपूर्ति वित्तीय वर्ष / शैक्षिक सत्र 2026-27 के आवेदन करने हेतु समय सारणी।
        """.trimIndent()
        assertTrue(
            "Clean Unicode Devanagari + English text must be accepted as usable",
            PdfNoticeExtractor.isSelectableTextUsable(cleanHindiOcrSample, minChars = 20)
        )

        val corruptedPdfBytes = createSelectablePdfBytes(
            pages = listOf(corruptedEmbeddedLayer, corruptedEmbeddedLayer)
        )
        val ocrTriggeredPages = mutableListOf<Int>()
        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String {
                    ocrTriggeredPages.add(pageNumberOneBased)
                    return "OCR Page $pageNumberOneBased: UP Scholarship 2026-27 application window is 15 September 2026 to 4 November 2026."
                }
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val extractionResult = extractor.extractFromBytes(corruptedPdfBytes)
        assertTrue(extractionResult.isSuccess)
        val extraction = extractionResult.getOrThrow()
        assertEquals(ExtractionSourceMethod.PDF_OCR_FALLBACK, extraction.method)
        assertEquals(listOf(1, 2), ocrTriggeredPages)
        assertEquals(2, extraction.ocrPageCount)
    }

    @Test
    fun `api failure surfaces error and never generates a fake description`() {
        val failingService = NoticeAnalysisService(
            aiGateway = FakeFailingAiGateway("Service timeout (HTTP 504)"),
            ioDispatcher = Dispatchers.Unconfined
        )
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = failingService,
            mainDispatcher = Dispatchers.Unconfined
        )

        val rawText = "Hostel mess fee payment circular for Odd Semester 2025."
        viewModel.updateDraftNoticeText(rawText)
        assertTrue(viewModel.submitNoticeText())

        val state = viewModel.uiState.value
        assertEquals(NoticeAppRoute.ENTER_TEXT_CONFIRMATION, state.currentRoute)
        assertFalse(state.isProcessing)
        assertNotNull(state.analysisError)
        assertTrue(state.analysisError!!.contains("Service timeout"))
        assertNull(state.activeConfirmedEntry?.descriptionResult)
        assertEquals(rawText, state.activeConfirmedEntry?.textContent)
    }

    @Test
    fun `missing information and invalid json responses are handled reliably`() = runBlocking {
        val sparseJson = """
            {
              "summary": "General notice regarding library renovation.",
              "purpose": "Inform students that the central reading hall will be closed for painting.",
              "eligibility": "",
              "importantDates": [],
              "requiredDocuments": [],
              "contactDetails": "",
              "nextSteps": ["Use the departmental reading room until further notice."],
              "missingOrAmbiguousInfo": ["Reopening date is not specified in the circular."]
            }
        """.trimIndent()
        val sparseService = NoticeAnalysisService(
            aiGateway = FakeSuccessAiGateway(sparseJson),
            ioDispatcher = Dispatchers.Unconfined
        )
        val sparseResult = sparseService.generateNoticeDescription("Library reading hall closed for painting.")
        assertTrue(sparseResult.isSuccess)
        val desc = sparseResult.getOrThrow()
        assertEquals(NoticeDescriptionResult.NOT_STATED, desc.eligibility)
        assertEquals(listOf(NoticeDescriptionResult.NOT_STATED), desc.importantDates)
        assertEquals(listOf(NoticeDescriptionResult.NOT_STATED), desc.requiredDocuments)
        assertEquals(NoticeDescriptionResult.NOT_STATED, desc.contactDetails)
        assertTrue(desc.missingOrAmbiguousInfo.any { it.contains("Reopening date") })

        val invalidJsonService = NoticeAnalysisService(
            aiGateway = FakeSuccessAiGateway("Not a JSON object"),
            ioDispatcher = Dispatchers.Unconfined
        )
        val invalidResult = invalidJsonService.generateNoticeDescription("Sample notice")
        assertTrue(invalidResult.isFailure)

        val unconfiguredGateway = HttpNoticeAiGateway(explicitApiKey = "")
        val unconfiguredService = NoticeAnalysisService(
            aiGateway = unconfiguredGateway,
            ioDispatcher = Dispatchers.Unconfined
        )
        val noKeyResult = unconfiguredService.generateNoticeDescription("Sample notice")
        if (!unconfiguredGateway.isConfigured()) {
            assertTrue(noKeyResult.isFailure)
        }
    }

    @Test
    fun `selectable text pdf extraction extracts multi-page text and cleans up temp files`() = runBlocking {
        val pdfBytes = createSelectablePdfBytes(
            pages = listOf(
                "Page 1: Annual Merit Scholarship Notice 2025. Applications open for UG students.",
                "Page 2: Submit Income Certificate and Marksheet by 30 November 2025 to Room 104."
            )
        )

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val result = extractor.extractFromBytes(pdfBytes)
        assertTrue(result.isSuccess)
        val extraction = result.getOrThrow()
        assertEquals(ExtractionSourceMethod.PDF_SELECTABLE_TEXT, extraction.method)
        assertEquals(2, extraction.totalPageCount)
        assertEquals(2, extraction.processedPageCount)
        assertEquals(0, extraction.ocrPageCount)
        assertTrue(extraction.extractedText.contains("Annual Merit Scholarship Notice 2025"))
        assertTrue(extraction.extractedText.contains("30 November 2025"))

        val stagingDir = File(application.cacheDir, "noticemate_pdf_staging")
        val remainingFiles = stagingDir.listFiles()?.toList().orEmpty()
        assertTrue(remainingFiles.isEmpty())
    }

    @Test
    fun `scanned pdf triggers ocr fallback handles rotated pages and connects to viewmodel`() = runBlocking {
        val scannedPdfBytes = createBlankScannedPdfBytes(pageCount = 2, secondPageRotation = 90)
        val ocrCalls = mutableListOf<Pair<Int, Int>>()

        val fakeOcrEngine = object : PdfOcrEngine {
            override suspend fun recognizePageText(
                pdfFile: File,
                pageNumberOneBased: Int,
                pageRotationDegrees: Int,
                maxImageDimensionPx: Int
            ): String {
                ocrCalls.add(pageNumberOneBased to pageRotationDegrees)
                return when (pageNumberOneBased) {
                    1 -> "Scanned Page 1: Examination Form Submission Notice for B.Tech Semester V."
                    2 -> "Scanned Page 2: Last date without late fee is 12 December 2025. Attach fee receipt."
                    else -> ""
                }
            }
        }

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = fakeOcrEngine,
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
        viewModel.onPdfFilePicked(
            fileName = "Scanned_Exam_Notice.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = scannedPdfBytes.size.toLong(),
            inMemoryBytes = scannedPdfBytes
        )
        assertTrue(viewModel.submitSelectedPdf())

        val state = viewModel.uiState.value
        assertEquals(NoticeAppRoute.UPLOAD_PDF_CONFIRMATION, state.currentRoute)
        assertFalse(state.isProcessing)
        assertNull(state.analysisError)

        assertEquals(2, ocrCalls.size)
        assertEquals(90, ocrCalls[1].second)

        val extraction = state.lastPdfExtractionResult
        assertNotNull(extraction)
        assertEquals(ExtractionSourceMethod.PDF_OCR_FALLBACK, extraction?.method)
        assertEquals(2, extraction?.ocrPageCount)
        assertEquals(1, extraction?.rotatedPageCount)

        val confirmedEntry = state.activeConfirmedEntry
        assertNotNull(confirmedEntry)
        assertEquals("Scanned_Exam_Notice.pdf", confirmedEntry?.pdfFileName)
        assertTrue(confirmedEntry?.extractedPdfText?.contains("12 December 2025") == true)
        assertNotNull(confirmedEntry?.descriptionResult)
        assertEquals(ExtractionSourceMethod.PDF_OCR_FALLBACK, confirmedEntry?.descriptionResult?.extractionMethod)
    }

    @Test
    fun `invalid pdfs unreadable scans and page limit warnings are handled safely`() = runBlocking {
        val emptyOcrEngine = object : PdfOcrEngine {
            override suspend fun recognizePageText(
                pdfFile: File,
                pageNumberOneBased: Int,
                pageRotationDegrees: Int,
                maxImageDimensionPx: Int
            ): String = ""
        }

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = emptyOcrEngine,
            config = PdfExtractionConfig(
                maxFileSizeBytes = 1024L * 1024L,
                maxPageCount = 2
            ),
            ioDispatcher = Dispatchers.Unconfined
        )

        val fakeBytes = "This is plain text, not a PDF header".toByteArray()
        val nonPdfResult = extractor.extractFromBytes(fakeBytes)
        assertTrue(nonPdfResult.isFailure)
        assertTrue(nonPdfResult.exceptionOrNull()?.message?.contains("valid PDF header") == true)

        val emptyResult = extractor.extractFromBytes(ByteArray(0))
        assertTrue(emptyResult.isFailure)
        assertTrue(emptyResult.exceptionOrNull()?.message?.contains("empty") == true)

        val blankScanBytes = createBlankScannedPdfBytes(pageCount = 1, secondPageRotation = 0)
        val unreadableResult = extractor.extractFromBytes(blankScanBytes)
        assertTrue(unreadableResult.isFailure)
        assertTrue(unreadableResult.exceptionOrNull()?.message?.contains("No readable text") == true)

        val threePageBytes = createSelectablePdfBytes(
            pages = listOf(
                "Page 1 Notice: Hostel Room Allotment Schedule.",
                "Page 2 Notice: Bring fee receipt and ID card.",
                "Page 3 Notice: Additional annexure rules."
            )
        )
        val limitedResult = extractor.extractFromBytes(threePageBytes)
        assertTrue(limitedResult.isSuccess)
        val limitedExtraction = limitedResult.getOrThrow()
        assertEquals(3, limitedExtraction.totalPageCount)
        assertEquals(2, limitedExtraction.processedPageCount)
        assertNotNull(limitedExtraction.limitWarning)
        assertTrue(limitedExtraction.limitWarning!!.contains("Page limit reached"))
    }

    @Test
    fun `profile settings navigation theme toggle and exact Gemma 4 31B model name`() {
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        viewModel.toggleProfileMenu()
        assertTrue(viewModel.uiState.value.isProfileMenuOpen)

        viewModel.openSettingsFromProfile()
        assertFalse(viewModel.uiState.value.isProfileMenuOpen)
        assertEquals(NoticeAppRoute.SETTINGS, viewModel.uiState.value.currentRoute)

        assertEquals(AppThemeMode.NIGHT, viewModel.uiState.value.themeMode)
        viewModel.toggleThemeMode()
        assertEquals(AppThemeMode.DAY, viewModel.uiState.value.themeMode)

        viewModel.navigateTo(NoticeAppRoute.SETTINGS_API_KEYS)
        assertEquals(NoticeAppRoute.SETTINGS_API_KEYS, viewModel.uiState.value.currentRoute)

        viewModel.navigateBack()
        assertEquals(NoticeAppRoute.SETTINGS, viewModel.uiState.value.currentRoute)
        viewModel.navigateTo(NoticeAppRoute.SETTINGS_MODEL)
        assertEquals(NoticeAppRoute.SETTINGS_MODEL, viewModel.uiState.value.currentRoute)

        assertEquals("Gemma 4 31B", HomePhaseViewModel.DISPLAY_MODEL_NAME)

        viewModel.navigateBack()
        assertEquals(NoticeAppRoute.SETTINGS, viewModel.uiState.value.currentRoute)
        viewModel.navigateTo(NoticeAppRoute.SETTINGS_DEBUG_LOGS)
        assertEquals(NoticeAppRoute.SETTINGS_DEBUG_LOGS, viewModel.uiState.value.currentRoute)

        com.example.data.local.AppDiagnosticLogger.info("TEST", "Sample debug log entry for verification")
        assertTrue(
            com.example.data.local.AppDiagnosticLogger.formatAllLogsAsText()
                .contains("Sample debug log entry for verification")
        )

        viewModel.onHomeButtonClicked()
        assertEquals(NoticeAppRoute.HOME, viewModel.uiState.value.currentRoute)
        assertTrue(viewModel.uiState.value.backStack.isEmpty())
    }

    @Test
    fun `phase 3 pdf analysis screen scrolls to last card without bottom actions overlapping`() {
        val longNoticeJson = """
            {
              "shortTitle": "Comprehensive Annual University Scholarship, Hostel Renewal, and Document Verification Circular 2026-27 for All Undergraduate & Postgraduate Departments",
              "titleHi": "सभी स्नातक और स्नातकोत्तर विभागों के लिए व्यापक वार्षिक विश्वविद्यालय छात्रवृत्ति और दस्तावेज़ सत्यापन परिपत्र 2026-27",
              "summary": "All eligible undergraduate and postgraduate students must complete online scholarship registration, upload verified income and category certificates, and submit hard copies to Room 204 before the final deadline.",
              "summaryHi": "सभी पात्र छात्रों को अंतिम तिथि से पहले ऑनलाइन छात्रवृत्ति पंजीकरण पूरा करना होगा और कमरा 204 में सत्यापित दस्तावेज़ जमा करने होंगे।",
              "purpose": "Provide complete timeline and document checklist for 2026-27 scholarship renewal and fresh applications.",
              "eligibility": "Regular full-time UG and PG students across all semesters with family annual income below Rs. 2,50,000 and minimum 75% attendance.",
              "eligibilityHi": "सभी सेमेस्टर के नियमित पूर्णकालिक छात्र जिनकी पारिवारिक वार्षिक आय रु. 2,50,000 से कम है।",
              "importantDates": [
                "01 Oct 2026 - Online portal opens for fresh registration",
                "10 Oct 2026 - Biometric KYC verification window begins",
                "18 Oct 2026 - Last date for initial draft form submission",
                "25 Oct 2026 - Departmental scrutiny and correction window",
                "02 Nov 2026 - Final hard-copy submission at Counter 3",
                "10 Nov 2026 - District committee forwarding deadline"
              ],
              "importantDatesHi": [
                "01 अक्टूबर 2026 - ऑनलाइन पोर्टल पंजीकरण प्रारंभ",
                "02 नवंबर 2026 - हार्ड कॉपी जमा करने की अंतिम तिथि"
              ],
              "requiredDocuments": [
                "Previous Semester Marksheet (Self-Attested)",
                "Valid Income Certificate issued after 01 April 2026",
                "Caste / Category Certificate (if applicable)",
                "Domicile / Residence Certificate",
                "Current Academic Year Fee Receipt",
                "Aadhaar Seeded Bank Passbook Copy",
                "Bonafide Student Certificate from Dean Office",
                "Two Passport Size Photographs"
              ],
              "requiredDocumentsHi": [
                "पिछले सेमेस्टर की अंकतालिका",
                "वैध आय प्रमाण पत्र",
                "शुल्क रसीद और बैंक पासबुक प्रति"
              ],
              "contactDetails": "scholarship.desk@university.edu | Helpdesk Room 204",
              "nextSteps": [
                "Log in to the official scholarship portal and fill out the 2026-27 application.",
                "Complete Aadhaar demographic and biometric authentication at the campus e-Kendra.",
                "Download the verification slip and attach all self-attested supporting documents.",
                "Get the forwarding signature from your Head of Department.",
                "Submit the complete physical file at Scholarship Counter 3 before 02 Nov 2026."
              ],
              "nextStepsHi": [
                "आधिकारिक छात्रवृत्ति पोर्टल पर लॉग इन करें और फॉर्म भरें।",
                "सभी प्रमाणित दस्तावेज़ों के साथ काउंटर 3 पर जमा करें।"
              ],
              "missingOrAmbiguousInfo": []
            }
        """.trimIndent()

        val pdfBytes = createSelectablePdfBytes(
            pages = listOf(
                "Page 1: Comprehensive Annual University Scholarship Notice 2026-27. Portal opens 01 Oct 2026.",
                "Page 2: Submit Income Certificate, Marksheet, Fee Receipt, and Bank Passbook to Room 204 by 02 Nov 2026."
            )
        )
        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""
            },
            ioDispatcher = Dispatchers.Unconfined
        )
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(longNoticeJson),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
        viewModel.onPdfFilePicked(
            fileName = "1005_UP_Scholarship_Time_Table_2026_27.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = pdfBytes.size.toLong(),
            inMemoryBytes = pdfBytes
        )
        assertTrue(viewModel.submitSelectedPdf())

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(viewModel = viewModel)
            }
        }

        // Verify header title & language selector are immediately easy to find
        composeTestRule.onNodeWithTag("pdf_confirmation_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("notice_result_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("language_toggle_pill").assertIsDisplayed()
        composeTestRule.onNodeWithTag("language_toggle_en").assertIsDisplayed()
        composeTestRule.onNodeWithTag("language_toggle_hi").assertIsDisplayed()

        // Verify fixed bottom actions ("Another PDF" and "Done") are visible and do not overlap the scroll region
        composeTestRule.onNodeWithTag("another_pdf_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("pdf_confirm_return_home_button").assertIsDisplayed()

        val scrollBounds = composeTestRule
            .onNodeWithTag("notice_analysis_scroll_container")
            .fetchSemanticsNode()
            .boundsInRoot
        val bottomBarBounds = composeTestRule
            .onNodeWithTag("notice_bottom_action_bar")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Scroll container bottom (${scrollBounds.bottom}) must be above bottom action bar top (${bottomBarBounds.top})",
            scrollBounds.bottom <= bottomBarBounds.top + 1f
        )

        // Verify separated analysis cards can each be scrolled to and read completely, including the last cards
        composeTestRule.onNodeWithTag("description_summary").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_dates").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_next_steps").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_eligibility").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_documents").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirmed_pdf_filename").performScrollTo().assertIsDisplayed()

        // Switch language to Hindi and verify title & summary update cleanly
        composeTestRule.onNodeWithTag("language_toggle_hi").performClick()
        assertEquals(NoticeLanguage.HINDI, viewModel.uiState.value.selectedLanguage)
        composeTestRule.onNodeWithTag("description_summary").performScrollTo().assertTextContains(
            "सभी पात्र छात्रों को अंतिम तिथि से पहले",
            substring = true
        )
    }

    @Test
    fun `phase 3 error state and compact screen resizing preserve bottom actions and usability`() {
        val failingViewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeFailingAiGateway("Network connection lost while analyzing notice."),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        failingViewModel.updateDraftNoticeText("Short Notice: Library closes at 5 PM today.")
        assertTrue(failingViewModel.submitNoticeText())

        // Render inside a compact landscape-like viewport (width=640dp, height=480dp)
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 640.dp, height = 480.dp)) {
                NoticeMateHomeApp(viewModel = failingViewModel)
            }
        }

        // Verify error state card and bottom actions ("Edit Text" and "Done") remain accessible
        composeTestRule.onNodeWithTag("notice_text_confirmation_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("edit_notice_text_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirm_return_home_button").assertIsDisplayed()

        composeTestRule.onNodeWithTag("analysis_error_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("analysis_error_message").assertTextContains(
            "Network connection lost",
            substring = true
        )
        composeTestRule.onNodeWithTag("retry_analysis_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("confirmed_notice_text_body").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `phase 4 structured analysis renders 7 sections in required order and keeps english hindi parity`() {
        val structuredJson = """
            {
              "shortTitle": "Hostel Winter Mess Rebate Circular",
              "titleHi": "शीतकालीन मेस शुल्क छूट सूचना",
              "summary": "Hostel residents leaving for winter vacation can apply for a mess fee rebate by submitting a signed gate pass and parent consent slip before 12 December 2026.",
              "summaryHi": "शीतकालीन अवकाश में घर जाने वाले हॉस्टल निवासी 12 दिसंबर 2026 से पहले गेट पास और अभिभावक सहमति पर्ची जमा करके मेस शुल्क छूट के लिए आवेदन कर सकते हैं।",
              "purpose": "Inform hostel residents about the winter recess mess fee rebate application process.",
              "importantDates": [
                "12 December 2026 — Last date to submit gate pass and parent consent slip"
              ],
              "importantDatesHi": [
                "12 दिसंबर 2026 — गेट पास और सहमति पर्ची जमा करने की अंतिम तिथि"
              ],
              "eligibility": "All hostel residents leaving campus for the upcoming winter recess.",
              "eligibilityHi": "शीतकालीन अवकाश के लिए कैंपस से बाहर जाने वाले सभी हॉस्टल निवासी छात्र।",
              "requiredDocuments": [
                "Signed Hostel Gate Pass",
                "Parent Consent Slip"
              ],
              "requiredDocumentsHi": [
                "हस्ताक्षरित हॉस्टल गेट पास",
                "अभिभावक सहमति पर्ची"
              ],
              "nextSteps": [
                "1. Obtain and complete the hostel gate pass.",
                "Step 2: Arrange the signed parent consent slip.",
                "3) Submit both documents to the Hostel Mess Supervisor before 12 December 2026."
              ],
              "nextStepsHi": [
                "१. हॉस्टल गेट पास प्राप्त करें और भरें।",
                "चरण 2: अभिभावक सहमति पर्ची तैयार करें।",
                "3. 12 दिसंबर 2026 से पहले मेस सुपरवाइजर को जमा करें।"
              ],
              "otherConditions": [
                "Late requests submitted after 12 December 2026 will not be entertained."
              ],
              "otherConditionsHi": [
                "12 दिसंबर 2026 के बाद प्राप्त आवेदनों पर विचार नहीं किया जाएगा।"
              ],
              "contactDetails": "Hostel Mess Supervisor, Chief Warden Office",
              "contactDetailsHi": "हॉस्टल मेस सुपरवाइजर, मुख्य वार्डन कार्यालय",
              "missingOrAmbiguousInfo": [
                "Minimum number of consecutive leave days required for rebate is not specified in the notice."
              ],
              "missingOrAmbiguousInfoHi": [
                "मेस छूट के लिए आवश्यक न्यूनतम छुट्टी के दिनों की संख्या नोटिस में निर्दिष्ट नहीं है।"
              ],
              "askNoticeBuddyQuestions": [
                "What is the minimum number of vacation days required to qualify for the mess rebate?",
                "Is a printed parent email or message accepted as the parent consent slip?"
              ],
              "askNoticeBuddyQuestionsHi": [
                "मेस शुल्क में छूट पाने के लिए न्यूनतम कितने दिनों की छुट्टी आवश्यक है?",
                "क्या माता-पिता द्वारा भेजा गया ईमेल सहमति पर्ची के रूप में मान्य होगा?"
              ]
            }
        """.trimIndent()

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(structuredJson),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        viewModel.updateDraftNoticeText(
            "All hostel residents leaving campus for winter recess may apply for a mess fee rebate before 12 December 2026."
        )
        assertTrue(viewModel.submitNoticeText())

        val desc = viewModel.uiState.value.activeConfirmedEntry?.descriptionResult
        assertNotNull(desc)
        // Verify redundant leading step prefixes ("1. ", "Step 2: ", "१. ") were stripped cleanly
        assertEquals(
            "Obtain and complete the hostel gate pass.",
            desc!!.displayNextSteps(NoticeLanguage.ENGLISH).first()
        )
        assertEquals(
            "हॉस्टल गेट पास प्राप्त करें और भरें।",
            desc.displayNextSteps(NoticeLanguage.HINDI).first()
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 1600.dp)) {
                NoticeMateHomeApp(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Verify exact top-to-bottom section order in the layout hierarchy:
        // 1. In Short -> 2. Important Dates -> 3. Eligibility -> 4. Required Documents -> 5. What To Do Next -> 6. Other Conditions -> 7. Ask NoticeBuddy
        val summaryY = composeTestRule.onNodeWithTag("description_summary").fetchSemanticsNode().positionInRoot.y
        val datesY = composeTestRule.onNodeWithTag("description_dates").fetchSemanticsNode().positionInRoot.y
        val eligibilityY = composeTestRule.onNodeWithTag("description_eligibility").fetchSemanticsNode().positionInRoot.y
        val docsY = composeTestRule.onNodeWithTag("description_documents").fetchSemanticsNode().positionInRoot.y
        val stepsY = composeTestRule.onNodeWithTag("description_next_steps").fetchSemanticsNode().positionInRoot.y
        val conditionsY = composeTestRule.onNodeWithTag("description_other_conditions").fetchSemanticsNode().positionInRoot.y
        val askY = composeTestRule.onNodeWithTag("description_ask_noticebuddy").fetchSemanticsNode().positionInRoot.y

        assertTrue("1. In Short ($summaryY) must appear above 2. Important Dates ($datesY)", summaryY < datesY)
        assertTrue("2. Important Dates ($datesY) must appear above 3. Who Can Apply / Eligibility ($eligibilityY)", datesY < eligibilityY)
        assertTrue("3. Eligibility ($eligibilityY) must appear above 4. Required Documents ($docsY)", eligibilityY < docsY)
        assertTrue("4. Required Documents ($docsY) must appear above 5. What To Do Next ($stepsY)", docsY < stepsY)
        assertTrue("5. What To Do Next ($stepsY) must appear above 6. Other Important Conditions ($conditionsY)", stepsY < conditionsY)
        assertTrue("6. Other Important Conditions ($conditionsY) must appear above 7. Ask NoticeBuddy ($askY)", conditionsY < askY)

        // Verify each of the 7 sections is reachable by scrolling and displayed
        composeTestRule.onNodeWithTag("description_summary").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_dates").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_eligibility").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_documents").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_next_steps").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_other_conditions").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("description_ask_noticebuddy").performScrollTo().assertIsDisplayed()

        // Toggle to Hindi and verify Hindi content is rendered consistently across sections
        composeTestRule.onNodeWithTag("language_toggle_hi").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("description_summary").performScrollTo().assertTextContains(
            "शीतकालीन अवकाश में घर जाने वाले",
            substring = true
        )
        composeTestRule.onNodeWithTag("description_eligibility").performScrollTo().assertTextContains(
            "शीतकालीन अवकाश के लिए कैंपस से बाहर जाने वाले",
            substring = true
        )
        composeTestRule.onNodeWithTag("ask_noticebuddy_question_0").performScrollTo().assertTextContains(
            "मेस शुल्क में छूट पाने के लिए",
            substring = true
        )
    }

    @Test
    fun `phase 4 short notice with missing fields marks absent info clearly in english and hindi and handles malformed json`() = runBlocking {
        // Test malformed/unexpected JSON with markdown fences, trailing commas, string instead of array, and missing fields
        val fencedQuirkyJson = """
            ```json
            {
              "title": "Water Supply Maintenance Notice",
              "inShort": "Campus water supply in Block B will remain suspended for tank cleaning.",
              "inShortHi": "टैंक की सफाई के कारण ब्लॉक बी में जलापूर्ति बंद रहेगी।",
              "importantDates": "Not specified in the notice",
              "whoCanApply": ["N/A"],
              "requiredDocuments": "None",
              "whatToDoNext": "1. Store sufficient drinking water in advance.\n2. Keep taps closed during maintenance.",
              "nextStepsHi": "१. पहले से पर्याप्त पेयजल जमा कर लें।\n२. मरम्मत के दौरान नल बंद रखें।",
              "otherConditions": ["Water tankers will be stationed near Gate 2 for emergency use.",],
              "otherConditionsHi": ["आपातकालीन उपयोग के लिए गेट 2 के पास पानी के टैंकर उपलब्ध रहेंगे।",],
              "contactDetails": "Not mentioned",
            }
            ```
        """.trimIndent()

        val service = NoticeAnalysisService(
            aiGateway = FakeSuccessAiGateway(fencedQuirkyJson),
            ioDispatcher = Dispatchers.Unconfined
        )
        val parsedResult = service.generateNoticeDescription("Water supply in Block B suspended for tank cleaning.")
        assertTrue("Resilient parser should handle markdown fences, trailing commas, and string/array variations", parsedResult.isSuccess)
        val desc = parsedResult.getOrThrow()

        // Verify unstated fields are normalized to NOT_STATED in English and NOT_STATED_HI in Hindi without inventing facts
        assertEquals(NoticeDescriptionResult.NOT_STATED, desc.eligibility)
        assertEquals(NoticeDescriptionResult.NOT_STATED, desc.displayEligibility(NoticeLanguage.ENGLISH))
        assertEquals(NoticeDescriptionResult.NOT_STATED_HI, desc.displayEligibility(NoticeLanguage.HINDI))

        assertEquals(listOf(NoticeDescriptionResult.NOT_STATED), desc.importantDates)
        assertEquals(listOf(NoticeDescriptionResult.NOT_STATED_HI), desc.displayImportantDates(NoticeLanguage.HINDI))

        assertEquals(listOf(NoticeDescriptionResult.NOT_STATED), desc.requiredDocuments)
        assertEquals(listOf(NoticeDescriptionResult.NOT_STATED_HI), desc.displayRequiredDocuments(NoticeLanguage.HINDI))

        // Verify multi-line string in whatToDoNext was split into 2 clean numbered steps without "1." prefix
        assertEquals(2, desc.displayNextSteps(NoticeLanguage.ENGLISH).size)
        assertEquals("Store sufficient drinking water in advance.", desc.displayNextSteps(NoticeLanguage.ENGLISH)[0])
        assertEquals("पहले से पर्याप्त पेयजल जमा कर लें।", desc.displayNextSteps(NoticeLanguage.HINDI)[0])

        // Verify otherConditions and auto-derived follow-up questions for missing dates/contact
        assertEquals(1, desc.displayOtherConditions(NoticeLanguage.ENGLISH).size)
        assertEquals(1, desc.displayOtherConditions(NoticeLanguage.HINDI).size)
        assertTrue(desc.displayAskNoticeBuddyQuestions(NoticeLanguage.ENGLISH).isNotEmpty())
        assertTrue(desc.displayAskNoticeBuddyQuestions(NoticeLanguage.HINDI).isNotEmpty())
    }

    @Test
    fun `phase 5 legitimate notice types pass validation gate and proceed to normal analysis`(): Unit = runBlocking {
        var classifierCalls = 0
        var normalAnalysisCalls = 0

        val trackingGateway = object : NoticeAiGateway {
            override fun isConfigured(): Boolean = true

            override suspend fun completeDocumentClassificationPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                classifierCalls++
                return Result.success(
                    """
                    {
                      "classificationStatus": "VALID_NOTICE",
                      "documentCategory": "GOVERNMENT_NOTIFICATION",
                      "confidence": 0.96,
                      "reasonEn": "Official notification issued to citizens/students with actionable directives.",
                      "reasonHi": "आधिकारिक सूचना जिसमें स्पष्ट निर्देश दिए गए हैं।"
                    }
                    """.trimIndent()
                )
            }

            override suspend fun completeNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                normalAnalysisCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }

            override suspend fun completeMultimodalNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String,
                bitmaps: List<Bitmap>
            ): Result<String> {
                normalAnalysisCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }
        }

        val service = NoticeAnalysisService(
            aiGateway = trackingGateway,
            ioDispatcher = Dispatchers.Unconfined
        )

        val legitimateNotices = listOf(
            // 1. Government Notification
            "Government of India, Ministry of Education Gazette Notification Ref No. GSR-412: All eligible institutions and citizens are hereby notified that the National Fellowship Portal opens on 10 November 2026.",
            // 2. School/College Circular
            "Office of the Principal, Government Engineering College Circular No. 48/2026: All B.Tech and M.Tech students are informed that mid-semester practical examinations are scheduled from 18 November 2026.",
            // 3. Scholarship Notice
            "Directorate of Social Welfare Scholarship Notice 2026-27: Applications are invited from undergraduate and postgraduate students for Post-Matric Scholarship. Last date to submit income certificate and fee receipt is 30 November 2026.",
            // 4. Office Notice
            "Human Resources Department Office Order / Memorandum: All staff and employees are hereby informed that biometric attendance verification at the main office block is mandatory effective from 01 December 2026.",
            // 5. Public Announcement
            "Municipal Corporation Public Announcement: Citizens and residents of Block A and Block B are informed that water supply will remain suspended on Sunday for pipeline maintenance.",
            // 6. Official Instruction
            "Controller of Examinations Official Instructions to Candidates: All applicants appearing for the entrance test must report to Examination Hall 2 by 8:30 AM and bring their Admit Card and photo ID."
        )

        for (noticeText in legitimateNotices) {
            val codeGate = NoticeValidationGate.analyzeContentAndContext(noticeText)
            assertEquals(
                "Expected VALID_NOTICE for legitimate notice: $noticeText",
                NoticeValidationStatus.VALID_NOTICE,
                codeGate.status
            )
            assertTrue(codeGate.isAllowedForNoticeAnalysis)

            val analysisRes = service.generateNoticeDescription(noticeText)
            assertTrue("Legitimate notice should proceed to normal analysis", analysisRes.isSuccess)
        }

        assertEquals(legitimateNotices.size, classifierCalls)
        assertEquals(legitimateNotices.size, normalAnalysisCalls)
    }

    @Test
    fun `phase 5 essays novels invoices resumes and irrelevant pdfs are blocked before notice analysis and preserve original file`(): Unit = runBlocking {
        var classifierCalls = 0
        var normalAnalysisCalls = 0

        // Even if an AI classifier prompt were tricked into returning VALID_NOTICE,
        // code-level content & context enforcement must still block confirmed non-notices!
        val permissiveAiGateway = object : NoticeAiGateway {
            override fun isConfigured(): Boolean = true

            override suspend fun completeDocumentClassificationPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                classifierCalls++
                return Result.success(
                    """
                    {
                      "classificationStatus": "VALID_NOTICE",
                      "documentCategory": "OFFICIAL_INSTRUCTION",
                      "confidence": 0.90,
                      "reasonEn": "Permissive mock classifier",
                      "reasonHi": "मॉक"
                    }
                    """.trimIndent()
                )
            }

            override suspend fun completeNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                normalAnalysisCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }

            override suspend fun completeMultimodalNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String,
                bitmaps: List<Bitmap>
            ): Result<String> {
                normalAnalysisCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }
        }

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = permissiveAiGateway,
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        val nonNoticeSamples = listOf(
            Triple(
                "Important_Government_Notice_2026.pdf", // Misleading notice-like filename!
                DetectedDocumentCategory.ESSAY_OR_ACADEMIC_PAPER,
                "Abstract: In this essay, we examine the socio-economic phenomenon of digital transformation in modern society. Furthermore, researchers have argued throughout the twenty-first century that literature review and empirical study play a crucial role in understanding public policy. On the other hand, scholars have argued that technological determinism overlooks cultural nuance. In conclusion, this paper proposes a balanced research methodology. Works Cited: Smith et al. 2024."
            ),
            Triple(
                "Campus_Circular_Story.pdf",
                DetectedDocumentCategory.NOVEL_OR_LITERARY_TEXT,
                "Chapter 1: Beneath the Stars. Once upon a time, the autumn wind rustled through the courtyard as Arjun walked slowly toward the old wooden gate. Did you see what they posted this morning, she whispered, looking out the window into the shadows. He shook his head and sighed deeply as footsteps echoed down the corridor. His heart pounded while darkness fell over the hills, and he replied softly."
            ),
            Triple(
                "Payment_Notice_Invoice.pdf",
                DetectedDocumentCategory.INVOICE_OR_BILL,
                "TAX INVOICE - Invoice Number: INV-2026-8841 - GSTIN: 09AAACH7409R1ZZ - Bill To: Acme Enterprises Pvt Ltd - Ship To: Sector 62 Noida - Item Description: Cloud Hosting Subscription - Qty: 2 - Unit Price: Rs. 12000 - Subtotal: Rs. 24000 - CGST: Rs. 2160 - SGST: Rs. 2160 - Total Amount Due: Rs. 28320 - Payment Terms: Net 15 Days. Thank you for your business!"
            ),
            Triple(
                "Scholarship_Applicant_Notice_CV.pdf",
                DetectedDocumentCategory.RESUME_OR_CV,
                "CURRICULUM VITAE - ROHIT VERMA - Software Engineer - linkedin.com/in/rohitverma - github.com/rohitv - Career Objective: Seeking a challenging backend engineering role. Notice Period: 30 Days. Work Experience: Senior Software Engineer at TechCorp. Technical Skills: Kotlin, Jetpack Compose, Distributed Systems, PostgreSQL. Educational Qualifications: B.Tech Computer Science, CGPA: 9.1. References available upon request."
            ),
            Triple(
                "Official_Circular_Recipe.pdf",
                DetectedDocumentCategory.IRRELEVANT_DOCUMENT,
                "Classic Masala Dosa Batter Recipe - Prep Time: 20 mins - Cook Time: 15 mins - Servings: 4 - Ingredients: 2 cups parboiled rice, 1/2 cup urad dal, 1 teaspoon fenugreek seeds, 2 tablespoons olive oil, chopped onions. Instructions: Soak rice and dal for 6 hours, grind to a smooth batter, ferment overnight, and simmer for 10 minutes with spices."
            )
        )

        for ((fileName, expectedCategory, documentText) in nonNoticeSamples) {
            val pdfBytes = createSelectablePdfBytes(pages = listOf(documentText))
            viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
            viewModel.onPdfFilePicked(
                fileName = fileName,
                mimeType = "application/pdf",
                fileSizeBytes = pdfBytes.size.toLong(),
                inMemoryBytes = pdfBytes
            )
            assertTrue(viewModel.submitSelectedPdf())

            val state = viewModel.uiState.value
            assertEquals(NoticeAppRoute.UPLOAD_PDF_CONFIRMATION, state.currentRoute)
            assertFalse(state.isProcessing)
            assertNull("Rejected non-notice must NOT produce a notice summary", state.activeConfirmedEntry?.descriptionResult)

            val gate = state.validationGateResult
            assertNotNull("Validation gate result must be populated for $fileName", gate)
            assertEquals(NoticeValidationStatus.NOT_A_NOTICE, gate!!.status)
            assertEquals(expectedCategory, gate.category)
            assertFalse(gate.isAllowedForNoticeAnalysis)
            assertTrue(
                "Polite rejection message must state NoticeBuddy only analyses notices",
                gate.userMessageEn.contains("NoticeBuddy only analyses notices")
            )

            // Verify original uploaded file metadata and extracted text are preserved non-destructively
            assertEquals(fileName, state.activeConfirmedEntry?.pdfFileName)
            assertTrue(state.activeConfirmedEntry?.extractedPdfText?.isNotBlank() == true)
        }

        // CRITICAL ENFORCEMENT VERIFICATION:
        // None of the 5 rejected PDFs ever reached completeNoticeAnalysisPrompt or completeMultimodalNoticeAnalysisPrompt!
        assertEquals(
            "Rejected non-notice documents must NEVER proceed to the normal notice-analysis request",
            0,
            normalAnalysisCalls
        )
        assertEquals(5, classifierCalls)

        // Verify UI renders the polite NoticeValidationGateCard in English and Hindi
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("notice_validation_gate_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("validation_gate_message").assertTextContains(
            "NoticeBuddy only analyses notices",
            substring = true
        )
        composeTestRule.onNodeWithTag("language_toggle_hi").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("validation_gate_message").assertTextContains(
            "NoticeBuddy केवल आधिकारिक नोटिस",
            substring = true
        )
        assertEquals(NoticeLanguage.HINDI, viewModel.uiState.value.selectedLanguage)
    }

    @Test
    fun `phase 5 camera scanned image and blurry or unreadable documents and api errors are handled distinctly`(): Unit = runBlocking {
        var normalAnalysisCalls = 0
        var currentOcrImageText = ""

        val customOcrEngine = object : PdfOcrEngine {
            override suspend fun recognizePageText(
                pdfFile: File,
                pageNumberOneBased: Int,
                pageRotationDegrees: Int,
                maxImageDimensionPx: Int
            ): String = currentOcrImageText

            override suspend fun recognizeBitmapText(pageBitmap: Bitmap): String = currentOcrImageText
        }

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = customOcrEngine,
            ioDispatcher = Dispatchers.Unconfined
        )

        var classificationCalls = 0
        val countingGateway = object : NoticeAiGateway {
            override fun isConfigured(): Boolean = true

            override suspend fun completeDocumentClassificationPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                classificationCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }

            override suspend fun completeMultimodalDocumentClassificationPrompt(
                systemPrompt: String,
                userPrompt: String,
                bitmaps: List<Bitmap>
            ): Result<String> {
                classificationCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }

            override suspend fun completeNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                normalAnalysisCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }

            override suspend fun completeMultimodalNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String,
                bitmaps: List<Bitmap>
            ): Result<String> {
                normalAnalysisCalls++
                return Result.success(DEFAULT_GROUNDED_JSON)
            }
        }

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = countingGateway,
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        val sampleBitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val pngBytes = ByteArrayOutputStream().use { out ->
            sampleBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }

        // 1. Camera-scanned image of an Invoice -> blocked as NOT_A_NOTICE
        currentOcrImageText = """
            TAX INVOICE #9921
            Bill To: Customer Desk | Subtotal: Rs. 4,500 | CGST: Rs. 405 | SGST: Rs. 405
            Total Amount Due: Rs. 5,310. Thank you for your business!
        """.trimIndent()

        viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_SELECT)
        viewModel.onImageFilePicked(
            fileName = "Camera_Scan_01.png",
            mimeType = "image/png",
            fileSizeBytes = pngBytes.size.toLong(),
            inMemoryBytes = pngBytes
        )
        assertTrue(viewModel.submitSelectedImage())
        assertEquals(NoticeValidationStatus.NOT_A_NOTICE, viewModel.uiState.value.validationGateResult?.status)
        assertEquals(DetectedDocumentCategory.INVOICE_OR_BILL, viewModel.uiState.value.validationGateResult?.category)
        assertNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
        assertEquals(0, normalAnalysisCalls)

        // 2. Blurry scan with fragmentary OCR tokens -> NEEDS_CLEARER_INPUT (does not invent classification)
        currentOcrImageText = "ab .. cd .. x1 # 9 .. ef .. k2 .. gh"
        viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_SELECT)
        viewModel.onImageFilePicked(
            fileName = "Blurry_Camera_Scan.png",
            mimeType = "image/png",
            fileSizeBytes = pngBytes.size.toLong(),
            inMemoryBytes = pngBytes
        )
        assertTrue(viewModel.submitSelectedImage())
        assertEquals(
            NoticeValidationStatus.NEEDS_CLEARER_INPUT,
            viewModel.uiState.value.validationGateResult?.status
        )
        assertEquals(
            DetectedDocumentCategory.INSUFFICIENT_OR_BLURRY,
            viewModel.uiState.value.validationGateResult?.category
        )
        assertNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
        assertEquals(0, normalAnalysisCalls)

        // 3. Completely blank / unreadable scan -> UNREADABLE_DOCUMENT (distinct from NOT_A_NOTICE)
        currentOcrImageText = "   "
        val blankPdfBytes = createBlankScannedPdfBytes(pageCount = 1, secondPageRotation = 0)
        viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
        viewModel.onPdfFilePicked(
            fileName = "Blank_Scan.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = blankPdfBytes.size.toLong(),
            inMemoryBytes = blankPdfBytes
        )
        assertTrue(viewModel.submitSelectedPdf())
        val blankGate = viewModel.uiState.value.validationGateResult
        assertNotNull(blankGate)
        assertEquals(NoticeValidationStatus.UNREADABLE_DOCUMENT, blankGate!!.status)
        assertTrue("Unreadable document must be distinguished from NOT_A_NOTICE", blankGate.status != NoticeValidationStatus.NOT_A_NOTICE)
        assertNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
        assertEquals(0, normalAnalysisCalls)

        // 4. Classifier API failure -> CLASSIFICATION_ERROR (never treated as proof that document is not a notice)
        val failingClassifierGateway = FakeFailingAiGateway("Classifier service HTTP 503 Unavailable")
        val failingService = NoticeAnalysisService(
            aiGateway = failingClassifierGateway,
            ioDispatcher = Dispatchers.Unconfined
        )
        val gateOnApiFailure = failingService.validateNoticeDocument(
            "Office of the Registrar Circular: All UG students must submit scholarship renewal forms by 25 Nov 2026."
        )
        assertEquals(NoticeValidationStatus.CLASSIFICATION_ERROR, gateOnApiFailure.status)
        assertTrue(
            "A failed classifier request must NEVER be treated as NOT_A_NOTICE",
            gateOnApiFailure.status != NoticeValidationStatus.NOT_A_NOTICE
        )
        assertFalse(gateOnApiFailure.isAllowedForNoticeAnalysis)
    }

    @Test
    fun `phase 6 startup offline and live connectivity transitions update banner without blank or stuck loading screen`() {
        val networkMonitor = ControllableNetworkConnectivityMonitor(
            initialStatus = NetworkConnectivityStatus.OFFLINE
        )
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined,
            networkMonitor = networkMonitor
        )

        // A. At app startup: checks connectivity immediately, shows offline state, never stuck loading or blank
        assertTrue(networkMonitor.isMonitoringActive)
        assertEquals(NetworkConnectivityStatus.OFFLINE, viewModel.uiState.value.networkStatus)
        assertFalse(viewModel.uiState.value.isProcessing)

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        // Home content and offline banner are both visible at startup
        composeTestRule.onNodeWithTag("network_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("network_status_badge").assertTextContains("Offline", substring = true)
        composeTestRule.onNodeWithTag("network_status_title").assertTextContains("You Are Currently Offline", substring = true)
        composeTestRule.onNodeWithTag("homepage_root").assertIsDisplayed()
        composeTestRule.onNodeWithTag("upload_notice_pdf_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("enter_notice_text_button").performScrollTo().assertIsDisplayed()

        // B. Distinguish "network connected without working internet" (CONNECTED_NO_INTERNET)
        networkMonitor.setStatus(NetworkConnectivityStatus.CONNECTED_NO_INTERNET)
        composeTestRule.waitForIdle()
        assertEquals(NetworkConnectivityStatus.CONNECTED_NO_INTERNET, viewModel.uiState.value.networkStatus)
        composeTestRule.onNodeWithTag("network_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("network_status_badge").assertTextContains("No Internet Access", substring = true)
        composeTestRule.onNodeWithTag("network_status_title").assertTextContains(
            "Network Connected — No Internet Access",
            substring = true
        )

        // C. Reconnection updates state and hides the offline banner
        networkMonitor.setStatus(NetworkConnectivityStatus.ONLINE)
        composeTestRule.waitForIdle()
        assertEquals(NetworkConnectivityStatus.ONLINE, viewModel.uiState.value.networkStatus)
        composeTestRule.onNodeWithTag("network_status_banner").assertDoesNotExist()

        // D. Connection loss during normal app use immediately surfaces the banner again
        networkMonitor.setStatus(NetworkConnectivityStatus.OFFLINE)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("network_status_banner").assertIsDisplayed()
    }

    @Test
    fun `phase 6 distinguishes upload failure no internet request timeout and ai service unavailable`() {
        val networkMonitor = ControllableNetworkConnectivityMonitor(
            initialStatus = NetworkConnectivityStatus.ONLINE
        )
        var aiCalls = 0
        var simulatedException: Exception? = null

        val configurableGateway = object : NoticeAiGateway {
            override fun isConfigured(): Boolean = true

            override suspend fun completeNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                aiCalls++
                val err = simulatedException
                return if (err != null) {
                    Result.failure(err)
                } else {
                    Result.success(DEFAULT_GROUNDED_JSON)
                }
            }
        }

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = configurableGateway,
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined,
            networkMonitor = networkMonitor
        )

        // 1. UPLOAD FAILURE: Corrupted / non-PDF bytes fail during local document upload/read before any AI call
        val corruptBytes = "Not a valid PDF file header stream".toByteArray()
        viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
        viewModel.onPdfFilePicked(
            fileName = "Corrupted_Upload.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = corruptBytes.size.toLong(),
            inMemoryBytes = corruptBytes
        )
        assertTrue(viewModel.submitSelectedPdf())
        assertEquals(0, aiCalls)
        assertNotNull("Selected PDF draft must be preserved on upload failure", viewModel.uiState.value.selectedPdfDraft)
        assertEquals(
            NoticeRequestErrorCategory.UPLOAD_FAILURE,
            viewModel.uiState.value.requestError?.category
        )

        // 2. NO INTERNET: Valid PDF uploaded while OFFLINE extracts local text, preserves progress, and blocks AI call with NO_INTERNET
        val validNoticeText =
            "Office of the Principal Circular No. 82/2026: All B.Tech students must submit semester registration forms and fee receipts to Room 104 by 20 December 2026."
        val validPdfBytes = createSelectablePdfBytes(pages = listOf(validNoticeText))
        networkMonitor.setStatus(NetworkConnectivityStatus.OFFLINE)

        viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
        viewModel.onPdfFilePicked(
            fileName = "Semester_Registration_Notice.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = validPdfBytes.size.toLong(),
            inMemoryBytes = validPdfBytes
        )
        assertTrue(viewModel.submitSelectedPdf())
        assertEquals("No AI backend call should be attempted while offline", 0, aiCalls)
        assertEquals(
            NoticeRequestErrorCategory.NO_INTERNET,
            viewModel.uiState.value.requestError?.category
        )
        assertTrue(
            "Local PDF text extraction progress must be preserved even when offline",
            viewModel.uiState.value.activeConfirmedEntry?.extractedPdfText?.contains("Office of the Principal") == true
        )

        // 3. REQUEST TIMEOUT: Reconnect to internet, but AI request times out -> REQUEST_TIMEOUT
        networkMonitor.setStatus(NetworkConnectivityStatus.ONLINE)
        simulatedException = NoticeAnalysisException(
            "The notice analysis request timed out. Please check your network connection and try again.",
            SocketTimeoutException("Read timed out")
        )
        assertTrue(viewModel.retryCurrentNoticeAnalysis())
        assertEquals(1, aiCalls)
        assertEquals(
            NoticeRequestErrorCategory.REQUEST_TIMEOUT,
            viewModel.uiState.value.requestError?.category
        )
        assertEquals(
            "Semester_Registration_Notice.pdf",
            viewModel.uiState.value.selectedPdfDraft?.fileName
        )

        // 4. AI SERVICE UNAVAILABLE: Internet is connected, but AI backend returns HTTP 503 -> AI_SERVICE_UNAVAILABLE
        simulatedException = NoticeAnalysisException(
            "The notice analysis service returned an error (HTTP 503). Please try again later."
        )
        assertTrue(viewModel.retryCurrentNoticeAnalysis())
        assertEquals(2, aiCalls)
        assertEquals(
            NoticeRequestErrorCategory.AI_SERVICE_UNAVAILABLE,
            viewModel.uiState.value.requestError?.category
        )

        // 5. RETRY RECOVERY: Backend recovers -> Retry succeeds using preserved extracted PDF text
        simulatedException = null
        assertTrue(viewModel.retryCurrentNoticeAnalysis())
        assertNull(viewModel.uiState.value.requestError)
        assertNull(viewModel.uiState.value.analysisError)
        assertNotNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
    }

    @Test
    fun `phase 6 mid flight connection loss prevents duplicate submissions preserves existing results and recovers on retry`() = runBlocking {
        val networkMonitor = ControllableNetworkConnectivityMonitor(
            initialStatus = NetworkConnectivityStatus.ONLINE
        )
        var holdRequestOpen = false
        var deferredResponse = CompletableDeferred<Result<String>>()
        var gatewayCalls = 0

        val controllableGateway = object : NoticeAiGateway {
            override fun isConfigured(): Boolean = true

            override suspend fun completeNoticeAnalysisPrompt(
                systemPrompt: String,
                userPrompt: String
            ): Result<String> {
                gatewayCalls++
                return if (holdRequestOpen) {
                    deferredResponse.await()
                } else {
                    Result.success(DEFAULT_GROUNDED_JSON)
                }
            }
        }

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = controllableGateway,
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined,
            networkMonitor = networkMonitor
        )

        val noticeText =
            "Office of Controller of Examinations Notice: Final Semester Computer Science Project Viva is scheduled on 18 November 2025. Bring College ID Card and Project Report."
        viewModel.updateDraftNoticeText(noticeText)
        assertTrue(viewModel.submitNoticeText())
        assertNotNull(
            "Initial analysis should succeed and store descriptionResult",
            viewModel.uiState.value.activeConfirmedEntry?.descriptionResult
        )

        // Now start a new analysis/retry that suspends in-flight
        holdRequestOpen = true
        deferredResponse = CompletableDeferred()
        val callsBeforeInFlight = gatewayCalls
        assertTrue(viewModel.retryCurrentNoticeAnalysis())
        assertTrue("ViewModel should be in processing state while request is in flight", viewModel.uiState.value.isProcessing)
        assertEquals(callsBeforeInFlight + 1, gatewayCalls)

        // Verify duplicate submissions and repeated retries are blocked while in-flight
        assertFalse("Duplicate submitNoticeText must be blocked while in-flight", viewModel.submitNoticeText())
        assertFalse("Duplicate retryCurrentNoticeAnalysis must be blocked while in-flight", viewModel.retryCurrentNoticeAnalysis())
        assertEquals("Blocked duplicate calls must not invoke the AI gateway again", callsBeforeInFlight + 1, gatewayCalls)

        // Simulate connection loss while the request is in-flight
        networkMonitor.setStatus(NetworkConnectivityStatus.OFFLINE)
        assertFalse("Connection loss must stop the in-flight loading state immediately", viewModel.uiState.value.isProcessing)
        assertEquals(
            NoticeRequestErrorCategory.NO_INTERNET,
            viewModel.uiState.value.requestError?.category
        )
        assertNotNull(
            "Existing descriptionResult must NOT be discarded when a subsequent request fails",
            viewModel.uiState.value.activeConfirmedEntry?.descriptionResult
        )
        assertEquals(noticeText, viewModel.uiState.value.draftNoticeText)

        // Verify UI renders the error card, connection restored badge on reconnect, and preserved summary simultaneously
        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 1000.dp)) {
                NoticeMateHomeApp(viewModel = viewModel)
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("network_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("analysis_error_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("request_error_category_badge").assertTextContains("No Internet", substring = true)
        composeTestRule.onNodeWithTag("description_summary").performScrollTo().assertIsDisplayed()

        // Reconnect to the internet -> banner disappears and "Connection Restored" badge appears on error card
        holdRequestOpen = false
        networkMonitor.setStatus(NetworkConnectivityStatus.ONLINE)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("network_status_banner").assertDoesNotExist()
        composeTestRule.onNodeWithTag("connection_restored_badge").performScrollTo().assertIsDisplayed()

        // Tap "Try Again" button in UI -> succeeds and clears error
        composeTestRule.onNodeWithTag("retry_analysis_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        assertNull(viewModel.uiState.value.requestError)
        assertNull(viewModel.uiState.value.analysisError)
        assertNotNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
    }

    @Test
    fun `phase 7 camera permission denial grant preview flash lens switch and rotation work reliably`() {
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        var simulatedCaptureRequests = 0

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = { simulatedCaptureRequests++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Navigate to Upload Image screen and tap "Scan Notice with Camera"
        viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_SELECT)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("open_camera_scanner_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(NoticeAppRoute.SCAN_NOTICE_CAMERA, viewModel.uiState.value.currentRoute)

        // 1. Permission Denial: User denies camera permission -> glassmorphic rationale/denial card shown
        viewModel.onCameraPermissionResult(isGranted = false)
        composeTestRule.waitForIdle()
        assertEquals(CameraPermissionState.DENIED, viewModel.uiState.value.cameraScanState.permissionState)
        assertEquals(1, viewModel.uiState.value.cameraScanState.permissionDeniedCount)
        assertFalse(viewModel.uiState.value.cameraScanState.isCameraBound)

        composeTestRule.onNodeWithTag("camera_permission_denied_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_permission_title").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camera_permission_settings_hint").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_permission_grant_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_permission_gallery_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_permission_cancel_button").assertIsDisplayed()

        // 2. Permission Grant: User grants camera permission -> transitions to INITIALIZING loading state
        viewModel.onCameraPermissionResult(isGranted = true)
        composeTestRule.waitForIdle()
        assertEquals(CameraPermissionState.GRANTED, viewModel.uiState.value.cameraScanState.permissionState)
        assertEquals(CameraSessionStage.INITIALIZING, viewModel.uiState.value.cameraScanState.sessionStage)
        composeTestRule.onNodeWithTag("camera_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_loading_indicator").assertIsDisplayed()

        // 3. Camera Preview Bound with Flash Supported -> PREVIEW_READY
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.isCameraBound)
        assertEquals(CameraSessionStage.PREVIEW_READY, viewModel.uiState.value.cameraScanState.sessionStage)
        composeTestRule.onNodeWithTag("camerax_preview_view").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_shutter_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_gallery_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_flash_toggle_button").assertIsDisplayed()

        // Toggle flash ON and OFF
        composeTestRule.onNodeWithTag("camerax_flash_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.isFlashEnabled)
        composeTestRule.onNodeWithTag("camerax_flash_toggle_button").assertTextContains("Flash: ON", substring = true)

        composeTestRule.onNodeWithTag("camerax_flash_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(viewModel.uiState.value.cameraScanState.isFlashEnabled)

        // 4. Switch to Front Lens where Flash is NOT supported -> flash disabled and badge shown
        composeTestRule.onNodeWithTag("camerax_switch_lens_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(CameraScanUiState.LENS_FACING_FRONT, viewModel.uiState.value.cameraScanState.lensFacing)
        viewModel.onCameraPreviewBound(isFlashSupported = false)
        composeTestRule.waitForIdle()
        assertFalse(viewModel.uiState.value.cameraScanState.isFlashSupported)
        assertFalse(viewModel.uiState.value.cameraScanState.isFlashEnabled)
        assertFalse("toggleCameraFlash must return false when flash is unsupported", viewModel.toggleCameraFlash())
        composeTestRule.onNodeWithTag("camerax_flash_unsupported_badge").assertIsDisplayed()

        // Switch back to Rear Lens and test Device Rotation (90 degrees)
        composeTestRule.onNodeWithTag("camerax_switch_lens_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        viewModel.onDeviceRotationChanged(90)
        composeTestRule.waitForIdle()
        assertEquals(90, viewModel.uiState.value.cameraScanState.deviceRotationDegrees)
        composeTestRule.onNodeWithTag("camerax_rotation_badge").assertIsDisplayed().assertTextContains("90°")

        // 5. Camera Error State & Retry Recovery
        viewModel.onCameraError("Camera sensor temporarily busy")
        composeTestRule.waitForIdle()
        assertEquals(CameraSessionStage.CAMERA_ERROR, viewModel.uiState.value.cameraScanState.sessionStage)
        composeTestRule.onNodeWithTag("camerax_error_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_error_message").assertTextContains("Camera sensor temporarily busy", substring = true)

        composeTestRule.onNodeWithTag("camerax_retry_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()
        assertEquals(CameraSessionStage.PREVIEW_READY, viewModel.uiState.value.cameraScanState.sessionStage)
        assertNull(viewModel.uiState.value.cameraScanState.cameraErrorMessage)
    }

    @Test
    fun `phase 7 manual capture review retake and accept integrate with validation gate and network error handling`() {
        val networkMonitor = ControllableNetworkConnectivityMonitor(
            initialStatus = NetworkConnectivityStatus.ONLINE
        )
        var currentOcrText =
            "Office of the Dean Circular No. 402/2026: Final Semester Computer Science Project Viva scheduled on 18 November 2025. Students must bring College ID Card and Project Report. Contact: hod.cs@college.edu"

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = currentOcrText

                override suspend fun recognizeBitmapText(pageBitmap: Bitmap): String = currentOcrText
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined,
            networkMonitor = networkMonitor
        )

        var captureInvocations = 0

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = {
                        captureInvocations++
                    }
                )
            }
        }
        composeTestRule.waitForIdle()

        // Open Camera Scanner and bind preview
        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()

        // 1. Trigger Manual Capture via shutter button
        composeTestRule.onNodeWithTag("camerax_shutter_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, captureInvocations)
        assertEquals(CameraSessionStage.CAPTURING, viewModel.uiState.value.cameraScanState.sessionStage)
        assertFalse("Duplicate capture must be blocked while capturing", viewModel.onManualCaptureStarted())

        // Complete first capture with a 320x200 landscape bitmap rotated 90 degrees -> upright 200x320
        val firstCaptureBitmap = Bitmap.createBitmap(320, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        assertTrue(viewModel.onManualCaptureSucceeded(firstCaptureBitmap, rotationDegrees = 90))
        composeTestRule.waitForIdle()

        // 2. Verify Captured Image Review Screen is displayed and survives device rotation
        assertEquals(CameraSessionStage.REVIEWING_CAPTURE, viewModel.uiState.value.cameraScanState.sessionStage)
        val firstCapturedPhoto = viewModel.uiState.value.cameraScanState.capturedPhoto
        assertNotNull(firstCapturedPhoto)
        assertEquals(200, firstCapturedPhoto?.widthPx)
        assertEquals(320, firstCapturedPhoto?.heightPx)

        composeTestRule.onNodeWithTag("camerax_review_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_captured_image_preview").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_retake_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_accept_button").assertIsDisplayed()

        // Rotate device while reviewing -> captured photo must remain intact
        viewModel.onDeviceRotationChanged(180)
        composeTestRule.waitForIdle()
        assertNotNull(viewModel.uiState.value.cameraScanState.capturedPhoto)
        composeTestRule.onNodeWithTag("camerax_review_screen").assertIsDisplayed()

        // 3. Tap "Retake" -> discards first capture and returns to live camera preview
        composeTestRule.onNodeWithTag("camerax_retake_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()
        assertNull(viewModel.uiState.value.cameraScanState.capturedPhoto)
        assertEquals(CameraSessionStage.PREVIEW_READY, viewModel.uiState.value.cameraScanState.sessionStage)
        composeTestRule.onNodeWithTag("camera_screen").assertIsDisplayed()

        // 4. Capture a Valid Notice -> Review -> Tap "Use Photo" -> passes into pipeline & succeeds
        composeTestRule.onNodeWithTag("camerax_shutter_button").performClick()
        val secondBitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        assertTrue(viewModel.onManualCaptureSucceeded(secondBitmap, rotationDegrees = 0))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camerax_accept_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertEquals(
            NoticeValidationStatus.VALID_NOTICE,
            viewModel.uiState.value.validationGateResult?.status
        )
        assertNotNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
        assertEquals(
            ExtractionSourceMethod.IMAGE_NOTICE,
            viewModel.uiState.value.activeConfirmedEntry?.descriptionResult?.extractionMethod
        )

        // 5. Preserve Phase 5 Notice-Only Validation Gate: Camera scan of a Non-Notice (Invoice) is blocked
        currentOcrText = """
            TAX INVOICE #INV-9981
            Billed To: Rahul Sharma
            1x Bluetooth Headphones - Rs. 2,499.00
            CGST 9%: Rs. 224.91 | SGST 9%: Rs. 224.91
            Grand Total Paid via UPI: Rs. 2,948.82
        """.trimIndent()

        viewModel.openCameraScanner()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(secondBitmap, rotationDegrees = 0))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camerax_accept_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertEquals(
            NoticeValidationStatus.NOT_A_NOTICE,
            viewModel.uiState.value.validationGateResult?.status
        )
        assertEquals(
            DetectedDocumentCategory.INVOICE_OR_BILL,
            viewModel.uiState.value.validationGateResult?.category
        )
        assertNull(
            "Rejected camera scan must never proceed to notice summarization",
            viewModel.uiState.value.activeConfirmedEntry?.descriptionResult
        )

        // 6. Preserve Phase 6 Network-Error Handling: Accepting a camera scan while OFFLINE surfaces NO_INTERNET and retries cleanly
        currentOcrText =
            "University Examination Circular: Semester V registration forms must be submitted by 15 December 2026 to Room 108."
        networkMonitor.setStatus(NetworkConnectivityStatus.OFFLINE)

        viewModel.openCameraScanner()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(secondBitmap, rotationDegrees = 0))
        assertTrue(viewModel.onAcceptCapturedPhoto())
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertEquals(
            NoticeRequestErrorCategory.NO_INTERNET,
            viewModel.uiState.value.requestError?.category
        )
        assertNotNull("Accepted camera photo draft must be preserved when offline", viewModel.uiState.value.selectedImageDraft)

        // Reconnect and retry -> succeeds
        networkMonitor.setStatus(NetworkConnectivityStatus.ONLINE)
        assertTrue(viewModel.retryCurrentNoticeAnalysis())
        composeTestRule.waitForIdle()
        assertNull(viewModel.uiState.value.requestError)
        assertNotNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
    }

    @Test
    fun `phase 7 gallery selection navigation away and back release camera resources and preserve pdf upload`() {
        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""
            },
            ioDispatcher = Dispatchers.Unconfined
        )
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false
                )
            }
        }
        composeTestRule.waitForIdle()

        // 1. Open camera scanner, bind preview and enable flash
        viewModel.navigateTo(NoticeAppRoute.UPLOAD_IMAGE_SELECT)
        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        assertTrue(viewModel.toggleCameraFlash())
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.isCameraBound)
        assertTrue(viewModel.uiState.value.cameraScanState.isFlashEnabled)

        // 2. Navigate away (Back to UPLOAD_IMAGE_SELECT) -> camera preview unbinds & flash turns off automatically
        assertTrue(viewModel.navigateBack())
        composeTestRule.waitForIdle()
        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_SELECT, viewModel.uiState.value.currentRoute)
        assertFalse(
            "Navigating away from camera screen must unbind camera to prevent leaks",
            viewModel.uiState.value.cameraScanState.isCameraBound
        )
        assertFalse(
            "Navigating away from camera screen must turn off flash/torch",
            viewModel.uiState.value.cameraScanState.isFlashEnabled
        )

        // 3. Reopen camera scanner -> rebinds cleanly without crashes
        viewModel.openCameraScanner()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()
        assertEquals(NoticeAppRoute.SCAN_NOTICE_CAMERA, viewModel.uiState.value.currentRoute)
        assertTrue(viewModel.uiState.value.cameraScanState.isCameraBound)

        // 4. Select an image from Gallery while on the Camera Scanner screen -> transitions to UPLOAD_IMAGE_SELECT with selected image card
        val sampleGalleryBmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
        }
        val galleryPngBytes = ByteArrayOutputStream().use { out ->
            sampleGalleryBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        viewModel.onImageFilePicked(
            fileName = "Gallery_Scholarship_Notice.png",
            mimeType = "image/png",
            fileSizeBytes = galleryPngBytes.size.toLong(),
            inMemoryBytes = galleryPngBytes
        )
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_SELECT, viewModel.uiState.value.currentRoute)
        assertFalse(viewModel.uiState.value.cameraScanState.isCameraBound)
        composeTestRule.onNodeWithTag("selected_image_info_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("selected_image_filename").assertTextContains("Gallery_Scholarship_Notice.png")

        // 5. Verify PDF upload remains completely intact
        val pdfBytes = createSelectablePdfBytes(
            pages = listOf(
                "Office of the Registrar Circular: Academic Calendar for Even Semester 2026-27 starts on 10 January 2027. Submit registration slip to Room 102."
            )
        )
        viewModel.onHomeButtonClicked()
        viewModel.navigateTo(NoticeAppRoute.UPLOAD_PDF_SELECT)
        viewModel.onPdfFilePicked(
            fileName = "Even_Semester_Calendar.pdf",
            mimeType = "application/pdf",
            fileSizeBytes = pdfBytes.size.toLong(),
            inMemoryBytes = pdfBytes
        )
        assertTrue(viewModel.submitSelectedPdf())
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_PDF_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertNull(viewModel.uiState.value.requestError)
        assertNotNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
    }

    @Test
    fun `phase 8 edge detection quality guidance for empty scenes lighting glare tilt cutoff and blur prevent false detection and premature auto capture`() {
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        var captureTriggers = 0

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = { captureTriggers++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()

        // 1. Empty Camera Scene: Must NOT claim a document is detected or render boundary indicator
        val emptyScene = createSyntheticCameraSceneBitmap(
            includeDocument = false,
            backgroundLuma = 90
        )
        repeat(5) { idx ->
            val autoTriggered = viewModel.analyzePreviewBitmap(
                bitmap = emptyScene,
                frameSequenceId = 100L + idx
            )
            assertFalse("Empty scene must never auto-capture", autoTriggered)
        }
        composeTestRule.waitForIdle()

        val emptyDetection = viewModel.uiState.value.cameraScanState.detectionState
        assertFalse("Empty scene must not falsely claim document detection", emptyDetection.isDocumentDetected)
        assertNull(emptyDetection.detectedQuad)
        assertEquals(CameraQualityGuidance.NO_DOCUMENT_DETECTED, emptyDetection.qualityGuidance)
        assertEquals(
            0,
            composeTestRule.onAllNodesWithTag("camerax_detected_boundary_indicator").fetchSemanticsNodes().size
        )
        composeTestRule.onNodeWithTag("camerax_quality_guidance_badge").assertTextContains("Searching for Notice")
        assertEquals(0, captureTriggers)

        // 2. Poor Lighting Scene: Detects POOR_LIGHTING and blocks auto-capture
        val darkNoticeScene = createSyntheticCameraSceneBitmap(
            includeDocument = true,
            backgroundLuma = 12,
            paperLuma = 48,
            textLuma = 15
        )
        assertFalse(viewModel.analyzePreviewBitmap(darkNoticeScene, frameSequenceId = 201L))
        composeTestRule.waitForIdle()
        assertEquals(
            CameraQualityGuidance.POOR_LIGHTING,
            viewModel.uiState.value.cameraScanState.detectionState.qualityGuidance
        )
        composeTestRule.onNodeWithTag("camerax_quality_guidance_badge").assertTextContains("Low Lighting")
        composeTestRule.onNodeWithTag("camerax_quality_guidance_text").assertTextContains("Lighting is too dim", substring = true)

        // 3. Cut-Off Document Edges: Detects CUT_OFF_EDGES when notice touches frame border
        val cutOffScene = createSyntheticCameraSceneBitmap(
            includeDocument = true,
            leftNorm = 0.0f,
            topNorm = 0.14f,
            rightNorm = 0.82f,
            bottomNorm = 0.84f
        )
        assertFalse(viewModel.analyzePreviewBitmap(cutOffScene, frameSequenceId = 202L))
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.isDocumentDetected)
        assertEquals(
            CameraQualityGuidance.CUT_OFF_EDGES,
            viewModel.uiState.value.cameraScanState.detectionState.qualityGuidance
        )
        composeTestRule.onNodeWithTag("camerax_detected_boundary_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_quality_guidance_badge").assertTextContains("Edges Cut Off")

        // 4. Glare on Document: Detects GLARE_DETECTED when bright reflection washes out interior
        val glareScene = createSyntheticCameraSceneBitmap(
            includeDocument = true,
            addGlareHotspot = true
        )
        assertFalse(viewModel.analyzePreviewBitmap(glareScene, frameSequenceId = 203L))
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.isDocumentDetected)
        assertEquals(
            CameraQualityGuidance.GLARE_DETECTED,
            viewModel.uiState.value.cameraScanState.detectionState.qualityGuidance
        )
        composeTestRule.onNodeWithTag("camerax_quality_guidance_badge").assertTextContains("Glare Detected")

        // 5. Angled / Tilted Document: Detects TILTED_OR_ANGLED when rotated at 18 degrees
        val angledScene = createSyntheticCameraSceneBitmap(
            includeDocument = true,
            leftNorm = 0.22f,
            topNorm = 0.20f,
            rightNorm = 0.78f,
            bottomNorm = 0.80f,
            tiltDegrees = 18f
        )
        assertFalse(viewModel.analyzePreviewBitmap(angledScene, frameSequenceId = 204L))
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.isDocumentDetected)
        assertEquals(
            CameraQualityGuidance.TILTED_OR_ANGLED,
            viewModel.uiState.value.cameraScanState.detectionState.qualityGuidance
        )
        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.tiltDegrees >= 10f)
        composeTestRule.onNodeWithTag("camerax_quality_guidance_badge").assertTextContains("Angle / Tilt Warning")

        // 6. Blurry Document: Detects BLURRY when interior lacks crisp text edges
        val blurryScene = createSyntheticCameraSceneBitmap(
            includeDocument = true,
            isBlurry = true
        )
        assertFalse(viewModel.analyzePreviewBitmap(blurryScene, frameSequenceId = 205L))
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.isDocumentDetected)
        assertEquals(
            CameraQualityGuidance.BLURRY,
            viewModel.uiState.value.cameraScanState.detectionState.qualityGuidance
        )
        composeTestRule.onNodeWithTag("camerax_quality_guidance_badge").assertTextContains("Blur Detected")
        assertEquals("None of the poor-quality frames may trigger auto-capture", 0, captureTriggers)
    }

    @Test
    fun `phase 8 movement resets stability and auto capture triggers once when stable without repeating on same frame or during capture`() {
        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""

                override suspend fun recognizeBitmapText(pageBitmap: Bitmap): String =
                    "Scholarship Notice 2026: Post-Matric Scholarship applications open until 30 November 2026. Submit income certificate and college ID to Scholarship Cell."
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        var captureInvocations = 0

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = { captureInvocations++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()

        val crispNoticePosA = createSyntheticCameraSceneBitmap(
            includeDocument = true,
            leftNorm = 0.16f,
            topNorm = 0.15f,
            rightNorm = 0.84f,
            bottomNorm = 0.85f
        )

        // Frames 1 & 2 at Position A -> builds stability to 2/4 (50%)
        assertFalse(viewModel.analyzePreviewBitmap(crispNoticePosA, frameSequenceId = 301L))
        assertFalse(viewModel.analyzePreviewBitmap(crispNoticePosA, frameSequenceId = 302L))
        composeTestRule.waitForIdle()

        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.isDocumentDetected)
        assertEquals(2, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)
        assertEquals(0.5f, viewModel.uiState.value.cameraScanState.detectionState.stabilityProgress)
        composeTestRule.onNodeWithTag("camerax_detected_boundary_indicator").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_stability_progress").assertTextContains("50%")

        // Camera Movement: Document shifts to Position B (delta > 0.032f) -> resets consecutiveStableFrames to 0
        val movedNoticePosB = createSyntheticCameraSceneBitmap(
            includeDocument = true,
            leftNorm = 0.22f,
            topNorm = 0.21f,
            rightNorm = 0.86f,
            bottomNorm = 0.87f
        )
        assertFalse(viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 303L))
        composeTestRule.waitForIdle()
        assertEquals(
            "Movement between frames must reset stability counter to 0",
            0,
            viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames
        )
        assertEquals(
            CameraQualityGuidance.HOLD_STEADY_MOVEMENT,
            viewModel.uiState.value.cameraScanState.detectionState.qualityGuidance
        )
        assertEquals(0, captureInvocations)

        // Hold Steady at Position B for 4 consecutive frames (304, 305, 306, 307)
        assertFalse(viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 304L))
        assertEquals(1, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)

        // Duplicate Frame Check: Sending the exact same frameSequenceId (304L) again is ignored
        assertFalse(viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 304L))
        assertEquals(1, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)

        assertFalse(viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 305L))
        assertEquals(2, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)

        assertFalse(viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 306L))
        assertEquals(3, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)

        // 4th stable frame (307L) locks onto notice and triggers Auto-Capture ONCE
        val triggeredOn307 = viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 307L)
        composeTestRule.waitForIdle()

        assertTrue("4th consecutive stable high-quality frame must trigger auto-capture", triggeredOn307)
        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.isStableLocked)
        assertEquals(CameraQualityGuidance.READY_LOCKED, viewModel.uiState.value.cameraScanState.detectionState.qualityGuidance)
        assertEquals(CameraSessionStage.CAPTURING, viewModel.uiState.value.cameraScanState.sessionStage)
        assertEquals(1, captureInvocations)

        // Guard: Re-submitting frame 307L or a new frame 308L while CAPTURING must NOT trigger again
        assertFalse(viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 307L))
        assertFalse(viewModel.analyzePreviewBitmap(movedNoticePosB, frameSequenceId = 308L))
        composeTestRule.waitForIdle()
        assertEquals("Auto-capture must never trigger repeatedly while capture is processing", 1, captureInvocations)

        // Complete the auto-capture -> transitions to Review screen with "Auto-Captured" badge
        assertTrue(viewModel.onManualCaptureSucceeded(movedNoticePosB, rotationDegrees = 0))
        composeTestRule.waitForIdle()
        assertEquals(CameraSessionStage.REVIEWING_CAPTURE, viewModel.uiState.value.cameraScanState.sessionStage)
        assertTrue(viewModel.uiState.value.cameraScanState.capturedPhoto?.wasAutoCaptured == true)
        composeTestRule.onNodeWithTag("camerax_review_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_review_capture_mode_badge").assertTextContains("Auto-Captured")

        // User can Retake -> returns to preview with stability reset to 0
        composeTestRule.onNodeWithTag("camerax_retake_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()
        assertEquals(CameraSessionStage.PREVIEW_READY, viewModel.uiState.value.cameraScanState.sessionStage)
        assertEquals(0, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)

        // Accept a capture and verify existing NoticeBuddy document-processing pipeline works end-to-end
        repeat(4) { step ->
            viewModel.analyzePreviewBitmap(crispNoticePosA, frameSequenceId = 400L + step)
        }
        composeTestRule.waitForIdle()
        assertEquals(2, captureInvocations)
        assertTrue(viewModel.onManualCaptureSucceeded(crispNoticePosA, rotationDegrees = 0))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camerax_accept_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertEquals(NoticeValidationStatus.VALID_NOTICE, viewModel.uiState.value.validationGateResult?.status)
        assertNotNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)
    }

    @Test
    fun `phase 8 auto capture toggle manual capture fallback and repeated camera open close work cleanly`() {
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            mainDispatcher = Dispatchers.Unconfined
        )

        var captureInvocations = 0

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = { captureInvocations++ }
                )
            }
        }
        composeTestRule.waitForIdle()

        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()

        // 1. Toggle Auto-Capture OFF via top control pill
        assertTrue(viewModel.uiState.value.cameraScanState.isAutoCaptureEnabled)
        composeTestRule.onNodeWithTag("camerax_auto_capture_toggle_button")
            .assertIsDisplayed()
            .assertTextContains("Auto: ON")
            .performClick()
        composeTestRule.waitForIdle()

        assertFalse(viewModel.uiState.value.cameraScanState.isAutoCaptureEnabled)
        composeTestRule.onNodeWithTag("camerax_auto_capture_toggle_button").assertTextContains("Auto: OFF")

        // Feed 6 stable high-quality notice frames while Auto-Capture is OFF -> boundary locks, but auto-capture does NOT fire
        val crispNotice = createSyntheticCameraSceneBitmap(includeDocument = true)
        repeat(6) { idx ->
            val triggered = viewModel.analyzePreviewBitmap(crispNotice, frameSequenceId = 500L + idx)
            assertFalse("Auto-capture must not trigger when Auto: OFF", triggered)
        }
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.detectionState.isStableLocked)
        assertEquals(0, captureInvocations)

        // 2. Manual Capture Fallback via banner button works immediately
        composeTestRule.onNodeWithTag("camerax_manual_fallback_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, captureInvocations)
        assertEquals(CameraSessionStage.CAPTURING, viewModel.uiState.value.cameraScanState.sessionStage)
        assertTrue(viewModel.onManualCaptureSucceeded(crispNotice, rotationDegrees = 0))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("camerax_review_capture_mode_badge").assertTextContains("Manual Capture")

        // Retake poor or manual capture and re-enable Auto: ON
        composeTestRule.onNodeWithTag("camerax_retake_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.onNodeWithTag("camerax_auto_capture_toggle_button").performClick()
        composeTestRule.waitForIdle()
        assertTrue(viewModel.uiState.value.cameraScanState.isAutoCaptureEnabled)

        // 3. Repeated Opening and Closing of the Camera Screen (3 cycles)
        repeat(3) { cycle ->
            viewModel.analyzePreviewBitmap(crispNotice, frameSequenceId = 600L + cycle * 10L + 1L)
            viewModel.analyzePreviewBitmap(crispNotice, frameSequenceId = 600L + cycle * 10L + 2L)
            assertEquals(2, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)

            // Close camera screen -> unbinds camera and resets detection/stability state
            composeTestRule.onNodeWithTag("camerax_close_button").performClick()
            composeTestRule.waitForIdle()
            assertFalse(viewModel.uiState.value.cameraScanState.isCameraBound)
            assertEquals(0, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)
            assertFalse(viewModel.uiState.value.cameraScanState.detectionState.isDocumentDetected)

            // Reopen camera screen -> binds cleanly with fresh state
            viewModel.openCameraScanner()
            viewModel.onCameraPreviewBound(isFlashSupported = true)
            composeTestRule.waitForIdle()
            assertEquals(NoticeAppRoute.SCAN_NOTICE_CAMERA, viewModel.uiState.value.currentRoute)
            assertTrue(viewModel.uiState.value.cameraScanState.isCameraBound)
            assertEquals(0, viewModel.uiState.value.cameraScanState.detectionState.consecutiveStableFrames)
        }
    }

    @Test
    fun `phase 9 multi page scanning page counter thumbnail strip reordering removal and single notice submission preserve order and avoid duplicates`() {
        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""

                override suspend fun recognizeBitmapText(pageBitmap: Bitmap): String {
                    return when (pageBitmap.width) {
                        200 -> "Office of the Registrar Circular No. 901/2026: Even Semester Academic Schedule and Examination Guidelines."
                        220 -> "Eligibility & Dates: All B.Tech and M.Tech students must submit examination forms by 18 November 2025."
                        240 -> "Required Documents & Contact: Attach College ID Card and Fee Receipt. Contact hod.cs@college.edu."
                        260 -> "Addendum Annex: Late submissions after 18 November 2025 require Dean approval in Room 104."
                        else -> "Official Notice Page Content"
                    }
                }
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()

        // 1. Capture Page 1 (200x300)
        val page1Bmp = createSyntheticCameraSceneBitmap(width = 200, height = 300, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(page1Bmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        assertEquals(1, viewModel.uiState.value.cameraScanState.pageCount)
        composeTestRule.onNodeWithTag("camerax_page_counter_badge").assertTextContains("Page 1 of 1")
        composeTestRule.onNodeWithTag("camerax_page_thumbnail_0").assertIsDisplayed()

        // 2. Tap "+ Add Page" -> returns to preview with multi-page counter badge & Review Pages shortcut
        composeTestRule.onNodeWithTag("camerax_add_page_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()

        assertEquals(CameraSessionStage.PREVIEW_READY, viewModel.uiState.value.cameraScanState.sessionStage)
        assertEquals(1, viewModel.uiState.value.cameraScanState.pageCount)
        composeTestRule.onNodeWithTag("camerax_preview_page_counter_badge").assertTextContains("1 Page(s) Scanned", substring = true)
        composeTestRule.onNodeWithTag("camerax_open_review_button").assertIsDisplayed()

        // Capture Page 2 (220x300)
        val page2Bmp = createSyntheticCameraSceneBitmap(width = 220, height = 300, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(page2Bmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        assertEquals(2, viewModel.uiState.value.cameraScanState.pageCount)
        assertEquals(1, viewModel.uiState.value.cameraScanState.selectedPageIndex)
        composeTestRule.onNodeWithTag("camerax_page_counter_badge").assertTextContains("Page 2 of 2")

        // 3. Tap "+ Add Page" and capture Page 3 (240x300)
        composeTestRule.onNodeWithTag("camerax_add_page_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        val page3Bmp = createSyntheticCameraSceneBitmap(width = 240, height = 300, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(page3Bmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        assertEquals(3, viewModel.uiState.value.cameraScanState.pageCount)
        composeTestRule.onNodeWithTag("camerax_page_counter_badge").assertTextContains("Page 3 of 3")
        composeTestRule.onNodeWithTag("camerax_page_thumbnail_0").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_page_thumbnail_1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("camerax_page_thumbnail_2").assertIsDisplayed()

        // 4. Reorder Page 3 earlier (to index 1) -> order becomes [200, 240, 220], then move it back later -> [200, 220, 240]
        composeTestRule.onNodeWithTag("camerax_move_page_left_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, viewModel.uiState.value.cameraScanState.selectedPageIndex)
        assertEquals(
            listOf(200, 240, 220),
            viewModel.uiState.value.cameraScanState.scannedPages.map { it.widthPx }
        )

        composeTestRule.onNodeWithTag("camerax_move_page_right_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, viewModel.uiState.value.cameraScanState.selectedPageIndex)
        assertEquals(
            listOf(200, 220, 240),
            viewModel.uiState.value.cameraScanState.scannedPages.map { it.widthPx }
        )

        // 5. Remove Page 3 and add a new Page 3 (260x300) -> verify no duplicate or ghost pages
        composeTestRule.onNodeWithTag("camerax_remove_page_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, viewModel.uiState.value.cameraScanState.pageCount)
        composeTestRule.onNodeWithTag("camerax_page_counter_badge").assertTextContains("Page 2 of 2")

        composeTestRule.onNodeWithTag("camerax_add_page_button").performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        val newPage3Bmp = createSyntheticCameraSceneBitmap(width = 260, height = 300, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(newPage3Bmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        assertEquals(3, viewModel.uiState.value.cameraScanState.pageCount)
        assertEquals(
            listOf(200, 220, 260),
            viewModel.uiState.value.cameraScanState.scannedPages.map { it.widthPx }
        )

        // 6. Finalize ("Use 3 Pages") -> submits all 3 pages as one ordered notice document
        composeTestRule.onNodeWithTag("camerax_accept_button")
            .assertTextContains("Use 3 Pages")
            .performClick()
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertEquals(3, viewModel.uiState.value.selectedImageDraft?.pageCount)
        assertEquals(3, viewModel.uiState.value.lastPdfExtractionResult?.totalPageCount)
        assertEquals(NoticeValidationStatus.VALID_NOTICE, viewModel.uiState.value.validationGateResult?.status)
        assertNotNull(viewModel.uiState.value.activeConfirmedEntry?.descriptionResult)

        // Verify combined text preserves exact page order [Page 1], [Page 2], [Page 3] without duplicates
        val combinedText = viewModel.uiState.value.activeConfirmedEntry?.extractedPdfText.orEmpty()
        val p1Index = combinedText.indexOf("[Page 1]\nOffice of the Registrar Circular No. 901/2026")
        val p2Index = combinedText.indexOf("[Page 2]\nEligibility & Dates: All B.Tech and M.Tech students")
        val p3Index = combinedText.indexOf("[Page 3]\nAddendum Annex: Late submissions after 18 November 2025")
        assertTrue("Page 1 content must be present", p1Index >= 0)
        assertTrue("Page 2 content must follow Page 1", p2Index > p1Index)
        assertTrue("Page 3 content must follow Page 2", p3Index > p2Index)
        assertFalse("Removed page content must not appear in final document", combinedText.contains("Attach College ID Card and Fee Receipt"))
    }

    @Test
    fun `phase 9 crop rotate perspective correction reset and in place page retake preserve readability without corrupting original image`() {
        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""

                override suspend fun recognizeBitmapText(pageBitmap: Bitmap): String =
                    "Government Scholarship Notification 2026: Applications invited from eligible undergraduate students before 25 December 2026."
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)

        // Capture Page 1 (200x300) and Page 2 (220x320)
        val page1Bmp = createSyntheticCameraSceneBitmap(width = 200, height = 300, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(page1Bmp, rotationDegrees = 0))
        viewModel.onStartAddAnotherPage()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        val page2Bmp = createSyntheticCameraSceneBitmap(width = 220, height = 320, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(page2Bmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        // Select Page 1 via thumbnail strip
        composeTestRule.onNodeWithTag("camerax_page_thumbnail_0").performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, viewModel.uiState.value.cameraScanState.selectedPageIndex)
        val originalPage1Bytes = viewModel.uiState.value.cameraScanState.activeScannedPage!!.originalPngBytes

        // 1. Rotate Right 90° -> dimensions swap from 200x300 to 300x200
        composeTestRule.onNodeWithTag("camerax_rotate_right_button").performClick()
        composeTestRule.waitForIdle()
        val rotatedPage = viewModel.uiState.value.cameraScanState.activeScannedPage!!
        assertEquals(90, rotatedPage.editState.extraRotationDegrees)
        assertEquals(300, rotatedPage.widthPx)
        assertEquals(200, rotatedPage.heightPx)
        composeTestRule.onNodeWithTag("camerax_active_edits_badge").assertTextContains("90°", substring = true)

        // 2. Toggle Crop ON -> dimensions reduce cleanly without corrupting image
        composeTestRule.onNodeWithTag("camerax_crop_button").performClick()
        composeTestRule.waitForIdle()
        val croppedPage = viewModel.uiState.value.cameraScanState.activeScannedPage!!
        assertTrue(croppedPage.editState.isCropped)
        assertTrue(croppedPage.widthPx in 64 until 300)
        assertTrue(croppedPage.heightPx in 64 until 200)
        composeTestRule.onNodeWithTag("camerax_active_edits_badge").assertTextContains("Cropped", substring = true)

        // 3. Toggle Perspective Correction ON -> rectifies document quadrilateral
        composeTestRule.onNodeWithTag("camerax_perspective_button").performClick()
        composeTestRule.waitForIdle()
        val perspectivePage = viewModel.uiState.value.cameraScanState.activeScannedPage!!
        assertTrue(perspectivePage.editState.isPerspectiveCorrected)
        assertNotNull(perspectivePage.editState.perspectiveQuad)
        assertTrue(perspectivePage.widthPx >= 64 && perspectivePage.heightPx >= 64)
        composeTestRule.onNodeWithTag("camerax_active_edits_badge").assertTextContains("Rectified", substring = true)

        // 4. Reset Edits -> restores exact original 200x300 dimensions and original lossless PNG bytes
        composeTestRule.onNodeWithTag("camerax_reset_page_edits_button").performClick()
        composeTestRule.waitForIdle()
        val resetPage = viewModel.uiState.value.cameraScanState.activeScannedPage!!
        assertFalse(resetPage.editState.hasEdits)
        assertEquals(200, resetPage.widthPx)
        assertEquals(300, resetPage.heightPx)
        assertTrue(originalPage1Bytes.contentEquals(resetPage.currentPngBytes))

        // 5. Select Page 2 and tap "Retake P2" -> replaces Page 2 in-place without losing Page 1
        composeTestRule.onNodeWithTag("camerax_page_thumbnail_1").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, viewModel.uiState.value.cameraScanState.selectedPageIndex)
        composeTestRule.onNodeWithTag("camerax_retake_button")
            .assertTextContains("Retake P2")
            .performClick()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        composeTestRule.waitForIdle()

        assertEquals(CameraSessionStage.PREVIEW_READY, viewModel.uiState.value.cameraScanState.sessionStage)
        composeTestRule.onNodeWithTag("camerax_preview_page_counter_badge")
            .assertTextContains("Retaking Page 2 of 2")

        val replacementPage2Bmp = createSyntheticCameraSceneBitmap(width = 250, height = 350, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(replacementPage2Bmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        assertEquals(2, viewModel.uiState.value.cameraScanState.pageCount)
        assertEquals(1, viewModel.uiState.value.cameraScanState.selectedPageIndex)
        assertEquals(
            listOf(200, 250),
            viewModel.uiState.value.cameraScanState.scannedPages.map { it.widthPx }
        )
    }

    @Test
    fun `phase 9 ocr check quality warnings for unreadable or incomplete pages ocr failure handling and validation gate enforcement`() {
        var simulateOcrException = false
        var nonNoticeMode = false

        val extractor = PdfNoticeExtractor(
            context = application,
            ocrEngine = object : PdfOcrEngine {
                override suspend fun recognizePageText(
                    pdfFile: File,
                    pageNumberOneBased: Int,
                    pageRotationDegrees: Int,
                    maxImageDimensionPx: Int
                ): String = ""

                override suspend fun recognizeBitmapText(pageBitmap: Bitmap): String {
                    if (simulateOcrException) {
                        throw IllegalStateException("Simulated ML Kit OCR runtime failure")
                    }
                    if (nonNoticeMode) {
                        return """
                            TAX INVOICE #INV-7741
                            Billed To: Priya Verma
                            1x Office Chair - Rs. 6,500.00
                            CGST 9%: Rs. 585.00 | SGST 9%: Rs. 585.00
                            Grand Total Paid: Rs. 7,670.00
                        """.trimIndent()
                    }
                    return when (pageBitmap.width) {
                        200 -> "Office of the Principal Circular No. 512/2026: Final Semester Computer Science Project Viva on 18 November 2025. Bring College ID Card and Project Report."
                        210 -> "" // Unreadable / blank page
                        220 -> "Page 3" // Incomplete / very short text
                        else -> "Valid official circular text for students."
                    }
                }
            },
            ioDispatcher = Dispatchers.Unconfined
        )

        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = NoticeAnalysisService(
                aiGateway = FakeSuccessAiGateway(),
                ioDispatcher = Dispatchers.Unconfined
            ),
            pdfExtractor = extractor,
            mainDispatcher = Dispatchers.Unconfined
        )

        composeTestRule.setContent {
            Box(modifier = Modifier.size(width = 412.dp, height = 892.dp)) {
                NoticeMateHomeApp(
                    viewModel = viewModel,
                    enableHardwareCameraBinding = false,
                    customCameraCaptureTrigger = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)

        // 1. Capture Page 1 (readable notice, 200x300) and run OCR Check -> READABLE
        val readableBmp = createSyntheticCameraSceneBitmap(width = 200, height = 300, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(readableBmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("camerax_ocr_preview_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            PageOcrPreviewStatus.READABLE,
            viewModel.uiState.value.cameraScanState.activeScannedPage?.ocrPreviewStatus
        )
        composeTestRule.onNodeWithTag("camerax_ocr_preview_status").assertTextContains("OCR Readable", substring = true)

        // 2. Add Page 2 (dark / unreadable page, 210x300) -> triggers quality warning banner & UNREADABLE OCR status
        viewModel.onStartAddAnotherPage()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        val unreadableBmp = createSyntheticCameraSceneBitmap(
            width = 210,
            height = 300,
            includeDocument = true,
            backgroundLuma = 10,
            paperLuma = 42,
            textLuma = 15
        )
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(unreadableBmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("camerax_page_quality_warning_banner").assertIsDisplayed()

        // Simulate OCR failure first -> OCR_FAILED status with retry guidance
        simulateOcrException = true
        composeTestRule.onNodeWithTag("camerax_ocr_preview_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            PageOcrPreviewStatus.OCR_FAILED,
            viewModel.uiState.value.cameraScanState.activeScannedPage?.ocrPreviewStatus
        )
        composeTestRule.onNodeWithTag("camerax_ocr_preview_status").assertTextContains("OCR Error")

        // Recover from OCR exception and run OCR check again -> UNREADABLE status on blank/dark Page 2
        simulateOcrException = false
        composeTestRule.onNodeWithTag("camerax_ocr_preview_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            PageOcrPreviewStatus.UNREADABLE,
            viewModel.uiState.value.cameraScanState.activeScannedPage?.ocrPreviewStatus
        )
        composeTestRule.onNodeWithTag("camerax_ocr_preview_status").assertTextContains("Unreadable Page")

        // 3. Add Page 3 (incomplete low-text page, 220x300) -> INCOMPLETE_OR_LOW_TEXT status
        viewModel.onStartAddAnotherPage()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        val incompleteBmp = createSyntheticCameraSceneBitmap(width = 220, height = 300, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(incompleteBmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("camerax_ocr_preview_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(
            PageOcrPreviewStatus.INCOMPLETE_OR_LOW_TEXT,
            viewModel.uiState.value.cameraScanState.activeScannedPage?.ocrPreviewStatus
        )
        composeTestRule.onNodeWithTag("camerax_ocr_preview_status").assertTextContains("Low Text Warning")

        // 4. Submit the 3-page notice -> succeeds using readable Page 1 while surfacing incomplete-page warning for Page 2
        composeTestRule.onNodeWithTag("camerax_accept_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertEquals(NoticeValidationStatus.VALID_NOTICE, viewModel.uiState.value.validationGateResult?.status)
        val result = viewModel.uiState.value.activeConfirmedEntry?.descriptionResult
        assertNotNull(result)
        assertTrue(
            "Incomplete/unreadable page warning must be preserved in missingOrAmbiguousInfo",
            result!!.missingOrAmbiguousInfo.any { it.contains("Page 2 had no readable text") }
        )

        // 5. Multi-page Non-Notice (2-page Invoice) must be blocked by Phase 5 Notice-Only Validation Gate
        nonNoticeMode = true
        viewModel.openCameraScanner()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(readableBmp, rotationDegrees = 0))
        viewModel.onStartAddAnotherPage()
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(incompleteBmp, rotationDegrees = 0))
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("camerax_accept_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(NoticeAppRoute.UPLOAD_IMAGE_CONFIRMATION, viewModel.uiState.value.currentRoute)
        assertEquals(
            NoticeValidationStatus.NOT_A_NOTICE,
            viewModel.uiState.value.validationGateResult?.status
        )
        assertNull(
            "Rejected multi-page document must never proceed to notice summarization",
            viewModel.uiState.value.activeConfirmedEntry?.descriptionResult
        )
    }

    @Test
    fun `phase 10 verification audit confirms branding bracketed delimiter stripping multi page navigation cleanup and structured parsing`() = runBlocking {
        // 1. Verify NoticeBuddy branding consistency in resources
        assertEquals("NoticeBuddy", application.getString(R.string.app_name))

        // 2. Verify Defect 1 fix: Bracketed [Page N] delimiters from multi-page camera OCR are stripped
        // before readability & classification checks so unreadable fragments are not inflated by "Page" letters
        val unreadableMultiPageOcr = "[Page 1]\n12\n\n--- Page Break ---\n\n[Page 2]\n34"
        val gateResult = NoticeValidationGate.analyzeContentAndContext(unreadableMultiPageOcr)
        assertEquals(NoticeValidationStatus.UNREADABLE_DOCUMENT, gateResult.status)
        assertFalse(gateResult.isAllowedForNoticeAnalysis)

        // 3. Verify structured AI response parsing (markdown code fences, bilingual fields, and fallback lists)
        val fencedRawJson = """
            ```json
            {
              "shortTitle": "Scholarship Renewal Circular 2026",
              "titleHi": "छात्रवृत्ति नवीनीकरण परिपत्र 2026",
              "summary": "All eligible scholars must submit renewal applications by 25 November 2026.",
              "summaryHi": "सभी पात्र छात्रों को 25 नवंबर 2026 तक नवीनीकरण आवेदन जमा करना होगा।",
              "purpose": "Notify students regarding annual scholarship renewal deadlines.",
              "eligibility": " Undergraduate and Postgraduate scholarship recipients. ",
              "eligibilityHi": " स्नातक और स्नातकोत्तर छात्रवृत्ति प्राप्तकर्ता। ",
              "importantDates": ["25 Nov 2026 - Last date for online renewal"],
              "importantDatesHi": ["25 नवंबर 2026 - ऑनलाइन नवीनीकरण की अंतिम तिथि"],
              "requiredDocuments": ["Previous Semester Marksheet", "Bonafide Certificate"],
              "requiredDocumentsHi": ["पिछले सेमेस्टर की अंकतालिका", "बोनाफाइड प्रमाण पत्र"],
              "contactDetails": "scholarship.cell@university.ac.in",
              "nextSteps": ["Upload documents on the portal", "Submit hard copy to Room 104"],
              "nextStepsHi": ["पोर्टल पर दस्तावेज़ अपलोड करें", "कमरा 104 में हार्ड कॉपी जमा करें"],
              "missingOrAmbiguousInfo": [],
              "factsVsInterpretationsNote": "Grounded directly in the circular."
            }
            ```
        """.trimIndent()
        val service = NoticeAnalysisService(
            aiGateway = FakeSuccessAiGateway(),
            ioDispatcher = Dispatchers.Unconfined
        )
        val parsed = service.parseGroundedResponse(
            rawResponse = fencedRawJson,
            extractionMethod = ExtractionSourceMethod.PDF_SELECTABLE_TEXT,
            combinedWarning = null
        ).getOrThrow()
        assertEquals("Scholarship Renewal Circular 2026", parsed.shortTitle)
        assertEquals("छात्रवृत्ति नवीनीकरण परिपत्र 2026", parsed.titleHi)
        assertEquals(listOf("25 Nov 2026 - Last date for online renewal"), parsed.importantDates)
        assertEquals(listOf("25 नवंबर 2026 - ऑनलाइन नवीनीकरण की अंतिम तिथि"), parsed.importantDatesHi)
        assertEquals(2, parsed.requiredDocuments.size)
        assertEquals(2, parsed.nextSteps.size)

        // 4. Verify Defect 2 fix: Multi-page camera session navigation cleanup and Back from "+ Add Page" preview
        val networkMonitor = ControllableNetworkConnectivityMonitor(initialStatus = NetworkConnectivityStatus.ONLINE)
        val viewModel = HomePhaseViewModel(
            storage = storage,
            analysisService = service,
            networkMonitor = networkMonitor,
            mainDispatcher = Dispatchers.Unconfined
        )

        viewModel.openCameraScanner()
        viewModel.onCameraPermissionResult(isGranted = true)
        viewModel.onCameraPreviewBound(isFlashSupported = true)

        val page1Bmp = createSyntheticCameraSceneBitmap(width = 160, height = 220, includeDocument = true)
        assertTrue(viewModel.onManualCaptureStarted())
        assertTrue(viewModel.onManualCaptureSucceeded(page1Bmp, rotationDegrees = 0))
        assertEquals(1, viewModel.uiState.value.cameraScanState.pageCount)
        assertEquals(CameraSessionStage.REVIEWING_CAPTURE, viewModel.uiState.value.cameraScanState.sessionStage)

        // Tap "+ Add Page" to enter live preview with 1 existing page held in session
        assertTrue(viewModel.onStartAddAnotherPage())
        viewModel.onCameraPreviewBound(isFlashSupported = true)
        assertNull(viewModel.uiState.value.cameraScanState.capturedPhoto)
        assertEquals(1, viewModel.uiState.value.cameraScanState.pageCount)

        // Pressing Back while in "+ Add Page" preview returns to multi-page review instead of losing the captured page
        assertTrue(viewModel.navigateBack())
        assertEquals(NoticeAppRoute.SCAN_NOTICE_CAMERA, viewModel.uiState.value.currentRoute)
        assertEquals(CameraSessionStage.REVIEWING_CAPTURE, viewModel.uiState.value.cameraScanState.sessionStage)
        assertNotNull(viewModel.uiState.value.cameraScanState.capturedPhoto)
        assertEquals(1, viewModel.uiState.value.cameraScanState.pageCount)

        // Abandoning the camera session via Home clears scannedPages so reopening camera starts fresh
        viewModel.onHomeButtonClicked()
        assertEquals(NoticeAppRoute.HOME, viewModel.uiState.value.currentRoute)
        assertEquals(0, viewModel.uiState.value.cameraScanState.pageCount)
        assertNull(viewModel.uiState.value.cameraScanState.capturedPhoto)

        viewModel.openCameraScanner()
        assertEquals(NoticeAppRoute.SCAN_NOTICE_CAMERA, viewModel.uiState.value.currentRoute)
        assertEquals(0, viewModel.uiState.value.cameraScanState.pageCount)
        assertNull(viewModel.uiState.value.cameraScanState.capturedPhoto)
    }

    private fun createSyntheticCameraSceneBitmap(
        width: Int = 120,
        height: Int = 160,
        includeDocument: Boolean = true,
        backgroundLuma: Int = 24,
        paperLuma: Int = 226,
        textLuma: Int = 28,
        leftNorm: Float = 0.16f,
        topNorm: Float = 0.15f,
        rightNorm: Float = 0.84f,
        bottomNorm: Float = 0.85f,
        tiltDegrees: Float = 0f,
        addGlareHotspot: Boolean = false,
        isBlurry: Boolean = false
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bgColor = Color.rgb(backgroundLuma, backgroundLuma, backgroundLuma)
        if (!includeDocument) {
            bmp.eraseColor(bgColor)
            return bmp
        }

        val cx = ((leftNorm + rightNorm) * 0.5f) * width
        val cy = ((topNorm + bottomNorm) * 0.5f) * height
        val halfW = ((rightNorm - leftNorm) * 0.5f) * width
        val halfH = ((bottomNorm - topNorm) * 0.5f) * height
        val rad = Math.toRadians(-tiltDegrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - cx
                val dy = y - cy
                val ux = dx * cosA - dy * sinA
                val uy = dx * sinA + dy * cosA

                if (kotlin.math.abs(ux) <= halfW && kotlin.math.abs(uy) <= halfH) {
                    val relX = (ux + halfW) / (2f * halfW).coerceAtLeast(1f)
                    val relY = (uy + halfH) / (2f * halfH).coerceAtLeast(1f)
                    val inGlareSpot = addGlareHotspot && relX in 0.20f..0.78f && relY in 0.20f..0.78f
                    val isTextStripe = !isBlurry &&
                        !inGlareSpot &&
                        relX in 0.12f..0.88f &&
                        relY in 0.12f..0.88f &&
                        ((uy + halfH).toInt() % 5 == 2)

                    val pxLuma = when {
                        inGlareSpot -> 255
                        isTextStripe -> textLuma
                        else -> paperLuma
                    }
                    bmp.setPixel(x, y, Color.rgb(pxLuma, pxLuma, pxLuma))
                } else {
                    bmp.setPixel(x, y, bgColor)
                }
            }
        }
        return bmp
    }

    private fun createSelectablePdfBytes(pages: List<String>): ByteArray {
        return buildSpecCompliantPdfBytes(
            pages = pages,
            pageRotations = List(pages.size) { 0 }
        )
    }

    private fun createBlankScannedPdfBytes(pageCount: Int, secondPageRotation: Int): ByteArray {
        val rotations = (1..pageCount).map { pageNum ->
            if (pageNum == 2) secondPageRotation else 0
        }
        return buildSpecCompliantPdfBytes(
            pages = List(pageCount) { "" },
            pageRotations = rotations
        )
    }

    private fun buildSpecCompliantPdfBytes(
        pages: List<String>,
        pageRotations: List<Int>
    ): ByteArray {
        val sb = StringBuilder()
        val offsets = mutableMapOf<Int, Int>()

        fun appendObj(id: Int, body: String) {
            offsets[id] = sb.toString().toByteArray(Charsets.ISO_8859_1).size
            sb.append("$id 0 obj\n$body\nendobj\n")
        }

        sb.append("%PDF-1.4\n")
        appendObj(1, "<< /Type /Catalog /Pages 2 0 R >>")

        val pageCount = pages.size
        val firstPageObjId = 4
        val pageObjIds = (0 until pageCount).map { firstPageObjId + (it * 2) }
        val kidsRef = pageObjIds.joinToString(" ") { "$it 0 R" }

        appendObj(2, "<< /Type /Pages /Kids [ $kidsRef ] /Count $pageCount >>")
        appendObj(3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

        for (i in 0 until pageCount) {
            val pageObjId = firstPageObjId + (i * 2)
            val contentObjId = pageObjId + 1
            val text = pages[i]
            val rotation = pageRotations.getOrElse(i) { 0 }
            val rotateEntry = if (rotation != 0) " /Rotate $rotation" else ""

            val streamContent = if (text.isNotBlank()) {
                val escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
                "BT\n/F1 12 Tf\n50 700 Td\n($escaped) Tj\nET"
            } else {
                ""
            }

            appendObj(
                pageObjId,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792]$rotateEntry /Resources << /Font << /F1 3 0 R >> >> /Contents $contentObjId 0 R >>"
            )
            appendObj(
                contentObjId,
                "<< /Length ${streamContent.length} >>\nstream\n$streamContent\nendstream"
            )
        }

        val totalObjects = 3 + (pageCount * 2) + 1
        val xrefOffset = sb.toString().toByteArray(Charsets.ISO_8859_1).size
        sb.append("xref\n0 $totalObjects\n")
        sb.append("0000000000 65535 f \n")
        for (objId in 1 until totalObjects) {
            val off = offsets[objId] ?: 0
            sb.append(String.format(Locale.US, "%010d 00000 n \n", off))
        }
        sb.append("trailer\n<< /Size $totalObjects /Root 1 0 R >>\n")
        sb.append("startxref\n$xrefOffset\n%%EOF\n")
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        private val DEFAULT_GROUNDED_JSON = """
            {
              "shortTitle": "CS Project Viva Schedule",
              "titleHi": "कंप्यूटर साइंस प्रोजेक्ट वाइवा समय-सारणी",
              "summary": "Final Semester Project Viva scheduled for Computer Science students on 18 November 2025.",
              "summaryHi": "कंप्यूटर साइंस के छात्रों के लिए 18 नवंबर 2025 को प्रोजेक्ट वाइवा निर्धारित है।",
              "purpose": "Announce the Final Semester Project Viva schedule and required documents for Computer Science students.",
              "eligibility": "Final Semester Computer Science students.",
              "eligibilityHi": "अंतिम सेमेस्टर के कंप्यूटर साइंस छात्र।",
              "importantDates": ["18th Nov 2025 - Final Semester Project Viva"],
              "importantDatesHi": ["18 नवंबर 2025 - अंतिम सेमेस्टर प्रोजेक्ट वाइवा"],
              "requiredDocuments": ["College ID Card", "Project Report"],
              "requiredDocumentsHi": ["कॉलेज आईडी कार्ड", "प्रोजेक्ट रिपोर्ट"],
              "contactDetails": "hod.cs@college.edu",
              "nextSteps": [
                "Prepare the final Project Report and bring your College ID Card.",
                "Attend the Project Viva on 18th Nov 2025."
              ],
              "nextStepsHi": [
                "अपनी प्रोजेक्ट रिपोर्ट और कॉलेज आईडी कार्ड तैयार रखें।",
                "18 नवंबर 2025 को वाइवा में शामिल हों।"
              ],
              "missingOrAmbiguousInfo": [
                "Exact reporting time and room number for the viva are not stated in the notice."
              ],
              "factsVsInterpretationsNote": "Dates, documents, and contact email are stated directly in the notice; reporting time is noted as unstated."
            }
        """.trimIndent()
    }
}
