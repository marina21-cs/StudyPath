package com.studypath.app.data

enum class AcademicCapability { SCORES, SCENARIOS, GWA, PREREQUISITES }
data class AcademicCourseBinding(val subjectId: String, val planVersion: String)
data class AcademicGwaPolicy(val scaleId: String, val minimumGrade: Double, val maximumGrade: Double,
                             val attemptInclusion: String, val retakePolicyRef: String, val exclusionPolicyRef: String,
                             val roundingMode: String, val roundingScale: Int)

/** Read-only catalog evidence. Construction alone is not an authorization; resolve against current learner choices. */
data class AcademicPolicySnapshot(
    val curriculumVersion: String,
    val levelId: String,
    val educationSector: String,
    val subjectIds: Set<String>,
    val approvalStatus: String,
    val approvalRef: String,
    val reviewer: String,
    val reviewedAt: Long,
    val sourceRef: String,
    val contextRef: String,
    val revision: Int,
    val validFrom: Long,
    val validUntil: Long,
    val capabilities: Set<AcademicCapability>,
    val capabilityApprovalRefs: Map<AcademicCapability, String>,
    val coursePlans: Map<String, AcademicCourseBinding>,
    val gwaPolicy: AcademicGwaPolicy? = null,
)

object AcademicPolicyRecords {
    const val MAX_POLICIES = 200
    private val required = setOf("schemaVersion", "curriculumVersion", "levelId", "educationSector", "subjectIds",
        "approvalStatus", "approvalRef", "reviewer", "reviewedAt", "sourceRef", "contextRef", "revision",
        "validFrom", "validUntil", "capabilities", "coursePlans")

    fun parse(curriculumVersion: String, fields: Map<String, Any?>): AcademicPolicySnapshot {
        DatabaseRecords.requireKey(curriculumVersion)
        require(fields.keys.containsAll(required) && fields.keys.all { it in required || it in setOf("capabilityApprovalRefs", "gwaPolicy") }) { "Unexpected academic policy fields." }
        require(integer(fields["schemaVersion"], 1) == 1L) { "Unsupported academic policy schema." }
        val curriculum = id(fields["curriculumVersion"])
        require(curriculum == curriculumVersion) { "The academic policy does not match its curriculum path." }
        val sector = text(fields["educationSector"], 24)
        require(sector in setOf("HIGHER_EDUCATION", "BASIC_EDUCATION")) { "Unsupported education sector." }
        val status = text(fields["approvalStatus"], 24)
        require(status in setOf("APPROVED", "DEMONSTRATION_ONLY", "REVIEW_PENDING", "WITHDRAWN")) { "Unsupported academic review status." }
        val subjects = objectFields(fields["subjectIds"], 20)
        require(subjects.isNotEmpty() && subjects.all { (key, value) -> id(key); value == true }) { "Invalid academic subjects." }
        val capabilities = objectFields(fields["capabilities"], AcademicCapability.entries.size)
        require(capabilities.isNotEmpty()) { "Academic capabilities must be explicit." }
        val enabled = capabilities.mapNotNull { (key, value) ->
            val capability = capability(key)
            require(value is Boolean) { "Invalid academic capability flag." }
            capability.takeIf { value }
        }.toSet()
        val refs = if (fields["capabilityApprovalRefs"] == null) emptyMap() else
            objectFields(fields["capabilityApprovalRefs"], AcademicCapability.entries.size).map { (key, value) ->
                capability(key) to text(value, 240)
            }.toMap()
        val courses = objectFields(fields["coursePlans"], 200)
        require(courses.isNotEmpty()) { "No reviewed academic course plans are recorded." }
        val bindings = courses.map { (key, value) ->
            id(key)
            val binding = objectFields(value, 2)
            require(binding.keys == setOf("subjectId", "planVersion")) { "Invalid academic course binding." }
            val subject = id(binding["subjectId"])
            require(subject in subjects) { "The academic course subject is outside this curriculum." }
            key to AcademicCourseBinding(subject, id(binding["planVersion"]))
        }.toMap()
        val reviewedAt = timestamp(fields["reviewedAt"])
        val from = timestamp(fields["validFrom"])
        val until = timestamp(fields["validUntil"])
        require(from < until && reviewedAt <= until) { "Invalid academic policy validity period." }
        val gwa = fields["gwaPolicy"]?.let { raw ->
            val value = objectFields(raw, 8)
            require(value.keys == setOf("scaleId", "minimumGrade", "maximumGrade", "attemptInclusion", "retakePolicyRef", "exclusionPolicyRef", "roundingMode", "roundingScale")) { "Incomplete reviewed GWA policy." }
            val minimum = grade(value["minimumGrade"]); val maximum = grade(value["maximumGrade"])
            require(minimum < maximum) { "Invalid reviewed grade scale range." }
            require(value["attemptInclusion"] == "EXPLICIT") { "Unsupported reviewed attempt inclusion policy." }
            val mode = text(value["roundingMode"], 12)
            require(mode in setOf("NONE", "HALF_UP", "HALF_EVEN")) { "Unsupported GWA rounding policy." }
            val precision = value["roundingScale"]
            require(precision is Number && precision.toDouble().isFinite() && precision.toDouble() % 1.0 == 0.0 && precision.toInt() in 0..6)
            AcademicGwaPolicy(id(value["scaleId"]), minimum, maximum, "EXPLICIT", text(value["retakePolicyRef"], 240),
                text(value["exclusionPolicyRef"], 240), mode, precision.toInt())
        }
        return AcademicPolicySnapshot(curriculum, id(fields["levelId"]), sector, subjects.keys, status,
            text(fields["approvalRef"], 240), text(fields["reviewer"], 120), reviewedAt,
            text(fields["sourceRef"], 500), text(fields["contextRef"], 500), integer(fields["revision"], 1_000_000).toInt(),
            from, until, enabled, refs, bindings, gwa)
    }

