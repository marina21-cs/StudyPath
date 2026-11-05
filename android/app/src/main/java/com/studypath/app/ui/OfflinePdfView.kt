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
    private var zoomed = false
    private val horizontal = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
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
        horizontal.addView(image, android.widget.FrameLayout.LayoutParams(-1, -2))
        addView(ScrollView(context).apply { isFillViewport = false; isVerticalScrollBarEnabled = false; addView(horizontal, LayoutParams(-1, -2)) }, LayoutParams(-1, 0, 1f))
        val tools = LinearLayout(context).apply { orientation = HORIZONTAL }
        tools.compactAction("Go to page") {
            val body = context.column(16)
            val input = body.field("Page number", android.text.InputType.TYPE_CLASS_NUMBER)
            input.setText((page + 1).toString())
            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle("Go to page")
                .setView(body).setPositiveButton("Go", null).setNegativeButton("Cancel", null).create()
            dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
                val number = input.text.toString().toIntOrNull()
                if (number == null || number !in 1..pageCount) input.error = "Enter 1–$pageCount"
                else { page = number - 1; dialog.dismiss(); render() }
            } }; dialog.show()
        }.apply { tag = "offline_pdf_jump"; layoutParams = LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) } }
        val zoom = tools.compactAction("Zoom in") {}.apply { tag = "offline_pdf_zoom"; layoutParams = LayoutParams(0, -2, 1f) }
        zoom.setOnClickListener { zoomed = !zoomed; zoom.text = if (zoomed) "Fit page" else "Zoom in"; render() }
        addView(tools)
        val largeText = resources.configuration.fontScale >= 1.5f
        val navigation = LinearLayout(context).apply { orientation = if (largeText) VERTICAL else HORIZONTAL; gravity = Gravity.CENTER }
        previous = MaterialButton(context).apply {
            text = "Previous"; tag = "offline_pdf_previous"; minHeight = (48 * resources.displayMetrics.density).toInt()
            contentDescription = "Previous PDF page"; setOnClickListener { if (page > 0) { page--; render() } }
        }
        next = MaterialButton(context).apply {
            text = "Next"; tag = "offline_pdf_next"; minHeight = (48 * resources.displayMetrics.density).toInt()
            contentDescription = "Next PDF page"; setOnClickListener { if (page + 1 < pageCount) { page++; render() } }
        }
        fun buttonParams() = if (largeText) LayoutParams(-1, -2) else LayoutParams(0, -2, 1f)
        previous.quietAction(); next.quietAction()
        navigation.addView(previous, buttonParams().apply { marginEnd = if (largeText) 0 else context.dp(8) })
        navigation.addView(next, buttonParams()); addView(navigation)
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
                                val (width, height) = PdfRenderBounds.fit(pdfPage.width, pdfPage.height, resources.displayMetrics.widthPixels * if (zoomed) 2 else 1)
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
                image.layoutParams = android.widget.FrameLayout.LayoutParams(if (zoomed) (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 2 else (width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels), -2)
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
