package com.example.data.local

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class DiagnosticLogLevel {
    INFO,
    WARN,
    ERROR
}

data class DiagnosticLogEntry(
    val id: Long,
    val timestampMs: Long,
    val timestampFormatted: String,
    val level: DiagnosticLogLevel,
    val tag: String,
    val message: String
)

/**
 * Thread-safe in-memory ring-buffer diagnostic logger for inspecting PDF extraction,
 * OCR recognition, and AI notice analysis steps directly inside Settings -> Debug Logs.
 */
object AppDiagnosticLogger {
    private const val MAX_ENTRIES = 250
    private val nextId = AtomicLong(1L)

    private val _logs = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logs: StateFlow<List<DiagnosticLogEntry>> = _logs.asStateFlow()

    init {
        info("SYSTEM", "NoticeBuddy Diagnostic Logger initialized.")
    }

    fun info(tag: String, message: String) {
        append(DiagnosticLogLevel.INFO, tag, message, null)
    }

    fun warn(tag: String, message: String, throwable: Throwable? = null) {
        append(DiagnosticLogLevel.WARN, tag, message, throwable)
    }

    fun error(tag: String, message: String, throwable: Throwable? = null) {
        append(DiagnosticLogLevel.ERROR, tag, message, throwable)
    }

    fun clear() {
        _logs.value = emptyList()
        info("SYSTEM", "Diagnostic logs cleared by user.")
    }

    fun formatAllLogsAsText(): String {
        val snapshot = _logs.value
        if (snapshot.isEmpty()) {
            return "No diagnostic logs recorded."
        }
        return buildString {
            appendLine("=== NoticeBuddy Diagnostic Logs (${snapshot.size} entries) ===")
            snapshot.forEach { entry ->
                appendLine("[${entry.timestampFormatted}] [${entry.level.name}] [${entry.tag}] ${entry.message}")
            }
        }.trim()
    }

    private fun append(
        level: DiagnosticLogLevel,
        tag: String,
        message: String,
        throwable: Throwable?
    ) {
        val now = System.currentTimeMillis()
        val timeStr = runCatching {
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(now))
        }.getOrDefault(now.toString())

        val detailMessage = if (throwable != null) {
            val errSummary = "${throwable.javaClass.simpleName}: ${throwable.message ?: "no message"}"
            "$message | $errSummary"
        } else {
            message
        }

        runCatching {
            when (level) {
                DiagnosticLogLevel.INFO -> Log.i("NoticeMate_$tag", detailMessage)
                DiagnosticLogLevel.WARN -> Log.w("NoticeMate_$tag", detailMessage)
                DiagnosticLogLevel.ERROR -> Log.e("NoticeMate_$tag", detailMessage, throwable)
            }
        }

        val entry = DiagnosticLogEntry(
            id = nextId.getAndIncrement(),
            timestampMs = now,
            timestampFormatted = timeStr,
            level = level,
            tag = tag,
            message = detailMessage
        )

        _logs.update { current ->
            val updated = current + entry
            if (updated.size > MAX_ENTRIES) {
                updated.takeLast(MAX_ENTRIES)
            } else {
                updated
            }
        }
    }
}
