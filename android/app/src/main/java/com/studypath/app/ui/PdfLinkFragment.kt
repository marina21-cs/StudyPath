package com.studypath.app.ui

import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.studypath.app.data.*
import kotlinx.coroutines.*
import java.io.File

class PdfLinkFragment : Fragment() {
    private var url=""; private var title="Linked PDF"; private var subjectId:String?=null
    private var sharedUri:String?=null
    private var stage="link"; private var error=""; private var job:Job?=null
    override fun onCreate(state:Bundle?) { super.onCreate(state);url=state?.getString("url")?:arguments?.getString("url").orEmpty();title=state?.getString("title")?:title;subjectId=state?.getString("subject")?:arguments?.getString("subject");sharedUri=state?.getString("sharedPdf")?:arguments?.getString("sharedPdf");if(sharedUri!=null)stage="review" }
    override fun onSaveInstanceState(out:Bundle) { out.putString("sharedPdf",sharedUri);out.putString("url",url);out.putString("title",title);out.putString("subject",subjectId);super.onSaveInstanceState(out) }
    override fun onCreateView(i:LayoutInflater,c:ViewGroup?,s:Bundle?):View=requireContext().scrolling(requireContext().column().apply{tag="pdf_link_panel"})
    override fun onViewCreated(v:View,s:Bundle?) { render() }
    private fun render() {
        val body=view?.findViewWithTag<LinearLayout>("pdf_link_panel")?:return;body.removeAllViews()
        if(stage=="done") {body.card("Available offline",title);body.action("Open PDF"){(requireActivity() as MainActivity).openDetails(PersonalMaterialFragment.open(requireNotNull(savedId)))};return}
        if(stage=="failed") {
            body.label("Download not completed",26f);body.label(error,14f)
            body.action("Retry download") { download() }
            body.action("Edit link") { sharedUri=null;stage="link";render() }.quietAction()
            return
        }
        body.label(if(stage=="review") "Give it a home." else "Found something useful?",26f)
        if(error.isNotEmpty())body.label(error,14f).tag="pdf_link_error"
        if(stage=="progress") {body.label("Downloading…",18f).tag="pdf_download_progress";body.action("Cancel download"){job?.cancel();stage="link";error="Download cancelled. Your link is kept.";render()};return}
        val subjects=personalStore().read().subjects
        if(subjects.isEmpty()){body.label("Add a subject before saving your PDF.");body.action("Add subject"){(requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.createNew())};return}
        if(subjectId !in subjects.map{it.id})subjectId=subjects.first().id
        if(stage=="link") {
            body.label("Paste the PDF’s web address below.")
            val input=body.field("PDF link",android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI).apply{setText(url);tag="pdf_url_input"}
            body.action("Check link →") {url=input.text.toString().trim();runCatching{PdfLinkDownloader.validate(url)}.onSuccess{stage="review";error="";render()}.onFailure{error=it.message.orEmpty();render()}}
            body.card("A link, or a file?","Use a full HTTPS address. A phone folder path needs Import from device.")
        } else {
            body.card("PDF source",if(sharedUri==null)url else "File shared from another app")
            val name=body.field("Material title").apply{setText(title)}
            body.compactAction("Subject: ${subjects.first{it.id==subjectId}.title}") {
                title=name.text.toString().trim()
                com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Save to subject").setItems(subjects.map{it.title}.toTypedArray()){_,n->subjectId=subjects[n].id;render()}.show()
            }
            body.label("PDFs up to 25 MB and 500 pages. Only a validated, stored file is marked available offline.",12f)
            body.action("Download for offline") {title=name.text.toString().trim();if(title.isBlank()||title.length>120){error="Use a title of 1–120 characters.";render()}else download()}.tag="pdf_download"
            body.action("Edit link"){sharedUri=null;stage="link";render()}.quietAction()
        }
    }
    private var savedId:String?=null
    private fun download() {
        val store=personalStore();val subject=requireNotNull(subjectId);val id=PersonalStudyStore.newId();val temp=File(store.directory,"$id.download")
        stage="progress";error="";render()
        job=viewLifecycleOwner.lifecycleScope.launch {
            try {
                if(sharedUri != null) withContext(Dispatchers.IO) {
                    val uri=android.net.Uri.parse(sharedUri);require(uri.scheme=="content") { "Share a readable PDF file." }
                    requireContext().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not read the shared file. Share it again." }
                        java.io.FileOutputStream(temp).use { output ->
                            val buffer=ByteArray(8192);var total=0L
                            while(true) { ensureActive();val count=input.read(buffer);if(count<0)break;total+=count;require(total<=PersonalStudyStore.MAX_PDF_BYTES){"PDFs must be 25 MB or smaller."};output.write(buffer,0,count) };output.fd.sync()
                        }
                    }
                } else {
                PdfLinkDownloader().download(url,temp){bytes,total->activity?.runOnUiThread{view?.findViewWithTag<android.widget.TextView>("pdf_download_progress")?.text=if(total>0)"Downloading… ${bytes*100/total}%" else "Downloading… ${bytes/1024} KB"}}
                }
                withContext(Dispatchers.IO){
                    val header=ByteArray(5);temp.inputStream().use{require(it.read(header)==5&&String(header,Charsets.US_ASCII)=="%PDF-"){"This link did not return a PDF. Try a direct document link."}}
                    ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY).use{d->PdfRenderer(d).use{require(it.pageCount in 1..500){"Use a PDF with 1–500 pages."}}}
                }
                ensureActive();require(personalStore().directory==store.directory){"Account changed. Download cancelled."}
                store.commitPdf(temp,PersonalMaterial(id,subject,title,pdf=true))
                savedId=id;stage="done";render()
            }catch(e:CancellationException){throw e}catch(e:Exception){stage="failed";error=e.message?:"Connection interrupted. Your link is kept; retry when connected.";render()}finally{temp.delete()}
        }
    }
    companion object { fun open(subject:String?=null,url:String="")=PdfLinkFragment().apply{arguments=Bundle().apply{putString("subject",subject);putString("url",url)}} }
}
