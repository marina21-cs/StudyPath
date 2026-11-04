package com.studypath.app.ui

import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.ReadingStarter
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Lifecycle-owned catalog display; opening an article remains an explicit user action. */
class ReadingStarterSection(
    private val content: LinearLayout,
    private val owner: LifecycleOwner,
    private val repository: FirebaseStudyRepository,
    private val open: (String) -> Unit,
) {
    private var job: Job? = null

    fun start() {
        job?.cancel()
        job = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                coroutineScope {
                    heading()
                    content.label("Loading reading suggestions…", 14f)
                    val timeout = launch {
                        delay(8000)
                        unavailable("Reading suggestions have not arrived. Reconnect or try again.")
                    }
                    try {
                        repository.readingStarters().catch {
                            timeout.cancel()
                            unavailable("Could not load reading suggestions. Search above or try again.")
                        }.collect { rows ->
                            timeout.cancel()
                            heading()
                            if (rows.isEmpty()) content.label("No reading suggestions yet. Search Wikipedia above.", 14f)
                            rows.forEach(::card)
                        }
                    } finally { timeout.cancel() }
                }
            }
        }
    }

    private fun heading() {
        content.removeAllViews()
        content.label("Reading ideas", 20f)
        content.label("Supplemental Wikipedia readings · internet needed", 14f)
    }

    private fun unavailable(message: String) {
        heading()
        content.label(message, 14f)
        content.action("Retry suggestions", ::start)
    }

    private fun card(row: ReadingStarter) {
        val card = content.card(row.title, "").apply { tag = "reading_starter_${row.id}" }
        card.label(row.description, 16f)
        card.action("Open live reading →") { open(row.pageKey) }.contentDescription = "Open live reading: ${row.title}"
        card.action("Gathered source") {
            content.context.showReadingSource("Gathered source",
                "${row.attribution}\n\n${row.licenseTitle}\n\nGathered revision ${row.revisionId}\nRevision date: ${row.revisionTimestamp}\nGathered ${dateLabel(row.gatheredAt)}\n\nThe live article may have changed.",
                row.revisionUrl, row.licenseUrl)
        }.apply { quietAction(); contentDescription = "Gathered source: ${row.title}" }
    }
}
