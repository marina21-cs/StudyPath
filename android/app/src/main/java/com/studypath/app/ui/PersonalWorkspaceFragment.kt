package com.studypath.app.ui

import android.app.DatePickerDialog
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

internal fun Fragment.personalStore(): PersonalStudyStore {
    val repository = (requireActivity().application as StudyPathApplication).graph.repository
    val uid = requireNotNull(repository.currentSession()?.uid) { "Sign in to open your subjects." }
    require(repository.boundUid() == uid) { "Return to your bound account." }
    return PersonalStudyStore(File(requireContext().filesDir, "personal-study"), uid)
}

/** Independent personal fallback. Never reads or writes the published curriculum. */
class PersonalWorkspaceFragment : Fragment() {
    private var shelf = "tasks"
    private var subjectId: String? = null
    private var pendingSubject: String? = null
    private var busy = false
    private var activeEditor: androidx.appcompat.app.AlertDialog? = null
    private val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPdf(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shelf = savedInstanceState?.getString("shelf") ?: arguments?.getString("shelf") ?: "tasks"
        subjectId = savedInstanceState?.getString("subject")
        pendingSubject = savedInstanceState?.getString("pendingSubject")
    }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        requireContext().scrolling(requireContext().column().apply { tag = "personal_workspace" })
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { render() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("shelf", shelf)
        outState.putString("subject", subjectId); outState.putString("pendingSubject", pendingSubject)
        super.onSaveInstanceState(outState)
    }
    private fun panel() = view?.findViewWithTag<LinearLayout>("personal_workspace")
    private fun render() {
        val body = panel() ?: return
        body.removeAllViews()
        if (busy) { body.label("Importing PDF…", 16f); return }
        try {
            val data = personalStore().read()
            if (data.subjects.isEmpty()) {
                body.label("Make room for your subjects", 24f)
                body.label("Keep tasks, lessons and PDFs together.", 16f)
                body.action("Add subject") { subjectEditor(null) }.tag = "personal_add_subject"
                storageInfo(body)
                return
            }
            val selected = data.subjects.firstOrNull { it.id == subjectId } ?: data.subjects.first().also { subjectId = it.id }
            val heading = requireContext().column(0).apply { orientation = LinearLayout.HORIZONTAL }
            body.addView(heading)
            heading.action("${selected.title}  ▾") {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Choose subject")
                    .setItems((data.subjects.map { it.title } + "Edit current subject").toTypedArray()) { _, index ->
                        if (index == data.subjects.size) subjectEditor(selected)
                        else { subjectId = data.subjects[index].id; render() }
                    }.show()
            }.quietAction().apply { tag = "personal_choose_subject"; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
            heading.action("+") { subjectEditor(null) }.quietAction().apply {
                tag = "personal_add_subject"; contentDescription = "Add subject"
                layoutParams = LinearLayout.LayoutParams(context.dp(48), -2).apply { marginStart = context.dp(8) }
            }
            val tabs = requireContext().column(0).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
            body.addView(tabs)
            listOf("tasks" to "Tasks", "lessons" to "Lessons & PDFs").forEach { (key, title) ->
                tabs.action(title) { shelf = key; render() }.quietAction().apply {
                    tag = "personal_tab_$key"; isCheckable = true; isChecked = shelf == key
                    setTextColor(context.tint(if (shelf == key) com.studypath.app.R.color.sp_action else com.studypath.app.R.color.sp_muted))
                    strokeWidth = context.dp(if (shelf == key) 2 else 1)
                    layoutParams = LinearLayout.LayoutParams(0, -1, 1f).apply { topMargin = context.dp(12); marginEnd = context.dp(4) }
                }
            }
            if (shelf == "tasks") {
                body.action("Add task") { taskEditor(null) }.tag = "personal_add_task"
                val tasks = data.tasks.filter { it.subjectId == selected.id }.sortedWith(compareBy<PersonalTask> { it.done }.thenBy { it.due.ifEmpty { "9999" } })
                if (tasks.isEmpty()) body.label("Nothing planned yet.", 14f)
                tasks.forEach { task ->
                    val row = requireContext().column(0).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
                    body.addView(row)
                    val toggle = com.google.android.material.checkbox.MaterialCheckBox(requireContext()).apply {
                        text = task.title + if (task.due.isBlank()) "" else "\n${task.due}"
                        textSize = 16f; minHeight = context.dp(56); isChecked = task.done
                        setTextColor(context.tint(com.studypath.app.R.color.sp_ink))
                        tag = "personal_task_toggle_${task.id}"
                        contentDescription = "${if (task.done) "Mark unfinished" else "Mark done"}: ${task.title}"
                        setOnCheckedChangeListener { _, checked -> safely {
                            personalStore().update { it.copy(tasks = it.tasks.map { item -> if (item.id == task.id) item.copy(done = checked) else item }) }; render()
                        } }
                    }
                    row.addView(toggle, LinearLayout.LayoutParams(0, -2, 1f))
                    row.action("⋮") { taskEditor(task) }.quietAction().apply {
                        contentDescription = "Edit task: ${task.title}"; layoutParams = LinearLayout.LayoutParams(context.dp(48), -2)
                    }
                }
            } else {
                body.action("Import PDF") {
                    pendingSubject = selected.id
                    runCatching { pdfPicker.launch(arrayOf("application/pdf")) }.onFailure { message("No file picker is available on this device.") }
                }.tag = "personal_import_pdf"
                body.action("Create lesson / study card") { materialEditor(null, arguments?.getString("readingTitle").orEmpty()) }.quietAction().tag = "personal_create_lesson"
                val materials = data.materials.filter { it.subjectId == selected.id }
                if (materials.isEmpty()) body.label("Import a module or write your own lesson.", 14f)
                materials.forEach { material ->
                    val row = requireContext().column(0).apply { orientation = LinearLayout.HORIZONTAL }
                    body.addView(row)
                    row.action(material.title) {
                        (requireActivity() as MainActivity).openDetails(PersonalMaterialFragment.open(material.id))
                    }.quietAction().apply {
                        contentDescription = "Study ${material.title}"
                        setIconResource(com.studypath.app.R.drawable.ic_chevron_right)
                        iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
                        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { topMargin = context.dp(8) }
                    }
                    row.action("⋮") {
                        MaterialAlertDialogBuilder(requireContext()).setTitle(material.title)
                            .setItems((if (material.pdf) listOf("Remove PDF") else listOf("Edit lesson", "Remove lesson")).toTypedArray()) { _, index ->
                                if (!material.pdf && index == 0) materialEditor(material)
                                else confirm("Remove this lesson?", "This removes your local copy.") { personalStore().removeMaterial(material.id); render() }
                            }.show()
                    }.quietAction().apply {
                        contentDescription = "Options for ${material.title}"
                        layoutParams = LinearLayout.LayoutParams(context.dp(48), -2).apply { topMargin = context.dp(8); marginStart = context.dp(8) }
                    }
                }
            }
            storageInfo(body)
        } catch (error: Exception) {
            body.label("Your workspace could not be opened. Your files are kept.", 16f)
            body.action("Retry") { render() }.quietAction()
        }
    }
    private fun storageInfo(body: LinearLayout) {
        body.action("On this phone  ⓘ") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Your personal workspace")
                .setMessage("Subjects, tasks and imported lessons stay in this account on this phone. They work offline and survive app updates. They are not cloud backed up; uninstalling removes them. Personal materials are not approved course content. PDF limit: 25 MB each, 250 MB total.")
                .setPositiveButton("Got it", null).show()
        }.quietAction().apply { textSize = 13f }
    }
    private fun subjectEditor(existing: PersonalSubject?) {
        val body = requireContext().column(16)
        val title = body.field("Subject name").apply { filters = arrayOf(InputFilter.LengthFilter(80)); setText(existing?.title.orEmpty()) }
        editor(if (existing == null) "Add subject" else "Edit subject", body) {
            val row = PersonalSubject(existing?.id ?: PersonalStudyStore.newId(), title.text.toString().trim())
            personalStore().update { it.copy(subjects = if (existing == null) it.subjects + row else it.subjects.map { old -> if (old.id == row.id) row else old }) }
            subjectId = row.id
        }
        if (existing != null) body.action("Delete subject") {
            confirm("Delete ${existing.title}?", "Its tasks and personal lessons will also be removed from this phone.") {
                personalStore().removeSubject(existing.id); subjectId = null; activeEditor?.dismiss(); render()
            }
        }.quietAction()
    }
    private fun taskEditor(existing: PersonalTask?) {
        val body = requireContext().column(16)
        val title = body.field("Task").apply { filters = arrayOf(InputFilter.LengthFilter(160)); setText(existing?.title.orEmpty()) }
        var due = existing?.due.orEmpty()
        val date = body.action(due.ifEmpty { "Choose due date (optional)" }) {}.quietAction()
        date.setOnClickListener {
            val initial = runCatching { LocalDate.parse(due) }.getOrDefault(LocalDate.now())
            DatePickerDialog(requireContext(), { _, year, month, day -> due = LocalDate.of(year, month + 1, day).toString(); date.text = due }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
        }
        body.action("Clear date") { due = ""; date.text = "Choose due date (optional)" }.quietAction()
        editor(if (existing == null) "Add task" else "Edit task", body) {
            val row = PersonalTask(existing?.id ?: PersonalStudyStore.newId(), requireNotNull(subjectId), title.text.toString().trim(), due, existing?.done ?: false)
            personalStore().update { it.copy(tasks = if (existing == null) it.tasks + row else it.tasks.map { old -> if (old.id == row.id) row else old }) }
        }
        if (existing != null) body.action("Delete task") {
            confirm("Delete task?", existing.title) { personalStore().update { it.copy(tasks = it.tasks.filterNot { row -> row.id == existing.id }) }; activeEditor?.dismiss(); render() }
        }.quietAction()
    }
    private fun materialEditor(existing: PersonalMaterial?, suggestedTitle: String = "") {
        val body = requireContext().column(16)
        val title = body.field("Lesson title").apply { filters = arrayOf(InputFilter.LengthFilter(120)); setText(existing?.title ?: suggestedTitle.take(120)) }
        fun textField(label: String, limit: Int, value: String) = body.field(label, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines = 2; maxLines = 6; filters = arrayOf(InputFilter.LengthFilter(limit)); setText(value)
        }
        val text = textField("Your lesson text", 20000, existing?.text.orEmpty())
        val question = textField("Practice question (optional)", 1000, existing?.question.orEmpty())
        val answer = textField("Answer", 4000, existing?.answer.orEmpty())
        editor("Personal lesson", body) {
            val row = PersonalMaterial(existing?.id ?: PersonalStudyStore.newId(), requireNotNull(subjectId), title.text.toString().trim(), text.text.toString().trim(), question.text.toString().trim(), answer.text.toString().trim())
            personalStore().update { it.copy(materials = if (existing == null) it.materials + row else it.materials.map { old -> if (old.id == row.id) row else old }) }
        }
    }
    private fun editor(title: String, body: LinearLayout, save: () -> Unit) {
        val status = body.label("", 14f)
        val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(title).setView(requireContext().scrolling(body))
            .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        activeEditor = dialog
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                try { save(); dialog.dismiss(); render() }
                catch (error: Exception) { status.text = error.message ?: "Could not save. Try again." }
            }
        }
        dialog.show()
    }
    override fun onDestroyView() { activeEditor?.dismiss(); activeEditor = null; super.onDestroyView() }
    private fun confirm(title: String, detail: String, run: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(title).setMessage(detail)
            .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ -> safely(run) }.show()
    }
    private fun safely(run: () -> Unit) { try { run() } catch (error: Exception) { message(error.message ?: "Could not save. Try again.") } }
    private fun message(text: String) { if (isAdded) Toast.makeText(requireContext(), text, Toast.LENGTH_LONG).show() }
    private fun importPdf(uri: android.net.Uri) {
        val subject = pendingSubject ?: return
        val store = runCatching { personalStore() }.getOrElse { return }
        val resolver = requireContext().contentResolver
        busy = true; render()
        viewLifecycleOwner.lifecycleScope.launch {
            val id = PersonalStudyStore.newId()
            val temp = File(store.directory, "$id.import")
            val target = store.pdf(id)
            var committed = false
            try {
                val title = withContext(Dispatchers.IO) {
                    require(store.directory.listFiles().orEmpty().filter { it.extension == "pdf" }.sumOf { it.length() } < PersonalStudyStore.MAX_TOTAL_PDF_BYTES) { "PDF storage is full. Remove an unused PDF first." }
                    resolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Could not open this file." }
                        FileOutputStream(temp).use { output ->
                            val buffer = ByteArray(8192); var total = 0L
                            while (true) {
                                val count = input.read(buffer); if (count < 0) break
                                total += count; require(total <= PersonalStudyStore.MAX_PDF_BYTES) { "Choose a PDF smaller than 25 MB." }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    val header = ByteArray(5)
                    temp.inputStream().use { require(it.read(header) == 5 && String(header, Charsets.US_ASCII) == "%PDF-") { "Choose a valid PDF file." } }
                    ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { require(it.pageCount in 1..500) { "Choose a PDF with 1–500 pages." } }
                    }
                    require(temp.length() + store.directory.listFiles().orEmpty().filter { it.extension == "pdf" }.sumOf { it.length() } <= PersonalStudyStore.MAX_TOTAL_PDF_BYTES) { "PDF storage is full." }
                    var name = "Imported PDF"
                    resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) name = cursor.getString(0)?.substringBeforeLast('.')?.take(120)?.ifBlank { "Imported PDF" } ?: name
                    }
                    name
                }
                require(personalStore().directory == store.directory) { "Your account changed. Import cancelled." }
                check(temp.renameTo(target)) { "Could not keep the PDF. Try again." }
                store.update { it.copy(materials = it.materials + PersonalMaterial(id, subject, title, pdf = true)) }
                committed = true; subjectId = subject
                message("PDF imported · ready offline")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { message(error.message ?: "Could not import this PDF. Try an unencrypted PDF.") }
            finally { temp.delete(); if (!committed) target.delete(); busy = false; if (view != null) render() }
        }
    }
    companion object {
        fun lessons() = PersonalWorkspaceFragment().apply { arguments = Bundle().apply { putString("shelf", "lessons") } }
        fun forReading(title: String) = lessons().apply { requireArguments().putString("readingTitle", title) }
    }
}

