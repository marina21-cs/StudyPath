package com.studypath.app.ui

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.data.*
import kotlinx.coroutines.*

/** COR review supports copied tables and text files. Demo data is always explicitly labeled. */
class CorImportFragment : Fragment() {
    private var draft = ""
    private var sample = false
    private var namesOnly = false
    private var parsed: List<CorImport.Row>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resolver = requireContext().contentResolver
                val kind=resolver.getType(uri).orEmpty()
                val isPdf=withContext(Dispatchers.IO) { resolver.openInputStream(uri)?.use { input -> ByteArray(5).let { h -> input.read(h)==5 && String(h,Charsets.US_ASCII)=="%PDF-" } } ?: false }
                if(kind.startsWith("image/") || kind=="application/pdf" || isPdf) {
                    runCatching { resolver.takePersistableUriPermission(uri,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    (requireActivity() as MainActivity).openDetails(CorScanFragment.open(uri)); return@launch
                }
                val text = withContext(Dispatchers.IO) {
                    val bytes = resolver.openInputStream(uri)?.use { it.readBytesBounded(100_000) } ?: throw java.io.IOException("Could not open that file.")
                    require(!bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray())) { "PDF extraction is not available yet. Copy its subject table here, or try the sample preview." }
                    bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                }
                draft = text; namesOnly = false; sample = false; parsed = null; render()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error(e.message ?: "Could not read this file.") }
        }
    }
    override fun onCreate(state: Bundle?) { super.onCreate(state); draft = state?.getString("draft") ?: arguments?.getString("names").orEmpty(); namesOnly = state?.getBoolean("namesOnly") ?: (arguments?.containsKey("names") == true); sample = state?.getBoolean("sample") ?: false }
    override fun onSaveInstanceState(state: Bundle) {
        captureDraft(); state.putString("draft", draft); state.putBoolean("sample", sample); state.putBoolean("namesOnly",namesOnly); super.onSaveInstanceState(state)
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View = requireContext().scrolling(requireContext().column().apply { tag = "cor_body" })
    override fun onViewCreated(view: View, state: Bundle?) { render() }
    private fun captureDraft() { view?.findViewWithTag<EditText>("cor_table")?.let { draft = it.text.toString() } }
    private fun render() {
        val body = view?.findViewWithTag<LinearLayout>("cor_body") ?: return
        body.removeAllViews()
        body.label(if (sample) "Sample COR preview" else "Import your subjects", 24f)
        body.label(if (sample) "Demo data · not extracted from your document" else if(namesOnly) "Review recognized names · correct spelling, remove headers and join wrapped names. One course per line." else "Step 1 · Choose your COR, or enter the course names. You review everything before adding.", 14f)
        val rows = parsed
        if (rows == null) {
            body.compactAction("Choose COR file") { captureDraft(); runCatching { picker.launch(arrayOf("text/plain", "text/csv", "application/pdf", "image/*")) }.onFailure { error("No file picker is available.") } }.tag = "cor_choose_file"
            body.compactAction(if(namesOnly) "Use a Code, Subject, Units table" else "Enter course names instead") { captureDraft(); namesOnly=!namesOnly; draft=""; render() }
            body.label(if(namesOnly) "One course name per line" else "Paste table: Code, Subject, Units", 14f)
            body.field(if(namesOnly) "Course names" else "COR table", android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
                tag = "cor_table"; filters = arrayOf(android.text.InputFilter.LengthFilter(100_000)); minLines = 4; maxLines = 10; setText(draft)
            }
            body.action("Review subjects") {
                captureDraft()
                try { parsed = if(namesOnly) CorImport.parseNames(draft) else CorImport.parse(draft); render() } catch(e: Exception) { error(e.message ?: "Check your table.") }
            }.tag = "cor_review"
            body.compactAction("Try sample COR") { draft = CorImport.SAMPLE; namesOnly=false; sample = true; parsed = CorImport.parse(draft); render() }.tag = "cor_sample"
            body.label("Images/PDFs: select the course-name area, then review on-device OCR. Text/CSV and manual names are also supported.", 12f)
        } else {
            body.label("${rows.size} subjects to review", 18f)
            val selected = rows.toMutableSet()
            rows.forEach { row ->
                val check = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
                    text = "${if(row.code.isBlank()) "" else row.code + " · "}${row.title}\n${row.units?.let { "$it units" } ?: "Units not entered"} · ${CorImport.suggestedCategory(row.code)}"
                    isChecked = true; minHeight = context.dp(56); textSize = 14f
                    setOnCheckedChangeListener { _, checked -> if (checked) selected.add(row) else selected.remove(row) }
                }
                body.addView(check)
            }
            body.action(if (sample) "Add sample subjects" else "Add selected subjects") {
                try {
                    require(selected.isNotEmpty()) { "Select at least one subject." }
                    personalStore().update { CorImport.merge(it, rows.filter(selected::contains)) }
                    val activity=requireActivity() as MainActivity
                    activity.onBackPressedDispatcher.onBackPressed()
                    if(!sample) activity.openDetails(MaterialRecommendationsFragment.forSubjects(rows.filter(selected::contains).map { it.title }))
                } catch(e: Exception) { error(e.message ?: "Could not import. Your subjects are kept.") }
            }.tag = "cor_confirm"
            body.compactAction("Edit table") { parsed = null; render() }
        }
    }
    companion object { fun forNames(names:String)=CorImportFragment().apply { arguments=Bundle().apply { putString("names",names) } } }
    private fun error(message: String) {
        if (isAdded) MaterialAlertDialogBuilder(requireContext()).setTitle("Check import").setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
        while (true) { val size = read(buffer); if (size < 0) break; require(output.size() + size <= limit) { "Use a text table smaller than 100 KB." }; output.write(buffer, 0, size) }
        return output.toByteArray()
    }
}
