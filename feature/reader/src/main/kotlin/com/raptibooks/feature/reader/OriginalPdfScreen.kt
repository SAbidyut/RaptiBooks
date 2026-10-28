package com.raptibooks.feature.reader

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raptibooks.core.data.OriginalPdf
import com.raptibooks.core.data.ReaderRepository
import com.raptibooks.core.data.appContainer
import com.raptibooks.core.ui.RaptiIcons
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OriginalPdfViewModel(private val bookId: Long, private val repo: ReaderRepository) : ViewModel() {
    var document by mutableStateOf<OriginalPdf?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    /** Recently rendered pages; old ones are dropped (not recycled, they may still be drawn). */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(8 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    init {
        viewModelScope.launch {
            try {
                document = repo.openOriginal(bookId)
            } catch (e: Exception) {
                failed = true
            }
        }
    }

    suspend fun page(index: Int, widthPx: Int): Bitmap? {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        val bmp = document?.render(index, widthPx) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    /** Where the print of a page starts and ends across it (fractions of its width), margins left out. */
    private val spans = HashMap<Int, ClosedFloatingPointRange<Float>>()

    fun contentSpan(index: Int, widthPx: Int): ClosedFloatingPointRange<Float>? {
        spans[index]?.let { return it }
        val bmp = cache.get("$index@$widthPx") ?: return null
        val w = bmp.width
        val row = IntArray(w)
        var left = w
        var right = -1
        // (every few rows is enough to find the edges of the text)
        for (y in 0 until bmp.height step 6) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                if (((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000 < 200) {
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (right <= left) return null
        val span = (left.toFloat() / w)..((right + 1).toFloat() / w)
        spans[index] = span
        return span
    }

    override fun onCleared() {
        cache.evictAll()
        document?.close()
    }
}

/** The original PDF pages, with pinch zoom. [onReflow] returns to the reflowed text at a page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OriginalPdfRoute(bookId: Long, initialPage: Int, onBack: () -> Unit, onReflow: ((page: Int) -> Unit)?) {
    val context = LocalContext.current
    val vm: OriginalPdfViewModel = viewModel(
        key = "original-$bookId",
        factory = viewModelFactory { initializer { OriginalPdfViewModel(bookId, context.appContainer().reader) } },
    )
    val doc = vm.document
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
    val current = listState.firstVisibleItemIndex
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.original_title), style = MaterialTheme.typography.titleMedium)
                        if (doc != null) Text(stringResource(R.string.reader_page, current + 1, doc.pageCount), style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back)) } },
                actions = {
                    if (onReflow != null) {
                        TextButton(onClick = { onReflow(current) }) {
                            Icon(RaptiIcons.Article, null, modifier = Modifier.size(18.dp))
                            Text(" " + stringResource(R.string.original_reflow))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(Color(0xFF3A3936))) {
            when {
                vm.failed -> Text(stringResource(R.string.original_error), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
                doc == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                else -> {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }
                    var viewport by remember { mutableStateOf(Size.Zero) }
                    val zoom = rememberTransformableState { z, pan, _ ->
                        scale = (scale * z).coerceIn(1f, 4f)
                        // The pages never slide off the screen sideways or out of the top/bottom.
                        offset = if (scale == 1f) Offset.Zero else clampOffset(offset + pan, viewport, viewport, scale)
                    }
                    val density = LocalDensity.current
                    val padPx = with(density) { 12.dp.toPx() }
                    // (the width the pages are rendered at, to find the one tapped)
                    var renderWidth by remember { mutableIntStateOf(0) }
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                            // One finger held still for a second goes back to the reflowed text, at the same page.
                            .holdGestures(enabled = onReflow != null, onHold = { onReflow?.invoke(current) })
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = { tap ->
                                    if (scale > 1f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        // The printed part of the page under the finger fills the
                                        // width of the screen; the tapped line stays where it is.
                                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { tap.y.roundToInt() in it.offset..it.offset + it.size }
                                        val itemWidth = viewport.width - padPx * 2f
                                        val span = item?.let { vm.contentSpan(it.index, renderWidth) }
                                        val left = padPx + (span?.start ?: 0.1f) * itemWidth - padPx * 0.5f
                                        val right = padPx + (span?.endInclusive ?: 0.9f) * itemWidth + padPx * 0.5f
                                        val target = (viewport.width / (right - left).coerceAtLeast(1f)).coerceIn(1.2f, 4f)
                                        val cx = viewport.width / 2f
                                        val cy = viewport.height / 2f
                                        scale = target
                                        offset = clampOffset(
                                            Offset(-((left + right) / 2f - cx) * target, (tap.y - cy) * (1f - target)),
                                            viewport, viewport, target,
                                        )
                                    }
                                })
                            }
                            .transformable(zoom, canPan = { scale > 1f }),
                    ) {
                        val widthPx = with(density) { (maxWidth.toPx() * 1.5f).roundToInt() }
                        SideEffect { renderWidth = widthPx }
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                        ) {
                            items(doc.pageCount, key = { it }) { index ->
                                val (w, h) = remember(index) { doc.pageSize(index) }
                                val bitmap by produceState<Bitmap?>(null, index, widthPx) { value = vm.page(index, widthPx) }
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio((w / h).coerceIn(0.2f, 5f)).background(Color.White),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val b = bitmap
                                    if (b != null) {
                                        Image(b.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
                                    } else {
                                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
