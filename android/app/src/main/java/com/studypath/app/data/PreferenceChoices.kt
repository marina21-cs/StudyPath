package com.studypath.app.data

import java.text.Normalizer
import java.util.Locale

data class PreferenceChoice(val id: String, val label: String)

object PreferenceChoices {
    private val levelNames = (1..12).associate { "grade_$it" to "Grade $it" } +
        mapOf("college" to "College", "graduate" to "Graduate study")
    private val subjectNames = mapOf("english" to "English", "mathematics" to "Mathematics", "science" to "Science",
        "filipino" to "Filipino", "social_studies" to "Social studies", "programming" to "Programming",
        "data_structures" to "Data structures", "databases" to "Databases")

    fun label(id: String): String = levelNames[id] ?: subjectNames[id] ?: id
        .replace(Regex("([a-zA-Z])([0-9])"), "$1 $2")
        .replace(Regex("[_-]+"), " ").lowercase(Locale.ROOT)
        .replaceFirstChar { it.titlecase(Locale.ROOT) }
        .replace(Regex("\\b([a-z])$")) { it.value.uppercase(Locale.ROOT) }

    fun customId(name: String): String {
        val id = Normalizer.normalize(name.trim(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), "_").trim('_')
        require(id.isNotBlank()) { "Enter a name using letters or numbers." }
        DatabaseRecords.requireKey(id)
        return id
    }

    fun levels(options: List<LearnerPreferenceOptions>, selected: String?): List<PreferenceChoice> =
        choices(levelNames.keys + options.map { it.levelId } + listOfNotNull(selected))

    fun subjects(options: List<LearnerPreferenceOptions>, selected: Set<String>): List<PreferenceChoice> =
        choices(subjectNames.keys + options.flatMap { it.subjectIds } + selected)

    fun matching(draft: LearnerPreferences, options: List<LearnerPreferenceOptions>): List<LearnerPreferenceOptions> =
        options.filter { it.approved && it.approvalStatus == "APPROVED" && it.levelId == draft.levelId &&
            draft.subjectIds.isNotEmpty() && it.subjectIds.containsAll(draft.subjectIds) }
            .distinctBy { it.curriculumVersion }.sortedBy { it.curriculumVersion }

    fun forSave(draft: LearnerPreferences, options: List<LearnerPreferenceOptions>): LearnerPreferences {
        val matching = matching(draft, options)
        val selected = matching.firstOrNull { it.curriculumVersion == draft.curriculumVersion } ?: matching.singleOrNull()
        return draft.copy(curriculumVersion = selected?.curriculumVersion, onboardingComplete = selected != null)
    }

    private fun choices(ids: Collection<String>): List<PreferenceChoice> = ids.distinct().map { PreferenceChoice(it, label(it)) }
}
