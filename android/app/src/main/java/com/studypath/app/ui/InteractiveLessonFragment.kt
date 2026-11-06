package com.studypath.app.ui

import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.doOnLayout
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.studypath.app.R
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.InteractiveLessonParser
import com.studypath.app.data.InteractiveModule
import com.studypath.app.data.LessonAnswerStatus
import com.studypath.app.data.LessonEngine
import com.studypath.app.data.LessonQuestion
import com.studypath.app.data.LessonQuestionFormat
import com.studypath.app.data.LessonRun
import com.studypath.app.data.LessonReviewPolicy
import com.studypath.app.data.LessonReviewSchedule
import com.studypath.app.data.LessonStep
import com.studypath.app.data.LessonStepKind
import com.studypath.app.data.TextPackageKey
import com.studypath.app.data.ReadingContextKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** The model serializes durable transitions and survives view recreation. */
class InteractiveLessonModel : ViewModel() {
    val changes = MutableStateFlow(0)
    val deviceSaveStatus = MutableStateFlow("Saved on this phone")
    private var queuedLocalWrites = 0
    private var unsavedRun: LessonRun? = null
    var leaving = false; private set
    val hasPendingDeviceSave get() = queuedLocalWrites > 0 || unsavedRun != null
    var module: InteractiveModule? = null; private set
    var run: LessonRun? = null; private set
    var onPath = true; private set
    var error: String? = null; private set
    var notice: String? = null; private set
    var confirmedAt: Long? = null; private set
    var pdfAssets: List<com.studypath.app.data.PackageAssetDescriptor> = emptyList(); private set
    var pendingCount = 0; private set
    var operationLabel = "Saving…"; private set
    var busy = false; private set
    val runs = mutableMapOf<String, LessonRun>()
    var history: List<LessonRun> = emptyList(); private set
    private val writes = Mutex()

