package com.studypath.app.data

import java.time.ZoneId
import com.studypath.app.domain.PrerequisiteDocument

data class UserSession(val uid: String, val email: String, val displayName: String = "")

/** A complete text snapshot. The server save acknowledgement is tracked separately in memory. */
data class SavedReading(val id: String, val reading: ReadingContent, val savedAt: Long, val courseId: String? = null) {
    val title: String get() = reading.title
    val excerpt: String get() = reading.excerpt
}

data class ModuleSection(val id: String, val title: String, val body: String)
data class PracticeQuestion(val id: String, val prompt: String, val options: Map<String, String>, val answerId: String)
data class CatalogAssessment(val id: String, val title: String, val possiblePoints: Double, val explicitWeight: Double? = null)
data class CatalogCategory(val id: String, val weight: Double, val aggregation: String, val assessments: List<CatalogAssessment>)
data class CatalogGradePlan(val version: String, val assessmentPlanKnown: Boolean, val verified: Boolean, val label: String, val categories: List<CatalogCategory>)
data class CatalogCourse(
    val id: String, val title: String, val units: Double, val topicQuery: String, val moduleId: String,
    val prerequisitesKnown: Boolean, val ruleKind: String, val prerequisiteCourseIds: List<String>,
    val gradePlan: CatalogGradePlan?,
    val prerequisiteExpression: PrerequisiteDocument? = null,
)
data class StudyModule(
    val id: String, val courseId: String, val version: Int, val title: String,
    val approved: Boolean, val approvalStatus: String, val author: String, val source: String,
    val sections: List<ModuleSection>, val practice: List<PracticeQuestion>,
)

data class StudyReminder(
    val id: String, val moduleId: String?, val title: String, val dueAt: Long,
    val enabled: Boolean, val zoneId: String = ZoneId.systemDefault().id, val updatedAt: Long? = null,
    val target: ReminderTarget? = null,
)

data class ReviewProgress(
    val moduleId: String, val version: Int, val completed: Boolean, val lastSection: Int,
    val updatedAt: Long? = null,
)

data class GradeScenario(
    val id: String, val courseId: String, val title: String, val targetPercent: Double,
    val baseVersion: String, val requiredPercent: Double? = null,
    val overrides: Map<String, Double> = emptyMap(), val updatedAt: Long? = null,
    val policyBinding: AcademicRecordBinding? = null,
)

data class CourseAttempt(
    val id: String, val courseId: String, val term: String, val status: String, val units: Double,
    val completionStatus: String? = null, val grade: Double? = null, val finalPercent: Double? = null,
    val scaleId: String? = null, val updatedAt: Long? = null, val includeInGwa: Boolean = false,
    val policyBinding: AcademicRecordBinding? = null,
)

data class ScoreRecord(
    val id: String, val courseId: String, val assessmentId: String, val categoryId: String,
    val status: String, val possiblePoints: Double, val earnedPoints: Double? = null,
    val updatedAt: Long? = null, val planVersion: String = "demo_linear_v1",
    val policyBinding: AcademicRecordBinding? = null,
)

/** Null means a retained legacy record, never permission to reinterpret it under a new policy. */
data class AcademicRecordBinding(val curriculumVersion: String, val policyRevision: Int,
                                 val policyRef: String, val planVersion: String)

data class PracticeAttempt(
    val id: String, val moduleId: String, val version: Int, val correct: Int, val total: Int,
    val submittedAt: Long,
)

object DatabaseRecords {
    private val key = Regex("[A-Za-z0-9_-]{1,64}")
    fun requireKey(value: String) { require(key.matches(value)) { "A valid stable record ID is required." } }

    fun score(value: ScoreRecord): Map<String, Any> {
        listOf(value.id, value.courseId, value.assessmentId, value.categoryId, value.planVersion).forEach(::requireKey)
        require(value.status in setOf("SCORED", "UNGRADED", "MISSING", "EXCUSED"))
        require(value.possiblePoints.isFinite() && value.possiblePoints > 0 && value.possiblePoints <= 1_000_000)
        require(if (value.status == "SCORED") value.earnedPoints != null && value.earnedPoints.isFinite() && value.earnedPoints in 0.0..value.possiblePoints else value.earnedPoints == null)
        return mutableMapOf<String, Any>("courseId" to value.courseId, "assessmentId" to value.assessmentId,
            "categoryId" to value.categoryId, "status" to value.status, "possiblePoints" to value.possiblePoints,
            "planVersion" to value.planVersion).apply {
            value.earnedPoints?.let { put("earnedPoints", it) }
            value.updatedAt?.let { timestamp(it); put("updatedAt", it) }
        }
    }

    fun attempt(value: PracticeAttempt): Map<String, Any> {
        requireKey(value.id); requireKey(value.moduleId)
        require(value.version in 1..1_000_000 && value.total in 1..500 && value.correct in 0..value.total)
        timestamp(value.submittedAt)
        return mapOf("moduleId" to value.moduleId, "version" to value.version, "correct" to value.correct,
            "total" to value.total, "submittedAt" to value.submittedAt)
    }

