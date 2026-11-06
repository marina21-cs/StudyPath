package com.studypath.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.content.res.ColorStateList
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.studypath.app.StudyPathApplication
import com.studypath.app.R
import com.google.android.material.button.MaterialButton
import com.studypath.app.data.MediaWikiClient
import com.studypath.app.data.ReadingSearchResult
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.ReadingContext
import com.studypath.app.data.ResolvedReadingContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

data class SearchState(val query: String = "", val loading: Boolean = false, val items: List<ReadingSearchResult> = emptyList(), val error: String? = null, val submitted: Boolean = false, val context: ReadingContext? = null)
class SearchModel : ViewModel() {
    val state = MutableStateFlow(SearchState())
    private var job: Job? = null
    var selectedContext: ResolvedReadingContext? = null; private set
    fun clearSearch() {
        job?.cancel()
        selectedContext = null
        state.value = SearchState()
    }
    fun selectContext(value: ResolvedReadingContext) {
        job?.cancel()
        selectedContext = value
        state.value = SearchState(query = value.query, context = value.context)
    }
    fun contextUnavailable(message: String) {
        job?.cancel()
        selectedContext = null
        state.value = SearchState(error = message, submitted = true)
    }
    fun search(api: MediaWikiClient, query: String, repository: FirebaseStudyRepository? = null) {
        job?.cancel()
        val selected = selectedContext?.takeIf { it.query == query }
        if (selected == null) selectedContext = null
        job = viewModelScope.launch {
            state.value = SearchState(query, loading = true, submitted = true, context = selected?.context)
            try {
                val current = if (selected == null) null else withTimeout(12_000) {
                    requireNotNull(repository).resolveReadingContext(selected.context)
                }.also { require(it.query == query) { "This reviewed query has changed. Open its context again." } }
                state.value = SearchState(query, items = api.search(current?.query ?: query), submitted = true, context = current?.context)
            }
            catch (error: TimeoutCancellationException) { state.value = SearchState(query, error = failureText(error), submitted = true, context = selected?.context) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { state.value = SearchState(query, error = failureText(error), submitted = true, context = selected?.context) }
        }
    }
}
class StudyFragment : Fragment() {
    private val model: SearchModel by viewModels()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = requireContext().column()
        val hero = requireContext().column(0).apply { tag = "study_hero" }
        hero.studyGreeting("")
        content.addView(hero)
        content.addView(requireContext().column(0).apply { tag = "study_continue" })
        content.addView(requireContext().column(0).apply { tag = "study_semester" })
        content.addView(requireContext().column(0).apply { tag = "study_subjects" })
        val discovery=content.card("Explore Wikipedia", "Find a supplemental reading for your next study session.")
        ((discovery.parent as View).layoutParams as LinearLayout.LayoutParams).topMargin=requireContext().dp(24)
        discovery.label("", 14f).apply { tag = "reading_context_label"; visibility = View.GONE }
        val searchRow = requireContext().column(0).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
        discovery.addView(searchRow)
        val query = searchRow.field("Search Wikipedia").apply { tag = "reading_search_query"; setText(model.state.value.query); imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true); id = View.generateViewId() }
        generateSequence(query.parent) { it.parent }.filterIsInstance<com.google.android.material.textfield.TextInputLayout>().firstOrNull()?.apply {
            layoutParams=LinearLayout.LayoutParams(0,-2,1f)
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
            setEndIconContentDescription("Clear search")
            setEndIconOnClickListener { content.findViewWithTag<View>("reading_search_clear")?.performClick() }
        }
        val graph = (requireActivity().application as StudyPathApplication).graph
        val api = graph.readings
        val search = searchRow.action("→") { model.search(api, query.text.toString(), graph.repository) }.apply { tag = "reading_search_submit"; contentDescription="Find readings";layoutParams=LinearLayout.LayoutParams(context.dp(56),context.dp(56)).apply{marginStart=context.dp(8)} }
        query.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEARCH) { search.performClick(); true } else false }
        content.action("Back to my study", model::clearSearch).apply {
            tag = "reading_search_clear"; quietAction(); isVisible = model.state.value.submitted
        }
        val topics = requireContext().column(0).apply { tag = "study_browse_body" }
        val wikiLane = requireContext().column(0).also { topics.addView(it); it.label("Browse topics", 20f) }
        val topicRow = com.google.android.material.chip.ChipGroup(requireContext()).apply { isSingleLine = false; chipSpacingHorizontal = context.dp(8) }
        listOf("Programming" to "Computer programming", "Data structures" to "Data structure", "Databases" to "Relational database").forEach { (title, topic) ->
            val chip = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = title; isAllCaps = false; textSize = 14f; minHeight = requireContext().dp(48)
                cornerRadius = requireContext().dp(16); setTextColor(requireContext().tint(R.color.sp_ink))
                strokeWidth = 0
                backgroundTintList = ColorStateList.valueOf(requireContext().tint(if (title == "Programming") R.color.sp_lilac else if (title == "Databases") R.color.sp_mint else R.color.sp_peach))
                contentDescription = "Search $topic"
                setOnClickListener { query.setText(topic); model.search(api, topic, graph.repository) }
            }
            topicRow.addView(chip, ViewGroup.LayoutParams(-2, -2))
        }
        wikiLane.addView(topicRow)
        val browse=discovery.compactAction("Browse topics & course lessons") { topics.isVisible=!topics.isVisible }
        browse.tag="study_browse_toggle"
        topics.isVisible=false
        discovery.addView(topics)
        val results = requireContext().column(0).apply { tag = "search_results" }
        content.addView(results)
        wikiLane.addView(requireContext().column(0).apply { tag = "reading_starters" })
        topics.label("Course lessons", 20f)
        topics.compactAction("Browse course packs") { (requireActivity() as MainActivity).openDetails(CoursePacksFragment()) }.tag = "study_course_packs"
        topics.addView(requireContext().column(0).apply { tag = "featured_module" })
        return requireContext().scrolling(content)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = (view as android.widget.ScrollView).getChildAt(0) as LinearLayout
        val subjectPanel = content.findViewWithTag<LinearLayout>("study_subjects")
        watchPersonal({ data ->
            subjectPanel.removeAllViews()
            content.findViewWithTag<LinearLayout>("study_semester").apply {
                removeAllViews(); semesterCard(data) { requireActivity().findViewById<CapsuleBottomNavigationView>(R.id.navigation).selectedItemId=R.id.nav_planner }
            }
            if (data.subjects.isNotEmpty()) {
                subjectPanel.label("Pick a subject", 20f)
                data.subjects.take(2).forEach { subject -> subjectPanel.plannerSubject(subject, data) {
                    (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.open(subject.id, "lessons"))
                } }
            }
            subjectPanel.compactAction("All subjects") { (requireActivity() as MainActivity).openDetails(SubjectsFragment()) }.tag="study_personal_workspace"
        }, { subjectPanel.label("Your subjects could not be loaded.", 14f) })
        val results = content.findViewWithTag<LinearLayout>("search_results")
        val resume = content.findViewWithTag<LinearLayout>("study_continue")
        val featured = content.findViewWithTag<LinearLayout>("featured_module")
        val starters = content.findViewWithTag<LinearLayout>("reading_starters")
        val graph = (requireActivity().application as StudyPathApplication).graph
        val requestedContext = arguments?.getString("readingContextId")
        val query = content.findViewWithTag<android.widget.EditText>("reading_search_query")
        val search = content.findViewWithTag<MaterialButton>("reading_search_submit")
        val contextLabel = content.findViewWithTag<android.widget.TextView>("reading_context_label")
        val clear = content.findViewWithTag<MaterialButton>("reading_search_clear")
        clear.setOnClickListener {
            model.clearSearch(); query.setText(""); query.clearFocus()
            contextLabel.isVisible = false
            requireContext().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(query.windowToken, 0)
        }
        arguments?.getString("subjectQuery")?.let { topic -> if (!model.state.value.submitted) { query.setText(topic); model.search(graph.readings, topic, graph.repository) } }
        if (requestedContext != null) {
            contextLabel.visibility = View.VISIBLE
            contextLabel.text = "Checking related reading context…"
            query.isEnabled = false
            search.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resolved = withTimeout(12_000) { graph.repository.resolveReadingContext(ReadingContext(requestedContext)) }
                    if (model.selectedContext?.context != resolved.context) model.selectContext(resolved)
                    query.setText(resolved.query)
                    contextLabel.text = "Related reading: ${resolved.query}"
                } catch (error: TimeoutCancellationException) {
                    contextLabel.text = "Related reading context is unavailable. You can search independently above."
                    model.contextUnavailable(failureText(error))
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    contextLabel.text = "Related reading context is unavailable. You can search independently above."
                    model.contextUnavailable(failureText(error))
                } finally { query.isEnabled = true; search.isEnabled = true }
            }
        }
        ReadingStarterSection(starters, viewLifecycleOwner, graph.repository) { page ->
            (requireActivity() as MainActivity).openDetails(ReadingFragment.forPage(page))
        }.start()
        LessonLibrarySection(featured, viewLifecycleOwner, graph.repository,
            editPreferences = { (requireActivity() as MainActivity).openDetails(CoursePacksFragment()) },
            openLesson = { key -> (requireActivity() as MainActivity).openDetails(InteractiveLessonFragment.forPackage(key)) },
        ).start()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { graph.repository.profileName().catch { emit(null) }.collect { name ->
                    content.findViewWithTag<android.widget.TextView>("study_greeting").text=if(name.isNullOrBlank()) "Your study space" else "Hi, ${name.trim().substringBefore(' ')}"
                } }
                launch {
                    combine(graph.repository.savedReadings(), graph.repository.operationStates()) { rows, _ -> rows }
                        .catch { resume.removeAllViews() }.collect { rows ->
                        resume.removeAllViews()
                        val latest = rows.mapNotNull { saved -> runCatching { saved to graph.repository.retainedReading(saved.id) }.getOrNull() }
                            .filter { (_, local) -> local != null && local.deletionEditId == null }
                            .maxByOrNull { (saved, local) -> local?.annotation?.modifiedAt ?: saved.savedAt }
                        val lesson = readyLessonResumes(graph.repository).maxByOrNull { it.updatedAt }
                        val readingUpdated = latest?.let { (saved, local) -> local?.annotation?.modifiedAt ?: saved.savedAt } ?: Long.MIN_VALUE
                        val continueLesson = lesson != null && lesson.updatedAt > readingUpdated
                        content.findViewWithTag<android.widget.TextView>("study_heading").text =
                            if (latest == null && lesson == null) "Your study space" else "Let’s pick it up."
                        if (continueLesson) {
                            val savedLesson = requireNotNull(lesson)
                            resume.continueStudy(savedLesson.title,"Ready offline · Step ${savedLesson.position + 1} of ${savedLesson.totalSteps}","Continue lesson →") {
                                (requireActivity() as MainActivity).openDetails(InteractiveLessonFragment.forPackage(savedLesson.key, savedLesson.lessonId))
                            }.tag="study_continue_lesson"
                        } else latest?.let { (saved, local) ->
                            resume.continueStudy(saved.title,readingCopyStatus(local)+" · "+readingPositionLabel(local?.annotation?.position ?: 0,saved.excerpt.length),"Continue reading →") {
                                (requireActivity() as MainActivity).openDetails(ReadingFragment.forSaved(saved.id))
                            }.tag="study_continue_reading"

                        }
                    }
                }
                model.state.collect { state ->
                    results.removeAllViews()
                    clear.isVisible = state.submitted
                    resume.isVisible = !state.submitted
                    subjectPanel.isVisible = !state.submitted
                    content.findViewWithTag<View>("study_semester").isVisible = !state.submitted
                    content.findViewWithTag<View>("study_hero").isVisible = !state.submitted
                    if(state.submitted) content.findViewWithTag<View>("study_browse_body").isVisible = false
                    content.findViewWithTag<View>("study_browse_toggle").isVisible = !state.submitted
                    featured.visibility = if (state.submitted) View.GONE else View.VISIBLE
                    starters.visibility = if (state.submitted) View.GONE else View.VISIBLE
                    if (state.loading) results.label("Searching…")
                    state.error?.let { results.label(it); if (state.query.isNotBlank()) results.action("Retry search") { model.search(graph.readings, state.query, graph.repository) } }
                    if (state.submitted && !state.loading && state.error == null && state.items.isEmpty()) results.label("No readings found. Try another topic.")
                    if (state.items.isNotEmpty()) results.label("Readings", 20f)
                    state.items.forEach { item ->
                        val card = results.card(item.title, item.description.ifBlank { item.excerpt })
                        card.action("Read excerpt →") { (requireActivity() as MainActivity).openDetails(ReadingFragment.forPage(item.pageKey, state.context)) }
                    }
                }
            }
        }
    }
    companion object {
        fun forSubject(title: String) = StudyFragment().apply { arguments = Bundle().apply { putString("subjectQuery", title.take(120)) } }
        fun forContext(contextId: String) = StudyFragment().apply {
            arguments = Bundle().apply { putString("readingContextId", contextId) }
        }
    }
}
