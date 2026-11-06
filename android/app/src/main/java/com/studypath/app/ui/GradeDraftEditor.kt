package com.studypath.app.ui

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.domain.*
import java.util.UUID

/** Identical grading controls for the personal workspace and the isolated demo. */
class GradeDraftEditor(private val context: Context, private val draft: PersonalGradeDraft, private val save: (PersonalGradeDraft) -> Unit) {
    private val decimal = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    private var dialog: AlertDialog? = null
    fun dismiss() { dialog?.dismiss(); dialog = null }
    private fun store(next: PersonalGradeDraft) { next.validate(); save(next) }
    private fun editor(title: String, form: LinearLayout, action: () -> Unit) {
        dismiss()
        dialog = MaterialAlertDialogBuilder(context).setTitle(title).setView(context.scrolling(form))
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create().also { current ->
                current.setOnShowListener { current.getButton(-1).setOnClickListener {
                    runCatching { action() }.onSuccess { current.dismiss() }
                        .onFailure { Toast.makeText(context,it.message,Toast.LENGTH_LONG).show() }
                } }
                current.show()
            }
    }
    fun setup(body: LinearLayout) = with(body) {
        label("1. What gets graded?",20f)
        label("Choose categories from your syllabus and enter their weights. You can rename any category.",14f)
        draft.categories.forEach { category ->
            card(category.title,"${"%.1f".format(category.weight*100)}% · ${aggregationLabel(category.aggregation)}")
                .compactAction("Edit ${category.title}") { editCategory(category) }
        }
        action("Add category") { editCategory(null) }.tag="academic_add_category"
        val total=draft.categories.sumOf { it.weight }*100
        label("Total: ${"%.1f".format(total)}% · ${if(kotlin.math.abs(total-100)<1e-7) "Ready" else "Must total 100% to calculate"}",16f).tag="grading_weight_total"
        label("2. How are grades shown?",20f)
        card("Grade scale",draft.scale?.description() ?: "Zero-based percentage · no conversion selected")
            .compactAction("Choose grade scale") { scale() }.tag="grading_scale"
        label("A passing mark of 75 on a 0–100 scale is different from mapping every score into a 75–100 range.",14f)
        label("3. Add your scores",20f)
        label("Enter earned and possible points for activities, tests, projects and other work. Leave earned points blank until graded.",14f)
    }
    fun previousGrading(body: LinearLayout, history: List<PersonalGradeDraft>) {
        if(history.isEmpty()) return
        body.compactAction("Previous grading · ${history.sumOf { it.scores.size }} scores") {
            dismiss()
            val form=context.column(16)
            form.label("Saved records from earlier grading setups. These scores are not included in your current estimate.",14f)
            history.withIndex().reversed().forEach { (index, previous) ->
                form.label("Setup ${index+1}",20f)
                form.label(previous.scale?.description() ?: "Zero-based percentage",14f)
                previous.categories.forEach { category ->
                    form.label("${category.title} · ${"%.1f".format(category.weight*100)}%",18f)
                    form.label(aggregationLabel(category.aggregation),12f)
                    previous.scores.filter { it.categoryId==category.id }.forEach { score ->
                        form.card(score.title,if(score.status=="SCORED") "${score.earned} / ${score.possible}" else "${score.status} · ${score.possible} possible points")
                            .also { card -> score.weight?.let { card.label("Assessment weight: ${it*100}%",12f) } }
                    }
                }
                if(previous.syllabus.isNotBlank()) form.label(previous.syllabus,12f)
            }
            dialog=MaterialAlertDialogBuilder(context).setTitle("Previous grading")
                .setView(context.scrolling(form)).setPositiveButton("Close",null).show()
        }.tag="grading_history"
    }
    fun scores(body: LinearLayout, onSetup: () -> Unit) = with(body) {
        action("Set up categories & grade scale",onSetup).tag="grading_setup"
        if(draft.categories.isEmpty()) { label("Choose what gets graded, set weights, then add scores."); return@with }
        val estimate=runCatching { GradeEngine.evaluate(draft.plan()) }
        val raw=estimate.getOrNull()?.gradedWorkPercent
        val result=card("Personal draft estimate",raw?.let { (draft.scale ?: DraftGradeScale()).convert(it) } ?: "—")
        if(raw!=null && draft.scale?.mode in listOf("LINEAR","BANDS")) result.label("Raw weighted percentage: ${"%.2f".format(raw)} / 100",14f)
        draft.scale?.passingPercent?.let { threshold -> result.label(if(raw==null) "Personal passing threshold: $threshold% raw" else "${if(raw>=threshold) "At or above" else "Below"} your $threshold% raw threshold",14f) }
        result.label("Current graded work only. Blank scores are not zero. This estimate does not change recorded final grades or GWA.",12f)
        estimate.exceptionOrNull()?.let { result.label(if(draft.categories.any { c -> draft.scores.none { it.categoryId==c.id } }) "Add at least one assessment in each category to calculate." else it.message ?: "Check category and assessment weights: each total must be 100%.",14f) }
        label("Assessment scores",20f)
        draft.categories.forEach { category ->
            label("${category.title} · ${"%.1f".format(category.weight*100)}%",18f)
            draft.scores.filter { it.categoryId==category.id }.forEach { score ->
                card(score.title,if(score.status=="SCORED") "${score.earned} / ${score.possible}" else score.status.lowercase().replaceFirstChar { it.uppercase() })
                    .compactAction("Edit ${score.title}") { editScore(category,score) }.tag="edit_score_${score.id}"
            }
            compactAction("Add ${category.title} score") { editScore(category,null) }
        }
    }
    private fun aggregationLabel(mode: String) = when(mode) {
        "EQUAL_ITEMS" -> "Each assessment counts equally"
        "EXPLICIT_WEIGHTS" -> "Custom assessment weights"
        else -> "Total earned ÷ total possible points"
    }
    private fun editCategory(previous: DraftCategory?) {
        val form=context.column(16)
        val name=form.field("Category name").apply { setText(previous?.title.orEmpty()); tag="category_name" }
        form.compactAction("Choose a category name") {
            val names=arrayOf("Activities","Quizzes","Exams / Tests","Projects","Homework","Participation","Practical / Lab","Other (type a name)")
            MaterialAlertDialogBuilder(context).setTitle("What gets graded?").setItems(names) { _,n -> if(n<names.lastIndex) name.setText(names[n]) else name.requestFocus() }.show()
        }
        val weight=form.field("Weight (%)",decimal).apply { setText(previous?.weight?.times(100)?.toString().orEmpty()); tag="category_weight" }
        var aggregation=previous?.aggregation ?: "POOLED_POINTS"
        val choice=form.compactAction(aggregationLabel(aggregation)) {}
        choice.setOnClickListener { val values=Aggregation.entries; MaterialAlertDialogBuilder(context).setTitle("Combine scores within this category")
            .setItems(values.map { aggregationLabel(it.name) }.toTypedArray()) { _,n -> aggregation=values[n].name; choice.text=aggregationLabel(aggregation) }.show() }
        form.label("Changing how existing assessments are combined requires clearing those assessments first.",12f)
        if(previous!=null) form.compactAction("Remove category & its scores") {
            MaterialAlertDialogBuilder(context).setTitle("Remove ${previous.title}?").setMessage("This removes its assessment scores from this grading draft.")
                .setNegativeButton("Keep",null).setPositiveButton("Remove") { _,_ -> store(draft.copy(categories=draft.categories.filter { it.id!=previous.id },scores=draft.scores.filter { it.categoryId!=previous.id },reviewPrepared=false)); dismiss() }.show()
        }
        editor("Grading category",form) {
            require(previous==null || previous.aggregation==aggregation || draft.scores.none { it.categoryId==previous.id }) { "Remove this category’s scores before changing aggregation." }
            val c=DraftCategory(previous?.id ?: UUID.randomUUID().toString(),name.text.toString().trim(),requireNotNull(weight.text.toString().toDoubleOrNull()) { "Enter a category weight." }/100,aggregation)
            store(draft.copy(categories=if(previous==null) draft.categories+c else draft.categories.map { if(it.id==c.id) c else it },reviewPrepared=false))
        }
    }
    private fun editScore(category: DraftCategory, previous: DraftScore?) {
        val form=context.column(16)
        val name=form.field("Assessment title").apply { setText(previous?.title.orEmpty()); tag="score_title" }
        val possible=form.field("Possible points",decimal).apply { setText(previous?.possible?.toString().orEmpty()); tag="score_possible" }
        val earned=form.field("Earned points (blank = ungraded)",decimal).apply { setText(previous?.earned?.toString().orEmpty()); tag="score_earned" }
        val excused=AppCompatCheckBox(context).apply { text="Excused · exclude from calculation"; isChecked=previous?.status=="EXCUSED" }; form.addView(excused)
        val weight=if(category.aggregation=="EXPLICIT_WEIGHTS") form.field("Assessment weight (%)",decimal).apply { setText(previous?.weight?.times(100)?.toString().orEmpty()) } else null
        if(previous!=null) form.compactAction("Remove assessment") { MaterialAlertDialogBuilder(context).setTitle("Remove ${previous.title}?").setNegativeButton("Keep",null).setPositiveButton("Remove") { _,_ -> store(draft.copy(scores=draft.scores.filter { it.id!=previous.id })); dismiss() }.show() }
        editor("${category.title} score",form) {
            val raw=earned.text.toString().trim()
            val score=DraftScore(previous?.id ?: UUID.randomUUID().toString(),category.id,name.text.toString().trim(),requireNotNull(possible.text.toString().toDoubleOrNull()) { "Enter possible points." },
                if(excused.isChecked || raw.isEmpty()) null else requireNotNull(raw.toDoubleOrNull()) { "Enter earned points." },if(excused.isChecked) "EXCUSED" else if(raw.isEmpty()) "UNGRADED" else "SCORED",weight?.let { requireNotNull(it.text.toString().toDoubleOrNull()) { "Enter assessment weight." }/100 })
            store(draft.copy(scores=if(previous==null) draft.scores+score else draft.scores.map { if(it.id==score.id) score else it }))
        }
    }
    fun chooseScale() = scale()
    private fun scale() {
        val options=arrayOf("Zero-based percentage (0–100)","Linear numeric range","Letter or custom grade bands")
        MaterialAlertDialogBuilder(context).setTitle("Choose your grading formula").setItems(options) { _,n -> scaleForm(listOf("PERCENT","LINEAR","BANDS")[n]) }.show()
    }
    private fun scaleForm(mode: String) {
        val previous=draft.scale?.takeIf { it.mode==mode }
        val form=context.column(16)
        form.label("Personal assumptions only. Confirm these rules with your syllabus or professor.",14f)
        val minimum=if(mode=="LINEAR") form.field("Grade at 0% raw",decimal).apply { setText(previous?.minimum?.toString().orEmpty()); tag="scale_minimum" } else null
        val maximum=if(mode=="LINEAR") form.field("Grade at 100% raw",decimal).apply { setText(previous?.maximum?.toString().orEmpty()); tag="scale_maximum" } else null
        val bands=if(mode=="BANDS") form.field("One line: minimum raw % = grade",InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE).apply {
            minLines=5; setText(previous?.bands?.joinToString("\n") { "${it.minimumPercent} = ${it.grade}" }.orEmpty()); tag="scale_bands"
        } else null
        form.label(when(mode) {
            "LINEAR" -> "Formula: minimum + raw % × (maximum − minimum) ÷ 100. If you enter 75 and 100, a raw 0% becomes 75 and a raw 80% becomes 95. Use only if this is your actual formula."
            "BANDS" -> "Enter your own thresholds, e.g. 90 = A. Include 0 for the lowest grade. Each threshold is inclusive; the next threshold is exclusive. Numeric labels such as 1.00 are also allowed. No school thresholds are prefilled."
            else -> "Earned ÷ possible × 100, combined using your category weights. Choose this if 75 is a passing mark on a 0–100 scale."
        },14f)
        val pass=form.field("Passing raw % (optional)",decimal).apply { setText(previous?.passingPercent?.toString().orEmpty()); tag="scale_pass" }
        editor("Grade scale",form) {
            val parsed=bands?.text?.toString()?.lineSequence()?.filter { it.isNotBlank() }?.map { line -> val parts=line.split('=',limit=2); require(parts.size==2) { "Use minimum % = grade on each line." }; DraftGradeBand(requireNotNull(parts[0].trim().toDoubleOrNull()) { "Enter a numeric threshold." },parts[1].trim()) }?.toList().orEmpty()
            val raw=pass.text.toString().trim()
            val scale=DraftGradeScale(mode,minimum?.let { requireNotNull(it.text.toString().toDoubleOrNull()) { "Enter the minimum grade." } } ?: 0.0,maximum?.let { requireNotNull(it.text.toString().toDoubleOrNull()) { "Enter the maximum grade." } } ?: 100.0,parsed,if(raw.isEmpty()) null else requireNotNull(raw.toDoubleOrNull()) { "Enter a valid passing threshold." })
            scale.validate(); store(draft.copy(scale=scale,reviewPrepared=false))
        }
    }
}
