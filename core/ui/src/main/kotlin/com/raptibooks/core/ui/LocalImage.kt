package com.raptibooks.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads images from app storage, downsampled to the size they are displayed at, through a
 * memory-bounded cache (1/8 of the app heap) so long illustrated books don't exhaust memory.
 */
object LocalImages {
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(8 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cached(path: String, widthPx: Int): Bitmap? = cache.get(key(path, widthPx))

    suspend fun load(path: String, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(path, widthPx)
        cache.get(k)?.let { return@withContext it }
        val bmp = decode(path, widthPx) ?: return@withContext null
        cache.put(k, bmp)
        bmp
    }

    fun clear() = cache.evictAll()

    private fun key(path: String, widthPx: Int) = "$path#${bucket(widthPx)}"

    /** Rounds requested widths so that small layout changes reuse cache entries. */
    private fun bucket(widthPx: Int) = ((widthPx + 127) / 128) * 128

    private fun decode(path: String, widthPx: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) {
            null
        } else {
            var sample = 1
            val target = bucket(widthPx).coerceAtLeast(64)
            while (bounds.outWidth / (sample * 2) >= target) sample *= 2
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: OutOfMemoryError) {
        cache.evictAll()
        null
    }
}

@Composable
fun rememberLocalImage(path: String?, widthPx: Int): State<ImageBitmap?> {
    val initial = path?.let { LocalImages.cached(it, widthPx)?.asImageBitmap() }
    return produceState(initial, path, widthPx) {
        if (path != null && widthPx > 0 && value == null) value = LocalImages.load(path, widthPx)?.asImageBitmap()
    }
}
