package com.studypath.app.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.studypath.app.R
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.SavedReading
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class SavedFragment : Fragment() {
    private var shelf = "readings"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shelf = savedInstanceState?.getString("shelf") ?: "readings"
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shelf", shelf)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = requireContext().column()
        content.compactAction("Manage subjects") {
            (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.lessons())
        }.tag = "saved_personal_workspace"
        val tabs = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val readingTab = tabs.action("Readings") {}.apply { tag = "saved_readings_tab" }
        val lessonTab = tabs.action("Materials") {}.apply { tag = "saved_lessons_tab" }
        listOf(readingTab, lessonTab).forEach {
            it.quietAction()
            it.layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = requireContext().dp(4) }
            it.isCheckable = true
        }
        content.addView(tabs)
        val readings = requireContext().column(0).apply { tag = "readings" }
        val modules = requireContext().column(0).apply { tag = "modules" }
        content.addView(readings); content.addView(modules)
        readings.label("Loading your readings…", 14f)
        fun selectShelf(value: String) {
            shelf = value
            readings.isVisible = value == "readings"
            modules.isVisible = value == "lessons"
            listOf(readingTab to "readings", lessonTab to "lessons").forEach { (button, key) ->
                val selected = value == key
                button.isChecked = selected
                button.backgroundTintList = ColorStateList.valueOf(requireContext().tint(if (selected) R.color.sp_action else android.R.color.white))
                button.setTextColor(requireContext().tint(if (selected) android.R.color.white else R.color.sp_ink))
                androidx.core.view.ViewCompat.setStateDescription(button, if (selected) "Selected" else "Not selected")
            }
        }
        readingTab.setOnClickListener { selectShelf("readings") }
        lessonTab.setOnClickListener { selectShelf("lessons") }
        selectShelf(shelf)
        return requireContext().scrolling(content)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val content = (view as android.widget.ScrollView).getChildAt(0) as LinearLayout
        val readings = content.findViewWithTag<LinearLayout>("readings")
        val modules = content.findViewWithTag<LinearLayout>("modules")
        val repository = (requireActivity().application as StudyPathApplication).graph.repository
        val personalMaterials = requireContext().column(0)
        val approvedMaterials = requireContext().column(0)
        modules.addView(personalMaterials); modules.addView(approvedMaterials)
        watchPersonal({ data ->
            personalMaterials.removeAllViews()
            personalMaterials.label("Your materials", 20f)
            content.findViewWithTag<MaterialButton>("saved_lessons_tab").text = "Materials"
            if (data.materials.isEmpty()) {
                personalMaterials.giraffe("read",96)
                personalMaterials.label("Import a PDF or create a study card.", 14f)
                personalMaterials.compactAction("Add material") { (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.lessons()) }
            }
            data.subjects.filter { subject -> data.materials.any { it.subjectId == subject.id } }.forEach { subject ->
                personalMaterials.label(subject.title, 18f)
                data.materials.filter { it.subjectId == subject.id }.take(20).forEach { material ->
                    personalMaterials.workspaceRow(material.title.replace('_',' '), if (material.pdf) "PDF · Offline" else "Study card", subjectIcon(subject.categoryLabel()), subjectColor(subject.categoryLabel())) {
                        (requireActivity() as MainActivity).openDetails(PersonalMaterialFragment.open(material.id))
                    }.tag = "saved_material_${material.id}"
                }
                if (data.materials.count { it.subjectId == subject.id } > 20) personalMaterials.compactAction("All ${subject.title} materials") { (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.open(subject.id, "lessons")) }
            }
            personalMaterials.label("Course downloads", 20f)
        }, { personalMaterials.label("Could not open personal materials. Your files are kept.", 14f) })
        DownloadedLessonsSection(approvedMaterials, viewLifecycleOwner, repository) { key ->
            (requireActivity() as MainActivity).openDetails(InteractiveLessonFragment.forPackage(key))
        }.start()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.savedReadings().catch {
                    readings.removeAllViews(); readings.label("Your reading list is unavailable.", 18f)
                    readings.label(failureText(it), 14f)
                    readings.action("Try again") {
                        parentFragmentManager.beginTransaction().replace(R.id.main_content, SavedFragment()).commit()
                    }
                }.collect { rows ->
                    content.findViewWithTag<MaterialButton>("saved_readings_tab").text = "Readings · ${rows.size}"
                    renderReadings(readings, repository, rows)
                }
            }
        }
    }

    private fun renderReadings(content: LinearLayout, repository: FirebaseStudyRepository, rows: List<SavedReading>) {
        content.removeAllViews()
        if (rows.isEmpty()) {
            content.giraffe("read",96)
            content.card("Keep what helps.", "Save a reading from Study to find it here.")
            content.action("Find a reading →") {
                requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.navigation).selectedItemId = R.id.nav_study
            }
        } else {
            val sorted = rows.sortedByDescending { saved -> runCatching { repository.retainedReading(saved.id)?.annotation?.modifiedAt }.getOrNull() ?: saved.savedAt }
            sorted.forEachIndexed { index, saved ->
                val local = runCatching { repository.retainedReading(saved.id) }
                val retained = local.getOrNull()
                val position = if (retained == null) "Open to check your saved place" else readingPositionLabel(retained.annotation?.position ?: 0, saved.excerpt.length)
                val panel = content.card(saved.title, position)
                panel.label(when {
                    local.isFailure -> "Couldn’t open. Your reading is kept."
                    retained?.deletionEditId == null && retained?.snapshotSync == com.studypath.app.data.ReadingSync.CONFIRMED -> "Ready offline"
                    else -> readingCopyStatus(retained)
                }, 14f)
                panel.action(if (index == 0) "Resume reading →" else "Open excerpt →") {
                    (requireActivity() as MainActivity).openDetails(ReadingFragment.forSaved(saved.id))
                }.apply { tag = "saved_reading_${saved.id}"; if (index > 0) quietAction() }
            }
        }
        val details = requireContext().column(12).apply {
            label("${rows.size} of 20 readings saved.", 14f)
        }
        content.studyDisclosure("Storage details", details, "saved_storage_details")
    }
}