    fun load(repository: FirebaseStudyRepository, key: TextPackageKey, resumeLessonId: String? = null, reviewLessonId: String? = null) {
        if (module != null || busy) return
        busy = true; changed()
        viewModelScope.launch {
            try {
                val parsed = withContext(Dispatchers.IO) {
                    val stored = requireNotNull(repository.openTextPackage(key)) { "Download this lesson again from Study." }
                    pdfAssets = stored.verified.assets
                    InteractiveLessonParser.parse(stored.verified)
                }
                val retained = parsed.lessons.mapNotNull { lesson -> repository.lessonRun(parsed, lesson.id)?.let { lesson.id to it } }.toMap()
                history = repository.lessonHistory(parsed)
                pendingCount = repository.pendingLessonRuns(parsed).size
                module = parsed; runs.putAll(retained); error = null
                if (reviewLessonId != null) {
                    val review = requireNotNull(parsed.lessons.singleOrNull { it.id == reviewLessonId && it.reviewOf != null }) { "This authored review is unavailable." }
                    requireNotNull(LessonReviewPolicy.schedule(parsed, requireNotNull(review.reviewOf), history)) { "Complete the original lesson before opening its review." }
                    val saved = retained[reviewLessonId]?.takeUnless { it.completed }
                    val selected = saved ?: LessonEngine.start(parsed, reviewLessonId,
                        LessonReviewPolicy.nextRunStartedAt(parsed, history, System.currentTimeMillis())).also { persist(repository, parsed, it) }
                    run = selected; runs[reviewLessonId] = selected; retainHistory(selected); onPath = false
                } else if (resumeLessonId != null) {
                    retained[resumeLessonId]?.let { saved ->
                        run = saved; onPath = false
                        confirmedAt = if (saved.completed) repository.lessonRunConfirmedAt(parsed, saved) else null
                    } ?: run { notice = "Choose another lesson to continue." }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = "Could not open this lesson. ${failureText(failure)}" }
            finally { busy = false; changed() }
        }
    }

    fun open(repository: FirebaseStudyRepository, lessonId: String, again: Boolean = false) {
        if (busy || leaving) return
        val content = module ?: return
        busy = true; operationLabel = "Saving…"; beginLocalSave()
        viewModelScope.launch {
            try {
                writes.withLock {
                    unsavedRun?.let { pending ->
                        persist(repository, content, pending)
                        run = pending; runs[pending.lessonId] = pending; retainHistory(pending)
                    }
                    val saved = if (again) null else runs[lessonId]
                    val value = saved ?: LessonEngine.start(content, lessonId,
                        LessonReviewPolicy.nextRunStartedAt(content, history, System.currentTimeMillis()))
                        .also { persist(repository, content, it) }
                    run = value; runs[lessonId] = value; retainHistory(value); onPath = false; error = null; notice = null
                    confirmedAt = if (value.completed) repository.lessonRunConfirmedAt(content, value) else null
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = "Could not save your lesson position. ${failureText(failure)}" }
            finally { busy = false; finishLocalSave(); changed() }
        }
    }

    fun path(repository: FirebaseStudyRepository) = leaveAfterSaved(repository) {
        onPath = true; error = null; notice = null; changed()
    }

    /** Navigation waits only for local saves; network acknowledgements remain cancellable. */
    fun leaveAfterSaved(repository: FirebaseStudyRepository, navigate: () -> Unit) {
        if (leaving) return
        leaving = true
        if (hasPendingDeviceSave) deviceSaveStatus.value = "Finishing save…"
        viewModelScope.launch {
            try {
                writes.withLock {
                    unsavedRun?.let { value ->
                        val content = requireNotNull(module)
                        persist(repository, content, value)
                        run = value; runs[value.lessonId] = value; retainHistory(value)
                    }
                }
                leaving = false
                navigate()
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                leaving = false
                deviceSaveStatus.value = "Couldn’t save. Go back to try again."
                error = "Your latest change is kept for a save retry. Back retries saving before leaving. ${failureText(failure)}"
                changed()
            }
        }
    }

    fun transition(repository: FirebaseStudyRepository, change: (InteractiveModule, LessonRun) -> LessonRun) {
        if (busy || leaving) return
        busy = true; operationLabel = "Saving…"; beginLocalSave()
        viewModelScope.launch {
            try {
                writes.withLock {
                    val content = requireNotNull(module); val current = unsavedRun ?: requireNotNull(run)
                    val updated = change(content, current)
                    persist(repository, content, updated)
                    run = updated; runs[updated.lessonId] = updated; retainHistory(updated); error = null; notice = "Saved on this phone"
                    if (updated.completed) confirmedAt = repository.lessonRunConfirmedAt(content, updated)
                    if (updated.completed) pendingCount = repository.pendingLessonRuns(content).size
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = "Your next step was not saved. Try again. ${failureText(failure)}" }
            finally { busy = false; finishLocalSave(); changed() }
        }
    }

    fun draft(repository: FirebaseStudyRepository, stepId: String, response: List<String>) {
        if (busy || leaving) return
        beginLocalSave()
        viewModelScope.launch {
            try {
                writes.withLock {
                    val content = module ?: return@withLock; val current = unsavedRun ?: run ?: return@withLock
                    val next = LessonEngine.select(content, current, stepId, response, System.currentTimeMillis())
                    persist(repository, content, next)
                    run = next; runs[next.lessonId] = next; retainHistory(next); error = null
                }
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = "Your answer draft was not saved. Enter it again or use Back to retry saving. ${failureText(failure)}"; changed() }
            finally { finishLocalSave() }
        }
    }

    fun submit(repository: FirebaseStudyRepository, all: Boolean = false) {
        if (busy || leaving) return
        val content = module ?: return
        val completed = run?.takeIf { it.completed }
        if (!all && completed == null) return
        busy = true; operationLabel = "Saving…"
        viewModelScope.launch {
            try {
                withTimeout(20_000) {
                    if (all) repository.syncPendingLessonRuns(content)
                    else repository.submitLessonRun(content, requireNotNull(completed))
                }
                if (completed != null) confirmedAt = repository.lessonRunConfirmedAt(content, completed)
                pendingCount = repository.pendingLessonRuns(content).size
                notice = if (all) "Results saved" else "Result saved"; error = null
            } catch (_: TimeoutCancellationException) { error = "Saved on this phone. Reconnect to save to your account." }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { error = "The result is saved on this device. ${failureText(failure)}" }
            finally { busy = false; changed() }
        }
    }

    private suspend fun persist(repository: FirebaseStudyRepository, module: InteractiveModule, value: LessonRun) {
        unsavedRun = value
        repository.saveLessonRun(module, value)
        unsavedRun = null
    }
    private fun beginLocalSave() {
        queuedLocalWrites += 1
        deviceSaveStatus.value = "Saving…"
    }
    private fun finishLocalSave() {
        queuedLocalWrites -= 1
        deviceSaveStatus.value = when {
            queuedLocalWrites > 0 -> "Saving…"
            unsavedRun != null -> "Couldn’t save. Go back to try again."
            else -> "Saved on this phone"
        }
    }
    private fun changed() { changes.value += 1 }
    private fun retainHistory(value: LessonRun) { history = history.filterNot { it.runId == value.runId } + value }
}

/** One authored activity at a time, with a stable action area and local-first feedback. */
class InteractiveLessonFragment : Fragment() {
    private val model: InteractiveLessonModel by viewModels()
    private val repository get() = (requireActivity().application as StudyPathApplication).graph.repository
    private lateinit var body: LinearLayout
    private lateinit var actions: LinearLayout
    private lateinit var scroll: ScrollView
    private var status: TextView? = null
    private var response: () -> List<String> = { emptyList() }
    private var lastAnnouncement: String? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = requireContext().column(0)
        root.layoutParams = ViewGroup.LayoutParams(-1, -1)
        body = requireContext().column(20)
        body.background = GradientDrawable().apply {
            setColor(requireContext().tint(R.color.sp_card)); cornerRadius = requireContext().dp(24).toFloat()
        }
        scroll = requireContext().scrolling(body)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(requireContext().dp(16), requireContext().dp(12), requireContext().dp(16), 0)
        })
        actions = requireContext().column(16)
        actions.setBackgroundColor(requireContext().tint(R.color.sp_background))
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        lastAnnouncement = savedInstanceState?.getString("announcement")
        viewLifecycleOwner.lifecycleScope.launch { model.changes.collect { render() } }
        viewLifecycleOwner.lifecycleScope.launch {
            model.deviceSaveStatus.collect { text ->
                if (model.run?.completed != true || model.hasPendingDeviceSave) status?.text = text
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                model.leaveAfterSaved(repository) {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                if (model.leaving) disableForSaving()
            }
        })
        model.load(repository, packageKey(), arguments?.getString("lessonId"), arguments?.getString("reviewLessonId"))
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putString("announcement", lastAnnouncement); super.onSaveInstanceState(outState) }

