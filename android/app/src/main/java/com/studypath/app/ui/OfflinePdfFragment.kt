package com.studypath.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.TextPackageKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens only the bound owner's revalidated ready package; no external viewer or exported URI. */
class OfflinePdfFragment : Fragment() {
    private lateinit var body: LinearLayout
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        requireContext().column(16).also { body = it }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val repository = (requireActivity().application as StudyPathApplication).graph.repository
        val args = requireArguments()
        val key = TextPackageKey(requireNotNull(args.getString("curriculum")), requireNotNull(args.getString("module")), args.getInt("version"))
        val assetId = requireNotNull(args.getString("asset"))
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var pdf: OfflinePdfView? = null
                try {
                    body.removeAllViews(); body.label("Opening offline PDF…", 18f)
                    val file = withContext(Dispatchers.IO) { requireNotNull(repository.openPackageAsset(key, assetId)) { "This complete download is unavailable. Return to Study to repair or download this version." } }
                    val uid = requireNotNull(repository.currentSession()).uid
                    val positions = requireContext().getSharedPreferences("offline-pdf-position", 0)
                    val positionKey = "$uid/${key.curriculumVersion}/${key.moduleId}/v${key.version}/$assetId"
                    body.removeAllViews()
                    pdf = OfflinePdfView(requireContext(), viewLifecycleOwner)
                    body.addView(pdf, LinearLayout.LayoutParams(-1, -1))
                    pdf.open(file, "$assetId · Version ${key.version}", positions.getInt(positionKey, 0)) { page ->
                        if (repository.currentSession()?.uid == uid) positions.edit().putInt(positionKey, page).apply()
                    }
                    // Session listeners are fenced by the repository; logout or denial closes this private view.
                    repository.learnerPreferences().collect { }
                    awaitCancellation()
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    body.removeAllViews(); body.label("PDF unavailable", 22f); body.label(failureText(error))
                } finally { pdf?.clear() }
            }
        }
    }

    companion object {
        fun create(key: TextPackageKey, assetId: String) = OfflinePdfFragment().apply {
            arguments = Bundle().apply { putString("curriculum", key.curriculumVersion); putString("module", key.moduleId); putInt("version", key.version); putString("asset", assetId) }
        }
    }
}
