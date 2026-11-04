package com.studypath.app.ui.auth

import android.view.View
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.studypath.app.BuildConfig
import com.studypath.app.data.PasswordPolicy
import com.studypath.app.data.AuthFailureException
import com.studypath.app.data.AuthFailureStage
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException

internal object AuthUi {
    private const val CREDENTIAL_MESSAGE = "Check your email and password, then try again. If you haven’t signed up, tap Create an account."

    fun applyInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    fun hideKeyboard(activity: AppCompatActivity, root: View) {
        activity.currentFocus?.clearFocus()
        WindowInsetsControllerCompat(activity.window, root).hide(WindowInsetsCompat.Type.ime())
    }

    fun status(view: TextView, message: String, error: Boolean = false) {
        view.text = message
        view.setTextIsSelectable(error)
        view.isVisible = message.isNotBlank()
        val role = if (error) com.google.android.material.R.attr.colorError
            else com.google.android.material.R.attr.colorOnSurfaceVariant
        view.setTextColor(MaterialColors.getColor(view, role))
    }

    fun buildDetails(): String = "StudyPath ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · " +
        (if (BuildConfig.USE_FIREBASE_EMULATORS) "Local test backend" else "Cloud backend") +
        "\nProject: ${BuildConfig.FIREBASE_PROJECT_ID}"

    fun errorMessage(error: Exception, useEmulators: Boolean = BuildConfig.USE_FIREBASE_EMULATORS): String {
        if (BuildConfig.DEBUG) Log.w("StudyPathAuth", authFailureDiagnostic(error))
        val chain = authFailureChain(error)
        fun reference(message: String) = "$message\nReference: ${authFailureReference(error, useEmulators)}"
        when (chain.filterIsInstance<AuthFailureException>().firstOrNull()?.stage) {
            AuthFailureStage.WORKSPACE_BINDING -> return "This installation is linked to its original account. Sign in with that account."
            AuthFailureStage.READING_RECOVERY -> return reference(
                "Your saved work is kept on this phone. Protected offline recovery needs attention before sign-in can finish. Keep the app data and share this reference for help.")
            AuthFailureStage.AUTH_PENDING -> return reference(
                "Your previous sign-in request is still finishing. Wait a moment before trying again. Your saved work is kept.")
            AuthFailureStage.LOCAL_STORAGE -> return reference(
                "This phone could not save the account workspace. Your saved work is kept. Share this reference for help.")
            AuthFailureStage.SESSION_CHANGED -> return reference(
                "Sign-in was interrupted because the account session changed. Sign in again with your original account.")
            else -> Unit
        }
        // A known SDK code takes precedence over broad subclasses such as InvalidUserException.
        when (authFailureCode(chain)) {
            "ERROR_USER_DISABLED" -> return reference("This account is disabled. Contact the app owner for help.")
            "ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED", "ERROR_INVALID_API_KEY",
            "ERROR_API_NOT_AVAILABLE", "ERROR_API_NOT_AVAILABLE_WITHOUT_GOOGLE_PLAY" -> return reference(
                "This app build cannot use the account service. Install the latest StudyPath APK, then try again.")
            "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN" -> return reference(
                "Sign in again with your original account to reconnect. Your saved work is kept.")
            "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND",
            "ERROR_WRONG_PASSWORD", "ERROR_INVALID_EMAIL" -> return CREDENTIAL_MESSAGE
            "ERROR_TOO_MANY_REQUESTS" -> return "Too many attempts. Wait a moment, then try again."
            "ERROR_NETWORK_REQUEST_FAILED" -> return authConnectionMessage(useEmulators)
        }
        return when {
            chain.any { it is FirebaseNetworkException } -> authConnectionMessage(useEmulators)
            chain.any { it is FirebaseTooManyRequestsException } -> "Too many attempts. Wait a moment, then try again."
            chain.any { it is FirebaseAuthUserCollisionException } -> "An account already uses this email. Sign in with that account."
            chain.any { it is FirebaseAuthWeakPasswordException } -> PasswordPolicy.REQUIREMENT
            chain.any { it is FirebaseAuthInvalidCredentialsException || it is FirebaseAuthInvalidUserException } -> CREDENTIAL_MESSAGE
            else -> reference(unconfirmedRequestMessage(useEmulators))
        }
    }

    private fun unconfirmedRequestMessage(useEmulators: Boolean): String =
        "Couldn’t finish. Try again with the same account." +
            if (useEmulators) " This test build needs a connection to your test computer." else ""

    fun recoveryErrorMessage(error: Exception): String = when (error) {
        is FirebaseNetworkException -> authConnectionMessage(BuildConfig.USE_FIREBASE_EMULATORS)
        is FirebaseAuthInvalidCredentialsException -> "Check the email address, then try again."
        else -> errorMessage(error)
    }
}

private fun authFailureChain(error: Throwable): List<Throwable> = generateSequence(error) { it.cause }.take(8).toList()

// Exact SDK identifiers only. Unknown text is never copied into a support reference or diagnostic.
private val authReferenceCodes = setOf(
    "ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND", "ERROR_WRONG_PASSWORD",
    "ERROR_INVALID_EMAIL", "ERROR_USER_DISABLED", "ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED",
    "ERROR_INVALID_API_KEY", "ERROR_API_NOT_AVAILABLE", "ERROR_API_NOT_AVAILABLE_WITHOUT_GOOGLE_PLAY",
    "ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_TOO_MANY_REQUESTS", "ERROR_NETWORK_REQUEST_FAILED",
    "ERROR_EMAIL_ALREADY_IN_USE", "ERROR_WEAK_PASSWORD", "ERROR_INTERNAL_ERROR", "ERROR_QUOTA_EXCEEDED",
    "ERROR_INVALID_CERT_HASH", "ERROR_MISSING_CLIENT_IDENTIFIER", "ERROR_ALTERNATE_CLIENT_IDENTIFIER_REQUIRED",
    "ERROR_MISSING_RECAPTCHA_TOKEN", "ERROR_INVALID_RECAPTCHA_TOKEN", "ERROR_RECAPTCHA_NOT_ENABLED",
    "ERROR_CAPTCHA_CHECK_FAILED", "ERROR_TOKEN_REFRESH_UNAVAILABLE",
)

private fun authFailureCode(chain: List<Throwable>): String =
    chain.filterIsInstance<FirebaseAuthException>().firstOrNull()?.errorCode?.let {
        if (it in authReferenceCodes) it else "UNRECOGNIZED"
    } ?: "NONE"

/** Fixed environment, stage and SDK identifiers; no messages, credentials or account identifiers. */
internal fun authFailureReference(error: Throwable, useEmulators: Boolean = BuildConfig.USE_FIREBASE_EMULATORS): String {
    val chain = authFailureChain(error)
    val stage = chain.filterIsInstance<AuthFailureException>().firstOrNull()?.stage ?: AuthFailureStage.ACCOUNT_SERVICE
    return "AUTH-${if (useEmulators) "LOCAL" else "CLOUD"}-${stage.name}-${authFailureCode(chain)}"
}

/** Error messages can contain account data; diagnostics include only class names and allowlisted SDK codes. */
internal fun authFailureDiagnostic(error: Throwable): String {
    val chain = authFailureChain(error)
    val stage = chain.filterIsInstance<AuthFailureException>().firstOrNull()?.stage ?: AuthFailureStage.ACCOUNT_SERVICE
    return "exception=${error.javaClass.name}; stage=${stage.name}; firebaseAuthCode=${authFailureCode(chain)}; causes=" +
        chain.drop(1).joinToString(" -> ") { it.javaClass.name }
}
