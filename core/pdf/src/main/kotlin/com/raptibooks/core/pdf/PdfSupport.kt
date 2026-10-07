package com.raptibooks.core.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

object PdfSupport {
    @Volatile
    private var initialized = false

    /** Loads PdfBox's bundled resources (glyph lists, standard fonts). Call once, cheap afterwards. */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                PDFBoxResourceLoader.init(context.applicationContext)
                initialized = true
            }
        }
    }
}
