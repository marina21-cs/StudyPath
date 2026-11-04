package com.studypath.app.ui.planner

import android.os.Bundle
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.studypath.app.R
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.*
import com.studypath.app.domain.*
import com.studypath.app.ui.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode

/** Native planner screen. All arithmetic, overlay and fingerprinting live outside this Fragment. */
class PlannerFragment : Fragment() {
    private val repository get() = (requireActivity().application as StudyPathApplication).graph.repository
    private var content: LinearLayout? = null
    private var coursePicker: Spinner? = null
    private var loading: TextView? = null
    private var syncNote: TextView? = null
    private var retry: MaterialButton? = null
    private var collector: Job? = null
    private var policyClock: Job? = null
    private var latest: PlannerData? = null
    private var pickerIds = emptyList<String>()
    private var selectedCourseId: String? = null
    private var targetText = "75"
    private var plannerMode = "scores"
    private var roadmapAnchor: String? = null
    private var selectedScenarioId: String? = null
    private var sessionUid: String? = null
    private var activeDialog: AlertDialog? = null
    private val expandedSections = mutableSetOf<String>()
    private val decimalInput = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedCourseId = savedInstanceState?.getString("courseId")
        targetText = savedInstanceState?.getString("target") ?: "75"
        plannerMode = savedInstanceState?.getString("plannerMode") ?: "scores"
        selectedScenarioId = savedInstanceState?.getString("scenarioId")
        expandedSections.addAll(savedInstanceState?.getStringArrayList("expandedSections").orEmpty())
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val page = requireContext().column()
        page.label("A little clarity.", 30f)
        loading = page.label("Loading courses…", 14f)
        coursePicker = Spinner(requireContext()).also { picker ->
            picker.minimumHeight = requireContext().dp(48)
            picker.contentDescription = "Choose a course for grade planning"
            page.addView(picker, LinearLayout.LayoutParams(-1, -2))
            picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val next = pickerIds.getOrNull(position) ?: return
                    if (selectedCourseId != next) { selectedCourseId = next; render() }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        syncNote = page.label("", 14f).apply { isVisible = false }
        content = requireContext().column(0).also { page.addView(it) }
        retry = page.action("Try again") { observe() }.apply { isVisible = false }
        return requireContext().scrolling(page)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        sessionUid = repository.currentSession()?.uid
        observe()
    }

