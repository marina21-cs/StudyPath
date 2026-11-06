package com.studypath.app.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.studypath.app.data.*
import com.studypath.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.tasks.await
import java.io.File
import java.util.UUID

internal suspend fun reviewerPageImage(file:File,number:Int):Bitmap {
    var rendered:Bitmap?=null
    try { return withContext(Dispatchers.IO) {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { pdf ->
            require(number in 1..pdf.pageCount) { "Choose an existing source page." }
            pdf.openPage(number-1).use { page ->
                val scale=1800f/maxOf(page.width,page.height)
                Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also { bitmap ->
                    rendered=bitmap;bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        } }
    } } catch(error:Exception) { rendered?.recycle();throw error }
}

class PdfReviewerModel:ViewModel() {
    val revision=MutableStateFlow(0)
    var stage="home";var busy=false;var message="";var pageCount=0
    var pages=listOf<ReviewerPage>();var cards=listOf<ReviewerCard>();var editPage=0
    var queue=listOf<String>();var position=0;var revealed=false;var response=""
    private var job:Job?=null
    fun refresh() { revision.value++ }
    fun load(file:File) { if(pageCount>0 || busy)return;busy=true;refresh();job=viewModelScope.launch {
        try { pageCount=withContext(Dispatchers.IO) { ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { require(it.pageCount in 1..500);it.pageCount } } };message="" }
        catch(e:Exception) { if(e is CancellationException)throw e;message="Could not read this PDF. Use an unlocked, readable PDF." }
        finally { busy=false;refresh() }
    } }
    fun scan(file:File,first:Int,last:Int) {
        require(first>=1 && last>=first && last<=pageCount && last-first<5) { "Choose 1–5 pages within this PDF." }
        busy=true;message="Reading selected pages on this phone…";refresh()
        job=viewModelScope.launch {
            val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val found=mutableListOf<ReviewerPage>()
                for(number in first..last) {
                    ensureActive();message="Reading page $number of $last…";refresh()
                    val image=reviewerPageImage(file,number)
                    try {
                        // Let the recognizer release its input before recycling, even when cancelled.
                        val text=withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(image,0)).await().text }
                        ensureActive();require(text.length<=12000) { "Page $number has too much text. Use a smaller section or add manual cards." }
                        found+=ReviewerPage(number,text)
                    } finally { image.recycle() }
                }
                pages=found;editPage=0;stage="text";message=""
            } catch(e:CancellationException) { message="Reading cancelled. Your saved reviewer is unchanged." }
            catch(e:Exception) { message=e.message ?: "Text could not be read. Try clearer pages or manual cards." }
            finally { recognizer.close();busy=false;refresh() }
        }
    }
    fun cancel() { job?.cancel() }
    fun resetDemo() { job?.cancel();stage="home";pages=emptyList();cards=emptyList();queue=emptyList();position=0;revealed=false;response="";message="";refresh() }
    fun practice(pack:PdfReviewer,missed:Boolean) { queue=if(missed)pack.retryIds() else pack.cards.sortedBy { pack.results[it.id]?.understood==true }.map { it.id };position=0;revealed=false;response="";stage="practice";refresh() }
}

interface DemoReviewerHost {
    fun demoMaterial(id:String):PersonalMaterial
    fun demoPdf(id:String):File
    fun demoReviewer(id:String):PdfReviewer?
    fun saveDemoReviewer(pack:PdfReviewer)
    fun addDemoReviewTask(task:PersonalTask)
    fun showDemoSource(id:String,page:Int)
}

