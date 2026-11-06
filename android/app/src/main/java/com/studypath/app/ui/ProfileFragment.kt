package com.studypath.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.studypath.app.StudyPathApplication
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Identity first; administrative controls live in Settings. */
class ProfileFragment : Fragment() {
    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View {
        val b = requireContext().column()
        b.label("Your space.", 26f)
        b.addView(requireContext().column(0).apply { tag = "profile_avatar_host" })
        b.compactAction("Edit profile") { (requireActivity() as MainActivity).openDetails(EditProfileFragment()) }.tag = "profile_edit_direct"
        b.addView(requireContext().column(0).apply { tag = "profile_counts" })
        b.compactAction("Settings") { (requireActivity() as MainActivity).openDetails(SettingsFragment()) }.tag = "profile_settings"
        b.compactAction("Study reminders & next review") { (requireActivity() as MainActivity).openDetails(AccountFragment.tools("Study reminders")) }
        b.compactAction("Try the student demo") { startActivity(Intent(requireContext(), DemoActivity::class.java)) }.tag = "profile_demo"
        return requireContext().scrolling(b)
    }
    override fun onViewCreated(v: View, s: Bundle?) {
        watchPersonal({ data -> v.findViewWithTag<LinearLayout>("profile_counts").apply {
            removeAllViews(); card("${data.subjects.size} subjects · ${data.subjects.sumOf { it.units ?: 0.0 }} units", "${data.materials.size} personal materials")
        } }, {})
        val repository = (requireActivity().application as StudyPathApplication).graph.repository
        viewLifecycleOwner.lifecycleScope.launch { viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            repository.profileName().catch { emit(null) }.collect { name ->
                val uid = repository.currentSession()?.uid ?: return@collect
                val host = v.findViewWithTag<LinearLayout>("profile_avatar_host")
                fun renderAvatar() {
                    host.removeAllViews()
                    fun pick() { chooseAvatar(requireContext(), name.orEmpty(), ProfileAvatars.selected(requireContext(),uid), "Saved on this device for your account.") {
                        ProfileAvatars.save(requireContext(),uid,it); renderAvatar()
                    } }
                    host.profileIdentity(name ?: "Your account","Your personal study space",ProfileAvatars.selected(requireContext(),uid)) { pick() }
                }
                renderAvatar()
            }
        } }
    }
}
