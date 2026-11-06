package com.studypath.app.ui

import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.studypath.app.R
import com.studypath.app.data.PersonalWorkspace
import com.studypath.app.ui.planner.PlannerFragment

class PlannerHomeFragment : Fragment() {
    private var selected = 0
    private var workspace = PersonalWorkspace()
    private lateinit var body: LinearLayout
    private lateinit var tabs: TabLayout
    override fun onCreate(s: Bundle?) { super.onCreate(s); selected = s?.getInt("view") ?: 0 }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("view", selected); super.onSaveInstanceState(out) }
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val b = requireContext().column()
        tabs=b.plannerHeading("Your personal semester",selected) { selected=it; if(::body.isInitialized) render() }
        body = requireContext().column(0); b.addView(body)
        return requireContext().scrolling(b)
    }
    override fun onViewCreated(v: View, s: Bundle?) { watchPersonal({ workspace = it; render() }, { body.label("Could not open subjects. Your saved files are kept. Reopen Planner to retry.") }) }
    private fun open(f: Fragment) = (requireActivity() as MainActivity).openDetails(f)
    private fun gradingSetup() {
        body.compactAction("Set up grading") {
            if(workspace.subjects.isEmpty()) open(PersonalWorkspaceFragment.createNew())
            else open(SemesterGradingFragment())
        }.tag="planner_grading_setup"
    }
    private fun render() {
        body.removeAllViews()
        when(selected) {
            0 -> {
                body.semesterSummary(workspace)
                gradingSetup()
                body.label("Up next",20f)
                val tasks=workspace.tasks.sortedWith(compareBy({ it.done },{ it.due.ifEmpty { "9999" } })).take(5)
                body.taskRows(workspace,tasks,"planner_task_") { task,checked ->
                    runCatching { personalStore().update { data -> data.copy(tasks=data.tasks.map { if(it.id==task.id) it.copy(done=checked) else it }) } }
                        .onFailure { android.widget.Toast.makeText(context,failureText(it),android.widget.Toast.LENGTH_LONG).show(); render() }
                }
                workspace.materials.firstOrNull()?.let { m -> body.compactAction("Continue · ${m.title}") { open(PersonalMaterialFragment.open(m.id)) } }
                body.compactAction("View subjects") { tabs.getTabAt(1)?.select() }
            }
            1 -> {
                if (workspace.subjects.isEmpty()) { body.giraffe(height=96); body.label("Add your first subject or import a COR to start your semester.") }
                workspace.subjects.forEach { subject ->
                    body.plannerSubject(subject,workspace) { open(PersonalWorkspaceFragment.open(subject.id)) }.tag = "planner_subject_${subject.id}"
                }
                body.compactAction("Add or import subjects") {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Add subjects").setItems(arrayOf("Add a subject", "Import COR", "Manage subjects")) { _, n ->
                        open(when(n) { 0 -> PersonalWorkspaceFragment.createNew(); 1 -> CorImportFragment(); else -> PersonalWorkspaceFragment() })
                    }.show()
                }
            }
            else -> {
                gradingSetup()
                body.gradeOverview("Personal GWA","—","Choose courses and confirm a common grade scale before calculating.",
                    { open(PersonalAcademicFragment.open("gwa")) },{ open(PersonalAcademicFragment.open("what-if")) },{ open(PersonalAcademicFragment.open("map")) }).tag="planner_gwa"
                workspace.subjects.forEach { subject -> body.compactAction("${subject.title} · ${subject.grade?.ifBlank { null } ?: "Not entered"}") { open(PersonalAcademicFragment.open("grades",subject.id)) } }
                if(workspace.subjects.isEmpty()) body.compactAction("Add a subject for grades") { open(PersonalWorkspaceFragment.createNew()) }
                body.compactAction("Reviewed school grade tools") { open(PlannerFragment().apply { arguments = Bundle().apply { putBoolean("toolsOnly", true) } }) }.tag = "planner_reviewed_tools"
            }
        }
    }
}
