package com.studypath.app.domain

import com.studypath.app.data.PersonalWorkspace
import java.util.Locale

/** Atomic template application: never drops assessments or changes their aggregation. */
object SemesterGrading {
    private fun key(value:String)=value.trim().lowercase(Locale.ROOT)
    fun apply(workspace:PersonalWorkspace, template:PersonalGradeDraft):PersonalWorkspace {
        template.validate()
        require(workspace.subjects.isNotEmpty()) { "Add subjects before setting up grading." }
        require(template.scale!=null) { "Choose a grading system first." }
        require(template.categories.isNotEmpty() && kotlin.math.abs(template.categories.sumOf { it.weight }-1.0)<1e-7) { "Category weights must total 100%." }
        require(template.categories.map { key(it.title) }.distinct().size==template.categories.size) { "Use a different name for each category." }
        val subjects=workspace.subjects.map { subject ->
            val old=subject.gradeDraft ?: PersonalGradeDraft()
            val scored=old.categories.filter { category -> old.scores.any { it.categoryId==category.id } }
            scored.forEach { category ->
                val matching=template.categories.singleOrNull { key(it.title)==key(category.title) }
                require(matching!=null && matching.aggregation==category.aggregation && old.categories.count { key(it.title)==key(category.title) }==1) {
                    "${subject.title}: keep '${category.title}' with its existing score-combining method, or adjust that subject first. No subjects were changed."
                }
            }
            val categories=template.categories.map { next ->
                val existing=old.categories.singleOrNull { key(it.title)==key(next.title) }
                next.copy(id=existing?.id ?: next.id)
            }
            subject.copy(gradeDraft=old.copy(categories=categories,scale=template.scale,reviewPrepared=false).also { it.validate() })
        }
        return workspace.copy(subjects=subjects)
    }
}
