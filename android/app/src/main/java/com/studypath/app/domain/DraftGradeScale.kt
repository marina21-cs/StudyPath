package com.studypath.app.domain

/** Explicit student assumptions, separate from reviewed institutional policy and recorded grades. */
data class DraftGradeBand(val minimumPercent: Double, val grade: String)
data class DraftGradeScale(
    val mode: String = "PERCENT",
    val minimum: Double = 0.0,
    val maximum: Double = 100.0,
    val bands: List<DraftGradeBand> = emptyList(),
    val passingPercent: Double? = null,
) {
    fun validate() {
        require(mode in listOf("PERCENT", "LINEAR", "BANDS")) { "Choose a supported grade scale." }
        require(passingPercent == null || passingPercent.isFinite() && passingPercent in 0.0..100.0) { "Passing threshold must be a raw percentage from 0 to 100." }
        if (mode == "LINEAR") require(minimum.isFinite() && maximum.isFinite() && minimum >= 0 && maximum > minimum && maximum <= 1000) { "Enter an increasing numeric range from 0 to 1000." }
        if (mode == "BANDS") {
            require(bands.size in 2..20) { "Enter 2–20 grade thresholds." }
            require(bands.all { it.minimumPercent.isFinite() && it.minimumPercent in 0.0..100.0 && it.grade.isNotBlank() && it.grade.length <= 12 }) { "Each threshold needs a percentage and a grade label." }
            require(bands.map { it.minimumPercent }.distinct().size == bands.size && bands.any { it.minimumPercent == 0.0 }) { "Use unique thresholds and include 0 for the lowest band." }
        }
    }
    fun convert(rawPercent: Double): String {
        validate()
        require(rawPercent.isFinite() && rawPercent in 0.0..100.0)
        return when(mode) {
            "LINEAR" -> "%.2f".format(java.util.Locale.US, minimum + (maximum - minimum) * rawPercent / 100)
            "BANDS" -> bands.sortedByDescending { it.minimumPercent }.first { rawPercent >= it.minimumPercent }.grade
            else -> "%.2f / 100".format(java.util.Locale.US, rawPercent)
        }
    }
    fun description(): String = when(mode) {
        "LINEAR" -> "Linear numeric: $minimum + raw % × (${maximum - minimum} / 100)"
        "BANDS" -> "Custom grade bands: " + bands.sortedByDescending { it.minimumPercent }.joinToString { "${it.minimumPercent}% → ${it.grade}" }
        else -> "Zero-based percentage · earned ÷ possible × 100"
    }
}
