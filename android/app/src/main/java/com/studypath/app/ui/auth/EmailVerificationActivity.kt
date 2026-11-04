package com.studypath.app.ui.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.studypath.app.BuildConfig
import com.studypath.app.R
import com.studypath.app.StudyPathApplication
import com.studypath.app.databinding.ActivityEmailVerificationBinding
import com.studypath.app.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EmailVerificationActivity : AppCompatActivity() {
    private lateinit var binding: ActivityEmailVerificationBinding
    private lateinit var verification: EmailVerificationFlow
    private val repository get() = (application as StudyPathApplication).graph.repository
    private var requestJob: Job? = null
    private var busy = false
    private var statusMessage = ""
    private var statusIsError = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityEmailVerificationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyInsets(binding.root)
        val ownerUid = intent.getStringExtra(OWNER_UID).orEmpty()
        val cooldowns = getSharedPreferences("email-verification", MODE_PRIVATE)
        verification = EmailVerificationFlow(ownerUid, FirebaseEmailVerificationAccount(
            FirebaseAuth.getInstance(), ownerUid, repository::boundUid),
            System::currentTimeMillis, cooldowns.getLong(ownerUid, 0)) { deadline ->
            cooldowns.edit().putLong(ownerUid, deadline).apply()
        }
        binding.helpText.setText(if (BuildConfig.USE_FIREBASE_EMULATORS)
            R.string.verification_local_help else R.string.verification_help)
        binding.sendVerificationButton.setOnClickListener { requestVerification(send = true) }
        binding.checkVerificationButton.setOnClickListener { requestVerification(send = false) }
        binding.openEmailButton.setOnClickListener { openEmail() }
        binding.continueStudyingButton.setOnClickListener { continueStudying() }
        onBackPressedDispatcher.addCallback(this) { continueStudying() }
        val initialMessage = when {
            savedInstanceState?.getBoolean("verificationInFlight") == true -> getString(R.string.verification_interrupted)
            savedInstanceState?.getString("verificationMessage") != null -> savedInstanceState.getString("verificationMessage").orEmpty()
            runCatching { verification.current().verified }.getOrDefault(false) -> getString(R.string.verification_verified)
            intent.getBooleanExtra(NEW_ACCOUNT, false) -> getString(R.string.verification_created)
            else -> getString(R.string.verification_pending)
        }
        showStatus(initialMessage, savedInstanceState?.getBoolean("verificationError") == true)
        renderControls()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) { renderControls(); delay(1_000) }
            }
        }
    }

    private fun requestVerification(send: Boolean) {
        if (requestJob?.isActive == true) return
        requestJob = lifecycleScope.launch {
            busy = true
            showStatus(getString(if (send) R.string.verification_sending else R.string.verification_checking))
            renderControls()
            try {
                val message = if (send) when (verification.send()) {
                    VerificationSendResult.REQUESTED -> if (BuildConfig.USE_FIREBASE_EMULATORS)
                        R.string.verification_local_requested else R.string.verification_requested
                    VerificationSendResult.WAIT -> R.string.verification_pending
                    VerificationSendResult.ALREADY_VERIFIED -> R.string.verification_verified
                } else if (verification.refresh()) R.string.verification_verified else R.string.verification_still_pending
                showStatus(getString(message))
            } catch (_: AuthRequestTimeoutException) {
                showStatus(getString(if (send) R.string.verification_send_timeout else R.string.verification_check_timeout), true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: VerificationSessionChangedException) {
                showStatus(getString(R.string.verification_session_changed), true)
            } catch (_: FirebaseAuthInvalidUserException) {
                showStatus(getString(R.string.verification_account_unavailable), true)
            } catch (_: FirebaseTooManyRequestsException) {
                showStatus(getString(R.string.verification_throttled), true)
            } catch (_: FirebaseNetworkException) {
                val message = getString(if (send) R.string.verification_send_failed else R.string.verification_check_failed)
                showStatus("$message ${authConnectionMessage(BuildConfig.USE_FIREBASE_EMULATORS)}", true)
            } catch (_: Exception) {
                showStatus(getString(if (send) R.string.verification_send_failed else R.string.verification_check_failed), true)
            } finally {
                busy = false
                renderControls()
            }
        }
    }

    private fun renderControls() {
        val current = try { verification.current() } catch (_: VerificationSessionChangedException) {
            showStatus(getString(R.string.verification_session_changed), true)
            null
        }
        binding.emailText.text = current?.let { getString(R.string.verification_email, it.email) }.orEmpty()
        val remaining = verification.resendSeconds
        binding.progress.isVisible = busy
        binding.sendVerificationButton.isVisible = current?.verified != true
        binding.sendVerificationButton.isEnabled = !busy && current != null && remaining == 0L
        binding.sendVerificationButton.text = when {
            remaining > 0 -> getString(R.string.verification_wait, remaining)
            verification.hasRequested -> getString(R.string.verification_resend)
            else -> getString(R.string.verification_send)
        }
        binding.checkVerificationButton.isEnabled = !busy && current != null
        binding.openEmailButton.isEnabled = !busy
    }

    private fun showStatus(message: String, error: Boolean = false) {
        statusMessage = message
        statusIsError = error
        AuthUi.status(binding.statusText, message, error)
    }

    private fun openEmail() {
        try { startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL)) }
        catch (_: ActivityNotFoundException) { showStatus(getString(R.string.verification_no_mail_app)) }
    }

    private fun continueStudying() {
        requestJob?.cancel()
        if (intent.getBooleanExtra(NEW_ACCOUNT, false)) {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("verificationMessage", statusMessage)
        outState.putBoolean("verificationError", statusIsError)
        outState.putBoolean("verificationInFlight", requestJob?.isActive == true)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        if (requestJob?.isActive == true) {
            requestJob?.cancel()
            showStatus(getString(R.string.verification_interrupted))
        }
        super.onStop()
    }

    companion object {
        private const val OWNER_UID = "verificationOwnerUid"
        private const val NEW_ACCOUNT = "verificationNewAccount"
        fun forAccount(context: Context, ownerUid: String): Intent =
            Intent(context, EmailVerificationActivity::class.java).putExtra(OWNER_UID, ownerUid)
        fun forNewAccount(context: Context, ownerUid: String): Intent =
            forAccount(context, ownerUid).putExtra(NEW_ACCOUNT, true)
    }
}
