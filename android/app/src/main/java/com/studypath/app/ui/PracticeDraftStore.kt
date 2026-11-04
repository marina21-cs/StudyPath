package com.studypath.app.ui

import android.content.Context
import com.studypath.app.data.DatabaseRecords
import com.studypath.app.data.PracticeAttempt
import org.json.JSONObject

/** Retains the same immutable submission across timeout, rotation and process death. */
class PracticeDraftStore(context: Context, private val uid: String) {
    private val preferences = context.getSharedPreferences("pending_practice", Context.MODE_PRIVATE)
    private fun key(moduleId: String) = uid + ":" + moduleId
    fun read(moduleId: String): PracticeAttempt? {
        val source = preferences.getString(key(moduleId), null) ?: return null
        val value = JSONObject(source)
        return PracticeAttempt(value.getString("id"), value.getString("moduleId"), value.getInt("version"),
            value.getInt("correct"), value.getInt("total"), value.getLong("submittedAt")).also { DatabaseRecords.attempt(it) }
    }
    fun retain(attempt: PracticeAttempt) {
        DatabaseRecords.attempt(attempt)
        val value = JSONObject().put("id", attempt.id).put("moduleId", attempt.moduleId).put("version", attempt.version)
            .put("correct", attempt.correct).put("total", attempt.total).put("submittedAt", attempt.submittedAt)
        check(preferences.edit().putString(key(attempt.moduleId), value.toString()).commit()) { "Could not retain the pending result. Try again." }
    }
    fun acknowledge(attempt: PracticeAttempt) {
        check(preferences.edit().remove(key(attempt.moduleId)).commit()) { "Result saved; could not clear its local retry record. Reopen and retry safely." }
    }
}
