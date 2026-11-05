package com.studypath.app.data

/** Parses a user-supplied registration table, never invents OCR or school authority. */
object CorImport {
    data class Row(val code: String, val title: String, val units: Double?)
    const val SAMPLE = "Code,Subject,Units\nMATH101,Calculus I,3\nPHY101,General Physics,4\nCS101,Programming,3"
    fun parse(text: String): List<Row> {
        require(text.length <= 100_000) { "Use a table smaller than 100 KB." }
        val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
        require(lines.size in 1..101) { "Import up to 100 subjects at a time." }
        val rows = lines.mapIndexedNotNull { index, line ->
            val cells = cells(line)
            if (index == 0 && cells.firstOrNull()?.lowercase() in listOf("code", "course code", "subject code")) null
            else {
                require(cells.size == 3) { "Line ${index + 1}: use Code, Subject, Units." }
                val (code, title, rawUnits) = cells
                val units = if (rawUnits.isBlank()) null else rawUnits.toDoubleOrNull()
                require(code.isNotBlank() && code.length <= 24 && title.isNotBlank() && title.length <= 80) { "Line ${index + 1}: check the code and subject name." }
                require(rawUnits.isBlank() || (units != null && units.isFinite() && units > 0 && units <= 30)) { "Line ${index + 1}: check the units." }
                Row(code, title, units)
            }
        }
        require(rows.isNotEmpty() && rows.size <= 100) { "Add at least one subject." }
        require(rows.map { it.code.lowercase() }.distinct().size == rows.size) { "Each course code must be unique." }
        return rows
    }
    fun parseNames(text: String): List<Row> {
        require(text.length <= 10_000) { "Select only course names, up to 100 subjects." }
        val names=text.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotBlank() }.toList()
        require(names.size in 1..100) { "Enter 1–100 course names, one per line." }
        require(names.all { it.length <= 80 && it.any(Char::isLetter) }) { "Each course name needs letters and at most 80 characters. Remove headers and student details." }
        require(names.map { it.lowercase(java.util.Locale.ROOT) }.distinct().size==names.size) { "Remove duplicate course names before continuing." }
        return names.map { Row("",it,null) }
    }
    private fun cells(line: String): List<String> {
        val delimiter = if ('\t' in line) '\t' else if ('|' in line) '|' else ','
        val result = mutableListOf<String>(); val cell = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '"') {
                if (quoted && i + 1 < line.length && line[i + 1] == '"') { cell.append('"'); i++ }
                else quoted = !quoted
            } else if (c == delimiter && !quoted) { result += cell.toString().trim(); cell.clear() }
            else cell.append(c)
            i++
        }
        require(!quoted) { "Close the quotation marks in your table." }
        result += cell.toString().trim(); return result
    }
    fun suggestedCategory(code: String): String = when {
        code.startsWith("MATH", true) -> "Mathematics"
        listOf("PHY", "BIO", "CHEM", "SCI").any { code.startsWith(it, true) } -> "Science"
        listOf("CS", "IT", "COMP").any { code.startsWith(it, true) } -> "Technology"
        listOf("ENG", "LANG", "FIL").any { code.startsWith(it, true) } -> "Languages"
        else -> "General"
    }
    fun merge(current: PersonalWorkspace, rows: List<Row>): PersonalWorkspace {
        val existingCodes = current.subjects.map { it.code.orEmpty().lowercase() }.filter { it.isNotBlank() }.toSet()
        require(rows.none { it.code.lowercase() in existingCodes }) { "A course code already exists. Edit or remove that row before importing." }
        val existingNames=current.subjects.map { it.title.trim().lowercase(java.util.Locale.ROOT) }.toSet()
        require(rows.none { it.title.trim().lowercase(java.util.Locale.ROOT) in existingNames }) { "A subject with that name already exists. Remove the duplicate row before importing." }
        val result = current.copy(subjects = current.subjects + rows.map { PersonalSubject(PersonalStudyStore.newId(), it.title, category = suggestedCategory(it.code), code = it.code, units = it.units) })
        PersonalStudyStore.validate(result); return result
    }
}
