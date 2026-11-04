package com.studypath.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

data class FamilyInvitation(val ownerUid: String, val contextId: String, val acceptanceId: String,
                            val policyRevision: Long, val consentRecordReference: String, val expiresAt: Long) {
    fun code(): String = FamilySharingRecords.encode("SPI1", FamilySharingRecords.invitationJson(this))
}

data class FamilySummaryLink(val ownerUid: String, val contextId: String, val slotId: String, val generation: Long) {
    fun code(): String = FamilySharingRecords.encode("SPF1", JsonObject().apply {
        addProperty("ownerUid", ownerUid); addProperty("contextId", contextId)
        addProperty("slotId", slotId); addProperty("generation", generation)
    })
}

data class FamilySummarySnapshot(val completedLessonCount: Int, val asOf: Long, val expiresAt: Long,
                                 val serverNow: Long, val link: FamilySummaryLink)

internal object FamilySharingRecords {
    val fields: JsonObject get() = JsonObject().apply { addProperty("completedLessonCount", true); addProperty("asOf", true) }
    val slots = (0..4).map { "g$it" }
    val acceptanceIds = (0..49).map { "a" + it.toString().padStart(2, '0') }
    fun id(value: String, uid: Boolean = false): String = value.also {
        require(it.matches(Regex(if (uid) "[A-Za-z0-9_-]{1,128}" else "[A-Za-z0-9_-]{1,64}"))) { "Invalid sharing identity." }
    }
    fun exact(value: JsonObject, keys: Set<String>) { require(value.keySet() == keys) { "Unsupported sharing record fields." } }
    fun text(value: JsonObject, key: String, max: Int = 240): String {
        val field = requireNotNull(value.get(key)); require(field.isJsonPrimitive && field.asJsonPrimitive.isString)
        return field.asString.also { require(it.isNotBlank() && it.length <= max && it.none { c -> c.code < 32 }) }
    }
    fun number(value: JsonObject, key: String, min: Long = 1, max: Long = 4_102_444_800_000): Long {
        val field = requireNotNull(value.get(key)); require(field.isJsonPrimitive && field.asJsonPrimitive.isNumber)
        return field.asBigDecimal.longValueExact().also { require(it in min..max) }
    }
    fun objectValue(value: JsonElement?): JsonObject = requireNotNull(value).also { require(it.isJsonObject) }.asJsonObject
    fun supportedFields(value: JsonObject) { require(value == fields) { "Only completion count and its timestamp are supported." } }
    fun encode(prefix: String, json: JsonObject): String = prefix + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    private fun decode(prefix: String, code: String): JsonObject {
        require(code.length in 8..4096 && code.startsWith("$prefix.") && code.substringAfter('.').matches(Regex("[A-Za-z0-9_-]+"))) { "Use the complete sharing code." }
        val bytes = Base64.getUrlDecoder().decode(code.substringAfter('.'))
        return objectValue(JsonParser.parseString(bytes.toString(Charsets.UTF_8)))
    }
    fun invitationJson(value: FamilyInvitation): JsonObject = JsonObject().apply {
        addProperty("ownerUid", value.ownerUid); addProperty("contextId", value.contextId); addProperty("acceptanceId", value.acceptanceId)
        addProperty("policyRevision", value.policyRevision); addProperty("consentRecordReference", value.consentRecordReference); addProperty("expiresAt", value.expiresAt)
    }
    fun invitation(code: String): FamilyInvitation {
        val json = decode("SPI1", code)
        exact(json, setOf("ownerUid", "contextId", "acceptanceId", "policyRevision", "consentRecordReference", "expiresAt"))
        val acceptance = text(json, "acceptanceId").also { require(it in acceptanceIds) }
        return FamilyInvitation(id(text(json, "ownerUid"), true), id(text(json, "contextId")), acceptance,
            number(json, "policyRevision", max = 1_000_000), text(json, "consentRecordReference", 1000), number(json, "expiresAt"))
    }
    fun link(code: String): FamilySummaryLink {
        val json = decode("SPF1", code); exact(json, setOf("ownerUid", "contextId", "slotId", "generation"))
        return FamilySummaryLink(id(text(json, "ownerUid"), true), id(text(json, "contextId")),
            text(json, "slotId").also { require(it in slots) }, number(json, "generation", max = 1_000_000))
    }
    fun snapshot(json: JsonObject, link: FamilySummaryLink, recipient: String, now: Long): FamilySummarySnapshot {
        exact(json, setOf("schemaVersion", "ownerUid", "recipientUid", "slotId", "generation", "contextId", "policyRevision", "expiresAt", "summary"))
        require(number(json, "schemaVersion") == 1L && text(json, "ownerUid") == link.ownerUid &&
            text(json, "recipientUid") == recipient && text(json, "contextId") == link.contextId &&
            text(json, "slotId") == link.slotId && number(json, "generation") == link.generation && recipient != link.ownerUid)
        number(json, "policyRevision", max = 1_000_000)
        val expiry = number(json, "expiresAt").also { require(it > now) { "This summary has expired." } }
        val summary = objectValue(json.get("summary")); exact(summary, setOf("completedLessonCount", "asOf"))
        val asOf = number(summary, "asOf").also { require(it <= now && it < expiry) }
        return FamilySummarySnapshot(number(summary, "completedLessonCount", 0, 1_000_000).toInt(), asOf, expiry, now, link)
    }
}
