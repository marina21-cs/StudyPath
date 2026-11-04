package com.studypath.app.domain

import com.studypath.app.data.AcademicCapability
import com.studypath.app.data.AcademicRecordBinding

/** Attempt bookkeeping may use any separately approved attempt capability. */
object AcademicAttemptEdits {
    fun permission(courseId: String, includeInGwa: Boolean, completionStatus: String?,
                   permissions: List<AcademicAccess>, previousBinding: AcademicRecordBinding? = null): AcademicAccess {
        val available = permissions.filter { it.capability != AcademicCapability.SCENARIOS && it.allows(courseId, it.capability) }
        require(completionStatus == null || available.any { it.capability == AcademicCapability.PREREQUISITES }) {
            "A completion outcome needs its own currently reviewed prerequisite policy."
        }
        val candidates = if (includeInGwa) available.filter { it.capability == AcademicCapability.GWA } else available
        val preferred = if (completionStatus != null) AcademicCapability.PREREQUISITES else AcademicCapability.SCORES
        return requireNotNull(candidates.firstOrNull { it.matches(previousBinding) }
            ?: candidates.firstOrNull { it.capability == preferred } ?: candidates.firstOrNull()) {
            if (includeInGwa) "GWA inclusion needs its own currently reviewed GWA policy."
            else "No currently reviewed policy permits editing this course attempt."
        }
    }
}