    private fun render() {
        body.removeAllViews(); actions.removeAllViews(); status = null; response = { emptyList() }
        actions.visibility = View.VISIBLE
        val module = model.module
        if (module == null) {
            body.label(if (model.busy) "Opening downloaded lesson…" else "Lesson unavailable", 24f)
            model.error?.let { body.label(it); primary("Retry opening") { model.load(repository, packageKey(), arguments?.getString("lessonId"), arguments?.getString("reviewLessonId")) } }
            return
        }
        if (model.onPath) path(module) else {
            val run = requireNotNull(model.run)
            val step = LessonEngine.currentStep(module, run)
            if (step == null) summary(module, run) else activity(module, run, step)
        }
        model.error?.let { body.label(it, 14f).apply { tag = "lesson_error"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE } }
        if (actions.childCount == 0) actions.visibility = View.GONE
        scroll.post { if (view != null) scroll.scrollTo(0, 0) }
    }

    private fun path(module: InteractiveModule) {
        body.tag = "lesson_path"
        body.label(module.summary.title, 28f)
        body.label("Ready offline", 14f)
        if (module.objectives.isNotEmpty()) {
            val objectives = requireContext().column(0).apply {
                module.objectives.values.forEach { label(it) }
            }
            body.studyDisclosure("What you’ll learn", objectives, "lesson_objectives")
        }
        lessonTools(body, module)
        val originalLessons = module.lessons.filter { it.reviewOf == null }
        originalLessons.forEach { lesson ->
            val saved = model.runs[lesson.id]
            val label = when { saved?.completed == true -> "Completed · view summary"; saved != null -> "Continue · step ${saved.position + 1} of ${lesson.steps.size}"; else -> "Available · ${lesson.steps.size} steps" }
            val entry = if (originalLessons.size == 1 && lesson.title == module.summary.title) {
                body.label(label, 14f)
                body
            } else body.card(lesson.title, label)
            entry.action(if (saved == null) "Start lesson" else if (saved.completed) "View summary" else "Continue lesson") {
                guard { model.open(repository, lesson.id) }
            }.apply { tag = "lesson_start_${lesson.id}"; contentDescription = "$label: ${lesson.title}" }
            reviewSchedule(module, lesson.id)?.let { schedule ->
                val review = module.lessons.first { it.id == schedule.reviewLessonId }
                val savedReview = model.runs[review.id]
                val reviewCard = body.card(review.title, reviewDueText(schedule)).apply { tag = "lesson_review_${lesson.id}" }
                reviewCard.action(if (savedReview != null && !savedReview.completed) "Continue review" else if (schedule.dueAt <= now()) "Start review" else "Practise review early") {
                    guard { model.open(repository, review.id, again = savedReview?.completed == true) }
                }.tag = "lesson_start_${review.id}"
                if (savedReview?.completed == true) reviewCard.action("View latest review summary") { guard { model.open(repository, review.id) } }
            }
        }
        if (model.pendingCount > 0) body.action("Save results to account (${model.pendingCount})") { guard { model.submit(repository, all = true) } }.tag = "lesson_sync_all"
        body.action("Add study reminder") {
            val selected = module.summary
            createStudyReminder(com.studypath.app.data.ReminderTarget.ModuleVersion(selected.curriculumVersion, selected.moduleId, selected.version), selected.title)
        }.tag = "lesson_reminder"
        model.notice?.let { body.label(it, 14f).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val next = originalLessons.firstOrNull { model.runs[it.id]?.completed != true }
        if (next == null) {
            val nextReview = originalLessons.mapNotNull { reviewSchedule(module, it.id) }.filter { it.dueAt <= now() }.minByOrNull { it.dueAt }
            if (nextReview != null) primary("Review due lesson") {
                guard { model.open(repository, nextReview.reviewLessonId, again = model.runs[nextReview.reviewLessonId]?.completed == true) }
            } else actions.label("Lessons complete. Review anytime.", 14f)
        }
        body.label("Practice doesn’t affect your grades.", 14f)
    }

    private fun activity(module: InteractiveModule, run: LessonRun, step: LessonStep) {
        body.tag = "lesson_step"
        val lesson = module.lessons.first { it.id == run.lessonId }
        quiet(body.action("‹ Lesson path") { guard { model.path(repository) } })
        body.label(lesson.title, 20f)
        lessonTools(body, module)
        body.label("Step ${run.position + 1} of ${lesson.steps.size}", 14f).tag = "lesson_progress"
        body.addView(LinearProgressIndicator(requireContext()).apply {
            max = lesson.steps.size; progress = run.position; isIndeterminate = false
            setIndicatorColor(context.tint(R.color.sp_ink)); trackColor = context.tint(R.color.sp_background)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, requireContext().dp(4)).apply { topMargin = requireContext().dp(8); bottomMargin = requireContext().dp(16) })
        body.label(step.title, 24f)
        body.label(step.body).setTextIsSelectable(true)
        if (step.kind == LessonStepKind.CHECK) question(module, run, step)
        else primary("Continue") { transition { value, current -> LessonEngine.continueStep(value, current, step.id, now()) } }
        status = actions.label(if (model.hasPendingDeviceSave) model.deviceSaveStatus.value else model.notice ?: "Saved on this phone", 14f).apply { tag = "lesson_sync" }
    }

    private fun question(module: InteractiveModule, run: LessonRun, step: LessonStep) {
        val question = module.questions.getValue(requireNotNull(step.questionId))
        val answer = run.answers[question.id]
        val awaiting = answer == null || answer.status == LessonAnswerStatus.AWAITING
        body.label(question.prompt, 18f)
        when (question.format) {
            LessonQuestionFormat.SELECTED_RESPONSE -> choices(question, step, answer?.draft.orEmpty(), awaiting)
            LessonQuestionFormat.ORDERING -> ordering(question, step, answer?.draft.orEmpty(), awaiting)
            LessonQuestionFormat.BOUNDED_RESPONSE -> bounded(question, step, answer?.draft.orEmpty(), awaiting)
        }
        if (awaiting) {
            val check = primary("Check") {
                val selected = response()
                transition { value, current ->
                    val draft = LessonEngine.select(value, current, step.id, selected, now())
                    LessonEngine.check(value, draft, step.id, now())
                }
            }
            check.contentDescription = "Check answer"
            check.isEnabled = LessonEngine.isValidResponse(question, response())
            quiet(body.action("Show hint") { transition { value, current -> LessonEngine.hint(value, current, step.id, now()) } })
                .apply { tag = "lesson_hint"; isEnabled = answer?.hintUsed != true }
            if (answer?.hintUsed == true) feedback("Hint", question.hint, "hint_${run.runId}_${step.id}")
        } else {
            val correct = answer?.status == LessonAnswerStatus.CORRECT
            val assisted = answer?.status == LessonAnswerStatus.ASSISTED
            val heading = when { assisted -> "Assisted practice"; correct -> "That’s it."; else -> "Take another look." }
            val chosenFeedback = if (question.format == LessonQuestionFormat.SELECTED_RESPONSE)
                question.options.firstOrNull { it.id == answer?.draft?.firstOrNull() }?.feedback.orEmpty() else ""
            val explanation = listOf(chosenFeedback, question.explanation).filter { it.isNotBlank() }.distinct().joinToString("\n\n")
            feedback(heading, explanation, "${run.runId}_${step.id}_${answer?.status}_${answer?.wrongCount}", correct)
            if (!correct) quiet(body.action("Find related readings →") { relatedReadings(module) }).tag = "lesson_related_readings"
            if (assisted) body.label("Solution: ${solution(question)}", 16f)
            if (correct || assisted) primary("Continue") { transition { value, current -> LessonEngine.continueStep(value, current, step.id, now()) } }
            else {
                primary("Try again") { transition { value, current -> LessonEngine.retry(value, current, step.id, now()) } }.tag = "lesson_retry"
                quiet(body.action("Reveal solution") { transition { value, current -> LessonEngine.reveal(value, current, step.id, now()) } }).apply { tag = "lesson_reveal"; contentDescription = "Reveal assisted solution" }
            }
        }
    }

    private fun choices(question: LessonQuestion, step: LessonStep, draft: List<String>, enabled: Boolean) {
        val group = RadioGroup(requireContext())
        question.options.forEachIndexed { index, option ->
            group.addView(RadioButton(requireContext()).apply {
                id = View.generateViewId(); tag = "lesson_option_${option.id}"; text = "${('A'.code + index).toChar()}  ${option.text}"
                textSize = 16f; typeface = ResourcesCompat.getFont(context, R.font.geist)
                minHeight = context.dp(56); setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
                isChecked = draft.firstOrNull() == option.id; isEnabled = enabled
                background = GradientDrawable().apply {
                    setColor(context.tint(if (isChecked) R.color.sp_action else R.color.sp_background))
                    cornerRadius = context.dp(16).toFloat()
                }
                setTextColor(context.tint(if (isChecked) com.studypath.app.R.color.sp_on_action else R.color.sp_ink))
                buttonTintList = ColorStateList.valueOf(context.tint(if (isChecked) com.studypath.app.R.color.sp_on_action else R.color.sp_ink))
                setOnClickListener { transition { module, run -> LessonEngine.select(module, run, step.id, listOf(option.id), now()) } }
            }, RadioGroup.LayoutParams(-1, -2).apply { bottomMargin = requireContext().dp(8) })
        }
        body.addView(group)
        response = { question.options.firstOrNull { option -> group.findViewWithTag<RadioButton>("lesson_option_${option.id}")?.isChecked == true }?.let { listOf(it.id) }.orEmpty() }
    }

    private fun ordering(question: LessonQuestion, step: LessonStep, draft: List<String>, enabled: Boolean) {
        val ordered = draft.ifEmpty { question.options.map { it.id } }
        body.label("Put the items in order. Use Move up or Move down.", 14f)
        ordered.forEachIndexed { index, id ->
            val option = question.options.first { it.id == id }
            val card = body.card("${index + 1}. ${option.text}", "")
            if (enabled) {
                fun move(offset: Int) {
                    val changed = ordered.toMutableList().apply { add(index + offset, removeAt(index)) }
                    transition { module, run -> LessonEngine.select(module, run, step.id, changed, now()) }
                }
                card.action("Move up") { move(-1) }.apply { isEnabled = index > 0; tag = "lesson_order_up_$id"; contentDescription = "Move ${option.text} up" }
                card.action("Move down") { move(1) }.apply { isEnabled = index < ordered.lastIndex; tag = "lesson_order_down_$id"; contentDescription = "Move ${option.text} down" }
            }
        }
        response = { ordered }
    }

    private fun bounded(question: LessonQuestion, step: LessonStep, draft: List<String>, enabled: Boolean) {
        val numeric = question.responseType == "numeric"
        val edit = body.field(if (question.unit.isBlank()) "Your answer" else "Your answer (${question.unit})",
            if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED else InputType.TYPE_CLASS_TEXT)
        edit.tag = "lesson_response"; edit.filters = arrayOf(InputFilter.LengthFilter(80))
        edit.setText(draft.firstOrNull().orEmpty()); edit.isEnabled = enabled
        response = { listOf(edit.text.toString()).filter { it.isNotBlank() } }
        val inputStatus = body.label("", 14f).apply { visibility = View.GONE }
        edit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!enabled) return
                val valid = LessonEngine.isValidResponse(question, response())
                actions.findViewWithTag<MaterialButton>("lesson_primary")?.isEnabled = valid
                inputStatus.text = if (numeric) "Enter a finite number. The unit is shown in the answer label." else "Enter an answer of up to 80 characters."
                inputStatus.visibility = if (!s.isNullOrBlank() && !valid) View.VISIBLE else View.GONE
                model.draft(repository, step.id, listOf(s?.toString().orEmpty()))
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun feedback(title: String, explanation: String, announcement: String, celebrate: Boolean = false) {
        body.card(title, explanation).apply {
            tag = "lesson_feedback"
            if (celebrate) {
                val mascot = GiraffeMascotView(requireContext()).apply { tag = "lesson_feedback_mascot" }
                addView(mascot, 0, LinearLayout.LayoutParams(-1, requireContext().dp(72)))
                if (lastAnnouncement != announcement) mascot.play(GiraffeMascotView.Pose.WAVE, 320L)
            }
            if (lastAnnouncement != announcement) {
                lastAnnouncement = announcement
                val currentScroll = scroll
                val currentBody = body
                doOnLayout {
                    currentScroll.post {
                        if (view != null && isAttachedToWindow && currentScroll === scroll && currentBody === body && lastAnnouncement == announcement) {
                            val area = Rect(0, 0, width, height)
                            currentBody.offsetDescendantRectToMyCoords(this, area)
                            currentScroll.scrollTo(0, (currentBody.top + area.top - context.dp(8)).coerceAtLeast(0))
                            announceForAccessibility("$title. $explanation")
                        }
                    }
                }
            }
        }
    }

    private fun summary(module: InteractiveModule, run: LessonRun) {
        body.tag = "lesson_summary"
        val lesson = module.lessons.first { it.id == run.lessonId }
        val checks = lesson.steps.filter { it.kind == LessonStepKind.CHECK }
        val firstTry = checks.count { step -> run.answers[requireNotNull(step.questionId)]?.let { it.status == LessonAnswerStatus.CORRECT && it.wrongCount == 0 && !it.hintUsed && !it.assisted } == true }
        val helped = checks.count { step -> run.answers[requireNotNull(step.questionId)]?.let { it.hintUsed || it.assisted } == true }
        val revisit = checks.filter { step -> run.answers[requireNotNull(step.questionId)]?.let { it.wrongCount > 0 || it.hintUsed || it.assisted } == true }
        body.label(if (lesson.reviewOf == null) "Lesson completed" else "Review completed", 28f)
        body.label(lesson.title, 20f)
        body.label("$firstTry of ${checks.size} correct first try", 16f)
        val details = requireContext().column(0).apply {
            tag = "lesson_result_details_body"
            label("${lesson.steps.size} steps completed · Help used: $helped of ${checks.size}", 14f)
            if (revisit.isNotEmpty()) { label("Worth another look", 18f); revisit.forEach { label(it.title) } }
            label("Practice only · doesn’t affect your grades.", 14f)
        }
        body.studyDisclosure("Practice details", details, "lesson_result_details")
        val originalId = lesson.reviewOf ?: lesson.id
        reviewSchedule(module, originalId)?.let { schedule ->
            body.label(reviewDueText(schedule), 16f).tag = "lesson_review_due_$originalId"
            if (lesson.reviewOf == null) {
                val savedReview = model.runs[schedule.reviewLessonId]
                quiet(body.action(if (savedReview != null && !savedReview.completed) "Continue review" else if (schedule.dueAt <= now()) "Start review" else "Review early") {
                    guard { model.open(repository, schedule.reviewLessonId, again = savedReview?.completed == true) }
                }).tag = "lesson_review_start_$originalId"
            }
        }
        status = body.label(if (model.confirmedAt != null) "Saved" else "Saved on this phone", 14f).apply { tag = "lesson_sync" }
        if (model.confirmedAt == null) {
            primary("Save to account") { guard { model.submit(repository) } }.tag = "lesson_submit"
        } else {
            val next = module.lessons.firstOrNull { it.reviewOf == null && model.runs[it.id]?.completed != true }
            val dueReview = module.lessons.filter { it.reviewOf == null }.mapNotNull { reviewSchedule(module, it.id) }
                .filter { it.dueAt <= now() }.minByOrNull { it.dueAt }
            when {
                next != null -> primary("Next lesson") { guard { model.open(repository, next.id) } }.tag = "lesson_next"
                dueReview != null -> primary("Open review") {
                    guard { model.open(repository, dueReview.reviewLessonId, again = model.runs[dueReview.reviewLessonId]?.completed == true) }
                }.tag = "lesson_next"
                else -> primary("Back to lesson path") { guard { model.path(repository) } }.tag = "lesson_next"
            }
        }
        if (model.pendingCount > 0 && (model.pendingCount > 1 || model.confirmedAt != null)) {
            body.action("Save results to account (${model.pendingCount})") { guard { model.submit(repository, all = true) } }.tag = "lesson_sync_all"
        }
        val options = requireContext().column(0)
        quiet(options.action("Practise again") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Start a new practice run?")
                .setMessage("Your completed result is kept. A new run starts this lesson from the beginning.")
                .setNegativeButton("Keep summary", null).setPositiveButton("Start again") { _, _ -> guard { model.open(repository, run.lessonId, again = true) } }.show()
        })
        quiet(options.action("Lesson path") { guard { model.path(repository) } })
        quiet(options.action("Find related readings →") { relatedReadings(module) }).tag = "lesson_related_readings"
        lessonTools(options, module)
        body.studyDisclosure("More options", options, "lesson_summary_options")
    }

