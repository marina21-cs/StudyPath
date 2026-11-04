package com.studypath.app.data

import java.time.LocalDate

/** Only a catalog identity is owner-authored; query text and review claims never come from this record. */
data class ReadingContext(val contextId: String, val schemaVersion: Int = 1)
enum class ReadingContextKind { MODULE_CONCEPT, WEAK_CATEGORY }
data class ReadingContextReview(val status: String, val reviewer: String, val reviewedOn: String, val approvalReference: String)
data class ReadingContextDefinition(
    val contextId: String, val kind: ReadingContextKind, val curriculumVersion: String, val subjectId: String,
    val query: String, val review: ReadingContextReview,
    val moduleId: String? = null, val moduleVersion: Int? = null, val packageSha256: String? = null, val conceptId: String? = null,
    val courseId: String? = null, val planVersion: String? = null, val categoryId: String? = null,
)
data class ReadingContextSources(
    val preferences: LearnerPreferences, val options: LearnerPreferenceOptions?,
    val modules: List<PublishedModuleSummary> = emptyList(), val packages: List<VerifiedTextPackage> = emptyList(),
    val courses: List<CatalogCourse> = emptyList(),
)
data class ResolvedReadingContext(val context: ReadingContext, val definition: ReadingContextDefinition) {
    val query get() = definition.query
}

object ReadingContextRecords {
    const val MAX_DEFINITIONS = 100
    private val common = setOf("schemaVersion", "kind", "curriculumVersion", "subjectId", "query", "review")
    private val module = setOf("moduleId", "moduleVersion", "packageSha256", "conceptId")
    private val category = setOf("courseId", "planVersion", "categoryId")

    fun reference(fields: Map<String, Any?>): ReadingContext {
        require(fields.keys == setOf("schemaVersion", "contextId")) { "Reading context permits only its catalog reference." }
        require(integer(fields["schemaVersion"], 1) == 1) { "Unsupported reading context version." }
        return ReadingContext(id(fields["contextId"]))
    }

    fun reference(value: ReadingContext): Map<String, Any> {
        require(value.schemaVersion == 1) { "Unsupported reading context version." }
        DatabaseRecords.requireKey(value.contextId)
        return mapOf("schemaVersion" to 1, "contextId" to value.contextId)
    }

    fun definition(contextId: String, fields: Map<String, Any?>): ReadingContextDefinition {
        DatabaseRecords.requireKey(contextId)
        require(integer(fields["schemaVersion"], 1) == 1) { "Unsupported reviewed query version." }
        val kind = runCatching { ReadingContextKind.valueOf(fields["kind"] as? String ?: "") }
            .getOrElse { throw IllegalArgumentException("Unsupported reading context kind.") }
        require(fields.keys == common + if (kind == ReadingContextKind.MODULE_CONCEPT) module else category) { "Unexpected reviewed query fields." }
        val rawReview = fields["review"]
        require(rawReview is Map<*, *> && rawReview.keys == setOf("status", "reviewer", "reviewedOn", "approvalReference")) { "A complete query review is required." }
        val status = text(rawReview["status"], 24)
        require(status in setOf("APPROVED", "DEMONSTRATION_ONLY", "REVIEW_PENDING", "WITHDRAWN"))
        val day = text(rawReview["reviewedOn"], 10)
        require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && runCatching { LocalDate.parse(day).toString() == day }.getOrDefault(false)) { "Invalid query review date." }
        val review = ReadingContextReview(status, text(rawReview["reviewer"], 120), day, text(rawReview["approvalReference"], 240))
        val curriculum = id(fields["curriculumVersion"]); val subject = id(fields["subjectId"])
        val query = text(fields["query"], 120)
        return if (kind == ReadingContextKind.MODULE_CONCEPT) {
            val hash = text(fields["packageSha256"], 64)
            require(hash.matches(Regex("[a-f0-9]{64}"))) { "Invalid context package identity." }
            ReadingContextDefinition(contextId, kind, curriculum, subject, query, review,
                id(fields["moduleId"]), integer(fields["moduleVersion"], 1_000_000), hash, id(fields["conceptId"]))
        } else ReadingContextDefinition(contextId, kind, curriculum, subject, query, review,
            courseId = id(fields["courseId"]), planVersion = id(fields["planVersion"]), categoryId = id(fields["categoryId"]))
    }

    fun definition(value: ReadingContextDefinition): Map<String, Any> {
        val fields = buildMap<String, Any> {
            put("schemaVersion", 1); put("kind", value.kind.name); put("curriculumVersion", value.curriculumVersion)
            put("subjectId", value.subjectId); put("query", value.query)
            put("review", mapOf("status" to value.review.status, "reviewer" to value.review.reviewer,
                "reviewedOn" to value.review.reviewedOn, "approvalReference" to value.review.approvalReference))
            value.moduleId?.let { put("moduleId", it) }; value.moduleVersion?.let { put("moduleVersion", it) }
            value.packageSha256?.let { put("packageSha256", it) }; value.conceptId?.let { put("conceptId", it) }
            value.courseId?.let { put("courseId", it) }; value.planVersion?.let { put("planVersion", it) }; value.categoryId?.let { put("categoryId", it) }
        }
        definition(value.contextId, fields)
        return fields
    }

    private fun id(value: Any?): String = text(value, 64).also(DatabaseRecords::requireKey)
    private fun text(value: Any?, max: Int): String {
        require(value is String && value.isNotBlank() && value.length <= max && value.none { it.isISOControl() }) { "Invalid reviewed query text." }
        TextPackageJson.utf8(value)
        return value
    }
    private fun integer(value: Any?, max: Int): Int {
        require(value is Number)
        val number = value.toDouble()
        require(number.isFinite() && number % 1.0 == 0.0 && number in 1.0..max.toDouble())
        return number.toInt()
    }
}

