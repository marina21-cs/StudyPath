package com.studypath.app.domain

import com.studypath.app.data.PersonalWorkspace
import java.util.Locale

/** Replaces active rules atomically; previous assessment records remain in visible history. */
object SemesterGrading {
    private fun key(value:String)=value.trim().lowercase(Locale.ROOT)
    fun validateTemplate(template:PersonalGradeDraft) {
        template.validate()
        require(template.scale!=null) { "Choose a grading system first." }
        require(template.categories.isNotEmpty() && kotlin.math.abs(template.categories.sumOf { it.weight }-1.0)<1e-7) { "Category weights must total 100%." }
        require(template.categories.map { key(it.title) }.distinct().size==template.categories.size) { "Use a different name for each category." }
        require(template.scores.isEmpty()) { "A shared grading setup cannot contain assessment scores." }
    }
    fun apply(workspace:PersonalWorkspace, template:PersonalGradeDraft):PersonalWorkspace {
        validateTemplate(template)
        require(workspace.subjects.isNotEmpty()) { "Add subjects before setting up grading." }
        val fresh=PersonalGradeDraft(categories=template.categories,scale=template.scale)
        return workspace.copy(gradingTemplate=fresh,subjects=workspace.subjects.map { subject ->
            val previous=subject.gradeDraft
            subject.copy(gradeDraft=fresh.copy(syllabus=previous?.syllabus.orEmpty()),
                gradeHistory=if(previous!=null && previous.scores.isNotEmpty()) subject.gradeHistory.orEmpty()+previous else subject.gradeHistory)
        })
    }
}
