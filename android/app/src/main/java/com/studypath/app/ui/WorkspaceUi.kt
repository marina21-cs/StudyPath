package com.studypath.app.ui

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.google.android.material.tabs.TabLayout
import com.studypath.app.R
import com.studypath.app.data.PersonalSubject
import com.studypath.app.data.PersonalTask
import com.studypath.app.data.PersonalWorkspace
import com.studypath.app.databinding.ActivityMainBinding

/** One shell for real and fictional data. Install insets before attaching a replacement root. */
fun AppCompatActivity.installStudyShell(): ActivityMainBinding {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val shell = ActivityMainBinding.inflate(layoutInflater)
    ViewCompat.setOnApplyWindowInsetsListener(shell.root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    shell.root.doOnAttach { ViewCompat.requestApplyInsets(it) }
    setContentView(shell.root)
    shell.toolbar.setTitleTextAppearance(this, R.style.TextAppearance_StudyPath_Title)
    shell.toolbar.isTitleCentered = false
    shell.navigation.isItemActiveIndicatorEnabled = true
    shell.navigation.menu.apply {
        add(0,R.id.nav_study,0,"Study").setIcon(R.drawable.ic_study).setContentDescription("Study")
        add(0,R.id.nav_saved,1,"Saved").setIcon(R.drawable.ic_saved).setContentDescription("Saved")
        add(0,R.id.nav_planner,2,"Planner").setIcon(R.drawable.ic_plan).setContentDescription("Planner")
        add(0,R.id.nav_account,3,"Account").setIcon(R.drawable.ic_profile).setContentDescription("Account")
    }
    return shell
}

fun LinearLayout.studyGreeting(name: String) {
    giraffe("read",100)
    label(if(name.isBlank()) "Your study space" else "Hi, ${name.trim().substringBefore(' ')}",14f).tag="study_greeting"
    label("Let’s pick it up.",26f).tag="study_heading"
}
fun LinearLayout.continueStudy(title:String, detail:String, actionLabel:String, onOpen:()->Unit) =
    card(title,detail).action(actionLabel,onOpen)
fun LinearLayout.semesterCard(data:PersonalWorkspace, onOpen:()->Unit) =
    card("Your semester", "${data.subjects.size} subjects · ${data.tasks.count { !it.done }} tasks left · ${data.materials.size} materials")
        .compactAction("Open Planner",onOpen)
fun LinearLayout.plannerHeading(subtitle:String, selected:Int, onSelect:(Int)->Unit): TabLayout {
    label("Your semester",26f); label(subtitle,14f)
    return TabLayout(context).also { tabs ->
        listOf("Overview","Subjects","Grades").forEachIndexed { n,title -> tabs.addTab(tabs.newTab().setText(title),n==selected) }
        tabs.addOnTabSelectedListener(object:TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab:TabLayout.Tab) = onSelect(tab.position)
            override fun onTabUnselected(tab:TabLayout.Tab) = Unit
            override fun onTabReselected(tab:TabLayout.Tab) = Unit
        }); addView(tabs)
    }
}
private fun units(value:Double) = if(value%1.0==0.0) value.toInt().toString() else value.toString()
fun LinearLayout.semesterSummary(data:PersonalWorkspace) = card("${data.subjects.size} subjects · ${units(data.subjects.sumOf { it.units ?: 0.0 })} units", "${data.tasks.count { !it.done }} tasks left")
fun LinearLayout.plannerSubject(subject:PersonalSubject, data:PersonalWorkspace, onOpen:()->Unit) =
    workspaceRow(subject.title, "${subject.code.orEmpty()} · ${subject.units?.let(::units) ?: "—"} units · ${data.materials.count { it.subjectId==subject.id }} materials",subjectIcon(subject.categoryLabel()),0,onOpen).apply { tag="subject_${subject.id}" }
fun LinearLayout.taskRows(data:PersonalWorkspace, rows:List<PersonalTask>, tagPrefix:String, onToggle:(PersonalTask,Boolean)->Unit) {
    if(rows.isEmpty()) { giraffe("sleep",96); label("No tasks due. A little breathing room.") }
    else if(rows.all { it.done }) { giraffe("celebrate",96); label("All done. Nice work!") }
    rows.forEach { task -> addView(AppCompatCheckBox(context).apply {
        text=listOf(task.title,data.subjects.find { it.id==task.subjectId }?.title.orEmpty(),task.due).filter { it.isNotBlank() }.joinToString("\n")
        minHeight=context.dp(64); isChecked=task.done; tag=tagPrefix+task.id
        setOnCheckedChangeListener { _,checked -> onToggle(task,checked) }
    }) }
}
fun LinearLayout.gradeOverview(title:String,value:String,detail:String,onCalculation:()->Unit,onScenario:()->Unit,onMap:()->Unit): View {
    val action=card(title,detail).let { it.label(value,36f); it.compactAction("See calculation",onCalculation) }
    compactAction("What-if",onScenario); compactAction("Course map",onMap); label("By subject",20f)
    return action
}
fun LinearLayout.profileIdentity(name:String,subtitle:String,avatar:String,onPicture:()->Unit) {
    profileAvatar(name,avatar,action=onPicture)
    compactAction("Change picture",onPicture)
    label(name,24f).apply { tag="profile_name"; gravity=Gravity.CENTER }
    label(subtitle,14f).gravity=Gravity.CENTER
}
fun LinearLayout.libraryHeading(detail:String) { label("Ready to revisit.",26f); label(detail,14f) }
fun LinearLayout.libraryMaterial(title:String,detail:String,onOpen:()->Unit) = compactAction(title.replace('_',' ')+"\n"+detail,onOpen)
