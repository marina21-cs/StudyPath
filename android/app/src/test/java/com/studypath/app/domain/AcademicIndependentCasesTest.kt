package com.studypath.app.domain

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.studypath.app.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.math.BigDecimal
import java.math.MathContext

/** Static reference expectations are authored outside the engines; no real approval is asserted. */
@RunWith(Parameterized::class)
class AcademicIndependentCasesTest(private val caseId: String, private val case: JsonObject) {
    @Test fun agreesWithWorkedReference() {
        assertTrue("$caseId needs explicit assumptions", case.getAsJsonArray("assumptions").size() >= 2)
        assertTrue("$caseId needs worked reasoning", case["workedExplanation"].asString.length >= 30)
        val input = case.getAsJsonObject("input")
        val expected = case.getAsJsonObject("expected")
        when (case["kind"].asString) {
            "GRADE" -> grade(input, expected)
            "GWA" -> gwa(input, expected)
            "GWA_POLICY" -> roundedGwa(input, expected)
            "PREREQUISITE" -> prerequisite(input, expected)
            "ACCESS" -> access(input, expected)
            else -> fail("Unknown reference case kind")
        }
    }

    private fun grade(input: JsonObject, expected: JsonObject) {
        val plan = GradePlan(input.getAsJsonArray("categories").map { raw ->
            val category = raw.asJsonObject
            GradeCategory(category["id"].asString, category["weight"].asDouble,
                Aggregation.valueOf(category["aggregation"].asString), category.getAsJsonArray("assessments").map { rawItem ->
                    val item = rawItem.asJsonObject
                    Assessment(item["id"].asString, item["possiblePoints"].asDouble, item.nullableDouble("earnedPoints"),
                        AssessmentStatus.valueOf(item["status"].asString), item.nullableDouble("explicitWeight"))
                })
        }, input["assessmentPlanKnown"].asBoolean)
        if (expected.has("errorContains")) {
            rejects(expected) { GradeEngine.evaluate(plan) }; return
        }
        val actual = GradeEngine.evaluate(plan)
        close(expected, "gradedWorkPercent", actual.gradedWorkPercent)
        close(expected, "minimumFinalPercent", actual.minimumFinalPercent)
        close(expected, "maximumFinalPercent", actual.maximumFinalPercent)
        close(expected, "remainingWeight", actual.remainingWeight)
        assertEquals(caseId, expected["complete"].asBoolean, actual.complete)
        if (expected.has("target")) {
            val target = GradeEngine.target(actual, input["targetPercent"].asDouble)
            val targetExpected = expected.getAsJsonObject("target")
            assertEquals(caseId, targetExpected["status"].asString, target.status.name)
            close(targetExpected, "requiredRemainingPercent", target.requiredRemainingPercent)
        }
    }

    private fun attempts(input: JsonObject) = input.getAsJsonArray("attempts").map { raw ->
        val row = raw.asJsonObject
        GradeAttempt(row["attemptId"].asString, row["courseId"].asString, row.nullableDouble("numericGrade"),
            row["units"].asDouble, row["included"].asBoolean, row["scaleId"].asString)
    }

    private fun gwa(input: JsonObject, expected: JsonObject) {
        val rows = attempts(input)
        if (expected.has("errorContains")) {
            rejects(expected) { GwaEngine.evaluate(rows) }; return
        }
        assertGwa(expected, GwaEngine.evaluate(rows))
    }

    private fun roundedGwa(input: JsonObject, expected: JsonObject) {
        val rounding = input.getAsJsonObject("rounding")
        val rows = attempts(input)
        val policy = policy().copy(gwaPolicy = AcademicGwaPolicy("mock_numeric", 0.0, 100.0, "EXPLICIT",
            "SYNTHETIC_TEST_ONLY_RETAKE", "SYNTHETIC_TEST_ONLY_EXCLUSION", rounding["mode"].asString,
            rounding["scale"].asInt), coursePlans = rows.associate { it.courseId to AcademicCourseBinding("computing", "plan_v1") })
        val access = rows.associate { row -> row.courseId to AcademicAccess.resolve(preferences(), options(), policy,
            row.courseId, AcademicCapability.GWA, "plan_v1", REFERENCE_NOW) }
        val bound = rows.map { row -> CourseAttempt(id = row.attemptId, courseId = row.courseId, term = "synthetic_term",
            status = "COMPLETED", units = row.units, grade = row.numericGrade, scaleId = row.scaleId,
            includeInGwa = row.included, policyBinding = binding(AcademicCapability.GWA)) }
        assertGwa(expected, AcademicGwa.evaluate(bound, access))
    }

    private fun assertGwa(expected: JsonObject, actual: GwaSummary) {
        close(expected, "average", actual.average)
        close(expected, "includedUnits", actual.includedUnits)
        assertEquals(caseId, expected["includedAttemptCount"].asInt, actual.includedAttemptCount)
        if (expected.has("reasonContains")) assertTrue(caseId, actual.unavailableReason.orEmpty().contains(expected["reasonContains"].asString))
        else assertNull(caseId, actual.unavailableReason)
    }

