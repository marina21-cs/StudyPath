package com.studypath.app.domain

/** User-entered arithmetic draft, never an institutional policy or an approval. */
data class DraftCategory(val id:String,val title:String,val weight:Double,val aggregation:String="POOLED_POINTS")
data class DraftScore(val id:String,val categoryId:String,val title:String,val possible:Double,val earned:Double?=null,val status:String="UNGRADED",val weight:Double?=null)
data class PersonalGradeDraft(val categories:List<DraftCategory> = emptyList(),val scores:List<DraftScore> = emptyList(),val syllabus:String="",val reviewPrepared:Boolean=false) {
    fun plan():GradePlan = GradePlan(categories.map { c -> GradeCategory(c.id,c.weight,Aggregation.valueOf(c.aggregation),scores.filter{it.categoryId==c.id}.map{Assessment(it.id,it.possible,it.earned,AssessmentStatus.valueOf(it.status),it.weight)}) },false)
    fun validate() {
        require(categories.size<=20 && scores.size<=300 && syllabus.length<=4000)
        require(categories.map{it.id}.distinct().size==categories.size && scores.map{it.id}.distinct().size==scores.size)
        categories.forEach{require(it.title.isNotBlank() && it.title.length<=80 && it.weight.isFinite() && it.weight in 0.0..1.0);Aggregation.valueOf(it.aggregation)}
        scores.forEach{require(it.categoryId in categories.map{c->c.id} && it.title.isNotBlank() && it.title.length<=120 && it.possible.isFinite() && it.possible>0);val status=AssessmentStatus.valueOf(it.status);require(if(status==AssessmentStatus.SCORED) it.earned!=null && it.earned.isFinite() && it.earned in 0.0..it.possible else it.earned==null);require(it.weight==null || (it.weight.isFinite() && it.weight>=0))}
    }
}