/** Resolves only current reviewed catalog data. No score, answer, note, identity or token enters a query. */
object ReadingContextResolver {
    fun resolve(context: ReadingContext, definitions: List<ReadingContextDefinition>, sources: ReadingContextSources): ResolvedReadingContext {
        ReadingContextRecords.reference(context)
        require(definitions.size <= ReadingContextRecords.MAX_DEFINITIONS && definitions.map { it.contextId }.distinct().size == definitions.size) { "Reviewed query registry is invalid." }
        definitions.forEach(ReadingContextRecords::definition)
        val definition = requireNotNull(definitions.singleOrNull { it.contextId == context.contextId }) { "This reviewed reading context is unavailable." }
        require(definition.review.status == "APPROVED") { "This reading query is awaiting review or has been withdrawn." }
        LearnerPreferenceRecords.preferences(sources.preferences)
        require(sources.preferences.supportedBy(sources.options) &&
            sources.preferences.curriculumVersion == definition.curriculumVersion && definition.subjectId in sources.preferences.subjectIds) {
            "This reading context does not match the current supported learner selection."
        }
        when (definition.kind) {
            ReadingContextKind.MODULE_CONCEPT -> module(definition, sources)
            ReadingContextKind.WEAK_CATEGORY -> category(definition, sources)
        }
        return ResolvedReadingContext(context, definition)
    }

    private fun module(definition: ReadingContextDefinition, sources: ReadingContextSources) {
        val summary = requireNotNull(sources.modules.singleOrNull { it.moduleId == definition.moduleId &&
            it.version == definition.moduleVersion && it.curriculumVersion == definition.curriculumVersion }) { "This module version is no longer available." }
        require(summary.packageSha256 == definition.packageSha256 && summary.subjectId == definition.subjectId &&
            PublishedModuleCatalog.select(sources.preferences, sources.options, listOf(summary)).modules == listOf(summary)) { "This module context is not currently approved for the learner selection." }
        val retained = requireNotNull(sources.packages.singleOrNull { it.key == TextPackageKey(summary.curriculumVersion, summary.moduleId, summary.version) }) {
            "Download and verify this exact module version before using its reading context."
        }
        val verified = TextPackageVerifier.verify(summary, retained.manifestJson, retained.textJson)
        require(retained.summary == summary && retained.payload == verified.payload) { "The retained context package differs from the current published version." }
        val concepts = TextPackageJson.objectValue(verified.manifestJson)["concepts"].asJsonArray
        require(concepts.size() in 1..100)
        val ids = concepts.map { element ->
            require(element.isJsonObject)
            val concept = element.asJsonObject
            require(concept.keySet() == setOf("id", "keywords")) { "Invalid reviewed module concept." }
            val id = TextPackageJson.string(concept["id"]).also(DatabaseRecords::requireKey)
            require(concept["keywords"].isJsonArray && concept["keywords"].asJsonArray.size() in 1..12)
            val keywords = concept["keywords"].asJsonArray.map { TextPackageJson.string(it).also { word ->
                require(word.isNotBlank() && word.length <= 80)
            } }
            require(keywords.distinct().size == keywords.size)
            id
        }
        require(ids.distinct().size == ids.size && definition.conceptId in ids) { "This concept is not part of the verified reviewed module." }
    }

    private fun category(definition: ReadingContextDefinition, sources: ReadingContextSources) {
        val course = requireNotNull(sources.courses.singleOrNull { it.id == definition.courseId }) { "This selected course context is unavailable." }
        val plan = requireNotNull(course.gradePlan) { "This course has no reviewed assessment plan." }
        require(plan.verified && plan.version == definition.planVersion) { "This course plan is unverified or has changed." }
        require(plan.categories.count { it.id == definition.categoryId } == 1) { "This reviewed category no longer belongs to the course plan." }
    }
}
