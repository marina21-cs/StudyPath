package com.studypath.app.ui

import android.graphics.Color
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.studypath.app.R
import com.studypath.app.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Callers keep category metadata; the row uses semantic Tide surface colors.
internal fun subjectColor(category: String): Int = Color.TRANSPARENT
internal fun subjectIcon(category: String) = when(category) {
    "Mathematics" -> R.drawable.ic_subject_math; "Science" -> R.drawable.ic_subject_science
    "Technology" -> R.drawable.ic_subject_code; "Languages" -> R.drawable.ic_subject_language
    "Humanities" -> R.drawable.ic_subject_humanities; else -> R.drawable.ic_subject_book
}

/** Shared row used by each tab: identical subject IDs, counts and destinations. */
internal fun LinearLayout.workspaceRow(title: String, detail: String, icon: Int, color: Int, onOpen: () -> Unit): MaterialCardView {
    val card = MaterialCardView(context).apply {
        radius = context.dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
        setCardBackgroundColor(context.tint(R.color.sp_card)); isClickable = true; isFocusable = true
        contentDescription = listOf(title, detail).filter { it.isNotBlank() }.joinToString(", ")
        setOnClickListener { onOpen() }
    }
    val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(14), context.dp(14), context.dp(12), context.dp(14))
        minimumHeight = context.dp(72); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    row.addView(ImageView(context).apply { setImageResource(icon); setPadding(context.dp(10),context.dp(10),context.dp(10),context.dp(10))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(context.tint(R.color.sp_cream)); cornerRadius=context.dp(14).toFloat() }
        imageTintList = ColorStateList.valueOf(context.tint(R.color.sp_ink)) },
        LinearLayout.LayoutParams(context.dp(48), context.dp(48)).apply { marginEnd = context.dp(12) })
    val words = context.column(0)
    words.label(title, 16f).typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.nunito_bold)
    if (detail.isNotBlank()) words.label(detail, 12f)
    row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
    row.addView(ImageView(context).apply { setImageResource(R.drawable.ic_chevron_right); imageTintList = ColorStateList.valueOf(context.tint(R.color.sp_ink)) }, LinearLayout.LayoutParams(context.dp(20), context.dp(24)))
    card.addView(row); addView(card, LinearLayout.LayoutParams(-1,-2).apply { topMargin=context.dp(8) })
    return card
}
internal fun LinearLayout.subjectRow(subject: PersonalSubject, data: PersonalWorkspace, onOpen: () -> Unit): MaterialCardView {
    val tasks = data.tasks.count { it.subjectId == subject.id && !it.done }
    val materials = data.materials.count { it.subjectId == subject.id }
    return workspaceRow(subject.title, "${subject.categoryLabel()} · $tasks to do · $materials saved", subjectIcon(subject.categoryLabel()), subjectColor(subject.categoryLabel()), onOpen)
        .apply { tag = "subject_${subject.id}" }
}
internal fun Fragment.watchPersonal(onData: (PersonalWorkspace) -> Unit, onFailure: () -> Unit) {
    val store = personalStore()
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { store.observe().collect { onData(it) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { onFailure() }
        }
    }
}
internal fun LinearLayout.compactAction(title: String, action: () -> Unit) = this.action(title, action).quietAction().apply {
    textSize = 14f; minHeight = context.dp(48); minimumHeight = context.dp(48)
    strokeWidth = 0; backgroundTintList = ColorStateList.valueOf(context.tint(R.color.sp_cream))
    setIconResource(R.drawable.ic_chevron_right); iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_END
    iconSize = context.dp(18); setPadding(context.dp(14), context.dp(8), context.dp(14), context.dp(8))
}

/** Tide Play tiles: shared geometry and colors from the selected slide-32 design. */
internal fun LinearLayout.academicToolkit(open: (String) -> Unit) {
    val items = listOf(
        arrayOf("Grades & scores", "Set up your template", "∑", "#FFC6A3", "grades"),
        arrayOf("GWA", "See the calculation", "÷", "#D8CCFF", "gwa"),
        arrayOf("What-if", "Explore a target", "↗", "#BCE8D4", "what-if"),
        arrayOf("Prerequisite map", "See what connects", "⋈", "#FFD66B", "map"))
    label("Your academic toolkit",18f)
    items.chunked(2).forEach { pair ->
        val row=context.column(0).apply{orientation=LinearLayout.HORIZONTAL;isBaselineAligned=false}
        addView(row,LinearLayout.LayoutParams(-1,-2).apply{topMargin=context.dp(12)})
        pair.forEachIndexed { index, item ->
            val card=MaterialCardView(context).apply{radius=context.dp(23).toFloat();setCardBackgroundColor(context.tint(R.color.sp_cream));strokeWidth=0;cardElevation=context.dp(2).toFloat();isClickable=true;isFocusable=true;contentDescription="${item[0]}, ${item[1]}";tag="toolkit_${item[4]}";setOnClickListener{open(item[4])}}
            val words=context.column(14).apply{minimumHeight=context.dp(148);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS}
            words.label(item[2],28f);words.label(item[0],18f);words.label(item[1],12f);card.addView(words)
            row.addView(card,LinearLayout.LayoutParams(0,-1,1f).apply{if(index==0)marginEnd=context.dp(12)})
        }
    }
}
