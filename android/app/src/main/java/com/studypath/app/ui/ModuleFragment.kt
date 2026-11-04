package com.studypath.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.PracticeAttempt
import com.studypath.app.data.ReviewProgress
import com.studypath.app.data.StudyModule
import com.studypath.app.data.StudyReminder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.studypath.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ModuleModel : ViewModel() {
    val answers = mutableMapOf<String, String>()
    var pendingAttempt: PracticeAttempt? = null
    var submitted = false
    var moduleVersion: Int? = null
}
class ModuleFragment : Fragment() {
    private val model: ModuleModel by viewModels()
    private var module: StudyModule? = null
    private var reminderStatus: TextView? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (isAdded && view != null) {
            if (granted && ReminderScheduler.allowed(requireContext())) module?.let(::pickReminder)
            else ReminderUi.showNotificationSettings(this)
        }
    }
    private val repository get() = (requireActivity().application as StudyPathApplication).graph.repository
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = requireContext().scrolling(requireContext().column())
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = (view as android.widget.ScrollView).getChildAt(0) as LinearLayout
        fun load() {
            content.removeAllViews(); content.label("Opening module…", 24f)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    module = withTimeout(15_000) { repository.pinModule(requireArguments().getString("moduleId")!!) }
                    content.removeAllViews(); render(content, module!!)
                } catch (error: TimeoutCancellationException) { content.removeAllViews(); content.label(failureText(error)); content.action("Retry", ::load) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { content.removeAllViews(); content.label(failureText(error)); content.action("Retry", ::load) }
            }
        }
        load()
    }
    private fun render(content: LinearLayout, value: StudyModule) {
        content.label("Demo · not instructor approved", 14f)
        content.label(value.title, 28f)
        content.action("Source & module details") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Module details")
                .setMessage("${value.author}\n${value.source}\n\nVersion ${value.version} · demonstration content\nNot instructor approved. Practice results are separate from course grades.\n\nOpening a module while connected prepares its content for offline study.")
                .setPositiveButton("Got it", null).show()
        }.apply {
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(context.tint(R.color.sp_ink)); layoutParams.width = -1
        }
        val progress = content.label("Loading progress…", 14f)
        viewLifecycleOwner.lifecycleScope.launch {
            try { repository.reviewProgress(value.id, value.version).collect { progress.text = if (it?.completed == true) "Review complete" else "Review in progress" } }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { progress.text = failureText(error) }
        }
        value.sections.forEach { section -> content.card(section.title, section.body) }
        content.action("Mark review complete") {
            mutation(progress) { repository.saveProgress(ReviewProgress(value.id, value.version, true, (value.sections.size - 1).coerceAtLeast(0))) }
        }
        content.action("Add study reminder") {
            createStudyReminder(com.studypath.app.data.ReminderTarget.LegacyModule(value.id), value.title)
        }
        reminderStatus = content.label("", 14f).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.label("Practice", 24f)
        val uid = requireNotNull(repository.currentSession()).uid
        val drafts = PracticeDraftStore(requireContext(), uid)
        if (model.moduleVersion != value.version) {
            model.answers.clear(); model.submitted = false; model.moduleVersion = value.version
        }
        model.pendingAttempt = drafts.read(value.id)
        val answerGroups = mutableListOf<RadioGroup>()
        var submit: MaterialButton? = null
        value.practice.forEach { question ->
            val questionCard = content.card(question.prompt, "")
            val group = RadioGroup(requireContext())
            question.options.forEach { (id, title) ->
                val radio = RadioButton(requireContext()).apply {
                    this.id = View.generateViewId(); text = title; minHeight = requireContext().dp(48)
                    isChecked = model.answers[question.id] == id; tag = id
                    isEnabled = model.pendingAttempt == null
                    textSize = 16f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.geist)
                }
                group.addView(radio)
            }
            group.setOnCheckedChangeListener { parent, id ->
                if (id != View.NO_ID && model.pendingAttempt == null) {
                    model.answers[question.id] = parent.findViewById<RadioButton>(id).tag as String
                    model.submitted = false
                    submit?.apply { text = "Check practice"; isEnabled = true }
                }
            }
            answerGroups += group
            questionCard.addView(group)
        }
        val status = content.label(model.pendingAttempt?.let { "${it.correct}/${it.total} correct · waiting to save" } ?: if (model.submitted) "Practice result saved." else "Answer every question.", 14f)
        val checkPractice = content.action(if (model.submitted) "Practice saved" else if (model.pendingAttempt != null) "Retry saved result" else "Check practice") { }.apply {
            layoutParams.width = -1; isEnabled = !model.submitted
        }
        submit = checkPractice
        checkPractice.setOnClickListener {
            if (model.pendingAttempt == null && (value.practice.isEmpty() || value.practice.any { it.id !in model.answers })) { status.text = "Please answer every question."; return@setOnClickListener }
            if (model.submitted) { status.text = "This result is already saved. Change an answer to start another attempt."; return@setOnClickListener }
            val attempt = model.pendingAttempt ?: repository.newPracticeAttempt(value.id, value.version,
                value.practice.count { model.answers[it.id] == it.answerId }, value.practice.size).also { model.pendingAttempt = it }
            try { drafts.retain(attempt) } catch (error: Exception) { status.text = failureText(error); return@setOnClickListener }
            answerGroups.forEach { group -> for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = false }
            checkPractice.isEnabled = false; status.text = "Saving…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withTimeout(20_000) { repository.submitPracticeAttempt(attempt) }
                    drafts.acknowledge(attempt)
                    model.pendingAttempt = null
                    model.submitted = true
                    checkPractice.text = "Practice saved"
                    status.text = "${attempt.correct}/${attempt.total} correct · saved" + if (attempt.version == value.version) "\n" + value.practice.joinToString("\n") { "${it.prompt}: ${it.options[it.answerId]}" } else ""
                } catch (error: TimeoutCancellationException) { status.text = failureText(error) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { status.text = failureText(error) }
                finally {
                    checkPractice.isEnabled = !model.submitted
                    if (model.pendingAttempt != null) checkPractice.text = "Retry saved result"
                    answerGroups.forEach { group -> for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = model.pendingAttempt == null }
                }
            }
        }
    }
    private fun mutation(status: android.widget.TextView, block: suspend () -> Unit) {
        status.text = "Saving…"
        viewLifecycleOwner.lifecycleScope.launch {
            try { withTimeout(20_000) { block() }; status.text = "Saved" }
            catch (error: TimeoutCancellationException) { status.text = failureText(error) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { status.text = failureText(error) }
        }
    }
    private fun pickReminder(value: StudyModule) {
        val ownerUid = repository.currentSession()?.uid ?: return
        ReminderUi.pickFutureTime(requireContext(), System.currentTimeMillis() + 30 * 60_000L) { dueAt, zoneId ->
            if (!isAdded || view == null || repository.currentSession()?.uid != ownerUid) return@pickFutureTime
            if (!ReminderScheduler.allowed(requireContext())) {
                ReminderUi.showNotificationSettings(this)
                return@pickFutureTime
            }
            val reminder = StudyReminder(FirebaseStudyRepository.newRecordId(), value.id, value.title, dueAt, true, zoneId)
            reminderStatus?.apply { visibility = View.VISIBLE; text = "Saving reminder…" }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withTimeout(20_000) { repository.saveReminder(reminder) }
                    reminderStatus?.text = "Reminder saved"
                    if (isAdded) MaterialAlertDialogBuilder(requireContext()).setTitle("Study reminder")
                        .setMessage("Reminder saved\n${dateLabel(reminder.dueAt)}\nAndroid may delay delivery to save battery.")
                        .setPositiveButton("Got it", null).show()
                } catch (error: TimeoutCancellationException) { reminderStatus?.text = failureText(error); showError(error) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { reminderStatus?.text = failureText(error); showError(error) }
            }
        }
    }
    override fun onDestroyView() {
        reminderStatus = null
        module = null
        super.onDestroyView()
    }
    private fun showError(error: Exception) { if (isAdded) com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setMessage(failureText(error)).setPositiveButton("OK", null).show() }
    companion object { fun forModule(id: String) = ModuleFragment().apply { arguments = Bundle().apply { putString("moduleId", id) } } }
}