/** Offline reviewer derived only from a student's own PDF; no academic score writes. */
class PdfReviewerFragment:Fragment() {
    private val model:PdfReviewerModel by lazy { androidx.lifecycle.ViewModelProvider(if(activity is DemoReviewerHost) requireActivity() else this)["pdf-reviewer-${requireArguments().getString("id")}",PdfReviewerModel::class.java] }
    private var store:PersonalStudyStore?=null
    private val demo get()=activity as? DemoReviewerHost
    private fun pdf()=demo?.demoPdf(material.id) ?: requireNotNull(store).pdf(material.id)
    private fun reviewer()=if(demo!=null) demo!!.demoReviewer(material.id) else requireNotNull(store).reviewer(material.id)
    private fun saveReviewer(pack:PdfReviewer) { if(demo!=null)demo!!.saveDemoReviewer(pack)else requireNotNull(store).saveReviewer(pack) }
    private lateinit var material:PersonalMaterial
    private var dialog:androidx.appcompat.app.AlertDialog?=null
    private var imageJob:Job?=null;private var pageImage:Bitmap?=null
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View=requireContext().scrolling(requireContext().column().apply { tag="pdf_reviewer" })
    override fun onViewCreated(v:View,s:Bundle?) {
        try {
            val id=requireArguments().getString("id")!!
            if(demo!=null) material=demo!!.demoMaterial(id)
            else { store=personalStore();material=requireNotNull(store!!.read().materials.firstOrNull { it.id==id && it.pdf }) { "The original PDF was removed." } }
            model.load(pdf())
            viewLifecycleOwner.lifecycleScope.launch { model.revision.collect { render() } }
        } catch(e:Exception) { (v as ScrollView).getChildAt(0).let { (it as LinearLayout).label(e.message ?: "Reviewer unavailable") } }
    }
    private fun go(stage:String) { model.stage=stage;model.refresh() }
    private fun original(page:Int) { if(demo!=null)demo!!.showDemoSource(material.id,page-1) else (requireActivity() as MainActivity).openDetails(PersonalMaterialFragment.open(material.id,page-1)) }
    private fun safe(action:()->Unit) { runCatching(action).onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() } }
    private fun render() {
        val b=view?.findViewWithTag<LinearLayout>("pdf_reviewer") ?: return
        imageJob?.cancel();pageImage?.recycle();pageImage=null;b.removeAllViews()
        b.label(material.title,14f);b.label("Your PDF, ready to practice.",26f)
        if(model.busy) { b.label(model.message.ifBlank { "Opening PDF…" });b.compactAction("Cancel") { model.cancel() };return }
        if(model.message.isNotBlank()) b.label(model.message,14f)
        when(model.stage) { "text"->textReview(b);"draft"->draftReview(b);"practice"->practice(b);else->home(b) }
    }
    private fun home(b:LinearLayout) {
        if(model.pageCount==0) b.compactAction("Retry opening PDF") { model.load(pdf()) }
        val pack=reviewer()
        if(pack!=null) {
            b.card("Your reviewer","${pack.cards.size} cards · ${pack.results.values.count { it.understood }} marked understood · ${pack.retryIds().size} to revisit")
            b.action("Practice reviewer") { model.practice(pack,false) }.tag="reviewer_practice"
            b.compactAction("Retry missed cards") { model.practice(pack,true) }.apply { isEnabled=pack.retryIds().isNotEmpty();tag="reviewer_retry" }
            b.compactAction("Edit reviewer") { model.pages=pack.pages;model.cards=pack.cards;go("draft") }
            b.compactAction("Plan a review") { planReview() }.tag="reviewer_plan"
        }
        b.label(if(pack==null) "Create a reviewer" else "Replace with a new page selection",20f)
        b.label("Choose up to 5 pages at a time. Check the extracted text, then edit your question drafts. Everything stays on this phone.",14f)
        val first=b.field("First page",InputType.TYPE_CLASS_NUMBER).apply { setText("1");tag="reviewer_first" }
        val last=b.field("Last page",InputType.TYPE_CLASS_NUMBER).apply { setText(minOf(model.pageCount.coerceAtLeast(1),3).toString());tag="reviewer_last" }
        b.label("${model.pageCount} pages · English text drafts · no automatic math interpretation",12f)
        b.action("Read selected pages") { safe { model.scan(pdf(),first.text.toString().toInt(),last.text.toString().toInt()) } }.apply { isEnabled=model.pageCount>0;tag="reviewer_scan" }
        b.compactAction("Make cards manually") { safe {
            val page=first.text.toString().toInt();require(page in 1..model.pageCount) { "Choose an existing first page." }
            model.pages=listOf(ReviewerPage(page,""));model.cards=emptyList();go("draft")
        } }
        b.label("A new draft replaces this PDF's saved reviewer only when you save it. Practice progress is separate from subject grades.",12f)
    }
    private fun textReview(b:LinearLayout) {
        val index=model.editPage;val page=model.pages[index]
        b.label("Check the scanned text",20f)
        b.label("Page ${page.number} · ${index+1} of ${model.pages.size}. Correct missing words and reading order. Check equations against the original.",14f)
        b.compactAction("View original · page ${page.number}") { original(page.number) }
        val extracted=b.field("Extracted text — edit before creating cards",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            setText(page.text);minLines=6;maxLines=12;filters=arrayOf(android.text.InputFilter.LengthFilter(12000));tag="reviewer_extracted"
            doAfterTextChanged { value -> model.pages=model.pages.mapIndexed { n,p -> if(n==index)p.copy(text=value.toString())else p } }
        }
        if(page.text.isBlank()) b.label("No text was found. You can type it here or make a manual original-page card.",14f)
        if(index>0)b.compactAction("Previous extracted page") { model.editPage--;model.refresh() }
        if(index<model.pages.lastIndex)b.compactAction("Next extracted page") { model.editPage++;model.refresh() }
        val checked=AppCompatCheckBox(requireContext()).apply { text="I checked all selected pages against the PDF";tag="reviewer_text_checked" };b.addView(checked)
        b.action("Create question drafts") { safe {
            require(checked.isChecked) { "Check the extracted text on every selected page first." }
            model.cards=ReviewerDrafts.create(model.pages);go("draft")
        } }.tag="reviewer_generate"
        b.compactAction("Use manual cards instead") { model.cards=emptyList();go("draft") }
    }
    private fun draftReview(b:LinearLayout) {
        b.label("Check your reviewer",20f)
        b.label("Questions are extracted from your checked text, not generated explanations. Edit weak questions and confirm each answer. Save only what helps you study.",14f)
        if(model.cards.isEmpty()) b.label("No suitable text questions found. Add a card with your own question and answer.")
        model.cards.forEach { card ->
            val row=b.card(card.prompt,"Answer: ${card.answer}\nSource: page ${card.page}")
            row.compactAction("Edit card") { editCard(card) }.tag="reviewer_edit_${card.id}"
            row.compactAction("View source · page ${card.page}") { original(card.page) }
            row.compactAction("Remove card") { model.cards=model.cards.filterNot { it.id==card.id };model.refresh() }
        }
        b.compactAction("Add a card") { editCard(null) }.apply { isEnabled=model.cards.size<40;tag="reviewer_add" }
        val checked=AppCompatCheckBox(requireContext()).apply { text="I checked these questions and answers against the source";tag="reviewer_cards_checked" };b.addView(checked)
        b.action("Save reviewer") { safe {
            require(checked.isChecked) { "Confirm the questions and answers before saving." }
            val next=PdfReviewer(material.id,model.pages,model.cards);next.validate()
            fun commit() { safe { saveReviewer(next);go("home") } }
            if(reviewer()!=null) MaterialAlertDialogBuilder(requireContext()).setTitle("Replace saved reviewer?").setMessage("This saves your edited cards and resets their practice progress. Your original PDF and grades stay unchanged.").setNegativeButton("Keep editing",null).setPositiveButton("Replace") { _,_->commit() }.show() else commit()
        } }.tag="reviewer_save"
        b.compactAction("Discard draft") { MaterialAlertDialogBuilder(requireContext()).setTitle("Discard unsaved cards?").setNegativeButton("Keep editing",null).setPositiveButton("Discard") { _,_->go("home") }.show() }
    }
    private fun editCard(previous:ReviewerCard?) {
        val form=requireContext().column(16)
        val prompt=form.field("Question",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { setText(previous?.prompt.orEmpty());tag="reviewer_question" }
        val answer=form.field("Answer",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { setText(previous?.answer.orEmpty());tag="reviewer_answer_edit" }
        val page=form.field("Source page",InputType.TYPE_CLASS_NUMBER).apply { setText((previous?.page?:model.pages.first().number).toString()) }
        val image=AppCompatCheckBox(requireContext()).apply { text="Show original page image (diagram or equation)";isChecked=previous?.kind=="IMAGE" };form.addView(image)
        dialog=MaterialAlertDialogBuilder(requireContext()).setTitle("Review card").setView(requireContext().scrolling(form)).setNegativeButton("Cancel",null).setPositiveButton("Keep card",null).create().also { d ->
            d.setOnShowListener { d.getButton(-1).setOnClickListener { runCatching {
                val c=ReviewerCard(previous?.id?:UUID.randomUUID().toString(),page.text.toString().toInt(),prompt.text.toString().trim(),answer.text.toString().trim(),previous?.excerpt.orEmpty(),if(image.isChecked) "IMAGE" else "MANUAL")
                val next=if(previous==null)model.cards+c else model.cards.map { if(it.id==c.id)c else it }
                PdfReviewer(material.id,model.pages,next).validate();model.cards=next
            }.onSuccess { d.dismiss();model.refresh() }.onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() } } };d.show()
        }
    }
    private fun practice(b:LinearLayout) {
        val pack=reviewer() ?: return go("home")
        val id=model.queue.getOrNull(model.position)
        val card=pack.cards.firstOrNull { it.id==id }
        if(card==null) {
            b.giraffe("celebrate",96);b.label("Review complete.",24f)
            b.label("${pack.results.values.count { it.understood }} marked understood · ${pack.retryIds().size} to revisit")
            b.compactAction("Retry missed cards") { model.practice(pack,true) }.isEnabled=pack.retryIds().isNotEmpty()
            b.compactAction("Plan a review") { planReview() };b.compactAction("Back to reviewer") { go("home") };return
        }
        b.label("Card ${model.position+1} of ${model.queue.size} · page ${card.page}",14f)
        b.card(card.prompt,"")
        if(card.kind=="IMAGE") {
            val image=ImageView(requireContext()).apply { adjustViewBounds=true;scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Original PDF page ${card.page}; use View source to zoom" };b.addView(image,LinearLayout.LayoutParams(-1,requireContext().dp(280)))
            imageJob=viewLifecycleOwner.lifecycleScope.launch { try { val bitmap=reviewerPageImage(pdf(),card.page);pageImage=bitmap;image.setImageBitmap(bitmap) } catch(e:CancellationException) { throw e } catch(_:Exception) { image.contentDescription="Preview unavailable. Open the source page." } }
        }
        b.field("Your answer (optional)",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { setText(model.response);tag="reviewer_response";doAfterTextChanged { model.response=it.toString() } }
        if(!model.revealed) b.action("Reveal answer") { model.revealed=true;model.refresh() }.tag="reviewer_reveal"
        else {
            b.card("Check your answer",card.answer).tag="reviewer_answer"
            if(card.excerpt.isNotBlank()) b.label("Source excerpt: ${card.excerpt}",14f)
            b.label("Self-check · this is not an exam score",12f)
            fun record(understood:Boolean) { safe { saveReviewer(pack.record(card.id,understood));model.position++;model.revealed=false;model.response="";model.refresh() } }
            b.action("I understood this") { record(true) }.tag="reviewer_understood"
            b.compactAction("Review again") { record(false) }.tag="reviewer_missed"
        }
        b.compactAction("View source · page ${card.page}") { original(card.page) }.tag="reviewer_source"
        b.compactAction("Finish later") { go("home") }
    }
    private fun planReview() {
        val form=requireContext().column(16);val date=form.field("Review date (YYYY-MM-DD)").apply { setText(java.time.LocalDate.now().plusDays(1).toString()) }
        dialog=MaterialAlertDialogBuilder(requireContext()).setTitle("Plan a review").setMessage("Add a dated task to Planner. This does not enable a notification.").setView(form).setNegativeButton("Cancel",null).setPositiveButton("Add to Planner",null).create().also { d ->
            d.setOnShowListener { d.getButton(-1).setOnClickListener { runCatching {
                val due=java.time.LocalDate.parse(date.text.toString().trim()).toString()
                val task=PersonalTask(PersonalStudyStore.newId(),material.subjectId,"Review · ${material.title}".take(160),due)
                if(demo!=null)demo!!.addDemoReviewTask(task) else requireNotNull(store).update { it.copy(tasks=it.tasks+task) }
            }.onSuccess { d.dismiss();Toast.makeText(context,"Review added to Planner",Toast.LENGTH_SHORT).show() }.onFailure { date.error="Enter a valid date as YYYY-MM-DD" } } };d.show()
        }
    }
    override fun onDestroyView() { dialog?.dismiss();dialog=null;imageJob?.cancel();pageImage?.recycle();pageImage=null;super.onDestroyView() }
    companion object { fun open(id:String)=PdfReviewerFragment().apply { arguments=Bundle().apply { putString("id",id) } } }
}
