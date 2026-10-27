package com.example.data.remote

import com.example.data.model.DetectedDocumentCategory
import com.example.data.model.NoticeValidationGateResult
import com.example.data.model.NoticeValidationStatus
import com.example.data.pdf.PdfNoticeExtractor
import org.json.JSONObject

class NoticeValidationGateException(
    val gateResult: NoticeValidationGateResult,
    cause: Throwable? = null
) : NoticeAnalysisException(gateResult.userMessageEn, cause)

/**
 * Phase 5 Notice-Only Validation Gate:
 * Classifies documents using their actual content, structure, and communicative context
 * (never relying on filename or AI prompt instructions alone) and enforces that only
 * legitimate notices proceed to normal notice summarization.
 */
object NoticeValidationGate {

    const val MIN_CONFIDENCE_THRESHOLD = 0.60

    val DOCUMENT_CLASSIFICATION_SYSTEM_PROMPT = """
        You are NoticeBuddy's Document Validation Classifier.
        Your ONLY task is to classify whether the provided document content is a legitimate notice before any summarization occurs.

        CLASSIFICATION RULES:
        1. Classify strictly using the actual content, structure, and communicative context of the document—NOT its filename, title alone, isolated keywords, or logos.
        2. Legitimate notice types ("VALID_NOTICE") include:
           - Government notifications, gazettes, and public orders ("GOVERNMENT_NOTIFICATION")
           - School, college, university, hostel, or academic circulars ("SCHOOL_OR_COLLEGE_CIRCULAR")
           - Scholarship, fellowship, or financial aid circulars ("SCHOLARSHIP_NOTICE")
           - Office notices, memos, HR circulars, and administrative advisories ("OFFICE_NOTICE")
           - Public announcements, civic/utility maintenance alerts, and facility closure notices ("PUBLIC_ANNOUNCEMENT")
           - Official instructions, examination schedules, document verification checklists, and compliance directives ("OFFICIAL_INSTRUCTION")
        3. Confirmed non-notices ("NOT_A_NOTICE") include:
           - Essays, academic research papers, opinion pieces, textbook chapters ("ESSAY_OR_ACADEMIC_PAPER")
           - Novels, short stories, poems, drama scripts, literary narratives ("NOVEL_OR_LITERARY_TEXT")
           - Commercial invoices, tax bills, purchase receipts, bank statements ("INVOICE_OR_BILL")
           - Personal resumes, CVs, job applications, bio-data ("RESUME_OR_CV")
           - Recipes, source code, product manuals, personal letters, shopping lists, or unrelated documents ("IRRELEVANT_DOCUMENT")
           Note: Even if a resume says "Notice Period: 30 days", an invoice says "Payment Notice", a novel character reads a notice, or an essay discusses government circulars, classify it as "NOT_A_NOTICE" based on its overall content and context.
        4. If the extracted text is too fragmentary, blurry, incomplete, or ambiguous to determine with confidence whether it is a notice, return "NEEDS_CLEARER_INPUT" ("INSUFFICIENT_OR_BLURRY"). Do NOT guess or invent a classification.
        5. If the document has no readable text, is completely blank, or consists of illegible OCR noise, return "UNREADABLE_DOCUMENT" ("UNREADABLE").

        Respond ONLY with a valid JSON object matching this exact structure:
        {
          "classificationStatus": "VALID_NOTICE | NOT_A_NOTICE | NEEDS_CLEARER_INPUT | UNREADABLE_DOCUMENT",
          "documentCategory": "GOVERNMENT_NOTIFICATION | SCHOOL_OR_COLLEGE_CIRCULAR | SCHOLARSHIP_NOTICE | OFFICE_NOTICE | PUBLIC_ANNOUNCEMENT | OFFICIAL_INSTRUCTION | ESSAY_OR_ACADEMIC_PAPER | NOVEL_OR_LITERARY_TEXT | INVOICE_OR_BILL | RESUME_OR_CV | IRRELEVANT_DOCUMENT | INSUFFICIENT_OR_BLURRY | UNREADABLE",
          "confidence": 0.95,
          "reasonEn": "Clear, polite explanation in simple English.",
          "reasonHi": "सरल हिंदी में स्पष्ट और विनम्र विवरण।"
        }
    """.trimIndent()

    /**
     * Pre-AI check for unreadable, blank, corrupted, or severely blurry/fragmentary text.
     * Distinguishes unreadable documents ("UNREADABLE_DOCUMENT") and insufficient/blurry scans
     * ("NEEDS_CLEARER_INPUT") from confirmed non-notices ("NOT_A_NOTICE").
     */
    fun checkPreReadabilityAndBlur(rawText: String): NoticeValidationGateResult? {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return buildUnreadableResult(
                detail = "Extracted text is empty."
            )
        }

