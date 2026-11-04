package com.studypath.app.ui

import android.text.format.Formatter
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.studypath.app.data.TextPackageKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.ModuleLibrary
import com.studypath.app.data.ModuleLibraryStatus
import com.studypath.app.data.PublishedModuleSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Published metadata is discovery only; it never opens a legacy demonstration payload. */
class LessonLibrarySection(
    private val content: LinearLayout,
    private val owner: LifecycleOwner,
    private val repository: FirebaseStudyRepository,
    private val editPreferences: () -> Unit,
    private val openLesson: (TextPackageKey) -> Unit,
) {
    private var job: Job? = null
    private var latest: Pair<ModuleLibrary, LearnerPreferences>? = null
    private val packages = LessonPackageControls(owner, repository, openLesson) { redraw() }

    fun start() {
        job?.cancel()
        val ownerUid = repository.currentSession()?.uid ?: return
        job = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coroutineScope {
                    heading()
                    content.label("Loading lessons…", 14f)
                    val timeout = launch {
                        delay(8000)
                        unavailable("Lessons have not arrived. Reconnect or try again.")
                    }
                    try {
                        combine(repository.moduleLibrary(), repository.learnerPreferences()) { library, preferences ->
                            library to preferences
                        }.collect { (library, preferences) ->
                            timeout.cancel()
                            if (repository.currentSession()?.uid != ownerUid) return@collect
                            packages.refresh()
                            if (repository.currentSession()?.uid != ownerUid) return@collect
                            latest = library to preferences
                            render(library, preferences)
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) {
                        // A refresh can finish after sign-out; handle collector failures too.
                        latest = null
                        if (repository.currentSession()?.uid == ownerUid)
                            unavailable("Could not load lessons. Your readings are still available.")
                        else content.removeAllViews()
                    } finally { timeout.cancel() }
                }
            }
        }
    }

    private fun redraw() { latest?.let { (library, preferences) -> render(library, preferences) } }

    private fun heading() {
        content.removeAllViews()
    }

    private fun render(library: ModuleLibrary, preferences: LearnerPreferences) {
        heading()
        if (!preferences.onboardingComplete || library.status == ModuleLibraryStatus.INCOMPLETE_SELECTION) {
            val hasChoices = preferences.levelId != null || preferences.curriculumVersion != null ||
                preferences.subjectIds.isNotEmpty() || preferences.classSectionId != null
            content.label(if (hasChoices) "No lessons for your subjects yet." else "Choose your subjects to find lessons.", 14f)
            preferenceAction(if (hasChoices) "Change subjects" else "Choose subjects")
            return
        }
        when (library.status) {
            ModuleLibraryStatus.UNSUPPORTED_SELECTION -> {
                content.label("No lessons for your subjects yet.", 14f)
                preferenceAction()
            }
            ModuleLibraryStatus.EMPTY -> emptyLessons()
            ModuleLibraryStatus.AVAILABLE -> {
                // Independently guard against transient combinations while profile listeners update.
                val matching = library.modules.filter { it.matches(preferences) }
                if (matching.isEmpty()) emptyLessons()
                else {
                    content.label("Download a lesson to study offline.", 14f)
                    matching.forEach(::lessonCard)
                    preferenceAction()
                }
            }
            ModuleLibraryStatus.INCOMPLETE_SELECTION -> Unit
        }
    }

    private fun emptyLessons() {
        content.label("No lessons for your subjects yet.", 14f)
        preferenceAction()
    }

    private fun preferenceAction(title: String = "Change subjects") {
        content.action(title, editPreferences).quietAction()
    }

    private fun unavailable(message: String) {
        heading()
        content.label(message, 14f)
        content.action("Retry lessons", ::start)
        preferenceAction()
    }

    private fun lessonCard(row: PublishedModuleSummary) {
        val size = Formatter.formatShortFileSize(content.context, row.textBytes + row.assetBytes)
        val card = content.card(row.title, "Lesson + PDF · $size").apply {
            tag = "published_lesson_${row.moduleId}_v${row.version}"
        }
        packages.bind(card, row)
        card.findViewWithTag<MaterialButton>("package_remove_${row.moduleId}_v${row.version}")?.quietAction()
        card.action("Lesson source and review") { showDetails(row) }.apply {
            quietAction(); contentDescription = "Lesson source and review: ${row.title}, version ${row.version}"
        }
    }

    private fun showDetails(row: PublishedModuleSummary) {
        val dialog = MaterialAlertDialogBuilder(content.context)
        val body = dialog.context.column(16)
        body.label("${row.title} · Version ${row.version}", 20f)
        val textSize = Formatter.formatShortFileSize(content.context, row.textBytes)
        val assetSize = Formatter.formatShortFileSize(content.context, row.assetBytes)
        body.label("Lesson: $textSize · Files: $assetSize", 14f)
        body.label("Source", 18f)
        body.label("${row.source.getValue("title")}\nAuthor: ${row.source.getValue("author")}\nSource version: ${row.source.getValue("version")}\nReference: ${row.source.getValue("reference")}\nLocation: ${row.source.getValue("locator")}", 14f).setTextIsSelectable(true)
        body.label("Approval record", 18f)
        body.label("Reviewer: ${row.review.getValue("reviewer")}\nReviewed: ${row.review.getValue("reviewedOn")}\nApproval reference: ${row.review.getValue("approvalReference")}\nPermission reference: ${row.review.getValue("permissionReference")}", 14f).setTextIsSelectable(true)
        dialog.setView(dialog.context.scrolling(body)).setPositiveButton("Close", null).show()
    }

    private fun PublishedModuleSummary.matches(preferences: LearnerPreferences): Boolean = approved &&
        curriculumVersion == preferences.curriculumVersion && levelId == preferences.levelId &&
        subjectId in preferences.subjectIds && (classIds.isEmpty() || preferences.classSectionId in classIds)
}
