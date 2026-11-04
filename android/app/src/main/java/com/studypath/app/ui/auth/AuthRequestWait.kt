package com.studypath.app.ui.auth

import kotlinx.coroutines.withTimeoutOrNull

internal class AuthRequestTimeoutException : Exception("This is taking longer than expected.")

private data class AuthReply<T>(val value: T)

/** Cancel only this wait. Repository cleanup and Firebase's late-completion fence still run. */
internal suspend fun <T> awaitAuthRequest(timeoutMillis: Long = 30_000, request: suspend () -> T): T =
    (withTimeoutOrNull(timeoutMillis) { AuthReply(request()) } ?: throw AuthRequestTimeoutException()).value

internal enum class AuthRequestKind { REGISTRATION, SIGN_IN, PASSWORD_RESET }

internal fun authConnectionMessage(useEmulators: Boolean): String = if (useEmulators)
    "This test build needs a connection to your test computer."
else "Check your internet connection and try again."

internal fun authTimeoutMessage(kind: AuthRequestKind, useEmulators: Boolean, accountLinked: Boolean = false): String {
    val recovery = when (kind) {
        AuthRequestKind.REGISTRATION -> if (accountLinked)
            "Your changes are kept. Reconnect and sign in with the same account."
        else "Your account may already exist. Try signing in with the same email."
        AuthRequestKind.SIGN_IN -> "Couldn’t finish signing in. Reconnect and try again."
        AuthRequestKind.PASSWORD_RESET -> "A reset email may still arrive. Check your inbox and spam before trying again."
    }
    return if (useEmulators) "$recovery ${authConnectionMessage(true)}" else recovery
}
