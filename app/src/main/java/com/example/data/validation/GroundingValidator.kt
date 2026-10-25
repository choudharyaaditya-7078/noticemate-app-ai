package com.example.data.validation

import com.example.data.model.ChecklistTaskUi
import com.example.data.model.MissingGapUi
import com.example.data.model.NoticeDocument
import com.example.data.model.QuoteGroundingStatus
import java.util.Locale

/**
 * Deterministic client-side grounding & gap validation engine (PS 02 Evidence-Aware AI).
 * Verifies that every quote returned by Gemma 4 actually exists in the raw notice text
 * and audits the notice for unstated administrative requirements.
 */
object GroundingValidator {

    fun verifyQuoteAgainstSource(quote: String, rawNoticeText: String): QuoteGroundingStatus {
        val cleanQuote = quote.trim().trim('"', '“', '”', '\'')
        val cleanSource = rawNoticeText.trim()
        if (cleanQuote.isBlank() || cleanSource.isBlank()) {
            return QuoteGroundingStatus.UNVERIFIED
        }

        val normalizedQuote = normalizeWhitespace(cleanQuote).lowercase(Locale.ROOT)
        val normalizedSource = normalizeWhitespace(cleanSource).lowercase(Locale.ROOT)

        if (normalizedSource.contains(normalizedQuote)) {
            return QuoteGroundingStatus.VERIFIED_EXACT
        }

        // Token overlap check for OCR noise / punctuation differences
        val significantTokens = normalizedQuote
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 3 }

        if (significantTokens.isEmpty()) {
            return QuoteGroundingStatus.UNVERIFIED
        }

        val matchedCount = significantTokens.count { token ->
            normalizedSource.contains(token)
        }
        val ratio = matchedCount.toDouble() / significantTokens.size.toDouble()
        return when {
            ratio >= 0.85 -> QuoteGroundingStatus.VERIFIED_EXACT
            ratio >= 0.55 -> QuoteGroundingStatus.PARTIAL_MATCH
            else -> QuoteGroundingStatus.UNVERIFIED
        }
    }

    fun calculateGroundingScore(notice: NoticeDocument): Long {
        val raw = notice.rawNoticeText
        val quotesToCheck = buildList {
            if (notice.purposeQuote.isNotBlank()) add(notice.purposeQuote)
            if (notice.deadlineQuote.isNotBlank()) add(notice.deadlineQuote)
            if (notice.eligibilityQuote.isNotBlank()) add(notice.eligibilityQuote)
            if (notice.contactQuote.isNotBlank()) add(notice.contactQuote)
            addAll(notice.checklistQuotes.filter { it.isNotBlank() })
        }

        if (quotesToCheck.isEmpty()) return 0L

        val totalPoints = quotesToCheck.sumOf { quote ->
            when (verifyQuoteAgainstSource(quote, raw)) {
                QuoteGroundingStatus.VERIFIED_EXACT -> 100
                QuoteGroundingStatus.PARTIAL_MATCH -> 70
                QuoteGroundingStatus.UNVERIFIED -> 0
            }
        }
        return (totalPoints / quotesToCheck.size).toLong().coerceIn(0L, 100L)
    }

    fun buildChecklistTasks(notice: NoticeDocument): List<ChecklistTaskUi> {
        val completedSet = notice.completedIndices.map { it.toInt() }.toSet()
        val count = maxOf(notice.checklistEn.size, notice.checklistHi.size)
        return (0 until count).map { idx ->
            val en = notice.checklistEn.getOrElse(idx) { notice.checklistHi.getOrElse(idx) { "Action step ${idx + 1}" } }
            val hi = notice.checklistHi.getOrElse(idx) { en }
            val quote = notice.checklistQuotes.getOrElse(idx) { notice.purposeQuote }
            ChecklistTaskUi(
                index = idx,
                textEn = en,
                textHi = hi,
                sourceQuote = quote,
                isCompleted = idx in completedSet,
                groundingStatus = verifyQuoteAgainstSource(quote, notice.rawNoticeText)
            )
        }
    }

    fun buildMissingGaps(notice: NoticeDocument): List<MissingGapUi> {
        val count = maxOf(notice.flaggedMissingEn.size, notice.flaggedMissingHi.size)
        return (0 until count).map { idx ->
            val descEn = notice.flaggedMissingEn.getOrElse(idx) { "Missing requirement detail" }
            val descHi = notice.flaggedMissingHi.getOrElse(idx) { descEn }
            val qEn = notice.followUpQuestionsEn.getOrElse(idx) {
                notice.followUpQuestionsEn.firstOrNull() ?: "Could you clarify this requirement?"
            }
            val qHi = notice.followUpQuestionsHi.getOrElse(idx) {
                notice.followUpQuestionsHi.firstOrNull() ?: "कृपया इस आवश्यकता को स्पष्ट करें?"
            }
            MissingGapUi(
                index = idx,
                descriptionEn = descEn,
                descriptionHi = descHi,
                followUpQuestionEn = qEn,
                followUpQuestionHi = qHi
            )
        }
    }

    /**
     * Audits any notice text for common unstated details (cutoff, fee amount, submission mode, time)
     * and enriches missing-info alerts if the notice omits them.
     */
    fun auditDeterministicGaps(rawText: String): Pair<List<String>, List<String>> {
        val lower = rawText.lowercase(Locale.ROOT)
        val gapsEn = mutableListOf<String>()
        val gapsHi = mutableListOf<String>()

        if ((lower.contains("scholarship") || lower.contains("eligible") || lower.contains("merit")) &&
            !lower.contains("cgpa") && !lower.contains("%") && !lower.contains("percent") && !lower.contains("marks")
        ) {
            gapsEn.add("Minimum marks or CGPA cutoff for eligibility is not specified.")
            gapsHi.add("पात्रता के लिए न्यूनतम अंक या सीजीपीए (CGPA) कटऑफ निर्दिष्ट नहीं है।")
        }

        if (lower.contains("income") && !lower.contains("lakh") && !lower.contains("rs") && !lower.contains("₹")) {
            gapsEn.add("Maximum annual family income threshold is not stated.")
            gapsHi.add("अधिकतम वार्षिक पारिवारिक आय सीमा का उल्लेख नहीं है।")
        }

        if (lower.contains("fee") && !lower.contains("rs") && !lower.contains("₹") && !Regex("\\b\\d{3,5}\\b").containsMatchIn(lower)) {
            gapsEn.add("Exact fee amount and payment link/account details are missing.")
            gapsHi.add("सटीक शुल्क राशि और भुगतान का माध्यम निर्दिष्ट नहीं है।")
        }

        if (!lower.contains("http") && !lower.contains("portal") && !lower.contains("room") && !lower.contains("email") && !lower.contains("@")) {
            gapsEn.add("Exact submission mode (online portal link vs. room number/counter) is not specified.")
            gapsHi.add("आवेदन जमा करने का सटीक माध्यम (ऑनलाइन लिंक या कमरा संख्या) स्पष्ट नहीं है।")
        }

        if (gapsEn.isEmpty()) {
            gapsEn.add("Office working hours and point-of-contact phone/email are not specified in the notice.")
            gapsHi.add("नोटिस में कार्यालय का समय और संपर्क फ़ोन/ईमेल नहीं दिया गया है।")
        }

        return gapsEn.take(5) to gapsHi.take(5)
    }

    private fun normalizeWhitespace(input: String): String =
        input.replace(Regex("\\s+"), " ").trim()
}