    fun fields(value: AcademicPolicySnapshot): Map<String, Any> {
        val fields = buildMap<String, Any> {
            put("schemaVersion", 1); put("curriculumVersion", value.curriculumVersion); put("levelId", value.levelId)
            put("educationSector", value.educationSector); put("subjectIds", value.subjectIds.associateWith { true })
            put("approvalStatus", value.approvalStatus); put("approvalRef", value.approvalRef); put("reviewer", value.reviewer)
            put("reviewedAt", value.reviewedAt); put("sourceRef", value.sourceRef); put("contextRef", value.contextRef)
            put("revision", value.revision); put("validFrom", value.validFrom); put("validUntil", value.validUntil)
            put("capabilities", AcademicCapability.entries.associate { it.name to (it in value.capabilities) })
            if (value.capabilityApprovalRefs.isNotEmpty()) put("capabilityApprovalRefs", value.capabilityApprovalRefs.mapKeys { it.key.name })
            put("coursePlans", value.coursePlans.mapValues { (_, binding) -> mapOf("subjectId" to binding.subjectId, "planVersion" to binding.planVersion) })
            value.gwaPolicy?.let { policy -> put("gwaPolicy", mapOf("scaleId" to policy.scaleId,
                "minimumGrade" to policy.minimumGrade, "maximumGrade" to policy.maximumGrade, "attemptInclusion" to policy.attemptInclusion,
                "retakePolicyRef" to policy.retakePolicyRef, "exclusionPolicyRef" to policy.exclusionPolicyRef,
                "roundingMode" to policy.roundingMode, "roundingScale" to policy.roundingScale)) }
        }
        parse(value.curriculumVersion, fields)
        return fields
    }

    private fun capability(value: String): AcademicCapability = runCatching { AcademicCapability.valueOf(value) }
        .getOrElse { throw IllegalArgumentException("Unsupported academic capability.") }
    private fun id(value: Any?): String = text(value, 64).also(DatabaseRecords::requireKey)
    private fun text(value: Any?, max: Int): String {
        require(value is String && value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }) { "Invalid academic policy provenance." }
        return value
    }
    private fun integer(value: Any?, max: Long): Long {
        require(value is Number) { "Invalid academic policy number." }
        val number = value.toDouble()
        require(number.isFinite() && number % 1.0 == 0.0 && number in 1.0..max.toDouble()) { "Invalid academic policy number." }
        return number.toLong()
    }
    private fun timestamp(value: Any?): Long = integer(value, 4_102_444_800_000L)
    private fun grade(value: Any?): Double {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..100.0) { "Invalid reviewed grade scale limit." }
        return value.toDouble()
    }
    private fun objectFields(value: Any?, max: Int): Map<String, Any?> {
        require(value is Map<*, *> && value.size <= max && value.keys.all { it is String }) { "Invalid academic policy structure." }
        return value.entries.associate { it.key as String to it.value }
    }
}
