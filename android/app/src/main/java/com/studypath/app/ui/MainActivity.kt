package com.studypath.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import com.studypath.app.R
import com.studypath.app.StudyPathApplication
import com.studypath.app.domain.ReminderDeliveryGate
import com.studypath.app.databinding.ActivityMainBinding
import com.studypath.app.ui.auth.LoginActivity
import com.studypath.app.ui.planner.PlannerFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val repository get() = (application as StudyPathApplication).graph.repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                if (repository.resumeSession() == null) { returnToLogin(); return@launch }
                setupContent()
                val requestedReminder = intent.getStringExtra("reminderId")
                if (requestedReminder != null) { intent.removeExtra("reminderId"); openReminder(requestedReminder) }
                if (savedInstanceState == null && requestedReminder == null) {
                    binding.navigation.selectedItemId = R.id.nav_study
                    // A saved draft is a valid student choice even before matching lessons exist.
                    val preferences = withTimeoutOrNull(8000) {
                        repository.learnerPreferences().catch { }.firstOrNull()
                    }
                    if (preferences != null && preferences.levelId == null && preferences.subjectIds.isEmpty() &&
                        repository.currentSession()?.uid?.let { !OptionalOnboarding.wasSkipped(this@MainActivity, it) } == true) {
                        lifecycle.withResumed {
                            if (!supportFragmentManager.isStateSaved &&
                                supportFragmentManager.findFragmentById(R.id.main_content) is StudyFragment) {
                                openDetails(LearnerPreferencesFragment())
                            }
                        }
                    }
                }
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        repository.connection().catch {
                            binding.connectionStatus.text = "Unknown"
                            binding.connectionStatus.contentDescription = "Connection unavailable. ${failureText(it)}"
                        }.collect {
                            binding.connectionStatus.text = if (it) "Online" else "Offline"
                            binding.connectionStatus.contentDescription = if (it)
                                "Online"
                            else "Offline. Saved material is available."
                        }
                    }
                    launch {
                        repository.reminders().catch { }.collect { ReminderScheduler.reconcile(this@MainActivity, repository.currentSession()?.uid, it) }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { returnToLogin() }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::binding.isInitialized) intent.getStringExtra("reminderId")?.let { intent.removeExtra("reminderId"); openReminder(it) }
    }

    fun openReminder(id: String) {
        lifecycleScope.launch {
            try {
                val target = kotlinx.coroutines.withTimeout(12_000) { repository.reminderTarget(id) }
                lifecycle.withResumed {
                    if (supportFragmentManager.isStateSaved) return@withResumed
                    openDetails(when (target) {
                        is com.studypath.app.data.ReminderTarget.LegacyModule -> ModuleFragment.forModule(target.moduleId)
                        is com.studypath.app.data.ReminderTarget.ModuleVersion -> InteractiveLessonFragment.forPackage(com.studypath.app.data.TextPackageKey(target.curriculumVersion, target.moduleId, target.version))
                        is com.studypath.app.data.ReminderTarget.SavedReading -> ReadingFragment.forSaved(target.readingId)
                    })
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (!isFinishing && repository.currentSession() != null) MaterialAlertDialogBuilder(this@MainActivity)
                    .setTitle("Study material unavailable").setMessage(failureText(error)).setPositiveButton("Close", null).show()
            }
        }
    }
    private fun setupContent() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (resources.configuration.fontScale >= 1.5f) {
            binding.mainHeader.orientation = LinearLayout.VERTICAL
            binding.toolbar.layoutParams = LinearLayout.LayoutParams(-1, -2)
            binding.connectionStatus.layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.END
                marginEnd = dp(8)
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left + dp(8), bars.top + dp(4), bars.right + dp(8), bars.bottom + dp(4)); insets
        }
        // The root already reserves system-bar space; keep the floating pill's padding symmetric.
        ViewCompat.setOnApplyWindowInsetsListener(binding.navigation) { _, insets -> insets }
        binding.connectionStatus.setOnClickListener {
            MaterialAlertDialogBuilder(this).setTitle("Connection")
                .setMessage(binding.connectionStatus.contentDescription)
                .setPositiveButton("Close", null).show()
        }
        binding.toolbar.title = "StudyPath"
        binding.toolbar.isTitleCentered = false
        binding.toolbar.setTitleTextAppearance(this, R.style.TextAppearance_StudyPath_Title)
        binding.navigation.isItemActiveIndicatorEnabled = false
        binding.navigation.menu.apply {
            add(0, R.id.nav_study, 0, "Study").setIcon(R.drawable.ic_study).setContentDescription("Study")
            add(0, R.id.nav_saved, 1, "Saved").setIcon(R.drawable.ic_saved).setContentDescription("Saved")
            add(0, R.id.nav_planner, 2, "Planner").setIcon(R.drawable.ic_plan).setContentDescription("Planner")
            add(0, R.id.nav_account, 3, "Account").setIcon(R.drawable.ic_profile).setContentDescription("Account")
        }
        binding.navigation.setOnItemSelectedListener { item ->
            supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
            val fragment = when (item.itemId) {
                R.id.nav_saved -> SavedFragment()
                R.id.nav_planner -> PlannerFragment()
                R.id.nav_account -> AccountFragment()
                else -> StudyFragment()
            }
            supportFragmentManager.beginTransaction().replace(R.id.main_content, fragment).commit()
            true
        }
        fun updateChrome() {
            val current = supportFragmentManager.findFragmentById(R.id.main_content)
            val detail = current is ReadingFragment || current is ModuleFragment || current is LearnerPreferencesFragment || current is InteractiveLessonFragment || current is OfflinePdfFragment || current is FamilySharingFragment || current is OnboardingReadyFragment
            binding.toolbar.navigationIcon = if (detail)
                androidx.appcompat.content.res.AppCompatResources.getDrawable(this, androidx.appcompat.R.drawable.abc_ic_ab_back_material) else null
            binding.toolbar.navigationContentDescription = if (detail) "Back" else null
            binding.navigation.visibility = if (detail) View.GONE else View.VISIBLE
            binding.toolbar.title = when (current) {
                is FamilySharingFragment -> "Family summaries"
                is OfflinePdfFragment -> "Companion PDF"
                is ReadingFragment -> "Reading"
                is ModuleFragment -> "Module"
                is InteractiveLessonFragment -> "Lesson"
                is LearnerPreferencesFragment, is OnboardingReadyFragment -> ""
                is SavedFragment -> "Saved"
                is PlannerFragment -> "Planner"
                is AccountFragment -> "Account"
                else -> "StudyPath"
            }
        }
        // A back-stack callback can precede the completed pop on predictive-back Android builds.
        // Render chrome from the resumed destination, after FragmentManager applies the transaction.
        supportFragmentManager.addOnBackStackChangedListener { binding.root.post { updateChrome() } }
        supportFragmentManager.registerFragmentLifecycleCallbacks(object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            override fun onFragmentResumed(manager: androidx.fragment.app.FragmentManager, fragment: Fragment) {
                updateChrome()
            }
        }, false)
        binding.root.post { updateChrome() }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
    fun openDetails(fragment: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.main_content, fragment).addToBackStack(null).commit()
    }
    fun goToStudy() {
        supportFragmentManager.popBackStackImmediate(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        binding.navigation.selectedItemId = R.id.nav_study
    }
    fun logout() {
        ReminderDeliveryGate.endSession {
            repository.logout()?.let { ReminderScheduler.clear(this, it) }
        }
        returnToLogin()
    }
    private fun returnToLogin() {
        startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }
}
