package com.studypath.app.ui

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.StudyPathApplication
import com.studypath.app.R
import com.studypath.app.ui.auth.OnboardingSaveModel
import com.studypath.app.data.LearnerPreferenceOptions
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.PreferenceChoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A preference draft is useful even when no approved curriculum has been published. */
class LearnerPreferencesFragment : Fragment() {
    private val repository get() = (requireActivity().application as StudyPathApplication).graph.repository
    private var draft = LearnerPreferences()
    private var initialized = false
    private var options = emptyList<LearnerPreferenceOptions>()
    private var catalogLoaded = false
    private var catalogError: String? = null
    private var connected: Boolean? = null
    private var saving = false
    private var acknowledged = false
    private var feedback = ""
    private var updatingClass = false
    private var classInput = ""
    private var nameInput = ""
    private var renderedSubjects = emptyList<String>()
    private var renderedPacks = emptyList<LearnerPreferenceOptions>()
    private var classValid = true
    private var loadFailure: String? = null
    private var preferenceLoadJob: Job? = null
    private var contextExpanded = false
    private val editing get() = arguments?.getBoolean("editing") == true
    companion object { fun editing() = LearnerPreferencesFragment().apply { arguments = Bundle().apply { putBoolean("editing", true) } } }
    private var setupStep = 1
    private var renderedStep: Int? = null
    private var stepBack: androidx.activity.OnBackPressedCallback? = null
    private val saveModel get() = ViewModelProvider(requireActivity())[OnboardingSaveModel::class.java]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupStep = savedInstanceState?.getInt("setup_step", 1) ?: 1
        contextExpanded = savedInstanceState?.getBoolean("context_expanded") == true
        savedInstanceState?.takeIf { it.getBoolean("initialized") }?.let {
            initialized = true
            draft = LearnerPreferences(it.getString("level"), it.getString("curriculum"),
                it.getStringArrayList("subjects").orEmpty().toSet(), it.getString("class"), it.getBoolean("complete"))
            nameInput = it.getString("display_name").orEmpty()
            classInput = it.getString("class_input") ?: draft.classSectionId?.let(PreferenceChoices::label).orEmpty()
            classValid = classInput.isBlank() || runCatching { PreferenceChoices.customId(classInput) }.isSuccess
            feedback = if (it.getBoolean("pending") || it.getString("feedback") == "Preferences saved")
                "Check your preferences and save again." else it.getString("feedback").orEmpty()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val context = requireContext()
        val content = context.column(24).apply { tag = "learner_preferences" }
        val top = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        val progress = LinearLayout(context).apply { tag = "preference_progress" }
        repeat(2) { index ->
            progress.addView(View(context).apply { setBackgroundColor(context.tint(R.color.sp_ink)) },
                LinearLayout.LayoutParams(0, context.dp(4), 1f).apply { if (index > 0) marginStart = context.dp(8) })
        }
        top.addView(progress, LinearLayout.LayoutParams(0, -2, 1f))
        val skip = MaterialButton(context).apply {
            text = "Skip"; isAllCaps = false; minHeight = context.dp(48); minimumHeight = context.dp(48)
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            strokeWidth = 0
            setTextColor(context.tint(R.color.sp_ink)); tag = "preference_later"
            setOnClickListener { repository.currentSession()?.uid?.let { OptionalOnboarding.skip(requireContext(), it) }; leave() }
        }
        top.addView(skip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = context.dp(16) })
        content.addView(top, LinearLayout.LayoutParams(-1, -2))
        if (editing) top.visibility = View.GONE
        val mascot = GiraffeMascotView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        content.addView(mascot, LinearLayout.LayoutParams(-1, context.dp(72)))
        mascot.play(GiraffeMascotView.Pose.WAKE)
        if (editing) mascot.visibility = View.GONE
        content.label("Your study level?", 28f).tag = "preference_heading"
        content.label("Loading your saved preferences…", 14f).tag = "preference_load_status"
        content.action("Retry preferences") { loadPreferences() }.tag = "preference_retry"
        val profile = context.column(0).apply { tag = "preference_profile_step" }
        content.addView(profile)
        profile.field("Your name (optional)").apply {
            tag = "preference_display_name"; filters = arrayOf(InputFilter.LengthFilter(80)); setSingleLine(true)
            setText(nameInput)
            doAfterTextChanged { nameInput = it.toString() }
        }
        profile.action("Choose level") { chooseLevel() }.tag = "preference_level"
        val subjects = context.column(0).apply { tag = "preference_subject_step" }
        content.addView(subjects)
        subjects.action("Back") { setupStep = 1; render(); (view as? ScrollView)?.smoothScrollTo(0, 0) }.apply {
            quietAction(); tag = "preference_back"; contentDescription = "Back to study level"
        }
        subjects.addView(context.column(0).apply { tag = "preference_subject_grid" })
        subjects.action("More options  +") {
            contextExpanded = !contextExpanded; render()
        }.apply {
            tag = "preference_context_toggle"; layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(context.tint(R.color.sp_ink)); strokeWidth = 0
        }
        val details = context.column(0).apply { tag = "preference_context_details" }
        details.addView(context.column(0).apply { tag = "preference_published_packs" })
        details.action("+ Custom subject") {
            customChoice("Subject name") { id ->
                if (draft.subjectIds.size < 20 || id in draft.subjectIds) changed(draft.copy(subjectIds = draft.subjectIds + id))
                else { feedback = "Choose up to 20 subjects."; render() }
            }
        }.apply { tag = "preference_custom_subject"; quietAction() }
        details.action("More subjects") { chooseSubjects() }.tag = "preference_subjects"
        details.action("Choose approved curriculum") { chooseCurriculum() }.tag = "preference_curriculum"
        details.field("Class or section (optional)").apply {
            tag = "preference_class"; filters = arrayOf(InputFilter.LengthFilter(64)); setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setText(classInput)
            doAfterTextChanged { text ->
                if (updatingClass) return@doAfterTextChanged
                classInput = text.toString()
                val value = classInput.trim()
                val selected = runCatching { if (value.isBlank()) null else PreferenceChoices.customId(value) }.getOrNull()
                classValid = value.isBlank() || selected != null
                error = if (classValid) null else "Enter a class name using letters or numbers, or leave this blank."
                changed(if (classValid) draft.copy(classSectionId = selected) else draft)
            }
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
                content.findViewWithTag<View>(if (setupStep == 1) "preference_next" else "preference_save")?.apply {
                    post { requestRectangleOnScreen(Rect(0, 0, width, height), true) }
                }
                true
            }
        }
        profile.action("Choose subjects →") { setupStep = 2; render(); (view as? ScrollView)?.smoothScrollTo(0, 0) }.tag = "preference_next"
        details.label("Loading curricula…", 14f).tag = "preference_catalog_status"
        subjects.addView(details, LinearLayout.LayoutParams(-1, -2))
        subjects.label("", 14f).apply {
            tag = "preference_unlock_summary"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        subjects.action(if (editing) "Save preferences" else "Continue") { saveAndContinue() }.apply {
            tag = "preference_save"; layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        subjects.label(feedback, 14f).apply {
            tag = "preference_save_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        return context.scrolling(content)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        stepBack = object : androidx.activity.OnBackPressedCallback(setupStep == 2) {
            override fun handleOnBackPressed() { setupStep = 1; render(); (view as? ScrollView)?.smoothScrollTo(0, 0) }
        }.also { requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, it) }
        render()
        loadPreferences()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.learnerPreferenceOptions().catch {
                        catalogLoaded = true; catalogError = "Curricula unavailable. You can still save a draft."
                        render()
                    }.collect { published ->
                        options = published; catalogLoaded = true; catalogError = null; render()
                    }
                }
                launch {
                    saveModel.state.collect { submission ->
                        if (!editing && submission.owner == repository.currentSession()?.uid && submission.selection != null) {
                            saving = submission.pending
                            if (submission.selection == PreferenceChoices.forSave(draft, options)) {
                                acknowledged = submission.confirmed; feedback = submission.message
                            }
                            render()
                        }
                    }
                }
                repository.connection().catch { connected = null; render() }.collect { online -> connected = online; render() }
            }
        }
    }

    private fun loadPreferences() {
        if (initialized) return
        preferenceLoadJob?.cancel()
        loadFailure = null; render()
        preferenceLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (!initialized) {
                    try {
                        val submission = saveModel.state.value
                        val stored = submission.selection?.takeIf { !editing && submission.owner == repository.currentSession()?.uid }
                            ?: repository.learnerPreferences().first()
                        draft = if (stored.levelId == null && stored.subjectIds.isEmpty()) stored.copy(levelId = "college") else stored
                        nameInput = submission.displayName?.takeIf { !editing && submission.owner == repository.currentSession()?.uid }
                            ?: repository.profileName().first().orEmpty()
                        view?.findViewWithTag<com.google.android.material.textfield.TextInputEditText>("preference_display_name")?.setText(nameInput)
                        initialized = true
                        classInput = draft.classSectionId?.let(PreferenceChoices::label).orEmpty()
                        updatingClass = true; field()?.setText(classInput); updatingClass = false
                        render()
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        loadFailure = "Your saved preferences could not load. Try again before editing."
                        render()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("setup_step", setupStep)
        outState.putBoolean("initialized", initialized)
        outState.putBoolean("context_expanded", contextExpanded)
        outState.putString("level", draft.levelId); outState.putString("curriculum", draft.curriculumVersion)
        outState.putStringArrayList("subjects", ArrayList(draft.subjectIds))
        outState.putString("class", draft.classSectionId); outState.putBoolean("complete", draft.onboardingComplete)
        outState.putString("class_input", classInput)
        outState.putString("display_name", nameInput)
        outState.putString("feedback", feedback); outState.putBoolean("pending", saving)
    }

    private fun chooseLevel() {
        val choices = PreferenceChoices.levels(options, draft.levelId)
        val labels = choices.map { it.label } + "Other level…"
        MaterialAlertDialogBuilder(requireContext()).setTitle("Choose your level")
            .setItems(labels.toTypedArray()) { _, index ->
                if (index == choices.size) customChoice("Your level") { changed(draft.copy(levelId = it, curriculumVersion = null)) }
                else changed(draft.copy(levelId = choices[index].id, curriculumVersion = null))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseSubjects() {
        val choices = PreferenceChoices.subjects(options.filter { it.levelId == draft.levelId }, draft.subjectIds)
        val selected = draft.subjectIds.toMutableSet()
        MaterialAlertDialogBuilder(requireContext()).setTitle("Choose up to 20 subjects")
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), choices.map { it.id in selected }.toBooleanArray()) { dialog, index, checked ->
                if (checked && selected.size >= 20) {
                    (dialog as androidx.appcompat.app.AlertDialog).listView.setItemChecked(index, false)
                } else if (checked) selected.add(choices[index].id) else selected.remove(choices[index].id)
            }.setPositiveButton("Apply") { _, _ -> changed(draft.copy(subjectIds = selected.toSet())) }
            .setNeutralButton("Add another subject") { _, _ ->
                customChoice("Subject name") { id ->
                    if (selected.size < 20 || id in selected) changed(draft.copy(subjectIds = selected + id))
                    else { feedback = "Choose up to 20 subjects."; render() }
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun chooseCurriculum() {
        val matching = PreferenceChoices.matching(draft, options)
        if (matching.isEmpty()) return
        MaterialAlertDialogBuilder(requireContext()).setTitle("Choose an approved curriculum")
            .setItems(matching.map { PreferenceChoices.label(it.curriculumVersion) }.toTypedArray()) { _, index ->
                changed(draft.copy(curriculumVersion = matching[index].curriculumVersion))
            }.setNegativeButton("Cancel", null).show()
    }

    private fun customChoice(title: String, apply: (String) -> Unit) {
        val content = requireContext().column(16)
        val input = content.field(title).apply { filters = arrayOf(InputFilter.LengthFilter(64)); setSingleLine(true) }
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(content)
            .setPositiveButton("Use this", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val id = runCatching { PreferenceChoices.customId(input.text.toString()) }.getOrNull()
                if (id == null) input.error = "Enter a name using letters or numbers."
                else { apply(id); dialog.dismiss() }
            }
        }
        dialog.show()
    }

    private fun changed(value: LearnerPreferences) {
        if (!initialized || saving) return
        draft = value.copy(onboardingComplete = false); initialized = true; acknowledged = false
        if (!saving) feedback = "Unsaved preferences"
        render()
    }

    private fun saveAndContinue() {
        if (!initialized || saving || !classValid || needsCatalog()) return
        val selected = PreferenceChoices.forSave(draft, options)
        if (editing) {
            viewLifecycleOwner.lifecycleScope.launch {
                saving = true; render()
                try {
                    kotlinx.coroutines.withTimeout(12000) { repository.saveLearnerPreferences(selected) }
                    leave()
                } catch (error: kotlinx.coroutines.TimeoutCancellationException) { feedback = "Waiting to save. Your changes are kept; reconnect and retry." }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { feedback = failureText(error) }
                finally { saving = false; if (view != null) render() }
            }
            return
        }
        saveModel.submit(repository, selected, nameInput.trim().ifBlank { "Student" })
        if (saveModel.state.value.owner != repository.currentSession()?.uid) return
        draft = selected
        parentFragmentManager.beginTransaction()
            .replace(R.id.main_content, OnboardingReadyFragment.forSubjects(selected.subjectIds)).commit()
    }

    private fun render() {
        val content = (view as? ScrollView)?.getChildAt(0) as? LinearLayout ?: return
        if (editing) {
            content.findViewWithTag<View>("preference_next").visibility = View.GONE
            content.findViewWithTag<View>("preference_back").visibility = View.GONE
            (content.findViewWithTag<View>("preference_display_name").parent.parent as View).visibility = View.GONE
        }
        stepBack?.isEnabled = !editing && setupStep == 2
        content.findViewWithTag<View>("preference_profile_step").visibility = if (editing || setupStep == 1) View.VISIBLE else View.GONE
        content.findViewWithTag<View>("preference_subject_step").visibility = if (editing || setupStep == 2) View.VISIBLE else View.GONE
        if (renderedStep != setupStep) {
            val previous = renderedStep
            renderedStep = setupStep
            listOf("preference_profile_step", "preference_subject_step").forEach { tag ->
                content.findViewWithTag<View>(tag).apply { animate().cancel(); translationX = 0f; alpha = 1f }
            }
            if (previous != null && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                content.findViewWithTag<View>(if (setupStep == 1) "preference_profile_step" else "preference_subject_step").apply {
                    translationX = context.dp(if (setupStep > previous) 24 else -24).toFloat()
                    alpha = 0f
                    animate().translationX(0f).alpha(1f).setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
            }
        }
        content.findViewWithTag<LinearLayout>("preference_progress").apply {
            contentDescription = "Preferences, step $setupStep of 2"
            for (index in 0 until childCount) getChildAt(index).setBackgroundColor(context.tint(if (index < setupStep) R.color.sp_ink else R.color.sp_outline))
        }
        content.findViewWithTag<TextView>("preference_heading").text = if (editing) "Study preferences" else if (setupStep == 1) "Your study level?" else "Your subjects?"
        listOf("preference_next", "preference_custom_subject").forEach { content.findViewWithTag<View>(it).isEnabled = initialized && !saving }
        content.findViewWithTag<View>("preference_context_details").visibility = if (contextExpanded) View.VISIBLE else View.GONE
        content.findViewWithTag<MaterialButton>("preference_context_toggle").text =
            if (contextExpanded) "More options  −" else "More options  +"
        renderChoices(content)
        content.findViewWithTag<com.google.android.material.textfield.TextInputEditText>("preference_display_name").isEnabled = initialized && !saving
        content.findViewWithTag<TextView>("preference_unlock_summary").text =
            if (PreferenceChoices.forSave(draft, options).onboardingComplete)
                "Course pack available"
            else "Personal study draft"
        for (id in renderedSubjects) {
            content.findViewWithTag<MaterialButton>("preference_subject_$id").apply {
                val selected = id in draft.subjectIds
                isEnabled = initialized && !saving; isChecked = selected
                backgroundTintList = android.content.res.ColorStateList.valueOf(requireContext().tint(if (selected) R.color.sp_action else R.color.sp_ink))
                setTextColor(requireContext().tint(R.color.sp_on_action)); strokeWidth = 0
                androidx.core.view.ViewCompat.setStateDescription(this, if (selected) "Selected" else "Not selected")
            }
        }
        content.findViewWithTag<TextView>("preference_load_status").apply {
            visibility = if (initialized) View.GONE else View.VISIBLE
            text = loadFailure ?: "Loading your saved preferences…"
        }
        content.findViewWithTag<MaterialButton>("preference_retry").visibility =
            if (!initialized && loadFailure != null) View.VISIBLE else View.GONE
        content.findViewWithTag<MaterialButton>("preference_level").apply {
            text = draft.levelId?.let { "Level: ${PreferenceChoices.label(it)}" } ?: "Choose level"
            isEnabled = initialized && !saving
        }
        content.findViewWithTag<MaterialButton>("preference_subjects").apply {
            val subjects = draft.subjectIds.sorted().map(PreferenceChoices::label)
            text = when {
                subjects.isEmpty() -> "Choose subjects"
                subjects.size == 1 && subjects.first().length <= 24 -> "Subjects: ${subjects.first()}"
                else -> "Subjects: ${subjects.size} selected"
            }
            contentDescription = if (subjects.isEmpty()) "Choose subjects"
                else "Choose subjects. ${subjects.size} selected: ${subjects.joinToString()}"
            isEnabled = initialized && !saving
        }
        val matching = PreferenceChoices.matching(draft, options)
        content.findViewWithTag<MaterialButton>("preference_curriculum").apply {
            visibility = if (matching.size > 1) View.VISIBLE else View.GONE
            text = draft.curriculumVersion?.takeIf { selected -> matching.any { it.curriculumVersion == selected } }
                ?.let { "Curriculum: ${PreferenceChoices.label(it)}" } ?: "Choose approved curriculum"
            isEnabled = initialized && !saving
        }
        content.findViewWithTag<TextView>("preference_catalog_status").text = when {
            catalogError != null && draft.onboardingComplete -> "Curricula unavailable. Your completed preferences are kept. Reopen to retry, or edit to save a draft."
            catalogError != null -> catalogError
            needsCatalog() -> "Checking curricula before updating completed preferences…"
            !catalogLoaded -> "Loading curricula… You can save a draft."
            matching.isEmpty() -> "No approved curriculum matches yet. Save a draft and explore Wikipedia."
            matching.size > 1 && matching.none { it.curriculumVersion == draft.curriculumVersion } -> "Choose a curriculum, or save a draft."
            else -> "Approved curriculum found. Save to complete your preferences."
        }
        content.findViewWithTag<TextView>("preference_save_status").text = if (saving) {
            if (connected == false) "Offline · preferences are waiting for confirmation. You can keep exploring."
            else "Saving…"
        } else feedback
        content.findViewWithTag<MaterialButton>("preference_save").isEnabled = initialized && !saving && classValid && !needsCatalog()
        content.findViewWithTag<TextView>("preference_save_status").visibility = if (saving || feedback.isNotBlank()) View.VISIBLE else View.GONE
        content.findViewWithTag<MaterialButton>("preference_later").isEnabled = true
        field()?.apply {
            isEnabled = initialized && !saving
            error = if (classValid) null else "Enter a class name using letters or numbers, or leave this blank."
        }
    }

    private fun renderChoices(content: LinearLayout) {
        val context = requireContext()
        val published = options.filter { it.approved && it.approvalStatus == "APPROVED" }
            .distinctBy { it.curriculumVersion }.sortedBy { it.curriculumVersion }
        val packs = content.findViewWithTag<LinearLayout>("preference_published_packs")
        if (published != renderedPacks || packs.childCount == 0) {
            renderedPacks = published; packs.removeAllViews()
            published.forEach { option ->
                packs.action("Use published course pack · ${PreferenceChoices.label(option.levelId)} · ${option.subjectIds.sorted().joinToString(transform = PreferenceChoices::label)} · ${PreferenceChoices.label(option.curriculumVersion)}") {
                    changed(draft.copy(levelId = option.levelId, subjectIds = option.subjectIds,
                        curriculumVersion = option.curriculumVersion))
                }.apply { tag = "preference_pack_${option.curriculumVersion}"; layoutParams.width = -1; maxLines = Int.MAX_VALUE }
            }
        }
        for (index in 0 until packs.childCount) packs.getChildAt(index).isEnabled = initialized && !saving
        val subjects = (listOf("programming", "databases", "mathematics", "statistics") +
            (if (draft.levelId?.startsWith("grade_") == true) listOf("english", "science", "filipino", "social_studies") else emptyList()) +
            published.filter { it.levelId == draft.levelId }.flatMap { it.subjectIds }.sorted() + draft.subjectIds.sorted()).distinct()
        val grid = content.findViewWithTag<LinearLayout>("preference_subject_grid")
        if (subjects == renderedSubjects && grid.childCount > 0) return
        renderedSubjects = subjects; grid.removeAllViews()
        val columns = if (resources.configuration.fontScale >= 1.5f) 1 else 2
        subjects.chunked(columns).forEach { ids ->
            val row = LinearLayout(context)
            ids.forEachIndexed { index, id ->
                val choice = MaterialButton(context).apply {
                    text = PreferenceChoices.label(id); isAllCaps = false; isCheckable = true
                    minHeight = context.dp(72); minimumHeight = context.dp(72); maxLines = 3
                    cornerRadius = context.dp(20); textSize = 15f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.geist)
                    tag = "preference_subject_$id"
                    setOnClickListener {
                        val selected = if (id in draft.subjectIds) draft.subjectIds - id else draft.subjectIds + id
                        if (selected.size <= 20) changed(draft.copy(subjectIds = selected))
                        else { feedback = "Choose up to 20 subjects."; render() }
                    }
                }
                row.addView(choice, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = context.dp(8) })
            }
            grid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
        }
    }

    private fun field() = view?.findViewWithTag<com.google.android.material.textfield.TextInputEditText>("preference_class")

    private fun needsCatalog() = draft.onboardingComplete && (!catalogLoaded || catalogError != null)

    private fun leave() { parentFragmentManager.popBackStack() }
}
