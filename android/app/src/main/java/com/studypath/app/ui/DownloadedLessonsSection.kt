package com.studypath.app.ui

import android.text.format.Formatter
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.data.TextPackageKey
import com.studypath.app.data.StoredTextPackage
import com.studypath.app.data.InteractiveLessonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ready packages stay distinct from retained downloads still awaiting acknowledgement. */
internal class DownloadedLessonsSection(
    private val content: LinearLayout,
    private val owner: LifecycleOwner,
    private val repository: FirebaseStudyRepository,
    openLesson: (TextPackageKey) -> Unit,
) {
    private var job: Job? = null
    private var preferences: LearnerPreferences? = null
    private var resumes = emptyMap<TextPackageKey, ReadyLessonResume>()
    private val packages = LessonPackageControls(owner, repository, openLesson, ::redraw)

    fun start() {
        job?.cancel()
        job = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                content.removeAllViews(); content.label("Loading downloaded lessons…", 20f)
                try {
                    packages.refresh()
                    resumes = readyLessonResumes(repository, packages.downloaded).associateBy { it.key }
                    render()
                    repository.learnerPreferences().collect { preferences = it; render() }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    content.removeAllViews(); content.label("Your lessons", 20f)
                    content.label(failureText(error), 14f)
                    content.action("Retry downloaded lessons", ::start)
                }
            }
        }
    }

    private fun redraw() {
        owner.lifecycleScope.launch {
            try {
                resumes = readyLessonResumes(repository, packages.downloaded).associateBy { it.key }
                render()
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { render() }
        }
    }

    private fun render() {
        content.removeAllViews()
        val ready = packages.downloaded.filter { it.ready }
        val unfinished = packages.downloaded.filterNot { it.ready }
        content.label("${ready.size} ready offline", 14f)
        if (packages.downloaded.isEmpty()) {
            content.card("A little learning, anywhere.", "Download a lesson from Study to learn offline.")
            return
        }
        val pending = content.context.column(0)
        (ready + unfinished).forEach { saved ->
            val row = saved.verified.summary
            val size = Formatter.formatShortFileSize(content.context, row.textBytes + row.assetBytes)
            val panel = (if (saved.ready) content else pending).card(row.title, "")
            val resume = resumes[saved.key]
            resume?.let {
                if (it.title != row.title) panel.label(it.title, 14f)
                panel.label("Step ${it.position + 1} of ${it.totalSteps}", 14f)
            }
            preferences?.let { selected ->
                if (row.curriculumVersion != selected.curriculumVersion || row.levelId != selected.levelId ||
                    row.subjectId !in selected.subjectIds || (row.classIds.isNotEmpty() && selected.classSectionId !in row.classIds)) {
                    panel.label("From earlier preferences", 14f)
                }
            }
            packages.bind(panel, row)
            if (resume != null) panel.findViewWithTag<MaterialButton>("package_open_${row.moduleId}_v${row.version}")?.apply {
                text = "Continue lesson →"
                setOnClickListener {
                    (content.context as? MainActivity)?.openDetails(InteractiveLessonFragment.forPackage(saved.key, resume.lessonId))
                }
            }
            panel.findViewWithTag<MaterialButton>("package_remove_${row.moduleId}_v${row.version}")?.quietAction()
            val details = content.context.column(0)
            details.label("$size", 14f)
            details.action("Lesson source and review") {
                MaterialAlertDialogBuilder(content.context).setTitle("Lesson source and review")
                    .setMessage("${row.title} · Version ${row.version}\n\nSource: ${row.source.getValue("title")}\nAuthor: ${row.source.getValue("author")}\nReference: ${row.source.getValue("reference")}\nLocation: ${row.source.getValue("locator")}\n\nReviewer: ${row.review.getValue("reviewer")}\nReviewed: ${row.review.getValue("reviewedOn")}\nApproval: ${row.review.getValue("approvalReference")}\nPermission: ${row.review.getValue("permissionReference")}")
                    .setPositiveButton("Close", null).show()
            }.quietAction()
            panel.studyDisclosure("Download details", details, "package_details_${row.moduleId}_v${row.version}")
        }
        if (unfinished.isNotEmpty()) content.studyDisclosure("Downloads needing attention · ${unfinished.size}", pending, "saved_pending_lessons")
    }
}

internal data class ReadyLessonResume(val key: TextPackageKey, val lessonId: String, val title: String,
    val position: Int, val totalSteps: Int, val updatedAt: Long)

/** Resume candidates always come from a complete, validated and acknowledged package. */
internal suspend fun readyLessonResumes(repository: FirebaseStudyRepository, retained: List<StoredTextPackage>? = null): List<ReadyLessonResume> {
    val packages = try { retained ?: withContext(Dispatchers.IO) { repository.downloadedTextPackages() } }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { return emptyList() }
    return packages.filter { it.ready }.mapNotNull { saved ->
        try {
            val module = withContext(Dispatchers.IO) { InteractiveLessonParser.parse(saved.verified) }
            module.lessons.mapNotNull { lesson ->
                repository.lessonRun(module, lesson.id)?.takeUnless { it.completed }?.let { run ->
                    ReadyLessonResume(saved.key, lesson.id, lesson.title, run.position, lesson.steps.size, run.updatedAt)
                }
            }.maxByOrNull { it.updatedAt }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { null }
    }
}
