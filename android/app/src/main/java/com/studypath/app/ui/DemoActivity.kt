package com.studypath.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.studypath.app.R
import com.studypath.app.data.PersonalSubject
import com.studypath.app.domain.StudentDemoSession

class StudentDemoViewModel : ViewModel() { val session = StudentDemoSession(); var avatar = "initials" }

/** Separate native sandbox. Never obtains the application graph or real-account store. */
class DemoActivity : AppCompatActivity() {
    private lateinit var model: StudentDemoViewModel
    private val sample get() = model.session
    private var page = "study"
    private val history = mutableListOf<String>()
    private lateinit var root: LinearLayout
    private fun go(next: String) { history.add(page); page = next; render() }
    private fun rootPage(next: String) { history.clear(); page = next; render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[StudentDemoViewModel::class.java]
        page = savedInstanceState?.getString("page") ?: "study"
        history.addAll(savedInstanceState?.getStringArrayList("history").orEmpty())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(history.isNotEmpty()) { page = history.removeAt(history.lastIndex); render() } else confirmExit() }
        })
        render()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putString("page", page); out.putStringArrayList("history", ArrayList(history)); super.onSaveInstanceState(out) }
    private fun render() {
        root = column(0).apply { setBackgroundColor(tint(R.color.sp_background)) }
        setContentView(root, android.view.ViewGroup.LayoutParams(-1, -1))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()); v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        val header = column(8).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun headerIcon(drawable: Int, description: String, action: () -> Unit) = android.widget.ImageButton(this).apply {
            setImageResource(drawable); contentDescription = description
            imageTintList = android.content.res.ColorStateList.valueOf(tint(R.color.sp_ink))
            val background = android.util.TypedValue(); theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, background, true); setBackgroundResource(background.resourceId)
            setPadding(dp(12),dp(12),dp(12),dp(12)); setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(48),dp(48))
        }
        if(history.isNotEmpty()) header.addView(headerIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material,"Back") { onBackPressedDispatcher.onBackPressed() })
        header.label(if(page == "settings") "Settings" else "studypath.", 22f).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        header.addView(headerIcon(R.drawable.ic_settings,"Open settings") { go("settings") })
        root.addView(header)
        val banner = column(0).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        banner.label("DEMO · Fictional student", 12f).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        banner.setPadding(dp(20),0,dp(12),0)
        banner.addView(com.google.android.material.button.MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text="Exit"; isAllCaps=false; minWidth=dp(48); minHeight=dp(48)
            setOnClickListener { confirmExit() }; layoutParams=LinearLayout.LayoutParams(-2,dp(48))
        })
        root.addView(banner)
        val body = column(20).apply { tag = "demo_body" }
        root.addView(scrolling(body), LinearLayout.LayoutParams(-1, 0, 1f))
        when {
            page == "study" -> study(body)
            page in listOf("overview", "subjects", "grades") -> planner(body)
            page == "profile" -> profile(body)
            page == "edit" -> editProfile(body)
            page == "settings" -> settings(body)
            page == "library" -> { body.label("Ready to revisit.",26f); body.label("9 authored sample notes · 5 subjects"); sample.workspace.materials.forEach { m -> body.compactAction(m.title + "\n" + subject(m.subjectId).title) { go("note:${m.id}") } } }
            page == "gwa" -> gwa(body)
            page == "scenario" -> scenario(body)
            page == "map" -> map(body)
            page.startsWith("subject:") -> subjectPage(body, subject(page.substringAfter(':')))
            page.startsWith("scores:") -> scores(body, subject(page.substringAfter(':')))
            page.startsWith("note:") -> {
                val note = sample.workspace.materials.first { it.id == page.substringAfter(':') }
                body.label("Authored demo note",12f); body.label(note.title,26f); body.label(subject(note.subjectId).title,14f); body.card("Read & reflect",note.text)
                body.compactAction("Back to subject") { go("subject:${note.subjectId}") }
            }
            page == "storage" -> { body.label("Storage & downloads",26f); body.label("9 sample notes are included with the app. No PDFs are downloaded by this demo."); body.compactAction("Browse sample materials") { go("library") } }
        }
        if(page in listOf("study","overview","subjects","grades","library","profile")) {
            val nav = CapsuleBottomNavigationView(this).apply {
                id = R.id.navigation
                menu.add(0, R.id.nav_study, 0, "Study").setIcon(R.drawable.ic_study)
                menu.add(0, R.id.nav_saved, 1, "Saved").setIcon(R.drawable.ic_saved)
                menu.add(0, R.id.nav_planner, 2, "Planner").setIcon(R.drawable.ic_plan)
                menu.add(0, R.id.nav_account, 3, "Account").setIcon(R.drawable.ic_profile)
                selectedItemId = when(page) { "study" -> R.id.nav_study; "library" -> R.id.nav_saved; "profile" -> R.id.nav_account; else -> R.id.nav_planner }
                setOnItemSelectedListener { item -> rootPage(when(item.itemId) { R.id.nav_study -> "study"; R.id.nav_saved -> "library"; R.id.nav_account -> "profile"; else -> "overview" }); true }
            }
            root.addView(nav, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun subject(id: String) = sample.workspace.subjects.first { it.id == id }
    private fun study(b: LinearLayout) {
        b.giraffe("read",height=100)
        b.label("Hi, ${sample.name.substringBefore(' ')}",14f); b.label("Let’s pick it up.",26f)
        b.card("Relational database", "Database Systems · authored sample note").action("Continue reading →") { go("note:relational") }
        b.card("Your semester", "5 subjects · ${sample.workspace.tasks.count { !it.done }} tasks left · 9 materials").compactAction("Open Planner") { rootPage("overview") }
        b.label("Pick a subject",20f); sample.workspace.subjects.take(2).forEach { row(b,it) }
    }
    private fun row(b: LinearLayout, s: PersonalSubject) {
        b.workspaceRow(s.title, "${s.code} · 3 units · ${sample.workspace.materials.count { it.subjectId == s.id }} materials", subjectIcon(s.categoryLabel()), 0) { go("subject:${s.id}") }.tag = "demo_subject_${s.id}"
    }
    private fun planner(b: LinearLayout) {
        b.label("Your semester",26f); b.label("First semester · 2026–27",14f)
        b.addView(TabLayout(this).apply {
            val pages = listOf("overview","subjects","grades")
            pages.forEach { addTab(newTab().setText(it.replaceFirstChar(Char::uppercase)), it == page) }
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(t: TabLayout.Tab) { rootPage(pages[t.position]) }
                override fun onTabUnselected(t: TabLayout.Tab) = Unit
                override fun onTabReselected(t: TabLayout.Tab) = Unit
            })
        })
        when(page) {
            "overview" -> {
                b.card("5 subjects · 15 units", "${sample.workspace.tasks.count { !it.done }} tasks left")
                b.label("Up next",20f); tasks(b)
                b.compactAction("Continue · Relational database") { go("note:relational") }
            }
            "subjects" -> { sample.workspace.subjects.forEach { row(b,it) }; b.compactAction("Add or import subjects") { info("Your real workspace", "The demo has five fixed subjects. In your account, Planner → Subjects lets you add a subject or import a COR.") } }
            else -> {
                b.card("Sample GWA", "${sample.included.size} included · ${sample.includedUnits().toInt()} units").apply {
                    label(sample.gwa()?.let { "%.2f".format(it) } ?: "—",36f); compactAction("See calculation") { go("gwa") }
                }
                b.compactAction("What-if") { go("scenario") }; b.compactAction("Course map") { go("map") }
                b.label("By subject",20f)
                sample.workspace.subjects.forEach { s -> b.compactAction("${s.title} · ${s.grade}") { go("scores:${s.id}") } }
                b.label("Illustrative grades, not official results.",12f)
            }
        }
    }
    private fun tasks(b: LinearLayout, id: String? = null) {
        val rows = sample.workspace.tasks.filter { id == null || it.subjectId == id }
        if(rows.isEmpty()) { b.giraffe("sleep",96); b.label("No tasks due. A little breathing room.") }
        else if(rows.all { it.done }) { b.giraffe("celebrate",96); b.label("All done. Nice work!") }
        rows.forEach { t -> b.addView(AppCompatCheckBox(this).apply {
            text = t.title + "\n" + subject(t.subjectId).title; minHeight = dp(64); isChecked = t.done; tag = "demo_task_${t.id}"
            setOnCheckedChangeListener { _, _ -> sample.toggleTask(t.id); render() }
        }) }
    }
    private fun subjectPage(b: LinearLayout, s: PersonalSubject) {
        b.label("${s.code} · 3 units",12f); b.label(s.title,26f)
        b.compactAction("Grades & scores") { go("scores:${s.id}") }
        b.label("Next task",20f); tasks(b,s.id); b.label("Materials",20f)
        sample.workspace.materials.filter { it.subjectId == s.id }.forEach { m -> b.compactAction(m.title) { go("note:${m.id}") } }
    }
    private fun scores(b: LinearLayout, s: PersonalSubject) {
        b.label(s.title,26f); b.label("Sample grading draft · not approved",12f)
        if(s.id == "calculus") {
            val score = b.field("Quiz 1 score / 20", android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL).apply { setText(sample.quiz.toString()); tag = "demo_quiz" }
            b.label("Quiz 20% · Midterm 82/100, 30% · Final 88/100, 50%",14f)
            b.action("Recalculate estimate") {
                val value = score.text.toString().toDoubleOrNull()
                if(value == null || !value.isFinite() || value !in 0.0..20.0) score.error = "Enter a score from 0 to 20"
                else { sample.setQuiz(value); render() }
            }
            b.card("Draft estimate", "%.1f / 100".format(sample.estimate()))
            b.compactAction("Review template") { info("Calculus sample template", "Quiz 20%, midterm 30%, final 50%. This authored example has no professor approval. Recorded final grades and what-if assumptions remain separate.") }
        } else b.card("Sample final grade", s.grade.orEmpty())
        b.label("Editing an estimate does not change a recorded final grade or GWA.",12f)
    }
    private fun gwa(b: LinearLayout) {
        b.label("Know what counts.",26f); b.label("Illustrative common scale · select courses to include.",14f)
        val result = b.label(sample.gwa()?.let { "%.2f".format(it) } ?: "—",36f).apply { tag = "demo_gwa" }
        val detail = b.label("${sample.includedUnits().toInt()} units included",14f)
        sample.workspace.subjects.forEach { s -> b.addView(AppCompatCheckBox(this).apply {
            text = "${s.title}\n${s.grade} × 3 units"; minHeight=dp(64); isChecked=s.id in sample.included; tag="demo_include_${s.id}"
            setOnCheckedChangeListener { _, checked -> if(checked) sample.included.add(s.id) else sample.included.remove(s.id)
                result.text=sample.gwa()?.let { "%.2f".format(it) } ?: "—"; detail.text="${sample.includedUnits().toInt()} units included" }
        }) }
    }
    private fun scenario(b: LinearLayout) {
        b.label("What would it take?",26f); b.label("Independent sample assumptions. Recorded grades stay unchanged.",14f)
        b.card("Advanced Calculus scenario", "41 weighted points earned · final exam 40% · target 75")
        val score=b.label("Hypothetical final: ${sample.exam}%",20f)
        val result=b.label("%.1f / 100".format(sample.projection()),36f).apply { tag="demo_projection" }
        b.addView(SeekBar(this).apply { max=100; progress=sample.exam; minimumHeight=dp(48); contentDescription="Hypothetical final score"; tag="demo_exam"
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(s:SeekBar) = Unit
                override fun onStopTrackingTouch(s:SeekBar) = Unit
                override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean) { sample.setExam(p);score.text="Hypothetical final: $p%";result.text="%.1f / 100".format(sample.projection()) }
            })
        })
        b.label("You need 85% on the final to reach 75 under these assumptions.")
    }
    private fun map(b: LinearLayout) {
        b.label("See what connects.",26f); b.label("Illustrative links, not a school curriculum.",14f)
        b.addView(HorizontalScrollView(this).apply { addView(PrerequisiteMapView(this@DemoActivity,sample.workspace.subjects) { go("subject:${it.id}") }) })
        b.label("Prerequisites as a list",20f)
        sample.workspace.subjects.forEach { s -> b.compactAction("${s.title}\nRequires: ${s.prerequisiteIds.orEmpty().joinToString { subject(it).title }.ifEmpty { "No demo links" }}") { go("subject:${s.id}") } }
    }
    private fun profile(b: LinearLayout) {
        b.label("Your space.",26f)
        b.profileAvatar(sample.name, model.avatar) {
            chooseAvatar(this, sample.name, model.avatar, "Only changes this demo profile.") { model.avatar=it; render() }
        }
        b.compactAction("Change picture") { chooseAvatar(this,sample.name,model.avatar,"Only changes this demo profile.") { model.avatar=it; render() } }
        b.label(sample.name,24f).gravity=Gravity.CENTER
        b.label("@alex.demo · Fictional student\nBS Information Technology · Year 2",14f).gravity=Gravity.CENTER
        b.compactAction("Edit profile") { go("edit") }
        b.card("5 subjects · 15 units", "9 materials")
        b.compactAction("Settings") { go("settings") }; b.compactAction("Reset demo") { confirmReset() }; b.compactAction("Exit demo") { confirmExit() }
    }
    private fun editProfile(b: LinearLayout) {
        b.label("Make it your space.",26f)
        val name=b.field("Display name").apply { setText(sample.name); filters=arrayOf(android.text.InputFilter.LengthFilter(60)); tag="demo_name" }
        b.label("BS Information Technology · Year 2",14f)
        b.action("Save demo profile") { if(name.text.toString().isBlank()) name.error="Enter a display name" else { sample.rename(name.text.toString()); go("profile") } }
    }
    private fun settings(b: LinearLayout) {
        b.label("Make it yours.",26f); b.appearanceControls()
        b.label("Preferences",20f)
        b.compactAction("Notifications") { info("Demo notifications", "Demo tasks never schedule notifications. Study reminders are available in your real account’s Settings.") }
        b.compactAction("Storage & downloads") { go("storage") }
        b.label("About & policies",20f)
        listOf("Privacy","Terms of use","About StudyPath").forEach { title -> b.compactAction(title) { showAppInformation(this,title) } }
        b.compactAction("Reset demo") { confirmReset() }; b.compactAction("Exit demo") { confirmExit() }
    }
    private fun info(title:String,text:String) { MaterialAlertDialogBuilder(this).setTitle(title).setMessage(text).setPositiveButton("Close",null).show() }
    private fun confirmReset() { MaterialAlertDialogBuilder(this).setTitle("Reset demo?").setMessage("Restore the original five subjects, scores and tasks. Your own account is untouched.").setNegativeButton("Keep exploring",null).setPositiveButton("Reset sample data") { _,_-> sample.reset();model.avatar="initials";rootPage("overview") }.show() }
    private fun confirmExit() { MaterialAlertDialogBuilder(this).setTitle("Exit demo?").setMessage("Return to your previous screen. Demo changes will be discarded; your account stays signed in.").setNegativeButton("Keep exploring",null).setPositiveButton("Exit demo") { _,_-> finish() }.show() }
}
