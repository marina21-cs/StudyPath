package com.studypath.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.studypath.app.BuildConfig
import com.studypath.app.StudyPathApplication
import com.studypath.app.databinding.ActivityLoginBinding
import com.studypath.app.ui.MainActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val repository get() = (application as StudyPathApplication).graph.repository
    private var authJob: Job? = null
    private var openingMain = false
    private var recovery = false
    private var resetSubmitted = false
    private var recoveryStatus = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyInsets(binding.root)
        recovery = savedInstanceState?.getBoolean("recovery") == true
        resetSubmitted = savedInstanceState?.getBoolean("reset_submitted") == true
        recoveryStatus = savedInstanceState?.getString("recovery_status").orEmpty()
        binding.backButton.setOnClickListener { if (recovery) leaveRecovery() else finish() }
        onBackPressedDispatcher.addCallback(this) {
            if (recovery) leaveRecovery() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
        }
        binding.signInButton.setOnClickListener {
            if (recovery && resetSubmitted) setRecovery(false)
            else if (recovery) resetPassword() else signIn()
        }
        binding.recoveryButton.setOnClickListener { setRecovery(true) }
        binding.registerButton.setOnClickListener {
            if (recovery) setRecovery(false) else startActivity(Intent(this, RegisterActivity::class.java))
        }
        binding.accountDetailsButton.setOnClickListener {
            binding.accountNotice.isVisible = !binding.accountNotice.isVisible
            binding.accountDetailsButton.text = if (binding.accountNotice.isVisible) "About this account  −" else "About this account  +"
        }
        binding.passwordInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { signIn(); true } else false
        }
        binding.emailInput.setOnEditorActionListener { _, action, _ ->
            if (recovery && action == EditorInfo.IME_ACTION_DONE) { resetPassword(); true } else false
        }
        renderMode()
        if (!recovery) resumeSessionOnce()
    }

    private fun resumeSessionOnce() {
        authJob = lifecycleScope.launch {
            setBusy(true)
            AuthUi.status(binding.statusText, "Opening your account…")
            try {
                if (repository.resumeSession() != null) openWorkspace()
                else AuthUi.status(binding.statusText, "")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AuthUi.status(binding.statusText, AuthUi.errorMessage(error), error = true)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun signIn() {
        if (authJob?.isActive == true) return
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        binding.emailLayout.error = if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) null else "Enter a valid email address."
        binding.passwordLayout.error = if (password.isNotBlank()) null else "Enter your password."
        if (binding.emailLayout.error != null || binding.passwordLayout.error != null) return
        binding.passwordInput.text?.clear()
        AuthUi.hideKeyboard(this, binding.root)
        authJob = lifecycleScope.launch {
            setBusy(true)
            AuthUi.status(binding.statusText, "Signing in…")
            try {
                awaitAuthRequest { repository.login(email, password) }
                openWorkspace()
            } catch (_: AuthRequestTimeoutException) {
                AuthUi.status(binding.statusText, authTimeoutMessage(AuthRequestKind.SIGN_IN,
                    BuildConfig.USE_FIREBASE_EMULATORS), error = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AuthUi.status(binding.statusText, AuthUi.errorMessage(error), error = true)
            } finally {
                setBusy(false)
                showAccountNotice()
            }
        }
    }

    private fun showAccountNotice() {
        val linked = repository.boundUid() != null
        val notice = if (linked)
            "Use your original account to open saved study data. Pending offline work stays safely linked to it."
        else "One account per installation. Internet needed for first login."
        binding.accountNotice.text = "$notice\n\n${AuthUi.buildDetails()}"
        binding.accountNotice.setTextIsSelectable(true)
        if (linked && !recovery) binding.intro.text = "Sign in to your original account. Pending offline work is preserved for you."
        binding.registerButton.isEnabled = !binding.progress.isVisible && (recovery || !linked)
        binding.registerButton.text = if (recovery) "Back to sign in" else if (linked) "Account already linked" else "Create an account"
    }

    private fun setRecovery(enabled: Boolean) {
        if (authJob?.isActive == true) return
        recovery = enabled; resetSubmitted = false; recoveryStatus = ""
        binding.passwordInput.text?.clear()
        binding.emailLayout.error = null; binding.passwordLayout.error = null
        renderMode()
    }

    private fun leaveRecovery() {
        // Cancel the UI wait only. Firebase may still send the requested email.
        authJob?.cancel(); authJob = null
        setRecovery(false)
    }

    private fun renderMode() {
        binding.heading.text = if (recovery) "Reset password." else "Welcome back."
        binding.intro.text = if (recovery) "Use the email linked to your account." else "Sign in to your original account."
        binding.passwordLayout.isVisible = !recovery
        binding.recoveryButton.isVisible = !recovery
        binding.signInButton.text = if (!recovery) "Sign in" else if (resetSubmitted) "Back to sign in" else "Send reset email"
        binding.emailInput.imeOptions = if (recovery) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        AuthUi.status(binding.statusText, if (recovery) recoveryStatus else "")
        setBusy(false); showAccountNotice()
    }

    private fun resetPassword() {
        if (authJob?.isActive == true || resetSubmitted) return
        val email = binding.emailInput.text.toString().trim()
        binding.emailLayout.error = if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) null else "Enter a valid email address."
        if (binding.emailLayout.error != null) return
        AuthUi.hideKeyboard(this, binding.root)
        authJob = lifecycleScope.launch {
            setBusy(true)
            recoveryStatus = "Requesting a reset email…"
            AuthUi.status(binding.statusText, recoveryStatus)
            try {
                // Password recovery sends an email; it never opens or rebinds a workspace.
                awaitAuthRequest { FirebaseAuth.getInstance().sendPasswordResetEmail(email).await() }
                resetComplete()
            } catch (_: AuthRequestTimeoutException) {
                recoveryStatus = authTimeoutMessage(AuthRequestKind.PASSWORD_RESET, BuildConfig.USE_FIREBASE_EMULATORS)
                AuthUi.status(binding.statusText, recoveryStatus, error = true)
            } catch (error: CancellationException) { throw error }
            catch (_: FirebaseAuthInvalidUserException) {
                // Keep the same response when the project has enumeration protection disabled.
                resetComplete()
            } catch (error: Exception) {
                recoveryStatus = AuthUi.recoveryErrorMessage(error)
                AuthUi.status(binding.statusText, recoveryStatus, error = true)
            } finally { setBusy(false); showAccountNotice() }
        }
    }

    private fun resetComplete() {
        resetSubmitted = true
        recoveryStatus = "If this email is eligible, a reset link will arrive. Check your inbox and spam folder, then return to sign in."
        binding.signInButton.text = "Back to sign in"
        AuthUi.status(binding.statusText, recoveryStatus)
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        binding.signInButton.isEnabled = !busy
        binding.registerButton.isEnabled = !busy && repository.boundUid() == null
        binding.recoveryButton.isEnabled = !busy
        binding.backButton.isEnabled = true
        binding.emailInput.isEnabled = !busy && !resetSubmitted
        binding.passwordInput.isEnabled = !busy
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("recovery", recovery)
        outState.putBoolean("reset_submitted", resetSubmitted)
        outState.putString("recovery_status", if (authJob?.isActive == true && recovery)
            "The request was interrupted. A reset email may still arrive. Check your inbox before trying again." else recoveryStatus)
    }

    private fun openWorkspace() {
        openingMain = true
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    override fun onStop() {
        binding.passwordInput.text?.clear()
        if (!openingMain && authJob?.isActive == true) {
            authJob?.cancel()
            val message = if (recovery) "The request was interrupted. A reset email may still arrive. Check your inbox before trying again."
                else "Sign-in was interrupted. Enter your password to try again."
            recoveryStatus = if (recovery) message else recoveryStatus
            AuthUi.status(binding.statusText, message)
        }
        super.onStop()
    }
}
