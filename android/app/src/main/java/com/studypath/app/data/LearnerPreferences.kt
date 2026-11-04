package com.studypath.app.data

data class LearnerPreferences(
    val levelId: String? = null,
    val curriculumVersion: String? = null,
    val subjectIds: Set<String> = emptySet(),
    val classSectionId: String? = null,
    val onboardingComplete: Boolean = false,
) {
    /** Current option membership is checked separately from the stored completion flag. */
    fun supportedBy(options: LearnerPreferenceOptions?): Boolean = onboardingComplete &&
        options != null && options.approved && options.approvalStatus == "APPROVED" &&
        curriculumVersion == options.curriculumVersion && levelId == options.levelId &&
        subjectIds.isNotEmpty() && subjectIds.all { it in options.subjectIds }
}

data class LearnerPreferenceOptions(
    val curriculumVersion: String,
    val levelId: String,
    val subjectIds: Set<String>,
    val approved: Boolean,
    val approvalStatus: String,
)

object LearnerPreferenceRecords {
    private val required = setOf("schemaVersion", "onboardingComplete")
    private val optional = setOf("levelId", "curriculumVersion", "subjectIds", "classSectionId")

    /** Read-only migration: never infer a real learner context from the legacy demo curriculum. */
    fun fromProfile(profile: Map<String, Any?>): LearnerPreferences {
        val raw = profile["learnerPreferences"] ?: return LearnerPreferences()
        require(raw is Map<*, *>) { "The stored learner preferences are invalid." }
        require(raw.keys.containsAll(required) && raw.keys.all { it in required || it in optional })
        require((raw["schemaVersion"] as? Number)?.toDouble() == 1.0) { "Unsupported learner preference version." }
        val complete = raw["onboardingComplete"]
        require(complete is Boolean)
        val value = LearnerPreferences(optionalId(raw, "levelId"), optionalId(raw, "curriculumVersion"),
            subjects(raw["subjectIds"]), optionalId(raw, "classSectionId"), complete)
        preferences(value)
        return value
    }

    fun preferences(value: LearnerPreferences): Map<String, Any> {
        listOfNotNull(value.levelId, value.curriculumVersion, value.classSectionId).forEach(DatabaseRecords::requireKey)
        require(value.subjectIds.size <= 20)
        value.subjectIds.forEach(DatabaseRecords::requireKey)
        require(!value.onboardingComplete || (value.levelId != null && value.curriculumVersion != null && value.subjectIds.isNotEmpty()))
        return mutableMapOf<String, Any>("schemaVersion" to 1, "onboardingComplete" to value.onboardingComplete).apply {
            value.levelId?.let { put("levelId", it) }
            value.curriculumVersion?.let { put("curriculumVersion", it) }
            value.classSectionId?.let { put("classSectionId", it) }
            if (value.subjectIds.isNotEmpty()) put("subjectIds", value.subjectIds.sorted().associateWith { true })
        }
    }

    /** Granular profile edit: omitting a name preserves it; UI supplies Student for a blank optional name. */
    fun profilePatch(value: LearnerPreferences, displayName: String?, updatedAt: Any): Map<String, Any> {
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
        require(name == null || name.length <= 80) { "Your name must be at most 80 characters." }
        return mutableMapOf<String, Any>("learnerPreferences" to preferences(value), "updatedAt" to updatedAt).apply {
            name?.let { put("displayName", it) }
        }
    }

    /** This is a read-only option index, not the full curriculum/policy publication record. */
    fun options(value: Map<String, Any?>?): LearnerPreferenceOptions? {
        if (value == null) return null
        require(value.keys == setOf("schemaVersion", "curriculumVersion", "levelId", "subjectIds", "approved", "approvalStatus"))
        require((value["schemaVersion"] as? Number)?.toDouble() == 1.0)
        val curriculum = optionalId(value, "curriculumVersion") ?: error("Missing curriculum version.")
        val level = optionalId(value, "levelId") ?: error("Missing learner level.")
        val subjects = subjects(value["subjectIds"])
        require(subjects.isNotEmpty())
        val approved = value["approved"]; val status = value["approvalStatus"]
        require(approved is Boolean && status is String && status in setOf("APPROVED", "DEMONSTRATION_ONLY", "REVIEW_PENDING"))
        return LearnerPreferenceOptions(curriculum, level, subjects, approved, status)
    }

    private fun optionalId(value: Map<*, *>, key: String): String? {
        if (!value.containsKey(key)) return null
        val field = value[key]
        require(field is String)
        DatabaseRecords.requireKey(field)
        return field
    }

    private fun subjects(value: Any?): Set<String> {
        if (value == null) return emptySet()
        require(value is Map<*, *> && value.size <= 20)
        return value.entries.map { (key, selected) ->
            require(key is String && selected == true)
            DatabaseRecords.requireKey(key)
            key
        }.toSet()
    }
}
