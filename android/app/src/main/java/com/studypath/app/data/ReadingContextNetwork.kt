package com.studypath.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.studypath.app.domain.Aggregation
import com.studypath.app.domain.Assessment
import com.studypath.app.domain.AssessmentStatus
import com.studypath.app.domain.GradeCategory
import com.studypath.app.domain.GradeEngine
import com.studypath.app.domain.GradePlan
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fresh authenticated catalog checks only. No SDK/disk/HTTP cache can establish current approval. */
class ReadingContextNetwork(client: OkHttpClient, private val baseUrl: HttpUrl) {
    private val client = client.newBuilder().apply {
        followRedirects(false); followSslRedirects(false); retryOnConnectionFailure(false); cache(null)
        connectTimeout(8, TimeUnit.SECONDS); readTimeout(8, TimeUnit.SECONDS); callTimeout(12, TimeUnit.SECONDS)
        interceptors().clear(); networkInterceptors().clear(); eventListener(EventListener.NONE)
    }.build()

    init {
        val local = baseUrl.host in setOf("127.0.0.1", "localhost", "10.0.2.2") && baseUrl.scheme == "http"
        val cloud = baseUrl.scheme == "https" && baseUrl.port == 443 &&
            (baseUrl.host.endsWith(".firebaseio.com") || baseUrl.host.endsWith(".firebasedatabase.app"))
        require((local || cloud) && baseUrl.encodedPath == "/" && baseUrl.username.isEmpty() && baseUrl.password.isEmpty() && baseUrl.fragment == null) {
            "Use the configured Firebase database endpoint."
        }
        require(baseUrl.queryParameterNames.all { local && it == "ns" } && baseUrl.querySize <= 1) { "Unexpected database endpoint parameters." }
        baseUrl.queryParameter("ns")?.let { require(it.matches(Regex("[A-Za-z0-9_-]{1,100}"))) }
    }

