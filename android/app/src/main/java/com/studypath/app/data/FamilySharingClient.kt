package com.studypath.app.data

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import java.io.IOException

class FamilyCredentials(val uid: String, val idToken: String, val checkActive: () -> Unit) {
    override fun toString(): String = "FamilyCredentials(redacted)"
}

data class FamilyAcceptance(val id: String, val recipientUid: String, val contextId: String, val expiresAt: Long,
                            internal val raw: JsonObject = JsonObject())
data class FamilyGrant(val slotId: String, val generation: Long, val recipientUid: String, val contextId: String,
                       val expiresAt: Long, val status: String, val link: FamilySummaryLink,
                       internal val raw: JsonObject = JsonObject())
data class FamilyOwnerState(val grants: List<FamilyGrant>, val acceptances: List<FamilyAcceptance>,
                            val unavailableReason: String?, val synthetic: Boolean)

/** Server-authorized sharing operations. Every public operation obtains a fresh fenced credential once. */
class FamilySharingClient(private val network: FamilySharingNetwork,
                          private val credentials: suspend () -> FamilyCredentials,
                          private val allowSynthetic: Boolean = false) {
    private val r = FamilySharingRecords
    private data class Policy(val raw: JsonObject, val context: String, val curriculum: String, val level: String,
                              val revision: Long, val until: Long, val lifetime: Long, val slots: Int,
                              val generations: Long, val synthetic: Boolean)
    private data class OwnerPolicy(val policy: Policy, val eligibility: JsonObject)
    private inner class Session(val auth: FamilyCredentials) {
        suspend fun request(path: List<String>, method: String = "GET", value: JsonElement? = null,
                            etag: String? = null, readEtag: Boolean = false): FamilyRestResponse {
            auth.checkActive()
            return try { network.request(path, auth.idToken, method, value, etag, readEtag) }
            finally { auth.checkActive() }
        }
    }
    private suspend fun <T> authenticated(block: suspend Session.() -> T): T {
        val auth = credentials(); auth.checkActive(); r.id(auth.uid, true)
        val result = Session(auth).block(); auth.checkActive(); return result
    }
    private fun path(vararg parts: String) = parts.toList()
    private fun obj(value: JsonElement?): JsonObject = r.objectValue(value)
    private fun nullableObject(value: JsonElement): JsonObject? = if (value.isJsonNull) null else obj(value)
    private fun time(value: JsonObject, key: String) = r.number(value, key, 0)
    private fun same(value: JsonObject, expected: JsonObject, keys: Set<String>) = keys.all { value.get(it) == expected.get(it) }
    private fun stopped(error: Exception): Nothing { if (error is CancellationException) throw error; throw error }

    private fun policy(raw: JsonObject, context: String, now: Long): Policy {
        r.exact(raw, setOf("schemaVersion", "contextId", "curriculumVersion", "levelId", "approvalStatus", "approvalReference",
            "reviewer", "reviewedAt", "evidenceMode", "revision", "validFrom", "validUntil", "eligibleLearnerRole",
            "eligibleRecipientRole", "permissionModelReference", "consentModelReference", "allowedFields", "maxLifetimeMs", "maxActiveGrants", "maxGeneration"))
        require(r.number(raw, "schemaVersion") == 1L && r.text(raw, "approvalStatus") == "APPROVED") { "Sharing has no current reviewed policy." }
        require(r.id(r.text(raw, "contextId")) == context)
        val curriculum = r.id(r.text(raw, "curriculumVersion")); val level = r.id(r.text(raw, "levelId"))
        for (key in listOf("approvalReference", "reviewer", "permissionModelReference", "consentModelReference")) r.text(raw, key, 1000)
        for (key in listOf("eligibleLearnerRole", "eligibleRecipientRole")) r.id(r.text(raw, key))
        require(time(raw, "reviewedAt") <= now && time(raw, "validFrom") <= now && time(raw, "validUntil") > now) { "The sharing policy is not current." }
        r.supportedFields(obj(raw.get("allowedFields")))
        val evidence = r.text(raw, "evidenceMode")
        val synthetic = evidence == "SYNTHETIC_LOCAL_ONLY"
        require(evidence == "HUMAN_REVIEW_RECORDED" || synthetic && allowSynthetic && network.localDemo && context == "mock_computing_v1") {
            "Synthetic sharing is available only in the isolated local demonstration."
        }
        return Policy(raw, context, curriculum, level, r.number(raw, "revision", max = 1_000_000), time(raw, "validUntil"),
            r.number(raw, "maxLifetimeMs", max = 2_592_000_000), r.number(raw, "maxActiveGrants", max = 5).toInt(),
            r.number(raw, "maxGeneration", max = 1_000_000), synthetic)
    }
    private suspend fun Session.publicPolicy(context: String): Pair<Policy, Long> {
        val response = request(path("catalog", "sharingPolicies", r.id(context)))
        val record = nullableObject(response.value) ?: error("Sharing has no current permission policy for this learner context.")
        return policy(record, context, response.serverNow + 999) to response.serverNow
    }
    private suspend fun Session.ownerPolicy(): OwnerPolicy {
        val preferences = obj(request(path("users", auth.uid, "profile", "learnerPreferences")).value)
        require(preferences.get("onboardingComplete")?.isJsonPrimitive == true && preferences.get("onboardingComplete").asJsonPrimitive.isBoolean &&
            preferences.get("onboardingComplete").asBoolean) { "Complete learner preferences before enabling sharing." }
        val context = r.id(r.text(preferences, "curriculumVersion")); val (p, _) = publicPolicy(context)
        require(r.text(preferences, "curriculumVersion") == p.curriculum && r.text(preferences, "levelId") == p.level)
        val response = request(path("sharingEligibility", auth.uid, p.context))
        val e = nullableObject(response.value) ?: error("Sharing is unavailable until trusted learner eligibility and consent are registered.")
        r.exact(e, setOf("ownerUid", "contextId", "curriculumVersion", "levelId", "learnerRole", "permissionModelReference",
            "consentModelReference", "consentRecordReference", "consentValidUntil"))
        require(r.text(e, "ownerUid") == auth.uid && same(e, p.raw, setOf("contextId", "curriculumVersion", "levelId", "permissionModelReference", "consentModelReference")) &&
                r.text(e, "learnerRole") == r.text(p.raw, "eligibleLearnerRole") && time(e, "consentValidUntil") > response.serverNow + 999) {
            "Trusted learner eligibility or consent is unavailable."
        }
        r.text(e, "consentRecordReference", 1000)
        return OwnerPolicy(p, e)
    }
    private suspend fun Session.recipientRole(p: Policy): Pair<JsonObject, Long> {
        val response = request(path("sharingRecipientRoles", auth.uid, p.context)); val role = obj(response.value)
        r.exact(role, setOf("recipientUid", "contextId", "recipientRole", "validUntil"))
        require(r.text(role, "recipientUid") == auth.uid && r.text(role, "contextId") == p.context &&
            r.text(role, "recipientRole") == r.text(p.raw, "eligibleRecipientRole") && time(role, "validUntil") > response.serverNow + 999) {
            "A current independently assigned recipient role is required."
        }
        return role to response.serverNow
    }
    private fun acceptance(raw: JsonObject, owner: String, id: String): FamilyAcceptance {
        require(id in r.acceptanceIds)
        r.exact(raw, setOf("schemaVersion", "ownerUid", "recipientUid", "contextId", "policyRevision", "acceptanceId",
            "consentRecordReference", "fields", "acceptedAt", "expiresAt"))
        require(r.number(raw, "schemaVersion") == 1L && r.text(raw, "ownerUid") == owner && r.text(raw, "acceptanceId") == id)
        val recipient = r.id(r.text(raw, "recipientUid"), true); require(recipient != owner)
        val context = r.id(r.text(raw, "contextId")); r.number(raw, "policyRevision", max = 1_000_000)
        r.text(raw, "consentRecordReference", 1000); r.supportedFields(obj(raw.get("fields")))
        val expiry = time(raw, "expiresAt"); require(expiry > time(raw, "acceptedAt"))
        return FamilyAcceptance(id, recipient, context, expiry, raw.deepCopy())
    }
    private fun grant(rawSlot: JsonObject, owner: String, slot: String): FamilyGrant {
        require(slot in r.slots); val g = obj(rawSlot.get("grant")); val status = r.text(g, "status")
        require(status in setOf("ACTIVE", "REVOKED"))
        r.exact(rawSlot, if (status == "ACTIVE") setOf("grant", "projection") else setOf("grant"))
        r.exact(g, setOf("schemaVersion", "ownerUid", "recipientUid", "slotId", "generation", "contextId", "policyRevision", "acceptanceId",
            "consentRecordReference", "fields", "createdAt", "expiresAt", "status") + if (status == "REVOKED") setOf("revokedAt") else emptySet())
        require(r.number(g, "schemaVersion") == 1L && r.text(g, "ownerUid") == owner && r.text(g, "slotId") == slot)
        val recipient = r.id(r.text(g, "recipientUid"), true); require(recipient != owner)
        val generation = r.number(g, "generation", max = 1_000_000); val context = r.id(r.text(g, "contextId"))
        r.number(g, "policyRevision", max = 1_000_000); require(r.text(g, "acceptanceId") in r.acceptanceIds)
        r.text(g, "consentRecordReference", 1000); r.supportedFields(obj(g.get("fields")))
        val expiry = time(g, "expiresAt"); require(expiry > time(g, "createdAt"))
        if (status == "REVOKED") require(time(g, "revokedAt") >= time(g, "createdAt"))
        return FamilyGrant(slot, generation, recipient, context, expiry, status,
            FamilySummaryLink(owner, context, slot, generation), rawSlot.deepCopy())
    }

    suspend fun ownerState(): FamilyOwnerState = authenticated {
        val slots = nullableObject(request(path("sharedStudy", auth.uid)).value)
        val acceptances = nullableObject(request(path("familyAcceptances", auth.uid)).value)
        val grants = slots?.entrySet()?.map { (key, value) -> grant(obj(value), auth.uid, key) }.orEmpty()
        val accepted = acceptances?.entrySet()?.map { (key, value) -> acceptance(obj(value), auth.uid, key) }.orEmpty()
        try { val available = ownerPolicy(); FamilyOwnerState(grants, accepted, null, available.policy.synthetic) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            auth.checkActive()
            FamilyOwnerState(grants, accepted, error.message ?: "Sharing is unavailable until trusted policy and eligibility are current.", false)
        }
    }
    suspend fun createInvitation(): FamilyInvitation = authenticated {
        val state = ownerPolicy(); val p = state.policy
        val response = request(path("familyAcceptances", auth.uid)); val existing = nullableObject(response.value)
        val id = r.acceptanceIds.firstOrNull { existing?.has(it) != true } ?: error("All retained recipient acceptance identities are in use.")
        FamilyInvitation(auth.uid, p.context, id, p.revision, r.text(state.eligibility, "consentRecordReference", 1000),
            minOf(response.serverNow + p.lifetime, p.until, time(state.eligibility, "consentValidUntil")))
    }
    suspend fun acceptInvitation(code: String): FamilyAcceptance = authenticated {
        val invitation = r.invitation(code); require(invitation.ownerUid != auth.uid) { "A learner cannot accept their own invitation." }
        val (p, _) = publicPolicy(invitation.contextId); val (role, now) = recipientRole(p)
        require(invitation.policyRevision == p.revision && invitation.expiresAt > now)
        val target = path("familyAcceptances", invitation.ownerUid, invitation.acceptanceId)
        fun accepted(value: JsonElement): FamilyAcceptance {
            val a = acceptance(obj(value), invitation.ownerUid, invitation.acceptanceId)
            require(a.recipientUid == auth.uid && a.contextId == invitation.contextId && a.expiresAt > now && a.expiresAt <= invitation.expiresAt &&
                r.number(a.raw, "policyRevision") == p.revision && r.text(a.raw, "consentRecordReference", 1000) == invitation.consentRecordReference)
            return a
        }
        val previous = try { accepted(request(target).value) }
        catch (error: FamilyRestFailure) { if (error.status !in setOf(401, 403)) throw error; null }
        if (previous != null) {
            // Reading one's acceptance history is permitted after consent withdrawal. An exact
            // retry must therefore authorize an unchanged write, preserving its first acceptedAt.
            val confirmed = request(target, "PUT", previous.raw)
            require(confirmed.value == previous.raw) { "Recipient acceptance was not confirmed." }
            return@authenticated accepted(confirmed.value)
        }
        val body = JsonObject().apply {
            addProperty("schemaVersion", 1); addProperty("ownerUid", invitation.ownerUid); addProperty("recipientUid", auth.uid)
            addProperty("contextId", invitation.contextId); addProperty("policyRevision", p.revision); addProperty("acceptanceId", invitation.acceptanceId)
            addProperty("consentRecordReference", invitation.consentRecordReference); add("fields", r.fields); addProperty("acceptedAt", now)
            addProperty("expiresAt", minOf(invitation.expiresAt, p.until, now + p.lifetime, time(role, "validUntil")))
        }
        try { accepted(request(target, "PUT", body).value) }
        catch (error: IOException) {
            if (error is FamilyRestFailure && error.status in setOf(401, 403)) throw error
            // Unknown write outcomes are acknowledged only by a fresh exact owned acceptance.
            val reconciled = try { accepted(request(target).value) } catch (failure: Exception) { stopped(failure) }
            require(same(reconciled.raw, body, body.keySet() - "acceptedAt")) { "Recipient acceptance was not confirmed." }
            reconciled
        }
    }

    private fun index(value: JsonElement): Pair<Long, JsonObject> {
        val raw = nullableObject(value) ?: return 0L to JsonObject()
        r.exact(raw, setOf("count", "lastKey", "entries")); r.text(raw, "lastKey", 148)
        val count = r.number(raw, "count", max = 1_000_000); val entries = obj(raw.get("entries"))
        require(entries.size().toLong() == count && entries.has(r.text(raw, "lastKey", 148))) { "The protected completion index is incomplete." }
        return count to entries
    }
    private suspend fun Session.syncCompletions(p: Policy) {
        val inventory = nullableObject(request(path("catalog", "sharingLessonKeys", p.curriculum)).value) ?: JsonObject()
        val response = request(path("users", auth.uid, "lessonAttempts")); val attempts = nullableObject(response.value) ?: JsonObject()
        val desired = linkedMapOf<String, JsonObject>()
        for ((runId, value) in attempts.entrySet().sortedBy { it.key }) {
            try {
                val run = obj(value); r.id(runId)
                if (r.number(run, "schemaVersion") != 1L || r.text(run, "curriculumVersion") != p.curriculum || r.text(run, "runId") != runId) continue
                val module = r.id(r.text(run, "moduleId")); val version = r.number(run, "version", max = 1_000_000); val lesson = r.id(r.text(run, "lessonId"))
                val key = "$module:v$version:$lesson"
                if (inventory.get(key)?.isJsonPrimitive != true || !inventory.get(key).asJsonPrimitive.isString ||
                    inventory.get(key).asString != r.text(run, "packageSha256", 64) ||
                    r.number(run, "resolvedSteps", max = 1000) != r.number(run, "totalSteps", max = 1000) || time(run, "completedAt") > response.serverNow + 999) continue
                desired.putIfAbsent(key, JsonObject().apply {
                    for (field in listOf("runId", "curriculumVersion", "moduleId", "version", "lessonId")) add(field, run.get(field).deepCopy())
                })
            } catch (_: IllegalArgumentException) { /* Unsupported/corrupt private runs never become a shared count. */ }
        }
        val target = path("users", auth.uid, "lessonCompletionIndex", p.curriculum)
        for ((key, entry) in desired) {
            var complete = false
            var failure: IOException? = null
            for (attempt in 0..2) {
                val (count, entries) = index(request(target).value)
                if (entries.has(key)) {
                    require(same(obj(entries.get(key)), entry, setOf("curriculumVersion", "moduleId", "version", "lessonId"))) { "Completion identity changed on the server." }
                    complete = true; break
                }
                require(count < 1_000_000)
                val patch = JsonObject().apply { addProperty("count", count + 1); addProperty("lastKey", key); add("entries/$key", entry) }
                try { request(target, "PATCH", patch); complete = true; break }
                catch (error: IOException) { failure = error }
            }
            if (!complete) {
                val (_, entries) = index(request(target).value)
                require(entries.has(key) && same(obj(entries.get(key)), entry, setOf("curriculumVersion", "moduleId", "version", "lessonId"))) {
                    failure?.message ?: "The unique completion was not confirmed. Refresh and retry."
                }
            }
        }
    }
    private fun projection(g: JsonObject, count: Long, now: Long): JsonObject = JsonObject().apply {
        for (key in listOf("schemaVersion", "ownerUid", "recipientUid", "slotId", "generation", "contextId", "policyRevision", "expiresAt")) add(key, g.get(key).deepCopy())
        add("summary", JsonObject().apply { addProperty("completedLessonCount", count); addProperty("asOf", now) })
    }
    private suspend fun Session.writeSlot(slot: String, generation: Long?, transform: suspend (FamilyRestResponse, FamilyGrant?) -> JsonObject): FamilyGrant {
        val target = path("sharedStudy", auth.uid, slot)
        var lastError: IOException? = null
        for (attempt in 0..2) {
            val old = request(target, readEtag = true); val current = nullableObject(old.value)?.let { grant(it, auth.uid, slot) }
            if (generation != null) require(current?.generation == generation) { "This sharing generation changed. Refresh before acting." }
            val next = transform(old, current)
            // Only a fresh exact revoked tombstone can acknowledge without another authorized write.
            // An unchanged ACTIVE grant still requires server authorization (role/policy may have changed).
            if (old.value == next && current?.status == "REVOKED") return grant(next, auth.uid, slot)
            try {
                val response = request(target, "PUT", next, requireNotNull(old.etag) { "The server did not provide a sharing revision." })
                require(response.value == next) { "The sharing acknowledgement did not match the requested state." }
                return grant(next, auth.uid, slot)
            } catch (error: IOException) {
                lastError = error
                val fresh = request(target)
                val desiredRevoked = r.text(obj(next.get("grant")), "status") == "REVOKED"
                if (fresh.value == next && (next != old.value || desiredRevoked)) return grant(next, auth.uid, slot)
                if (generation != null && !fresh.value.isJsonNull) require(grant(obj(fresh.value), auth.uid, slot).generation == generation) {
                    "This sharing generation changed. The newer grant was kept."
                }
                if (error is FamilyRestFailure && error.status !in setOf(401, 403, 412)) throw error
            }
        }
        throw IOException(lastError?.message ?: "The sharing change is not confirmed. Reconnect and refresh.")
    }

    suspend fun grant(acceptanceId: String, slotId: String): FamilyGrant = authenticated {
        require(acceptanceId in r.acceptanceIds && slotId in r.slots)
        val initial = ownerPolicy(); syncCompletions(initial.policy)
        writeSlot(slotId, null) { old, current ->
            val state = ownerPolicy(); val p = state.policy
            require(r.slots.indexOf(slotId) < p.slots)
            if (current?.status == "ACTIVE") {
                require(r.text(obj(current.raw.get("grant")), "acceptanceId") == acceptanceId && current.contextId == p.context && current.expiresAt > old.serverNow) {
                    "Revoke the current grant before changing its recipient or scope."
                }
                return@writeSlot current.raw
            }
            val accepted = acceptance(obj(request(path("familyAcceptances", auth.uid, acceptanceId)).value), auth.uid, acceptanceId)
            require(accepted.contextId == p.context && accepted.expiresAt > old.serverNow && r.number(accepted.raw, "policyRevision") == p.revision &&
                r.text(accepted.raw, "consentRecordReference", 1000) == r.text(state.eligibility, "consentRecordReference", 1000))
            val generation = (current?.generation ?: 0) + 1; require(generation <= p.generations)
            val roleResponse = request(path("catalog", "sharingPolicies", p.context)) // Fresh server time without exposing recipient role records.
            val now = maxOf(old.serverNow, roleResponse.serverNow)
            val countResponse = request(path("users", auth.uid, "lessonCompletionIndex", p.curriculum)); val (count, _) = index(countResponse.value)
            val g = JsonObject().apply {
                addProperty("schemaVersion", 1); addProperty("ownerUid", auth.uid); addProperty("recipientUid", accepted.recipientUid)
                addProperty("slotId", slotId); addProperty("generation", generation); addProperty("contextId", p.context); addProperty("policyRevision", p.revision)
                addProperty("acceptanceId", acceptanceId); addProperty("consentRecordReference", r.text(state.eligibility, "consentRecordReference", 1000))
                add("fields", r.fields); addProperty("createdAt", maxOf(now, countResponse.serverNow))
                addProperty("expiresAt", minOf(accepted.expiresAt, p.until, now + p.lifetime, time(state.eligibility, "consentValidUntil")))
                addProperty("status", "ACTIVE")
            }
            JsonObject().apply { add("grant", g); add("projection", projection(g, count, time(g, "createdAt"))) }
        }
    }
    suspend fun refresh(slotId: String, generation: Long): FamilyGrant = authenticated {
        require(slotId in r.slots && generation in 1L..1_000_000L)
        val initial = ownerPolicy(); syncCompletions(initial.policy)
        writeSlot(slotId, generation) { _, current ->
            val state = ownerPolicy(); val existing = requireNotNull(current)
            require(existing.status == "ACTIVE" && existing.contextId == state.policy.context)
            val g = obj(existing.raw.get("grant")); require(r.number(g, "policyRevision") == state.policy.revision)
            val response = request(path("users", auth.uid, "lessonCompletionIndex", state.policy.curriculum)); val (count, _) = index(response.value)
            JsonObject().apply { add("grant", g.deepCopy()); add("projection", projection(g, count, response.serverNow)) }
        }
    }
    suspend fun revoke(slotId: String, generation: Long): Unit = authenticated {
        require(slotId in r.slots && generation in 1L..1_000_000L)
        writeSlot(slotId, generation) { response, current ->
            val existing = requireNotNull(current)
            if (existing.status == "REVOKED") existing.raw else JsonObject().apply {
                add("grant", obj(existing.raw.get("grant")).deepCopy().apply {
                    addProperty("status", "REVOKED"); addProperty("revokedAt", response.serverNow)
                })
            }
        }
        Unit
    }
    suspend fun readSummary(code: String): FamilySummarySnapshot = authenticated {
        val link = r.link(code); require(link.ownerUid != auth.uid)
        val (p, _) = publicPolicy(link.contextId); val (role, _) = recipientRole(p)
        val started = System.nanoTime()
        val response = request(path("sharedStudy", link.ownerUid, link.slotId, "projection"))
        // Date is a header-time lower bound. Include its fractional second and the entire
        // request/body/resume time so a delayed response cannot extend the displayed lifetime.
        val elapsed = ((System.nanoTime() - started).coerceAtLeast(0) + 999_999) / 1_000_000
        val raw = obj(response.value); val snapshot = r.snapshot(raw, link, auth.uid, response.serverNow + 999 + elapsed)
        require(r.number(raw, "policyRevision") == p.revision && snapshot.expiresAt <= minOf(p.until, time(role, "validUntil")))
        snapshot
    }
}