    fun reminder(value: StudyReminder): Map<String, Any> {
        requireKey(value.id); text(value.title, 120)
        timestamp(value.dueAt); text(value.zoneId, 80); ZoneId.of(value.zoneId)
        return (ReminderTargets.fields(value) + mutableMapOf<String, Any>("title" to value.title,
            "dueAt" to value.dueAt, "enabled" to value.enabled, "zoneId" to value.zoneId)).toMutableMap().apply {
            value.updatedAt?.let { timestamp(it); put("updatedAt", it) }
        }
    }

    fun progress(value: ReviewProgress): Map<String, Any> {
        requireKey(value.moduleId); require(value.version in 1..1_000_000 && value.lastSection in 0..1000)
        return mutableMapOf<String, Any>("moduleId" to value.moduleId, "version" to value.version,
            "completed" to value.completed, "lastSection" to value.lastSection).apply {
            value.updatedAt?.let { timestamp(it); put("updatedAt", it) }
        }
    }

    fun scenario(value: GradeScenario): Map<String, Any> {
        requireKey(value.id); requireKey(value.courseId); text(value.title, 80); text(value.baseVersion, 128)
        percent(value.targetPercent); value.requiredPercent?.let(::percent)
        require(value.overrides.size <= 100)
        value.overrides.forEach { (id, number) -> requireKey(id); percent(number) }
        return mutableMapOf<String, Any>("courseId" to value.courseId, "title" to value.title,
            "targetPercent" to value.targetPercent, "baseVersion" to value.baseVersion).apply {
            value.requiredPercent?.let { put("requiredPercent", it) }
            if (value.overrides.isNotEmpty()) put("overrides", value.overrides)
            value.updatedAt?.let { timestamp(it); put("updatedAt", it) }
        }
    }

    fun courseAttempt(value: CourseAttempt): Map<String, Any> {
        requireKey(value.id); requireKey(value.courseId); text(value.term, 40)
        require(value.status in setOf("COMPLETED", "IN_PROGRESS", "WITHDRAWN", "INCOMPLETE", "NOT_TAKEN"))
        require(value.units.isFinite() && value.units > 0 && value.units <= 30)
        if (value.status != "COMPLETED") require(value.grade == null && value.finalPercent == null && value.completionStatus == null)
        value.grade?.let(::percent); value.finalPercent?.let(::percent)
        value.completionStatus?.let { require(it in setOf("PASSED", "FAILED", "UNKNOWN", "CREDITED_EQUIVALENT")) }
        value.scaleId?.let(::requireKey)
        require(!value.includeInGwa || (value.status == "COMPLETED" && value.grade != null && !value.scaleId.isNullOrBlank()))
        return mutableMapOf<String, Any>("courseId" to value.courseId, "term" to value.term,
            "status" to value.status, "units" to value.units, "includeInGwa" to value.includeInGwa).apply {
            value.grade?.let { put("grade", it) }; value.finalPercent?.let { put("finalPercent", it) }
            value.completionStatus?.let { put("completionStatus", it) }; value.scaleId?.let { put("scaleId", it) }
            value.updatedAt?.let { timestamp(it); put("updatedAt", it) }
        }
    }

    fun reading(value: SavedReading): Map<String, Any> {
        requireKey(value.id); value.courseId?.let(::requireKey)
        val r = value.reading
        require(r.pageId > 0 && r.revisionId > 0)
        text(r.title, 240); text(r.pageKey, 240); text(r.excerpt, 8000)
        require(r.sourceUrl.startsWith("https://en.wikipedia.org/wiki/"))
        require(r.revisionUrl == "https://en.wikipedia.org/w/index.php?oldid=${r.revisionId}")
        require(r.revisionTimestamp.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
        text(r.licenseTitle, 80); require(r.licenseUrl.matches(Regex("https://creativecommons[.]org/licenses/by-sa/[0-9][.][0-9]/(?:deed[.]en)?")))
        text(r.attribution, 500); text(r.modificationNotice, 240)
        timestamp(value.savedAt); timestamp(r.retrievedAt)
        return mutableMapOf<String, Any>("pageId" to r.pageId, "pageKey" to r.pageKey, "title" to r.title,
            "excerpt" to r.excerpt, "sourceUrl" to r.sourceUrl, "revisionId" to r.revisionId,
            "revisionUrl" to r.revisionUrl, "revisionTimestamp" to r.revisionTimestamp,
            "licenseTitle" to r.licenseTitle, "licenseUrl" to r.licenseUrl, "attribution" to r.attribution,
            "modificationNotice" to r.modificationNotice, "retrievedAt" to r.retrievedAt, "savedAt" to value.savedAt).apply {
            value.courseId?.let { put("courseId", it) }
        }
    }

    private fun timestamp(value: Long) { require(value in 0..4_102_444_800_000L) }
    private fun percent(value: Double) { require(value.isFinite() && value in 0.0..100.0) }
    private fun text(value: String, max: Int) { require(value.isNotBlank() && value.length <= max) }
}
