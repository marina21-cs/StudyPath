package com.studypath.app.ui

import android.content.Context

/** Local presentation choice only; it neither completes preferences nor changes curriculum authority. */
internal object OptionalOnboarding {
    private fun preferences(context: Context) = context.getSharedPreferences("optional_onboarding", Context.MODE_PRIVATE)
    fun wasSkipped(context: Context, uid: String) = preferences(context).getBoolean("skipped_$uid", false)
    fun skip(context: Context, uid: String) { preferences(context).edit().putBoolean("skipped_$uid", true).apply() }
}
