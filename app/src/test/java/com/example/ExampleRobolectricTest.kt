package com.example

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppThemeMode
import com.example.data.local.RecentNoticeStorage
import com.example.data.model.ExtractionSourceMethod
import com.example.data.model.NoticeDescriptionResult
import com.example.data.model.NoticeLanguage
import com.example.data.model.PdfExtractionConfig
import com.example.data.pdf.PdfNoticeExtractor
import com.example.data.pdf.PdfOcrEngine
import com.example.data.remote.HttpNoticeAiGateway
import com.example.data.remote.NoticeAiGateway
import com.example.data.remote.NoticeAnalysisException
import com.example.data.remote.NoticeAnalysisService
import com.example.ui.viewmodel.HomePhaseViewModel
import com.example.ui.viewmodel.NoticeAppRoute
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

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
