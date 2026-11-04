package com.studypath.app.data

import android.content.SharedPreferences
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.AuthResult
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.MutableData
import com.google.firebase.database.Transaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.studypath.app.domain.ReminderDeliveryGate
import com.studypath.app.domain.PrerequisiteCodec
import com.studypath.app.domain.PrerequisiteDocument
import com.studypath.app.domain.Prerequisite
import com.studypath.app.domain.AcademicAccess

/**
 * Uses Firebase Authentication and Realtime Database only. App initialization owns emulator routing
 * and enabling disk persistence before constructing this repository.
 *
 * This pilot binds one installation to one UID. Logout keeps queued writes for that account and
 * pauses the database. It cannot erase the Firebase SDK disk cache or support account switching.
 */
class FirebaseStudyRepository(
    private val auth: FirebaseAuth,
    private val database: FirebaseDatabase,
    private val preferences: SharedPreferences,
    private val packageStore: TextPackageStore? = null,
    private val lessonRunStore: LessonRunStore? = null,
    private val readingStore: ReadingStateStore? = null,
    private val readingContextNetwork: ReadingContextNetwork? = null,
    private val packageAssetDownloader: PackageAssetDownloader? = null,
    packageDownloadDirectory: java.io.File? = null,
    private val operationDirectory: java.io.File? = null,
    private val reminderChanged: (uid: String, id: String, value: StudyReminder?) -> Unit,
) {
    private val packageDownloadDirectory = packageDownloadDirectory?.let {
        require(!java.nio.file.Files.isSymbolicLink(it.toPath())) { "Download staging must not be a symbolic link." }
        it.canonicalFile
    }
    private val gate = SessionGate({ boundUid() }, { auth.currentUser?.uid })
    private val operationJournal = operationDirectory?.let { OperationJournal(java.io.File(it, "metadata"), gate) }
    private val operationPayloads = operationDirectory?.let { OperationPayloadStore(java.io.File(it, "payloads"), gate, requireNotNull(operationJournal)) }
    private val operationTasks = ConcurrentHashMap<String, Task<Void>>()
    private val operationLock = Any()
    private val operationJson = com.google.gson.GsonBuilder().serializeNulls().disableHtmlEscaping().create()
    private val authMutex = Mutex()
    private val saveMutex = Mutex()
    private val packageMutex = Mutex()
    private val lessonMutex = Mutex()
    private val reminderMutex = Mutex()
    private val removingLessonPackages = ConcurrentHashMap.newKeySet<TextPackageKey>()
    private val registrations = ConcurrentHashMap.newKeySet<Registration>()
    private val confirmedReadingIds = ConcurrentHashMap.newKeySet<String>()
    private val authRequests = AuthRequestFence()
    private val reminderWrites = ConcurrentHashMap<String, Task<Void>>()

    fun boundUid(): String? = preferences.getString(BOUND_UID, null)

    suspend fun lessonRun(module: InteractiveModule, lessonId: String): LessonRun? = lessonMutex.withLock {
        val token = gate.capture()
        withContext(Dispatchers.IO) {
            checkedInteractiveModule(token, module)
            requireNotNull(lessonRunStore).read(token.uid, module, lessonId).also { gate.check(token) }
        }
    }

    suspend fun saveLessonRun(module: InteractiveModule, run: LessonRun) = lessonMutex.withLock {
        val token = gate.capture()
        withContext(Dispatchers.IO) {
            checkedInteractiveModule(token, module)
            requireNotNull(lessonRunStore).save(token.uid, module, run)
            gate.check(token)
        }
    }

    suspend fun lessonRunConfirmedAt(module: InteractiveModule, run: LessonRun): Long? = lessonMutex.withLock {
        val token = gate.capture()
        withContext(Dispatchers.IO) {
            checkedInteractiveModule(token, module)
            requireNotNull(lessonRunStore).confirmedAt(token.uid, module, run).also { gate.check(token) }
        }
    }

    suspend fun pendingLessonRuns(module: InteractiveModule): List<LessonRun> = lessonMutex.withLock {
        val token = gate.capture()
        withContext(Dispatchers.IO) {
            checkedInteractiveModule(token, module)
            requireNotNull(lessonRunStore).pending(token.uid, module).also { gate.check(token) }
        }
    }

    suspend fun lessonHistory(module: InteractiveModule): List<LessonRun> = lessonMutex.withLock {
        val token = gate.capture()
        withContext(Dispatchers.IO) {
            checkedInteractiveModule(token, module)
            requireNotNull(lessonRunStore).history(token.uid, module).also { gate.check(token) }
        }
    }

    suspend fun syncPendingLessonRuns(module: InteractiveModule) {
        pendingLessonRuns(module).forEach { submitLessonRun(module, it) }
    }

    /** Completion is retained first. Cancellation never turns a queued SDK write into an acknowledgement. */
    suspend fun submitLessonRun(module: InteractiveModule, run: LessonRun) {
        val token = gate.capture()
        val store = requireNotNull(lessonRunStore)
        val record = lessonMutex.withLock {
            withContext(Dispatchers.IO) {
                checkedInteractiveModule(token, module)
                require(store.get(token.uid, module, run.runId) == run) { "Save this lesson result before syncing it." }
                LessonEngine.attempt(module, run)
            }
        }
        val ref = privateRef(token, "lessonAttempts").child(run.runId)
        write(token, ref, record)
        confirmLessonRun(token, module, run, record)
    }

    private suspend fun confirmLessonRun(token: SessionToken, module: InteractiveModule, run: LessonRun,
                                         record: Map<String, Any> = LessonEngine.attempt(module, run)) {
        val store = requireNotNull(lessonRunStore)
        val acknowledged = read(token, privateRef(token, "lessonAttempts").child(run.runId))
        require(acknowledged.children.map { it.key }.toSet() == record.keys && record.all { (key, value) ->
            val actual = acknowledged.child(key).value
            if (value is Number) actual is Number && value.toDouble() == actual.toDouble() else value == actual
        }) { "The server lesson result differs from the saved attempt." }
        lessonMutex.withLock {
            withContext(Dispatchers.IO) {
                gate.check(token)
                store.confirm(token.uid, module, run, System.currentTimeMillis())
                gate.check(token)
            }
        }
    }

    private fun checkedInteractiveModule(token: SessionToken, module: InteractiveModule) {
        gate.check(token)
        val summary = module.summary
        require(TextPackageKey(summary.curriculumVersion, summary.moduleId, summary.version) !in removingLessonPackages) {
            "This download is being removed. Wait for removal to finish before saving lesson changes."
        }
        val retained = requireNotNull(packageStore).read(token.uid, summary.curriculumVersion, summary.moduleId, summary.version)
        require(retained?.ready == true && retained.verified.summary == summary &&
            InteractiveLessonParser.parse(retained.verified) == module) { "Download and verify the complete lesson before opening it." }
        gate.check(token)
    }

    /** Ready retries are local no-ops. Initial downloads require published bytes and a server acknowledgement. */
    suspend fun downloadTextPackage(summary: PublishedModuleSummary, progress: (String) -> Unit = {}): StoredTextPackage = packageMutex.withLock {
        val token = gate.capture()
        val store = requireNotNull(packageStore) { "Text package storage is unavailable." }
        val key = TextPackageKey(summary.curriculumVersion, summary.moduleId, summary.version)
        require(!store.isRemoving(token.uid, key)) { "This download is awaiting removal. Retry its retained removal from Profile or this lesson's controls." }
        val path = packagePath(key)
        require(summary.approved) { "Choose an approved complete package." }
        val retained = withContext(Dispatchers.IO) {
            gate.check(token)
            store.read(token.uid, key.curriculumVersion, key.moduleId, key.version)
        }
        gate.check(token)
        if (retained?.ready == true) {
            require(retained.verified.summary == summary) { "This immutable version has different published metadata." }
            return@withLock retained
        }
        progress("Downloading package…")
        val published = read(token, database.getReference("catalog/moduleIndex").child(path))
        require(PublishedModuleCatalog.parse(key.curriculumVersion, key.moduleId, "v${key.version}", published.catalogFields()) == summary) {
            "The selected package no longer matches the published version."
        }
        val transport = read(token, database.getReference("catalog/modulePackages").child(path))
        require(transport.children.map { it.key }.toSet() == setOf("schemaVersion", "manifestJson", "textJson") &&
            transport.number("schemaVersion") == 1.0) { "The text package transport is invalid." }
        progress("Verifying package text…")
        val verified = withContext(Dispatchers.IO) {
            TextPackageVerifier.verify(summary, transport.text("manifestJson"), transport.text("textJson"))
        }
        val receiptRef = privateRef(token, "offlinePackages").child(path)
        val previous = read(token, receiptRef).takeIf { it.exists() }?.let { packageReceipt(it, summary) }
        if (retained != null && previous != null) require(retained.downloadedAt == previous.first) {
            "The retained package and managed receipt have different download times."
        }
        progress(if (verified.assets.isEmpty()) "Verifying files…" else "Downloading and verifying PDF files…")
        val staged = withContext(Dispatchers.IO) {
            gate.check(token)
            val downloadedAt = previous?.first ?: retained?.downloadedAt ?: System.currentTimeMillis()
            if (verified.assets.isEmpty()) store.stage(token.uid, verified, downloadedAt)
            else {
                val context = kotlin.coroutines.coroutineContext
                requireNotNull(packageAssetDownloader) { "Complete package downloading is unavailable." }
                    .download(verified, requireNotNull(packageDownloadDirectory)) {
                        context.ensureActive()
                        gate.check(token)
                    }.use { store.stage(token.uid, verified, downloadedAt, assets = it.files) }
            }
        }
        gate.check(token)
        progress("Confirming sync…")
        // Cancelling Task.await does not cancel the SDK write. Only the live acknowledged path below finalizes the file.
        write(token, receiptRef, mapOf(
            "schemaVersion" to 1, "moduleId" to key.moduleId, "version" to key.version,
            "curriculumVersion" to key.curriculumVersion, "packageSha256" to summary.packageSha256,
            "textBytes" to summary.textBytes, "downloadedAt" to staged.downloadedAt,
            "confirmedAt" to ServerValue.TIMESTAMP,
        ))
        val acknowledged = packageReceipt(read(token, receiptRef), summary)
        require(acknowledged.first == staged.downloadedAt) { "The acknowledged download time changed." }
        val ready = withContext(Dispatchers.IO) {
            gate.check(token)
            store.confirm(token.uid, verified, acknowledged.second)
        }
        gate.check(token)
        ready
    }

    /** Cached database rows alone never make a retained local package ready. */
    fun downloadedTextPackages(): List<StoredTextPackage> {
        val token = gate.capture()
        reconcilePackageRemovals(token)
        val result = requireNotNull(packageStore).list(token.uid).filter { it.ready }
        gate.check(token)
        return result
    }

    fun openTextPackage(key: TextPackageKey): StoredTextPackage? {
        val token = gate.capture()
        packagePath(key)
        reconcilePackageRemovals(token)
        val result = requireNotNull(packageStore).read(token.uid, key.curriculumVersion, key.moduleId, key.version)
        gate.check(token)
        return result?.takeIf { it.ready }
    }

    fun openPackageAsset(key: TextPackageKey, assetId: String): java.io.File? {
        val token = gate.capture()
        reconcilePackageRemovals(token)
        val result = requireNotNull(packageStore).assetFile(token.uid, key, assetId)
        gate.check(token)
        return result
    }

    /** Serialize with downloads; retain local bytes until the selected server deletion is acknowledged. */
    suspend fun removeTextPackage(key: TextPackageKey) {
        val token = gate.capture()
        if (operationJournal != null) packageStore?.removalOperationId(token.uid, key)?.let { retryOperation(it); return }
        removeNewTextPackage(key)
    }

    private suspend fun removeNewTextPackage(key: TextPackageKey) = packageMutex.withLock {
        val token = gate.capture()
        val store = requireNotNull(packageStore)
        val path = packagePath(key)
        lessonMutex.withLock {
            withContext(Dispatchers.IO) {
                gate.check(token)
                if (lessonRunStore != null) {
                    val retained = store.read(token.uid, key.curriculumVersion, key.moduleId, key.version)
                    val module = retained?.let { runCatching { InteractiveLessonParser.parse(it.verified) }.getOrNull() }
                    val pending = module?.let { lessonRunStore.pending(token.uid, it) }.orEmpty()
                    if (module != null && pending.isNotEmpty()) {
                        val history = read(token, database.getReference("catalog/modulePublicationHistory").child(path))
                        require(history.child("schemaVersion").value == 1L &&
                            history.child("packageSha256").value == module.summary.packageSha256 &&
                            history.child("withdrawal/retention").value == "REVIEW_REQUIRED_FOR_RETAINED_COPIES") {
                            "Sync your saved practice results before removing this download. Results for withdrawn content are retained separately when further review is required."
                        }
                        pending.forEach { preserveWithdrawnResult(token, module, it) }
                    }
                }
                removingLessonPackages.add(key)
            }
        }
        try {
            reminderMutex.withLock {
                val knownReminders = withTimeoutOrNull(5_000) { reminders().first() }
                    ?: error("Study reminders are not available offline yet. Reconnect and retry removal; the download and pending work were kept.")
                val linked = knownReminders.filter {
                    ReminderTargets.target(it) == ReminderTarget.ModuleVersion(key.curriculumVersion, key.moduleId, key.version)
                }
                val patch = mutableMapOf<String, Any?>("offlinePackages/$path" to null)
                linked.forEach { patch["reminders/${it.id}"] = null }
                awaitOwned(token, deleteWithReminders(token, patch, linked, "removePackage/$path"))
            }
            withContext(Dispatchers.IO) {
                gate.check(token)
                operationJournal?.records(token)?.lastOrNull { it.relativePath == "removePackage/$path" }
                    ?.let { store.finishRemoval(token.uid, key, it.operationId) }
                if (operationJournal == null) store.removalOperationId(token.uid, key)?.let { store.finishRemoval(token.uid, key, it) }
            }
            gate.check(token)
        } finally {
            removingLessonPackages.remove(key)
        }
    }

    fun retainedTextPackages(): List<StoredTextPackage> {
        val token = gate.capture()
        reconcilePackageRemovals(token)
        return requireNotNull(packageStore).list(token.uid).also { gate.check(token) }
    }

    private fun removalKey(path: String): TextPackageKey {
        val parts = path.removePrefix("removePackage/").split('/')
        require(parts.size == 3 && parts[2].startsWith("v"))
        return TextPackageKey(parts[0], parts[1], parts[2].drop(1).toInt()).also(::packagePath)
    }

    private fun finishPackageRemoval(token: SessionToken, operation: OperationRecord) {
        if (!operation.relativePath.startsWith("removePackage/")) return
        gate.check(token)
        val key = removalKey(operation.relativePath)
        val store = requireNotNull(packageStore)
        store.finishRemoval(token.uid, key, operation.operationId)
    }

    private fun reconcilePackageRemovals(token: SessionToken) {
        operationJournal?.records(token)?.filter { it.state == OperationSyncState.CONFIRMED && it.relativePath.startsWith("removePackage/") }
            ?.forEach { finishPackageRemoval(token, it) }
    }

    private fun preserveWithdrawnResult(token: SessionToken, module: InteractiveModule, run: LessonRun) = synchronized(operationLock) {
        gate.check(token)
        val journal = requireNotNull(operationJournal) { "Result recovery storage is unavailable; this download was kept." }
        val path = "withdrawnLessonAttempts/${run.runId}"
        val json = operationJson.toJson(LessonEngine.attempt(module, run))
        val previous = journal.records(token).lastOrNull { it.relativePath == path }
        val record = previous?.also { require(it.payloadSha256 == OperationJournal.payloadFingerprint(json)) }
            ?: journal.enqueue(token, newRecordId(), path, json, System.currentTimeMillis())
        requireNotNull(operationPayloads).retain(token, record.operationId, path, json)
    }

    private fun packagePath(key: TextPackageKey): String {
        DatabaseRecords.requireKey(key.curriculumVersion); DatabaseRecords.requireKey(key.moduleId)
        require(key.version in 1..1_000_000)
        return "${key.curriculumVersion}/${key.moduleId}/v${key.version}"
    }

    private fun packageReceipt(snapshot: DataSnapshot, summary: PublishedModuleSummary): Pair<Long, Long> {
        require(snapshot.children.map { it.key }.toSet() == setOf("schemaVersion", "moduleId", "version",
            "curriculumVersion", "packageSha256", "textBytes", "downloadedAt", "confirmedAt"))
        require(snapshot.number("schemaVersion") == 1.0 && snapshot.text("moduleId") == summary.moduleId &&
            snapshot.number("version") == summary.version.toDouble() && snapshot.text("curriculumVersion") == summary.curriculumVersion &&
            snapshot.text("packageSha256") == summary.packageSha256 && snapshot.number("textBytes") == summary.textBytes.toDouble()) {
            "The managed package receipt does not match its immutable version."
        }
        fun timestamp(name: String): Long {
            val number = snapshot.number(name)
            require(number.isFinite() && number in 1.0..4_102_444_800_000.0 && number % 1.0 == 0.0)
            return number.toLong()
        }
        val downloadedAt = timestamp("downloadedAt"); val confirmedAt = timestamp("confirmedAt")
        // The download time comes from the device; the acknowledgement comes from the server.
        return downloadedAt to confirmedAt
    }

    fun currentSession(): UserSession? = runCatching {
        gate.capture()
        auth.currentUser?.let { UserSession(it.uid, it.email.orEmpty(), it.displayName.orEmpty()) }
    }.getOrNull()

    internal suspend fun familyCredentials(): FamilyCredentials {
        val session = gate.capture()
        val result = awaitOwned(session, requireNotNull(auth.currentUser).getIdToken(true))
        return FamilyCredentials(session.uid, requireNotNull(result.token)) { gate.check(session) }
    }

    suspend fun resumeSession(): UserSession? {
        if (authRequests.hasPending()) return null
        val user = auth.currentUser ?: return null
        bindOrReject(user.uid)
        val token=gate.activate()
        recoverLegacyReadingQueue(token)
        database.goOnline()
        return UserSession(user.uid, user.email.orEmpty(), user.displayName.orEmpty())
    }

    /** On first upgrade, export the OLD SDK cache before a stricter server can reject queued source writes. */
    private suspend fun recoverLegacyReadingQueue(token:SessionToken) {
        val store=readingStore ?: return
        if(preferences.getBoolean(READING_JOURNAL_READY,false)) return
        database.goOffline()
        // A listener emits the merged local event cache, including restored pending writes.
        // get() may return only the persisted server cache and omit an unsynced child.
        val cached=try { withTimeout(5_000) { observe("savedReadings") { it }.first() } }
            catch(error:Exception) {
                if(error is CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                throw LegacyReadingRecoveryException(error)
            }
        try {
            withContext(Dispatchers.IO) {
                gate.check(token)
                cached.children.forEach { child ->
                    val saved=parseReading(child)
                    if(store.get(token.uid,saved.id)==null) {
                        store.retain(token.uid,saved,null)
                        store.snapshotPending(token.uid,saved.id)
                    }
                }
                gate.check(token)
                check(preferences.edit().putBoolean(READING_JOURNAL_READY,true).commit())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw LegacyReadingRecoveryException(error)
        }
    }

    suspend fun register(email: String, password: String, displayName: String): UserSession = authMutex.withLock {
        try {
            if (boundUid() != null) throw InstallationBindingException()
            require(email.isNotBlank() && PasswordPolicy.accepts(password) && displayName.trim().length in 1..80)
            if (authRequests.hasPending()) throw AuthRequestPendingException()
            closeWorkspace()
            val epoch = authRequests.epoch()
            val user = awaitAuth(auth.createUserWithEmailAndPassword(email.trim(), password), epoch).user
                ?: error("Registration did not return an account.")
            requireAuthEpoch(epoch)
            bindOrReject(user.uid)
            val token = gate.activate()
            database.goOnline()
            createInitialProfile(token, mapOf(
                "displayName" to displayName.trim(), "selectedCurriculumVersion" to "bsit_demo_v1",
                "createdAt" to ServerValue.TIMESTAMP, "updatedAt" to ServerValue.TIMESTAMP,
                "learnerPreferences" to LearnerPreferenceRecords.preferences(LearnerPreferences()),
            ))
            UserSession(user.uid, user.email.orEmpty(), displayName.trim())
        } catch (error: CancellationException) {
            logout()
            throw error
        }
    }

    suspend fun login(email: String, password: String): UserSession = authMutex.withLock {
        try {
            require(email.isNotBlank() && password.isNotBlank())
            if (authRequests.hasPending()) throw AuthRequestPendingException()
            closeWorkspace()
            val epoch = authRequests.epoch()
            val user = awaitAuth(auth.signInWithEmailAndPassword(email.trim(), password), epoch).user
                ?: error("Sign in did not return an account.")
            requireAuthEpoch(epoch)
            bindOrReject(user.uid)
            val token=gate.activate()
            recoverLegacyReadingQueue(token)
            database.goOnline()
            UserSession(user.uid, user.email.orEmpty(), user.displayName.orEmpty())
        } catch (error: CancellationException) {
            logout()
            throw error
        }
    }

    /** The caller also cancels WorkManager jobs tagged with the returned UID and clears private UI. */
    fun logout(): String? = ReminderDeliveryGate.endSession {
        val uid = boundUid()
        authRequests.cancel()
        closeWorkspace()
        auth.signOut()
        uid
    }

    suspend fun saveProfileName(name: String) {
        require(name.trim().length in 1..80) { "Enter a name of 1–80 characters." }
        val token = gate.capture()
        val profile = privateRef(token, "profile")
        val patch = mapOf<String, Any>("displayName" to name.trim())
        awaitOwned(token, trackedSubmission(token, profile, patch, merge = true) { profile.updateChildren(patch) })
    }

    fun profileName(): Flow<String?> = observe("profile") { snapshot ->
        snapshot.optionalText("displayName")?.also {
            require(it.isNotBlank() && it.length <= 80) { "The stored profile name is invalid." }
        }
    }

    fun learnerPreferences(): Flow<LearnerPreferences> = observe("profile") { snapshot ->
        val child = snapshot.child("learnerPreferences")
        LearnerPreferenceRecords.fromProfile(if (child.exists()) mapOf("learnerPreferences" to child.preferenceFields()) else emptyMap())
    }

    /** Policies are published by trusted tooling; private profile flags cannot approve them. */
    fun academicPolicies(): Flow<List<AcademicPolicySnapshot>> = observeCatalog("academicPolicies") { snapshot ->
        require(snapshot.childrenCount <= 200) { "The academic policy list is too large." }
        snapshot.children.map { AcademicPolicyRecords.parse(it.key.orEmpty(), it.catalogFields()) }
    }

    suspend fun academicAccess(courseId: String, capability: AcademicCapability, planVersion: String? = null): AcademicAccess =
        academicAccess(gate.capture(), courseId, capability, planVersion)

    private suspend fun academicAccess(token: SessionToken, courseId: String, capability: AcademicCapability,
                                       planVersion: String? = null): AcademicAccess {
        DatabaseRecords.requireKey(courseId)
        val profile = readAcademic(token, privateRef(token, "profile"))
        val selection = LearnerPreferenceRecords.fromProfile(profile.catalogFields())
        val curriculum = selection.curriculumVersion
        val options = curriculum?.let {
            parsePreferenceOptions(readAcademic(token, database.getReference("catalog/learnerPreferenceOptions").child(it)), it)
        }
        val policy = curriculum?.let {
            readAcademic(token, database.getReference("catalog/academicPolicies").child(it)).let { snapshot ->
                if (snapshot.exists()) AcademicPolicyRecords.parse(it, snapshot.catalogFields()) else null
            }
        }
        gate.check(token)
        return AcademicAccess.resolve(selection, options, policy, courseId, capability, planVersion)
    }

    private suspend fun readAcademic(token: SessionToken, ref: DatabaseReference): DataSnapshot {
        gate.check(token)
        val connected = withTimeoutOrNull(5_000) { connection().first() }
            ?: error("Connection state is unavailable. Reconnect before saving this academic edit.")
        if (connected) return read(token, ref)
        // get() can wait three seconds per persisted-cache lookup after a process restart.
        // A listener emits only a complete cached result, including pending local edits.
        val cached = withTimeoutOrNull(5_000) { observeReference({ ref }) { it }.first() }
            ?: error("This academic record is not fully cached. Reconnect before saving this edit. Your existing records are kept.")
        gate.check(token)
        return cached
    }

    fun learnerPreferenceOptions(curriculumVersion: String): Flow<LearnerPreferenceOptions?> {
        DatabaseRecords.requireKey(curriculumVersion)
        return observeCatalog("learnerPreferenceOptions/$curriculumVersion") { parsePreferenceOptions(it, curriculumVersion) }
    }

    /** Discover choices across levels; no learner context is inferred from the demo catalog. */
    fun learnerPreferenceOptions(): Flow<List<LearnerPreferenceOptions>> =
        observeCatalog("learnerPreferenceOptions") { snapshot ->
            require(!snapshot.exists() || snapshot.hasChildren()) { "The learning choices are invalid." }
            require(snapshot.childrenCount <= 200) { "The learning choice list is too large." }
            snapshot.children.map { child ->
                requireNotNull(parsePreferenceOptions(child, child.key.orEmpty()))
            }.sortedBy { it.curriculumVersion }
        }

    /** Immutable metadata lives separately from the legacy demonstration lesson payloads. */
    fun publishedModuleIndex(curriculumVersion: String): Flow<List<PublishedModuleSummary>> {
        DatabaseRecords.requireKey(curriculumVersion)
        return observeCatalog("moduleIndex/$curriculumVersion") { snapshot ->
            require(!snapshot.exists() || snapshot.hasChildren()) { "The published module index is invalid." }
            snapshot.children.flatMap { module ->
                require(module.hasChildren()) { "The published module version index is invalid." }
                module.children.map { version ->
                    PublishedModuleCatalog.parse(curriculumVersion, module.key.orEmpty(), version.key.orEmpty(), version.catalogFields())
                }
            }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun moduleLibrary(): Flow<ModuleLibrary> = learnerPreferences().flatMapLatest { value ->
        val curriculum = value.curriculumVersion
        if (!value.onboardingComplete || curriculum == null) flowOf(PublishedModuleCatalog.select(value, null, emptyList()))
        else combine(learnerPreferenceOptions(curriculum), publishedModuleIndex(curriculum)) { options, modules ->
            PublishedModuleCatalog.select(value, options, modules)
        }
    }

    suspend fun saveLearnerPreferences(value: LearnerPreferences, displayName: String? = null) {
        val token = gate.capture()
        val patch = LearnerPreferenceRecords.profilePatch(value, displayName, ServerValue.TIMESTAMP)
        if (value.onboardingComplete) {
            val curriculum = value.curriculumVersion!!
            val snapshot = read(token, database.getReference("catalog/learnerPreferenceOptions").child(curriculum))
            require(value.supportedBy(parsePreferenceOptions(snapshot, curriculum))) { "Choose a supported published learner context before completing onboarding." }
        }
        val profile = privateRef(token, "profile")
        awaitOwned(token, trackedSubmission(token, profile, patch, merge = true) { profile.updateChildren(patch) })
    }

    fun savedReadings(): Flow<List<SavedReading>> = combine(observe("savedReadings") { snapshot ->
        snapshot.children.map(::parseReading)
    }, readingStore?.changes ?: flowOf(0L)) { remote, _ ->
        val token=gate.capture()
        val pending=readingStore?.list(token.uid).orEmpty().filter { it.snapshotSync != ReadingSync.CONFIRMED || it.deletionEditId != null }
        (remote.associateBy { it.id } + pending.associate { it.saved.id to it.saved }).values.sortedByDescending { it.savedAt }.also { gate.check(token) }
    }

    fun readingContextDefinitions(): Flow<List<ReadingContextDefinition>> = observeCatalog("readingContexts") { snapshot ->
        require(snapshot.childrenCount <= ReadingContextRecords.MAX_DEFINITIONS)
        snapshot.children.map { ReadingContextRecords.definition(it.key.orEmpty(),it.catalogFields()) }
    }

    suspend fun resolveReadingContext(context: ReadingContext): ResolvedReadingContext {
        val token=gate.capture()
        val network=requireNotNull(readingContextNetwork) { "Fresh reviewed reading context is unavailable." }
        val user=requireNotNull(auth.currentUser)
        require(user.uid==token.uid)
        val idToken=requireNotNull(awaitOwned(token,user.getIdToken(false)).token)
        val packages=packageStore?.let { downloadedTextPackages().map { it.verified } }.orEmpty()
        val result=network.resolve(context,token.uid,idToken,packages)
        gate.check(token); return result
    }

    fun retainedReading(id:String): RetainedReading? {
        val token=gate.capture(); DatabaseRecords.requireKey(id)
        return readingStore?.get(token.uid,id).also { gate.check(token) }
    }

    fun readingLocalState(id:String):Flow<RetainedReading?> = flow {
        val token=gate.capture(); DatabaseRecords.requireKey(id)
        val store=readingStore
        if(store==null) emit(null)
        else store.changes.collect {
            gate.check(token)
            val entry=store.get(token.uid,id)
            gate.check(token); emit(entry)
        }
    }

    suspend fun openReading(id:String): SavedReading {
        DatabaseRecords.requireKey(id); val token=gate.capture()
        readingStore?.get(token.uid,id)?.let { gate.check(token); return it.saved }
        val saved=parseReading(read(token,privateRef(token,"savedReadings").child(id)))
        val context=read(token,privateRef(token,"readingContexts").child(id)).let { if(it.exists()) ReadingContextRecords.reference(it.catalogFields()).contextId else null }
        readingStore?.retain(token.uid,saved,context); gate.check(token); return saved
    }

    fun readingAnnotation(id:String): Flow<Pair<ReadingAnnotation?,ReadingSync>> = combine(
        observe("readingAnnotations/$id") { it }, readingStore?.changes ?: flowOf(0L)
    ) { snapshot, _ ->
        val token=gate.capture(); val local=readingStore?.get(token.uid,id)
        val remote=if(snapshot.exists()) ReadingAnnotation(snapshot.long("snapshotRevisionId"),snapshot.long("excerptLength").toInt(),
            snapshot.text("note"),snapshot.long("position").toInt(),snapshot.text("editId"),snapshot.long("modifiedAt")) else null
        local?.saved?.let { remote?.record(it) }
        val localWins=local?.annotation!=null && (local.annotationSync != ReadingSync.CONFIRMED || remote==null || remote.editId==local.annotation.editId)
        (if(localWins) local!!.annotation to local.annotationSync else remote to ReadingSync.LOCAL).also { gate.check(token) }
    }

    /** Persist a personal edit first. Awaiting the SDK task never turns connectivity into an acknowledgement. */
    suspend fun saveReadingAnnotation(id:String,note:String,position:Int):ReadingAnnotation {
        val token=gate.capture(); val store=requireNotNull(readingStore)
        val saved=openReading(id)
        val annotation=ReadingAnnotation(saved.reading.revisionId,saved.excerpt.length,note,position,newRecordId(),System.currentTimeMillis())
        annotation.record(saved)
        withContext(Dispatchers.IO) { gate.check(token); store.edit(token.uid,id,annotation) }
        syncReadingAnnotation(id)
        return annotation
    }

    suspend fun syncReadingAnnotation(id:String) {
        val token=gate.capture(); val store=requireNotNull(readingStore)
        val retained=requireNotNull(store.get(token.uid,id)); val annotation=requireNotNull(retained.annotation)
        require(retained.deletionEditId==null) { "This reading is awaiting removal." }
        require(retained.annotationSync!=ReadingSync.CONFIRMED) { "This personal edit is already confirmed. Save a new edit to change it." }
        val record=annotation.record(retained.saved)
        store.annotationPending(token.uid,id,annotation.editId)
        val ref=privateRef(token,"readingAnnotations").child(id); ref.keepSynced(true)
        acknowledgedReadingWrite(token,trackedSubmission(token,ref,record) { ref.setValue(record) }) { success -> store.annotationResult(token.uid,id,annotation.editId,success) }
    }

    fun readingStarters(): Flow<List<ReadingStarter>> = observeCatalog("readingStarters") { snapshot ->
        require(!snapshot.exists() || snapshot.hasChildren()) { "The reading suggestions are invalid." }
        require(snapshot.childrenCount <= 20) { "The reading suggestion list is too large." }
        snapshot.children.map { child ->
            ReadingStarterRecords.parse(child.key.orEmpty(), child.children.associate { it.key.orEmpty() to it.value })
        }.sortedWith(compareBy<ReadingStarter> { it.title }.thenBy { it.id })
    }

    /** Only true after a write acknowledgement during this process. Cached rows start unknown. */
    fun isReadingSaveConfirmed(id: String): Boolean = id in confirmedReadingIds

    /** Slot and immutable source are one atomic server-validated change. Journal survives a canceled waiter. */
    suspend fun saveReading(reading: ReadingContent, courseId: String? = null, context: ReadingContext? = null): SavedReading = saveMutex.withLock {
        val token=gate.capture(); val id="wiki_${reading.pageId}_${reading.revisionId}"
        val user=database.getReference("users").child(token.uid)
        val snapshot=read(token,user)
        val sources=snapshot.child("savedReadings")
        val remote=if(sources.hasChild(id)) parseReading(sources.child(id)) else null
        val previous=readingStore?.get(token.uid,id)
        require(previous?.deletionEditId==null) { "Resolve this reading's pending removal before saving it again." }
        val savedContext=if(snapshot.child("readingContexts").hasChild(id)) ReadingContextRecords.reference(snapshot.child("readingContexts").child(id).catalogFields()) else null
        require(remote==null || previous==null || previous.contextId==savedContext?.contextId) { "The server context differs from the retained reading; the local association was preserved for recovery." }
        val effectiveContext=previous?.contextId?.let { ReadingContext(it) } ?: if(remote!=null) savedContext else context
        val resolved=if(remote==null && effectiveContext!=null) resolveReadingContext(effectiveContext) else null
        val reviewedCourse=resolved?.definition?.takeIf { it.kind==ReadingContextKind.WEAK_CATEGORY }?.courseId
        require(reviewedCourse==null || courseId==null || courseId==reviewedCourse) { "The selected course differs from the reviewed reading context." }
        val saved=previous?.saved ?: remote ?: SavedReading(id,reading,reading.retrievedAt,reviewedCourse ?: courseId)
        require(remote==null || saved==remote) { "The server source differs from the retained copy; both must be recovered before retrying." }
        require(reviewedCourse==null || saved.courseId==reviewedCourse) { "The retained course context differs from the current reviewed definition." }
        val patch=mutableMapOf<String,Any?>("savedReadings/$id" to DatabaseRecords.reading(saved))
        if(remote==null) {
            val version=snapshot.child("readingShelfVersion").value?.let { require(it is Number && it.toDouble()==1.0); 1 }
            val index=snapshot.child("savedReadingSlots").children.associate { it.key.orEmpty() to it.textValue() }
            val slot=ReadingShelf.freeSlot(sources.children.map { it.key.orEmpty() }.toSet(),index,version)
            if(version==null) patch["readingShelfVersion"]=1
            patch["savedReadingSlots/$slot"]=id
            effectiveContext?.let { patch["readingContexts/$id"]=ReadingContextRecords.reference(it) }
        } else savedContext?.let { patch["readingContexts/$id"]=ReadingContextRecords.reference(it) }
        val journal=readingStore?.retain(token.uid,saved,effectiveContext?.contextId)
        privateRef(token,"savedReadings").child(id).keepSynced(true)
        privateRef(token,"readingAnnotations").child(id).keepSynced(true)
        privateRef(token,"readingContexts").child(id).keepSynced(true)
        readingStore?.snapshotPending(token.uid,id)
        acknowledgedReadingWrite(token,trackedSubmission(token,user,patch,merge=true, intentPath="saveReading/$id") { user.updateChildren(patch) }) { success ->
            val matches=if(readingStore!=null && journal!=null) readingStore.snapshotResult(token.uid,id,journal.snapshotEditId,success) else true
            if(success && matches) confirmedReadingIds+=id
        }
        saved
    }

    suspend fun deleteReading(id:String) = saveMutex.withLock {
        DatabaseRecords.requireKey(id); val token=gate.capture()
        val saved=openReading(id); val store=readingStore
        val retained=store?.get(token.uid,id)
        val deletionId=retained?.deletionEditId ?: newRecordId()
        val user=database.getReference("users").child(token.uid)
        val snapshot=read(token,user)
        val index=snapshot.child("savedReadingSlots").children.associate { it.key.orEmpty() to it.textValue() }
        store?.retain(token.uid,saved,retained?.contextId)
        reminderMutex.withLock {
        val linked = read(token, privateRef(token, "reminders")).children.map(::parseReminder).filter { ReminderTargets.target(it) == ReminderTarget.SavedReading(id) }
        val patch = ReadingShelf.deletion(id,index).toMutableMap()
        linked.forEach { patch["reminders/${it.id}"] = null }
        store?.beginDelete(token.uid,id,deletionId)
        acknowledgedReadingWrite(token,deleteWithReminders(token,patch,linked,"deleteReading/$id")) { success ->
            val matches=store?.deleteResult(token.uid,id,deletionId,success) ?: true
            if(success && matches) {
                confirmedReadingIds-=id
                listOf("savedReadings","readingAnnotations","readingContexts").forEach { privateRef(token,it).child(id).keepSynced(false) }
            }
        }
        }
    }

    private val readingWriteExecutor=java.util.concurrent.Executors.newSingleThreadExecutor()
    private suspend fun acknowledgedReadingWrite(token:SessionToken,task:Task<Void>,result:(Boolean)->Unit) {
        task.addOnCompleteListener(readingWriteExecutor) { completed ->
            runCatching { gate.check(token); result(completed.isSuccessful) }
        }
        try { awaitOwned(token,task); result(true) }
        catch(error:CancellationException) { throw error }
        catch(error:Exception) { gate.check(token); result(false); throw error }
    }
    private fun DataSnapshot.textValue():String = value as? String ?: error("Invalid reading slot.")

    fun modules(): Flow<List<StudyModule>> = observeCatalog("modules") { snapshot ->
        snapshot.children.map(::parseModule).sortedBy { it.title }
    }

    fun courses(): Flow<List<CatalogCourse>> = observeCatalog("courses") { snapshot ->
        snapshot.children.map(::parseCourse).sortedBy { it.id }
    }

    suspend fun pinModule(id: String): StudyModule {
        DatabaseRecords.requireKey(id)
        val token = gate.capture()
        val ref = database.getReference("catalog/modules").child(id)
        val module = parseModule(read(token, ref))
        require(module.sections.isNotEmpty()) { "This module has no complete text content." }
        ref.keepSynced(true)
        return module
    }

    fun scores(): Flow<List<ScoreRecord>> = observe("scores") { it.children.map(::parseScore) }
    suspend fun saveScore(score: ScoreRecord) = saveAcademicRecord("scores", score.id, DatabaseRecords.score(score), AcademicCapability.SCORES, score.planVersion, score.policyBinding)
    suspend fun deleteScore(id: String) = deleteRecord("scores", id)

    fun courseAttempts(): Flow<List<CourseAttempt>> = observe("courseAttempts") { it.children.map(::parseCourseAttempt) }
    suspend fun saveCourseAttempt(value: CourseAttempt) {
        var capability = if (value.includeInGwa) AcademicCapability.GWA else if (value.completionStatus != null) AcademicCapability.PREREQUISITES else AcademicCapability.SCORES
        if (!value.includeInGwa && value.policyBinding != null) {
            for (candidate in listOf(AcademicCapability.GWA, AcademicCapability.PREREQUISITES, AcademicCapability.SCORES)) {
                val access = academicAccess(value.courseId, candidate)
                if (access.allowed && access.matches(value.policyBinding)) { capability = candidate; break }
            }
        }
        saveAcademicRecord("courseAttempts", value.id, DatabaseRecords.courseAttempt(value), capability, incomingBinding = value.policyBinding)
    }

    fun operationStates(): Flow<List<OperationRecord>> {
        val journal = operationJournal ?: return flowOf(emptyList())
        val token = gate.capture()
        return journal.changes.map { withContext(Dispatchers.IO) { journal.records(token) } }
    }
    suspend fun deleteCourseAttempt(id: String) = deleteRecord("courseAttempts", id)

    fun scenarios(): Flow<List<GradeScenario>> = observe("scenarios") { it.children.map(::parseScenario) }
    suspend fun saveScenario(value: GradeScenario) = saveAcademicRecord("scenarios", value.id, DatabaseRecords.scenario(value), AcademicCapability.SCENARIOS, incomingBinding = value.policyBinding)
    suspend fun deleteScenario(id: String) = deleteRecord("scenarios", id)

    fun reminders(): Flow<List<StudyReminder>> = observe("reminders") { it.children.map(::parseReminder).sortedBy { row -> row.dueAt } }
    suspend fun saveReminder(value: StudyReminder) = changeReminder(value.id, value)
    suspend fun deleteReminder(id: String) = changeReminder(id, null)

    suspend fun reminderTarget(id: String): ReminderTarget {
        DatabaseRecords.requireKey(id)
        val token = gate.capture()
        val snapshot = read(token, privateRef(token, "reminders").child(id))
        require(snapshot.exists()) { "This reminder has been removed." }
        val value = parseReminder(snapshot)
        checkedReminderTarget(token, value.copy(enabled = true))
        return ReminderTargets.target(value)
    }

    private suspend fun changeReminder(id: String, value: StudyReminder?): Unit = reminderMutex.withLock {
        DatabaseRecords.requireKey(id)
        val record = value?.let { DatabaseRecords.reminder(it) + ("updatedAt" to ServerValue.TIMESTAMP) }
        val token = gate.capture()
        if (value != null) checkedReminderTarget(token, value)
        val ref = privateRef(token, "reminders").child(id)
        val task = ReminderDeliveryGate.updateSchedule {
            gate.check(token)
            // Queue the local Firebase mutation and its schedule before suspending.
            // This remains effective when the visible activity stops collecting.
            trackedSubmission(token, ref, record) { ref.setValue(record) }.also { pending ->
                reminderWrites[id] = pending
                pending.addOnCompleteListener { completed ->
                    ReminderDeliveryGate.updateSchedule {
                        if (reminderWrites.remove(id, completed) && !completed.isSuccessful &&
                            runCatching { gate.check(token) }.isSuccess) {
                            // A rejected write must not leave a phantom reminder. A coroutine
                            // timeout does not complete the Task or discard its queued write.
                            reminderChanged(token.uid, id, null)
                        }
                    }
                }
                reminderChanged(token.uid, id, value)
            }
        }
        awaitOwned(token, task)
        Unit
    }

    private suspend fun checkedReminderTarget(token: SessionToken, value: StudyReminder) {
        if (!value.enabled) {
            val retained = read(token, privateRef(token, "reminders").child(value.id))
            if (retained.exists() && ReminderTargets.identity(parseReminder(retained)) == ReminderTargets.identity(value)) return
        }
        when (val target = ReminderTargets.target(value)) {
            is ReminderTarget.LegacyModule -> require(read(token, database.getReference("catalog/modules").child(target.moduleId)).exists()) { "This reminder's module is unavailable." }
            is ReminderTarget.ModuleVersion -> {
                val key = TextPackageKey(target.curriculumVersion, target.moduleId, target.version)
                require(key !in removingLessonPackages && withContext(Dispatchers.IO) { packageStore?.read(token.uid, target.curriculumVersion, target.moduleId, target.version)?.ready } == true) { "Download this complete module version before setting its reminder." }
                require(read(token, privateRef(token, "offlinePackages").child(packagePath(key))).exists()) { "This package receipt is unavailable." }
            }
            is ReminderTarget.SavedReading -> {
                require(readingStore?.get(token.uid, target.readingId)?.deletionEditId == null &&
                    read(token, privateRef(token, "savedReadings").child(target.readingId)).exists()) { "This saved reading is unavailable or awaiting deletion." }
            }
        }
        gate.check(token)
    }

    /** Queue source deletion and linked rows atomically, then fence old local work before suspending. */
    private fun deleteWithReminders(token: SessionToken, patch: Map<String, Any?>, linked: List<StudyReminder>, intentPath: String): Task<Void> =
        ReminderDeliveryGate.updateSchedule {
            gate.check(token)
            val owner = database.getReference("users").child(token.uid)
            trackedSubmission(token, owner, patch, merge = true, intentPath = intentPath) { owner.updateChildren(patch) }.also { task ->
                linked.forEach { reminder -> reminderWrites[reminder.id] = task; reminderChanged(token.uid, reminder.id, null) }
                task.addOnCompleteListener { completed ->
                    ReminderDeliveryGate.updateSchedule {
                        linked.forEach { reminder ->
                            if (reminderWrites.remove(reminder.id, completed) && !completed.isSuccessful && runCatching { gate.check(token) }.isSuccess)
                                reminderChanged(token.uid, reminder.id, reminder)
                        }
                    }
                }
            }
        }

    fun reviewProgress(moduleId: String, version: Int): Flow<ReviewProgress?> {
        DatabaseRecords.requireKey(moduleId); require(version in 1..1_000_000)
        return observe("reviewProgress/$moduleId/v$version") { snapshot ->
            if (!snapshot.exists()) null else parseProgress(snapshot)
        }
    }

    suspend fun saveProgress(value: ReviewProgress) {
        val record = DatabaseRecords.progress(value)
        val token = gate.capture()
        write(token, privateRef(token, "reviewProgress").child(value.moduleId).child("v${value.version}"),
            record + ("updatedAt" to ServerValue.TIMESTAMP))
    }

    fun practiceAttempts(): Flow<List<PracticeAttempt>> = observe("practiceAttempts") { it.children.map(::parseAttempt) }

    /** Generate once when a practice session starts; retain this object while a submission retries. */
    fun newPracticeAttempt(moduleId: String, version: Int, correct: Int, total: Int): PracticeAttempt =
        PracticeAttempt(newRecordId(), moduleId, version, correct, total, System.currentTimeMillis())

    suspend fun submitPracticeAttempt(value: PracticeAttempt) =
        saveRecord("practiceAttempts", value.id, DatabaseRecords.attempt(value))

    fun connection(): Flow<Boolean> = observeReference({ database.getReference(".info/connected") }) {
        it.getValue(Boolean::class.java) == true
    }

    private fun bindOrReject(uid: String) {
        val bound = boundUid()
        if (bound != null && bound != uid) {
            database.goOffline()
            auth.signOut()
            throw InstallationBindingException()
        }
        if (bound == null) {
            // No private writes can precede the first UID binding. Initialize its journal in the
            // same commit so first sign-in never waits for an offline cache that does not exist.
            // Existing bound workspaces still require the legacy queue export before reconnecting.
            if (!preferences.edit().putString(BOUND_UID, uid)
                .putBoolean(READING_JOURNAL_READY, true).commit()) throw WorkspaceStorageException()
        }
    }

    private fun requireAuthEpoch(epoch: Long) {
        if (!authRequests.isCurrent(epoch)) {
            database.goOffline(); auth.signOut()
            throw AuthSessionChangedException()
        }
    }

    private suspend fun awaitAuth(task: Task<AuthResult>, epoch: Long): AuthResult {
        authRequests.attach(epoch)
        task.addOnCompleteListener {
            // Firebase auth Tasks continue after coroutine cancellation. Clean a late completion before
            // allowing another sign-in; otherwise an old completion could replace the current user.
            try {
                if (!authRequests.isCurrent(epoch)) {
                    database.goOffline()
                    auth.signOut()
                }
            } finally {
                authRequests.complete(epoch)
            }
        }
        return task.await()
    }

    private fun closeWorkspace() {
        gate.invalidate()
        registrations.toList().forEach { registration ->
            registration.ref.removeEventListener(registration.listener)
            registration.close()
            registrations -= registration
        }
        database.goOffline()
        confirmedReadingIds.clear()
        operationTasks.clear()
    }

    private fun privateRef(token: SessionToken, collection: String): DatabaseReference {
        gate.check(token)
        return database.getReference("users").child(token.uid).child(collection)
    }

    private suspend fun saveRecord(collection: String, id: String, values: Map<String, Any>) {
        DatabaseRecords.requireKey(id)
        val token = gate.capture()
        val record = if (collection == "practiceAttempts") values else values + ("updatedAt" to ServerValue.TIMESTAMP)
        write(token, privateRef(token, collection).child(id), record)
    }

    private suspend fun saveAcademicRecord(collection: String, id: String, values: Map<String, Any>,
                                           capability: AcademicCapability, planVersion: String? = null, incomingBinding: AcademicRecordBinding? = null) {
        DatabaseRecords.requireKey(id)
        val token = gate.capture()
        val record = checkedAcademicRecord(token, collection, id, values, capability, planVersion, incomingBinding)
        write(token, privateRef(token, collection).child(id), record)
    }

    private suspend fun checkedAcademicRecord(token: SessionToken, collection: String, id: String, values: Map<String, Any>,
                                              capability: AcademicCapability, planVersion: String? = null,
                                              incomingBinding: AcademicRecordBinding? = null): Map<String, Any> {
        val courseId = values.getValue("courseId") as String
        val access = academicAccess(token, courseId, capability, planVersion)
        require(access.allows(courseId, capability, planVersion)) { access.reason }
        require(incomingBinding == null || access.matches(incomingBinding)) { "The supplied academic policy binding no longer matches the reviewed selection. Refresh before saving." }
        val courseSnapshot = readAcademic(token, database.getReference("catalog/courses").child(courseId))
        val course = parseCourse(courseSnapshot)
        require(collection == "courseAttempts" || (course.gradePlan?.verified == true && course.gradePlan.version == access.planVersion)) {
            "The course assessment plan no longer matches its reviewed policy."
        }
        val retained = readAcademic(token, privateRef(token, collection).child(id))
        if (retained.exists()) {
            val previous = parseAcademicBinding(retained)
            require(previous != null && previous.curriculumVersion == access.curriculumVersion &&
                previous.policyRevision == access.policyRevision && previous.planVersion == access.planVersion) {
                "Keep the retained record and review it as a new entry for this policy. Existing records cannot be silently rebound."
            }
        }
        if (collection == "scores") {
            val assessment = requireNotNull(course.gradePlan).categories.firstOrNull { it.id == values["categoryId"] }
                ?.assessments?.firstOrNull { it.id == values["assessmentId"] }
            require(assessment != null && assessment.possiblePoints == values["possiblePoints"]) {
                "This score does not match an assessment in the reviewed plan."
            }
        }
        if (collection == "courseAttempts" && values.containsKey("completionStatus")) {
            val prerequisites = academicAccess(token, courseId, AcademicCapability.PREREQUISITES)
            require(prerequisites.allows(courseId, AcademicCapability.PREREQUISITES)) { prerequisites.reason }
        }
        if (collection == "scenarios") {
            com.studypath.app.domain.AcademicScenarios.validateOverrides(course,
                (values["overrides"] as? Map<*, *>)?.entries?.associate { (key, value) -> key as String to (value as Number).toDouble() } ?: emptyMap())
            require((values["overrides"] as? Map<*, *>)?.keys?.all { courseSnapshot.child("gradePlan/overrideKeys").child(it as String).value == true } != false) {
                "The reviewed plan's scenario assessment index is incomplete. Request a corrected publication before saving these overrides."
            }
            val scoreAccess = academicAccess(token, courseId, AcademicCapability.SCORES, access.planVersion)
            require(scoreAccess.allowed) { scoreAccess.reason }
            val actual = readAcademic(token, privateRef(token, "scores")).children.map(::parseScore)
            val fingerprint = com.studypath.app.domain.AcademicPlanFingerprint.compute(course, actual, scoreAccess)
            require(values["baseVersion"] == fingerprint) { "Actual scores, the plan or policy changed. Review and explicitly rebase this scenario before saving." }
        }
        if (collection == "courseAttempts") {
            require(values["units"] == course.units) { "Attempt units must match the reviewed course." }
            if (values["includeInGwa"] == true) {
                val scale = requireNotNull(access.gwaPolicy) { "A reviewed GWA scale is required." }
                val grade = (values["grade"] as? Number)?.toDouble()
                require(values["scaleId"] == scale.scaleId && grade != null && grade in scale.minimumGrade..scale.maximumGrade) {
                    "The grade and scale must match the reviewed GWA policy."
                }
            }
        }
        return values + mapOf("curriculumVersion" to requireNotNull(access.curriculumVersion),
            "policyRevision" to requireNotNull(access.policyRevision), "policyRef" to requireNotNull(access.policyRef),
            "planVersion" to requireNotNull(access.planVersion), "updatedAt" to ServerValue.TIMESTAMP)
    }

    private suspend fun deleteRecord(collection: String, id: String) {
        DatabaseRecords.requireKey(id)
        val token = gate.capture()
        write(token, privateRef(token, collection).child(id), null)
    }

    private suspend fun write(token: SessionToken, ref: DatabaseReference, value: Any?) {
        gate.check(token)
        awaitOwned(token, trackedSubmission(token, ref, value) { ref.setValue(value) })
    }

    private suspend fun createInitialProfile(token: SessionToken, value: Any?) {
        val ref = privateRef(token, "profile")
        val transaction = InitialProfileTransaction(value) { gate.check(token) }
        // The journal retains this setup intent across restart; SDK transactions alone do not.
        awaitOwned(token, trackedSubmission(token, ref, value) {
            ref.runTransaction(transaction, false)
            transaction.task
        })
    }

    /** Persist the exact private intent before the SDK queues it. Only its actual Task may acknowledge it. */
    private fun trackedSubmission(token: SessionToken, ref: DatabaseReference, value: Any?, merge: Boolean = false, intentPath: String? = null,
                                  submit: () -> Task<Void>): Task<Void> = synchronized(operationLock) {
        gate.check(token)
        val journal = operationJournal ?: return@synchronized if (intentPath?.startsWith("removePackage/") == true) {
            val key = removalKey(intentPath)
            val store = requireNotNull(packageStore)
            val id = store.removalOperationId(token.uid, key) ?: newRecordId()
            store.beginRemoval(token.uid, key, id)
            submit().also { task -> task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { completed ->
                if (runCatching { gate.check(token) }.isSuccess) {
                    if (completed.isSuccessful) store.finishRemoval(token.uid, key, id) else store.cancelRemoval(token.uid, key)
                }
            } }
        } else submit()
        if (journal.records(token).size >= 4900) journal.compactConfirmed(token) { confirmed ->
            finishPackageRemoval(token, confirmed)
            requireNotNull(operationPayloads).removeConfirmed(token, confirmed)
        }
        val ownerPath = "/users/${token.uid}"
        val referencePath = java.net.URI(ref.toString()).path
        require(referencePath == ownerPath || referencePath.startsWith("$ownerPath/")) { "Only this account's study writes may be journaled." }
        val suffix = referencePath.removePrefix(ownerPath).trim('/').ifEmpty { "_workspace" }
        val path = intentPath ?: (if (merge) "merge/" else "set/") + suffix
        val json = operationJson.toJson(value)
        val hash = OperationJournal.payloadFingerprint(json)
        val latest = journal.records(token).lastOrNull { it.relativePath == path }
        val record = if (latest != null && latest.payloadSha256 == hash && latest.state != OperationSyncState.CONFIRMED) {
            operationTasks[latest.operationId]?.let { return@synchronized it }
            if (latest.state == OperationSyncState.QUEUED) latest else journal.retry(token, latest.operationId, path, json)
        } else journal.enqueue(token, newRecordId(), path, json, System.currentTimeMillis())
        requireNotNull(operationPayloads).retain(token, record.operationId, path, json)
        if (path.startsWith("removePackage/")) requireNotNull(packageStore).beginRemoval(token.uid,
            removalKey(path), record.operationId)
        val ticket = journal.awaitingAck(token, record.operationId)
        val task = try { submit() } catch (error: Exception) {
            journal.result(ticket, OperationSyncState.REJECTED); throw error
        }
        operationTasks[record.operationId] = task
        task.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { completed ->
            try {
                val state = if (completed.isSuccessful) OperationSyncState.CONFIRMED
                    else if (completed.exception is com.google.firebase.auth.FirebaseAuthException) OperationSyncState.AUTH_REQUIRED else OperationSyncState.REJECTED
                if (journal.result(ticket, state, if (completed.isSuccessful) System.currentTimeMillis() else null)) {
                    if (completed.isSuccessful) {
                        finishPackageRemoval(token, record)
                        operationPayloads.removeConfirmed(token, requireNotNull(journal.get(token, record.operationId)))
                    } else if (record.relativePath.startsWith("removePackage/")) {
                        requireNotNull(packageStore).cancelRemoval(token.uid, removalKey(record.relativePath))
                    }
                }
            } catch (_: Exception) { /* Retain unverified metadata/payload; never invent acknowledgement after a storage/session failure. */ }
            finally { operationTasks.remove(record.operationId, completed) }
        }
        task
    }

    suspend fun retryOperation(operationId: String) {
        val token = gate.capture()
        val journal = requireNotNull(operationJournal)
        val operation = requireNotNull(journal.get(token, operationId))
        val lessonRetry = operation.relativePath.startsWith("set/lessonAttempts/")
        require(operation.state != OperationSyncState.CONFIRMED || lessonRetry || operation.relativePath.startsWith("removePackage/")) { "This change is already acknowledged." }
        require(journal.records(token).lastOrNull { OperationJournal.overlaps(it, operation) }?.operationId == operationId) {
            "A newer overlapping edit exists. Review it before retrying an older change."
        }
        if (lessonRetry) {
            val runId = operation.relativePath.substringAfterLast('/')
            val json = operationPayloads?.read(token, operationId, operation.relativePath)
            val fields = json?.let { operationJson.fromJson(it, Map::class.java) }
            val retained = withContext(Dispatchers.IO) {
                val packages = requireNotNull(packageStore).list(token.uid).filter { it.ready }
                val candidate = packages.firstNotNullOfOrNull { pack ->
                    if (fields != null && (fields["curriculumVersion"] != pack.key.curriculumVersion || fields["moduleId"] != pack.key.moduleId ||
                        (fields["version"] as? Number)?.toInt() != pack.key.version)) null
                    else runCatching { InteractiveLessonParser.parse(pack.verified) }.getOrNull()?.let { module ->
                        requireNotNull(lessonRunStore).get(token.uid, module, runId)?.let { module to it }
                    }
                }
                requireNotNull(candidate) { "The original lesson package or result is unavailable. Its recovery data was retained." }
            }
            val run = lessonMutex.withLock { withContext(Dispatchers.IO) {
                checkedInteractiveModule(token, retained.first)
                requireNotNull(lessonRunStore).recoverAttempt(token.uid, retained.first, operation)
            } }
            if (journal.get(token, operationId)?.state == OperationSyncState.CONFIRMED)
                confirmLessonRun(token, retained.first, run)
            else submitLessonRun(retained.first, run)
            return
        }
        if (operation.relativePath.startsWith("removePackage/") && operation.state == OperationSyncState.CONFIRMED) {
            finishPackageRemoval(token, operation); return
        }
        operationTasks[operationId]?.let {
            awaitOwned(token, it)
            finishPackageRemoval(token, operation)
            return
        }
        val json = requireNotNull(operationPayloads).read(token, operationId, operation.relativePath) ?: error("The original private payload is unavailable; its recovery metadata was retained.")
        val mode = operation.relativePath.substringBefore('/')
        val path = operation.relativePath.substringAfter('/')
        if (mode == "deleteReading") { deleteReading(path); return }
        if (mode == "removePackage") {
            val parts = path.split('/'); require(parts.size == 3 && parts[2].startsWith("v"))
            val patch = operationJson.fromJson(json, Map::class.java).entries.associate { it.key as String to it.value }
            val key = TextPackageKey(parts[0], parts[1], parts[2].drop(1).toInt())
            packageMutex.withLock {
                awaitOwned(token, trackedSubmission(token, database.getReference("users").child(token.uid), patch,
                    merge = true, intentPath = operation.relativePath) {
                    database.getReference("users").child(token.uid).updateChildren(patch)
                })
                requireNotNull(packageStore).finishRemoval(token.uid, key, operation.operationId)
            }
            return
        }
        if (mode == "saveReading") {
            val retained = requireNotNull(readingStore?.get(token.uid, path)) { "The retained reading is unavailable; recovery metadata was kept." }
            saveReading(retained.saved.reading, retained.saved.courseId, retained.contextId?.let(::ReadingContext)); return
        }
        require(mode in setOf("set", "merge"))
        val ref = if (path == "_workspace") database.getReference("users").child(token.uid) else privateRef(token, path)
        val value = operationJson.fromJson(json, Any::class.java)
        if (isInitialProfileCreation(operation.relativePath)) {
            createInitialProfile(token, value)
            return
        }
        if (mode == "set" && path.startsWith("readingAnnotations/") && value != null) {
            syncReadingAnnotation(path.substringAfter('/')); return
        }
        if (mode == "set" && path.startsWith("offlinePackages/") && value != null) {
            val parts = path.removePrefix("offlinePackages/").split('/'); require(parts.size == 3)
            val retained = withContext(Dispatchers.IO) { requireNotNull(packageStore).read(token.uid, parts[0], parts[1], parts[2].removePrefix("v").toInt()) }
            downloadTextPackage(requireNotNull(retained).verified.summary); return
        }
        if (mode == "set" && value is Map<*, *> && path.substringBefore('/') in setOf("scores", "courseAttempts", "scenarios")) {
            @Suppress("UNCHECKED_CAST") val values = value as Map<String, Any>
            val binding = AcademicRecordBinding(values["curriculumVersion"] as String, (values["policyRevision"] as Number).toInt(), values["policyRef"] as String, values["planVersion"] as String)
            val collection = path.substringBefore('/'); val courseId = values["courseId"] as String
            val candidates = when (collection) { "scores" -> listOf(AcademicCapability.SCORES); "scenarios" -> listOf(AcademicCapability.SCENARIOS); else -> listOf(AcademicCapability.GWA, AcademicCapability.PREREQUISITES, AcademicCapability.SCORES) }
            val capability = candidates.firstOrNull { academicAccess(token, courseId, it).matches(binding) } ?: error("The original policy is unavailable. Review this retained edit in Planner.")
            checkedAcademicRecord(token, collection, path.substringAfter('/'), values, capability, binding.planVersion, binding)
        }
        if (mode == "set" && path.startsWith("reminders/") && value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST") val fields = value as Map<String, Any?>
            changeReminder(path.substringAfter('/'), StudyReminder(path.substringAfter('/'), fields["moduleId"] as? String,
                fields["title"] as String, (fields["dueAt"] as Number).toLong(), fields["enabled"] as Boolean,
                fields["zoneId"] as? String ?: "UTC", target = ReminderTargets.parse(fields)))
            return
        }
        awaitOwned(token, trackedSubmission(token, ref, value, merge = mode == "merge") {
            if (mode == "merge") {
                @Suppress("UNCHECKED_CAST") ref.updateChildren(value as Map<String, Any?>)
            } else ref.setValue(value)
        })
    }

    private suspend fun read(token: SessionToken, ref: DatabaseReference): DataSnapshot {
        gate.check(token)
        return awaitOwned(token, ref.get())
    }

    private suspend fun <T> awaitOwned(token: SessionToken, task: Task<T>): T {
        val result = task.await()
        gate.check(token)
        return result
    }

    private fun <T> observe(path: String, parse: (DataSnapshot) -> T): Flow<T> =
        observeReference({ token -> privateRef(token, path) }, parse)

    private fun <T> observeCatalog(path: String, parse: (DataSnapshot) -> T): Flow<T> =
        observeReference({ database.getReference("catalog").child(path) }, parse)

    private fun <T> observeReference(reference: (SessionToken) -> DatabaseReference, parse: (DataSnapshot) -> T): Flow<T> = callbackFlow {
        val token = gate.capture()
        val ref = reference(token)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                try {
                    gate.check(token)
                    val value = parse(snapshot)
                    gate.check(token)
                    trySend(value)
                } catch (error: Exception) { close(error) }
            }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        val registration = Registration(ref, listener) { close(IllegalStateException("The account session ended.")) }
        registrations += registration
        try {
            gate.check(token)
            ref.addValueEventListener(listener)
            awaitClose { ref.removeEventListener(listener); registrations -= registration }
        } catch (error: Exception) {
            ref.removeEventListener(listener)
            registrations -= registration
            throw error
        }
    }

    private fun parseReading(s: DataSnapshot): SavedReading {
        val reading = ReadingContent(s.long("pageId"), s.text("pageKey"), s.text("title"), s.text("excerpt"),
            s.long("revisionId"), s.text("revisionTimestamp"), s.text("sourceUrl"), s.text("revisionUrl"),
            s.text("licenseTitle"), s.text("licenseUrl"), s.text("attribution"), s.text("modificationNotice"), s.long("retrievedAt"))
        return SavedReading(s.key.orEmpty(), reading, s.long("savedAt"), s.optionalText("courseId")).also(DatabaseRecords::reading)
    }

    private fun DataSnapshot.preferenceFields(): Map<String, Any?> = children.associate { child ->
        child.key.orEmpty() to if (child.key == "subjectIds" && child.hasChildren())
            child.children.associate { it.key.orEmpty() to it.value } else child.value
    }

    // Traverse child keys instead of Firebase's value coercion (dense numeric keys can become lists).
    private fun DataSnapshot.catalogFields(): Map<String, Any?> = children.associate { child ->
        child.key.orEmpty() to if (child.hasChildren()) child.catalogFields() else child.value
    }

    private fun parsePreferenceOptions(snapshot: DataSnapshot, curriculum: String): LearnerPreferenceOptions? =
        LearnerPreferenceRecords.options(if (snapshot.exists()) snapshot.preferenceFields() else null).also {
            require(it == null || it.curriculumVersion == curriculum) { "The learner option index does not match its version." }
        }

    private fun parseModule(s: DataSnapshot): StudyModule {
        val sections = s.child("sections").children.map { ModuleSection(it.key.orEmpty(), it.text("title"), it.text("body")) }
        val practice = s.child("practice").children.map { question ->
            PracticeQuestion(question.key.orEmpty(), question.text("prompt"), question.child("options").children.associate { it.key.orEmpty() to (it.value as? String ?: error("Invalid practice option.")) }, question.text("answerId"))
        }
        require(sections.sumOf { it.body.length + it.title.length } <= 100_000) { "Module exceeds the offline text budget." }
        return StudyModule(s.key.orEmpty(), s.text("courseId"), s.long("version").toInt(), s.text("title"),
            s.child("approved").getValue(Boolean::class.java) == true, s.text("approvalStatus"),
            s.text("author"), s.text("source"), sections, practice)
    }

    private fun parseCourse(s: DataSnapshot): CatalogCourse {
        val plan = s.child("gradePlan")
        val parsedPlan = if (!plan.exists()) null else CatalogGradePlan(plan.text("version"),
            plan.child("assessmentPlanKnown").getValue(Boolean::class.java) == true,
            plan.child("verified").getValue(Boolean::class.java) == true, plan.text("label"),
            plan.child("categories").children.map { category ->
                CatalogCategory(category.key.orEmpty(), category.number("weight"), category.text("aggregation"),
                    category.child("assessments").children.map { item ->
                        CatalogAssessment(item.key.orEmpty(), item.text("title"), item.number("possiblePoints"), item.optionalNumber("explicitWeight"))
                    })
            })
        val nested = s.child("prerequisiteExpression")
        val legacyReferences = s.child("prerequisiteCourseIds")
        val parsedExpression = when {
            nested.exists() -> PrerequisiteCodec.parse(nested.value)
            legacyReferences.exists() && (!legacyReferences.hasChildren() || legacyReferences.children.any { it.value != true }) -> {
                val reason = "Legacy prerequisite references must be a map of course IDs to true"
                PrerequisiteDocument(Prerequisite.Unknown(reason), reason)
            }
            else -> null
        }
        return CatalogCourse(s.key.orEmpty(), s.text("title"), s.number("units"), s.text("topicQuery"),
            s.text("moduleId"), s.child("prerequisitesKnown").getValue(Boolean::class.java) == true,
            s.optionalText("ruleKind") ?: "UNKNOWN",
            legacyReferences.children.filter { it.value == true }.map { it.key.orEmpty() }, parsedPlan, parsedExpression)
    }

    private fun parseScore(s: DataSnapshot) = ScoreRecord(s.key.orEmpty(), s.text("courseId"), s.text("assessmentId"),
        s.text("categoryId"), s.text("status"), s.number("possiblePoints"), s.optionalNumber("earnedPoints"), s.optionalLong("updatedAt"), s.text("planVersion"), parseAcademicBinding(s)).also(DatabaseRecords::score)

    private fun parseCourseAttempt(s: DataSnapshot) = CourseAttempt(s.key.orEmpty(), s.text("courseId"), s.text("term"),
        s.text("status"), s.number("units"), s.optionalText("completionStatus"), s.optionalNumber("grade"),
        s.optionalNumber("finalPercent"), s.optionalText("scaleId"), s.optionalLong("updatedAt"),
        s.child("includeInGwa").getValue(Boolean::class.java) ?: false, parseAcademicBinding(s)).also(DatabaseRecords::courseAttempt)

    private fun parseAcademicBinding(s: DataSnapshot): AcademicRecordBinding? {
        val fields = listOf("curriculumVersion", "policyRevision", "policyRef")
        if (fields.none { s.hasChild(it) }) return null
        require(fields.all { s.hasChild(it) } && s.hasChild("planVersion")) { "The academic record has an incomplete policy binding." }
        val curriculum = s.text("curriculumVersion").also(DatabaseRecords::requireKey)
        val plan = s.text("planVersion").also(DatabaseRecords::requireKey)
        val revision = s.long("policyRevision").also { require(it in 1..1_000_000) }.toInt()
        val reference = s.text("policyRef").also { require(it.isNotBlank() && it.length <= 240) }
        return AcademicRecordBinding(curriculum, revision, reference, plan)
    }

    private fun parseScenario(s: DataSnapshot) = GradeScenario(s.key.orEmpty(), s.text("courseId"), s.text("title"),
        s.number("targetPercent"), s.text("baseVersion"), s.optionalNumber("requiredPercent"),
        s.child("overrides").children.associate { it.key.orEmpty() to ((it.value as? Number)?.toDouble() ?: error("Invalid scenario override.")) }, s.optionalLong("updatedAt"), parseAcademicBinding(s)).also(DatabaseRecords::scenario)

    private fun parseReminder(s: DataSnapshot) = StudyReminder(s.key.orEmpty(), s.optionalText("moduleId"), s.text("title"),
        s.long("dueAt"), s.child("enabled").getValue(Boolean::class.java) ?: error("Invalid reminder."),
        s.optionalText("zoneId") ?: "UTC", s.optionalLong("updatedAt"), ReminderTargets.parse(s.catalogFields())).also(DatabaseRecords::reminder)

    private fun parseProgress(s: DataSnapshot) = ReviewProgress(s.text("moduleId"), s.long("version").toInt(),
        s.child("completed").getValue(Boolean::class.java) ?: error("Invalid progress."), s.long("lastSection").toInt(),
        s.optionalLong("updatedAt")).also(DatabaseRecords::progress)

    private fun parseAttempt(s: DataSnapshot) = PracticeAttempt(s.key.orEmpty(), s.text("moduleId"), s.long("version").toInt(),
        s.long("correct").toInt(), s.long("total").toInt(), s.long("submittedAt")).also(DatabaseRecords::attempt)

    private fun DataSnapshot.text(name: String): String = optionalText(name) ?: error("Missing $name in a stored record.")
    private fun DataSnapshot.optionalText(name: String): String? = child(name).value as? String
    private fun DataSnapshot.number(name: String): Double = optionalNumber(name) ?: error("Missing $name in a stored record.")
    private fun DataSnapshot.optionalNumber(name: String): Double? = (child(name).value as? Number)?.toDouble()
    private fun DataSnapshot.long(name: String): Long = optionalLong(name) ?: error("Missing $name in a stored record.")
    private fun DataSnapshot.optionalLong(name: String): Long? = (child(name).value as? Number)?.toLong()

    private class Registration(val ref: DatabaseReference, val listener: ValueEventListener, val close: () -> Unit)

    companion object {
        private const val BOUND_UID = "boundUid"
        private const val READING_JOURNAL_READY = "readingJournalV1Ready"
        private const val MAX_READINGS = 20
        fun newRecordId(): String = UUID.randomUUID().toString()
    }
}

internal fun isInitialProfileCreation(relativePath: String): Boolean = relativePath == "set/profile"

/** Server transaction callbacks keep initial account setup tied to its owning session. */
internal class InitialProfileTransaction(private val initialProfile: Any?, private val requireOwner: () -> Unit) : Transaction.Handler {
    private val completion = TaskCompletionSource<Void>()
    private var failure: Exception? = null
    val task: Task<Void> get() = completion.task

    override fun doTransaction(currentData: MutableData): Transaction.Result = try {
        requireOwner()
        if (currentData.value == null) {
            requireProfile(initialProfile, allowServerTimestamp = true)
            currentData.value = initialProfile
        } else requireProfile(currentData.value)
        // An unchanged success still performs server CAS; abort could accept stale local cache.
        Transaction.success(currentData)
    } catch (error: Exception) {
        failure = error
        Transaction.abort()
    }

    override fun onComplete(error: DatabaseError?, committed: Boolean, currentData: DataSnapshot?) {
        try {
            failure?.let { throw it }
            error?.let { throw it.toException() }
            requireOwner()
            check(committed && currentData?.exists() == true) { "Initial account profile was not confirmed." }
            requireProfile(requireNotNull(currentData).value)
            completion.trySetResult(null)
        } catch (error: Exception) {
            completion.trySetException(error)
        }
    }

    private fun requireProfile(value: Any?, allowServerTimestamp: Boolean = false) {
        require(value is Map<*, *>) { "The existing account profile needs review; its data was preserved." }
        val name = value["displayName"]
        require(name is String && name.isNotBlank() && name.length <= 80) { "The account profile name needs review." }
        val curriculum = value["selectedCurriculumVersion"]
        require(curriculum is String) { "The account profile context needs review." }
        DatabaseRecords.requireKey(curriculum)
        LearnerPreferenceRecords.fromProfile(mapOf("learnerPreferences" to value["learnerPreferences"]))
        for (key in listOf("createdAt", "updatedAt")) {
            if (!value.containsKey(key)) continue
            val timestamp = value[key]
            if (allowServerTimestamp && timestamp == ServerValue.TIMESTAMP) continue
            require(timestamp is Number && timestamp.toDouble().let {
                it.isFinite() && it in 0.0..4_102_444_800_000.0 && it % 1.0 == 0.0
            }) { "The account profile timestamp needs review." }
        }
    }
}
