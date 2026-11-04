package com.studypath.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.studypath.app.data.FirebaseStudyRepository
import com.studypath.app.data.LearnerPreferences
import com.studypath.app.ui.failureText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Activity-lived progress lets optional onboarding continue while its owned write waits. */
class OnboardingSaveModel : ViewModel() {
    data class Submission(
        val owner: String? = null,
        val selection: LearnerPreferences? = null,
        val pending: Boolean = false,
        val confirmed: Boolean = false,
        val message: String = "",
        val displayName: String? = null,
    )
    private val mutable = MutableStateFlow(Submission())
    val state = mutable.asStateFlow()

    fun submit(repository: FirebaseStudyRepository, selection: LearnerPreferences, displayName: String? = null) {
        if (mutable.value.pending) return
        val owner = repository.currentSession()?.uid ?: return
        mutable.value = Submission(owner, selection, pending = true,
            message = "Saving preferences…", displayName = displayName)
        viewModelScope.launch {
            try {
                repository.saveLearnerPreferences(selection, displayName)
                if (repository.currentSession()?.uid == owner) mutable.value = Submission(owner, selection,
                    confirmed = true, message = "Preferences saved", displayName = displayName)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (repository.currentSession()?.uid == owner) mutable.value = Submission(owner, selection,
                    message = failureText(error), displayName = displayName)
            }
        }
    }
}
