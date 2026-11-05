package com.studypath.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.studypath.app.R
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.PreferenceChoices
import com.studypath.app.ui.auth.OnboardingSaveModel
import kotlinx.coroutines.launch

/** A gentle handoff to discovery; this page does not assert curriculum or sync approval. */
class OnboardingReadyFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val content = requireContext().column(24).apply { tag = "onboarding_ready" }
        val mascot = GiraffeMascotView(requireContext()).apply { tag = "ready_mascot"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        content.addView(mascot, LinearLayout.LayoutParams(-1, requireContext().dp(128)))
        mascot.play(GiraffeMascotView.Pose.WAVE)
        content.label("Ready to explore?", 28f).apply { gravity = Gravity.CENTER; tag = "ready_heading" }
        content.label(arguments?.getStringArrayList("subjects").orEmpty().joinToString("  ·  ", transform = PreferenceChoices::label)
            .ifBlank { "Explore any topic" }, 14f).apply { gravity = Gravity.CENTER; tag = "ready_subjects" }
        content.label("", 14f).apply { tag = "ready_save_status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        content.action("Let’s go") { (requireActivity() as MainActivity).goToStudy() }.apply {
            tag = "preference_continue"; layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
        }
        content.action("Adjust my subjects") {
            parentFragmentManager.beginTransaction().replace(R.id.main_content, LearnerPreferencesFragment()).commit()
        }.apply {
            tag = "ready_adjust"; layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(requireContext().tint(R.color.sp_ink)); strokeWidth = 0
        }
        return requireContext().scrolling(content)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val status = view.findViewWithTag<android.widget.TextView>("ready_save_status")
        val selected = view.findViewWithTag<android.widget.TextView>("ready_subjects")
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    val model = ViewModelProvider(requireActivity())[OnboardingSaveModel::class.java]
                    model.state.collect { submission ->
                        val uid = (requireActivity().application as StudyPathApplication).graph.repository.currentSession()?.uid
                        val current = submission.takeIf { it.owner == uid }
                        view.findViewWithTag<android.widget.TextView>("ready_heading").text =
                            "Ready to explore?"
                        status.text = current?.message?.takeIf { it.isNotBlank() }
                            ?: "Check your preferences to finish saving."
                        status.isVisible = current?.confirmed != true
                        current?.selection?.let { preferences ->
                            selected.text = preferences.subjectIds.sorted().joinToString("  ·  ", transform = PreferenceChoices::label).ifBlank { "Explore any topic" }
                        }
                    }
            }
        }
    }

    companion object {
        fun forSubjects(subjects: Set<String>) = OnboardingReadyFragment().apply {
            arguments = Bundle().apply { putStringArrayList("subjects", ArrayList(subjects.sorted())) }
        }
    }
}
