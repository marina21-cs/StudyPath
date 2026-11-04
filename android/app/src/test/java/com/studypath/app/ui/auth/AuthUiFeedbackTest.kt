package com.studypath.app.ui.auth

import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.studypath.app.data.LegacyReadingRecoveryException
import org.junit.Assert.*
import org.junit.Test

class AuthUiFeedbackTest {
    @Test fun `disabled accounts get distinct guidance before the broad invalid user fallback`() {
        val error = FirebaseAuthInvalidUserException("ERROR_USER_DISABLED", "Private account details")
        val message = AuthUi.errorMessage(error, useEmulators = false)
        assertTrue(message.contains("disabled", ignoreCase = true))
        assertFalse(message.contains("email and password"))
        assertFalse(message.contains("Create an account"))
        assertFalse(message.contains("Private account details"))
    }

    @Test fun `known app authorization failures explain the build instead of blaming credentials`() {
        for (code in listOf("ERROR_OPERATION_NOT_ALLOWED", "ERROR_APP_NOT_AUTHORIZED")) {
            val message = AuthUi.errorMessage(FirebaseAuthException(code, "Private configuration details"), useEmulators = false)
            assertTrue("$code must identify an app-build problem", message.contains("app build"))
            assertTrue("$code needs a copyable support reference", message.contains("Reference:"))
            assertFalse(message.contains("email and password"))
            assertFalse(message.contains("internet", ignoreCase = true))
            assertFalse(message.contains("Private configuration details"))
        }
    }

    @Test fun `a wrapper does not hide a known credential rejection`() {
        val error = IllegalStateException("private wrapper", FirebaseAuthException("ERROR_INVALID_CREDENTIAL", "private cause"))
        val message = AuthUi.errorMessage(error, useEmulators = false)
        assertTrue(message.contains("email and password"))
        assertFalse(message.contains("private wrapper"))
        assertFalse(message.contains("private cause"))
    }

    @Test fun `protected legacy cache recovery explains preservation and identifies its stage`() {
        val error = LegacyReadingRecoveryException(IllegalStateException("private cached reading"))
        val message = AuthUi.errorMessage(error, useEmulators = false)
        assertTrue(message.contains("saved work", ignoreCase = true))
        assertTrue(message.contains("kept", ignoreCase = true))
        assertTrue(message.contains("recovery", ignoreCase = true))
        assertTrue(message.contains("Reference:"))
        assertTrue(message.contains("READING_RECOVERY"))
        assertFalse(message.contains("private cached reading"))
        assertFalse(message.contains("Try again with the same account"))
    }

    @Test fun `unknown failures expose only a bounded support reference`() {
        val error = IllegalStateException("student@example.com password secret-token")
        val message = AuthUi.errorMessage(error, useEmulators = false)
        assertTrue(message.contains("Reference:"))
        assertTrue(message.contains("CLOUD"))
        for (privateValue in listOf("student@example.com", "password", "secret-token")) assertFalse(message.contains(privateValue))
    }

    @Test fun `unrecognized SDK code text cannot become a diagnostic identifier`() {
        val error = FirebaseAuthException("ERROR_PRIVATE_TOKEN_123", "private-message")
        val message = AuthUi.errorMessage(error, useEmulators = false)
        val diagnostic = authFailureDiagnostic(error)
        assertFalse(message.contains("PRIVATE_TOKEN_123"))
        assertFalse(diagnostic.contains("PRIVATE_TOKEN_123"))
        assertTrue(diagnostic.contains("UNRECOGNIZED"))
    }

    @Test fun `SDK credential error codes remain actionable when returned as base auth exceptions`() {
        for (code in listOf("ERROR_INVALID_LOGIN_CREDENTIALS", "ERROR_INVALID_CREDENTIAL", "ERROR_USER_NOT_FOUND", "ERROR_WRONG_PASSWORD")) {
            val message = AuthUi.errorMessage(FirebaseAuthException(code, "Private SDK details"), useEmulators = false)
            assertTrue("$code must explain the credential problem", message.contains("email and password"))
            assertTrue("$code must explain how a first-time user can continue", message.contains("Create an account"))
            assertFalse(message.contains("Private SDK details"))
        }
    }

    @Test fun `invalid credential subclasses explain first time account creation without claiming an account exists`() {
        val message = AuthUi.errorMessage(FirebaseAuthInvalidCredentialsException(
            "ERROR_INVALID_CREDENTIAL", "Private SDK details"), useEmulators = false)
        assertTrue(message.contains("email and password"))
        assertTrue(message.contains("Create an account"))
        assertFalse(message.contains("No account"))
    }

    @Test fun `an unknown SDK failure explains local build requirements without inventing a network diagnosis`() {
        val sdkFailure = FirebaseAuthException("ERROR_INTERNAL_ERROR", "Private SDK details")
        val local = AuthUi.errorMessage(sdkFailure, useEmulators = true)
        assertTrue(local.contains("Couldn’t finish"))
        assertTrue(local.contains("test build"))
        assertTrue(local.contains("test computer"))
        assertFalse(local.contains("Could not reach"))
        assertFalse(local.contains("Check your connection"))
        assertFalse(local.contains("email and password"))
    }

    @Test fun `an unknown cloud failure does not blame internet or expose emulator setup`() {
        val message = AuthUi.errorMessage(IllegalArgumentException("Unknown failure"), useEmulators = false)
        assertTrue(message.contains("Couldn’t finish"))
        assertTrue(message.contains("same account"))
        assertFalse(message.contains("internet", ignoreCase = true))
        assertFalse(message.contains("connection", ignoreCase = true))
        assertFalse(message.contains("test services"))
    }

    @Test fun `known account failures keep distinct actionable guidance`() {
        val credentials = AuthUi.errorMessage(FirebaseAuthInvalidCredentialsException(
            "ERROR_WRONG_PASSWORD", "Private SDK details"), useEmulators = true)
        val collision = AuthUi.errorMessage(FirebaseAuthUserCollisionException(
            "ERROR_EMAIL_ALREADY_IN_USE", "Private SDK details"), useEmulators = true)
        val limited = AuthUi.errorMessage(FirebaseTooManyRequestsException("Private SDK details"), useEmulators = true)
        assertTrue(credentials.contains("email and password"))
        assertTrue(collision.contains("already uses this email"))
        assertTrue(limited.contains("Too many attempts"))
        assertEquals(3, setOf(credentials, collision, limited).size)
    }

    @Test fun `diagnostics retain SDK code and cause classes without exception messages`() {
        val sdkFailure = FirebaseAuthException("ERROR_INTERNAL_ERROR", "secret-password student@example.com token-value")
        val wrapped = IllegalArgumentException("wrapper-secret", sdkFailure)
        val diagnostic = authFailureDiagnostic(wrapped)
        assertTrue(diagnostic.contains(IllegalArgumentException::class.java.name))
        assertTrue(diagnostic.contains(FirebaseAuthException::class.java.name))
        assertTrue(diagnostic.contains("ERROR_INTERNAL_ERROR"))
        for (privateValue in listOf("secret-password", "student@example.com", "token-value", "wrapper-secret")) {
            assertFalse("Exception messages must never enter diagnostics", diagnostic.contains(privateValue))
        }
    }

    @Test fun `unexpected error code values cannot carry private data into diagnostics`() {
        val diagnostic = authFailureDiagnostic(FirebaseAuthException("student@example.com", "secret-password"))
        assertFalse(diagnostic.contains("student@example.com"))
        assertFalse(diagnostic.contains("secret-password"))
        assertTrue(diagnostic.contains("UNRECOGNIZED"))
    }
}
