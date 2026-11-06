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
import com.studypath.app.data.*
import java.io.File
import com.studypath.app.domain.StudentDemoSession

class StudentDemoViewModel : ViewModel() { val session = StudentDemoSession(); var avatar = "initials"; var grading=SemesterGradingModel();var notesDirectory:File?=null
    override fun onCleared() { notesDirectory?.deleteRecursively() }
}

/** Separate native sandbox. Never obtains the application graph or real-account store. */
class DemoActivity : AppCompatActivity(), DemoReviewerHost {
    private lateinit var model: StudentDemoViewModel
    private val sample get() = model.session
    private var page = "study"
    private var noteArgs:Bundle?=null
    private val history = mutableListOf<String>()
    private lateinit var root: LinearLayout
    private var pdfView:OfflinePdfView?=null
    private var semesterUi:SemesterGradingUi?=null
    private var gradeEditor: GradeDraftEditor? = null
    private fun go(next: String) { history.add(page); page = next; render() }
    private fun rootPage(next: String) { history.clear(); page = next; render() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[StudentDemoViewModel::class.java]
        noteArgs=savedInstanceState?.getBundle("noteArgs")
        page = savedInstanceState?.getString("page") ?: "study"
        history.addAll(savedInstanceState?.getStringArrayList("history").orEmpty())
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if(history.isNotEmpty()) { page = history.removeAt(history.lastIndex); render() } else confirmExit() }
        })
        render()
    }
    override fun onSaveInstanceState(out: Bundle) { out.putBundle("noteArgs",noteArgs);out.putString("page", page); out.putStringArrayList("history", ArrayList(history)); super.onSaveInstanceState(out) }
    private fun render() {
        gradeEditor?.dismiss();semesterUi?.dismiss();pdfView?.clear();pdfView=null
        supportFragmentManager.findFragmentById(R.id.main_content)?.let { supportFragmentManager.beginTransaction().remove(it).commitNow() }
        val shell = installStudyShell()
        root = shell.mainRoot
        shell.connectionStatus.visibility = View.GONE
        val mainPage = page in listOf("study","overview","subjects","grades","library","profile")
        shell.toolbar.title = if(mainPage) "studypath." else when {
            page=="edit-note" -> "Study note"
            page=="semester-grading" -> "Set up grading"
            page=="settings" -> "Settings"
            page=="edit" -> "Edit profile"
            page.startsWith("reviewer:") -> "PDF reviewer"
            page.startsWith("pdf:") -> "Sample PDF"
            page.startsWith("note:") -> "My lesson"
            page.startsWith("subject:") -> "My subjects"
            else -> "Academic toolkit"
        }
        if(!mainPage) { shell.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material); shell.toolbar.navigationContentDescription="Back"; shell.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() } }
        shell.toolbar.menu.add("Open settings").apply { setIcon(R.drawable.ic_settings); setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS); setOnMenuItemClickListener { go("settings"); true } }
        val banner = column(0).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        banner.label("DEMO · Fictional student", 12f).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        banner.setPadding(dp(20),0,dp(12),0)
        banner.addView(com.google.android.material.button.MaterialButton(this,null,com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text="Exit"; isAllCaps=false; minWidth=dp(48); minHeight=dp(48)
            setOnClickListener { confirmExit() }; layoutParams=LinearLayout.LayoutParams(-2,dp(48))
        })
        shell.demoBanner.visibility=View.VISIBLE
        shell.demoBanner.addView(banner,LinearLayout.LayoutParams(-1,-2))
        val body = column(20).apply { tag = "demo_body" }
        shell.mainContent.addView(scrolling(body), android.widget.FrameLayout.LayoutParams(-1,-1))
        when {
            page == "edit-note" -> {
                shell.mainContent.removeAllViews()
                supportFragmentManager.beginTransaction().replace(R.id.main_content,StudyNoteFragment().apply { arguments=noteArgs }).commitNow()
            }
            page == "study" -> study(body)
            page in listOf("overview", "subjects", "grades") -> planner(body)
            page == "profile" -> profile(body)
            page == "edit" -> editProfile(body)
            page == "settings" -> settings(body)
            page == "library" -> { body.libraryHeading("${sample.workspace.materials.count { it.pdf }} offline PDFs · ${sample.workspace.materials.count { !it.pdf }} notes · 5 subjects"); sample.workspace.materials.sortedByDescending { it.pdf }.forEach { m -> body.libraryMaterial(m.title,subject(m.subjectId).title+(if(m.pdf) " · PDF · Offline" else "")) { openMaterial(m.id) } } }
            page == "semester-grading" -> { semesterUi=SemesterGradingUi(body,model.grading,{sample.workspace},sample::applyGrading,{ rootPage("grades") }).also { it.render() } }
            page == "gwa" -> gwa(body)
            page == "scenario" -> scenario(body)
            page == "map" -> map(body)
            page.startsWith("subject:") -> subjectPage(body, subject(page.substringAfter(':')))
            page.startsWith("scores:") -> scores(body, subject(page.substringAfter(':')))
            page.startsWith("template:") -> gradingSetup(body, subject(page.substringAfter(':')))
            page.startsWith("reviewer:") -> {
                shell.mainContent.removeAllViews()
                supportFragmentManager.beginTransaction().replace(R.id.main_content,PdfReviewerFragment.open(page.substringAfter(':'))).commitNow()
            }
            page.startsWith("pdf:") -> {
                val id=page.split(':')[1];val material=demoMaterial(id)
                shell.mainContent.removeAllViews()
                val pdfBody=column(12);shell.mainContent.addView(pdfBody,FrameLayout.LayoutParams(-1,-1))
                pdfBody.label("Original sample · available offline",12f)
                pdfBody.action("Turn this PDF into a reviewer") { go("reviewer:$id") }.tag="demo_pdf_reviewer"
                pdfBody.compactAction("Add note for this page") { editNote(material.subjectId,source=NoteSource(id,pdfView?.currentPageNumber ?: 1)) }.tag="pdf_add_note"
                val notes=sample.workspace.materials.filter { it.source?.materialId==id }
                if(notes.isNotEmpty())pdfBody.compactAction("Page notes (${notes.size})") { MaterialAlertDialogBuilder(this).setTitle("Page notes").setItems(notes.map { "Page ${it.source!!.page}: ${it.title}" }.toTypedArray()) { _,i -> openMaterial(notes[i].id) }.show() }
                pdfView=OfflinePdfView(this,this).also { pdfBody.addView(it,LinearLayout.LayoutParams(-1,0,1f));it.open(demoPdf(id),material.title,page.split(':').getOrNull(2)?.toIntOrNull() ?: 0) }
            }
            page.startsWith("note:") -> {
                val note = sample.workspace.materials.first { it.id == page.substringAfter(':') }
                body.label("Demo study note",12f); body.label(note.title,24f); body.label(subject(note.subjectId).title,14f)
                body.compactAction("Edit note") { editNote(note.subjectId,note.id) }.tag="note_edit"
                if(note.text.isNotBlank())body.label(note.text,16f).setTextIsSelectable(true)
                note.photos.orEmpty().forEach { body.notePhoto(StudyNotes.image(noteDirectory(),it.id),it.caption,this) }
                note.source?.let { source -> body.compactAction("Open PDF page ${source.page}") { go("pdf:${source.materialId}:${source.page-1}") }.tag="note_source" }
                if(note.question.isNotBlank()) { body.label(note.question,18f);val answer=body.label(note.answer).apply { visibility=View.GONE };body.compactAction("Show answer") { answer.visibility=View.VISIBLE } }
                body.compactAction("Back to subject") { go("subject:${note.subjectId}") }
            }
            page == "storage" -> { body.label("Storage & downloads",26f); body.label("Five real PDFs and nine notes are bundled with the app and work offline. Demo reviewers and tasks stay in this demo session."); body.compactAction("Browse sample materials") { go("library") } }
        }
        shell.navigation.visibility = if(mainPage) View.VISIBLE else View.GONE
        shell.navigation.selectedItemId = when(page) { "study" -> R.id.nav_study; "library" -> R.id.nav_saved; "profile" -> R.id.nav_account; else -> R.id.nav_planner }
        shell.navigation.setOnItemSelectedListener { item -> rootPage(when(item.itemId) { R.id.nav_study -> "study"; R.id.nav_saved -> "library"; R.id.nav_account -> "profile"; else -> "overview" }); true }
    }

    fun noteWorkspace()=ViewModelProvider(this)[StudentDemoViewModel::class.java].session.workspace
    fun noteDirectory():File { val state=ViewModelProvider(this)[StudentDemoViewModel::class.java];return state.notesDirectory ?: File(cacheDir,"demo-notes/${PersonalStudyStore.newId()}").apply { mkdirs();state.notesDirectory=this } }
    fun saveStudyNote(note:PersonalMaterial,drafts:File) {
        StudyNotes.commit(noteDirectory(),drafts,note,sample.workspace.materials.firstOrNull { it.id==note.id }) { sample.saveNote(note) }
    }
    fun editNote(subject:String,id:String?=null,source:NoteSource?=null) { noteArgs=StudyNoteFragment.open(subject,id,source=source).arguments;go("edit-note") }
    fun finishNote() { page=if(history.isEmpty())"study"else history.removeAt(history.lastIndex);render() }
    private fun openMaterial(id:String) { go(if(demoMaterial(id).pdf) "pdf:$id" else "note:$id") }
    override fun demoMaterial(id:String)=sample.workspace.materials.first { it.id==id }
    override fun demoPdf(id:String):java.io.File {
        val material=demoMaterial(id);require(material.pdf)
        val directory=java.io.File(cacheDir,"demo-handouts").apply { mkdirs() }
        return java.io.File(directory,"${material.subjectId}.pdf").also { file ->
            if(!file.isFile) assets.open("demo/${material.subjectId}.pdf").use { input -> file.outputStream().use { input.copyTo(it) } }
        }
    }
    override fun demoReviewer(id:String)=sample.workspace.reviewers.orEmpty().firstOrNull { it.materialId==id }
    override fun saveDemoReviewer(pack:com.studypath.app.domain.PdfReviewer) { sample.saveReviewer(pack) }
    override fun addDemoReviewTask(task:com.studypath.app.data.PersonalTask) { sample.addReviewTask(task) }
    override fun showDemoSource(id:String,page:Int) { go("pdf:$id:$page") }
    private fun subject(id: String) = sample.workspace.subjects.first { it.id == id }
    private fun study(b: LinearLayout) {
        b.studyGreeting(sample.name)
        b.continueStudy("Relational database", "Database Systems · authored sample note","Continue reading →") { go("note:relational") }
        b.semesterCard(sample.workspace) { rootPage("overview") }
        b.label("Pick a subject",20f); sample.workspace.subjects.take(2).forEach { row(b,it) }
    }
    private fun row(b: LinearLayout, s: PersonalSubject) {
        b.plannerSubject(s,sample.workspace) { go("subject:${s.id}") }.tag = "demo_subject_${s.id}"
    }
    private fun planner(b: LinearLayout) {
        val pages=listOf("overview","subjects","grades")
        b.plannerHeading("First semester · 2026–27",pages.indexOf(page)) { rootPage(pages[it]) }
        when(page) {
            "overview" -> {
                b.semesterSummary(sample.workspace)
                b.compactAction("Set up grading") { chooseGradingSubject() }.tag="planner_grading_setup"
                b.label("Up next",20f); tasks(b)
                b.compactAction("Continue · Relational database") { go("note:relational") }
            }
            "subjects" -> { sample.workspace.subjects.forEach { row(b,it) }; b.compactAction("Add or import subjects") { info("Your real workspace", "The demo has five fixed subjects. In your account, Planner → Subjects lets you add a subject or import a COR.") } }
            else -> {
                b.compactAction("Set up grading") { chooseGradingSubject() }.tag="planner_grading_setup"
                b.gradeOverview("Sample GWA",sample.gwa()?.let { "%.2f".format(it) } ?: "—", "${sample.included.size} included · ${sample.includedUnits().toInt()} units", { go("gwa") }, { go("scenario") }, { go("map") })
                sample.workspace.subjects.forEach { s -> b.compactAction("${s.title} · ${s.grade}") { go("scores:${s.id}") } }
                b.label("Illustrative grades, not official results.",12f)
            }
        }
    }
    private fun chooseGradingSubject() {
        model.grading=SemesterGradingModel();go("semester-grading")
    }
    private fun tasks(b: LinearLayout, id: String? = null) {
        val rows = sample.workspace.tasks.filter { id == null || it.subjectId == id }
        b.taskRows(sample.workspace,rows,"demo_task_") { task,_ -> sample.toggleTask(task.id); render() }
    }

    private fun subjectPage(b: LinearLayout, s: PersonalSubject) {
        b.label("${s.code} · 3 units",12f); b.label(s.title,26f)
        b.compactAction("Grades & scores") { go("scores:${s.id}") }
        b.label("Next task",20f); tasks(b,s.id); b.label("Materials",20f)
        b.action("Add material") { MaterialAlertDialogBuilder(this).setTitle("Add material").setItems(arrayOf("Write note or add photos", "Browse sample PDFs")) { _,i -> if(i==0)editNote(s.id)else rootPage("library") }.show() }.tag="demo_add_material"
        sample.workspace.materials.filter { it.subjectId == s.id }.forEach { m -> b.compactAction(m.title+(if(m.pdf) " · PDF" else "")) { openMaterial(m.id) } }
    }
    private fun scores(b: LinearLayout, s: PersonalSubject) {
        b.label(s.title,26f); b.label("Sample grading draft · not approved",12f)
        gradeEditor=GradeDraftEditor(this,s.gradeDraft ?: com.studypath.app.domain.PersonalGradeDraft()) { sample.saveDraft(s.id,it); render() }
        gradeEditor!!.previousGrading(b,s.gradeHistory.orEmpty())
        gradeEditor!!.scores(b) { go("template:${s.id}") }
    }
    private fun gradingSetup(b: LinearLayout, s: PersonalSubject) {
        b.label(s.title,26f); b.label("Sample grading setup · not approved",14f)
        gradeEditor=GradeDraftEditor(this,s.gradeDraft ?: com.studypath.app.domain.PersonalGradeDraft()) { sample.saveDraft(s.id,it); render() }
        gradeEditor!!.setup(b)
        b.compactAction("Add / edit scores") { go("scores:${s.id}") }
    }
    override fun onDestroy() { semesterUi?.dismiss();gradeEditor?.dismiss();pdfView?.clear(); super.onDestroy() }

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
        b.profileIdentity(sample.name,"@alex.demo · Fictional student\nBS Information Technology · Year 2",model.avatar) {
            chooseAvatar(this,sample.name,model.avatar,"Only changes this demo profile.") { model.avatar=it; render() }
        }
        b.compactAction("Edit profile") { go("edit") }
        b.card("5 subjects · 15 units", "5 PDFs · 9 notes")
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
    private fun confirmReset() { MaterialAlertDialogBuilder(this).setTitle("Reset demo?").setMessage("Restore the original five subjects, scores and tasks. Your own account is untouched.").setNegativeButton("Keep exploring",null).setPositiveButton("Reset sample data") { _,_-> sample.workspace.materials.filter { it.pdf }.forEach { ViewModelProvider(this)["pdf-reviewer-${it.id}",PdfReviewerModel::class.java].resetDemo() };model.notesDirectory?.deleteRecursively();model.notesDirectory=null;sample.reset();model.avatar="initials";rootPage("overview") }.show() }
    private fun confirmExit() { MaterialAlertDialogBuilder(this).setTitle("Exit demo?").setMessage("Return to your previous screen. Demo changes will be discarded; your account stays signed in.").setNegativeButton("Keep exploring",null).setPositiveButton("Exit demo") { _,_-> finish() }.show() }
}
