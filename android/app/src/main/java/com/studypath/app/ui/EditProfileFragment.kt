package com.studypath.app.ui

import android.os.Bundle
import android.text.InputFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.studypath.app.StudyPathApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class EditProfileFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val content = requireContext().column()
        content.label("What should we call you?", 24f)
        content.field("Name").apply { tag = "edit_profile_name"; id = View.generateViewId(); filters = arrayOf(InputFilter.LengthFilter(80)); setSingleLine(true) }
        content.label("", 14f).tag = "edit_profile_status"
        content.action("Save name") {}.tag = "edit_profile_save"
        return requireContext().scrolling(content)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val repository = (requireActivity().application as StudyPathApplication).graph.repository
        val field = view.findViewWithTag<android.widget.EditText>("edit_profile_name")
        val status = view.findViewWithTag<android.widget.TextView>("edit_profile_status")
        val save = view.findViewWithTag<View>("edit_profile_save")
        val restored = savedInstanceState?.getString("name")
        field.setText(restored ?: repository.currentSession()?.displayName.orEmpty())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val name = withTimeout(5000) { repository.profileName().first() }
                if (restored == null && !field.hasFocus()) field.setText(name.orEmpty())
            } catch (_: TimeoutCancellationException) { status.text = "Enter your name to update it." }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { status.text = "Enter your name to update it." }
        }
        save.setOnClickListener {
            if (field.text.toString().trim().isBlank()) { field.error = "Enter your name"; return@setOnClickListener }
            viewLifecycleOwner.lifecycleScope.launch {
                save.isEnabled = false; status.text = "Saving…"
                try {
                    withTimeout(12000) { repository.saveProfileName(field.text.toString()) }
                    parentFragmentManager.popBackStack()
                } catch (_: TimeoutCancellationException) { status.text = "Waiting to save. Reconnect and retry." }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { status.text = failureText(error) }
                finally { save.isEnabled = true }
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("name", view?.findViewWithTag<android.widget.EditText>("edit_profile_name")?.text?.toString())
        super.onSaveInstanceState(outState)
    }
}
