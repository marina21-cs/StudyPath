package com.studypath.app.data

import com.google.gson.JsonParser

/** Synthetic validator content only; fabricated approval metadata must never enter production seeds. */
internal object LessonFixture {
    val payload = """{"schemaVersion":1,"moduleId":"fixture_module","version":1,"lessons":[{"id":"lesson_1","title":"Synthetic lesson","steps":[{"id":"goal","kind":"READING","title":"Goal","body":"Test objective"},{"id":"example","kind":"EXAMPLE","title":"Worked example","body":"One synthetic part and two synthetic parts make three."},{"id":"choice","kind":"CHECK","title":"Guided check","body":"Choose a synthetic answer.","questionId":"q_1"},{"id":"ordering","kind":"CHECK","title":"Independent ordering","body":"Put steps in order.","questionId":"q_order"},{"id":"number","kind":"CHECK","title":"Independent number","body":"Count synthetic parts.","questionId":"q_number"}]}],"questions":[{"id":"q_1","prompt":"Pick A","format":"selected_response","options":[{"id":"a","text":"A","feedback":"A follows the example."},{"id":"b","text":"B","feedback":"Look again at the example."}],"answer":["a"],"hint":"Read the example.","explanation":"A follows this synthetic example."},{"id":"q_order","prompt":"First then second","format":"ordering","options":[{"id":"first","text":"First","feedback":"First comes first."},{"id":"second","text":"Second","feedback":"Second comes later."}],"answer":["first","second"],"hint":"Begin with first.","explanation":"First precedes second."},{"id":"q_number","prompt":"One plus two","format":"bounded_response","responseType":"numeric","unit":"parts","target":3,"tolerance":0.05,"hint":"Count the parts.","explanation":"One plus two makes three."},{"id":"q_review","prompt":"Name the first letter","format":"bounded_response","responseType":"short","acceptedAnswers":["a"],"caseSensitive":false,"unit":"","hint":"The alphabet begins here.","explanation":"A is the first letter."}],"glossary":[{"term":"parts","definition":"Parts of a synthetic fixture."}]}"""
    fun verified(text: String = payload): VerifiedTextPackage {
        val manifest = JsonParser.parseString(TextPackageFixture.manifestJson).asJsonObject.apply {
            add("objectives", JsonParser.parseString("""[{"id":"obj_1","description":"Test objective"}]"""))
            add("lessons", JsonParser.parseString("""[{"id":"lesson_1","objectiveIds":["obj_1"],"stepIds":["goal","example","choice","ordering","number"],"questionIds":["q_1","q_order","q_number"],"reviewQuestionIds":["q_review"],"conceptIds":["parts"]}]"""))
            add("questions", JsonParser.parseString("""[{"id":"q_1","objectiveId":"obj_1","conceptIds":["parts"],"format":"selected_response"},{"id":"q_order","objectiveId":"obj_1","conceptIds":["parts"],"format":"ordering"},{"id":"q_number","objectiveId":"obj_1","conceptIds":["parts"],"format":"bounded_response"},{"id":"q_review","objectiveId":"obj_1","conceptIds":["parts"],"format":"bounded_response"}]"""))
            getAsJsonObject("text").apply { addProperty("bytes", text.toByteArray().size); addProperty("sha256", TextPackageFixture.sha(text)) }
        }.toString()
        val summary = TextPackageFixture.bind(TextPackageFixture.summary, manifest, text)
        return TextPackageVerifier.verify(summary, manifest, text)
    }
}
