package com.studypath.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.studypath.app.StudyPathApplication
import com.studypath.app.data.ReadingPages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Online text reader. The attributed offline snapshot remains a separate bounded record. */
class FullArticleFragment : Fragment() {
    private var page = 0
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); page = savedInstanceState?.getInt("page") ?: 0 }
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View = requireContext().scrolling(requireContext().column())
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) { load() }
    private fun load() {
        val body = (view as? ScrollView)?.getChildAt(0) as? LinearLayout ?: return
        body.removeAllViews(); body.label("Opening article…", 24f)
        body.action("Open on Wikipedia") { requireContext().openSource(requireArguments().getString("url")!!) }.quietAction()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val article = (requireActivity().application as StudyPathApplication).graph.readings.fetchFullArticle(requireArguments().getString("key")!!)
                val pages = ReadingPages.split(article.text)
                fun render() {
                    page = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                    body.removeAllViews(); body.label(article.title, 24f)
                    body.label("Article text · ${page + 1} of ${pages.size}", 14f)
                    body.action("Contents / page") {
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext()).setTitle("Go to page")
                            .setItems(pages.mapIndexed { index, text -> "${index + 1}. ${text.trim().take(55)}…" }.toTypedArray()) { _, selected -> page = selected; render() }.show()
                    }.quietAction()
                    body.label(pages.getOrElse(page) { "No readable text found." }).apply { setTextIsSelectable(true); tag = "full_article_text" }
                    body.action("Previous") { page--; render() }.quietAction().isEnabled = page > 0
                    body.action("Next page") { page++; render() }.apply { isEnabled = page + 1 < pages.size; tag = "full_article_next" }
                    body.action("Make a study card") { (requireActivity() as MainActivity).openDetails(PersonalWorkspaceFragment.forReading(article.title)) }.quietAction()
                    body.action("Images, tables & source") { requireContext().openSource(article.revisionUrl) }.quietAction()
                    body.action("Source & license") { requireContext().showReadingSource("Source & license", "${article.attribution}\n${article.license}\nText reformatted; images and tables available at the source.", article.revisionUrl, article.licenseUrl) }.quietAction()
                    (view as? ScrollView)?.post { (view as? ScrollView)?.scrollTo(0, 0) }
                }
                render()
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { body.label(failureText(error)); body.action("Try again") { load() }; body.label("Your saved excerpt is still available when you go back.", 14f) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("page", page); super.onSaveInstanceState(outState) }
    companion object { fun open(key: String, url: String) = FullArticleFragment().apply { arguments = Bundle().apply { putString("key", key); putString("url", url) } } }
}
