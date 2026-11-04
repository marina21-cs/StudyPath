package com.studypath.app.ui.auth

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import android.view.inputmethod.EditorInfo
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.studypath.app.BuildConfig
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.PasswordPolicy
import com.studypath.app.databinding.ActivityRegisterBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRegisterBinding
    private val repository get() = (application as StudyPathApplication).graph.repository
    private var authJob: Job? = null
    private var openingMain = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyInsets(binding.root)
        binding.signupNotice.text = "${binding.signupNotice.text}\n\n${AuthUi.buildDetails()}"
        binding.signupNotice.setTextIsSelectable(true)
        binding.accountDetailsButton.setOnClickListener {
            binding.signupNotice.isVisible = !binding.signupNotice.isVisible
            binding.accountDetailsButton.text = if (binding.signupNotice.isVisible) "About this account  −" else "About this account  +"
        }
        binding.backButton.setOnClickListener { finish() }
        binding.createButton.setOnClickListener { createAccount() }
        binding.signInButton.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        binding.passwordInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { createAccount(); true } else false
        }
        if (repository.boundUid() != null) {
            binding.createButton.isEnabled = false
            AuthUi.status(binding.statusText, "This installation already has an account. Return to sign in with the original account.")
        }
    }

    private fun createAccount() {
        if (authJob?.isActive == true || repository.boundUid() != null) return
        val email = binding.emailInput.text.toString().trim()
        val password = binding.passwordInput.text.toString()
        binding.emailLayout.error = if (Patterns.EMAIL_ADDRESS.matcher(email).matches()) null else "Enter a valid email address."
        binding.passwordLayout.error = if (PasswordPolicy.accepts(password)) null else PasswordPolicy.REQUIREMENT
        if (binding.emailLayout.error != null || binding.passwordLayout.error != null) return
        clearPasswords()
        AuthUi.hideKeyboard(this, binding.root)
        authJob = lifecycleScope.launch {
            setBusy(true)
            AuthUi.status(binding.statusText, "Creating your account…")
            try {
                // A display name is optional setup, not another barrier before the first reading.
                val session = awaitAuthRequest { repository.register(email, password, "Student") }
                openingMain = true
                startActivity(EmailVerificationActivity.forNewAccount(this@RegisterActivity, session.uid)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            } catch (_: AuthRequestTimeoutException) {
                AuthUi.status(binding.statusText, authTimeoutMessage(AuthRequestKind.REGISTRATION,
                    BuildConfig.USE_FIREBASE_EMULATORS, repository.boundUid() != null), error = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = if (repository.boundUid() != null)
                    "Your account is created. Sign in again to finish setup."
                else AuthUi.errorMessage(error)
                AuthUi.status(binding.statusText, message, error = true)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun clearPasswords() {
        binding.passwordInput.text?.clear()
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        binding.createButton.isEnabled = !busy && repository.boundUid() == null
        binding.signInButton.isEnabled = !busy
        listOf(binding.emailInput, binding.passwordInput).forEach { it.isEnabled = !busy }
    }

    override fun onStop() {
        clearPasswords()
        if (!openingMain && authJob?.isActive == true) {
            authJob?.cancel()
            AuthUi.status(binding.statusText, "The request was interrupted. If the account was created, sign in with the same email.")
        }
        super.onStop()
    }
}
