package com.studypath.app.domain

data class GradeAttempt(
    val attemptId: String,
    val courseId: String,
    val numericGrade: Double?,
    val units: Double,
    val included: Boolean,
    val scaleId: String,
)

data class GwaSummary(
    val average: Double?,
    val includedUnits: Double,
    val includedAttemptCount: Int,
    val unavailableReason: String? = null,
)

/** The caller selects included attempts and a consistent scale under its approved policy. */
object GwaEngine {
    fun evaluate(attempts: List<GradeAttempt>): GwaSummary {
        require(attempts.all { it.attemptId.isNotBlank() && it.courseId.isNotBlank() }) { "Attempts require nonblank IDs" }
        require(attempts.map { it.attemptId }.distinct().size == attempts.size) { "Attempt IDs must be unique" }
        val included = attempts.filter { it.included }
        if (included.isEmpty()) return GwaSummary(null, 0.0, 0, "No attempts were explicitly included")
        require(included.all { it.units.isFinite() && it.units > 0.0 }) { "Included units must be finite and positive" }
        require(included.all { it.numericGrade == null || it.numericGrade.isFinite() }) { "Numeric grades must be finite" }
        require(included.all { it.scaleId.isNotBlank() } && included.map { it.scaleId }.distinct().size == 1) {
            "Included attempts must use one explicitly identified grade scale"
        }
        val units = included.sumOf { it.units }
        require(units.isFinite()) { "Total included units exceed numeric capacity" }
        if (included.any { it.numericGrade == null }) {
            return GwaSummary(null, units, included.size, "An included attempt has no numeric grade; resolve its inclusion policy")
        }
        val average = included.sumOf { it.numericGrade!! * (it.units / units) }
        require(average.isFinite()) { "Weighted result exceeds numeric capacity" }
        return GwaSummary(average, units, included.size)
    }
}