    suspend fun resolve(context: ReadingContext, uid: String, idToken: String,
                        publishedPackages: List<VerifiedTextPackage> = emptyList()): ResolvedReadingContext = withTimeout(20_000) {
        ReadingContextRecords.reference(context)
        require(uid.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid authenticated account identity." }
        require(idToken.matches(Regex("[A-Za-z0-9_.-]{1,8192}"))) { "A Firebase ID token is required." }
        val budget = BodyBudget()
        val definition = ReadingContextRecords.definition(context.contextId,
            get(listOf("catalog", "readingContexts", context.contextId), idToken, budget))
        val preferences = LearnerPreferenceRecords.fromProfile(mapOf("learnerPreferences" to
            get(listOf("users", uid, "profile", "learnerPreferences"), idToken, budget)))
        val options = LearnerPreferenceRecords.options(get(listOf("catalog", "learnerPreferenceOptions", definition.curriculumVersion), idToken, budget))
        val sources = when (definition.kind) {
            ReadingContextKind.MODULE_CONCEPT -> {
                val id = requireNotNull(definition.moduleId); val version = "v${definition.moduleVersion}"
                val fields = get(listOf("catalog", "moduleIndex", definition.curriculumVersion, id, version), idToken, budget)
                val summary = PublishedModuleCatalog.parse(definition.curriculumVersion, id, version, fields)
                ReadingContextSources(preferences, options, listOf(summary), publishedPackages)
            }
            ReadingContextKind.WEAK_CATEGORY -> {
                val id = requireNotNull(definition.courseId)
                val plan = gradePlan(get(listOf("catalog", "courses", id, "gradePlan"), idToken, budget))
                val course = CatalogCourse(id, id, 0.0, "", "", false, "UNKNOWN", emptyList(), plan)
                ReadingContextSources(preferences, options, courses = listOf(course))
            }
        }
        ReadingContextResolver.resolve(context, listOf(definition), sources)
    }

    private suspend fun get(path: List<String>, token: String, budget: BodyBudget): Map<String, Any?> = suspendCancellableCoroutine { continuation ->
        val url = baseUrl.newBuilder().apply {
            path.dropLast(1).forEach(::addPathSegment)
            addPathSegment(path.last() + ".json"); addQueryParameter("auth", token)
        }.build()
        val call = client.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache, no-store").get().build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(IOException("Fresh reading context could not be loaded."))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        require(response.code == 200) { "Fresh context request was rejected." }
                        val body = requireNotNull(response.body)
                        require(body.contentLength() <= budget.remaining) { "Context response exceeds its byte limit." }
                        val bytes = ByteArrayOutputStream()
                        body.byteStream().use { input ->
                            val chunk = ByteArray(4096)
                            while (continuation.isActive) {
                                val count = input.read(chunk)
                                if (count < 0) break
                                budget.take(count); bytes.write(chunk, 0, count)
                            }
                        }
                        if (!continuation.isActive) return
                        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                        val fields = objectFields(TextPackageJson.objectValue(text))
                        if (continuation.isActive) continuation.resume(fields)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(IOException("Fresh reading context response is unavailable or invalid."))
                    }
                }
            }
        })
    }

    private fun objectFields(value: JsonObject): Map<String, Any?> = value.entrySet().associate { it.key to field(it.value) }
    private fun field(value: JsonElement): Any? = when {
        value.isJsonNull -> null
        value.isJsonObject -> objectFields(value.asJsonObject)
        // RTDB renders dense numeric-key maps as arrays. Preserve their child identities, including holes.
        value.isJsonArray -> value.asJsonArray.mapIndexedNotNull { index, child ->
            if (child.isJsonNull) null else index.toString() to field(child)
        }.toMap()
        value.asJsonPrimitive.isBoolean -> value.asBoolean
        value.asJsonPrimitive.isNumber -> value.asBigDecimal
        else -> value.asString
    }

    /** Catalog-only projection: never fetches scores, attempts, a display name or private notes. */
    private fun gradePlan(fields: Map<String, Any?>): CatalogGradePlan {
        require(fields.keys == setOf("version", "assessmentPlanKnown", "verified", "label", "categories"))
        val version = text(fields["version"], 64).also(DatabaseRecords::requireKey)
        require(fields["assessmentPlanKnown"] is Boolean && fields["verified"] is Boolean)
        val categories = map(fields["categories"]).also { require(it.size in 1..100) }.map { (id, raw) ->
            DatabaseRecords.requireKey(id)
            val category = map(raw)
            require(category.keys == setOf("weight", "aggregation", "assessments"))
            val aggregation = Aggregation.valueOf(text(category["aggregation"], 32))
            val assessments = map(category["assessments"]).also { require(it.size in 1..500) }.map { (assessmentId, item) ->
                DatabaseRecords.requireKey(assessmentId)
                val assessment = map(item)
                require(assessment.keys == setOf("title", "possiblePoints") || assessment.keys == setOf("title", "possiblePoints", "explicitWeight"))
                val possible = number(assessment["possiblePoints"]).also { require(it > 0 && it <= 1_000_000) }
                CatalogAssessment(assessmentId, text(assessment["title"], 240), possible, assessment["explicitWeight"]?.let(::number))
            }
            CatalogCategory(id, number(category["weight"]), aggregation.name, assessments)
        }
        require(categories.sumOf { it.assessments.size } <= 1_000)
        GradeEngine.evaluate(GradePlan(categories.map { category -> GradeCategory(category.id, category.weight,
            Aggregation.valueOf(category.aggregation), category.assessments.map { Assessment(it.id, it.possiblePoints, null, AssessmentStatus.UNGRADED, it.explicitWeight) }) },
            fields["assessmentPlanKnown"] == true))
        return CatalogGradePlan(version, fields["assessmentPlanKnown"] == true, fields["verified"] == true, text(fields["label"], 240), categories)
    }

    private fun map(value: Any?): Map<String, Any?> {
        require(value is Map<*, *> && value.keys.all { it is String })
        return value.entries.associate { it.key as String to it.value }
    }
    private fun text(value: Any?, max: Int): String {
        require(value is String && value.isNotBlank() && value.length <= max)
        return value
    }
    private fun number(value: Any?): Double {
        require(value is Number && value.toDouble().isFinite())
        return value.toDouble()
    }
    private class BodyBudget {
        var remaining = 2L * 1024 * 1024; private set
        fun take(count: Int) { require(count <= remaining) { "Context response exceeds its byte limit." }; remaining -= count }
    }
}
