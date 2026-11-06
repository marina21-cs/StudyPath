package com.studypath.app.domain

import com.studypath.app.data.*

/** Fictional in-memory data. No repository, UID, file or network access is available here. */
object StudentDemo {
    private fun calculusDraft() = PersonalGradeDraft(
        categories=listOf(DraftCategory("quiz","Quizzes",.2),DraftCategory("midterm","Midterm",.3),DraftCategory("final","Final exam",.5)),
        scores=listOf(DraftScore("quiz1","quiz","Quiz 1",20.0,16.0,"SCORED"),DraftScore("midterm1","midterm","Midterm exam",100.0,82.0,"SCORED"),DraftScore("final1","final","Final exam",100.0,88.0,"SCORED")),
        syllabus="Authored fictional example; no professor approval.",scale=DraftGradeScale())
    fun workspace() = PersonalWorkspace(subjects = listOf(
        PersonalSubject("calculus","Advanced Calculus",code="MATH203",units=3.0,grade="1.75",prerequisiteIds=listOf(),gradeDraft=calculusDraft()),
        PersonalSubject("structures","Data Structures",code="CS202",units=3.0,grade="2.0",prerequisiteIds=listOf()),
        PersonalSubject("databases","Database Systems",code="IT204",units=3.0,grade="1.5",prerequisiteIds=listOf("structures")),
        PersonalSubject("physics","General Physics",code="PHY102",units=3.0,grade="2.25",prerequisiteIds=listOf("calculus")),
        PersonalSubject("communication","Purposive Communication",code="GE105",units=3.0,grade="1.25",prerequisiteIds=listOf())
    ), tasks = listOf(
        PersonalTask("t1","calculus","Review inverse transforms"),
        PersonalTask("t2","structures","Trace a queue by hand"),
        PersonalTask("t3","databases","Sketch a student database")
    ), materials = listOf(
        PersonalMaterial("laplace","calculus","Laplace transforms","A Laplace transform represents a time-based function using a new variable, s. It can turn a differential equation into an algebraic equation. Start by identifying the function and its initial conditions."),
        PersonalMaterial("linearity","calculus","Using linearity","The transform of a sum is the sum of the transforms. Constant multipliers stay outside the transform. Practice by splitting a function into simple terms before using a transform table."),
        PersonalMaterial("inverse","calculus","Inverse transform steps","Factor the denominator, use partial fractions where appropriate, and match each term to a known transform pair. Check that the result agrees with the given initial conditions."),
        PersonalMaterial("queues","structures","Queues in practice","A queue processes items in first-in, first-out order. Enqueue adds an item to the back; dequeue removes the item at the front. Trace three arrivals followed by two removals."),
        PersonalMaterial("stacks","structures","Stacks in practice","A stack processes the most recently added item first. Push adds to the top and pop removes from the top. Try modeling undo actions with a stack."),
        PersonalMaterial("relational","databases","Relational database","A relational database organizes records into tables. Keys identify records and connect related information. Sketch a student table, a subject table and an enrollment table that connects them."),
        PersonalMaterial("normalization","databases","Organizing related data","Separate facts about different entities into related tables to reduce duplication. A student name belongs with the student record; enrollment connects that student to a subject."),
        PersonalMaterial("forces","physics","Motion and forces","Net force is the vector sum of forces on an object. In an inertial frame, net force equals mass times acceleration. Draw a free-body diagram before choosing an axis and writing equations."),
        PersonalMaterial("audience","communication","Audience and purpose","Choose details and tone for the audience and the purpose of your message. Explain the same idea to a classmate and to a new learner, then compare your choices.")
    )+pdfMaterials())
    fun pdfMaterials()=listOf("calculus" to "Laplace transforms handout", "structures" to "Queues and stacks handout", "databases" to "Tables and relationships handout", "physics" to "Forces and acceleration handout", "communication" to "Audience and purpose handout")
        .map { (id,title) -> PersonalMaterial("pdf-$id",id,title,text="Original StudyPath sample handout. Two pages with a worked example and practice question.",pdf=true) }
}

class StudentDemoSession {
    var workspace = StudentDemo.workspace(); private set
    var name = "Alex Reyes"; private set
    var quiz = 16.0; private set
    var exam = 85; private set
    val included = StudentDemo.workspace().subjects.map { it.id }.toMutableSet()
    fun rename(value: String) { require(value.trim().length in 1..60); name = value.trim() }
    fun toggleTask(id: String) {
        require(workspace.tasks.any { it.id == id })
        workspace = workspace.copy(tasks = workspace.tasks.map { if (it.id == id) it.copy(done = !it.done) else it })
    }
    fun saveReviewer(pack:PdfReviewer) {
        pack.validate();require(workspace.materials.any { it.id==pack.materialId && it.pdf })
        workspace=workspace.copy(reviewers=workspace.reviewers.orEmpty().filterNot { it.materialId==pack.materialId }+pack)
    }
    fun addReviewTask(task:PersonalTask) { require(workspace.subjects.any { it.id==task.subjectId });workspace=workspace.copy(tasks=workspace.tasks+task) }
    fun applyGrading(template:PersonalGradeDraft) { workspace=SemesterGrading.apply(workspace,template) }
    fun saveDraft(subjectId: String, draft: PersonalGradeDraft) {
        require(workspace.subjects.any { it.id==subjectId }); draft.validate()
        workspace=workspace.copy(subjects=workspace.subjects.map { if(it.id==subjectId) it.copy(gradeDraft=draft) else it })
    }
    fun setQuiz(value: Double) { require(value.isFinite() && value in 0.0..20.0); quiz = value }
    fun setExam(value: Int) { require(value in 0..100); exam = value }
    fun estimate() = quiz + 82 * .3 + 88 * .5
    fun projection() = 41 + .4 * exam
    fun includedUnits() = workspace.subjects.filter { it.id in included }.sumOf { it.units ?: 0.0 }
    fun gwa(): Double? {
        val units = includedUnits(); if (units == 0.0) return null
        return workspace.subjects.filter { it.id in included }.sumOf { it.grade!!.toDouble() * it.units!! } / units
    }
    fun reset() { workspace = StudentDemo.workspace(); name = "Alex Reyes"; quiz = 16.0; exam = 85; included.clear(); included.addAll(workspace.subjects.map { it.id }) }
}
