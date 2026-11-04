package com.studypath.app.domain

/** Parsing errors remain distinct from an authored UNKNOWN rule and never trigger legacy fallback. */
data class PrerequisiteDocument(val expression: Prerequisite, val invalidReason: String? = null)

/** Versioned catalog syntax only. A valid expression does not establish curriculum or policy approval. */
object PrerequisiteCodec {
    const val MAX_DEPTH = 8
    const val MAX_NODES = 64
    const val MAX_CHILDREN = 16
    const val MAX_REFERENCES = 32
    const val MAX_COURSES = 200
    private val key = Regex("[A-Za-z0-9_-]{1,64}")

    fun decode(value: Any?): Prerequisite {
        val root = objectFields(value, setOf("schemaVersion", "expression"), "Prerequisite document")
        require((root["schemaVersion"] as? Number)?.toDouble() == 1.0) { "Prerequisite schemaVersion must be 1" }
        return decodeNode(root["expression"], 1, Budget()).also { inspect(it, 1, Budget()) }
    }

    fun parse(value: Any?): PrerequisiteDocument = try {
        PrerequisiteDocument(decode(value))
    } catch (error: IllegalArgumentException) {
        val reason = "Invalid prerequisite expression: ${error.message}"
        PrerequisiteDocument(Prerequisite.Unknown(reason), reason)
    }

    fun encode(expression: Prerequisite): Map<String, Any> {
        inspect(expression, 1, Budget())
        return linkedMapOf("schemaVersion" to 1, "expression" to encodeNode(expression))
    }

    /** Checks every course before evaluating any result, including expressions supplied by direct callers. */
    fun validateCatalog(courses: List<Pair<String, PrerequisiteDocument>>): Map<String, Prerequisite> {
        require(courses.size in 1..MAX_COURSES) { "Prerequisite catalog must contain 1..$MAX_COURSES courses" }
        require(courses.all { key.matches(it.first) }) { "Invalid prerequisite catalog courseId" }
        require(courses.map { it.first }.distinct().size == courses.size) { "Duplicate prerequisite catalog courseId" }
        val expressions = courses.sortedBy { it.first }.associate { (id, document) ->
            require(document.invalidReason == null) { "$id: ${document.invalidReason}" }
            id to document.expression
        }
        val references = expressions.mapValues { (id, expression) ->
            val budget = Budget()
            try { inspect(expression, 1, budget) }
            catch (error: IllegalArgumentException) { throw IllegalArgumentException("$id: ${error.message}") }
            budget.references.toList()
        }
        references.forEach { (id, refs) ->
            refs.forEach { require(it in expressions) { "$id references missing catalog course: $it" } }
        }
        rejectCycles(references)
        return expressions
    }

    private fun decodeNode(value: Any?, depth: Int, budget: Budget): Prerequisite {
        budget.node(depth)
        require(value is Map<*, *>) { "Prerequisite node must be an object" }
        return when (value["kind"]) {
            "NONE" -> {
                objectFields(value, setOf("kind"), "NONE")
                Prerequisite.AllOf(emptyList())
            }
            "UNKNOWN" -> {
                objectFields(value, setOf("kind", "reason"), "UNKNOWN")
                Prerequisite.Unknown(reason(value["reason"]))
            }
            "COURSE" -> {
                objectFields(value, setOf("kind", "courseId"), "COURSE")
                val id = value["courseId"]
                require(id is String && key.matches(id)) { "COURSE courseId must be a valid catalog ID" }
                Prerequisite.Course(id)
            }
            "AND", "OR" -> {
                objectFields(value, setOf("kind", "children"), "AND/OR")
                val children = orderedChildren(value["children"]).map { decodeNode(it, depth + 1, budget) }
                if (value["kind"] == "AND") Prerequisite.AllOf(children) else Prerequisite.AnyOf(children)
            }
            else -> throw IllegalArgumentException("Unknown prerequisite kind")
        }
    }

