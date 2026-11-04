package com.studypath.app.ui

import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.OperationRecord
import com.studypath.app.data.OperationSyncState
import com.studypath.app.domain.OperationPresentation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Owner-only durable metadata. Payloads, credentials and UID values never enter this view. */
internal class ProfileSyncSection(private val fragment: Fragment, private val repository: FirebaseStudyRepository) {
    private var rows: List<OperationRecord> = emptyList()
    private var notice: String? = null
    private var retrying = false

    fun start(content: LinearLayout) {
        val uid = repository.currentSession()?.uid ?: return
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.operationStates().catch {
                    content.removeAllViews()
                    content.label("Couldn’t check saved changes. ${failureText(it)}", 14f).tag = "profile_sync_status"
                }.collect { records ->
                    if (repository.currentSession()?.uid != uid) { content.removeAllViews(); return@collect }
                    rows = records.filter { it.uid == uid }
                    render(content, uid)
                }
            }
        }
    }

    private fun render(content: LinearLayout, uid: String) {
        content.removeAllViews()
        if (!fragment.isAdded || fragment.view == null || repository.currentSession()?.uid != uid) return
        content.label(OperationPresentation.summary(rows), 14f).apply {
            tag = "profile_sync_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val tracked = OperationPresentation.displayedRecords(rows).filterNot { it.relativePath.startsWith("withdrawnLessonAttempts/") }
        val retryable = tracked.filter(OperationPresentation::retryable)
        if (retryable.isNotEmpty()) content.action("Try again") {
            if (retryable.size == 1) retry(content, uid, retryable.single().operationId)
            else MaterialAlertDialogBuilder(content.context).setTitle("Choose a saved change")
                .setItems(retryable.map { "${OperationPresentation.title(it)} · ${dateLabel(it.createdAt)}" }.toTypedArray()) { _, index ->
                    retry(content, uid, retryable[index].operationId)
                }.setNegativeButton("Cancel", null).show()
        }.apply { tag = "profile_retry_now"; isEnabled = !retrying }
        notice?.let { content.label(it, 14f).tag = "profile_sync_notice" }
        content.action("Saved changes") {
            val pending = OperationPresentation.displayedRecords(rows).filter { it.state != OperationSyncState.CONFIRMED }.sortedBy { it.createdAt }
            if (pending.isEmpty()) {
                MaterialAlertDialogBuilder(content.context).setTitle("Saved changes")
                    .setMessage("${OperationPresentation.summary(rows)}")
                    .setPositiveButton("Close", null).show()
            } else {
                MaterialAlertDialogBuilder(content.context).setTitle("Saved changes")
                    .setItems(pending.map { "${OperationPresentation.title(it)} · ${OperationPresentation.status(it)}\n${dateLabel(it.createdAt)}" }.toTypedArray()) { _, index ->
                        show(content, uid, pending[index])
                    }.setNegativeButton("Close", null).show()
            }
        }.apply { tag = "profile_sync_action"; layoutParams.width = -1 }
    }

    private fun show(content: LinearLayout, uid: String, record: OperationRecord) {
        if (repository.currentSession()?.uid != uid) return
        val details = content.context.column(16)
        details.label(OperationPresentation.status(record), 18f)
        details.label(OperationPresentation.title(record), 14f)
        details.label("${dateLabel(record.createdAt)}", 14f)
        details.label(if (record.relativePath.startsWith("withdrawnLessonAttempts/"))
            "This lesson is unavailable. Your result stays on this phone."
        else when (record.state) {
            OperationSyncState.AWAITING_ACK -> "Still saving. Your change is kept on this phone."
            OperationSyncState.AUTH_REQUIRED -> "Sign in again to save this change."
            OperationSyncState.UNKNOWN_AFTER_RESTART -> "Your change is kept on this phone. Try saving again."
            OperationSyncState.REJECTED -> "Couldn’t save to your account. Your change is kept on this phone."
            else -> "Your change is kept on this phone."
        }, 14f)
        val dialog = MaterialAlertDialogBuilder(content.context).setTitle("Saved change")
            .setView(content.context.scrolling(details)).setNegativeButton("Close", null).create()
        if (OperationPresentation.retryable(record)) details.action("Try again") {
            dialog.dismiss()
            retry(content, uid, record.operationId)
        }.apply { tag = "profile_sync_retry"; isEnabled = !retrying; layoutParams.width = -1 }
        if (record.state == OperationSyncState.AUTH_REQUIRED) details.secondarySyncAction("Sign in again") {
            dialog.dismiss()
            if (repository.currentSession()?.uid == uid) (fragment.requireActivity() as MainActivity).logout()
        }
        dialog.show()
    }

    private fun retry(content: LinearLayout, uid: String, operationId: String) {
        if (retrying || repository.currentSession()?.uid != uid) return
        retrying = true; notice = "Saving…"; render(content, uid)
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                withTimeout(20_000) { repository.retryOperation(operationId) }
                notice = "Saved."
            } catch (_: TimeoutCancellationException) {
                notice = "Still saving. Your change is kept on this phone."
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice = failureText(error) }
            finally { retrying = false; render(content, uid) }
        }
    }

    private fun LinearLayout.secondarySyncAction(label: String, run: () -> Unit) = action(label, run).apply { layoutParams.width = -1 }
}
