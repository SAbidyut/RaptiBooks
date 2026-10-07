package com.raptibooks.core.ocr

import android.content.Context
import java.io.File
import java.io.IOException

/** Copies the bundled Tesseract models from the APK assets to app storage (once per version). */
internal object TessdataInstaller {
    private const val ASSET_DIR = "tessdata"

    @Synchronized
    fun install(context: Context, dataDir: File) {
        val target = File(dataDir, "tessdata")
        target.mkdirs()
        val assets = context.assets
        val names = assets.list(ASSET_DIR)?.filter { it.endsWith(".traineddata") }.orEmpty()
        if (names.isEmpty()) throw OcrUnavailableException("OCR language data missing from the app")
        for (name in names) {
            val out = File(target, name)
            val expected = try {
                assets.openFd("$ASSET_DIR/$name").use { it.length }
            } catch (_: IOException) {
                -1L // compressed asset: length unknown, compare after copy
            }
            if (out.exists() && (expected < 0 && out.length() > 0 || out.length() == expected)) continue
            val tmp = File(target, "$name.part")
            assets.open("$ASSET_DIR/$name").use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
            if (!tmp.renameTo(out)) {
                out.delete()
                if (!tmp.renameTo(out)) throw IOException("cannot install $name")
            }
        }
    }
}
