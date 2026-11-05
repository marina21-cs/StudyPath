package com.studypath.app.ui

import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Explicit pack selection; a personal subject never masquerades as a published curriculum. */
class CoursePacksFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View = requireContext().scrolling(requireContext().column().apply { tag = "course_packs_body" })
    override fun onViewCreated(view: View, state: Bundle?) { load() }
    private fun load() {
        val body = view?.findViewWithTag<LinearLayout>("course_packs_body") ?: return
        body.removeAllViews(); body.label("Course packs", 24f); val status = body.label("Checking available packs…", 14f)
        val repository = (requireActivity().application as StudyPathApplication).graph.repository
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (options, current) = withTimeout(12000) { repository.learnerPreferenceOptions().first() to repository.learnerPreferences().first() }
                status.text = "Select a published pack for school grade tools."
                val approved = options.filter { it.approved && it.approvalStatus == "APPROVED" }
                if (approved.isEmpty()) {
                    status.text = "No published course packs yet."
                    status.tag = "course_packs_empty"
                    body.workspaceRow("Use your own subjects", "Tasks, materials and recorded grades", com.studypath.app.R.drawable.ic_subject_book, subjectColor("Science")) {
                        (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment())
                    }.tag = "course_packs_personal"
                }
                approved.forEach { option ->
                    val chosen = current.curriculumVersion == option.curriculumVersion && current.supportedBy(option)
                    body.workspaceRow(PreferenceChoices.label(option.curriculumVersion), "${PreferenceChoices.label(option.levelId)} · ${option.subjectIds.size} subjects${if (chosen) " · Selected" else ""}", com.studypath.app.R.drawable.ic_subject_book, subjectColor("Mathematics")) {
                        val ids = option.subjectIds.sorted(); val selected = ids.toMutableSet()
                        MaterialAlertDialogBuilder(requireContext()).setTitle("Choose pack subjects")
                            .setMultiChoiceItems(ids.map(PreferenceChoices::label).toTypedArray(), BooleanArray(ids.size) { true }) { _, index, checked -> if(checked) selected.add(ids[index]) else selected.remove(ids[index]) }
                            .setNegativeButton("Cancel", null).setPositiveButton("Use pack") { _, _ ->
                                if (selected.isEmpty()) { status.text = "Choose at least one subject."; return@setPositiveButton }
                                viewLifecycleOwner.lifecycleScope.launch {
                                    status.text = "Saving pack…"
                                    try {
                                        withTimeout(12000) { repository.saveLearnerPreferences(LearnerPreferences(option.levelId, option.curriculumVersion, selected.toSet(), onboardingComplete = true)) }
                                        load()
                                    } catch(e: CancellationException) { if(e is TimeoutCancellationException) status.text = "Still waiting to save. Reconnect and retry." else throw e }
                                    catch(e: Exception) { status.text = failureText(e) }
                                }
                            }.show()
                    }.tag = "course_pack_${option.curriculumVersion}"
                }
                body.compactAction("Refresh packs") { load() }
                body.compactAction("Learning preferences") { (requireActivity() as MainActivity).openDetails(LearnerPreferencesFragment.editing()) }
            } catch(e: CancellationException) { if(e is TimeoutCancellationException) { status.text = "Could not load packs. Check your connection."; body.compactAction("Retry") { load() } } else throw e }
            catch(e: Exception) { status.text = "Could not load packs."; body.compactAction("Retry") { load() }; body.compactAction("My subjects") { (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment()) } }
        }
    }
}
