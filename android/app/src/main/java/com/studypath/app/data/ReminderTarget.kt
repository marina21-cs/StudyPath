package com.studypath.app.data

sealed interface ReminderTarget {
    data class LegacyModule(val moduleId: String) : ReminderTarget
    data class ModuleVersion(val curriculumVersion: String, val moduleId: String, val version: Int) : ReminderTarget
    data class SavedReading(val readingId: String) : ReminderTarget
}

object ReminderTargets {
    fun target(value: StudyReminder): ReminderTarget = value.target ?: ReminderTarget.LegacyModule(requireNotNull(value.moduleId))
    fun identity(value: StudyReminder): String = when (val target = target(value)) {
        is ReminderTarget.LegacyModule -> "legacy:${target.moduleId}"
        is ReminderTarget.ModuleVersion -> "module:${target.curriculumVersion}:${target.moduleId}:${target.version}"
        is ReminderTarget.SavedReading -> "reading:${target.readingId}"
    }
    fun fields(value: StudyReminder): Map<String, Any> = when (val target = target(value)) {
        is ReminderTarget.LegacyModule -> {
            DatabaseRecords.requireKey(target.moduleId); require(value.moduleId == target.moduleId)
            mapOf("moduleId" to target.moduleId)
        }
        is ReminderTarget.ModuleVersion -> {
            listOf(target.curriculumVersion, target.moduleId).forEach(DatabaseRecords::requireKey)
            require(value.moduleId == target.moduleId && target.version in 1..1_000_000)
            mapOf("schemaVersion" to 2, "targetType" to "MODULE_VERSION", "curriculumVersion" to target.curriculumVersion,
                "moduleId" to target.moduleId, "moduleVersion" to target.version)
        }
        is ReminderTarget.SavedReading -> {
            DatabaseRecords.requireKey(target.readingId); require(value.moduleId == null)
            mapOf("schemaVersion" to 2, "targetType" to "SAVED_READING", "readingId" to target.readingId)
        }
    }
    fun parse(fields: Map<String, Any?>): ReminderTarget? {
        if (!fields.containsKey("schemaVersion")) {
            require(listOf("targetType", "curriculumVersion", "moduleVersion", "readingId").none(fields::containsKey))
            return null
        }
        require((fields["schemaVersion"] as? Number)?.toDouble() == 2.0)
        return when (fields["targetType"]) {
            "MODULE_VERSION" -> {
                require(!fields.containsKey("readingId"))
                val version = (fields["moduleVersion"] as? Number)?.toDouble() ?: error("Missing reminder module version.")
                require(version.isFinite() && version % 1 == 0.0 && version in 1.0..1_000_000.0)
                ReminderTarget.ModuleVersion(fields["curriculumVersion"] as? String ?: error("Missing reminder curriculum."),
                    fields["moduleId"] as? String ?: error("Missing reminder module."), version.toInt())
            }
            "SAVED_READING" -> {
                require(listOf("moduleId", "moduleVersion", "curriculumVersion").none(fields::containsKey))
                ReminderTarget.SavedReading(fields["readingId"] as? String ?: error("Missing reminder reading."))
            }
            else -> error("Unknown reminder target.")
        }
    }
}
