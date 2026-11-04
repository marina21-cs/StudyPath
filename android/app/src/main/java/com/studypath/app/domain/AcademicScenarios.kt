package com.studypath.app.domain

import com.studypath.app.data.*
import java.security.MessageDigest

/** Version-bound hypothetical score overlays; no database mutation is performed here. */
object AcademicScenarios {
    fun overrideKey(categoryId: String, assessmentId: String): String {
        val bytes = listOf(categoryId, assessmentId).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return "override_" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(48)
    }

    fun overlay(course: CatalogCourse, actual: List<ScoreRecord>, overrides: Map<String, Double>,
                scoreAccess: AcademicAccess, scenarioAccess: AcademicAccess): List<ScoreRecord> {
        val plan = requireNotNull(course.gradePlan) { "No reviewed scenario assessment plan is available." }
        require(plan.verified && scoreAccess.allows(course.id, AcademicCapability.SCORES, plan.version)) { scoreAccess.reason }
        require(scenarioAccess.allows(course.id, AcademicCapability.SCENARIOS, plan.version)) { scenarioAccess.reason }
        validateOverrides(course, overrides)
        val selected = plan.categories.flatMap { category -> category.assessments.map { assessment ->
            Triple(overrideKey(category.id, assessment.id), category.id, assessment)
        } }.filter { it.first in overrides }
        val retained = actual.filterNot { record -> record.courseId == course.id && record.planVersion == plan.version &&
            scoreAccess.matches(record.policyBinding) && overrideKey(record.categoryId, record.assessmentId) in overrides }
        val binding = AcademicRecordBinding(requireNotNull(scoreAccess.curriculumVersion), requireNotNull(scoreAccess.policyRevision),
            requireNotNull(scoreAccess.policyRef), requireNotNull(scoreAccess.planVersion))
        return retained + selected.map { (key, category, assessment) ->
            ScoreRecord("whatif_" + key.removePrefix("override_"), course.id, assessment.id, category, "SCORED", assessment.possiblePoints,
                overrides.getValue(key) / 100.0 * assessment.possiblePoints, planVersion = plan.version, policyBinding = binding)
        }
    }

    fun validateOverrides(course: CatalogCourse, overrides: Map<String, Double>) {
        val plan = requireNotNull(course.gradePlan) { "No reviewed scenario assessment plan is available." }
        require(plan.verified) { "The scenario assessment plan has not been reviewed." }
        val keys = plan.categories.flatMap { category -> category.assessments.map { overrideKey(category.id, it.id) } }
        require(keys.distinct().size == keys.size && overrides.size <= 100 && overrides.keys.all { it in keys } &&
            overrides.values.all { it.isFinite() && it in 0.0..100.0 }) { "A scenario override must reference a current planned assessment and a percentage from 0 to 100." }
    }

    fun isCurrent(scenario: GradeScenario, fingerprint: String, access: AcademicAccess): Boolean =
        scenario.baseVersion == fingerprint && access.matches(scenario.policyBinding)

    fun rebase(scenario: GradeScenario, fingerprint: String, access: AcademicAccess): GradeScenario {
        require(access.allows(scenario.courseId, AcademicCapability.SCENARIOS)) { access.reason }
        val binding = AcademicRecordBinding(requireNotNull(access.curriculumVersion), requireNotNull(access.policyRevision),
            requireNotNull(access.policyRef), requireNotNull(access.planVersion))
        return scenario.copy(id = if (scenario.policyBinding == binding) scenario.id else java.util.UUID.randomUUID().toString(),
            baseVersion = fingerprint, requiredPercent = null, policyBinding = binding)
    }
}
