package com.studypath.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.net.ConnectivityManager
import android.net.Network
import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.view.doOnLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.FamilyGrant
import com.studypath.app.data.FamilyOwnerState
import com.studypath.app.data.FamilySharingRecords
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.min

/** Owner controls and an explicitly opened, network-only recipient view. No summary is persisted. */
class FamilySharingFragment : Fragment() {
    private val client get() = requireNotNull((requireActivity().application as StudyPathApplication).graph.family) {
        "Family sharing isn’t available yet."
    }
    private lateinit var status: TextView
    private lateinit var ownerCards: LinearLayout
    private lateinit var invitation: TextView
    private lateinit var create: MaterialButton
    private lateinit var acceptCode: TextInputEditText
    private lateinit var viewCode: TextInputEditText
    private lateinit var summary: TextView
    private var roleFilter = "student"
    private var ownerState: FamilyOwnerState? = null
    private var mutation: Job? = null
    private var reader: Job? = null
    private var readerGeneration = 0L
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val body = requireContext().column()
        body.label("Share completed lessons only. Scores, answers and notes stay private.", 14f)
        status = body.label("Loading sharing controls…", 14f).apply { tag = "family_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        roleFilter = state?.getString("role_filter") ?: roleFilter
        val filters = requireContext().column(0).apply {
            orientation = if (resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }
        body.addView(filters)
        val student = body.card("Share my summary", "Invite someone, then choose who can view.")
        student.tag = "family_student_controls"
        create = student.action("Create invitation") { perform("Preparing invitation") {
            val value = client.createInvitation()
            invitation.text = value.code()
            status.text = "Send this invitation code to your intended recipient. It grants no access by itself. You choose whether to share after they accept."
        } }.apply { isEnabled = false }
        invitation = student.label("", 13f).apply { tag = "family_invitation_code"; setTextIsSelectable(true) }
        student.secondaryAction("Copy invitation") { copy("StudyPath invitation", invitation.text.toString()) }
        student.secondaryAction("Refresh sharing controls") { loadOwner() }
        ownerCards = requireContext().column(0); student.addView(ownerCards)
        val recipient = body.card("View a shared summary", "Accept an invitation, then ask for a viewing code.")
        recipient.tag = "family_recipient_controls"
        val ownUid = (requireActivity().application as StudyPathApplication).graph.repository.currentSession()?.uid.orEmpty()
        val identity = requireContext().column(0).apply { label(ownUid, 13f).setTextIsSelectable(true) }
        recipient.studyDisclosure("My recipient ID", identity, "family_recipient_id")
        acceptCode = recipient.field("Invitation code").apply { tag = "family_accept_code" }
        recipient.action("Accept invitation") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Accept this limited invitation?")
                .setMessage("You agree to receive only the student's completion count and update time if they explicitly grant access. The grant expires and the student can revoke it.")
                .setNegativeButton("Cancel", null).setPositiveButton("Accept") { _, _ -> perform("Accepting invitation") {
                    val accepted = client.acceptInvitation(acceptCode.text.toString().trim())
                    status.text = "Invitation ${accepted.id} accepted. The student still needs to choose to share with your verified account."
                } }.show()
        }
        recipient.label("View online only", 14f)
        viewCode = recipient.field("Viewing code").apply { tag = "family_view_code" }
        recipient.action("Open shared summary") { openSummary() }
        summary = recipient.label("No shared summary is open.", 18f).apply { tag = "family_summary"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val buttons = mutableListOf<Pair<String, MaterialButton>>()
        fun updateFilter() {
            student.visibility = if (roleFilter == "family") View.GONE else View.VISIBLE
            recipient.visibility = if (roleFilter == "student") View.GONE else View.VISIBLE
            buttons.forEach { (role, button) ->
                button.isSelected = role == roleFilter
                button.backgroundTintList = android.content.res.ColorStateList.valueOf(requireContext().tint(
                    if (button.isSelected) com.studypath.app.R.color.sp_ink else com.studypath.app.R.color.sp_card))
                button.setTextColor(requireContext().tint(if (button.isSelected) android.R.color.white else com.studypath.app.R.color.sp_ink))
                androidx.core.view.ViewCompat.setStateDescription(button, if (button.isSelected) "Selected" else "Not selected")
            }
        }
        listOf("all" to "All", "student" to "Share", "family" to "View").forEach { (role, title) ->
            buttons += role to filters.action(title) {
                roleFilter = role
                if (role == "student") clearSummary("No shared summary is open. Reopen it for a fresh permission check.")
                updateFilter()
            }.apply { tag = "family_role_$role"
                layoutParams = if (filters.orientation == LinearLayout.VERTICAL) LinearLayout.LayoutParams(-1, -2)
                    else LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(4) }
                maxLines = Int.MAX_VALUE }
        }
        updateFilter()
        return requireContext().scrolling(body)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("role_filter", roleFilter)
    }

