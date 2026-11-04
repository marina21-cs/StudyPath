package com.studypath.app.domain

import com.studypath.app.data.OperationRecord
import com.studypath.app.data.OperationSyncState

object OperationPresentation {
    fun summary(records: List<OperationRecord>): String {
        val displayed = displayedRecords(records)
        val preserved = displayed.count { it.relativePath.startsWith("withdrawnLessonAttempts/") }
        val tracked = displayed.filterNot { it.relativePath.startsWith("withdrawnLessonAttempts/") }
        val open = tracked.count { it.state != OperationSyncState.CONFIRMED }
        val kept = if (preserved == 0) "" else "$preserved ${if (preserved == 1) "result" else "results"} kept on this phone."
        val saving = when {
            open > 0 -> "$open ${if (open == 1) "change needs" else "changes need"} attention."
            tracked.isNotEmpty() -> "Recent changes saved."
            else -> ""
        }
        return listOf(saving, kept).filter { it.isNotEmpty() }.joinToString(" ")
            .ifEmpty { "No saved changes yet." }
    }

    fun title(record: OperationRecord): String {
        val parts = record.relativePath.split('/')
        return when {
            parts.first() == "removePackage" -> "Remove download"
            parts.first() == "saveReading" -> "Saved reading"
            parts.first() == "withdrawnLessonAttempts" -> "Previous lesson result"
            else -> when (parts.getOrNull(1)) {
                "lessonAttempts", "practiceAttempts" -> "Practice result"
                "lessonRuns", "reviewProgress", "progress" -> "Lesson progress"
                "reminders" -> "Reminder"
                "savedReadings" -> "Saved reading"
                "readingAnnotations" -> "Reading note & place"
                "downloads" -> "Lesson download"
                "scores" -> "Score"
                "scenarios" -> "What-if scenario"
                "courseAttempts" -> "Course attempt"
                "learnerPreferences", "profile" -> "Preferences"
                else -> "Saved change"
            }
        }
    }
    fun displayedRecords(records: List<OperationRecord>): List<OperationRecord> {
        val archived = records.filter { it.relativePath.startsWith("withdrawnLessonAttempts/") }
            .map { it.uid to it.relativePath.substringAfter('/') }.toSet()
        return records.filterNot { it.relativePath.startsWith("set/lessonAttempts/") &&
            (it.uid to it.relativePath.substringAfterLast('/')) in archived }
    }
    fun status(state: OperationSyncState): String = when (state) {
        OperationSyncState.QUEUED -> "Saved on this phone"
        OperationSyncState.AWAITING_ACK -> "Saving…"
        OperationSyncState.UNKNOWN_AFTER_RESTART -> "Save status unavailable"
        OperationSyncState.REJECTED -> "Couldn’t save to your account"
        OperationSyncState.AUTH_REQUIRED -> "Sign-in required"
        OperationSyncState.CONFIRMED -> "Saved"
    }
    fun status(record: OperationRecord) = if (record.relativePath.startsWith("withdrawnLessonAttempts/"))
        "Result kept · lesson unavailable" else status(record.state)
    fun retryable(record: OperationRecord) = !record.relativePath.startsWith("withdrawnLessonAttempts/") &&
        record.state !in setOf(OperationSyncState.AWAITING_ACK, OperationSyncState.CONFIRMED)
}
