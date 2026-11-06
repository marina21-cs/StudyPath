package com.studypath.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.data.PersonalWorkspace
import com.studypath.app.domain.*
import java.util.UUID

class SemesterGradingModel:ViewModel() {
    var draft=PersonalGradeDraft()
    var step=0
    val weights=linkedMapOf<String,String>()
    val selected=linkedSetOf<String>()
}

/** Shared full-screen flow, used by both demo and signed-in workspaces. */
class SemesterGradingUi(private val body:LinearLayout,private val state:SemesterGradingModel,
    private val read:()->PersonalWorkspace,private val commit:(PersonalGradeDraft)->Unit,private val done:()->Unit) {
    private var editor:GradeDraftEditor?=null
    fun dismiss() { editor?.dismiss() }
    fun render():Unit = with(body) {
        removeAllViews()
        label("Set up all subjects",26f)
        label("${read().subjects.size} subjects · Step ${state.step+1} of 3",14f)
        when(state.step) {
            0 -> {
                label("Choose your grading system",20f)
                label("Start with how grades are displayed. Next, choose what gets graded and its percentage.",14f)
                card("Grading system",state.draft.scale?.description() ?: "Choose a system to continue")
                editor=GradeDraftEditor(context,state.draft) { state.draft=it;render() }
                compactAction("Choose grading system") { editor!!.chooseScale() }.tag="semester_scale"
                label("Use your syllabus. A passing mark of 75 is different from converting all scores into a 75–100 range.",14f)
                action("Next: categories") { state.step=1;render() }.apply { isEnabled=state.draft.scale!=null;tag="semester_next" }
            }
            1 -> {
                label("Choose what gets graded",20f)
                label("Manual weights · check the categories you use, then enter each percentage. Scores within a new category use total earned ÷ total possible points.",14f)
                val total=label("",18f).apply { tag="semester_weight_total";accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE }
                fun updateTotal() { val sum=state.selected.sumOf { state.weights[it]?.toDoubleOrNull() ?: 0.0 };total.text="Total: ${"%.1f".format(sum)}% / 100%" }
                updateTotal()
                val names=(listOf("Activities","Quizzes","Exams / Tests","Projects","Homework","Participation","Practical / Lab")+
                    read().subjects.flatMap { it.gradeDraft?.categories.orEmpty().map { c -> c.title } }+state.weights.keys).distinct()
                names.forEach { name ->
                    val box=AppCompatCheckBox(context).apply { text=name;minHeight=context.dp(48);isChecked=name in state.selected;tag="semester_category_$name" }
                    addView(box)
                    val weight=field("$name weight (%)",InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL).apply {
                        setText(state.weights[name].orEmpty());tag="semester_weight_$name"
                        doAfterTextChanged { state.weights[name]=it.toString();updateTotal() }
                    }
                    val holder=weight.parent.parent as View
                    holder.visibility=if(box.isChecked) View.VISIBLE else View.GONE
                    box.setOnCheckedChangeListener { _,checked -> if(checked)state.selected.add(name)else state.selected.remove(name);holder.visibility=if(checked)View.VISIBLE else View.GONE;updateTotal() }
                }
                compactAction("Add another category") {
                    val form=context.column();val name=form.field("Category name")
                    MaterialAlertDialogBuilder(context).setTitle("Custom category").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Add") { _,_ ->
                        val title=name.text.toString().trim()
                        if(title.length in 1..80) { state.weights.putIfAbsent(title,"");state.selected.add(title);render() }
                        else Toast.makeText(context,"Use a name of 1–80 characters.",Toast.LENGTH_LONG).show()
                    }.show()
                }
                action("Review all subjects") { safe {
                    val existing=read().subjects.flatMap { it.gradeDraft?.categories.orEmpty() }
                    state.draft=state.draft.copy(categories=state.selected.map { name ->
                        val weight=state.weights[name]?.toDoubleOrNull() ?: error("Enter a percentage for $name.")
                        DraftCategory(UUID.randomUUID().toString(),name,weight/100,existing.firstOrNull { it.title.equals(name,true) }?.aggregation ?: "POOLED_POINTS")
                    })
                    SemesterGrading.apply(read(),state.draft);state.step=2;render()
                } }.tag="semester_review"
                compactAction("Back: grading system") { state.step=0;render() }
            }
            else -> {
                label("Review your semester setup",20f)
                label(state.draft.scale!!.description(),16f)
                state.draft.categories.forEach { label("${it.title} · ${"%.1f".format(it.weight*100)}%",16f) }
                label("Total: 100%",20f)
                label("Applies to all current subjects:",14f)
                read().subjects.forEach { label(it.title,14f) }
                label("Existing scores are kept. Estimates may change with the new weights and scale. Recorded final grades and GWA stay unchanged. You can adjust each subject later.",14f)
                action("Apply to all subjects") { safe { commit(state.draft);done() } }.tag="semester_apply"
                compactAction("Back: categories") { state.step=1;render() }
            }
        }
    }
    private fun safe(action:()->Unit) { runCatching(action).onFailure { MaterialAlertDialogBuilder(body.context).setTitle("Check grading setup").setMessage(it.message).setPositiveButton("Keep editing",null).show() } }
}

class SemesterGradingFragment:Fragment() {
    private val state:SemesterGradingModel by viewModels()
    private var ui:SemesterGradingUi?=null
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View {
        val body=requireContext().column()
        ui=SemesterGradingUi(body,state,{ personalStore().read() },{ template -> personalStore().update { SemesterGrading.apply(it,template) } },{
            Toast.makeText(context,"Grading applied to all subjects",Toast.LENGTH_SHORT).show();parentFragmentManager.popBackStack()
        }).also { it.render() }
        return requireContext().scrolling(body)
    }
    override fun onDestroyView() { ui?.dismiss();ui=null;super.onDestroyView() }
}
