package com.studypath.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.studypath.app.data.PdfRenderBounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Native, app-private and offline: no web viewer, external app or file URI exposure. */
class OfflinePdfView(context: Context, private val owner: LifecycleOwner) : LinearLayout(context) {
    private val status = label("Opening offline PDF…", 16f).apply { tag = "offline_pdf_status" }
    private val image = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
    private val previous: MaterialButton
    private val next: MaterialButton
    private var file: File? = null
    private var title = "PDF"
    private var page = 0
    private var pageCount = 0
    private var generation = 0
    private var renderJob: Job? = null
    private var bitmap: Bitmap? = null
    private var onPageChanged: (Int) -> Unit = {}

    init {
        orientation = VERTICAL
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        addView(ScrollView(context).apply { isFillViewport = false; isVerticalScrollBarEnabled = false; addView(image, LayoutParams(-1, -2)) }, LayoutParams(-1, 0, 1f))
        val largeText = resources.configuration.fontScale >= 1.5f
        val navigation = LinearLayout(context).apply { orientation = if (largeText) VERTICAL else HORIZONTAL; gravity = Gravity.CENTER }
        previous = MaterialButton(context).apply {
            text = "Previous page"; tag = "offline_pdf_previous"; minHeight = (48 * resources.displayMetrics.density).toInt()
            contentDescription = "Previous PDF page"; setOnClickListener { if (page > 0) { page--; render() } }
        }
        next = MaterialButton(context).apply {
            text = "Next page"; tag = "offline_pdf_next"; minHeight = (48 * resources.displayMetrics.density).toInt()
            contentDescription = "Next PDF page"; setOnClickListener { if (page + 1 < pageCount) { page++; render() } }
        }
        fun buttonParams() = if (largeText) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)
        navigation.addView(previous, buttonParams()); navigation.addView(next, buttonParams()); addView(navigation)
        label("PDF pages are images; text selection is unavailable.", 14f)
        updateButtons(false)
    }

    fun open(file: File, label: String, initialPage: Int = 0, onPageChanged: (Int) -> Unit = {}) {
        this.file = file; title = label; page = initialPage.coerceAtLeast(0); pageCount = 0; this.onPageChanged = onPageChanged
        render()
    }

    fun clear() {
        generation++; renderJob?.cancel(); renderJob = null; file = null
        image.setImageDrawable(null); bitmap?.recycle(); bitmap = null
        status.text = "PDF closed"; updateButtons(false)
    }

    private fun render() {
        val selected = file ?: return
        val targetPage = page
        val current = ++generation
        renderJob?.cancel()
        status.text = if (pageCount == 0) "Opening $title offline…" else "Opening page ${page + 1} of $pageCount…"
        updateButtons(false)
        renderJob = owner.lifecycleScope.launch {
            var rendered: Bitmap? = null
            var delivered = false
            try {
                val (count, presentedPage) = withContext(Dispatchers.IO) {
                    ParcelFileDescriptor.open(selected, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            require(renderer.pageCount in 1..500) { "PDF page is unavailable." }
                            val selectedPage = targetPage.coerceIn(0, renderer.pageCount - 1)
                            renderer.openPage(selectedPage).use { pdfPage ->
                                val (width, height) = PdfRenderBounds.fit(pdfPage.width, pdfPage.height, resources.displayMetrics.widthPixels)
                                val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                rendered = result; result.eraseColor(Color.WHITE)
                                pdfPage.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                            renderer.pageCount to selectedPage
                        }
                    }
                }
                if (generation != current) return@launch
                pageCount = count; page = presentedPage
                image.setImageBitmap(rendered); bitmap?.recycle(); bitmap = rendered; delivered = true
                image.contentDescription = "$title, PDF page ${presentedPage + 1} of $count"
                status.text = "Page ${presentedPage + 1} of $count · Available offline"
                updateButtons(true)
                onPageChanged(presentedPage)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (generation == current) {
                    image.setImageDrawable(null); bitmap?.recycle(); bitmap = null
                    status.text = "This PDF could not be opened. Return to downloads to repair this package."
                    updateButtons(false)
                }
            } finally { if (!delivered) rendered?.recycle() }
        }
    }

    private fun updateButtons(ready: Boolean) { previous.isEnabled = ready && page > 0; next.isEnabled = ready && page + 1 < pageCount }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }
}