    private fun observe() {
        collector?.cancel()
        val uid = sessionUid ?: run { loading?.text = "Sign in to open your private planner."; return }
        loading?.text = "Loading planner…"
        loading?.isVisible = true
        retry?.isVisible = false
        collector = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    val records = combine(repository.courses(), repository.scores(), repository.courseAttempts(), repository.scenarios()) {
                            courses, scores, attempts, scenarios -> PlannerData(courses, scores, attempts, scenarios)
                    }
                    val policy = combine(repository.learnerPreferences(), repository.learnerPreferenceOptions(), repository.academicPolicies()) {
                            preferences, options, policies -> PlannerContext(preferences, options, policies)
                    }
                    combine(records, policy) { data, context -> data.copy(context = context) }.collect { data ->
                        if (!sessionActive(uid)) return@collect
                        // A preference/policy change invalidates dialogs opened under the previous authority.
                        if (latest?.context != data.context) { activeDialog?.dismiss(); activeDialog = null }
                        latest = data
                        loading?.isVisible = false
                        retry?.isVisible = false
                        coursePicker?.isVisible = data.courses.isNotEmpty()
                        updateCoursePicker(data.courses)
                        render()
                        schedulePolicyBoundary()
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    if (sessionActive(uid)) {
                        loading?.text = "Could not refresh. ${failureText(error)}"
                        loading?.isVisible = true
                        retry?.isVisible = true
                    }
                }
            }
        }
    }

    private fun updateCoursePicker(courses: List<CatalogCourse>) {
        val ids = courses.map { it.id }
        if (ids == pickerIds) return
        pickerIds = ids
        if (selectedCourseId !in ids) selectedCourseId = ids.firstOrNull()
        coursePicker?.adapter = object : ArrayAdapter<String>(requireContext(),
            android.R.layout.simple_spinner_dropdown_item, courses.map { it.title }) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    textSize = 18f; maxLines = 2
                    typeface = ResourcesCompat.getFont(context, R.font.nunito_bold)
                    setTextColor(context.tint(R.color.sp_ink))
                }
        }
        coursePicker?.setSelection(ids.indexOf(selectedCourseId).coerceAtLeast(0))
    }

    private fun render() {
        val data = latest ?: return
        val body = content ?: return
        if (!sessionActive()) { body.removeAllViews(); return }
        body.removeAllViews()
        val course = data.courses.firstOrNull { it.id == selectedCourseId } ?: run {
            body.card("No courses yet", "Choose your study preferences to check for available courses.").tag = "planner_policy_unavailable"
            body.action("Choose study preferences") { (requireActivity() as MainActivity).openDetails(LearnerPreferencesFragment()) }
            body.action("Browse readings") { (requireActivity() as MainActivity).goToStudy() }.quietAction()
            return
        }
        modePicker(body)
        if (plannerMode == "roadmap") {
            val map = requireContext().column(0)
            val gwa = requireContext().column(0)
            val attempts = requireContext().column(0)
            body.addView(map); body.addView(gwa); body.addView(attempts)
            renderPrerequisites(map, course, data)
            renderGwa(gwa, data)
            renderAttempts(attempts, course, data)
            val anchor = when (roadmapAnchor) { "GWA" -> gwa; "Course attempts" -> attempts; else -> map }
            if (roadmapAnchor != null) anchor.post { anchor.requestRectangleOnScreen(android.graphics.Rect(0, 0, anchor.width, requireContext().dp(120)), true) }
            roadmapAnchor = null
            return
        }
        val access = access(data, course, AcademicCapability.SCORES)
        val evaluation = try { PlannerAdapter.evaluate(course, data.scores, access) } catch (error: IllegalArgumentException) {
            body.card("Scores need review", error.message ?: "This plan cannot be calculated safely.")
            data.scores.filter { it.courseId == course.id && it.planVersion == course.gradePlan?.version && access.matches(it.policyBinding) }.forEach { record ->
                val category = course.gradePlan?.categories?.singleOrNull { it.id == record.categoryId }
                val planned = category?.assessments?.singleOrNull { it.id == record.assessmentId }
                val status = runCatching { AssessmentStatus.valueOf(record.status) }.getOrNull()
                if (category != null && planned != null && status != null) {
                    body.action("Edit ${planned.title} score") { editScore(course, PlannedAssessmentState(category.id, planned.title,
                        Assessment(planned.id, planned.possiblePoints, record.earnedPoints, status, planned.explicitWeight), record)) }
                }
                body.action("Delete conflicting ${record.assessmentId} score") { confirmDeleteScore(record) }
            }
            renderPrerequisites(body, course, data)
            return
        }
        val plan = course.gradePlan
        if (!access.allowed || plan?.verified != true) {
            val unavailable = body.card("Planning unavailable", "This course needs an approved grading policy.")
            unavailable.tag = "planner_policy_unavailable"
            val details = requireContext().column(0).apply {
                label("Your saved records are kept.", 14f)
                label(evaluation.warnings.joinToString("\n"), 14f)
            }
            unavailable.studyDisclosure("Policy details", details, "planner_policy_details")
            body.action("Change learning preferences") { (requireActivity() as MainActivity).openDetails(LearnerPreferencesFragment()) }.quietAction()
            val prerequisites = access(data, course, AcademicCapability.PREREQUISITES)
            if (prerequisites.allowed) renderPrerequisites(body, course, data)
            renderAttempts(body, course, data)
            if (access(data, course, AcademicCapability.GWA).allowed) renderGwa(body, data)
            body.action("Refresh") { observe() }
            return
        }
        if (plannerMode == "what-if") {
            renderWhatIf(body, course, evaluation, data)
        } else {
        val summary = evaluation.summary
        val grade = body.card("Graded-work average", "")
        grade.label(summary?.gradedWorkPercent?.let(::percent) ?: "—", 42f).apply {
            textSize = 42f; tag = "planner_average"
            contentDescription = summary?.gradedWorkPercent?.let { "Graded-work average ${percent(it)}" } ?: "No graded-work average available"
        }
        grade.label(if (summary?.gradedWorkPercent == null) "No scored work yet. Ungraded work is excluded."
            else "Based on recorded work. This is not a final or official grade.", 14f)
        grade.label("Self-reported · advisory", 12f)
        if (evaluation.assessments.isNotEmpty()) {
            section(body, "Scores", primary = true,
                label = if (summary?.gradedWorkPercent == null) "Add scores" else "View recorded scores") { scores ->
            evaluation.assessments.forEach { row ->
                val value = row.assessment
                val description = when (value.status) {
                    AssessmentStatus.SCORED -> "${number(value.earnedPoints!!)} / ${number(value.possiblePoints)} points"
                    AssessmentStatus.UNGRADED -> "Ungraded · ${number(value.possiblePoints)} points"
                    AssessmentStatus.EXCUSED -> "Excused"
                    AssessmentStatus.MISSING -> "Missing · grade treatment needs policy review"
                }
                val card = scores.card(row.title, description)
                card.action(if (row.record == null) "Enter score" else "Edit score") { editScore(course, row) }
                row.record?.let { record -> card.action("Delete score") { confirmDeleteScore(record) } }
                if (value.status in setOf(AssessmentStatus.UNGRADED, AssessmentStatus.MISSING) ||
                    (value.earnedPoints != null && value.earnedPoints < value.possiblePoints)) {
                    card.action("Find related readings →") { relatedReadings(course, row.categoryId) }.apply {
                        tag = "planner_category_readings_${row.categoryId}_${value.id}"; quietAction()
                    }
                }
            }
            }
        }
        }
        if (plannerMode == "scores") {
            body.label("Review a topic from this course. Your scores stay private.", 14f)
            body.action("Find readings for a category") { relatedReadings(course) }.tag = "planner_related_readings"
        }
        section(body, "More options") { options ->
        listOf("Course attempts", "GWA", "Prerequisites").forEach { title ->
            options.action(title) { plannerMode = "roadmap"; roadmapAnchor = title; render() }.quietAction()
        }
        if (plannerMode != "scores") {
            options.label("Choose a reviewed course category for supplemental reading. Your scores stay private.", 14f)
            options.action("Find readings for a category") { relatedReadings(course) }.tag = "planner_related_readings"
        }
        options.action("Refresh") { observe() }
        options.action("Plan & policy details") {
            showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Reviewed plan")
                .setMessage("Self-reported scores and outcomes. These estimates do not certify official grades or enrollment eligibility.\n\n${plan?.label ?: "No complete assessment plan"}\nVersion: ${plan?.version ?: "unknown"}\n\n${evaluation.summary?.let { summary -> if (summary.minimumFinalPercent != null && summary.maximumFinalPercent != null) "Final range: ${percent(summary.minimumFinalPercent)}–${percent(summary.maximumFinalPercent)}${if (summary.complete) " · complete" else " · remaining work is ungraded"}" else "Final range unavailable · incomplete or unsupported plan" } ?: "Grade unavailable while the assessment plan needs review."}\n\n${evaluation.warnings.joinToString("\n")}\n\nUngraded is different from zero. Practice results stay separate from course grades. Calculations use raw percentages without an institutional conversion.")
                .setPositiveButton("Close", null).create())
        }.apply {
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(context.tint(R.color.sp_muted))
        }
        }
    }

    private fun modePicker(body: LinearLayout) {
        val modes = LinearLayout(requireContext()).apply {
            orientation = if (resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        listOf("scores" to "My scores", "what-if" to "What-if", "roadmap" to "GWA & map").forEach { (mode, title) ->
            val button = modes.action(title) { plannerMode = mode; render() }.apply {
                tag = "planner_mode_$mode"
                isSelected = plannerMode == mode
                backgroundTintList = ColorStateList.valueOf(context.tint(if (isSelected) R.color.sp_ink else R.color.sp_card))
                setTextColor(context.tint(if (isSelected) android.R.color.white else R.color.sp_ink))
                contentDescription = "$title, ${if (isSelected) "selected" else "not selected"}"
                layoutParams = (if (modes.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2)
                    else LinearLayout.LayoutParams(0, -2, 1f)).apply {
                    marginEnd = context.dp(if (mode != "roadmap") 4 else 0)
                    marginStart = context.dp(if (mode != "scores") 4 else 0)
                    bottomMargin = context.dp(12)
                }
            }
        }
        body.addView(modes)
    }

    private fun renderWhatIf(body: LinearLayout, course: CatalogCourse, evaluation: PlannerEvaluation, data: PlannerData) {
        val permission = access(data, course, AcademicCapability.SCENARIOS)
        if (!permission.allowed) {
            body.card("What-if unavailable", permission.reason).tag = "planner_scenario_unavailable"
            return
        }
        val saved = data.scenarios.filter { it.courseId == course.id }.sortedByDescending { it.updatedAt ?: 0L }
        val selected = saved.firstOrNull { it.id == selectedScenarioId } ?: saved.firstOrNull()
        selectedScenarioId = selected?.id
        val current = selected?.takeIf { AcademicScenarios.isCurrent(it, evaluation.fingerprint, permission) }
        val preview = current?.let { scenario -> runCatching {
            PlannerAdapter.evaluate(course, AcademicScenarios.overlay(course, data.scores, scenario.overrides,
                access(data, course, AcademicCapability.SCORES), permission), access(data, course, AcademicCapability.SCORES))
        }.getOrNull() }
        body.label("HYPOTHETICAL · NOT RECORDED", 14f).tag = "planner_scenario_badge"
        val card = body.card("Scenario average", "")
        card.label(preview?.summary?.gradedWorkPercent?.let(::percent) ?: "—", 42f).apply {
            textSize = 42f; tag = "planner_scenario_average"
        }
        card.label(selected?.title ?: "No scenario selected", 18f)
        card.label(when {
            selected == null -> "Create a what-if to preview a change. Your recorded scores stay unchanged."
            current == null -> "Needs review (Base changed) · scores, plan or policy changed."
            preview?.summary?.gradedWorkPercent == null -> "A scenario average is unavailable for these inputs. Review the assessment states."
            else -> "Hypothetical · not recorded. Your actual scores stay unchanged."
        }, 14f)
        body.action("Create a what-if") { editScenario(course, evaluation, null) }.apply { layoutParams.width = -1 }
        section(body, "Saved scenarios") { renderScenarios(it, course, evaluation, data.scenarios) }
        renderTarget(body.card("Target calculator", "Hypothetical · based on the reviewed remaining plan"), course, evaluation)
    }

    private fun section(body: LinearLayout, title: String, primary: Boolean = false,
                        label: String = title,
                        build: (LinearLayout) -> Unit) {
        val panel = requireContext().column(0).apply { isVisible = title in expandedSections }
        val toggle = body.action(label) {}.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) }
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            iconGravity = MaterialButton.ICON_GRAVITY_END
            iconSize = context.dp(20)
            if (!primary) {
                backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                setTextColor(context.tint(R.color.sp_ink))
                strokeWidth = context.dp(1)
                strokeColor = ColorStateList.valueOf(context.tint(R.color.sp_outline))
                iconTint = ColorStateList.valueOf(context.tint(R.color.sp_ink))
            }
        }
        fun update() {
            toggle.icon = ContextCompat.getDrawable(requireContext(), if (panel.isVisible)
                R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            toggle.contentDescription = "$title, ${if (panel.isVisible) "collapse" else "expand"}"
        }
        toggle.setOnClickListener {
            panel.isVisible = !panel.isVisible
            if (panel.isVisible) expandedSections.add(title) else expandedSections.remove(title)
            update()
        }
        update()
        build(panel)
        body.addView(panel)
    }

    private fun renderTarget(body: LinearLayout, course: CatalogCourse, evaluation: PlannerEvaluation) {
        val summary = evaluation.summary ?: return
        body.label("Set a target", 20f)
        body.label("Hypothetical · your scores stay unchanged", 14f)
        val input = body.field("Target percentage", decimalInput).apply {
            setText(targetText)
            doAfterTextChanged { targetText = it?.toString().orEmpty() }
        }
        val output = body.label("See what you need on remaining work.", 14f)
        fun projection(): TargetProjection? {
            val allowed = latest?.let { access(it, course, AcademicCapability.SCENARIOS).allowed } == true
            if (!allowed) { render(); return null }
            val target = input.text?.toString()?.trim()?.toDoubleOrNull()
            if (target == null || !target.isFinite() || target !in 0.0..100.0) {
                input.error = "Enter a number from 0 to 100"
                return null
            }
            return GradeEngine.target(summary, target).also {
                output.text = "${projectionText(it)}\nRequired average on remaining work: ${it.requiredRemainingPercent?.let(::requiredPercent) ?: "Unavailable / no remaining work"}\nBest possible final percentage: ${summary.maximumFinalPercent?.let(::percent) ?: "Unavailable without a complete plan"}"
            }
        }
        body.action("Calculate") { projection() }
        body.action("Save target") {
            val result = projection() ?: return@action
            val target = input.text.toString().trim().toDouble()
            val id = PlannerAdapter.scoreId(course.id, evaluation.fingerprint, "target", target.toString())
                .replaceFirst("score_", "target_")
            val scenarioAccess = latest?.let { access(it, course, AcademicCapability.SCENARIOS) } ?: return@action
            if (!scenarioAccess.allowed) { render(); return@action }
            val scenario = GradeScenario(id, course.id, "Target ${percent(target)}", target,
                evaluation.fingerprint, result.requiredRemainingPercent?.takeIf { it in 0.0..100.0 }, policyBinding = binding(scenarioAccess))
            write("Target scenario") { repository.saveScenario(scenario) }
        }
    }

    private fun renderScenarios(body: LinearLayout, course: CatalogCourse, evaluation: PlannerEvaluation,
                                scenarios: List<GradeScenario>) {
        body.label("Saved scenarios", 20f)
        val data = latest ?: return
        val scenarioAccess = access(data, course, AcademicCapability.SCENARIOS)
        val scoreAccess = access(data, course, AcademicCapability.SCORES)
        val selected = scenarios.filter { it.courseId == course.id }.sortedByDescending { it.updatedAt ?: 0L }
        if (selected.isEmpty()) body.label("No saved scenarios for this course yet.", 14f)
        selected.forEach { scenario ->
            val stale = !AcademicScenarios.isCurrent(scenario, evaluation.fingerprint, scenarioAccess)
            val description = if (stale) "Needs review (Base changed) · review and explicitly rebase before replaying."
            else runCatching {
                val hypothetical = PlannerAdapter.evaluate(course, AcademicScenarios.overlay(course, data.scores,
                    scenario.overrides, scoreAccess, scenarioAccess), scoreAccess)
                hypothetical.summary?.let { "Hypothetical average: ${it.gradedWorkPercent?.let(::percent) ?: "no scores"}\n${projectionText(GradeEngine.target(it, scenario.targetPercent))}" }
                    ?: "Calculation unavailable: missing work needs a reviewed treatment."
            }.getOrElse { "Saved overrides need review: ${failureText(it)}" }
            val card = body.card(scenario.title, "Hypothetical target: ${percent(scenario.targetPercent)}\n$description")
            if (!stale) card.action("View scenario") { selectedScenarioId = scenario.id; render() }
            if (scenarioAccess.allowed) card.action(if (stale) "Review and rebase scenario" else "Restore and edit scenario") {
                editScenario(course, evaluation, scenario)
            }
            card.action("Delete scenario") {
                showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Delete this scenario?")
                    .setMessage("Your actual scores and attempts remain unchanged.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                        write("Scenario deletion") { repository.deleteScenario(scenario.id) }
                    }.create())
            }
        }
    }

    private fun editScenario(course: CatalogCourse, evaluation: PlannerEvaluation, saved: GradeScenario?, rebaseApproved: Boolean = false) {
        val data = latest ?: return
        val permission = access(data, course, AcademicCapability.SCENARIOS)
        if (!permission.allowed) { render(); return }
        if (saved != null && !AcademicScenarios.isCurrent(saved, evaluation.fingerprint, permission) && !rebaseApproved) {
            showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Rebase this scenario?")
                .setMessage("The actual scores, plan or policy changed. Review this scenario against current records. Removed assessments will be omitted from the editable draft. " +
                    if (saved.policyBinding != binding(permission)) "Saving creates a new scenario under the current policy and keeps the original scenario. Actual records stay unchanged."
                    else "The saved scenario changes only when you save; actual records stay unchanged.")
                .setNegativeButton("Cancel", null).setPositiveButton("Review current base") { _, _ ->
                    editScenario(course, evaluation, AcademicScenarios.rebase(saved, evaluation.fingerprint, permission), true)
                }.create())
            return
        }
        val form = requireContext().column()
        form.label("Hypothetical only · actual scores stay unchanged", 14f)
        val title = form.field("Scenario name").apply { setText(saved?.title ?: "What if") }
        val target = form.field("Target percentage", decimalInput).apply { setText(saved?.targetPercent?.let(::number) ?: targetText) }
        form.label("Assessment overrides", 20f)
        form.label("Enter a hypothetical percentage from 0 to 100. Leave a field blank to keep its actual or ungraded state.", 14f)
        val fields = course.gradePlan?.categories.orEmpty().flatMap { category -> category.assessments.map { planned ->
            val key = AcademicScenarios.overrideKey(category.id, planned.id)
            key to form.field("${planned.title} · hypothetical %", decimalInput).apply {
                setText(saved?.overrides?.get(key)?.let(::number).orEmpty())
            }
        } }.toMap()
        val result = form.label("Preview before saving this scenario.", 14f)
        var previewTarget = 0.0
        var previewOverrides = emptyMap<String, Double>()
        var previewEvaluation: PlannerEvaluation? = null
        fun preview(): Boolean {
            val current = latest ?: return false
            val scorePermission = access(current, course, AcademicCapability.SCORES)
            val scenarioPermission = access(current, course, AcademicCapability.SCENARIOS)
            if (!scorePermission.allowed || !scenarioPermission.allowed) { result.text = scenarioPermission.reason; return false }
            val actual = runCatching { PlannerAdapter.evaluate(course, current.scores, scorePermission) }.getOrElse { result.text = failureText(it); return false }
            if (actual.fingerprint != evaluation.fingerprint) { result.text = "Actual records changed while this draft was open. Close and reopen to review the new base."; return false }
            val desired = target.text.toString().trim().toDoubleOrNull()
            if (desired == null || !desired.isFinite() || desired !in 0.0..100.0) { target.error = "Enter a percentage from 0 to 100"; return false }
            val overrides = mutableMapOf<String, Double>()
            fields.forEach { (key, field) ->
                val text = field.text.toString().trim()
                if (text.isNotEmpty()) {
                    val value = text.toDoubleOrNull()
                    if (value == null || !value.isFinite() || value !in 0.0..100.0) { field.error = "Enter 0 to 100, or leave blank"; return false }
                    overrides[key] = value
                }
            }
            return try {
                val hypothetical = PlannerAdapter.evaluate(course, AcademicScenarios.overlay(course, current.scores, overrides,
                    scorePermission, scenarioPermission), scorePermission)
                result.text = hypothetical.summary?.let { "Scenario average: ${it.gradedWorkPercent?.let(::percent) ?: "no scores"}\n${projectionText(GradeEngine.target(it, desired))}" }
                    ?: hypothetical.warnings.joinToString("\n")
                previewTarget = desired; previewOverrides = overrides.toMap(); previewEvaluation = hypothetical
                true
            } catch (error: IllegalArgumentException) { result.text = failureText(error); false }
        }
        form.action("Preview scenario") { preview() }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(if (saved == null) "Create scenario" else "Restore scenario draft")
            .setView(requireContext().scrolling(form)).setNegativeButton("Cancel", null).setPositiveButton("Save scenario", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (title.text.toString().trim().isBlank() || title.text.toString().trim().length > 80) { title.error = "Enter a name of up to 80 characters"; return@setOnClickListener }
                if (!preview()) return@setOnClickListener
                val currentAccess = latest?.let { access(it, course, AcademicCapability.SCENARIOS) } ?: return@setOnClickListener
                val projection = previewEvaluation?.summary?.let { GradeEngine.target(it, previewTarget) }
                val value = GradeScenario(saved?.id ?: FirebaseStudyRepository.newRecordId(), course.id, title.text.toString().trim(),
                    previewTarget, evaluation.fingerprint, projection?.requiredRemainingPercent?.takeIf { it in 0.0..100.0 },
                    previewOverrides, policyBinding = binding(currentAccess))
                dialog.dismiss()
                write("Scenario") { repository.saveScenario(value) }
            }
        }
        showDialog(dialog)
    }

    private fun renderAttempts(body: LinearLayout, course: CatalogCourse, data: PlannerData, canCreate: Boolean = true) {
        body.label("Course attempts", 20f)
        body.label("Keep each term or retake separate.", 14f)
        val records = data.attempts.filter { it.courseId == course.id }.sortedByDescending { it.updatedAt ?: 0L }
        if (records.isEmpty()) body.label("No attempts recorded for this course.", 14f)
        val permission = access(data, course, AcademicCapability.SCORES)
        val otherPermission = access(data, course, AcademicCapability.PREREQUISITES)
        val gwaPermission = access(data, course, AcademicCapability.GWA)
        val mayEdit = canCreate && (permission.allowed || otherPermission.allowed || gwaPermission.allowed)
        if (mayEdit) body.action("Add course attempt") { editAttempt(course, null) }
        records.forEach { attempt ->
            val current = permission.matchesAttempt(attempt.policyBinding, course.id) || otherPermission.matchesAttempt(attempt.policyBinding, course.id) || gwaPermission.matchesAttempt(attempt.policyBinding, course.id)
            val description = "${attempt.status.replace('_', ' ')} · ${number(attempt.units)} units\n" +
                "${attempt.grade?.let { "Numeric grade ${number(it)}" } ?: "No numeric grade"} · ${attempt.scaleId ?: "scale not recorded"}\n" +
                "${if (attempt.includeInGwa && current) "Included in GWA" else if (attempt.includeInGwa) "GWA inclusion needs policy review" else "Excluded from current GWA"}" +
                if (!current) "\nFrom an older course plan." else ""
            val card = body.card(attempt.term, description)
            if (mayEdit) card.action(if (current) "Edit attempt" else "Review as new attempt") { editAttempt(course, attempt, copyAsNew = !current) }
            card.action("Delete attempt") {
                showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Delete this attempt?")
                    .setMessage("Delete ${attempt.term} for ${course.title}? Other terms and actual assessment scores remain unchanged.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ -> write("Course attempt deletion") { repository.deleteCourseAttempt(attempt.id) } }.create())
            }
        }
    }

    private fun editAttempt(course: CatalogCourse, previous: CourseAttempt?, copyAsNew: Boolean = false) {
        val data = latest ?: return
        val scorePermission = access(data, course, AcademicCapability.SCORES)
        val prerequisitePermission = access(data, course, AcademicCapability.PREREQUISITES)
        val gwaPermission = access(data, course, AcademicCapability.GWA)
        if (!scorePermission.allowed && !prerequisitePermission.allowed && !gwaPermission.allowed) { render(); return }
        val form = requireContext().column()
        if (copyAsNew) form.label("This creates a reviewed new attempt. The retained earlier policy record stays unchanged.", 14f)
        val term = form.field("Term or school year").apply { setText(previous?.term.orEmpty()) }
        val statuses = listOf("COMPLETED", "IN_PROGRESS", "INCOMPLETE", "WITHDRAWN", "NOT_TAKEN")
        val status = Spinner(requireContext()).apply {
            minimumHeight = context.dp(48); contentDescription = "Course attempt status"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, statuses.map { it.replace('_', ' ') })
            setSelection(statuses.indexOf(previous?.status).coerceAtLeast(0))
        }
        form.addView(status)
        form.label("${number(course.units)} units from the current reviewed course catalog", 14f)
        val outcomes = listOf("UNKNOWN", "PASSED", "FAILED", "CREDITED_EQUIVALENT")
        val outcome = Spinner(requireContext()).apply {
            minimumHeight = context.dp(48); contentDescription = "Self-reported completion outcome"
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, outcomes.map { it.replace('_', ' ') })
            setSelection(outcomes.indexOf(previous?.completionStatus).coerceAtLeast(0)); isEnabled = prerequisitePermission.allowed
        }
        form.addView(outcome)
        if (!prerequisitePermission.allowed) form.label("Completion decisions are unavailable without a matching prerequisite policy.", 14f)
        val grade = form.field("Numeric grade (optional; not a passing decision)", decimalInput).apply { setText(previous?.grade?.let(::number).orEmpty()) }
        val final = form.field("Final percentage (optional)", decimalInput).apply { setText(previous?.finalPercent?.let(::number).orEmpty()) }
        val scale = form.field("Grade scale").apply { setText(previous?.scaleId ?: gwaPermission.gwaPolicy?.scaleId.orEmpty()) }
        val include = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
            text = "Explicitly include this attempt in GWA"; minHeight = context.dp(48)
            isChecked = previous?.includeInGwa == true && !copyAsNew; isEnabled = gwaPermission.allowed
        }
        form.addView(include)
        form.label(gwaPermission.gwaPolicy?.let { "Reviewed scale ${it.scaleId}: ${number(it.minimumGrade)} to ${number(it.maximumGrade)}. Repeat/exclusion policy: ${it.retakePolicyRef}; ${it.exclusionPolicyRef}." } ?: gwaPermission.reason, 14f)
        val feedback = form.label("Only a completed attempt may have a numeric grade or GWA inclusion. Other states preserve this term separately.", 14f)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(if (previous == null || copyAsNew) "Add course attempt" else "Edit course attempt")
            .setView(requireContext().scrolling(form)).setNegativeButton("Cancel", null).setPositiveButton("Save attempt", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val termText = term.text.toString().trim()
                if (termText.isBlank() || termText.length > 40) { term.error = "Enter a term of up to 40 characters"; return@setOnClickListener }
                val state = statuses[status.selectedItemPosition]
                val completed = state == "COMPLETED"
                fun numeric(field: com.google.android.material.textfield.TextInputEditText): Double? = field.text.toString().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
                val gradeText = grade.text.toString().trim(); val finalText = final.text.toString().trim()
                val numericGrade = numeric(grade); val finalPercent = numeric(final)
                if (completed && gradeText.isNotEmpty() && (numericGrade == null || !numericGrade.isFinite() || numericGrade !in 0.0..100.0)) { grade.error = "Enter a finite numeric grade from 0 to 100"; return@setOnClickListener }
                if (completed && finalText.isNotEmpty() && (finalPercent == null || !finalPercent.isFinite() || finalPercent !in 0.0..100.0)) { final.error = "Enter a percentage from 0 to 100"; return@setOnClickListener }
                val included = completed && include.isChecked
                val completion = if (completed && prerequisitePermission.allowed) outcomes[outcome.selectedItemPosition] else null
                val current = latest ?: return@setOnClickListener
                val permission = try {
                    AcademicAttemptEdits.permission(course.id, included, completion,
                        listOf(AcademicCapability.SCORES, AcademicCapability.PREREQUISITES, AcademicCapability.GWA).map { access(current, course, it) },
                        previous?.policyBinding.takeUnless { copyAsNew })
                } catch (error: IllegalArgumentException) { feedback.text = failureText(error); return@setOnClickListener }
                val scaleId = scale.text.toString().trim().takeIf { it.isNotEmpty() }
                if (scaleId != null && !scaleId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { scale.error = "Use a stable scale ID of up to 64 letters, digits, underscores or hyphens"; return@setOnClickListener }
                if (included && (permission.gwaPolicy == null || numericGrade == null || numericGrade !in permission.gwaPolicy.minimumGrade..permission.gwaPolicy.maximumGrade || scaleId != permission.gwaPolicy.scaleId)) {
                    feedback.text = "GWA inclusion needs a numeric grade within the current reviewed scale and its exact scale ID."; return@setOnClickListener
                }
                val value = CourseAttempt(if (copyAsNew) FirebaseStudyRepository.newRecordId() else previous?.id ?: FirebaseStudyRepository.newRecordId(),
                    course.id, termText, state, course.units, completion, if (completed) numericGrade else null,
                    if (completed) finalPercent else null, if (completed) scaleId else null, includeInGwa = included, policyBinding = binding(permission))
                dialog.dismiss()
                write("Course attempt") { repository.saveCourseAttempt(value) }
            }
        }
        showDialog(dialog)
    }

    private fun renderGwa(body: LinearLayout, data: PlannerData) {
        body.label("GWA", 20f)
        val permissions = data.courses.associate { it.id to access(data, it, AcademicCapability.GWA) }
        val available = permissions.values.firstOrNull { it.allowed }
        if (available == null) { body.card("GWA unavailable", permissions.values.firstOrNull()?.reason ?: "Choose a reviewed college curriculum and GWA policy."); return }
        val result = AcademicGwa.evaluate(data.attempts, permissions)
        body.card("Self-reported GWA", result.average?.let { "${number(it)} · ${number(result.includedUnits)} units · ${result.includedAttemptCount} included attempt(s)\nScale ${available.gwaPolicy?.scaleId}; ${available.gwaPolicy?.roundingMode} rounding. Review retake and inclusion decisions with an adviser." }
            ?: result.unavailableReason ?: "No applicable included attempts.")
        body.label("Included and excluded attempts remain visible under Course attempts. No prior attempt is automatically replaced or deleted.", 14f)
    }

    private fun relatedReadings(course: CatalogCourse, categoryId: String? = null) {
        val plan = course.gradePlan
        if (plan == null || !plan.verified) {
            showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Related readings unavailable")
                .setMessage("This course needs a reviewed assessment plan and reading query before category readings can be suggested. Independent search is available in Study.")
                .setPositiveButton("Close", null).create())
            return
        }
        val uid = sessionUid ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val definitions = withTimeout(12_000) { repository.readingContextDefinitions().first() }.filter {
                    it.kind == ReadingContextKind.WEAK_CATEGORY && it.review.status == "APPROVED" &&
                        (categoryId == null || categoryId == it.categoryId) &&
                        it.courseId == course.id && it.planVersion == plan.version && plan.categories.any { category -> category.id == it.categoryId }
                }
                if (!sessionActive(uid) || selectedCourseId != course.id) return@launch
                if (definitions.isEmpty()) {
                    showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Related readings unavailable")
                        .setMessage("No reviewed reading query is published for this course category. Independent search is available in Study.")
                        .setPositiveButton("Close", null).create())
                } else showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Choose a course category")
                    .setItems(definitions.map { "${it.categoryId} · ${it.query}" }.toTypedArray()) { _, index ->
                        (requireActivity() as MainActivity).openDetails(StudyFragment.forContext(definitions[index].contextId))
                    }.setNegativeButton("Cancel", null).create())
            } catch (error: TimeoutCancellationException) { contextUnavailable(error) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { contextUnavailable(error) }
        }
    }

    private fun contextUnavailable(error: Exception) {
        if (sessionActive()) showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Related readings unavailable")
            .setMessage(failureText(error)).setPositiveButton("Close", null).create())
    }

    private fun renderPrerequisites(body: LinearLayout, course: CatalogCourse, data: PlannerData) {
        body.label("Course requirements", 20f)
        val prerequisiteAccess = access(data, course, AcademicCapability.PREREQUISITES)
        val result = PlannerAdapter.prerequisites(course, data.courses, data.attempts, prerequisiteAccess)
        val chain = body.card("Prerequisite dependency chain", "Self-reported records · adviser review required")
        chain.tag = "planner_prerequisite_chain"
        val states = PlannerAdapter.completionStates(data.attempts.filter { prerequisiteAccess.matchesAttempt(it.policyBinding, it.courseId) })
        fun node(parent: LinearLayout, expression: Prerequisite, path: String) {
            when (expression) {
                is Prerequisite.Course -> {
                    val item = data.courses.firstOrNull { it.id == expression.courseId }
                    val status = states[expression.courseId] ?: CompletionStatus.UNKNOWN
                    val badge = when (status) {
                        CompletionStatus.PASSED -> "PASSED ✓"
                        CompletionStatus.CREDITED_EQUIVALENT -> "CREDITED EQUIVALENT ✓"
                        CompletionStatus.IN_PROGRESS -> "IN PROGRESS …"
                        CompletionStatus.UNKNOWN -> "UNKNOWN ?"
                        CompletionStatus.FAILED -> "UNMET / FAILED"
                        CompletionStatus.INCOMPLETE -> "UNMET / INCOMPLETE"
                        CompletionStatus.NOT_TAKEN -> "UNMET / NOT TAKEN"
                    }
                    parent.action("${expression.courseId.uppercase(Locale.ROOT)} · $badge") {
                        item?.let { target ->
                            val choices = arrayOf("Record or update a course attempt", "Switch Planner to this course")
                            showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle(target.title)
                                .setItems(choices) { _, index ->
                                    if (index == 0) {
                                        val attempts = data.attempts.filter { it.courseId == target.id }
                                        if (attempts.isEmpty()) editAttempt(target, null)
                                        else showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Record or update attempt")
                                            .setItems((listOf("Add a new term / retake") + attempts.map { it.term }).toTypedArray()) { _, choice ->
                                                val previous = attempts.getOrNull(choice - 1)
                                                val permission = access(data, target, AcademicCapability.PREREQUISITES)
                                                editAttempt(target, previous, copyAsNew = previous != null && !permission.matchesAttempt(previous.policyBinding, target.id))
                                            }.setNegativeButton("Cancel", null).create())
                                    }
                                    else {
                                        selectedCourseId = target.id; plannerMode = "roadmap"
                                        coursePicker?.setSelection(pickerIds.indexOf(target.id).coerceAtLeast(0)); render()
                                    }
                                }.setNegativeButton("Cancel", null).create())
                        }
                    }.apply {
                        tag = "planner_prerequisite_node_$path"
                        isEnabled = item != null
                        quietAction()
                        contentDescription = "${expression.courseId}, $badge. Record an attempt or switch course"
                        backgroundTintList = ColorStateList.valueOf(context.tint(if (status in setOf(CompletionStatus.PASSED, CompletionStatus.CREDITED_EQUIVALENT)) R.color.sp_ink else R.color.sp_background))
                        setTextColor(context.tint(if (status in setOf(CompletionStatus.PASSED, CompletionStatus.CREDITED_EQUIVALENT)) android.R.color.white else R.color.sp_ink))
                    }
                }
                is Prerequisite.Unknown -> parent.label("UNKNOWN ? · ${expression.reason}", 14f)
                else -> {
                    val children = when (expression) {
                        is Prerequisite.AllOf -> expression.children
                        is Prerequisite.AnyOf -> expression.children
                        else -> emptyList()
                    }
                    if (children.isEmpty()) parent.label("No prerequisites required by this reviewed rule", 14f)
                    else {
                        val all = expression is Prerequisite.AllOf
                        val state = PrerequisiteEvaluator.evaluate(expression, data.courses.map { it.id }.toSet(), states).state
                        val group = requireContext().column(4)
                        group.label(if (all) "AND · all required" else "OR · any one required", 16f)
                        group.label(stateText(state), 14f)
                        parent.addView(group)
                        children.forEachIndexed { index, child -> node(group, child, "${path}_$index") }
                    }
                }
            }
        }
        node(chain, PlannerAdapter.prerequisiteTree(course, data.courses, prerequisiteAccess), "root")
        chain.label("↓ Requires the group above", 14f).importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        chain.label("${course.id.uppercase(Locale.ROOT)} · ${stateText(result.state)}", 18f)
        body.card("${course.id.uppercase(Locale.ROOT)} · ${stateText(result.state)}", buildString {
            if (result.reasons.isEmpty()) append("Prerequisites are met based on the entered records.")
            else append(result.reasons.joinToString("\n"))
            append("\nSelf-reported · adviser review required")
        })
        if (!prerequisiteAccess.allowed) return
        body.label("Course outcomes", 20f)
        body.label("Self-reported; no automatic pass decisions", 14f)
        val currentAttempts = data.attempts.filter { prerequisiteAccess.matchesAttempt(it.policyBinding, it.courseId) }
        val outcomeStates = PlannerAdapter.completionStates(currentAttempts)
        data.courses.filter { access(data, it, AcademicCapability.PREREQUISITES).allowed }.forEach { item ->
            val current = outcomeStates[item.id] ?: CompletionStatus.UNKNOWN
            body.action("${item.id.uppercase(Locale.ROOT)} · ${completionText(current)}") {
                selectedCourseId = item.id
                expandedSections.add("More options"); expandedSections.add("Course attempts")
                coursePicker?.setSelection(pickerIds.indexOf(item.id).coerceAtLeast(0))
                render()
            }
        }
    }

    private fun editScore(course: CatalogCourse, row: PlannedAssessmentState) {
        if (!sessionActive()) return
        if (latest?.let { access(it, course, AcademicCapability.SCORES).allowed } != true) { render(); return }
        val form = requireContext().column()
        form.label("${row.title}", 14f)
        val maximum = form.field("Planned maximum points", decimalInput).apply {
            setText(number(row.assessment.possiblePoints)); isEnabled = false
        }
        val statuses = listOf("Scored", "Ungraded", "Excused", "Missing (treatment needs policy review)")
        val picker = Spinner(requireContext()).apply {
            minimumHeight = requireContext().dp(48)
            contentDescription = "Assessment score status"
            adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, statuses)
            setSelection(row.assessment.status.ordinal)
        }
        form.addView(picker)
        val earned = form.field("Earned points (zero is a valid score)", decimalInput).apply {
            setText(row.assessment.earnedPoints?.let(::number).orEmpty())
            isEnabled = row.assessment.status == AssessmentStatus.SCORED
        }
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { earned.isEnabled = position == 0 }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle("Record assessment score")
            .setView(requireContext().scrolling(form)).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val status = AssessmentStatus.entries[picker.selectedItemPosition]
                val possible = maximum.text.toString().toDoubleOrNull()
                val points = earned.text.toString().trim().toDoubleOrNull()
                if (possible == null || !possible.isFinite() || possible <= 0.0 || possible != row.assessment.possiblePoints) {
                    maximum.error = "The planned maximum must be a positive number"; return@setOnClickListener
                }
                if (status == AssessmentStatus.SCORED && (points == null || !points.isFinite() || points !in 0.0..possible)) {
                    earned.error = "Enter earned points from 0 to ${number(possible)}"; return@setOnClickListener
                }
                val version = course.gradePlan?.version ?: return@setOnClickListener
                val scoreAccess = latest?.let { access(it, course, AcademicCapability.SCORES) } ?: return@setOnClickListener
                if (!scoreAccess.allowed) { dialog.dismiss(); render(); return@setOnClickListener }
                val score = ScoreRecord(row.record?.id ?: PlannerAdapter.scoreId(course.id, version, row.categoryId, row.assessment.id, scoreAccess.bindingFingerprint),
                    course.id, row.assessment.id, row.categoryId, status.name, possible,
                    if (status == AssessmentStatus.SCORED) points else null, planVersion = version, policyBinding = binding(scoreAccess))
                dialog.dismiss()
                write("Assessment score") { repository.saveScore(score) }
            }
        }
        showDialog(dialog)
    }

    private fun confirmDeleteScore(record: ScoreRecord) {
        showDialog(MaterialAlertDialogBuilder(requireContext()).setTitle("Delete this recorded score?")
            .setMessage("The planned assessment will return to ungraded. A deleted score is not a scored zero.")
            .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                write("Score deletion") { repository.deleteScore(record.id) }
            }.create())
    }

    private fun showDialog(dialog: AlertDialog) {
        activeDialog?.dismiss()
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    private fun write(label: String, action: suspend () -> Unit) {
        val uid = sessionUid ?: return
        if (!sessionActive(uid)) return
        syncNote?.isVisible = true
        syncNote?.text = "Saving…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                action()
                if (sessionActive(uid)) syncNote?.text = "Saved"
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (sessionActive(uid)) syncNote?.text = "Couldn’t save. ${failureText(error)}"
            }
        }
    }

    private fun sessionActive(uid: String? = sessionUid): Boolean =
        isAdded && content != null && uid != null && repository.currentSession()?.uid == uid

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("courseId", selectedCourseId); outState.putString("target", targetText)
        outState.putString("plannerMode", plannerMode); outState.putString("scenarioId", selectedScenarioId)
        outState.putStringArrayList("expandedSections", ArrayList(expandedSections))
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        activeDialog?.dismiss(); activeDialog = null
        collector?.cancel(); collector = null
        policyClock?.cancel(); policyClock = null
        content = null; coursePicker = null; loading = null; syncNote = null; retry = null
        pickerIds = emptyList(); latest = null; sessionUid = null
        super.onDestroyView()
    }

    private fun projectionText(result: TargetProjection): String = when (result.status) {
        TargetStatus.UNKNOWN_PLAN -> "Exact remaining performance is unavailable without a complete assessment plan."
        TargetStatus.NEEDS_REMAINING -> "Needed average on remaining planned work: at least ${requiredPercent(result.requiredRemainingPercent!!)}. This is a requirement, not a prediction."
        TargetStatus.ALREADY_SECURED -> "Secured · no more than 0% is needed on remaining planned work."
        TargetStatus.UNREACHABLE -> "Unreachable · this target would require ${percent(result.requiredRemainingPercent!!)} on remaining work."
        TargetStatus.FINAL_MET -> "The plan is complete and the recorded result meets this target."
        TargetStatus.FINAL_MISSED -> "The plan is complete and the recorded result is below this target."
    }

    private fun stateText(state: PrerequisiteState) = when (state) {
        PrerequisiteState.MET -> "prerequisites met based on records"
        PrerequisiteState.NOT_MET -> "prerequisites not met today"
        PrerequisiteState.UNKNOWN -> "prerequisites need review"
    }

    private fun completionText(state: CompletionStatus) = when (state) {
        CompletionStatus.PASSED -> "Passed (self-reported)"
        CompletionStatus.CREDITED_EQUIVALENT -> "Credited equivalent (self-reported)"
        CompletionStatus.IN_PROGRESS -> "In progress"
        CompletionStatus.FAILED -> "Failed (self-reported)"
        CompletionStatus.INCOMPLETE -> "Incomplete"
        CompletionStatus.NOT_TAKEN -> "Not taken"
        CompletionStatus.UNKNOWN -> "Unknown / needs review"
    }

    private fun number(value: Double): String = if (value % 1.0 == 0.0) "%.0f".format(Locale.ROOT, value) else value.toString()
    private fun percent(value: Double): String = "%.2f%%".format(Locale.ROOT, value)
    private fun requiredPercent(value: Double): String = BigDecimal.valueOf(value).setScale(2, RoundingMode.CEILING).toPlainString() + "%"

    private fun access(data: PlannerData, course: CatalogCourse, capability: AcademicCapability): AcademicAccess {
        val context = data.context
        val curriculum = context.preferences.curriculumVersion
        return AcademicAccess.resolve(context.preferences, context.options.singleOrNull { it.curriculumVersion == curriculum },
            context.policies.singleOrNull { it.curriculumVersion == curriculum }, course.id, capability, course.gradePlan?.version)
    }

    private fun binding(access: AcademicAccess) = AcademicRecordBinding(requireNotNull(access.curriculumVersion),
        requireNotNull(access.policyRevision), requireNotNull(access.policyRef), requireNotNull(access.planVersion))

    private fun schedulePolicyBoundary() {
        policyClock?.cancel()
        val data = latest ?: return
        val policy = data.context.policies.singleOrNull { it.curriculumVersion == data.context.preferences.curriculumVersion } ?: return
        val now = System.currentTimeMillis()
        val boundary = listOf(policy.validFrom, policy.validUntil).filter { it > now }.minOrNull() ?: return
        policyClock = viewLifecycleOwner.lifecycleScope.launch {
            delay(boundary - now + 1)
            if (sessionActive()) { activeDialog?.dismiss(); activeDialog = null; render(); schedulePolicyBoundary() }
        }
    }

    private data class PlannerContext(val preferences: LearnerPreferences = LearnerPreferences(),
                                      val options: List<LearnerPreferenceOptions> = emptyList(),
                                      val policies: List<AcademicPolicySnapshot> = emptyList())
    private data class PlannerData(val courses: List<CatalogCourse>, val scores: List<ScoreRecord>,
                                   val attempts: List<CourseAttempt>, val scenarios: List<GradeScenario>,
                                   val context: PlannerContext = PlannerContext())
}
