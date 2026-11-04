package com.studypath.app.domain

import com.studypath.app.data.*
import java.math.BigDecimal
import java.math.RoundingMode

/** Adapts only current explicitly included attempts under one separately reviewed scale and repeat policy. */
object AcademicGwa {
    fun evaluate(attempts: List<CourseAttempt>, accessByCourse: Map<String, AcademicAccess>): GwaSummary {
        fun unavailable(reason: String) = GwaSummary(null, 0.0, 0, reason)
        val included = attempts.filter { it.includeInGwa }
        if (included.isEmpty()) return unavailable("No attempts are explicitly included in GWA.")
        val access = included.map { row -> accessByCourse[row.courseId] ?: return unavailable("An included course has no matching reviewed GWA policy.") }
        if (included.zip(access).any { (row, permission) -> !permission.allows(row.courseId, AcademicCapability.GWA) || !permission.matches(row.policyBinding) })
            return unavailable("An included attempt has an outdated or incompatible curriculum/policy binding. Review inclusion before calculating.")
        if (access.map { it.curriculumVersion to it.policyRevision }.distinct().size != 1 || access.map { it.policyRef }.distinct().size != 1)
            return unavailable("Included attempts must share one reviewed curriculum and GWA policy revision.")
        val policy = access.first().gwaPolicy ?: return unavailable("No reviewed numeric grade scale is available.")
        if (access.any { it.gwaPolicy != policy }) return unavailable("Included attempts have mixed grade-scale or repeat policies.")
        if (included.any { it.status != "COMPLETED" || it.grade == null || !it.grade.isFinite() ||
                it.grade !in policy.minimumGrade..policy.maximumGrade || it.scaleId != policy.scaleId })
            return unavailable("Included attempts need completed numeric grades within the reviewed ${policy.scaleId} scale.")
        return try {
            val raw = GwaEngine.evaluate(included.map { GradeAttempt(it.id, it.courseId, it.grade, it.units, true, requireNotNull(it.scaleId)) })
            val rounded = raw.average?.let { value -> if (policy.roundingMode == "NONE") value else
                BigDecimal.valueOf(value).setScale(policy.roundingScale, RoundingMode.valueOf(policy.roundingMode)).toDouble() }
            raw.copy(average = rounded)
        } catch (error: IllegalArgumentException) { unavailable(error.message ?: "These attempts require policy review.") }
    }
}
