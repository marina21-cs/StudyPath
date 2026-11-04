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
        hero.giraffe("wave", 72)
        hero.label("What do you want to understand?", 24f).apply { tag = "study_heading" }
        content.addView(hero)
        content.addView(requireContext().column(0).apply { tag = "study_continue" })
        content.label("", 14f).apply { tag = "reading_context_label"; visibility = View.GONE }
        val query = content.field("Search Wikipedia").apply { tag = "reading_search_query"; setText(model.state.value.query); imeOptions = EditorInfo.IME_ACTION_SEARCH; setSingleLine(true); id = View.generateViewId() }
        generateSequence(query.parent) { it.parent }.filterIsInstance<com.google.android.material.textfield.TextInputLayout>().firstOrNull()?.apply {
            endIconMode = com.google.android.material.textfield.TextInputLayout.END_ICON_CLEAR_TEXT
            setEndIconContentDescription("Clear search")
            setEndIconOnClickListener { content.findViewWithTag<View>("reading_search_clear")?.performClick() }
        }
        val graph = (requireActivity().application as StudyPathApplication).graph
        val api = graph.readings
        val search = content.action("Find readings →") { model.search(api, query.text.toString(), graph.repository) }.apply { tag = "reading_search_submit" }
        query.setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_SEARCH) { search.performClick(); true } else false }
        content.action("Back to my study", model::clearSearch).apply {
            tag = "reading_search_clear"; quietAction(); isVisible = model.state.value.submitted
        }
        val topics = requireContext().column(0).apply { tag = "study_browse_body" }
        val wikiLane = topics.card("Topic starters", "Wikipedia · supplemental reading")
        val topicRow = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Programming" to "Computer programming", "Data structures" to "Data structure", "Databases" to "Relational database").forEach { (title, topic) ->
            val chip = MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = title; isAllCaps = false; textSize = 14f; minHeight = requireContext().dp(48)
                cornerRadius = requireContext().dp(16); setTextColor(requireContext().tint(R.color.sp_ink))
                strokeColor = ColorStateList.valueOf(requireContext().tint(R.color.sp_outline))
                contentDescription = "Search $topic"
                setOnClickListener { query.setText(topic); model.search(api, topic, graph.repository) }
            }
            topicRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = requireContext().dp(8) })
        }
        wikiLane.addView(HorizontalScrollView(requireContext()).apply { isHorizontalScrollBarEnabled = false; addView(topicRow) })
        content.action("Browse topics & lessons") { topics.requestRectangleOnScreen(android.graphics.Rect(0, 0, topics.width, requireContext().dp(120)), true) }.apply { tag = "study_browse"; quietAction() }
        content.addView(topics)
        val results = requireContext().column(0).apply { tag = "search_results" }
        content.addView(results)
        wikiLane.addView(requireContext().column(0).apply { tag = "reading_starters" })
        topics.card("Course lessons", "").addView(requireContext().column(0).apply { tag = "featured_module" })
        return requireContext().scrolling(content)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = (view as android.widget.ScrollView).getChildAt(0) as LinearLayout
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
            editPreferences = { (requireActivity() as MainActivity).openDetails(LearnerPreferencesFragment()) },
            openLesson = { key -> (requireActivity() as MainActivity).openDetails(InteractiveLessonFragment.forPackage(key)) },
        ).start()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
                            if (latest == null && lesson == null) "What do you want to understand?" else "Let’s pick it up."
                        if (continueLesson) {
                            val savedLesson = requireNotNull(lesson)
                            val card = resume.card("Continue learning", "").apply {
                                setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
                                (getChildAt(0) as android.widget.TextView).apply {
                                    textSize = 12f; typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.geist)
                                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, false)
                                }
                            }
                            card.label(savedLesson.title, 20f)
                            card.label("Ready offline", 12f)
                            card.studyMetadata("Saved place", "Step ${savedLesson.position + 1} of ${savedLesson.totalSteps}", "Material", "Practice lesson")
                            card.action("Continue lesson →") {
                                (requireActivity() as MainActivity).openDetails(InteractiveLessonFragment.forPackage(savedLesson.key, savedLesson.lessonId))
                            }.tag = "study_continue_lesson"
                        } else latest?.let { (saved, local) ->
                            val card = resume.card("Continue learning", "").apply {
                                setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(16))
                                (getChildAt(0) as android.widget.TextView).apply {
                                    textSize = 12f; typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.geist)
                                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, false)
                                }
                            }
                            card.label(saved.title, 20f)
                            card.label(readingCopyStatus(local), 12f)
                            card.studyMetadata("Saved place", readingPositionLabel(local?.annotation?.position ?: 0, saved.excerpt.length), "Material", "Wikipedia excerpt")
                            card.action("Continue reading →") {
                                (requireActivity() as MainActivity).openDetails(ReadingFragment.forSaved(saved.id))
                            }.tag = "study_continue_reading"
                        }
                    }
                }
                model.state.collect { state ->
                    results.removeAllViews()
                    clear.isVisible = state.submitted
                    resume.isVisible = !state.submitted
                    content.findViewWithTag<View>("study_hero").isVisible = !state.submitted
                    content.findViewWithTag<View>("study_browse").isVisible = !state.submitted
                    content.findViewWithTag<View>("study_browse_body").isVisible = !state.submitted
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
        fun forContext(contextId: String) = StudyFragment().apply {
            arguments = Bundle().apply { putString("readingContextId", contextId) }
        }
    }
}
