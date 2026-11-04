package com.studypath.app.ui

import android.content.Context
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.FrameLayout
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.setPadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.studypath.app.R
import java.text.DateFormat
import java.util.Date

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
fun Context.tint(id: Int): Int = ContextCompat.getColor(this, id)
fun Context.column(padding: Int = 20): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding)); layoutParams = LinearLayout.LayoutParams(-1, -2)
}
fun LinearLayout.label(value: String, size: Float = 16f): TextView = TextView(context).also {
    val textSize = size.coerceAtLeast(12f)
    it.text = value; it.textSize = textSize
    it.typeface = ResourcesCompat.getFont(context, if (textSize >= 18f) R.font.nunito_bold else R.font.geist)
    it.includeFontPadding = false
    it.setTextColor(context.tint(R.color.sp_ink))
    it.setLineSpacing(context.dp(if (textSize >= 18f) 1 else 2).toFloat(), 1f)
    it.setPadding(0, context.dp(if (textSize >= 18f) 4 else 2), 0, context.dp(2))
    if (textSize >= 18f) ViewCompat.setAccessibilityHeading(it, true)
    addView(it, LinearLayout.LayoutParams(-1, -2))
}
fun LinearLayout.action(title: String, run: () -> Unit): MaterialButton = StudyButton(context).also {
    it.text = title; it.isAllCaps = false; it.minHeight = context.dp(56); it.minimumHeight = context.dp(56); it.maxLines = 3
    it.typeface = ResourcesCompat.getFont(context, R.font.nunito_bold)
    it.cornerRadius = context.dp(16); it.textSize = 16f
    it.backgroundTintList = ContextCompat.getColorStateList(context, R.color.button_fill)
    it.setTextColor(ContextCompat.getColorStateList(context, R.color.button_text))
    it.insetTop = 0; it.insetBottom = context.dp(4)
    it.setPadding(context.dp(16), context.dp(8), context.dp(16), context.dp(8))
    addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8); bottomMargin = context.dp(4) })
    it.setOnClickListener { run() }
}
fun LinearLayout.field(title: String, input: Int = android.text.InputType.TYPE_CLASS_TEXT): TextInputEditText {
    val box = TextInputLayout(context, null, com.google.android.material.R.attr.textInputFilledStyle)
    box.hint = title; box.boxBackgroundColor = context.tint(R.color.sp_card)
    box.setBoxCornerRadii(context.dp(16).toFloat(),context.dp(16).toFloat(),context.dp(16).toFloat(),context.dp(16).toFloat())
    val edit = TextInputEditText(box.context).apply { inputType = input; minHeight = context.dp(56); textSize = 16f }
    box.addView(edit, LinearLayout.LayoutParams(-1, -2))
    addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8) })
    return edit
}
fun LinearLayout.card(title: String, body: String, onOpen: (() -> Unit)? = null): LinearLayout {
    val card = MaterialCardView(context).apply {
        radius = context.dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
        strokeColor = context.tint(R.color.sp_outline)
        setCardBackgroundColor(context.tint(R.color.sp_card))
        if (onOpen != null) setOnClickListener { onOpen() }
    }
    val content = context.column(20)
    content.label(title, 18f).setPadding(0, 0, 0, context.dp(2))
    if (body.isNotBlank()) content.label(body, 14f)
    if (onOpen != null) content.action("Open reading", onOpen).contentDescription = "Open $title"
    card.addView(content)
    addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8); bottomMargin = context.dp(2) })
    return content
}
fun LinearLayout.studyArtwork() {
    val image = ImageView(context).apply {
        setImageResource(R.drawable.study_shapes); scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    addView(image, LinearLayout.LayoutParams(-1, context.dp(96)).apply { topMargin = context.dp(8); bottomMargin = context.dp(8) })
}
fun LinearLayout.lessonCard(title: String, detail: String, selected: Boolean, onOpen: () -> Unit) {
    val card = MaterialCardView(context).apply {
        radius = context.dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
        strokeColor = context.tint(if (selected) R.color.sp_blue else R.color.sp_outline)
        setCardBackgroundColor(context.tint(if (selected) R.color.sp_action else R.color.sp_card))
        setOnClickListener { onOpen() }
        isFocusable = true
        contentDescription = "Study $title"
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(12)); minimumHeight = context.dp(80)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    val art = ImageView(context).apply {
        setImageResource(R.drawable.study_shapes); scaleType = ImageView.ScaleType.CENTER_CROP
        background = ContextCompat.getDrawable(context, R.drawable.bg_cream_panel); clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    row.addView(art, LinearLayout.LayoutParams(context.dp(40), context.dp(40)).apply { marginEnd = context.dp(12) })
    val words = context.column(0)
    words.label(title, 18f).apply { setPadding(0, 0, 0, context.dp(4)); setTextColor(context.tint(if (selected) android.R.color.white else R.color.sp_ink)) }
    if (detail.isNotBlank()) words.label(detail, 14f).setTextColor(context.tint(if (selected) android.R.color.white else R.color.sp_ink))
    row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
    val arrow = TextView(context).apply {
        text = "›"; textSize = 24f; gravity = Gravity.CENTER
        typeface = ResourcesCompat.getFont(context, R.font.nunito_bold)
        setTextColor(context.tint(if (selected) android.R.color.white else R.color.sp_ink))
    }
    row.addView(arrow, LinearLayout.LayoutParams(context.dp(24), context.dp(48)).apply { marginStart = context.dp(8) })
    card.addView(row)
    addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = context.dp(8); bottomMargin = context.dp(4) })
}
fun LinearLayout.giraffe(pose: String = "wave", height: Int = 140): GiraffeMascotView = GiraffeMascotView(context).also {
    addView(it, LinearLayout.LayoutParams(-1, context.dp(height)).apply { topMargin = context.dp(4); bottomMargin = context.dp(8) })
    it.play(when(pose.lowercase()) { "sleep" -> GiraffeMascotView.Pose.SLEEP; "wake" -> GiraffeMascotView.Pose.WAKE; else -> GiraffeMascotView.Pose.WAVE })
}
fun LinearLayout.detailArtwork() { giraffe("wave", 112) }
fun Context.scrolling(content: View): ScrollView = ScrollView(this).apply {
    isFillViewport = true; clipToPadding = false; isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
    addView(content, FrameLayout.LayoutParams(-1, -2))
}
fun Context.openSource(url: String) {
    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    catch (_: ActivityNotFoundException) { Toast.makeText(this, "Install or enable a browser to open this source.", Toast.LENGTH_LONG).show() }
}
fun dateLabel(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))
fun failureText(error: Throwable): String = when (error) {
    is kotlinx.coroutines.TimeoutCancellationException -> "Still saving. Check your connection and try again."
    is java.io.IOException -> "Could not reach the reading service. Check your connection and try again."
    else -> error.message ?: "This action could not finish. Please try again."
}