    private fun quiet(button: MaterialButton) = button.apply {
        backgroundTintList = ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(context.tint(R.color.sp_ink)); strokeWidth = 0
        layoutParams.width = -1
    }

    private fun lessonTools(content: LinearLayout, module: InteractiveModule) {
        quiet(content.action("Glossary & PDF") {
            val tools = requireContext().column(16)
            val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Glossary & PDF")
                .setView(requireContext().scrolling(tools)).setPositiveButton("Close", null).create()
            tools.action("Glossary") { dialog.dismiss(); glossary(module) }.quietAction()
            model.pdfAssets.forEach { asset ->
                tools.action("Open PDF · ${asset.id}") {
                    dialog.dismiss()
                    model.leaveAfterSaved(repository) { (requireActivity() as MainActivity).openDetails(OfflinePdfFragment.create(packageKey(), asset.id)) }
                }.quietAction()
            }
            dialog.show()
        }).tag = "lesson_glossary_pdf"
        model.pdfAssets.forEach { asset ->
            quiet(content.action("Open companion PDF") {
                model.leaveAfterSaved(repository) { (requireActivity() as MainActivity).openDetails(OfflinePdfFragment.create(packageKey(), asset.id)) }
            }).tag = "lesson_pdf_${asset.id}"
        }
        quiet(content.action("Lesson tools & source") {
            val options = listOf("Source & review", "Glossary", "Find related readings")
            MaterialAlertDialogBuilder(requireContext()).setTitle("Lesson tools")
                .setItems(options.toTypedArray()) { _, index ->
                    when (index) { 0 -> details(module); 1 -> glossary(module); else -> relatedReadings(module) }
                }.setNegativeButton("Close", null).show()
        }).tag = "lesson_tools"
    }

