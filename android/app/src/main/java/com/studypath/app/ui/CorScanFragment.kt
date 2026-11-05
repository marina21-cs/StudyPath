package com.studypath.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.*
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.studypath.app.data.CorCrop
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.File

class CorScanModel : ViewModel() {
    var bitmap: Bitmap? = null
    var crop = CorCrop()
    var turns = 0
    var page = 0
    var pages = 1
    var busy = false
    var error: String? = null
}

/** Only the selected pixels enter the bundled on-device recognizer. Nothing uploads the COR. */
class CorScanFragment : Fragment() {
    private val model: CorScanModel by viewModels()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if(model.bitmap==null && state!=null) { model.page=state.getInt("page"); model.turns=state.getInt("turns"); state.getFloatArray("crop")?.let { model.crop=CorCrop(it[0],it[1],it[2],it[3]) } }
    }
    override fun onSaveInstanceState(state: Bundle) { state.putInt("page",model.page); state.putInt("turns",model.turns); model.crop.let { state.putFloatArray("crop",floatArrayOf(it.left,it.top,it.right,it.bottom)) }; super.onSaveInstanceState(state) }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View = requireContext().scrolling(requireContext().column().apply { tag="cor_scan" })
    override fun onViewCreated(view: View, state: Bundle?) { if(model.bitmap==null) load() else render() }
    private fun load() {
        val context=requireContext().applicationContext
        val uri=Uri.parse(requireArguments().getString("uri"))
        model.busy=true; model.error=null; render()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (bitmap,count)=withContext(Dispatchers.IO) {
                    val file=File.createTempFile("cor-scan-",".tmp",context.cacheDir)
                    try {
                        context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                            val b=ByteArray(8192); var total=0
                            while(true) { val n=input.read(b); if(n<0)break; total+=n; require(total<=25*1024*1024) { "Choose a document smaller than 25 MB." }; output.write(b,0,n) }
                        } } ?: error("Could not open this document. Choose it again.")
                        val pdf=file.inputStream().use { ByteArray(5).let { header -> it.read(header)==5 && String(header,Charsets.US_ASCII)=="%PDF-" } }
                        if(pdf) {
                            ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                                require(renderer.pageCount in 1..500) { "Use a PDF with 1–500 pages." }
                                renderer.openPage(model.page.coerceIn(0,renderer.pageCount-1)).use { page ->
                                    val scale=1800f/maxOf(page.width,page.height)
                                    val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                                    bitmap.eraseColor(Color.WHITE); page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    bitmap to renderer.pageCount
                                }
                            } }
                        } else {
                            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(file.path,bounds)
                            require(bounds.outWidth>0 && bounds.outHeight>0) { "Use a JPG, PNG, WebP image or an unlocked PDF." }
                            var sample=1; while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1800)sample*=2
                            val bitmap=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample }) ?: error("Could not decode the image.")
                            bitmap to 1
                        }
                    } finally { file.delete() }
                }
                model.bitmap=if(model.turns==0) bitmap else Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { postRotate(model.turns*90f) },true); model.pages=count
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { model.error=e.message ?: "Could not open this document. Try an unlocked PDF or image." }
            finally { model.busy=false; render() }
        }
    }
    private fun render() {
        val body=view?.findViewWithTag<LinearLayout>("cor_scan") ?: return
        body.removeAllViews()
        body.label("Select the course names",26f)
        body.label("Step 2 · Drag the blue corners around only the course-name column. Leave your name, ID and grades outside.",14f)
        if(model.busy) { body.label("Reading on this phone…",16f); return }
        model.error?.let { body.label(it,14f); body.action("Try again") { load() }; return }
        val bitmap=model.bitmap ?: return
        body.addView(CorCropView(requireContext(),bitmap,model.crop) { model.crop=it }.apply { tag="cor_crop" },LinearLayout.LayoutParams(-1,requireContext().dp(360)))
        body.label("Page ${model.page+1} of ${model.pages}",14f)
        if(model.pages>1) {
            body.compactAction("Previous page") { model.page--; load() }.isEnabled=model.page>0
            body.compactAction("Next page") { model.page++; load() }.isEnabled=model.page+1<model.pages
        }
        body.compactAction("Edit crop percentages") { editCrop() }.tag="cor_crop_edit"
        body.compactAction("Rotate page clockwise") {
            model.turns=(model.turns+1)%4; model.bitmap=Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,Matrix().apply { postRotate(90f) },true); model.crop=CorCrop(); render()
        }
        body.action("Read selected course names") { recognize() }.tag="cor_scan_read"
        body.label("Text recognition runs on this phone. Review and correct every name before searching online. You can return to Import COR to type names instead.",14f)
    }
    private fun editCrop() {
        val fields=requireContext().column()
        val inputs=listOf("Left", "Top", "Right", "Bottom").zip(model.crop.let { listOf(it.left,it.top,it.right,it.bottom) }).map { (label,value) ->
            fields.field("$label (%)",android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL).apply { setText("${(value*100).toInt()}") }
        }
        val dialog=MaterialAlertDialogBuilder(requireContext()).setTitle("Crop edges · percentages").setView(requireContext().scrolling(fields)).setPositiveButton("Apply",null).setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            try { val n=inputs.map { it.text.toString().toFloat()/100 }; model.crop=CorCrop(n[0],n[1],n[2],n[3]); dialog.dismiss(); render() }
            catch(_:Exception) { inputs[0].error="Use 0–100; right/bottom must exceed left/top by at least 2%." }
        } }; dialog.show()
    }
    private fun recognize() {
        val bitmap=model.bitmap ?: return
        val bounds=model.crop.pixels(bitmap.width,bitmap.height)
        model.busy=true; render()
        viewLifecycleOwner.lifecycleScope.launch {
            val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val cropped=Bitmap.createBitmap(bitmap,bounds[0],bounds[1],bounds[2],bounds[3])
                val result=recognizer.process(InputImage.fromBitmap(cropped,0)).await()
                val lines=result.textBlocks.flatMap { it.lines }.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 })).joinToString("\n") { it.text }
                require(lines.isNotBlank()) { "No text found. Adjust the box or use a clearer image." }
                require(lines.length<=10_000) { "Select only the course-name column." }
                (requireActivity() as MainActivity).openDetails(CorImportFragment.forNames(lines))
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { MaterialAlertDialogBuilder(requireContext()).setTitle("Check the selected area").setMessage(e.message ?: "Recognition failed. Try again or enter names manually.").setPositiveButton("OK",null).show() }
            finally { recognizer.close(); model.busy=false; render() }
        }
    }
    companion object { fun open(uri:Uri)=CorScanFragment().apply { arguments=Bundle().apply { putString("uri",uri.toString()) } } }
}
