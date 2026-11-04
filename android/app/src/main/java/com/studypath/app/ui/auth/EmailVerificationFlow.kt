package com.studypath.app.ui.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.tasks.await

internal data class VerificationAccount(val uid: String, val email: String, val verified: Boolean)

internal interface EmailVerificationAccount {
    fun current(): VerificationAccount
    suspend fun send()
    suspend fun reload()
    suspend fun refreshToken()
}

internal class VerificationSessionChangedException : IllegalStateException(
    "Return to the original signed-in account to verify its email. Your saved work is kept.",
)

internal enum class VerificationSendResult { REQUESTED, WAIT, ALREADY_VERIFIED }

/** The cooldown prevents accidental repeated taps; Firebase remains responsible for abuse limits. */
internal class EmailVerificationFlow(
    private val ownerUid: String,
    private val account: EmailVerificationAccount,
    private val now: () -> Long,
    savedSendDeadline: Long = 0,
    private val saveSendDeadline: (Long) -> Unit = {},
) {
    private var sendDeadline = savedSendDeadline.coerceAtMost(now() + SEND_COOLDOWN_MILLIS)
    private var requestActive = false

    init {
        if (sendDeadline != savedSendDeadline) saveSendDeadline(sendDeadline)
    }

    val hasRequested: Boolean get() = sendDeadline > 0
    val resendSeconds: Long get() = ((sendDeadline - now()).coerceIn(0, SEND_COOLDOWN_MILLIS) + 999) / 1000

    fun current(): VerificationAccount = account.current().also {
        if (ownerUid.isBlank() || it.uid != ownerUid) throw VerificationSessionChangedException()
    }

    suspend fun send(): VerificationSendResult = request {
        if (current().verified) return@request VerificationSendResult.ALREADY_VERIFIED
        if (resendSeconds > 0) return@request VerificationSendResult.WAIT
        // Save before the SDK call: a cancelled wait does not cancel a Firebase email request.
        sendDeadline = now() + SEND_COOLDOWN_MILLIS
        saveSendDeadline(sendDeadline)
        awaitAuthRequest { account.send() }
        current()
        VerificationSendResult.REQUESTED
    }

    suspend fun refresh(): Boolean = request {
        current()
        awaitAuthRequest {
            account.reload()
            if (!current().verified) return@awaitAuthRequest false
            account.refreshToken()
            current().verified
        }
    }

    private suspend fun <T> request(action: suspend () -> T): T {
        check(!requestActive) { "The previous verification request is still finishing." }
        requestActive = true
        return try { action() } finally { requestActive = false }
    }

    companion object { const val SEND_COOLDOWN_MILLIS = 60_000L }
}

/** Every SDK operation targets the installation's original user, including after process restore. */
internal class FirebaseEmailVerificationAccount(
    private val auth: FirebaseAuth,
    private val ownerUid: String,
    private val boundUid: () -> String?,
) : EmailVerificationAccount {
    private fun user(): FirebaseUser {
        val user = auth.currentUser ?: throw VerificationSessionChangedException()
        if (ownerUid.isBlank() || boundUid() != ownerUid || user.uid != ownerUid) {
            throw VerificationSessionChangedException()
        }
        return user
    }

    override fun current(): VerificationAccount = user().let {
        VerificationAccount(it.uid, it.email.orEmpty(), it.isEmailVerified)
    }

    override suspend fun send() { user().sendEmailVerification().await(); user() }
    override suspend fun reload() { user().reload().await(); user() }
    override suspend fun refreshToken() { user().getIdToken(true).await(); user() }
}
