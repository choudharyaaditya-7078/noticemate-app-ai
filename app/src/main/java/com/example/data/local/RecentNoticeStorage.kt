package com.example.data.local

import android.content.Context
import com.example.data.model.NoticeDescriptionResult
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class NoticeInputType(val label: String) {
    TEXT("Text"),
    PDF("PDF"),
    IMAGE("Image")
}

enum class AppThemeMode(val label: String) {
    NIGHT("Night (Liquid Aurora)"),
    DAY("Day (Crystal Glass)")
}

data class RecentNoticeEntry(
    val id: String,
    val title: String,
    val inputType: NoticeInputType,
    val timestamp: Long,
    val textContent: String = "",
    val pdfFileName: String = "",
    val pdfFileSizeBytes: Long = -1L,
    val pdfUriString: String = "",
    val extractedPdfText: String = "",
    val descriptionResult: NoticeDescriptionResult? = null
)

class RecentNoticeStorage(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeMode(): AppThemeMode {
        val raw = prefs.getString(KEY_THEME_MODE, AppThemeMode.NIGHT.name)
        return runCatching { AppThemeMode.valueOf(raw ?: AppThemeMode.NIGHT.name) }
            .getOrDefault(AppThemeMode.NIGHT)
    }

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun loadRecentNotices(): List<RecentNoticeEntry> {
        val rawJson = prefs.getString(KEY_RECENT_NOTICES, null) ?: return emptyList()
        return try {
            val array = JSONArray(rawJson)
            val entries = mutableListOf<RecentNoticeEntry>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val inputTypeStr = obj.optString("inputType", NoticeInputType.TEXT.name)
                val inputType = runCatching { NoticeInputType.valueOf(inputTypeStr) }
                    .getOrDefault(NoticeInputType.TEXT)
                val descriptionObj = obj.optJSONObject("descriptionResult")
                val parsedDescription = NoticeDescriptionResult.fromJsonObject(descriptionObj)
                entries.add(
                    RecentNoticeEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        title = obj.optString("title", "Untitled Notice"),
                        inputType = inputType,
                        timestamp = obj.optLong("timestamp", 0L),
                        textContent = obj.optString("textContent", ""),
                        pdfFileName = obj.optString("pdfFileName", ""),
                        pdfFileSizeBytes = obj.optLong("pdfFileSizeBytes", -1L),
                        pdfUriString = obj.optString("pdfUriString", ""),
                        extractedPdfText = obj.optString("extractedPdfText", ""),
                        descriptionResult = parsedDescription
                    )
                )
            }
            entries.sortedByDescending { it.timestamp }.take(MAX_RECENT_ENTRIES)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveOrUpdateTextNotice(
        rawText: String,
        nowMillis: Long = System.currentTimeMillis()
    ): RecentNoticeEntry {
        val cleaned = rawText.trim()
        val normalizedKey = cleaned.replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
        val existing = loadRecentNotices().toMutableList()

        val duplicateIndex = existing.indexOfFirst {
            it.inputType == NoticeInputType.TEXT &&
                it.textContent.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) == normalizedKey
        }

        val entry = if (duplicateIndex >= 0) {
            val prev = existing.removeAt(duplicateIndex)
            prev.copy(
                title = deriveTitleFromNoticeText(cleaned),
                timestamp = nowMillis,
                textContent = cleaned
            )
        } else {
            RecentNoticeEntry(
                id = "notice_${UUID.randomUUID().toString().take(8)}",
                title = deriveTitleFromNoticeText(cleaned),
                inputType = NoticeInputType.TEXT,
                timestamp = nowMillis,
                textContent = cleaned
            )
        }

        val updatedList = (listOf(entry) + existing)
            .sortedByDescending { it.timestamp }
            .take(MAX_RECENT_ENTRIES)
        persistList(updatedList)
        return entry
    }

    fun saveOrUpdatePdfNotice(
        fileName: String,
        fileSizeBytes: Long,
        uriString: String = "",
        extractedPdfText: String = "",
        nowMillis: Long = System.currentTimeMillis()
    ): RecentNoticeEntry {
        val cleanName = fileName.trim().ifBlank { "document.pdf" }
        val existing = loadRecentNotices().toMutableList()

        val duplicateIndex = existing.indexOfFirst {
            it.inputType == NoticeInputType.PDF &&
                it.pdfFileName.equals(cleanName, ignoreCase = true)
        }

        val entry = if (duplicateIndex >= 0) {
            val prev = existing.removeAt(duplicateIndex)
            prev.copy(
                title = cleanName,
                timestamp = nowMillis,
                pdfFileName = cleanName,
                pdfFileSizeBytes = fileSizeBytes,
                pdfUriString = uriString,
                extractedPdfText = extractedPdfText.ifBlank { prev.extractedPdfText }
            )
        } else {
            RecentNoticeEntry(
                id = "pdf_${UUID.randomUUID().toString().take(8)}",
                title = cleanName,
                inputType = NoticeInputType.PDF,
                timestamp = nowMillis,
                pdfFileName = cleanName,
                pdfFileSizeBytes = fileSizeBytes,
                pdfUriString = uriString,
                extractedPdfText = extractedPdfText
            )
        }

        val updatedList = (listOf(entry) + existing)
            .sortedByDescending { it.timestamp }
            .take(MAX_RECENT_ENTRIES)
        persistList(updatedList)
        return entry
    }

    fun saveOrUpdateImageNotice(
        fileName: String,
        fileSizeBytes: Long,
        uriString: String = "",
        extractedText: String = "",
        nowMillis: Long = System.currentTimeMillis()
    ): RecentNoticeEntry {
        val cleanName = fileName.trim().ifBlank { "Notice_Image.jpg" }
        val existing = loadRecentNotices().toMutableList()

        val duplicateIndex = existing.indexOfFirst {
            it.inputType == NoticeInputType.IMAGE &&
                it.pdfFileName.equals(cleanName, ignoreCase = true)
        }

        val entry = if (duplicateIndex >= 0) {
            val prev = existing.removeAt(duplicateIndex)
            prev.copy(
                title = cleanName,
                timestamp = nowMillis,
                pdfFileName = cleanName,
                pdfFileSizeBytes = fileSizeBytes,
                pdfUriString = uriString,
                extractedPdfText = extractedText.ifBlank { prev.extractedPdfText }
            )
        } else {
            RecentNoticeEntry(
                id = "img_${UUID.randomUUID().toString().take(8)}",
                title = cleanName,
                inputType = NoticeInputType.IMAGE,
                timestamp = nowMillis,
                pdfFileName = cleanName,
                pdfFileSizeBytes = fileSizeBytes,
                pdfUriString = uriString,
                extractedPdfText = extractedText
            )
        }

        val updatedList = (listOf(entry) + existing)
            .sortedByDescending { it.timestamp }
            .take(MAX_RECENT_ENTRIES)
        persistList(updatedList)
        return entry
    }

    /**
     * Attaches a generated [NoticeDescriptionResult] (and optional extracted PDF/image text) to an
     * existing [RecentNoticeEntry] WITHOUT overwriting its original [RecentNoticeEntry.textContent]
     * or [RecentNoticeEntry.pdfFileName].
     */
    fun attachDescriptionToEntry(
        entryId: String,
        description: NoticeDescriptionResult,
        extractedPdfText: String? = null
    ): RecentNoticeEntry? {
        val existing = loadRecentNotices().toMutableList()
        val index = existing.indexOfFirst { it.id == entryId }
        if (index < 0) return null

        val current = existing[index]
        val resolvedTitle = description.shortTitle.takeIf {
            it.isNotBlank() && current.inputType != NoticeInputType.PDF
        } ?: current.title

        val updated = current.copy(
            title = resolvedTitle,
            textContent = current.textContent,
            pdfFileName = current.pdfFileName,
            extractedPdfText = extractedPdfText?.takeIf { it.isNotBlank() } ?: current.extractedPdfText,
            descriptionResult = description
        )
        existing[index] = updated
        persistList(existing)
        return updated
    }

    fun attachExtractedPdfText(
        entryId: String,
        extractedPdfText: String
    ): RecentNoticeEntry? {
        val existing = loadRecentNotices().toMutableList()
        val index = existing.indexOfFirst { it.id == entryId }
        if (index < 0) return null

        val current = existing[index]
        val updated = current.copy(
            extractedPdfText = extractedPdfText
        )
        existing[index] = updated
        persistList(existing)
        return updated
    }

    /**
     * Deletes a single saved notice by ID and returns the updated list.
     */
    fun deleteNotice(entryId: String): List<RecentNoticeEntry> {
        val updated = loadRecentNotices().filterNot { it.id == entryId }
        persistList(updated)
        return updated
    }

    fun clearAll() {
        prefs.edit().remove(KEY_RECENT_NOTICES).apply()
    }

    private fun persistList(entries: List<RecentNoticeEntry>) {
        val array = JSONArray()
        entries.take(MAX_RECENT_ENTRIES).forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("title", entry.title)
                    put("inputType", entry.inputType.name)
                    put("timestamp", entry.timestamp)
                    put("textContent", entry.textContent)
                    put("pdfFileName", entry.pdfFileName)
                    put("pdfFileSizeBytes", entry.pdfFileSizeBytes)
                    put("pdfUriString", entry.pdfUriString)
                    put("extractedPdfText", entry.extractedPdfText)
                    if (entry.descriptionResult != null) {
                        put("descriptionResult", entry.descriptionResult.toJsonObject())
                    }
                }
            )
        }
        prefs.edit().putString(KEY_RECENT_NOTICES, array.toString()).apply()
    }

    companion object {
        const val PREFS_NAME = "noticemate_local_storage"
        const val KEY_RECENT_NOTICES = "recent_notices_json"
        const val KEY_THEME_MODE = "theme_mode"
        const val MAX_RECENT_ENTRIES = 15

        fun deriveTitleFromNoticeText(rawText: String): String {
            val words = rawText.trim()
                .replace(Regex("\\s+"), " ")
                .split(" ")
                .filter { it.isNotBlank() }
            if (words.isEmpty()) return "Untitled Notice"
            val firstWords = words.take(7).joinToString(" ")
            return if (words.size > 7 || firstWords.length > 52) {
                "${firstWords.take(49).trimEnd()}..."
            } else {
                firstWords
            }
        }

        fun formatFileSize(bytes: Long): String {
            if (bytes < 0L) return "Size unavailable"
            if (bytes < 1024L) return "$bytes B"
            val kb = bytes / 1024.0
            if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
            val mb = kb / 1024.0
            return String.format(Locale.US, "%.2f MB", mb)
        }
    }
}