    private fun primary(title: String, action: () -> Unit): MaterialButton = actions.action(title, action).apply {
        tag = "lesson_primary"; minHeight = requireContext().dp(56)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = requireContext().dp(4) }
    }

    private fun transition(change: (InteractiveModule, LessonRun) -> LessonRun) { guard { model.transition(repository, change) } }
    private fun guard(action: () -> Unit) {
        if (model.busy || model.leaving) return
        action()
        if (model.busy || model.leaving) disableForSaving()
    }
    private fun disableForSaving() {
        actions.visibility = View.VISIBLE
        fun disable(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                if (child is ViewGroup) disable(child)
                else if (child is MaterialButton || child is RadioButton || child is android.widget.EditText) child.isEnabled = false
            }
        }
        disable(body); disable(actions)
        val text = if (model.leaving) "Finishing save…" else model.operationLabel
        if (status == null) status = actions.label(text, 14f) else status?.text = text
    }

    private fun relatedReadings(module: InteractiveModule) {
        val uid = repository.currentSession()?.uid ?: return
        guard {
            model.leaveAfterSaved(repository) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val definitions = withTimeout(12_000) { repository.readingContextDefinitions().first() }.filter {
                            it.kind == ReadingContextKind.MODULE_CONCEPT && it.review.status == "APPROVED" &&
                                it.curriculumVersion == module.summary.curriculumVersion && it.moduleId == module.summary.moduleId &&
                                it.moduleVersion == module.summary.version && it.packageSha256 == module.summary.packageSha256
                        }
                        if (!isAdded || view == null || repository.currentSession()?.uid != uid || model.module?.summary != module.summary) return@launch
                        if (definitions.isEmpty()) {
                            MaterialAlertDialogBuilder(requireContext()).setTitle("Related readings unavailable")
                                .setMessage("No related readings yet. Search in Study.")
                                .setPositiveButton("Close", null).show()
                        } else MaterialAlertDialogBuilder(requireContext()).setTitle("Choose a module concept")
                            .setItems(definitions.map { it.query }.toTypedArray()) { _, index ->
                                (requireActivity() as MainActivity).openDetails(StudyFragment.forContext(definitions[index].contextId))
                            }.setNegativeButton("Cancel", null).show()
                    } catch (error: TimeoutCancellationException) { relatedReadingsUnavailable(error) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { relatedReadingsUnavailable(error) }
                }
            }
        }
    }

    private fun relatedReadingsUnavailable(error: Exception) {
        if (isAdded && view != null) MaterialAlertDialogBuilder(requireContext()).setTitle("Related readings unavailable")
            .setMessage(failureText(error)).setPositiveButton("Close", null).show()
    }

    private fun glossary(module: InteractiveModule) {
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Glossary")
        val content = dialog.context.column(20)
        module.glossary.forEach { (term, definition) -> content.label(term, 18f); content.label(definition).setTextIsSelectable(true) }
        dialog.setView(dialog.context.scrolling(content)).setPositiveButton("Close", null).show()
    }

    private fun details(module: InteractiveModule) {
        val row = module.summary
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Lesson source and review")
        val content = dialog.context.column(20)
        content.label("${row.title} · Version ${row.version}", 20f)
        content.label("${row.source.getValue("title")}\nAuthor: ${row.source.getValue("author")}\nSource version: ${row.source.getValue("version")}\nReference: ${row.source.getValue("reference")}\nLocation: ${row.source.getValue("locator")}\n\nReviewer: ${row.review.getValue("reviewer")}\nReviewed: ${row.review.getValue("reviewedOn")}\nApproval: ${row.review.getValue("approvalReference")}\nPermission: ${row.review.getValue("permissionReference")}").setTextIsSelectable(true)
        dialog.setView(dialog.context.scrolling(content)).setPositiveButton("Close", null).show()
    }

    private fun solution(question: LessonQuestion): String = when (question.format) {
        LessonQuestionFormat.SELECTED_RESPONSE, LessonQuestionFormat.ORDERING -> question.answer.joinToString(" → ") { id -> question.options.first { it.id == id }.text }
        LessonQuestionFormat.BOUNDED_RESPONSE -> if (question.responseType == "numeric") "${question.target} ${question.unit}".trim() else question.acceptedAnswers.firstOrNull().orEmpty()
    }
    private fun reviewSchedule(module: InteractiveModule, originalId: String): LessonReviewSchedule? =
        if (module.lessons.none { it.reviewOf == originalId }) null else LessonReviewPolicy.schedule(module, originalId, model.history)
    private fun reviewDueText(schedule: LessonReviewSchedule): String =
        if (schedule.dueAt <= now()) "Review suggested now"
        else "Review suggested ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(schedule.dueAt))}"
    private fun now() = System.currentTimeMillis()
    private fun packageKey() = TextPackageKey(requireArguments().getString("curriculum")!!, requireArguments().getString("module")!!, requireArguments().getInt("version"))
    override fun onDestroyView() { status = null; super.onDestroyView() }
    companion object {
        fun forPackage(key: TextPackageKey, lessonId: String? = null) = InteractiveLessonFragment().apply {
            arguments = Bundle().apply { putString("curriculum", key.curriculumVersion); putString("module", key.moduleId); putInt("version", key.version); putString("lessonId", lessonId) }
        }
        fun forReview(key: TextPackageKey, reviewLessonId: String) = forPackage(key).apply {
            requireArguments().putString("reviewLessonId", reviewLessonId)
        }
    }
}