    private fun prerequisite(input: JsonObject, expected: JsonObject) {
        fun expression(raw: JsonObject): Prerequisite = when (raw["kind"].asString) {
            "COURSE" -> Prerequisite.Course(raw["courseId"].asString)
            "AND" -> Prerequisite.AllOf(raw.getAsJsonArray("children").map { expression(it.asJsonObject) })
            "OR" -> Prerequisite.AnyOf(raw.getAsJsonArray("children").map { expression(it.asJsonObject) })
            "UNKNOWN" -> Prerequisite.Unknown(raw["reason"].asString)
            else -> error("Unsupported reference expression")
        }
        val catalog = input.getAsJsonArray("catalogCourseIds").map { it.asString }.toSet()
        val records = input.getAsJsonObject("completions").entrySet().associate { it.key to CompletionStatus.valueOf(it.value.asString) }
        val actual = PrerequisiteEvaluator.evaluate(expression(input.getAsJsonObject("expression")), catalog, records)
        assertEquals(caseId, expected["state"].asString, actual.state.name)
        assertEquals("$caseId blocker reasons", expected.getAsJsonArray("reasons").map { it.asString }.toSet(), actual.reasons.toSet())
    }

    private fun access(input: JsonObject, expected: JsonObject) {
        val capability = AcademicCapability.valueOf(input["capability"]?.asString ?: "SCORES")
        val now = input["nowMs"]?.asLong ?: REFERENCE_NOW
        val policy = policy().copy(approvalStatus = input["approvalStatus"]?.asString ?: "APPROVED",
            revision = input["policyRevision"]?.asInt ?: 1, educationSector = input["educationSector"]?.asString ?: "HIGHER_EDUCATION")
        val actual = AcademicAccess.resolve(preferences(), options(), policy, "one", capability,
            input["requestedPlanVersion"]?.asString ?: "plan_v1", now)
        assertEquals(caseId, expected["allowed"].asBoolean, actual.allowed)
        if (expected.has("bindingMatches")) {
            val retained = binding(capability).copy(policyRevision = input["bindingRevision"]?.asInt ?: 1)
            assertEquals(caseId, expected["bindingMatches"].asBoolean, actual.matches(retained, nowMs = now))
        }
        if (expected.has("reasonContains")) assertTrue(caseId, actual.reason.contains(expected["reasonContains"].asString, ignoreCase = true))
    }

    private fun close(expected: JsonObject, field: String, actual: Double?) {
        val value = expected[field]
        if (value.isJsonNull) assertNull("$caseId $field", actual)
        else {
            assertNotNull("$caseId $field", actual)
            assertEquals("$caseId $field", rational(value), actual!!, 1e-8)
        }
    }

    private fun rational(value: JsonElement): Double {
        val parts = value.asString.split('/')
        require(parts.size in 1..2)
        val numerator = BigDecimal(parts[0])
        return (if (parts.size == 1) numerator else numerator.divide(BigDecimal(parts[1]), MathContext.DECIMAL128)).toDouble()
    }

    private fun rejects(expected: JsonObject, action: () -> Any?) {
        val error = assertThrows(IllegalArgumentException::class.java) { action() }
        assertTrue("$caseId needs an actionable unavailable reason", error.message.orEmpty().contains(expected["errorContains"].asString, ignoreCase = true))
    }

    private fun JsonObject.nullableDouble(field: String): Double? = get(field)?.takeUnless { it.isJsonNull }?.asDouble
    private fun preferences() = LearnerPreferences("case_college", "case_curriculum", setOf("computing"), onboardingComplete = true)
    private fun options() = LearnerPreferenceOptions("case_curriculum", "case_college", setOf("computing"), true, "APPROVED")
    private fun reference(capability: AcademicCapability) = "SYNTHETIC_TEST_ONLY_${capability.name}"
    private fun binding(capability: AcademicCapability) = AcademicRecordBinding("case_curriculum", 1, reference(capability), "plan_v1")
    private fun policy() = AcademicPolicySnapshot("case_curriculum", "case_college", "HIGHER_EDUCATION", setOf("computing"),
        "APPROVED", "SYNTHETIC_TEST_ONLY", "Synthetic reference fixture", 1, "SYNTHETIC_TEST_ONLY_SOURCE", "SYNTHETIC_TEST_ONLY_CONTEXT", 1,
        1, 4_102_444_800_000L, AcademicCapability.entries.toSet(), AcademicCapability.entries.associateWith(::reference),
        mapOf("one" to AcademicCourseBinding("computing", "plan_v1")), AcademicGwaPolicy("mock_numeric", 0.0, 100.0,
            "EXPLICIT", "SYNTHETIC_TEST_ONLY_RETAKE", "SYNTHETIC_TEST_ONLY_EXCLUSION", "NONE", 2))

    companion object {
        private const val REFERENCE_NOW = 1_800_000_000_000L

        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): Collection<Array<Any>> {
            val packet = JsonParser.parseString(requireNotNull(AcademicIndependentCasesTest::class.java
                .getResourceAsStream("/academic-worked-cases.json")).bufferedReader().use { it.readText() }).asJsonObject
            require(packet["schemaVersion"].asInt == 1 && packet["mockOnly"].asBoolean && !packet["externallyReviewed"].asBoolean &&
                !packet["institutionallyApproved"].asBoolean)
            val cases = packet.getAsJsonArray("cases").map { it.asJsonObject }
            require(cases.size >= 30 && cases.size == packet["caseCount"].asInt && cases.map { it["id"].asString }.distinct().size == cases.size)
            return cases.map { arrayOf<Any>(it["id"].asString, it) }
        }
    }
}
