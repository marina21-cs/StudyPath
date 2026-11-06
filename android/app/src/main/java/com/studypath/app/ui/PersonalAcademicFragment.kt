package com.studypath.app.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import android.text.InputType
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.StudyPathApplication
import com.studypath.app.R
import com.studypath.app.data.*
import com.studypath.app.domain.*
import com.studypath.app.ui.planner.PlannerFragment
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class PersonalAcademicFragment:Fragment() {
    private var mode="grades";private var subjectId:String?=null
    private val decimal=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    private var gradeEditor:GradeDraftEditor?=null
    override fun onCreate(s:Bundle?){super.onCreate(s);mode=s?.getString("mode")?:arguments?.getString("mode")?:"grades";subjectId=s?.getString("subject")?:arguments?.getString("subject")}
    override fun onSaveInstanceState(out:Bundle){out.putString("mode",mode);out.putString("subject",subjectId);super.onSaveInstanceState(out)}
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View=requireContext().scrolling(requireContext().column().apply{tag="academic_panel"})
    override fun onViewCreated(v:View,s:Bundle?){render()}
    private fun open(m:String){mode=m;render()}
    private fun render(){
        val b=view?.findViewWithTag<LinearLayout>("academic_panel")?:return;gradeEditor?.dismiss();b.removeAllViews()
        val data=personalStore().read()
        if(mode=="what-if"){scenario(b);return}
        if(mode=="gwa"){gwa(b,data);return}
        if(mode=="map"){map(b,data);return}
        if(data.subjects.isEmpty()){b.giraffe(height=88);b.label("Start with your subjects",26f);b.label("Add a subject or import your COR to set up grades.");b.action("Add subject"){(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.createNew())};return}
        val subject=data.subjects.firstOrNull{it.id==subjectId}?:data.subjects.first().also{subjectId=it.id}
        b.compactAction(subject.title){MaterialAlertDialogBuilder(requireContext()).setTitle("Choose subject").setItems(data.subjects.map{it.title}.toTypedArray()){_,n->subjectId=data.subjects[n].id;render()}.show()}
        gradeEditor=GradeDraftEditor(requireContext(),subject.gradeDraft?:PersonalGradeDraft()){save(subject,it)}
        if(mode=="template"){template(b,subject);return}
        b.label("Every score counts.",26f)
        val draft=subject.gradeDraft?:PersonalGradeDraft()
        val top=b.card("Grading template",if(draft.reviewPrepared)"Review copy prepared · approval not confirmed" else "Personal draft · not approved")
        top.compactAction("Review weights & rules →"){open("template")}.tag="academic_template"
        top.compactAction("Link a reviewed course pack"){chooseCatalog(subject)}
        subject.catalogCourseId?.let{courseId->b.action("Open reviewed grade tools"){(requireActivity() as MainActivity).openDetails(PlannerFragment().apply{arguments=Bundle().apply{putString("courseId",courseId);putBoolean("toolsOnly",true)}})}}
        gradeEditor!!.scores(b){open("template")}
        b.action("Explore what-if →"){open("what-if")}.quietAction()
        b.compactAction("Recorded final grade & units"){(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.open(subject.id,"grades"))}
    }
    private fun save(subject:PersonalSubject,draft:PersonalGradeDraft){draft.validate();personalStore().update{w->w.copy(subjects=w.subjects.map{if(it.id==subject.id)it.copy(gradeDraft=draft)else it})};render()}
    private fun template(b:LinearLayout,subject:PersonalSubject){
        val draft=subject.gradeDraft?:PersonalGradeDraft()
        b.label("Start with your syllabus.",26f)
        b.label(if(draft.reviewPrepared)"Review copy prepared · awaiting external confirmation" else "Draft · no professor approval",14f)
        gradeEditor!!.setup(b)
        b.compactAction("Add / edit scores"){open("grades")}
        val source=b.field("Syllabus source / review notes",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply{setText(draft.syllabus);minLines=2}
        b.compactAction("Save syllabus notes"){runCatching{save(subject,draft.copy(syllabus=source.text.toString().take(4000),reviewPrepared=false))}.onFailure{Toast.makeText(context,it.message,Toast.LENGTH_LONG).show()}}
        b.card("Rules to confirm","Blank scores remain ungraded. Excused work is excluded. Missing-work treatment, conversion, rounding and retakes need reviewed school rules.")
        b.action("Prepare review copy"){
            if(draft.categories.isEmpty()||kotlin.math.abs(draft.categories.sumOf{it.weight}-1.0)>1e-9){Toast.makeText(context,"Category weights must total 100%.",Toast.LENGTH_LONG).show();return@action}
            save(subject,draft.copy(syllabus=source.text.toString().take(4000),reviewPrepared=true))
        }.tag="academic_prepare_review"
        if(draft.reviewPrepared){b.card("Awaiting review","No approval is recorded. Share this draft with your professor; a trusted maintainer must publish the reviewed course policy.");b.action("Share review copy"){
            val text="StudyPath grading draft — ${subject.title}\n"+draft.categories.joinToString("\n"){"${it.title}: ${it.weight*100}% (${it.aggregation})"}+"\n${draft.scale?.description()?:"Zero-based percentage"}\nPassing raw threshold: ${draft.scale?.passingPercent?:"Not configured"}\n${draft.syllabus}\nNot approved. Confirm the grading scale, missing work, rounding and retake rules."
            startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT,text),"Share grading draft"))
        }.quietAction()}
        b.compactAction("Link reviewed course pack"){chooseCatalog(subject)}
        b.compactAction("Back to scores"){open("grades")}
    }
    private fun chooseCatalog(subject:PersonalSubject){viewLifecycleOwner.lifecycleScope.launch{runCatching{withTimeout(10000){(requireActivity().application as StudyPathApplication).graph.repository.courses().first()}}.onSuccess{courses->if(courses.isEmpty()){Toast.makeText(context,"No course packs available. Your draft is kept.",Toast.LENGTH_LONG).show()}else MaterialAlertDialogBuilder(requireContext()).setTitle("Link exact course / term policy").setItems(courses.map{it.title+" · "+it.id}.toTypedArray()){_,n->personalStore().update{w->w.copy(subjects=w.subjects.map{if(it.id==subject.id)it.copy(catalogCourseId=courses[n].id)else it})};render()}.show()}.onFailure{Toast.makeText(context,"Could not load course packs. Retry when connected.",Toast.LENGTH_LONG).show()}}}
    private fun gwa(b:LinearLayout,data:PersonalWorkspace){
        b.label("Your GWA, explained.",26f);b.label("Personal arithmetic estimate. Include only grades on the same scale; apply your school’s retake and exclusion rules.",14f)
        val result=b.card("Included courses","Choose courses below.");val output=result.label("—",40f);val detail=result.label("",14f)
        val included=mutableSetOf<String>();val confirmation=androidx.appcompat.widget.AppCompatCheckBox(requireContext()).apply{text="These grades use the same numeric scale"};b.addView(confirmation)
        fun calculate(){val rows=data.subjects.filter{it.id in included};val units=rows.sumOf{it.units?:0.0};val points=rows.sumOf{(it.units?:0.0)*(it.grade?.toDoubleOrNull()?:0.0)};output.text=if(confirmation.isChecked&&units>0)"%.2f".format(points/units)else "—";detail.text=if(!confirmation.isChecked)"Confirm a common grade scale before calculating."else if(units==0.0)"Select at least one valid course."else "${"%.2f".format(points)} weighted points ÷ $units units"}
        confirmation.setOnCheckedChangeListener{_,_->calculate()}
        data.subjects.forEach{s->val valid=s.grade?.toDoubleOrNull()?.let{it.isFinite()&&it>=0}==true && s.units!=null
            val check=androidx.appcompat.widget.AppCompatCheckBox(requireContext()).apply{text="${s.title}\n${s.units?:"No"} units · Grade ${s.grade?.ifBlank{null}?:"not entered"}";minHeight=context.dp(64);isEnabled=valid;setOnCheckedChangeListener{_,yes->if(yes)included.add(s.id)else included.remove(s.id);calculate()}};b.addView(check)
            if(!valid)b.compactAction("Enter ${s.title} grade / units"){(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.open(s.id,"grades"))}}
        if(data.subjects.isEmpty())b.action("Add subjects"){(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment())}
        calculate()
    }
    private fun scenario(b:LinearLayout){
        b.label("What would it take?",26f);b.label("Hypothetical · does not change recorded scores.",14f)
        val earned=b.field("Weighted points already earned",decimal);val remaining=b.field("Remaining exam weight (%)",decimal);val target=b.field("Target overall (%)",decimal)
        val label=b.label("Hypothetical exam score: 85%",20f);val slider=SeekBar(requireContext()).apply{max=100;progress=85;minimumHeight=context.dp(48);contentDescription="Hypothetical exam score"};b.addView(slider)
        val result=b.card("Projected overall","Enter your assumptions above.");val output=result.label("—",40f);val help=result.label("",14f)
        fun calculate(){runCatching{val e=requireNotNull(earned.text.toString().toDoubleOrNull()){"Enter earned points."};val r=requireNotNull(remaining.text.toString().toDoubleOrNull()){"Enter remaining weight."};val t=requireNotNull(target.text.toString().toDoubleOrNull()){"Enter target."};require(e.isFinite()&&r.isFinite()&&t.isFinite()&&r>0&&r<=100&&e>=0&&e<=100-r&&t in 0.0..100.0){"Use consistent weights and values from 0–100."};output.text="%.2f / 100".format(e+r*slider.progress/100);val needed=(t-e)*100/r;help.text=when{needed>100->"Target needs ${"%.1f".format(needed)}%: unreachable under these assumptions.";needed<=0->"Target already secured under these assumptions.";else->"You need ${"%.1f".format(needed)}% on the remaining exam."}}.onFailure{output.text="—";help.text=it.message}}
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onStartTrackingTouch(s:SeekBar){};override fun onStopTrackingTouch(s:SeekBar){};override fun onProgressChanged(s:SeekBar,p:Int,user:Boolean){label.text="Hypothetical exam score: $p%";calculate()}})
        b.action("Calculate scenario"){calculate()};b.label("Raw percentage scenario. Confirm your exact assessment plan before relying on a target.",12f)
    }
    private fun map(b:LinearLayout,data:PersonalWorkspace){
        b.label("My course path",26f);b.label("Unreviewed personal map · no enrollment eligibility inferred.",14f)
        if(data.subjects.isEmpty()){b.action("Add subjects"){(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment())};return}
        val graph=PrerequisiteMapView(requireContext(),data.subjects){s->MaterialAlertDialogBuilder(requireContext()).setTitle(s.title).setMessage("${s.grade?.let{"Recorded grade: $it"}?:"Grade not recorded"}\nRequires all: "+s.prerequisiteIds.orEmpty().mapNotNull{id->data.subjects.find{it.id==id}?.title}.joinToString().ifEmpty{"No links added"}+"\nOfficial policy: unknown").setPositiveButton("Edit prerequisites"){_,_->(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.open(s.id,"grades"))}.setNegativeButton("Close",null).show()}
        val scroll=HorizontalScrollView(requireContext()).apply{addView(graph)};b.addView(scroll)
        val list=requireContext().column(0);data.subjects.forEach{s->list.card(s.title,"Requires all: "+s.prerequisiteIds.orEmpty().mapNotNull{id->data.subjects.find{it.id==id}?.title}.joinToString().ifEmpty{"No links added"}).compactAction("Edit prerequisites"){(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.open(s.id,"grades"))}}
        b.action("Map / accessible list"){val show=scroll.visibility==View.VISIBLE;scroll.visibility=if(show)View.GONE else View.VISIBLE;list.visibility=if(show)View.VISIBLE else View.GONE}.quietAction();list.visibility=View.GONE;b.addView(list)
    }
    override fun onDestroyView(){gradeEditor?.dismiss();gradeEditor=null;super.onDestroyView()}
    companion object{fun open(mode:String="grades",subject:String?=null)=PersonalAcademicFragment().apply{arguments=Bundle().apply{putString("mode",mode);putString("subject",subject)}}}
}