        val stripped = stripPageDelimiters(trimmed)
        if (stripped.isBlank()) {
            return buildUnreadableResult(
                detail = "Document contains only page delimiters with no readable text."
            )
        }

        val letterCount = stripped.count { it.isLetter() || it in '\u0900'..'\u097F' }
        if (letterCount < 6) {
            return buildUnreadableResult(
                detail = "Document contains fewer than 6 readable letters ($letterCount found)."
            )
        }

        if (isCorruptedFontNoise(stripped)) {
            return buildUnreadableResult(
                detail = "Extracted text contains corrupted font encoding or unreadable symbol noise."
            )
        }

        val lower = stripped.lowercase()
        val explicitBlurMarkers = listOf(
            "[blurry]",
            "[illegible]",
            "[unreadable]",
            "blurry scan",
            "illegible scan",
            "faint scan",
            "text is too blurry",
            "unreadable low-resolution"
        )
        if (explicitBlurMarkers.any { lower.contains(it) }) {
            return buildNeedsClearerInputResult(
                detail = "Scan quality is too blurry or illegible to verify notice content."
            )
        }

        // Check token fragmentation typical of a blurry or cut-off scan
        val tokens = stripped.split(Regex("\\s+")).filter { it.isNotBlank() }
        val meaningfulWords = tokens.filter { token ->
            val cleanToken = token.trim('.', ',', ';', ':', '-', '—', '(', ')', '[', ']', '"', '\'')
            val chars = cleanToken.count { it.isLetter() || it in '\u0900'..'\u097F' }
            chars >= 3
        }

        if (tokens.size >= 5 && meaningfulWords.size <= 2) {
            return buildNeedsClearerInputResult(
                detail = "Extracted text consists mostly of isolated fragments (${meaningfulWords.size} readable words out of ${tokens.size} tokens)."
            )
        }

