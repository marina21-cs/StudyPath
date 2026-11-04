package com.studypath.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.view.Gravity
import android.content.res.ColorStateList
import com.studypath.app.R
import com.google.firebase.auth.FirebaseAuth
import com.studypath.app.ui.auth.EmailVerificationActivity
import kotlinx.coroutines.flow.combine
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.BuildConfig
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.StudyReminder
import com.studypath.app.domain.ReminderPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

class AccountFragment : Fragment() {
    private val repository get() = (requireActivity().application as StudyPathApplication).graph.repository
    private var reminderMutationActive = false
    private val expanded = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expanded.addAll(savedInstanceState?.getStringArrayList("accountSections").orEmpty())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = requireContext().column(20)

        content.label(repository.currentSession()?.displayName?.takeIf { it.isNotBlank() } ?: "Your account", 26f)
            .apply { tag = "profile_name"; setTextColor(context.tint(R.color.sp_muted)) }
        content.label("Loading your study track…", 14f).tag = "profile_study_track"
        content.secondaryAction("Edit profile") {
            (requireActivity() as MainActivity).openDetails(EditProfileFragment())
        }.tag = "profile_edit_direct"
        val upNext = content.card("Up Next", "")
        upNext.addView(requireContext().column(0).apply { tag = "profile_due_queue"; label("Checking due reminders…", 14f) })
        upNext.addView(requireContext().column(0).apply {
            tag = "profile_next_review"; label("Checking your next review…", 14f)
        })
        val reminders = disclosure(content, "Study reminders", "Optional · delivery is approximate").apply { tag = "reminders" }
        reminders.label("Loading reminders…", 14f)
        content.label("", 14f).apply { tag = "reminder_status"; visibility = View.GONE }
        val family = disclosure(content, "Family summaries", "Off until you share")
        family.label("Share lesson progress. Scores and notes stay private.", 14f)
        family.secondaryAction("Manage family summaries") {
            (requireActivity() as MainActivity).openDetails(FamilySharingFragment())
        }.tag = "profile_family_action"
        val settings = disclosure(content, "Your study & account", "Preferences and sign-in")
        settings.secondaryAction("Edit learning preferences") {
            (requireActivity() as MainActivity).openDetails(LearnerPreferencesFragment.editing())
        }.tag = "profile_preferences_action"
        settings.addView(requireContext().column(0).apply { tag = "profile_sync_section"; label("Checking saved changes…", 14f) })
        settings.label(getString(R.string.verification_account_pending), 14f).tag = "profile_email_verification_status"
        settings.secondaryAction(getString(R.string.verification_account_action)) {
            val owner = repository.currentSession()?.uid ?: return@secondaryAction
            startActivity(EmailVerificationActivity.forAccount(requireContext(), owner))
        }.tag = "profile_email_verification_action"
        settings.secondaryAction("Account details") { showAccountDetails() }.tag = "profile_account_action"
        return requireContext().scrolling(content)
    }

    override fun onResume() {
        super.onResume()
        val owner = repository.currentSession()?.uid
        val user = FirebaseAuth.getInstance().currentUser
        val originalAccount = owner != null && repository.boundUid() == owner && user?.uid == owner
        val verified = originalAccount && user?.isEmailVerified == true
        view?.findViewWithTag<TextView>("profile_email_verification_status")?.setText(when {
            !originalAccount -> R.string.verification_session_changed
            verified -> R.string.verification_account_verified
            else -> R.string.verification_account_pending
        })
        view?.findViewWithTag<MaterialButton>("profile_email_verification_action")?.apply {
            isEnabled = originalAccount
            setText(if (verified) R.string.verification_account_recheck else R.string.verification_account_action)
        }
    }

    private fun disclosure(content: LinearLayout, title: String, detail: String): LinearLayout {
        val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
            radius = context.dp(16).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(context.tint(R.color.sp_card))
        }
        val wrap = requireContext().column(0)
        val panel = requireContext().column(0).apply { visibility = if (title in expanded) View.VISIBLE else View.GONE }
        val toggle = wrap.secondaryAction(title) {}.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            strokeWidth = context.dp(1); iconTint = ColorStateList.valueOf(context.tint(R.color.sp_action)); iconGravity = MaterialButton.ICON_GRAVITY_END; iconSize = context.dp(20)
            tag = "account_section_$title"
        }
        fun update() {
            toggle.icon = androidx.core.content.ContextCompat.getDrawable(requireContext(),
                if (panel.visibility == View.VISIBLE) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            toggle.contentDescription = "$title, ${if (panel.visibility == View.VISIBLE) "collapse" else "expand"}"
        }
        toggle.setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (panel.visibility == View.VISIBLE) expanded.add(title) else expanded.remove(title)
            update()
        }
        wrap.label(detail, 14f).apply {
            tag = "account_section_status_$title"
            setTextColor(requireContext().tint(R.color.sp_muted))
        }
        wrap.addView(panel); card.addView(wrap)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = requireContext().dp(12) })
        update()
        return panel
    }

    private fun showAccountDetails() {
        val accountKind = if (BuildConfig.USE_FIREBASE_EMULATORS) "Test account" else "StudyPath account"
        val accountContent = requireContext().column(20)
        accountContent.label("${repository.currentSession()?.email.orEmpty()}\n$accountKind\n\nUse this account to return to your saved work.")
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Account details")
            .setView(requireContext().scrolling(accountContent)).setPositiveButton("Close", null).create()
        accountContent.secondaryAction("Notifications") {
            dialog.dismiss()
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName))
        }.tag = "profile_account_notifications"
        accountContent.secondaryAction("Sign out") { dialog.dismiss(); confirmSignOut() }.tag = "profile_account_signout"
        dialog.show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = (view as android.widget.ScrollView).getChildAt(0) as LinearLayout
        val reminders = content.findViewWithTag<LinearLayout>("reminders")
        val reminderStatus = content.findViewWithTag<TextView>("reminder_status")
        val nextReview = content.findViewWithTag<LinearLayout>("profile_next_review")
        ProfileSyncSection(this, repository).start(content.findViewWithTag("profile_sync_section"))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.profileName().catch { }.collect { name ->
                        content.findViewWithTag<TextView>("profile_name").text = name ?: "Your account"
                    }
                }
                launch {
                    repository.learnerPreferences().catch {
                        content.findViewWithTag<TextView>("profile_study_track").text = "Study track unavailable · your saved work is kept"
                    }.collect { preferences ->
                        content.findViewWithTag<TextView>("profile_study_track").text = listOfNotNull(
                            preferences.levelId?.let(com.studypath.app.data.PreferenceChoices::label),
                            preferences.curriculumVersion?.let(com.studypath.app.data.PreferenceChoices::label),
                            if (preferences.onboardingComplete) "Published course pack" else "Personal study draft"
                        ).joinToString(" · ")
                    }
                }
                launch {
                    while (true) {
                        renderNextReview(nextReview)
                        delay(ReminderPresentation.millisUntilRefresh(System.currentTimeMillis()))
                    }
                }
                val minuteTicks = flow {
                    while (true) { emit(Unit); delay(ReminderPresentation.millisUntilRefresh(System.currentTimeMillis())) }
                }
                combine(repository.reminders(), minuteTicks) { rows, _ -> rows }
                    .catch {
                        reminders.removeAllViews(); reminders.label(failureText(it), 14f)
                        content.findViewWithTag<LinearLayout>("profile_due_queue").apply {
                            removeAllViews(); label("Due reminders could not refresh. ${failureText(it)}", 14f)
                        }
                    }.collect { rows ->
                    content.findViewWithTag<TextView>("account_section_status_Study reminders").text =
                        "${rows.count { it.enabled }} enabled · " + if (ReminderScheduler.allowed(requireContext()))
                            "notifications on" else "notifications off"
                    val dueQueue = content.findViewWithTag<LinearLayout>("profile_due_queue")
                    dueQueue.removeAllViews()
                    val due = rows.filter { it.enabled && it.dueAt <= System.currentTimeMillis() }.sortedBy { it.dueAt }
                    if (due.isEmpty()) dueQueue.label("No reminders due.", 14f)
                    due.forEach { row -> reminderRow(dueQueue, row, reminderStatus, "due") }
                    reminders.removeAllViews()
                    rows.sortedBy { it.dueAt }.forEach { row -> reminderRow(reminders, row, reminderStatus, "all") }
                    reminders.label(ReminderPresentation.summary(rows, System.currentTimeMillis()) { "${it.title} · ${dateLabel(it.dueAt)}" }, 14f)
                        .tag = "profile_reminder_summary"
                    reminders.secondaryAction("Choose a time or edit reminders") {
                        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Study reminders")
                        if (rows.isEmpty()) {
                            dialog.setMessage("Reminders are off until you add one from a lesson or saved reading. Android may delay delivery to save battery.")
                                .setPositiveButton("Got it", null).show()
                        } else {
                            dialog.setItems(rows.map { "${it.title}\n${ReminderPresentation.targetLabel(it)} · ${dateLabel(it.dueAt)} · ${if (it.enabled) "on" else "off"}" }.toTypedArray()) { _, position ->
                                showReminder(rows[position], reminderStatus)
                            }.setNegativeButton("Close", null).show()
                        }
                    }.tag = "profile_reminders_action"
                    if (ReminderScheduler.allowed(requireContext())) {
                        reminders.label("Delivery is approximate.", 14f).tag = "profile_notification_status"
                    } else {
                        reminders.label("Notifications are off. Your reminders remain available here.", 14f).tag = "profile_notification_status"
                        reminders.secondaryAction("Notification settings") { ReminderUi.showNotificationSettings(this@AccountFragment) }.tag = "profile_notification_settings"
                    }
                }
            }
        }
    }

    private data class NextReview(val key: com.studypath.app.data.TextPackageKey, val title: String,
                                  val reviewId: String, val dueAt: Long)

    private suspend fun renderNextReview(panel: LinearLayout) {
        val owner = repository.currentSession()?.uid ?: return
        try {
            val next = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                repository.downloadedTextPackages().flatMap { stored ->
                    val module = com.studypath.app.data.InteractiveLessonParser.parse(stored.verified)
                    val history = repository.lessonHistory(module)
                    module.lessons.filter { it.reviewOf == null }.mapNotNull { original ->
                        com.studypath.app.data.LessonReviewPolicy.schedule(module, original.id, history)?.let { schedule ->
                            NextReview(stored.key, module.lessons.single { it.id == schedule.reviewLessonId }.title,
                                schedule.reviewLessonId, schedule.dueAt)
                        }
                    }
                }.minByOrNull { it.dueAt }
            }
            if (!isAdded || view == null || repository.currentSession()?.uid != owner) return
            panel.removeAllViews()
            if (next == null) return
            val card = panel.card("Next review", "")
            card.label(next.title, 24f)
            card.label(if (next.dueAt <= System.currentTimeMillis()) "Suggested now"
                else "Suggested ${dateLabel(next.dueAt)}", 14f)
            card.action("Open review") {
                (requireActivity() as MainActivity).openDetails(InteractiveLessonFragment.forReview(next.key, next.reviewId))
            }.apply { layoutParams.width = -1; tag = "profile_open_review" }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (isAdded && view != null && repository.currentSession()?.uid == owner) {
                panel.removeAllViews(); panel.card("Next review unavailable", "Your saved work stays on this device. ${failureText(error)}")
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("accountSections", ArrayList(expanded)); super.onSaveInstanceState(outState)
    }

    private fun LinearLayout.secondaryAction(title: String, run: () -> Unit): MaterialButton = action(title, run).apply {
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(context.tint(R.color.sp_ink))
        iconTint = ColorStateList.valueOf(context.tint(R.color.sp_action))
        iconGravity = MaterialButton.ICON_GRAVITY_END
        setIconResource(R.drawable.ic_chevron_right)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        minHeight = context.dp(48); minimumHeight = context.dp(48)
        strokeColor = ColorStateList.valueOf(context.tint(R.color.sp_outline))
        strokeWidth = context.dp(1)
    }

    private fun reminderRow(parent: LinearLayout, row: StudyReminder, status: TextView, location: String) {
        val uid = repository.currentSession()?.uid ?: return
        val card = parent.card(row.title, "${dateLabel(row.dueAt)} · ${if (row.enabled) "On" else "Off"} · ${ReminderPresentation.targetLabel(row)}")
        card.tag = "reminder_row_${location}_${row.id}"
        card.secondaryAction("Open material") {
            if (repository.currentSession()?.uid == uid) (requireActivity() as MainActivity).openReminder(row.id)
        }.tag = "reminder_inline_open_${location}_${row.id}"
        card.secondaryAction("Change time") { editReminder(row, uid, status, row.enabled) }
        card.secondaryAction(if (row.enabled) "Turn off" else "Turn on") {
            if (!row.enabled && row.dueAt <= System.currentTimeMillis()) editReminder(row, uid, status, true)
            else changeReminder(uid, status, "Updating…", "Reminder updated") {
                repository.saveReminder(row.copy(enabled = !row.enabled))
            }
        }
        card.secondaryAction("Remove") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Remove reminder?")
                .setMessage("Remove ${row.title} from your reminders?")
                .setNegativeButton("Keep", null).setPositiveButton("Remove reminder") { _, _ ->
                    changeReminder(uid, status, "Removing…", "Removed") {
                        repository.deleteReminder(row.id)
                    }
                }.show()
        }
    }

    private fun showReminder(row: StudyReminder, status: TextView) {
        val ownerUid = repository.currentSession()?.uid ?: return
        val content = requireContext().column(16)
        content.label(ReminderPresentation.targetLabel(row), 14f)
        content.label("${dateLabel(row.dueAt)} · ${if (row.enabled) "on" else "off"}", 14f)
        content.label(if (ReminderScheduler.allowed(requireContext())) "Delivery is approximate."
            else "Notifications are off. Open your study material here; delivery resumes when notifications are allowed.", 14f)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(row.title)
            .setView(requireContext().scrolling(content)).setNegativeButton("Close", null).create()
        content.action("Open study material") {
            dialog.dismiss()
            if (repository.currentSession()?.uid == ownerUid) (requireActivity() as MainActivity).openReminder(row.id)
        }.apply { tag = "reminder_open_${row.id}"; layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT }
        content.action("Edit reminder") {
            dialog.dismiss()
            editReminder(row, ownerUid, status, row.enabled)
        }
        content.action(if (row.enabled) "Turn off" else "Turn on") {
            dialog.dismiss()
            if (!row.enabled && row.dueAt <= System.currentTimeMillis()) {
                editReminder(row, ownerUid, status, true)
            } else {
                val updated = row.copy(enabled = !row.enabled)
                changeReminder(ownerUid, status, "Updating…",
                    "Reminder turned ${if (updated.enabled) "on" else "off"}") {
                    repository.saveReminder(updated)
                }
            }
        }
        content.action("Remove reminder") {
            dialog.dismiss()
            MaterialAlertDialogBuilder(requireContext()).setTitle("Remove reminder?")
                .setMessage("Remove ${row.title} from your reminders?")
                .setNegativeButton("Keep", null).setPositiveButton("Remove reminder") { _, _ ->
                    changeReminder(ownerUid, status, "Removing…", "Removed") {
                        repository.deleteReminder(row.id)
                    }
                }.show()
        }
        dialog.show()
    }

    private fun editReminder(row: StudyReminder, ownerUid: String, status: TextView, enabled: Boolean) {
        if (reminderMutationActive) return
        ReminderUi.pickFutureTime(requireContext(), row.dueAt) { dueAt, zoneId ->
            val updated = row.copy(dueAt = dueAt, zoneId = zoneId, enabled = enabled)
            val success = if (!row.enabled && enabled) "Reminder turned on"
                else "Reminder updated"
            changeReminder(ownerUid, status, "Updating…", success) {
                repository.saveReminder(updated)
            }
        }
    }

    private fun changeReminder(ownerUid: String, status: TextView, pending: String, confirmed: String, action: suspend () -> Unit) {
        if (!isAdded || view == null || repository.currentSession()?.uid != ownerUid || reminderMutationActive) return
        reminderMutationActive = true
        status.visibility = View.VISIBLE
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        status.text = pending
        viewLifecycleOwner.lifecycleScope.launch {
            try { withTimeout(20_000) { action() }; status.text = confirmed }
            catch (error: TimeoutCancellationException) { status.text = failureText(error) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = failureText(error) }
            finally { reminderMutationActive = false }
        }
    }

    private fun confirmSignOut() {
        val content = requireContext().column(20)
        content.label("Reminders will stop. Your work stays on this phone; sign in with the same account to return.")
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Sign out?")
            .setView(requireContext().scrolling(content)).setNegativeButton("Stay", null).create()
        content.action("Sign out") {
            dialog.dismiss()
            (requireActivity() as MainActivity).logout()
        }.apply {
            tag = "profile_confirm_signout"
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        dialog.show()
    }
}
