package com.studypath.app.data

import android.graphics.pdf.PdfRenderer
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.File

/** Android's offline parser must be able to open every page; no passwords or external viewer required. */
object NativePdfAssetValidator {
    fun validate(file: File) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                require(renderer.pageCount in 1..500) { "The PDF has no usable pages or exceeds the page limit." }
                val probe = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
                try { repeat(renderer.pageCount) { index -> renderer.openPage(index).use { page ->
                    require(page.width > 0 && page.height > 0 && page.width <= 14_400 && page.height <= 14_400) { "Unsupported PDF page dimensions." }
                    page.render(probe, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } } } finally { probe.recycle() }
            }
        }
    }
}
