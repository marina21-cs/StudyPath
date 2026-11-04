package com.studypath.app.data

/** Fixed stage identifiers only: account values and SDK messages never become support references. */
enum class AuthFailureStage {
    ACCOUNT_SERVICE, WORKSPACE_BINDING, READING_RECOVERY, AUTH_PENDING, LOCAL_STORAGE, SESSION_CHANGED,
}

open class AuthFailureException(val stage: AuthFailureStage, cause: Throwable? = null) :
    IllegalStateException(stage.name, cause)

class LegacyReadingRecoveryException(cause: Throwable? = null) :
    AuthFailureException(AuthFailureStage.READING_RECOVERY, cause)

class InstallationBindingException : AuthFailureException(AuthFailureStage.WORKSPACE_BINDING)

class AuthRequestPendingException : AuthFailureException(AuthFailureStage.AUTH_PENDING)

class WorkspaceStorageException : AuthFailureException(AuthFailureStage.LOCAL_STORAGE)

class AuthSessionChangedException : AuthFailureException(AuthFailureStage.SESSION_CHANGED)