    override fun onStart() {
        super.onStart()
        val manager = requireContext().getSystemService(ConnectivityManager::class.java)
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { activity?.runOnUiThread {
                if (view != null) clearSummary("Connection changed. Reopen the summary for a fresh permission check.")
            } }
        }
        runCatching { manager.registerDefaultNetworkCallback(listener); callback = listener }
        loadOwner()
    }

    override fun onStop() {
        callback?.let { listener -> context?.getSystemService(ConnectivityManager::class.java)?.let { runCatching { it.unregisterNetworkCallback(listener) } } }
        callback = null
        clearSummary("No shared summary is open. Reopen it when connected.")
        mutation?.cancel(); mutation = null
        ownerState = null; ownerCards.removeAllViews(); invitation.text = ""; create.isEnabled = false
        super.onStop()
    }

    override fun onPause() {
        clearSummary("No shared summary is open. Reopen it for a fresh permission check.")
        super.onPause()
    }

    private fun loadOwner() = perform("Checking current sharing permission") {
        renderOwnerState(client.ownerState())
    }

    private fun renderOwnerState(value: FamilyOwnerState) {
        ownerState = value
        create.isEnabled = value.unavailableReason == null
        ownerCards.removeAllViews()
        status.text = value.unavailableReason ?: if (value.synthetic) "Demo sharing · choose who can view." else "Choose whether to share a limited summary."
        if (value.grants.isEmpty()) ownerCards.label("No one has access yet.", 14f)
        value.grants.forEach(::renderGrant)
        val available = FamilySharingRecords.slots.firstOrNull { slot -> value.grants.none { it.slotId == slot && it.status == "ACTIVE" } }
        if (value.unavailableReason == null && available != null) value.acceptances.forEach { accepted ->
            val card = ownerCards.card("Accepted recipient", "Account ${accepted.recipientUid}\nExpires ${dateLabel(accepted.expiresAt)}")
            card.action("Share with this recipient") {
                MaterialAlertDialogBuilder(requireContext()).setTitle("Share completion summary?")
                    .setMessage("This account may view your self-reported completion count and update time until expiry. You can revoke access.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Share completion summary") { _, _ -> perform("Sharing") {
                        client.grant(accepted.id, available)
                        renderOwnerState(client.ownerState())
                        status.text = "Sharing is on. Send the viewing code."
                    } }.show()
            }
        }
    }

    private fun renderGrant(grant: FamilyGrant) {
        val card = ownerCards.card("${grant.slotId} · ${if (grant.status == "ACTIVE") "Sharing active" else "Sharing revoked"}",
            "Account ${grant.recipientUid}\nExpires ${dateLabel(grant.expiresAt)}")
        if (grant.status != "ACTIVE") return
        val code = grant.link.code()
        card.label(code, 13f).apply { tag = "family_viewing_code_${grant.slotId}"; setTextIsSelectable(true) }
        card.secondaryAction("Copy viewing code") { copy("StudyPath viewing code", code) }
        card.secondaryAction("Update summary") { perform("Updating summary") {
            client.refresh(grant.slotId, grant.generation)
            status.text = "Summary updated."
        } }
        card.secondaryAction("Revoke sharing") {
            MaterialAlertDialogBuilder(requireContext()).setTitle("Revoke this sharing grant?")
                .setMessage("This stops future access. Information already viewed cannot be recalled.")
                .setNegativeButton("Cancel", null).setPositiveButton("Revoke") { _, _ -> perform("Stopping sharing") {
                    client.revoke(grant.slotId, grant.generation)
                    card.removeAllViews(); card.label("Sharing stopped", 16f)
                    status.text = "Access removed."
                } }.show()
        }
    }

    private fun perform(label: String, block: suspend () -> Unit) {
        if (mutation?.isActive == true) return
        mutation = viewLifecycleOwner.lifecycleScope.launch {
            status.text = "$label…"
            try { withTimeout(60_000) { block() } }
            catch (error: CancellationException) {
                if (error is kotlinx.coroutines.TimeoutCancellationException) status.text = "Still updating. Refresh to check."
                else throw error
            }
            catch (error: Exception) { status.text = failureText(error) }
        }
    }

    private fun openSummary() {
        clearSummary("Opening summary…")
        val generation = readerGeneration
        val code = viewCode.text.toString().trim()
        reader = viewLifecycleOwner.lifecycleScope.launch {
            var firstResult = true
            fun showResult(message: String) {
                summary.text = message
                if (firstResult) {
                    firstResult = false
                    summary.doOnLayout { result ->
                        if (generation == readerGeneration && isResumed) {
                            result.requestRectangleOnScreen(Rect(0, 0, result.width, result.height), true)
                        }
                    }
                }
            }
            try {
                while (true) {
                    summary.text = "Opening summary…"
                    val value = withTimeout(25_000) { client.readSummary(code) }
                    if (generation != readerGeneration) return@launch
                    showResult("Self-reported completed lessons: ${value.completedLessonCount}\nUpdated ${dateLabel(value.asOf)}\nAccess expires ${dateLabel(value.expiresAt)}")
                    delay(min(10_000L, (value.expiresAt - value.serverNow).coerceAtLeast(1)))
                }
            } catch (error: CancellationException) {
                if (error !is kotlinx.coroutines.TimeoutCancellationException) throw error
                if (generation == readerGeneration) showResult("Summary unavailable. Try again.")
            } catch (error: Exception) { if (generation == readerGeneration) showResult("Summary unavailable. ${failureText(error)}") }
        }
    }

    private fun clearSummary(message: String) { readerGeneration++; reader?.cancel(); reader = null; if (::summary.isInitialized) summary.text = message }
    private fun copy(label: String, text: String) {
        if (text.isBlank()) return
        requireContext().getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        status.text = "Code copied. Share it only with the intended person."
    }
    private fun LinearLayout.secondaryAction(title: String, run: () -> Unit): MaterialButton = action(title, run).apply {
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
        setTextColor(context.tint(com.studypath.app.R.color.sp_ink))
        strokeColor = android.content.res.ColorStateList.valueOf(context.tint(com.studypath.app.R.color.sp_outline))
        strokeWidth = context.dp(1)
    }
}