        return null
    }

    private fun isCorruptedFontNoise(text: String): Boolean {
        val cleaned = text.trim()
        if (cleaned.length < 10) return false
        val corruptedGlyphs = setOf('*', '~', '^', '€', '\\')
        val corruptedCount = cleaned.count { it in corruptedGlyphs }
        val hasFfiSpam = Regex("ffi\\W*ffi", RegexOption.IGNORE_CASE).containsMatchIn(cleaned)
        return hasFfiSpam || (corruptedCount >= 6 && corruptedCount.toDouble() / cleaned.length.toDouble() > 0.04)
    }

    /**
     * Multi-signal content & context structural classifier.
     * Inspects the document's discourse patterns, section headings, communicative intent,
     * and audience directives—completely ignoring the filename.
     */
    fun analyzeContentAndContext(rawText: String): NoticeValidationGateResult {
        val preCheck = checkPreReadabilityAndBlur(rawText)
        if (preCheck != null) return preCheck

        val stripped = stripPageDelimiters(rawText.trim())
        val lower = stripped.lowercase()
        val tokens = stripped.split(Regex("\\s+")).filter { it.isNotBlank() }
        val meaningfulWords = tokens.filter { token ->
            val clean = token.trim('.', ',', ';', ':', '-', '—', '(', ')', '[', ']', '"', '\'')
            clean.count { it.isLetter() || it in '\u0900'..'\u097F' } >= 3
        }

        //Insufficient extracted text (< 4 meaningful words) cannot be reliably classified
        if (meaningfulWords.size < 4 || stripped.length < 20) {
            return buildNeedsClearerInputResult(
                detail = "Extracted text is too brief (${meaningfulWords.size} meaningful words) to verify whether it is a notice."
            )
        }

        // --- 1. Non-Notice Genre Context Scores ---
        val resumeScore = scoreResumeContext(lower)
        val invoiceScore = scoreInvoiceContext(lower)
        val novelScore = scoreNovelContext(lower, stripped)
        val essayScore = scoreEssayContext(lower)
        val irrelevantScore = scoreIrrelevantContext(lower)

        // --- 2. Legitimate Notice Context Score ---
        val authorityScore = scoreAuthorityContext(lower)
        val audienceScore = scoreAudienceContext(lower)
        val directiveScore = scoreDirectiveAndScheduleContext(lower)
        val framingScore = scoreFramingMarkers(lower)
        val noticeContextScore = authorityScore + audienceScore + directiveScore + framingScore

        val genreScores = listOf(
            DetectedDocumentCategory.RESUME_OR_CV to resumeScore,
            DetectedDocumentCategory.INVOICE_OR_BILL to invoiceScore,
            DetectedDocumentCategory.NOVEL_OR_LITERARY_TEXT to novelScore,
            DetectedDocumentCategory.ESSAY_OR_ACADEMIC_PAPER to essayScore,
            DetectedDocumentCategory.IRRELEVANT_DOCUMENT to irrelevantScore
        )
        val (topNonNoticeCategory, maxNonNoticeScore) = genreScores.maxByOrNull { it.second }!!

        // Rule A: Strong non-notice genre structure dominates (even if incidental words like "notice" or "scholarship" appear)
        if (maxNonNoticeScore >= 4 && (maxNonNoticeScore > noticeContextScore || maxNonNoticeScore >= 6)) {
            return buildNotANoticeResult(
                category = topNonNoticeCategory,
                confidence = (0.78 + (maxNonNoticeScore.coerceAtMost(12) * 0.015)).coerceAtMost(0.98),
                detail = "Detected ${topNonNoticeCategory.displayLabelEn} structure (nonNoticeScore=$maxNonNoticeScore, noticeScore=$noticeContextScore)."
            )
        }

        // Rule B: Legitimate notice context is present and not outweighed by non-notice genres
        if (noticeContextScore >= 2 && maxNonNoticeScore < 4) {
            val noticeCategory = inferLegitimateNoticeCategory(lower)
            val confidence = (0.72 + (noticeContextScore.coerceAtMost(12) * 0.02)).coerceAtMost(0.98)
            return NoticeValidationGateResult(
                status = NoticeValidationStatus.VALID_NOTICE,
                category = noticeCategory,
                confidence = confidence,
                userMessageEn = "Verified as a ${noticeCategory.displayLabelEn.lowercase()}.",
                userMessageHi = "यह एक मान्य ${noticeCategory.displayLabelHi} है।",
                technicalDetail = "Valid notice context (noticeScore=$noticeContextScore, category=${noticeCategory.name})."
            )
        }

        // Rule C: Substantial unrelated prose (>= 18 meaningful words) with zero notice context
        if (meaningfulWords.size >= 18 && noticeContextScore == 0) {
            val category = if (maxNonNoticeScore >= 2) {
                topNonNoticeCategory
            } else {
                DetectedDocumentCategory.IRRELEVANT_DOCUMENT
            }
            return buildNotANoticeResult(
                category = category,
                confidence = 0.82,
                detail = "Substantial prose (${meaningfulWords.size} words) with zero institutional notice or directive context."
            )
        }

        // Rule D: Ambiguous, uncertain, or insufficient context -> ask user for clearer/more suitable input
        return buildNeedsClearerInputResult(
            detail = "Classification uncertain (noticeScore=$noticeContextScore, maxNonNoticeScore=$maxNonNoticeScore, words=${meaningfulWords.size})."
        )
    }

    /**
     * Parses the structured JSON returned by the AI classification step.
     * Returns null if the JSON is a legacy test summary JSON that lacks classification fields.
     * Throws IllegalArgumentException if [rawResponse] does not contain a valid JSON object at all.
     */
    fun parseAiClassificationResponse(rawResponse: String): NoticeValidationGateResult? {
        val withoutFences = rawResponse.trim()
            .replace(Regex("^```(?:json)?\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*```$"), "")
            .trim()
        val start = withoutFences.indexOf('{')
        val end = withoutFences.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw IllegalArgumentException("Classifier response is not a valid JSON object.")
        }

        val jsonCandidate = withoutFences.substring(start, end + 1)
            .replace(Regex(",\\s*([}\\]])"), "$1")
        val obj = try {
            JSONObject(jsonCandidate)
        } catch (e: Exception) {
            throw IllegalArgumentException("Could not parse classifier JSON response.", e)
        }

        val rawStatus = listOf("classificationStatus", "status", "verdict", "validationStatus")
            .firstNotNullOfOrNull { key ->
                obj.optString(key, "").trim().takeIf { it.isNotBlank() }
            }

        val hasIsNoticeBoolean = obj.has("isNotice") && !obj.isNull("isNotice")
        if (rawStatus == null && !hasIsNoticeBoolean) {
            // Legacy summary JSON (e.g., FakeSuccessAiGateway in existing tests)
            if (obj.has("summary") || obj.has("inShort") || obj.has("purpose")) {
                return null
            }
            throw IllegalArgumentException("Classifier JSON missing classificationStatus field.")
        }

        val confidence = obj.optDouble("confidence", 0.85).let {
            if (it.isNaN()) 0.85 else it.coerceIn(0.0, 1.0)
        }
        val rawCategory = obj.optString("documentCategory", obj.optString("category", "")).trim()
        val reasonEn = obj.optString("reasonEn", obj.optString("reason", "")).trim()
        val reasonHi = obj.optString("reasonHi", "").trim()

        val status = when {
            rawStatus != null -> when (rawStatus.uppercase().replace("-", "_").replace(" ", "_")) {
                "VALID_NOTICE", "NOTICE", "ALLOWED", "VALID" -> NoticeValidationStatus.VALID_NOTICE
                "NOT_A_NOTICE", "NON_NOTICE", "REJECTED", "IRRELEVANT", "NOT_NOTICE" -> NoticeValidationStatus.NOT_A_NOTICE
                "NEEDS_CLEARER_INPUT", "UNCERTAIN", "INSUFFICIENT_TEXT", "BLURRY", "AMBIGUOUS" -> NoticeValidationStatus.NEEDS_CLEARER_INPUT
                "UNREADABLE_DOCUMENT", "UNREADABLE", "BLANK", "EMPTY" -> NoticeValidationStatus.UNREADABLE_DOCUMENT
                else -> NoticeValidationStatus.NEEDS_CLEARER_INPUT
            }
            else -> if (obj.optBoolean("isNotice", false)) {
                NoticeValidationStatus.VALID_NOTICE
            } else {
                NoticeValidationStatus.NOT_A_NOTICE
            }
        }

        val category = parseCategory(rawCategory, status)

        // If AI classifier is uncertain (low confidence), do not invent a classification
        if ((status == NoticeValidationStatus.VALID_NOTICE || status == NoticeValidationStatus.NOT_A_NOTICE) &&
            confidence < MIN_CONFIDENCE_THRESHOLD
        ) {
            return buildNeedsClearerInputResult(
                detail = "AI classifier confidence ($confidence) is below threshold ($MIN_CONFIDENCE_THRESHOLD)."
            )
        }

        return when (status) {
            NoticeValidationStatus.VALID_NOTICE -> NoticeValidationGateResult(
                status = NoticeValidationStatus.VALID_NOTICE,
                category = if (category.isNoticeCategory) category else DetectedDocumentCategory.OFFICIAL_INSTRUCTION,
                confidence = confidence,
                userMessageEn = reasonEn.ifBlank { "Verified as a ${category.displayLabelEn.lowercase()}." },
                userMessageHi = reasonHi.ifBlank { "यह एक मान्य ${category.displayLabelHi} है।" },
                technicalDetail = "AI classified as VALID_NOTICE (${category.name}, confidence=$confidence)."
            )

            NoticeValidationStatus.NOT_A_NOTICE -> {
                val nonNoticeCat = if (!category.isNoticeCategory) category else DetectedDocumentCategory.IRRELEVANT_DOCUMENT
                val defaultNotNotice = buildNotANoticeResult(nonNoticeCat, confidence, "AI classified as NOT_A_NOTICE")
                defaultNotNotice.copy(
                    userMessageEn = reasonEn.ifBlank { defaultNotNotice.userMessageEn },
                    userMessageHi = reasonHi.ifBlank { defaultNotNotice.userMessageHi }
                )
            }

            NoticeValidationStatus.NEEDS_CLEARER_INPUT -> {
                val defaultUnclear = buildNeedsClearerInputResult("AI classified as NEEDS_CLEARER_INPUT")
                defaultUnclear.copy(
                    confidence = confidence,
                    userMessageEn = reasonEn.ifBlank { defaultUnclear.userMessageEn },
                    userMessageHi = reasonHi.ifBlank { defaultUnclear.userMessageHi }
                )
            }

            NoticeValidationStatus.UNREADABLE_DOCUMENT -> {
                val defaultUnreadable = buildUnreadableResult("AI classified as UNREADABLE_DOCUMENT")
                defaultUnreadable.copy(
                    confidence = confidence,
                    userMessageEn = reasonEn.ifBlank { defaultUnreadable.userMessageEn },
                    userMessageHi = reasonHi.ifBlank { defaultUnreadable.userMessageHi }
                )
            }

            NoticeValidationStatus.CLASSIFICATION_ERROR -> buildClassificationErrorResult(
                errorMessage = reasonEn.ifBlank { "Unable to classify document at this time." }
            )
        }
    }

    /**
     * Combines the AI classifier result with the deterministic code-level content & context check
     * so that an AI prompt alone is never the sole enforcement mechanism.
     */
    fun combineAiAndCodeValidation(
        aiResult: NoticeValidationGateResult?,
        codeResult: NoticeValidationGateResult
    ): NoticeValidationGateResult {
        if (codeResult.status == NoticeValidationStatus.UNREADABLE_DOCUMENT) {
            return codeResult
        }
        // Code-level non-notice detection cannot be bypassed even if the AI prompt returned VALID_NOTICE
        if (codeResult.status == NoticeValidationStatus.NOT_A_NOTICE) {
            return codeResult
        }

        if (aiResult == null) {
            return codeResult
        }

        return when (aiResult.status) {
            NoticeValidationStatus.UNREADABLE_DOCUMENT -> aiResult
            NoticeValidationStatus.NOT_A_NOTICE -> aiResult
            NoticeValidationStatus.NEEDS_CLEARER_INPUT -> aiResult
            NoticeValidationStatus.CLASSIFICATION_ERROR -> aiResult
            NoticeValidationStatus.VALID_NOTICE -> {
                if (codeResult.status == NoticeValidationStatus.NEEDS_CLEARER_INPUT && aiResult.confidence < 0.85) {
                    codeResult
                } else {
                    aiResult
                }
            }
        }
    }

    fun buildClassificationErrorResult(
        errorMessage: String
    ): NoticeValidationGateResult {
        val cleanMsg = errorMessage.trim().ifBlank {
            "Unable to verify the document right now. Please check your internet connection and try again."
        }
        return NoticeValidationGateResult(
            status = NoticeValidationStatus.CLASSIFICATION_ERROR,
            category = DetectedDocumentCategory.CLASSIFIER_UNAVAILABLE,
            confidence = 0.0,
            userMessageEn = cleanMsg,
            userMessageHi = "दस्तावेज़ सत्यापन सेवा से संपर्क नहीं हो सका: $cleanMsg",
            technicalDetail = "Classifier API failure: $cleanMsg"
        )
    }

    fun buildUnreadableResult(
        detail: String
    ): NoticeValidationGateResult {
        return NoticeValidationGateResult(
            status = NoticeValidationStatus.UNREADABLE_DOCUMENT,
            category = DetectedDocumentCategory.UNREADABLE,
            confidence = 1.0,
            userMessageEn = "No readable text could be extracted from this document. It appears to be blank, corrupted, or an unreadable scan (this is a reading error, not a confirmed non-notice). Please upload a clear, readable notice PDF or photo.",
            userMessageHi = "इस दस्तावेज़ से कोई पठनीय टेक्स्ट नहीं निकाला जा सका। यह खाली, क्षतिग्रस्त या अपठनीय स्कैन प्रतीत होता है। कृपया एक स्पष्ट और पठनीय नोटिस अपलोड करें।",
            technicalDetail = detail
        )
    }

    fun buildNeedsClearerInputResult(
        detail: String
    ): NoticeValidationGateResult {
        return NoticeValidationGateResult(
            status = NoticeValidationStatus.NEEDS_CLEARER_INPUT,
            category = DetectedDocumentCategory.INSUFFICIENT_OR_BLURRY,
            confidence = 0.40,
            userMessageEn = "We could not confidently determine whether this document is an official notice because the extracted text is too brief, blurry, or incomplete. Please provide a clearer scan or a complete notice document.",
            userMessageHi = "निकाला गया टेक्स्ट बहुत छोटा, धुंधला या अधूरा होने के कारण यह स्पष्ट नहीं हो सका कि यह एक आधिकारिक नोटिस है या नहीं। कृपया अधिक स्पष्ट स्कैन या पूर्ण नोटिस दस्तावेज़ प्रदान करें।",
            technicalDetail = detail
        )
    }

    fun buildNotANoticeResult(
        category: DetectedDocumentCategory,
        confidence: Double = 0.90,
        detail: String = ""
    ): NoticeValidationGateResult {
        val categoryDescEn = when (category) {
            DetectedDocumentCategory.ESSAY_OR_ACADEMIC_PAPER -> "an essay or academic article"
            DetectedDocumentCategory.NOVEL_OR_LITERARY_TEXT -> "a novel, story, or literary text"
            DetectedDocumentCategory.INVOICE_OR_BILL -> "an invoice, receipt, or commercial bill"
            DetectedDocumentCategory.RESUME_OR_CV -> "a personal resume or curriculum vitae (CV)"
            else -> "a general document unrelated to notices"
        }
        val categoryDescHi = when (category) {
            DetectedDocumentCategory.ESSAY_OR_ACADEMIC_PAPER -> "एक निबंध या शैक्षणिक लेख"
            DetectedDocumentCategory.NOVEL_OR_LITERARY_TEXT -> "एक उपन्यास, कहानी या साहित्यिक पाठ"
            DetectedDocumentCategory.INVOICE_OR_BILL -> "एक चालान (इनवॉइस), रसीद या बिल"
            DetectedDocumentCategory.RESUME_OR_CV -> "एक व्यक्तिगत रेज़्यूमे या बायोडाटा (CV)"
            else -> "नोटिस से असंबंधित एक सामान्य दस्तावेज़"
        }

        return NoticeValidationGateResult(
            status = NoticeValidationStatus.NOT_A_NOTICE,
            category = category,
            confidence = confidence,
            userMessageEn = "NoticeBuddy only analyses notices, circulars, government notifications, scholarship announcements, and official instructions. This document appears to be $categoryDescEn, so notice summarization was blocked. Please upload an official notice instead.",
            userMessageHi = "NoticeBuddy केवल आधिकारिक नोटिस, परिपत्र (सर्कुलर), सरकारी अधिसूचनाओं और छात्रवृत्ति घोषणाओं का विश्लेषण करता है। यह दस्तावेज़ $categoryDescHi प्रतीत होता है, इसलिए इसका सारांश रोक दिया गया है। कृपया कोई आधिकारिक नोटिस अपलोड करें।",
            technicalDetail = detail
        )
    }

    private fun stripPageDelimiters(text: String): String {
        return text
            .replace(Regex("---\\s*Page Break\\s*---", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\[\\s*(?:Scanned\\s+|OCR\\s+)?Page\\s+\\d+\\s*\\]", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:Scanned\\s+|OCR\\s+)?Page\\s+\\d+\\s*:", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun scoreResumeContext(lower: String): Int {
        var score = 0
        val strongSignals = listOf(
            "curriculum vitae",
            "work experience",
            "professional experience",
            "employment history",
            "career objective",
            "professional summary",
            "technical skills",
            "core competencies",
            "academic qualifications",
            "educational qualifications",
            "references available upon request",
            "i hereby declare that the information"
        )
        val mediumSignals = listOf(
            "linkedin.com",
            "github.com",
            "notice period",
            "cgpa",
            "gpa:",
            "languages known",
            "personal details",
            "project experience",
            "key achievements",
            "certifications:",
            "software engineer",
            "intern at"
        )
        for (s in strongSignals) if (lower.contains(s)) score += 3
        for (s in mediumSignals) if (lower.contains(s)) score += 2
        return score
    }

    private fun scoreInvoiceContext(lower: String): Int {
        var score = 0
        val strongSignals = listOf(
            "tax invoice",
            "invoice no",
            "invoice number",
            "invoice #",
            "bill to",
            "ship to",
            "billed to",
            "subtotal",
            "sub-total",
            "total amount due",
            "amount due",
            "balance due",
            "gstin",
            "hsn/sac",
            "thank you for your business"
        )
        val mediumSignals = listOf(
            "unit price",
            "qty",
            "quantity:",
            "cgst",
            "sgst",
            "igst",
            "sales tax",
            "vat",
            "payment terms",
            "payment method",
            "customer id",
            "order id",
            "make all checks payable"
        )
        for (s in strongSignals) if (lower.contains(s)) score += 3
        for (s in mediumSignals) if (lower.contains(s)) score += 2
        return score
    }

    private fun scoreNovelContext(lower: String, original: String): Int {
        var score = 0
        val strongSignals = listOf(
            "chapter 1",
            "chapter one",
            "chapter two",
            "chapter ii",
            "chapter iii",
            "chapter iv",
            "prologue",
            "epilogue",
            "once upon a time",
            "he whispered",
            "she whispered",
            "he murmured",
            "she murmured",
            "he replied",
            "she replied",
            "asked softly",
            "heart pounded",
            "heart raced"
        )
        val mediumSignals = listOf(
            "he said",
            "she said",
            "walked slowly",
            "looked out the window",
            "in the shadows",
            "beneath the stars",
            "footsteps echoed",
            "sighed deeply",
            "shook his head",
            "shook her head",
            "tears rolled",
            "darkness fell",
            "sunlight filtered"
        )
        for (s in strongSignals) if (lower.contains(s)) score += 3
        for (s in mediumSignals) if (lower.contains(s)) score += 2

        val dialogueQuotes = Regex("[\"“][^\"”]{8,120}[\"”]\\s*(?:he|she|they)?\\s*(?:said|asked|whispered|replied|shouted)", RegexOption.IGNORE_CASE)
            .findAll(original).count()
        if (dialogueQuotes >= 1) score += 3
        return score
    }

    private fun scoreEssayContext(lower: String): Int {
        var score = 0
        val strongSignals = listOf(
            "in this essay",
            "this essay explores",
            "this essay examines",
            "this paper examines",
            "this paper proposes",
            "in conclusion",
            "to conclude",
            "literature review",
            "research methodology",
            "works cited",
            "bibliography",
            "researchers have argued",
            "scholars have argued",
            "throughout human history"
        )
        val mediumSignals = listOf(
            "furthermore",
            "moreover",
            "nevertheless",
            "on the other hand",
            "plays a vital role",
            "plays a crucial role",
            "in modern society",
            "twentieth century",
            "twenty-first century",
            "empirical study",
            "et al.",
            "abstract:",
            "the phenomenon of",
            "socio-economic"
        )
        for (s in strongSignals) if (lower.contains(s)) score += 3
        for (s in mediumSignals) if (lower.contains(s)) score += 2
        return score
    }

    private fun scoreIrrelevantContext(lower: String): Int {
        var score = 0
        val strongSignals = listOf(
            "ingredients:",
            "preheat oven",
            "tablespoon",
            "teaspoon",
            "simmer for",
            "bake for",
            "chopped onions",
            "servings:",
            "prep time:",
            "cook time:",
            "public static void",
            "fun main(",
            "select * from",
            "console.log(",
            "troubleshooting guide",
            "warranty void if",
            "bluetooth pairing"
        )
        val mediumSignals = listOf(
            "dear diary",
            "shopping list:",
            "olive oil",
            "verse 1",
            "chorus:"
        )
        for (s in strongSignals) if (lower.contains(s)) score += 3
        for (s in mediumSignals) if (lower.contains(s)) score += 2
        return score
    }

    private fun scoreAuthorityContext(lower: String): Int {
        var hits = 0
        val authorities = listOf(
            "ministry of",
            "government of",
            "department of",
            "office of",
            "dean",
            "principal",
            "registrar",
            "warden",
            "controller of examinations",
            "directorate",
            "university",
            "college",
            "institute",
            "school",
            "municipal",
            "board of",
            "committee",
            "helpdesk",
            "library",
            "reading hall",
            "hostel",
            "campus",
            "मंत्रालय",
            "सरकार",
            "विभाग",
            "कार्यालय",
            "विश्वविद्यालय",
            "महाविद्यालय",
            "प्रधानाचार्य",
            "कुलसचिव",
            "छात्रावास",
            "समिति",
            "बोर्ड",
            "पुस्तकालय"
        )
        for (a in authorities) {
            if (lower.contains(a)) hits += 2
        }
        return hits.coerceAtMost(6)
    }

    private fun scoreAudienceContext(lower: String): Int {
        var hits = 0
        val audiences = listOf(
            "all students",
            "all residents",
            "hostel residents",
            "all employees",
            "all staff",
            "all concerned",
            "all faculty",
            "ug students",
            "pg students",
            "undergraduate",
            "postgraduate",
            "b.tech",
            "m.tech",
            "semester",
            "applicants",
            "candidates",
            "citizens",
            "residents of",
            "block a",
            "block b",
            "block c",
            "सभी छात्रों",
            "समस्त छात्रों",
            "सभी निवासियों",
            "सभी कर्मचारियों",
            "सर्वसाधारण",
            "आवेदकों",
            "अभ्यर्थियों",
            "स्नातक",
            "स्नातकोत्तर"
        )
        for (aud in audiences) {
            if (lower.contains(aud)) hits += 2
        }
        return hits.coerceAtMost(6)
    }

    private fun scoreDirectiveAndScheduleContext(lower: String): Int {
        var hits = 0
        val directives = listOf(
            "hereby informed",
            "hereby notified",
            "is informed that",
            "are informed that",
            "applications are invited",
            "applications open",
            "portal opens",
            "may apply for",
            "must submit",
            "submit",
            "bring",
            "attach",
            "report to",
            "scheduled on",
            "scheduled for",
            "last date",
            "deadline",
            "will remain closed",
            "closed for",
            "closes at",
            "will remain suspended",
            "suspended for",
            "effective from",
            "without late fee",
            "fee payment",
            "fee submission",
            "fee rebate",
            "mess fee",
            "room allotment",
            "document verification",
            "biometric",
            "project viva",
            "examination form",
            "scholarship",
            "water supply",
            "power supply",
            "maintenance",
            "सूचित किया जाता है",
            "आवेदन आमंत्रित",
            "आवेदन करने हेतु",
            "अंतिम तिथि",
            "निर्धारित है",
            "बंद रहेगा",
            "बंद रहेगी",
            "जमा करें",
            "समय सारणी",
            "छात्रवृत्ति",
            "शुल्क प्रतिपूर्ति",
            "मेस शुल्क",
            "जलापूर्ति"
        )
        for (dir in directives) {
            if (lower.contains(dir)) hits += 2
        }
        return hits.coerceAtMost(8)
    }

    private fun scoreFramingMarkers(lower: String): Int {
        var hits = 0
        val markers = listOf(
            "notice",
            "circular",
            "notification",
            "memorandum",
            "office order",
            "public announcement",
            "ref no",
            "circular no",
            "subject:",
            "dated:",
            "पत्रांक",
            "दिनांक",
            "विषय",
            "सूचना",
            "परिपत्र",
            "अधिसूचना",
            "कार्यालय आदेश",
            "विज्ञप्ति"
        )
        for (m in markers) {
            if (lower.contains(m)) hits += 1
        }
        return hits.coerceAtMost(3)
    }

    private fun inferLegitimateNoticeCategory(lower: String): DetectedDocumentCategory {
        return when {
            lower.contains("scholarship") || lower.contains("छात्रवृत्ति") || lower.contains("fellowship") ->
                DetectedDocumentCategory.SCHOLARSHIP_NOTICE
            lower.contains("ministry") || lower.contains("government") || lower.contains("gazette") ||
                lower.contains("सरकार") || lower.contains("मंत्रालय") || lower.contains("अधिसूचना") ->
                DetectedDocumentCategory.GOVERNMENT_NOTIFICATION
            lower.contains("college") || lower.contains("university") || lower.contains("semester") ||
                lower.contains("hostel") || lower.contains("b.tech") || lower.contains("viva") ||
                lower.contains("महाविद्यालय") || lower.contains("विश्वविद्यालय") || lower.contains("छात्रावास") ->
                DetectedDocumentCategory.SCHOOL_OR_COLLEGE_CIRCULAR
            lower.contains("employee") || lower.contains("staff") || lower.contains("office order") ||
                lower.contains("memorandum") || lower.contains("कार्यालय") ->
                DetectedDocumentCategory.OFFICE_NOTICE
            lower.contains("water supply") || lower.contains("closed for") || lower.contains("closes at") ||
                lower.contains("suspended") || lower.contains("maintenance") || lower.contains("public") ||
                lower.contains("जलापूर्ति") || lower.contains("बंद") ->
                DetectedDocumentCategory.PUBLIC_ANNOUNCEMENT
            else -> DetectedDocumentCategory.OFFICIAL_INSTRUCTION
        }
    }

    private fun parseCategory(
        rawCategory: String,
        fallbackStatus: NoticeValidationStatus
    ): DetectedDocumentCategory {
        val normalized = rawCategory.uppercase().replace("-", "_").replace(" ", "_")
        for (entry in DetectedDocumentCategory.entries) {
            if (entry.name == normalized) return entry
        }
        return when {
            normalized.contains("GOV") -> DetectedDocumentCategory.GOVERNMENT_NOTIFICATION
            normalized.contains("SCHOLAR") -> DetectedDocumentCategory.SCHOLARSHIP_NOTICE
            normalized.contains("COLLEGE") || normalized.contains("SCHOOL") || normalized.contains("CAMPUS") ->
                DetectedDocumentCategory.SCHOOL_OR_COLLEGE_CIRCULAR
            normalized.contains("OFFICE") || normalized.contains("MEMO") ->
                DetectedDocumentCategory.OFFICE_NOTICE
            normalized.contains("PUBLIC") || normalized.contains("ANNOUNCE") ->
                DetectedDocumentCategory.PUBLIC_ANNOUNCEMENT
            normalized.contains("ESSAY") || normalized.contains("ARTICLE") || normalized.contains("PAPER") ->
                DetectedDocumentCategory.ESSAY_OR_ACADEMIC_PAPER
            normalized.contains("NOVEL") || normalized.contains("STORY") || normalized.contains("FICTION") ->
                DetectedDocumentCategory.NOVEL_OR_LITERARY_TEXT
            normalized.contains("INVOICE") || normalized.contains("BILL") || normalized.contains("RECEIPT") ->
                DetectedDocumentCategory.INVOICE_OR_BILL
            normalized.contains("RESUME") || normalized.contains("CV") ->
                DetectedDocumentCategory.RESUME_OR_CV
            normalized.contains("BLUR") || normalized.contains("INSUFFICIENT") || normalized.contains("UNCLEAR") ->
                DetectedDocumentCategory.INSUFFICIENT_OR_BLURRY
            normalized.contains("UNREADABLE") || normalized.contains("BLANK") ->
                DetectedDocumentCategory.UNREADABLE
            fallbackStatus == NoticeValidationStatus.VALID_NOTICE ->
                DetectedDocumentCategory.OFFICIAL_INSTRUCTION
            fallbackStatus == NoticeValidationStatus.NOT_A_NOTICE ->
                DetectedDocumentCategory.IRRELEVANT_DOCUMENT
            fallbackStatus == NoticeValidationStatus.NEEDS_CLEARER_INPUT ->
                DetectedDocumentCategory.INSUFFICIENT_OR_BLURRY
            fallbackStatus == NoticeValidationStatus.UNREADABLE_DOCUMENT ->
                DetectedDocumentCategory.UNREADABLE
            else -> DetectedDocumentCategory.CLASSIFIER_UNAVAILABLE
        }
    }
}
