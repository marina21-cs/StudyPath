package com.studypath.app.data

import java.time.LocalDate

data class PublishedModuleSummary(
    val moduleId: String, val version: Int, val title: String,
    val curriculumVersion: String, val levelId: String, val subjectId: String,
    val classIds: Set<String>, val approved: Boolean, val textBytes: Long,
    val assetBytes: Long, val packageSha256: String,
    val source: Map<String, String>, val review: Map<String, Any>,
)

enum class ModuleLibraryStatus { INCOMPLETE_SELECTION, UNSUPPORTED_SELECTION, EMPTY, AVAILABLE }
data class ModuleLibrary(val status: ModuleLibraryStatus, val modules: List<PublishedModuleSummary>)

object PublishedModuleCatalog {
    /** Metadata only: declared package sizes/hash are not proof of a verified offline download. */
    fun parse(curriculum: String, moduleId: String, versionKey: String, fields: Map<String, Any?>): PublishedModuleSummary {
        listOf(curriculum, moduleId).forEach(DatabaseRecords::requireKey)
        require(fields.keys == setOf("schemaVersion", "moduleId", "version", "title", "visibility", "applicability",
            "source", "review", "textBytes", "assetBytes", "packageSha256"))
        require(integer(fields["schemaVersion"], 1, 1) == 1L && fields["visibility"] == "PUBLIC")
        val version = integer(fields["version"], 1, 1_000_000).toInt()
        require(fields["moduleId"] == moduleId && versionKey == "v$version")
        val title = text(fields["title"], 240)
        val applicability = map(fields["applicability"])
        require(applicability.keys.containsAll(setOf("curriculumVersion", "levelId", "subjectId")) &&
            applicability.keys.all { it in setOf("curriculumVersion", "levelId", "subjectId", "classIds") })
        val recordCurriculum = id(applicability["curriculumVersion"])
        require(recordCurriculum == curriculum)
        val level = id(applicability["levelId"]); val subject = id(applicability["subjectId"])
        val classes = if (applicability.containsKey("classIds")) map(applicability["classIds"]).also { value ->
            require(value.isNotEmpty() && value.size <= 1000 && value.values.all { it == true })
            value.keys.forEach { id(it) }
        }.keys else emptySet()
        val source = map(fields["source"])
        require(source.keys == setOf("title", "reference", "locator", "author", "version"))
        val sourceText = source.mapValues { text(it.value, 1000) }
        val review = map(fields["review"])
        require(review.keys == setOf("status", "reviewer", "reviewedOn", "approvalReference", "permissionReference", "adaptationAllowed", "redistributionAllowed"))
        require(review["status"] in setOf("APPROVED", "DEMONSTRATION_ONLY", "REVIEW_PENDING"))
        listOf("reviewer", "approvalReference", "permissionReference").forEach { text(review[it], 1000) }
        val day = text(review["reviewedOn"], 10)
        require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && runCatching { LocalDate.parse(day).toString() == day }.getOrDefault(false))
        require(review["adaptationAllowed"] is Boolean && review["redistributionAllowed"] is Boolean)
        val hash = text(fields["packageSha256"], 64)
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return PublishedModuleSummary(moduleId, version, title, curriculum, level, subject, classes.toSet(),
            review["status"] == "APPROVED" && review["adaptationAllowed"] == true && review["redistributionAllowed"] == true,
            integer(fields["textBytes"], 1, 99_999), integer(fields["assetBytes"], 0, 9_007_199_254_740_991), hash,
            sourceText, review.mapValues { requireNotNull(it.value) })
    }

    /** Section applicability filters public discovery; it never grants private assignment access. */
    fun select(preferences: LearnerPreferences, options: LearnerPreferenceOptions?, modules: List<PublishedModuleSummary>): ModuleLibrary {
        if (!preferences.onboardingComplete) return ModuleLibrary(ModuleLibraryStatus.INCOMPLETE_SELECTION, emptyList())
        if (!preferences.supportedBy(options)) return ModuleLibrary(ModuleLibraryStatus.UNSUPPORTED_SELECTION, emptyList())
        val selected = modules.filter { it.approved && it.curriculumVersion == preferences.curriculumVersion &&
            it.levelId == preferences.levelId && it.subjectId in preferences.subjectIds &&
            (it.classIds.isEmpty() || preferences.classSectionId in it.classIds) }
            .sortedWith(compareBy<PublishedModuleSummary> { it.title }.thenBy { it.moduleId }.thenByDescending { it.version })
        return ModuleLibrary(if (selected.isEmpty()) ModuleLibraryStatus.EMPTY else ModuleLibraryStatus.AVAILABLE, selected)
    }

    private fun map(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String })
        return value.entries.associate { it.key as String to it.value }
    }
    private fun id(value: Any?): String = text(value, 64).also(DatabaseRecords::requireKey)
    private fun text(value: Any?, max: Int): String {
        require(value is String && value.isNotBlank() && value.length <= max)
        return value
    }
    private fun integer(value: Any?, min: Long, max: Long): Long {
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite() && number >= min.toDouble() && number <= max.toDouble() && number % 1.0 == 0.0)
        return number.toLong()
    }
}
