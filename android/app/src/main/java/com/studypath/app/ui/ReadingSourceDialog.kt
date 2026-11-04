package com.studypath.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.R
import com.studypath.app.data.ReadingSync
import com.studypath.app.data.RetainedReading

/** Scrollable actions remain reachable when source text and system fonts are large. */
fun Context.showReadingSource(title: String, message: String, revisionUrl: String, licenseUrl: String) {
    val dialog = MaterialAlertDialogBuilder(this)
    val body = dialog.context.column(16).apply {
        label(message).setTextIsSelectable(true)
        action("Open article revision") { context.openSource(revisionUrl) }.quietAction()
        action("Open license") { context.openSource(licenseUrl) }.quietAction()
    }
    val heading = dialog.context.column(16).apply { label(title, 20f) }
    dialog.setCustomTitle(heading).setView(dialog.context.scrolling(body))
        .setPositiveButton("Close", null).show()
}

/** A full touch target with quiet visual weight, used for secondary reading actions. */
internal fun MaterialButton.quietAction(): MaterialButton = apply {
    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
    setTextColor(context.tint(R.color.sp_ink))
    strokeWidth = context.dp(1)
    strokeColor = ColorStateList.valueOf(context.tint(R.color.sp_outline))
    minHeight = context.dp(48); minimumHeight = context.dp(48)
    iconTint = ColorStateList.valueOf(context.tint(R.color.sp_ink))
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = context.dp(4); bottomMargin = context.dp(2)
    }
}

internal fun LinearLayout.studyDisclosure(title: String, body: View, viewTag: String) {
    val toggle = action(title) {}.quietAction().apply {
        tag = viewTag
        iconGravity = MaterialButton.ICON_GRAVITY_END
        setIconResource(R.drawable.ic_expand_more)
        ViewCompat.setStateDescription(this, "Collapsed")
    }
    body.isVisible = false
    addView(body)
    toggle.setOnClickListener {
        body.isVisible = !body.isVisible
        toggle.setIconResource(if (body.isVisible) R.drawable.ic_expand_less else R.drawable.ic_expand_more)
        ViewCompat.setStateDescription(toggle, if (body.isVisible) "Expanded" else "Collapsed")
    }
}

internal fun LinearLayout.studyMetadata(firstLabel: String, firstValue: String, secondLabel: String, secondValue: String) {
    val row = LinearLayout(context).apply {
        orientation = if (resources.configuration.fontScale >= 1.5f) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
    }
    listOf(firstLabel to firstValue, secondLabel to secondValue).forEachIndexed { index, (title, value) ->
        val cell = context.column(12).apply {
            background = GradientDrawable().apply { setColor(context.tint(R.color.sp_background)); cornerRadius = context.dp(16).toFloat() }
            label(title, 12f)
            label(value, 14f)
        }
        row.addView(cell, if (row.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f).apply {
            if (index > 0) marginStart = context.dp(8)
        } else LinearLayout.LayoutParams(-1, -2).apply { if (index > 0) topMargin = context.dp(8) })
    }
    addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(12); bottomMargin = context.dp(12) })
}

internal fun readingCopyStatus(local: RetainedReading?): String = when {
    local?.deletionEditId != null -> "Removing… · still on this phone"
    local?.snapshotSync == ReadingSync.CONFIRMED -> "Ready offline"
    local?.snapshotSync == ReadingSync.PENDING -> "Saved on this phone"
    local?.snapshotSync == ReadingSync.REJECTED -> "Saved on this phone · try again"
    local != null -> "Saved on this phone"
    else -> "Preview · save to read offline"
}

internal fun readingPositionLabel(position: Int, excerptLength: Int): String = when {
    position <= 0 || excerptLength <= 0 -> "Start of excerpt"
    else -> "${(position.toLong().coerceAtMost(excerptLength.toLong()) * 100 / excerptLength).coerceAtLeast(1)}% through excerpt"
}

internal fun readingSyncBadge(local: RetainedReading?): String = when (local?.snapshotSync) {
    ReadingSync.CONFIRMED -> com.studypath.app.domain.SyncBadge.CONFIRMED
    ReadingSync.PENDING -> com.studypath.app.domain.SyncBadge.WAITING
    ReadingSync.REJECTED -> com.studypath.app.domain.SyncBadge.RETRY
    else -> com.studypath.app.domain.SyncBadge.UNKNOWN
}
