package com.studypath.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.studypath.app.R

/** Shared by the signed-in shell and demo. Their root reserves all system-bar space. */
class CapsuleBottomNavigationView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    BottomNavigationView(context, attrs) {
    init {
        // Material otherwise adds bottom system insets a second time in programmatic screens.
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets -> insets }
        setPadding(context.dp(8), 0, context.dp(8), 0)
        // Material 1.12 keeps navigation labels unscaled and provides scaled long-press tooltips.
        minimumHeight = context.dp(64)
        labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
        isItemHorizontalTranslationEnabled = false
        itemIconSize = context.dp(22)
        itemPaddingTop = context.dp(8)
        itemPaddingBottom = context.dp(8)
        itemTextAppearanceActive = R.style.TextAppearance_StudyPath_Navigation
        itemTextAppearanceInactive = R.style.TextAppearance_StudyPath_Navigation
        itemIconTintList = ContextCompat.getColorStateList(context, R.color.navigation_icon_tint)
        itemTextColor = ContextCompat.getColorStateList(context, R.color.navigation_text_tint)
        itemActiveIndicatorWidth = context.dp(64)
        itemActiveIndicatorHeight = context.dp(32)
        itemActiveIndicatorColor = ColorStateList.valueOf(context.tint(R.color.sp_action))
        setBackgroundColor(context.tint(R.color.sp_card))
        elevation = 0f
    }
}