class PersonalMaterialFragment : Fragment() {
    private var pdfView: OfflinePdfView? = null
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = requireContext().column()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val body = view as LinearLayout
        try {
            val store = personalStore()
            val item = requireNotNull(store.read().materials.firstOrNull { it.id == arguments?.getString("id") }) { "This lesson was removed. Go back to your subjects." }
            if (item.pdf) {
                require(store.pdf(item.id).isFile) { "This PDF is missing. Import it again from My subjects." }
                val prefs = requireContext().getSharedPreferences("personal_pdf_pages", 0)
                val key = store.directory.name + item.id
                pdfView = OfflinePdfView(requireContext(), viewLifecycleOwner).also {
                    body.addView(it, LinearLayout.LayoutParams(-1, -1))
                    it.open(store.pdf(item.id), item.title, prefs.getInt(key, 0)) { page -> prefs.edit().putInt(key, page).apply() }
                }
            } else {
                val content = requireContext().column(0)
                body.addView(requireContext().scrolling(content))
                content.label(item.title, 24f)
                if (item.text.isNotBlank()) content.label(item.text).setTextIsSelectable(true)
                if (item.question.isNotBlank()) {
                    content.label("Try it", 20f); content.label(item.question)
                    content.field("Your answer", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply { minLines = 2; filters = arrayOf(InputFilter.LengthFilter(4000)) }
                    val answer = content.label(item.answer).apply { visibility = View.GONE; tag = "personal_answer" }
                    val reveal = content.action("Show answer") {}.apply { tag = "personal_reveal_answer" }
                    reveal.setOnClickListener { answer.visibility = if (answer.visibility == View.GONE) View.VISIBLE else View.GONE; reveal.text = if (answer.visibility == View.VISIBLE) "Try again" else "Show answer" }
                }
            }
        } catch (error: Exception) { body.label(error.message ?: "Could not open this lesson.") }
    }
    override fun onDestroyView() { pdfView?.clear(); pdfView = null; super.onDestroyView() }
    companion object { fun open(id: String) = PersonalMaterialFragment().apply { arguments = Bundle().apply { putString("id", id) } } }
}
