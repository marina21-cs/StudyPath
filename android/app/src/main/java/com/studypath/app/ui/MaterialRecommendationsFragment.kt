package com.studypath.app.ui

import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.studypath.app.StudyPathApplication
import kotlinx.coroutines.launch

/** Reviewed course names only; online results remain supplemental and are never auto-downloaded. */
class MaterialRecommendationsFragment : Fragment() {
    private val model: SearchModel by viewModels()
    private var selected=0
    override fun onCreate(state:Bundle?) { super.onCreate(state); selected=state?.getInt("selected") ?: 0 }
    override fun onSaveInstanceState(state:Bundle) { state.putInt("selected",selected); super.onSaveInstanceState(state) }
    override fun onCreateView(inflater:LayoutInflater,container:ViewGroup?,state:Bundle?):View {
        val body=requireContext().column()
        val names=requireArguments().getStringArrayList("subjects").orEmpty()
        body.label("Find your starting material",26f)
        body.label("Only the reviewed course name is used for searches. Your COR image stays on this phone. Results are suggestions, not school-approved modules.",14f)
        val spinner=Spinner(requireContext()).apply {
            tag="recommend_subject"; contentDescription="Choose a reviewed course"; minimumHeight=context.dp(48)
            adapter=ArrayAdapter(context,android.R.layout.simple_spinner_dropdown_item,names)
            setSelection(selected.coerceIn(0,(names.size-1).coerceAtLeast(0)))
        }
        body.addView(spinner)
        body.compactAction("Search the web for PDFs & modules") {
            names.getOrNull(selected)?.let { name -> requireContext().openSource("https://www.google.com/search?q="+Uri.encode("$name open educational module filetype:pdf")) }
        }.tag="recommend_pdfs"
        body.label("PDF search opens your browser. Check the source and permission to download; nothing is saved automatically.",12f)
        body.label("Suggested Wikipedia readings",20f)
        val results=requireContext().column(0).apply { tag="recommend_results" }; body.addView(results)
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long) {
                selected=position
                val name=names.getOrNull(position) ?: return
                if(model.state.value.query!=name || !model.state.value.submitted) model.search((requireActivity().application as StudyPathApplication).graph.readings,name)
            }
            override fun onNothingSelected(parent:AdapterView<*>?) = Unit
        }
        return requireContext().scrolling(body)
    }
    override fun onViewCreated(view:View,state:Bundle?) {
        val results=view.findViewWithTag<LinearLayout>("recommend_results")
        viewLifecycleOwner.lifecycleScope.launch { model.state.collect { result ->
            results.removeAllViews()
            if(result.loading) results.label("Finding readings for ${result.query}…",14f)
            result.error?.let {
                results.label("Could not load suggestions. Your imported subjects are saved on this phone. Connect and retry, or try a shorter course name in Study.",14f)
                results.action("Retry suggestions") { model.search((requireActivity().application as StudyPathApplication).graph.readings,result.query) }
            }
            if(result.submitted && !result.loading && result.error==null && result.items.isEmpty()) {
                results.label("No close matches. Try a shorter topic in Study.",14f)
                results.compactAction("Edit search") { (requireActivity() as MainActivity).openDetails(StudyFragment.forSubject(result.query)) }
            }
            result.items.forEach { item ->
                results.card(item.title,item.description.ifBlank { item.excerpt }).action("Read excerpt") { (requireActivity() as MainActivity).openDetails(ReadingFragment.forPage(item.pageKey)) }
            }
        } }
    }
    companion object { fun forSubjects(titles:List<String>)=MaterialRecommendationsFragment().apply { arguments=Bundle().apply { putStringArrayList("subjects",ArrayList(titles.take(100))) } } }
}