    private fun objectFields(value: Any?, fields: Set<String>, label: String): Map<*, *> {
        require(value is Map<*, *>) { "$label must be an object" }
        require(value.keys == fields) { "$label requires exact fields: ${fields.joinToString()}" }
        return value
    }

    private fun orderedChildren(value: Any?): List<Any?> = when (value) {
        is List<*> -> {
            require(value.size in 1..MAX_CHILDREN) { "Prerequisite children must contain 1..$MAX_CHILDREN nodes" }
            value.toList()
        }
        is Map<*, *> -> {
            require(value.size in 1..MAX_CHILDREN) { "Prerequisite children must contain 1..$MAX_CHILDREN nodes" }
            val keys = (0 until value.size).map(Int::toString)
            require(value.keys == keys.toSet()) { "Prerequisite children need contiguous ordered keys starting at 0" }
            keys.map { value[it] }
        }
        else -> throw IllegalArgumentException("Prerequisite children must be an ordered map or list")
    }

    private fun inspect(expression: Prerequisite, depth: Int, budget: Budget) {
        budget.node(depth)
        when (expression) {
            is Prerequisite.Course -> {
                require(key.matches(expression.courseId)) { "COURSE courseId must be a valid catalog ID" }
                require(budget.references.add(expression.courseId)) { "Duplicate prerequisite reference: ${expression.courseId}" }
                require(budget.references.size <= MAX_REFERENCES) { "Prerequisite references exceed $MAX_REFERENCES" }
            }
            is Prerequisite.Unknown -> reason(expression.reason)
            is Prerequisite.AllOf -> inspectChildren(expression.children, depth, budget, allowNone = true)
            is Prerequisite.AnyOf -> inspectChildren(expression.children, depth, budget, allowNone = false)
        }
    }

    private fun inspectChildren(children: List<Prerequisite>, depth: Int, budget: Budget, allowNone: Boolean) {
        require(children.size <= MAX_CHILDREN && (allowNone || children.isNotEmpty())) {
            "Prerequisite children must contain ${if (allowNone) 0 else 1}..$MAX_CHILDREN nodes"
        }
        children.forEach { inspect(it, depth + 1, budget) }
    }

    private fun reason(value: Any?): String {
        require(value is String && value.isNotBlank() && value.length <= 240) { "UNKNOWN reason must contain 1..240 characters" }
        return value
    }

    private fun encodeNode(expression: Prerequisite): Map<String, Any> = when (expression) {
        is Prerequisite.Course -> linkedMapOf("kind" to "COURSE", "courseId" to expression.courseId)
        is Prerequisite.Unknown -> linkedMapOf("kind" to "UNKNOWN", "reason" to expression.reason)
        is Prerequisite.AllOf -> if (expression.children.isEmpty()) mapOf("kind" to "NONE")
            else linkedMapOf("kind" to "AND", "children" to encodeChildren(expression.children))
        is Prerequisite.AnyOf -> linkedMapOf("kind" to "OR", "children" to encodeChildren(expression.children))
    }

    private fun encodeChildren(children: List<Prerequisite>): Map<String, Any> =
        children.mapIndexed { index, expression -> index.toString() to encodeNode(expression) }.toMap()

    private fun rejectCycles(references: Map<String, List<String>>) {
        val visited = mutableSetOf<String>()
        val active = mutableListOf<String>()
        fun visit(id: String) {
            val index = active.indexOf(id)
            require(index < 0) { "Prerequisite cycle: ${(active.drop(index) + id).joinToString(" -> ")}" }
            if (id in visited) return
            active += id
            references.getValue(id).sorted().forEach(::visit)
            active.removeAt(active.lastIndex)
            visited += id
        }
        references.keys.forEach(::visit)
    }

    private class Budget {
        private var nodes = 0
        val references = linkedSetOf<String>()
        fun node(depth: Int) {
            require(depth <= MAX_DEPTH) { "Prerequisite depth exceeds $MAX_DEPTH" }
            require(++nodes <= MAX_NODES) { "Prerequisite nodes exceed $MAX_NODES" }
        }
    }
}
