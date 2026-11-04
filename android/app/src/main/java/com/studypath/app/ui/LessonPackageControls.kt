package com.studypath.app.ui

import android.view.View
import android.widget.LinearLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.PublishedModuleSummary
import com.studypath.app.data.StoredTextPackage
import com.studypath.app.data.TextPackageKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Shared download state. A cancelled wait never promotes staged bytes to ready. */
internal class LessonPackageControls(
    private val owner: LifecycleOwner,
    private val repository: FirebaseStudyRepository,
    private val openLesson: (TextPackageKey) -> Unit,
    private val changed: () -> Unit,
) {
    var downloaded: List<StoredTextPackage> = emptyList()
        private set
    private val notices = mutableMapOf<TextPackageKey, String>()
    private var activeKey: TextPackageKey? = null
    private var operation: Job? = null
    private var removing = false
    private var downloadStage = "Downloading…"

    init {
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                try {
                    repository.operationStates().collect {
                        if (activeKey == null) { refresh(); changed() }
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Session changes stop this owner's status observer. */ }
            }
        }
    }

    suspend fun refresh() {
        downloaded = withContext(Dispatchers.IO) { repository.retainedTextPackages() }
    }

    fun bind(content: LinearLayout, summary: PublishedModuleSummary) {
        val key = TextPackageKey(summary.curriculumVersion, summary.moduleId, summary.version)
        val retained = downloaded.firstOrNull { it.key == key }
        val busy = activeKey == key
        content.label(when {
            busy && removing -> "Removing…"
            busy -> downloadStage
            retained?.state == com.studypath.app.data.TextPackageState.REMOVING -> "Removal unfinished · try again"
            retained?.ready == true -> "Ready offline"
            retained != null -> "Download unfinished · try again"
            else -> "Not downloaded"
        }, 14f).apply { tag = "package_status_${summary.moduleId}_v${summary.version}"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        notices[key]?.let { content.label(it, 14f).accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        if (retained?.state == com.studypath.app.data.TextPackageState.REMOVING && !busy) {
            content.action("Retry removal") { mutate(summary, true) }.apply { tag = "package_retry_removal_${summary.moduleId}_v${summary.version}"; isEnabled = activeKey == null }
        } else if (retained?.ready == true && !busy) {
            content.action("Open lesson") { openLesson(key) }.apply { tag = "package_open_${summary.moduleId}_v${summary.version}"; contentDescription = "Open ${summary.title}, version ${summary.version}" }
            retained.verified.assets.forEach { asset ->
                content.action("Open PDF · ${asset.id}") {
                    (content.context as? MainActivity)?.openDetails(OfflinePdfFragment.create(key, asset.id))
                }.apply { tag = "package_pdf_${asset.id}"; quietAction(); contentDescription = "Open companion PDF ${asset.id} from ${summary.title}" }
            }
            content.action("Remove download") {
                MaterialAlertDialogBuilder(content.context).setTitle("Remove downloaded lesson?")
                    .setMessage("Remove this download of ${summary.title}? Your progress and results are kept.")
                    .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ -> mutate(summary, true) }.show()
            }.apply { tag = "package_remove_${summary.moduleId}_v${summary.version}"; isEnabled = activeKey == null; contentDescription = "Remove downloaded ${summary.title}, version ${summary.version}" }
        } else if (busy) {
            content.action(if (removing) "Stop waiting" else "Cancel") {
                operation?.cancel()
            }.apply { tag = "package_cancel_${summary.moduleId}_v${summary.version}"; contentDescription = if (removing) "Stop waiting for removal of ${summary.title}" else "Stop waiting for download of ${summary.title}" }
        } else {
            content.action(if (notices.containsKey(key)) "Retry download" else "Download lesson") { mutate(summary, false) }
                .apply { tag = "package_download_${summary.moduleId}_v${summary.version}"; isEnabled = activeKey == null; contentDescription = "Download ${summary.title}, version ${summary.version}" }
        }
    }

    private fun mutate(summary: PublishedModuleSummary, remove: Boolean) {
        if (activeKey != null) return
        val key = TextPackageKey(summary.curriculumVersion, summary.moduleId, summary.version)
        activeKey = key
        removing = remove
        downloadStage = "Downloading…"
        notices.remove(key)
        changed()
        operation = owner.lifecycleScope.launch {
            try {
                withTimeout(120_000) {
                    if (remove) repository.removeTextPackage(key) else repository.downloadTextPackage(summary) { stage ->
                        downloadStage = when (stage) {
                            "Verifying package text…", "Verifying files…" -> "Checking download…"
                            "Downloading and verifying PDF files…" -> "Downloading PDF…"
                            "Confirming sync…" -> "Finishing download…"
                            else -> "Downloading…"
                        }
                        changed() }
                }
                if (remove) notices[key] = "Download removed. Progress and results are kept." else notices.remove(key)
            } catch (_: TimeoutCancellationException) {
                notices[key] = if (remove) "Couldn’t finish removing. Reconnect and try again."
                    else "Download unfinished. Reconnect and try again."
            } catch (error: CancellationException) {
                notices[key] = if (remove) "Removal unfinished. The download is kept."
                    else "Download paused. Try again to finish."
                throw error
            } catch (error: Exception) {
                notices[key] = if (remove) "Could not remove the download. ${failureText(error)}"
                    else "Download is not ready. ${failureText(error)}"
            } finally {
                activeKey = null
                operation = null
                // The owner may be destroyed or signed out. Never resurrect its UI from a late callback.
                if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.CREATED)) {
                    owner.lifecycleScope.launch {
                        try { refresh(); changed() } catch (_: Exception) { /* Session changes clear private UI. */ }
                    }
                }
            }
        }
    }
}
