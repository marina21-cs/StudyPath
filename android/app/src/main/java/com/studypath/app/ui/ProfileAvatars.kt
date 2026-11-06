package com.studypath.app.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.R

/** Bundled art only: no uploads or remote image URLs. Preference belongs to a single UID/device. */
object ProfileAvatars {
    val choices = listOf("initials" to "Your initials", "hoodie" to "Blue hoodie", "glasses" to "Blue glasses", "giraffe" to "StudyPath giraffe")
    fun valid(value: String?) = choices.any { it.first == value }
    fun selected(context: Context, uid: String): String = context.getSharedPreferences("profile-avatars", Context.MODE_PRIVATE)
        .getString(uid, "initials").takeIf(::valid) ?: "initials"
    fun save(context: Context, uid: String, choice: String) {
        require(uid.isNotBlank() && valid(choice))
        context.getSharedPreferences("profile-avatars", Context.MODE_PRIVATE).edit().putString(uid, choice).apply()
    }
}

fun LinearLayout.profileAvatar(name: String, choice: String, size: Int = 96, action: () -> Unit): FrameLayout {
    val avatar = FrameLayout(context).apply {
        tag = "profile_avatar"
        contentDescription = "Change profile picture, ${ProfileAvatars.choices.find { it.first == choice }?.second ?: "Your initials"}"
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(context.tint(R.color.sp_cream)) }
        clipToOutline = true; outlineProvider = ViewOutlineProvider.BACKGROUND
        val ripple = android.util.TypedValue(); context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
        foreground = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, ripple.resourceId)
        isFocusable = true; isClickable = true
        setOnClickListener { action() }
    }
    when (choice) {
        "hoodie", "glasses" -> avatar.addView(ImageView(context).apply {
            setImageResource(if(choice == "hoodie") R.drawable.avatar_tide_hoodie else R.drawable.avatar_tide_glasses)
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1,-1))
        "giraffe" -> avatar.addView(GiraffeMascotView(context).apply { play(GiraffeMascotView.Pose.WAVE) }, FrameLayout.LayoutParams(-1,-1))
        else -> avatar.addView(TextView(context).apply {
            text = name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("").ifBlank { "●" }
            textSize = if(size >= 96) 30f else 20f
            typeface = ResourcesCompat.getFont(context, R.font.nunito_bold)
            gravity = Gravity.CENTER; setTextColor(context.tint(R.color.sp_ink))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(-1,-1))
    }
    addView(avatar, LinearLayout.LayoutParams(context.dp(size), context.dp(size)).apply {
        gravity = Gravity.CENTER_HORIZONTAL; topMargin = context.dp(8); bottomMargin = context.dp(8)
    })
    return avatar
}

fun chooseAvatar(context: Context, name: String, current: String, note: String, onSelect: (String) -> Unit) {
    val body = context.column(16)
    body.label(note, 14f)
    val dialog = MaterialAlertDialogBuilder(context).setTitle("Choose your picture")
        .setView(context.scrolling(body)).setNegativeButton("Cancel",null).create()
    ProfileAvatars.choices.forEach { (id, title) ->
        val row = context.column(0).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        fun select() { onSelect(id); dialog.dismiss() }
        row.profileAvatar(name,id,64) { select() }.apply { contentDescription="Choose $title avatar"; tag="avatar_choice_$id" }
        row.label(title + if(current == id) " · Selected" else "",16f).apply {
            layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=context.dp(16) }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.setOnClickListener { select() }
        body.addView(row)
    }
    dialog.show()
}
