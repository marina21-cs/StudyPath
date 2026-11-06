package com.studypath.app.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.R
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.ReadingContent
import com.studypath.app.data.ReadingContext
import com.studypath.app.data.ReadingSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class ReadingFragment : Fragment() {
    private var notesExpanded = false
    private var noteDraft: String? = null
    private var noteReadingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notesExpanded = savedInstanceState?.getBoolean("notesExpanded") ?: false
        noteDraft = savedInstanceState?.getString("noteDraft")
        noteReadingId = savedInstanceState?.getString("noteReadingId")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("notesExpanded", notesExpanded)
        outState.putString("noteDraft", view?.findViewWithTag<android.widget.EditText>("reading_note")?.text?.toString() ?: noteDraft)
        outState.putString("noteReadingId", noteReadingId)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        requireContext().scrolling(requireContext().column())

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = (view as ScrollView).getChildAt(0) as LinearLayout
        val graph = (requireActivity().application as StudyPathApplication).graph
        fun load() {
            content.removeAllViews(); content.label("Opening reading…", 24f)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val savedId = arguments?.getString("savedId")
                    val draftReading = noteReadingId?.let { graph.repository.retainedReading(it)?.saved?.reading }
                    val reading = if (savedId != null) withTimeout(12_000) { graph.repository.openReading(savedId).reading }
                        else draftReading ?: graph.readings.fetchReading(requireArguments().getString("page")!!)
                    content.removeAllViews(); render(content, reading, savedId)
                } catch (error: TimeoutCancellationException) { content.removeAllViews(); content.label(failureText(error)); content.action("Try again", ::load); sourceFallback(content) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { content.removeAllViews(); content.label(failureText(error)); content.action("Try again", ::load); sourceFallback(content) }
            }
        }
        load()
    }

    private fun sourceFallback(content: LinearLayout) {
        val key = arguments?.getString("page") ?: return
        val encoded = java.net.URLEncoder.encode(key, "UTF-8").replace("+", "%20")
        content.action("Open on Wikipedia") { requireContext().openSource("https://en.wikipedia.org/wiki/$encoded") }.quietAction()
    }

    private fun render(content: LinearLayout, reading: ReadingContent, savedId: String?) {
        val repository = (requireActivity().application as StudyPathApplication).graph.repository
        val id = savedId ?: "wiki_${reading.pageId}_${reading.revisionId}"
        if (noteReadingId != id) noteDraft = null
        noteReadingId = id
        val readingContext = arguments?.getString("contextId")?.let { ReadingContext(it) }
        val retained = repository.retainedReading(id)
        var savedPosition = retained?.annotation?.position ?: 0
        var currentPosition = savedPosition
        var observedNote = retained?.annotation?.note.orEmpty()
        var removalRequested = retained?.deletionEditId != null
        var removalFinished = false
        var writing = false
        var hasPlaceFeedback = false
        val scroll = content.parent as ScrollView

        content.label(reading.title, 28f)
        val sourceRow = requireContext().column(0).apply {
            orientation = if (resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            tag = "reading_metadata"
        }
        content.addView(sourceRow)
        sourceRow.label("Wikipedia excerpt", 14f).apply {
            if (sourceRow.orientation == LinearLayout.HORIZONTAL) layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        sourceRow.action("Source & license") {
            requireContext().showReadingSource("Source & license",
                "${reading.attribution}\n\n${reading.licenseTitle}\n${reading.modificationNotice}\n\nRevision ${reading.revisionId}\nRevision date: ${reading.revisionTimestamp}\nRetrieved ${dateLabel(reading.retrievedAt)}",
                reading.revisionUrl, reading.licenseUrl)
        }.apply {
            tag = "reading_source"; quietAction()
            if (sourceRow.orientation == LinearLayout.HORIZONTAL) layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        readingContext?.let { content.label("Related reading", 14f) }
        if(savedId != null) content.compactAction("Open cleaned live copy") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Open a fresh reading?")
                .setMessage("The cleaned article opens separately. Your saved excerpt, notes and reading position are kept unchanged.")
                .setPositiveButton("Open clean article") { _, _ -> (requireActivity() as MainActivity).openDetails(FullArticleFragment.open(reading.pageKey, reading.sourceUrl)) }
                .setNegativeButton("Keep reading",null).show()
        }
        content.action("Read full article") {
            (requireActivity() as MainActivity).openDetails(FullArticleFragment.open(reading.pageKey, reading.sourceUrl))
        }.quietAction().tag = "reading_full_article"
        content.action("Make a study card") {
            (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.forReading(reading.title))
        }.quietAction().tag = "reading_study_card"

        val excerpt = content.label(reading.excerpt).apply {
            tag = "reading_excerpt"; setTextIsSelectable(true)
            background = GradientDrawable().apply { setColor(context.tint(R.color.sp_card)); cornerRadius = context.dp(24).toFloat() }
            setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
            setLineSpacing(context.dp(5).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12); bottomMargin = context.dp(12) }
        }
        content.label("End of excerpt · continue with Read full article", 14f)
        val status = content.label(readingCopyStatus(retained), 14f).apply {
            tag = "reading_copy_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isVisible = retained != null
        }
        val place = content.action(if (retained == null) "Save for offline" else "Save my place") {}.apply { tag = "reading_save_place" }
        val positionStatus = content.label("Saved place: ${readingPositionLabel(savedPosition, reading.excerpt.length)}", 14f).apply {
            tag = "reading_position_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            isVisible = retained?.annotation != null
        }
        val metadata = content.context.column(0).apply { tag = "reading_management"; isVisible = notesExpanded }
        val toggle = content.action("My note") {}.apply {
            tag = "reading_management_toggle"; quietAction()
            iconGravity = MaterialButton.ICON_GRAVITY_END; iconSize = context.dp(20)
        }
        fun updateDisclosure() {
            toggle.setIconResource(if (notesExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
            ViewCompat.setStateDescription(toggle, if (notesExpanded) "Expanded" else "Collapsed")
        }
        updateDisclosure()
        content.addView(metadata)
        val note = metadata.field("Personal note", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            tag = "reading_note"; minLines = 3; maxLines = 8; filters = arrayOf(InputFilter.LengthFilter(4000))
            setText(noteDraft ?: observedNote)
        }
        val noteStatus = metadata.label("", 14f).apply {
            tag = "reading_note_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        toggle.setOnClickListener {
            notesExpanded = !notesExpanded
            if (!notesExpanded) {
                note.clearFocus()
                requireContext().getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(note.windowToken, 0)
            }
            metadata.isVisible = notesExpanded
            updateDisclosure()
        }

        fun excerptTop(): Int {
            val textLocation = IntArray(2); val scrollLocation = IntArray(2)
            excerpt.getLocationInWindow(textLocation); scroll.getLocationInWindow(scrollLocation)
            return textLocation[1] - scrollLocation[1] + scroll.scrollY
        }
        fun positionNow(): Int {
            val layout = excerpt.layout ?: return currentPosition
            val visibleTextY = scroll.scrollY - excerptTop() - excerpt.compoundPaddingTop
            if (scroll.scrollY >= excerptTop() && scroll.scrollY < excerptTop() + excerpt.height) {
                currentPosition = layout.getLineStart(layout.getLineForVertical(visibleTextY.coerceAtLeast(0)))
                    .coerceIn(0, reading.excerpt.length)
            }
            return currentPosition
        }
        fun finishRemoval() {
            if (removalFinished) return
            removalFinished = true
            if (isAdded && !parentFragmentManager.isStateSaved) parentFragmentManager.popBackStack()
        }
        lateinit var saveNote: MaterialButton
        lateinit var retry: MaterialButton
        lateinit var copy: MaterialButton
        val readingToolbar = requireActivity().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        fun updateEnabled() {
            if (!isAdded || view == null || repository.currentSession() == null) return
            val hasCopy = repository.retainedReading(id) != null
            place.text = if (hasCopy) "Save my place" else "Save for offline"
            place.isEnabled = !writing && !removalRequested
            note.isEnabled = hasCopy && !removalRequested
            saveNote.isEnabled = hasCopy && !writing && !removalRequested
            val local = repository.retainedReading(id)
            status.isVisible = local != null || writing || status.text != readingCopyStatus(null)
            positionStatus.isVisible = local?.annotation != null || writing || hasPlaceFeedback
            retry.isEnabled = local?.annotation != null && !writing && !removalRequested && local.annotationSync != ReadingSync.CONFIRMED
            copy.isEnabled = !writing && !removalRequested
            content.findViewWithTag<View>("reading_reminder")?.isEnabled = hasCopy && !removalRequested
            content.findViewWithTag<View>("reading_remove")?.isVisible = hasCopy
            toggle.contentDescription = if (observedNote.isBlank()) "My note" else "My note · saved note available"
            toggle.setCompoundDrawablesRelativeWithIntrinsicBounds(if (observedNote.isBlank()) 0 else R.drawable.ic_saved, 0, 0, 0)
            readingToolbar.invalidateMenu()
        }
        fun savePersonalState(includeDraft: Boolean) {
            if (writing || removalRequested) return
            val position = positionNow()
            // Saving a place must not silently submit a note the student is still drafting.
            val text = if (includeDraft) note.text.toString() else observedNote
            hasPlaceFeedback = true
            writing = true
            positionStatus.text = "Saving place on this phone…"
            if (includeDraft) noteStatus.text = "Saving note on this phone…"
            updateEnabled()
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (repository.retainedReading(id) == null) {
                        try { withTimeout(8_000) { repository.saveReading(reading, context = readingContext) } }
                        catch (error: CancellationException) { if (error !is TimeoutCancellationException) throw error }
                        catch (error: Exception) { if (repository.retainedReading(id) == null) throw error }
                    }
                    require(repository.retainedReading(id) != null) { "The reading copy could not be kept on this phone. Try again." }
                    withTimeout(8_000) { repository.saveReadingAnnotation(id, text, position) }
                    positionStatus.text = "Place saved · ${readingPositionLabel(position, reading.excerpt.length)}"
                    if (includeDraft) noteStatus.text = "Note saved"
                } catch (error: TimeoutCancellationException) {
                    positionStatus.text = "Place saved on this phone"
                    if (includeDraft) noteStatus.text = "Note saved on this phone"
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    val local = repository.retainedReading(id)?.annotation
                    positionStatus.text = if (local?.position == position) "Place kept on this phone · ${failureText(error)}" else failureText(error)
                    if (includeDraft) noteStatus.text = if (local?.note == text) "Note kept on this phone · ${failureText(error)}" else failureText(error)
                } finally { writing = false; updateEnabled() }
            }
        }
        place.setOnClickListener { savePersonalState(false) }
        saveNote = metadata.action("Save note") { savePersonalState(true) }.apply { tag = "reading_save_note"; quietAction() }
        val tools = requireContext().column(0)
        copy = tools.action(if (retained == null) "Keep excerpt offline" else "Save to account") {}.apply {
            tag = "reading_save_copy"; quietAction()
        }
        copy.setOnClickListener {
            writing = true; updateEnabled(); status.text = "Saving…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withTimeout(20_000) { repository.saveReading(reading, context = readingContext) }
                    status.text = "Ready offline"
                } catch (error: TimeoutCancellationException) {
                    status.text = if (repository.retainedReading(id) != null) "Saved on this phone" else "Could not keep a reading copy on this phone. Try again."
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { status.text = failureText(error) }
                finally { writing = false; updateEnabled() }
            }
        }
        retry = tools.action("Save note & place again") {
            viewLifecycleOwner.lifecycleScope.launch {
                hasPlaceFeedback = true
                writing = true; updateEnabled()
                try {
                    withTimeout(8_000) { repository.syncReadingAnnotation(id) }
                    positionStatus.text = "Place saved · ${readingPositionLabel(savedPosition, reading.excerpt.length)}"
                    noteStatus.text = "Note saved"
                } catch (error: TimeoutCancellationException) { positionStatus.text = "Place saved on this phone"; noteStatus.text = "Note saved on this phone" }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { positionStatus.text = failureText(error) }
                finally { writing = false; updateEnabled() }
            }
        }.apply { tag = "reading_retry"; quietAction() }
        tools.action("Resume saved position") {
            excerpt.post { excerpt.layout?.let { layout -> scroll.smoothScrollTo(0, excerptTop() + excerpt.compoundPaddingTop + layout.getLineTop(layout.getLineForOffset(savedPosition.coerceIn(0, reading.excerpt.length)))) } }
        }.apply { tag = "reading_resume"; quietAction() }
        tools.action("Add study reminder") {
            createStudyReminder(com.studypath.app.data.ReminderTarget.SavedReading(id), reading.title)
        }.apply { tag = "reading_reminder"; quietAction(); isEnabled = retained != null }
        tools.action("Remove saved reading") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Remove saved reading?")
                .setMessage("This removes the reading, note and saved place.")
                .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        status.text = "Removing… · still on this phone"
                        try { withTimeout(8_000) { repository.deleteReading(id) }; finishRemoval() }
                        catch (error: TimeoutCancellationException) { status.text = "Removing… · still on this phone" }
                        catch (error: CancellationException) { throw error }
                        catch (error: Exception) { status.text = "Couldn’t remove. ${failureText(error)}" }
                    }
                }.show()
        }.apply { tag = "reading_remove"; quietAction(); isVisible = retained != null }
        content.studyDisclosure("Reading options", tools, "reading_options")

        val placeMenuId = View.generateViewId()
        readingToolbar.addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
                menu.add(Menu.NONE, placeMenuId, Menu.NONE, "Save place").apply {
                    setIcon(R.drawable.ic_saved); contentDescription = "Save place"
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            override fun onPrepareMenu(menu: Menu) { menu.findItem(placeMenuId)?.isEnabled = !writing && !removalRequested }
            override fun onMenuItemSelected(item: MenuItem): Boolean {
                if (item.itemId != placeMenuId) return false
                savePersonalState(false); return true
            }
        }, viewLifecycleOwner, Lifecycle.State.STARTED)
        updateEnabled()
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> positionNow() }
        if (savedId != null && savedPosition > 0) excerpt.post {
            excerpt.layout?.let { layout -> scroll.scrollTo(0, excerptTop() + excerpt.compoundPaddingTop + layout.getLineTop(layout.getLineForOffset(savedPosition.coerceIn(0, reading.excerpt.length)))) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repository.readingAnnotation(id).catch { hasPlaceFeedback = true; positionStatus.text = failureText(it); positionStatus.isVisible = true }.collect { (annotation, sync) ->
                if (annotation != null) {
                    savedPosition = annotation.position
                    if (!note.hasFocus() && note.text.toString() == observedNote) note.setText(annotation.note)
                    observedNote = annotation.note
                    val suffix = when (sync) {
                        ReadingSync.LOCAL -> "saved on this phone"
                        ReadingSync.PENDING -> "saved on this phone"
                        ReadingSync.CONFIRMED -> "saved"
                        ReadingSync.REJECTED -> "saved on this phone · try again"
                    }
                    positionStatus.text = "Place · $suffix · ${readingPositionLabel(savedPosition, reading.excerpt.length)}"
                    noteStatus.text = if (annotation.note.isBlank()) "" else "Note · $suffix"
                    updateEnabled()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            repository.readingLocalState(id).catch { status.text = failureText(it); status.isVisible = true }.collect { local ->
                status.text = readingCopyStatus(local)
                if (local?.deletionEditId != null) {
                    removalRequested = true
                    if (local.deletionSync == ReadingSync.REJECTED) status.text = "Couldn’t remove. Your reading and note are kept."
                    updateEnabled()
                } else if (removalRequested && local == null) finishRemoval()
                else updateEnabled()
            }
        }
    }

    companion object {
        fun forPage(page: String, context: ReadingContext? = null) = ReadingFragment().apply {
            arguments = Bundle().apply { putString("page", page); context?.let { putString("contextId", it.contextId) } }
        }
        fun forSaved(id: String) = ReadingFragment().apply { arguments = Bundle().apply { putString("savedId", id) } }
    }
}
