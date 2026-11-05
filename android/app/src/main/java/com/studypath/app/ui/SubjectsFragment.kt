package com.studypath.app.ui

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.studypath.app.R
import com.studypath.app.data.PersonalWorkspace

/** Subject identities remain names + icons; colors support recognition. */
class SubjectsFragment : Fragment() {
    override fun onCreateView(inflater:LayoutInflater,container:ViewGroup?,state:Bundle?):View = requireContext().scrolling(requireContext().column().apply { tag="subject_library" })
    override fun onViewCreated(view:View,state:Bundle?) {
        watchPersonal({ render(it) }, { view.findViewWithTag<LinearLayout>("subject_library").label("Could not load subjects. Reopen this page to retry.") })
    }
    private fun render(data:PersonalWorkspace) {
        val body=view?.findViewWithTag<LinearLayout>("subject_library") ?: return
        body.removeAllViews(); body.label("A place for every subject",26f)
        body.label("Open your tasks, materials and personal records.",14f)
        val columns=if(resources.configuration.fontScale>1.2f || resources.configuration.screenWidthDp<360) 1 else 2
        data.subjects.chunked(columns).forEach { group ->
            val line=LinearLayout(requireContext()).apply { orientation=LinearLayout.HORIZONTAL; isBaselineAligned=false }
            body.addView(line,LinearLayout.LayoutParams(-1,-2).apply { topMargin=requireContext().dp(12) })
            group.forEach { subject ->
                val card=MaterialCardView(requireContext()).apply {
                    radius=requireContext().dp(24).toFloat(); cardElevation=requireContext().dp(2).toFloat(); strokeWidth=0
                    setCardBackgroundColor(subjectColor(subject.categoryLabel())); isClickable=true; isFocusable=true
                    val count=data.materials.count { it.subjectId==subject.id }
                    contentDescription="${subject.title}, $count materials"; tag="subject_tile_${subject.id}"
                    setOnClickListener { (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.open(subject.id,"lessons")) }
                }
                val inside=requireContext().column(16).apply { minimumHeight=requireContext().dp(164); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
                inside.addView(ImageView(requireContext()).apply {
                    setImageResource(subjectIcon(subject.categoryLabel())); imageTintList=ColorStateList.valueOf(context.tint(R.color.sp_ink))
                    setPadding(requireContext().dp(10),requireContext().dp(10),requireContext().dp(10),requireContext().dp(10))
                    background=android.graphics.drawable.GradientDrawable().apply { setColor(0xAAFFFFFF.toInt()); cornerRadius=requireContext().dp(14).toFloat() }
                },LinearLayout.LayoutParams(requireContext().dp(48),requireContext().dp(48)).apply { bottomMargin=requireContext().dp(12) })
                inside.label(subject.title,16f); inside.label("${data.materials.count { it.subjectId==subject.id }} materials",14f)
                card.addView(inside); line.addView(card,LinearLayout.LayoutParams(0,-1,1f).apply { marginEnd=requireContext().dp(8) })
            }
            if(group.size<columns) line.addView(View(requireContext()),LinearLayout.LayoutParams(0,1,1f))
        }
        if(data.subjects.isEmpty()) {
            body.giraffe(height = 96).tag = "empty_subject_mascot"
            body.card("Your semester starts here","Add a subject, or bring in your course list from a COR.")
        }
        body.action("Add subject") { (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.createNew()) }
        body.compactAction("Import COR") { (requireActivity() as MainActivity).openDetails(CorImportFragment()) }
        if(data.subjects.isNotEmpty()) body.compactAction("Find materials for my subjects") { (requireActivity() as MainActivity).openDetails(MaterialRecommendationsFragment.forSubjects(data.subjects.map { it.title })) }
    }
}
