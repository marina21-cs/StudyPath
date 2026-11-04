package com.studypath.app.ui

import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.ReminderTarget
import com.studypath.app.data.StudyReminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Due work is useful even when Android notification delivery is blocked. */
internal fun Fragment.createStudyReminder(target: ReminderTarget, title: String) {
    ReminderUi.pickFutureTime(requireContext(), System.currentTimeMillis() + 30 * 60_000L) { dueAt, zone ->
        if (!isAdded || view == null) return@pickFutureTime
        val repository = (requireActivity().application as StudyPathApplication).graph.repository
        val moduleId = when (target) { is ReminderTarget.LegacyModule -> target.moduleId; is ReminderTarget.ModuleVersion -> target.moduleId; is ReminderTarget.SavedReading -> null }
        val reminder = StudyReminder(FirebaseStudyRepository.newRecordId(), moduleId, title.take(120), dueAt, true, zone, target = target)
        val content = requireContext().column(16)
        val status = content.label("Saving reminder…", 16f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if (!ReminderScheduler.allowed(requireContext())) {
            content.label("Notifications are off. This item remains available in your due list.", 14f)
            content.action("Notification settings") { ReminderUi.showNotificationSettings(this) }
        }
        MaterialAlertDialogBuilder(requireContext()).setTitle("Study reminder").setView(content).setPositiveButton("Close", null).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withTimeout(20_000) { repository.saveReminder(reminder) }
                status.text = "Reminder saved\n${dateLabel(dueAt)}\nAndroid may delay notification delivery."
            } catch (_: TimeoutCancellationException) { status.text = "Still saving. Check your reminders in Account." }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = "Couldn’t save reminder. ${failureText(error)}" }
        }
    }
}
