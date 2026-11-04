package com.studypath.app.domain

import com.studypath.app.data.*
import java.security.MessageDigest

/** A bounded result, never a persisted client-authored approval flag. Repositories resolve fresh evidence before writes. */
class AcademicAccess private constructor(
    val allowed: Boolean,
    val reason: String,
    val courseId: String,
    val capability: AcademicCapability,
    val planVersion: String?,
    val curriculumVersion: String? = null,
    val policyRevision: Int? = null,
    val policyRef: String? = null,
    val bindingFingerprint: String? = null,
    val gwaPolicy: AcademicGwaPolicy? = null,
    private val validFrom: Long = Long.MAX_VALUE,
    private val validUntil: Long = Long.MIN_VALUE,
    private val coursePlans: Map<String, AcademicCourseBinding> = emptyMap(),
    private val currentPolicyRefs: Set<String> = emptySet(),
    private val attemptPolicyRefs: Set<String> = emptySet(),
) {
    fun allows(courseId: String, capability: AcademicCapability, planVersion: String? = null,
               nowMs: Long = System.currentTimeMillis()): Boolean =
        allowed && this.courseId == courseId && this.capability == capability &&
            (planVersion == null || this.planVersion == planVersion) && nowMs >= validFrom && nowMs < validUntil

    fun matches(binding: AcademicRecordBinding?, acceptedPolicyRefs: Set<String> = setOfNotNull(policyRef),
                courseId: String = this.courseId, nowMs: Long = System.currentTimeMillis()): Boolean =
        binding != null && allowed && nowMs >= validFrom && nowMs < validUntil &&
            binding.curriculumVersion == curriculumVersion && binding.policyRevision == policyRevision &&
            binding.policyRef in acceptedPolicyRefs && binding.policyRef in currentPolicyRefs && binding.planVersion == coursePlans[courseId]?.planVersion

    /** A prior GWA/outcome edit may supply a completion, but only under this exact current approved curriculum policy. */
    fun matchesAttempt(binding: AcademicRecordBinding?, courseId: String,
                       nowMs: Long = System.currentTimeMillis()): Boolean = matches(binding, attemptPolicyRefs, courseId, nowMs)

    companion object {
        fun resolve(preferences: LearnerPreferences, options: LearnerPreferenceOptions?, policy: AcademicPolicySnapshot?,
                    courseId: String, capability: AcademicCapability, planVersion: String? = null,
                    nowMs: Long = System.currentTimeMillis()): AcademicAccess {
            fun unavailable(reason: String) = AcademicAccess(false, reason, courseId, capability, planVersion)
            if (policy == null) return unavailable("Academic planning is unavailable until a reviewed curriculum and applicable policy are published.")
            try {
                DatabaseRecords.requireKey(courseId)
                planVersion?.let(DatabaseRecords::requireKey)
                LearnerPreferenceRecords.preferences(preferences)
                AcademicPolicyRecords.fields(policy)
            } catch (error: IllegalArgumentException) {
                return unavailable("Academic policy data needs review. ${error.message.orEmpty()}")
            }
            if (!preferences.supportedBy(options)) return unavailable("Academic planning is unavailable for an incomplete or unsupported learner selection. Update your learning preferences.")
            if (policy.curriculumVersion != preferences.curriculumVersion || policy.levelId != preferences.levelId ||
                preferences.subjectIds.any { it !in policy.subjectIds }) return unavailable("This academic policy does not match the selected curriculum, level and subjects.")
            if (policy.approvalStatus != "APPROVED") return unavailable("Academic policy is awaiting review or has been withdrawn; results are unavailable.")
            if (nowMs < policy.validFrom || nowMs >= policy.validUntil || policy.reviewedAt > nowMs) return unavailable("This academic policy is not currently valid; refresh or request policy review.")
            if (capability !in policy.capabilities || policy.capabilityApprovalRefs[capability].isNullOrBlank()) return unavailable("No separately reviewed ${capability.name.lowercase()} policy is available for this curriculum.")
            if (policy.educationSector != "HIGHER_EDUCATION" && capability in setOf(AcademicCapability.GWA, AcademicCapability.PREREQUISITES))
                return unavailable("College GWA and prerequisite decisions are unavailable for a basic-education selection.")
            if (capability == AcademicCapability.GWA && policy.gwaPolicy == null)
                return unavailable("GWA is unavailable until its numeric grade scale, retake/exclusion and rounding policies are reviewed.")
            val binding = policy.coursePlans[courseId] ?: return unavailable("This course does not belong to the reviewed curriculum policy.")
            if (binding.subjectId !in preferences.subjectIds || (planVersion != null && binding.planVersion != planVersion))
                return unavailable("This course subject or plan version does not match the selected reviewed academic policy.")
            val parts = buildList {
                add(policy.curriculumVersion); add(policy.levelId); add(policy.educationSector); add(policy.revision.toString())
                add(policy.approvalRef); add(policy.reviewer); add(policy.reviewedAt.toString()); add(policy.sourceRef); add(policy.contextRef)
                add(policy.validFrom.toString()); add(policy.validUntil.toString()); add(courseId); add(binding.subjectId); add(binding.planVersion)
                policy.subjectIds.sorted().forEach(::add)
                AcademicCapability.entries.forEach { add(it.name); add((it in policy.capabilities).toString()); add(policy.capabilityApprovalRefs[it].orEmpty()) }
                policy.gwaPolicy?.let { add(it.toString()) }
            }
            val bytes = parts.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val usablePolicyRefs = policy.capabilityApprovalRefs.filterKeys {
                it in policy.capabilities &&
                    (it !in setOf(AcademicCapability.GWA, AcademicCapability.PREREQUISITES) || policy.educationSector == "HIGHER_EDUCATION") &&
                    (it != AcademicCapability.GWA || policy.gwaPolicy != null)
            }
            return AcademicAccess(true, "Reviewed matching policy; self-reported advisory results.", courseId, capability, binding.planVersion,
                policy.curriculumVersion, policy.revision, policy.capabilityApprovalRefs.getValue(capability), fingerprint, policy.gwaPolicy,
                policy.validFrom, policy.validUntil, policy.coursePlans.toMap(), usablePolicyRefs.values.toSet(),
                usablePolicyRefs.filterKeys { it != AcademicCapability.SCENARIOS }.values.toSet())
        }
    }
}
