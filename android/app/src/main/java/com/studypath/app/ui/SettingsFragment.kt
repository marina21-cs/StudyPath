package com.studypath.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.studypath.app.BuildConfig
import com.studypath.app.R

object Appearance {
    private fun prefs(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    fun selected(context: Context): Int = prefs(context).getInt("mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    fun apply(context: Context) { AppCompatDelegate.setDefaultNightMode(selected(context)) }
    fun set(context: Context, mode: Int) {
        require(mode in listOf(AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM))
        prefs(context).edit().putInt("mode", mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}

internal fun LinearLayout.appearanceControls() {
    label("Appearance", 20f)
    val options = listOf("Light" to AppCompatDelegate.MODE_NIGHT_NO, "Dark" to AppCompatDelegate.MODE_NIGHT_YES, "System" to AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    val group = com.google.android.material.button.MaterialButtonToggleGroup(context).apply { isSingleSelection = true; isSelectionRequired = true }
    options.forEach { (title, mode) ->
        val button = com.google.android.material.button.MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = View.generateViewId(); text = title; isAllCaps = false; minHeight = context.dp(48); minimumHeight = context.dp(48)
            tag = "theme_${title.lowercase()}"; contentDescription = "$title theme"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        group.addView(button)
        if (Appearance.selected(context) == mode) group.check(button.id)
        button.setOnClickListener { Appearance.set(context, mode) }
    }
    addView(group)
}

/** Informational pages describe the test build; no agreement acceptance is implied. */
internal fun showAppInformation(context: Context, page: String) {
    val body = context.column()
    body.label(page, 26f)
    body.label(when (page) {
        "Privacy" -> "StudyPath test build · 6 November 2026\n\nDemo mode\nAll sample student records are fictional. Demo changes stay in the demo session and are never uploaded to your account. Closing the demo discards those changes. Appearance is saved as an app preference. Profile pictures are bundled artwork; your choice is saved on this device for your account. No photo is uploaded.\n\nYour study data\nPersonal subjects, tasks, grading drafts, PDF reviewers, practice progress and imported files are stored on this device under your account. PDF text recognition and extractive question drafting run on this phone; reviewer text is not sent to a generation service. Firebase Authentication handles sign-in; Realtime Database supports account study data and sync. Requests for Wikipedia readings and PDF links contact their source services.\n\nYour controls\nFamily sharing is off until you choose to share. Sign-out stops your session and reminders; it keeps local work for the same account. Review storage before removing materials.\n\nThis is a test-build privacy notice. A public-release policy, operator contact and complete retention/deletion process are still pending."
        "Terms of use" -> "StudyPath test build\n\nUse this app as a personal learning aid. Grade drafts, scenarios and personal prerequisite maps do not replace official school records or professor approval.\n\nOnly save materials you are permitted to access. Retain source attribution with readings. Demo records and sample prerequisite links are illustrative.\n\nThese are test-build usage notes, not a finalized public-service agreement. Operator details, support and release terms are pending. No agreement is accepted by opening this page."
        else -> "StudyPath ${BuildConfig.VERSION_NAME}\n\nA little progress, every day.\n\nNative Android · Tide light and dark\n\nCredits\nNunito and Geist use their bundled open font licenses. Wikipedia readings retain source and license information in the reader. Demo study notes are authored sample content. StudyPath uses its own giraffe artwork and original AI-generated student avatars."
    })
    MaterialAlertDialogBuilder(context).setView(context.scrolling(body)).setPositiveButton("Close", null).show()
}

class SettingsFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View = requireContext().scrolling(requireContext().column().apply {
        label("Make it yours.", 26f)
        appearanceControls()
        label("Preferences", 20f)
        compactAction("Notifications & study reminders") { open(AccountFragment.tools("Study reminders")) }.tag = "settings_reminders"
        compactAction("Storage & downloads") { open(StorageSettingsFragment()) }
        compactAction("Learning preferences") { open(LearnerPreferencesFragment.editing()) }
        compactAction("Family summaries") { open(FamilySharingFragment()) }
        label("About & policies", 20f)
        listOf("Privacy", "Terms of use", "About StudyPath").forEach { title -> compactAction(title) { showAppInformation(requireContext(), title) } }
        label("Your account", 20f)
        compactAction("Account, sync & sign-in") { open(AccountFragment.tools("Your study & account")) }.tag = "settings_account"
        compactAction("Try the student demo") { startActivity(Intent(requireContext(), DemoActivity::class.java)) }.tag = "settings_demo"
    })
    private fun open(f: Fragment) = (requireActivity() as MainActivity).openDetails(f)
}

class StorageSettingsFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val b = requireContext().column()
        val store = personalStore(); val data = store.read()
        val bytes = data.materials.filter { it.pdf }.sumOf { store.pdf(it.id).takeIf { f -> f.isFile }?.length() ?: 0L }
        b.label("Storage & downloads", 26f)
        b.card("Personal materials", "${data.materials.size} materials · ${android.text.format.Formatter.formatFileSize(requireContext(), bytes)} of imported PDFs")
        b.label("Remove individual personal materials from their subject menu. Removing a subject also removes its personal tasks and materials. Your original source file stays where you imported it from.")
        b.compactAction("Manage personal materials") { (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.lessons()) }
        b.compactAction("Saved readings & course downloads") { (requireActivity() as MainActivity).openDetails(SavedFragment()) }
        return requireContext().scrolling(b)
    }
}
