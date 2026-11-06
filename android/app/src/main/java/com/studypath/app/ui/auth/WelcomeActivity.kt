package com.studypath.app.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.studypath.app.StudyPathApplication
import com.studypath.app.databinding.ActivityWelcomeBinding
import com.studypath.app.ui.MainActivity
import com.studypath.app.ui.GiraffeMascotView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWelcomeBinding
    private val repository get() = (application as StudyPathApplication).graph.repository
    private var resumeJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        AuthUi.applyInsets(binding.root)
        binding.mascot.play(GiraffeMascotView.Pose.WAKE)
        binding.demoButton.setOnClickListener { startActivity(Intent(this, com.studypath.app.ui.DemoActivity::class.java)) }
        binding.loginButton.setOnClickListener { startActivity(Intent(this, LoginActivity::class.java)) }
        binding.signUpButton.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
    }

    override fun onStart() {
        super.onStart()
        resumeJob = lifecycleScope.launch {
            setBusy(true)
            AuthUi.status(binding.statusText, "")
            try {
                if (repository.resumeSession() != null) {
                    startActivity(Intent(this@WelcomeActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    finish()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AuthUi.status(binding.statusText, AuthUi.errorMessage(error), error = true)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.isVisible = busy
        binding.loginButton.isEnabled = !busy
        binding.signUpButton.isEnabled = !busy && repository.boundUid() == null
        binding.signUpButton.text = if (repository.boundUid() == null) "Get started" else "Account linked"
    }

    override fun onStop() {
        resumeJob?.cancel()
        super.onStop()
    }
}
