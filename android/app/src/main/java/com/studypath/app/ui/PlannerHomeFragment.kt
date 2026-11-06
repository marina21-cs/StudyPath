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
    override fun onCreate(s: Bundle?) { super.onCreate(s); selected = s?.getInt("view") ?: 0 }
    override fun onSaveInstanceState(out: Bundle) { out.putInt("view", selected); super.onSaveInstanceState(out) }
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val b = requireContext().column()
        b.label("Your semester", 26f)
        b.label("One place for your next step.", 14f)
        b.addView(TabLayout(requireContext()).apply {
            listOf("Overview", "Subjects", "Grades").forEachIndexed { n, title -> addTab(newTab().setText(title).setTag(n), n == selected) }
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) { selected = tab.position; if (::body.isInitialized) render() }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        })
        body = requireContext().column(0); b.addView(body)
        return requireContext().scrolling(b)
    }
    override fun onViewCreated(v: View, s: Bundle?) { watchPersonal({ workspace = it; render() }, { body.label("Could not open subjects. Your saved files are kept. Reopen Planner to retry.") }) }
    private fun open(f: Fragment) = (requireActivity() as MainActivity).openDetails(f)
    private fun render() {
        body.removeAllViews()
        when(selected) {
            0 -> {
                body.card("${workspace.subjects.size} subjects · ${workspace.tasks.count { !it.done }} tasks left", "${workspace.materials.size} personal materials")
                body.label("Up next", 20f)
                val tasks = workspace.tasks.filterNot { it.done }.sortedBy { it.due.ifEmpty { "9999" } }.take(5)
                if (tasks.isEmpty()) body.label("You're all caught up. Add tasks from a subject.")
                tasks.forEach { task ->
                    val row = requireContext().column(0).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    val check = androidx.appcompat.widget.AppCompatCheckBox(requireContext()).apply {
                        contentDescription = "Complete ${task.title}"; minWidth = context.dp(48); minHeight = context.dp(48)
                        setOnCheckedChangeListener { _, checked -> if (checked) runCatching {
                            personalStore().update { data -> data.copy(tasks = data.tasks.map { if(it.id == task.id) it.copy(done = true) else it }) }
                        }.onFailure { android.widget.Toast.makeText(context, failureText(it), android.widget.Toast.LENGTH_LONG).show(); isChecked = false } }
                    }
                    row.addView(check)
                    val text = row.compactAction(task.title + "\n" + (workspace.subjects.find { it.id == task.subjectId }?.title ?: "") + " · " + task.due) { open(PersonalWorkspaceFragment.open(task.subjectId)) }
                    text.layoutParams = LinearLayout.LayoutParams(0, -2, 1f); body.addView(row)
                }
                workspace.materials.firstOrNull()?.let { m -> body.compactAction("Continue · ${m.title}") { open(PersonalMaterialFragment.open(m.id)) } }
                body.compactAction("View subjects") { selected = 1; (body.parent as LinearLayout).let { (it.getChildAt(2) as TabLayout).getTabAt(1)?.select() } }
            }
            1 -> {
                if (workspace.subjects.isEmpty()) body.label("Add your first subject or import a COR to start your semester.")
                workspace.subjects.forEach { subject ->
                    body.workspaceRow(subject.title, "${subject.code.orEmpty()} · ${subject.units ?: "—"} units · ${workspace.materials.count { it.subjectId == subject.id }} materials", subjectIcon(subject.categoryLabel()), 0) { open(PersonalWorkspaceFragment.open(subject.id)) }.tag = "planner_subject_${subject.id}"
                }
                body.compactAction("Add or import subjects") {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Add subjects").setItems(arrayOf("Add a subject", "Import COR", "Manage subjects")) { _, n ->
                        open(when(n) { 0 -> PersonalWorkspaceFragment.createNew(); 1 -> CorImportFragment(); else -> PersonalWorkspaceFragment() })
                    }.show()
                }
            }
            else -> {
                body.compactAction("GWA · See calculation") { open(PersonalAcademicFragment.open("gwa")) }.tag = "planner_gwa"
                body.label("Choose courses and confirm a common grade scale before calculating.", 12f)
                val tools = requireContext().column(0).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
                tools.compactAction("What-if") { open(PersonalAcademicFragment.open("what-if")) }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                tools.compactAction("Course map") { open(PersonalAcademicFragment.open("map")) }.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                body.addView(tools); body.label("By subject", 20f)
                workspace.subjects.forEach { s -> body.workspaceRow(s.title, "${s.units ?: "—"} units · Grade ${s.grade?.ifBlank { null } ?: "not entered"}", subjectIcon(s.categoryLabel()), 0) { open(PersonalAcademicFragment.open("grades", s.id)) } }
                if(workspace.subjects.isEmpty()) body.compactAction("Add a subject for grades") { open(PersonalWorkspaceFragment.createNew()) }
                body.compactAction("Reviewed school grade tools") { open(PlannerFragment().apply { arguments = Bundle().apply { putBoolean("toolsOnly", true) } }) }.tag = "planner_reviewed_tools"
            }
        }
    }
}
