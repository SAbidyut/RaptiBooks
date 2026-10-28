package com.raptibooks.feature.reader

import android.graphics.Bitmap
import android.util.LruCache
import android.view.OrientationEventListener
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raptibooks.core.data.OriginalPdf
import com.raptibooks.core.data.PageLine
import com.raptibooks.core.data.ReaderRepository
import com.raptibooks.core.data.appContainer
import com.raptibooks.core.model.InkStroke
import com.raptibooks.core.model.MarkColor
import com.raptibooks.core.model.Note
import com.raptibooks.core.model.NoteKind
import com.raptibooks.core.model.ReaderTheme
import com.raptibooks.core.model.TocEntry
import com.raptibooks.core.ui.RaptiIcons
import com.raptibooks.core.ui.ReaderColors
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class OriginalPdfViewModel(private val bookId: Long, private val repo: ReaderRepository) : ViewModel() {
    var document by mutableStateOf<OriginalPdf?>(null)
        private set
    var failed by mutableStateOf(false)
        private set

    /** How many pages' sizes are known (they are read in the background, ahead of the pages). */
    var sizesKnown by mutableIntStateOf(0)
        private set

    /** The chapters of the book (or the PDF's own outline), with their pages; null while read. */
    var contents by mutableStateOf<List<TocEntry>?>(null)
        private set

    /** The printed lines of the passage held in the reflowed text, on its page. */
    internal var arrival: Deferred<List<PageLine>>? = null
        private set

    /** Lines drawn on the original pages, by page (kept as block -(page + 1)). */
    val ink: StateFlow<Map<Int, List<InkStroke>>> =
        repo.ink(bookId).map { all -> all.filter { it.blockIndex < 0 }.groupBy { -it.blockIndex - 1 } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun addInk(page: Int, color: MarkColor, points: FloatArray) {
        viewModelScope.launch { repo.addInk(bookId, -(page + 1), color, points) }
    }

    fun eraseInk(ids: List<Long>) {
        viewModelScope.launch { repo.eraseInk(ids) }
    }

    /** Notes pinned to the original pages, by page (kept as block -(page + 1), like the lines). */
    val notes: StateFlow<Map<Int, List<Note>>> =
        repo.notes(bookId).map { all -> all.filter { it.blockIndex < 0 }.groupBy { -it.blockIndex - 1 } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun newVoiceFile(): File = repo.newVoiceFile(bookId)

    fun addNote(page: Int, x: Float, y: Float, kind: NoteKind, text: String, audioPath: String?, durationMs: Long) {
        viewModelScope.launch { repo.addNote(bookId, -(page + 1), page, x, y, kind, text, audioPath, durationMs) }
    }

    fun updateNoteText(noteId: Long, text: String) {
        viewModelScope.launch { repo.updateNoteText(noteId, text) }
    }

    fun deleteNote(note: Note) {
        viewModelScope.launch { repo.deleteNote(note) }
    }

    /** The printed lines of a page, to find the one held. */
    suspend fun lines(page: Int): List<PageLine> = repo.originalLines(bookId, page)

    /** Recently rendered pages; old ones are dropped (not recycled, they may still be drawn). */
    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceAtLeast(8 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    private var opened = false

    fun open(initialPage: Int, passage: String?) {
        if (opened) return
        opened = true
        repo.holdOriginalText(bookId, initialPage)
        // (looked for at once, while the pages are opened)
        if (passage != null) arrival = viewModelScope.async { Passage.linesOf(repo.originalLines(bookId, initialPage), passage) }
        viewModelScope.launch {
            val doc = try {
                repo.openOriginal(bookId)
            } catch (e: Exception) {
                failed = true
                return@launch
            }
            // (the sizes are read off the main thread: the renderer is busy drawing pages)
            withContext(Dispatchers.IO) {
                doc.pageSize(0)
                if (initialPage in 0 until doc.pageCount) doc.pageSize(initialPage)
            }
            document = doc
            launch {
                contents = repo.toc(bookId).filter { it.pageIndex >= 0 }.ifEmpty { repo.originalOutline(bookId) }
            }
            withContext(Dispatchers.IO) {
                for (i in 0 until doc.pageCount) {
                    yield()
                    doc.pageSize(i)
                    if (i % 40 == 39) withContext(Dispatchers.Main) { sizesKnown = i + 1 }
                }
            }
            sizesKnown = doc.pageCount
        }
    }

    /** The size of page [index], or that of the first page until it is known. */
    fun pageSize(index: Int): Pair<Float, Float> {
        val doc = document ?: return 595f to 842f
        return doc.knownSize(index) ?: doc.knownSize(0) ?: (595f to 842f)
    }

    /** Pages being rendered: asked for twice (the list and the passage lit up), drawn once. */
    private val rendering = HashMap<String, Deferred<Bitmap?>>()

    suspend fun page(index: Int, widthPx: Int): Bitmap? {
        val key = "$index@$widthPx"
        cache.get(key)?.let { return it }
        val job = rendering.getOrPut(key) {
            viewModelScope.async {
                val bmp = document?.render(index, widthPx)
                // (sent to the GPU now, not in the frame that first shows it)
                bmp?.prepareToDraw()
                if (bmp != null) cache.put(key, bmp)
                rendering.remove(key)
                bmp
            }
        }
        return job.await()
    }

    /** Reads the printed lines of a page ahead, so holding a line there goes back at once. */
    fun prefetchLines(page: Int) = repo.prefetchOriginalLines(bookId, page)

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
        if (opened) repo.releaseOriginalText(bookId)
        cache.evictAll()
        document?.close()
    }
}

/** A place on an original page chosen for a new note (fractions of the page). */
private class PageSpot(val page: Int, val x: Float, val y: Float)

/**
 * The original PDF pages, full screen, with pinch zoom. [onReflow] returns to the reflowed text
 * at a page, with the printed line held there; [passage] is the text held in the reflowed book
 * on the way here, shown lit up on its page.
 */
@Composable
fun OriginalPdfRoute(
    bookId: Long,
    initialPage: Int,
    onBack: () -> Unit,
    onReflow: ((page: Int, passage: String?) -> Unit)?,
    /** Start with the pages turned for reading across. */
    initialLandscape: Boolean = false,
    passage: String? = null,
) {
    val context = LocalContext.current
    val vm: OriginalPdfViewModel = viewModel(
        key = "original-$bookId",
        factory = viewModelFactory { initializer { OriginalPdfViewModel(bookId, context.appContainer().reader) } },
    )
    LaunchedEffect(vm) { vm.open(initialPage, passage) }
    val doc = vm.document
    val ink by vm.ink.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage.coerceAtLeast(0))
    val current = listState.firstVisibleItemIndex
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    SystemBars(dark = true, restore = true)
    NoStatusBar()
    // Pages turned a quarter for reading across; the screen itself (its bars) stays upright.
    var landscape by rememberSaveable { mutableStateOf(initialLandscape) }
    // Which way: the right side of the phone up (1) or its left side (-1), as it is held.
    var turn by rememberSaveable { mutableIntStateOf(1) }
    if (landscape) PhoneSide { turn = it }
    // The bar at the bottom fades away after a moment; a tap brings it back.
    var barShown by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var penOpen by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf(MarkTool.NONE) }
    var showContents by remember { mutableStateOf(false) }
    // (the page the slider is dragged to, while it is)
    var seeking by remember { mutableStateOf<Float?>(null) }
    var spot by remember { mutableStateOf<PageSpot?>(null) }
    var noteStep by remember { mutableStateOf(NoteStep.CHOOSE) }
    var openNote by remember { mutableStateOf<Note?>(null) }
    LaunchedEffect(barShown, poke, penOpen, seeking != null) {
        if (barShown && !penOpen && seeking == null) {
            delay(3000)
            barShown = false
        }
    }
    // The passage come from, lit up for a moment on its page (once, not again when turned).
    var arrived by rememberSaveable { mutableStateOf(passage == null) }
    var lit by remember { mutableStateOf<Pair<Int, List<PageLine>>?>(null) }
    val glow = remember { Animatable(0f) }
    Box(Modifier.fillMaxSize().background(Color(0xFF3A3936))) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .then(if (landscape) Modifier.quarterTurn(turn) else Modifier),
        ) {
            key(landscape, turn) {
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
                    val padPx = with(density) { 12.dp.toPx() }
                    // (the width the pages are rendered at, to find the one tapped)
                    var renderWidth by remember { mutableIntStateOf(0) }
                    // The page and the place on it (fractions) under a point of the screen.
                    fun pageAt(p: Offset): Triple<Int, Float, Float>? {
                        val cx = viewport.width / 2f
                        val cy = viewport.height / 2f
                        val x = cx + (p.x - cx - offset.x) / scale
                        val y = cy + (p.y - cy - offset.y) / scale
                        val inList = y.roundToInt() + listState.layoutInfo.viewportStartOffset
                        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { inList in it.offset until it.offset + it.size } ?: return null
                        val itemWidth = (viewport.width - padPx * 2f).coerceAtLeast(1f)
                        return Triple(item.index, (x - padPx) / itemWidth, (inList - item.offset).toFloat() / item.size)
                    }
                    // Lines of the page in view read ahead, to go back from any of them at once.
                    LaunchedEffect(doc) {
                        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest {
                            delay(250)
                            vm.prefetchLines(it)
                        }
                    }
                    BoxWithConstraints(
                        Modifier
                            .fillMaxSize()
                            .onSizeChanged { viewport = Size(it.width.toFloat(), it.height.toFloat()) }
                            // One finger held still for 0.6 s goes back to the reflowed text, to the
                            // line held.
                            .holdGestures(
                                enabled = onReflow != null && tool == MarkTool.NONE,
                                onHold = { at ->
                                    val place = pageAt(at)
                                    scope.launch {
                                        val line = place?.let { (page, fx, fy) ->
                                            val lines = vm.lines(page)
                                            lines.firstOrNull { fy in it.top..it.bottom && fx in it.left - 0.02f..it.right + 0.02f }
                                                ?: lines.minByOrNull { abs((it.top + it.bottom) / 2f - fy) }?.takeIf { abs((it.top + it.bottom) / 2f - fy) < 0.05f }
                                        }
                                        onReflow?.invoke(place?.first ?: current, line?.text)
                                    }
                                },
                            )
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onTap = {
                                        barShown = !barShown
                                        poke++
                                    },
                                    onDoubleTap = { tap ->
                                        if (scale > 1f) {
                                            scale = 1f
                                            offset = Offset.Zero
                                        } else {
                                            // The printed part of the page under the finger fills the
                                            // width of the screen; the tapped line stays where it is.
                                            val place = pageAt(tap)
                                            val itemWidth = viewport.width - padPx * 2f
                                            val span = place?.let { vm.contentSpan(it.first, renderWidth) }
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
                                    },
                                )
                            }
                            .transformable(zoom, canPan = { scale > 1f }),
                    ) {
                        // (no larger than needed: big pages are slow to draw and scroll; sharper
                        // when zoomed in)
                        val base = with(density) { maxWidth.toPx().coerceAtMost(1600f).roundToInt() }
                        val widthPx = if (scale > 1.6f) (base * 2).coerceAtMost(3000) else base
                        SideEffect { renderWidth = base }
                        LaunchedEffect(doc) {
                            if (arrived) return@LaunchedEffect
                            val hit = vm.arrival?.await().orEmpty()
                            arrived = true
                            if (hit.isEmpty()) return@LaunchedEffect
                            val size = snapshotFlow { viewport }.first { it.width > 0f }
                            val (w, h) = vm.pageSize(initialPage)
                            val itemHeight = (size.width - padPx * 2f) * h / w
                            val top = hit.minOf { it.top } * itemHeight
                            listState.scrollToItem(initialPage, (top - size.height * 0.3f).roundToInt().coerceAtLeast(0))
                            // lit up when the page itself shows, not over a blank one
                            vm.page(initialPage, widthPx)
                            lit = initialPage to hit
                            glow.snapTo(0.6f)
                            delay(700)
                            glow.animateTo(0f, tween(1200))
                            lit = null
                        }
                        val minStep = with(density) { 2.5.dp.toPx() }
                        val eraseReach = with(density) { 14.dp.toPx() }
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                        ) {
                            items(doc.pageCount, key = { it }) { index ->
                                val (w, h) = remember(index, vm.sizesKnown > index) { vm.pageSize(index) }
                                val bitmap by produceState<Bitmap?>(null, index, widthPx) { value = vm.page(index, widthPx) }
                                var pageSize by remember { mutableStateOf(Size.Zero) }
                                val strokes = ink[index].orEmpty()
                                val pins = notes[index].orEmpty()
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .aspectRatio((w / h).coerceIn(0.2f, 5f))
                                        .background(Color.White)
                                        .onSizeChanged { pageSize = Size(it.width.toFloat(), it.height.toFloat()) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    val b = bitmap
                                    if (b != null) {
                                        Image(b.asImageBitmap(), null, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxSize())
                                    } else {
                                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                                    }
                                    if (strokes.isNotEmpty()) InkCanvas(strokes, Modifier.fillMaxSize())
                                    val shine = lit?.takeIf { it.first == index }?.second
                                    if (shine != null) {
                                        Canvas(Modifier.fillMaxSize()) {
                                            for (l in shine) {
                                                drawRect(
                                                    PASSAGE_GLOW.copy(alpha = glow.value),
                                                    topLeft = Offset(l.left * size.width - 3f, l.top * size.height - 2f),
                                                    size = Size((l.right - l.left) * size.width + 6f, (l.bottom - l.top) * size.height + 4f),
                                                )
                                            }
                                        }
                                    }
                                    if (tool == MarkTool.NOTE) {
                                        // Choosing a place for a note: the next tap on a page is it;
                                        // scrolling still works.
                                        Box(
                                            Modifier.fillMaxSize().pointerInput(index) {
                                                awaitEachGesture {
                                                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                    while (true) {
                                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                        if (event.changes.size > 1 || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                                        if (change.changedToUp()) {
                                                            change.consume()
                                                            spot = PageSpot(
                                                                index,
                                                                (down.position.x / size.width).coerceIn(0f, 1f),
                                                                (down.position.y / size.height).coerceIn(0f, 1f),
                                                            )
                                                            noteStep = NoteStep.CHOOSE
                                                            tool = MarkTool.NONE
                                                            break
                                                        }
                                                    }
                                                }
                                            },
                                        )
                                    } else if (tool != MarkTool.NONE) {
                                        MarkOverlay(
                                            tool = tool,
                                            onStroke = { points, _ ->
                                                val color = tool.color
                                                if (color != null) {
                                                    val line = normalizedInk(points, pageSize, minStep)
                                                    if (line.size >= 2) vm.addInk(index, color, line)
                                                } else {
                                                    vm.eraseInk(inkHitIds(strokes, points, pageSize, eraseReach))
                                                }
                                            },
                                        )
                                    }
                                    if (pins.isNotEmpty()) PagePins(pins, onOpen = { openNote = it }, modifier = Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }
            }
            if (doc != null) {
                // The page shown, a slider to move through the book, the contents, the pen and the
                // switch between reading upright and across: at the bottom of the pages, turned
                // with them; see-through, and away after a moment.
                AnimatedVisibility(
                    barShown || penOpen,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 12.dp, end = 12.dp),
                    enter = fadeIn(),
                    exit = fadeOut(tween(600)),
                ) {
                    PageBar(
                        page = seeking?.roundToInt() ?: current,
                        pageCount = doc.pageCount,
                        hasContents = !vm.contents.isNullOrEmpty(),
                        onSeek = { p ->
                            seeking = p
                            poke++
                            scope.launch { listState.scrollToItem(p.roundToInt()) }
                        },
                        onSeekDone = { seeking = null },
                        onContents = { showContents = true; poke++ },
                        onPen = {
                            penOpen = !penOpen
                            if (!penOpen) tool = MarkTool.NONE
                            poke++
                        },
                        landscape = landscape,
                        onTurn = {
                            landscape = !landscape
                            poke++
                        },
                    )
                }
                // The colours of the pen and the note: a finger draws on the page while a colour is
                // chosen; with the note, a tap on a page places one.
                if (penOpen) {
                    MarkPalette(
                        tool = tool, colors = ReaderColors.of(ReaderTheme.DARK), onSelect = { tool = it },
                        onClose = { penOpen = false; tool = MarkTool.NONE },
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                    )
                }
                // The contents over the pages, turned with them.
                if (showContents) {
                    OriginalContents(
                        entries = vm.contents.orEmpty(),
                        currentPage = current,
                        onJump = { page ->
                            showContents = false
                            scope.launch { listState.scrollToItem(page.coerceIn(0, doc.pageCount - 1)) }
                        },
                        onDismiss = { showContents = false },
                    )
                }
                AnimatedVisibility(
                    tool == MarkTool.NOTE, Modifier.align(Alignment.TopCenter),
                    enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
                ) {
                    NoteHint(ReaderColors.of(ReaderTheme.DARK), onCancel = { tool = MarkTool.NONE })
                }
            }
        }
    }

    // Notes: place chosen -> voice or written note; a pin opens its note.
    spot?.let { place ->
        fun done() { spot = null }
        when (noteStep) {
            NoteStep.CHOOSE -> NoteChooserDialog(
                onVoice = { noteStep = NoteStep.RECORD },
                onText = { noteStep = NoteStep.WRITE },
                onDismiss = ::done,
            )
            NoteStep.WRITE -> NoteEditorDialog(
                initial = "", onDelete = null, onDismiss = ::done,
                onSave = { text ->
                    vm.addNote(place.page, place.x, place.y, NoteKind.TEXT, text, null, 0L)
                    done()
                },
            )
            NoteStep.RECORD -> VoiceRecordDialog(
                newFile = vm::newVoiceFile, onDismiss = ::done,
                onSaved = { file, duration ->
                    vm.addNote(place.page, place.x, place.y, NoteKind.VOICE, "", file.absolutePath, duration)
                    done()
                },
            )
        }
    }
    openNote?.let { note ->
        if (note.kind == NoteKind.VOICE) {
            VoiceNoteDialog(note, onDelete = { vm.deleteNote(note); openNote = null }, onDismiss = { openNote = null })
        } else {
            NoteEditorDialog(
                initial = note.text,
                onSave = { text -> vm.updateNoteText(note.id, text); openNote = null },
                onDelete = { vm.deleteNote(note); openNote = null },
                onDismiss = { openNote = null },
            )
        }
    }
}

/** The see-through bar over the original pages: page, slider, contents, pen, turn. */
@Composable
private fun PageBar(
    page: Int,
    pageCount: Int,
    hasContents: Boolean,
    onSeek: (Float) -> Unit,
    onSeekDone: () -> Unit,
    onContents: () -> Unit,
    onPen: () -> Unit,
    landscape: Boolean,
    onTurn: () -> Unit,
) {
    val tint = Color.White.copy(alpha = 0.88f)
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.Black.copy(alpha = 0.45f),
        contentColor = Color.White,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 6.dp)) {
            if (pageCount > 1) {
                Slider(
                    value = page.toFloat().coerceIn(0f, (pageCount - 1).toFloat()),
                    onValueChange = onSeek,
                    onValueChangeFinished = onSeekDone,
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White.copy(alpha = 0.85f),
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp).heightIn(max = 36.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (hasContents) {
                    IconButton(onClick = onContents) { Icon(RaptiIcons.Toc, stringResource(R.string.reader_contents), tint = tint, modifier = Modifier.size(20.dp)) }
                }
                Text(
                    "${page + 1} / $pageCount",
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    modifier = Modifier.weight(1f).padding(start = if (hasContents) 0.dp else 12.dp),
                )
                IconButton(onClick = onPen) {
                    Icon(Icons.Default.Create, stringResource(R.string.original_pen), tint = tint, modifier = Modifier.size(20.dp))
                }
                TextButton(onClick = onTurn) {
                    Icon(RaptiIcons.Rotate, null, tint = tint, modifier = Modifier.size(18.dp))
                    Text(" " + stringResource(if (landscape) R.string.original_portrait else R.string.original_landscape), color = tint)
                }
            }
        }
    }
}

/**
 * The chapters of the book over the pages, a tap goes to the page of one. Drawn in the pages'
 * frame (not a sheet of its own), so it is turned with them when they are read across.
 */
@Composable
private fun OriginalContents(entries: List<TocEntry>, currentPage: Int, onJump: (Int) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .padding(16.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                // (a tap inside is not a tap on the dimmed pages)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Column {
                Text(
                    stringResource(R.string.reader_contents),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 8.dp),
                )
                val now = entries.indexOfLast { it.pageIndex <= currentPage }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (now - 3).coerceAtLeast(0))
                LazyColumn(state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(entries.size) { i ->
                        val e = entries[i]
                        val selected = i == now
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(e.pageIndex) }
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                                .padding(start = (20 + (e.level - 1).coerceIn(0, 4) * 18).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                e.title,
                                style = if (e.level == 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                                fontWeight = if (e.level == 1 || selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text("${e.pageIndex + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which side of the phone is up while it is held across: 1 for its right side, -1 for its left
 * (the last one seen, while it lies flat or stands upright).
 */
@Composable
private fun PhoneSide(onSide: (Int) -> Unit) {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onSide)
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                when (degrees) {
                    in 50..130 -> latest(-1)
                    in 230..310 -> latest(1)
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }
}

/** The status bar is hidden over the original pages (a swipe from the top shows it for a moment). */
@Composable
private fun NoStatusBar() {
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.statusBars())
        onDispose { controller.show(WindowInsetsCompat.Type.statusBars()) }
    }
}

/**
 * The content turned a quarter, to the right ([direction] 1: for the phone held with its right
 * side up) or to the left (-1), filling the place (its width and height swapped).
 */
private fun Modifier.quarterTurn(direction: Int): Modifier = layout { measurable, constraints ->
    val turned = measurable.measure(
        Constraints(constraints.minHeight, constraints.maxHeight, constraints.minWidth, constraints.maxWidth),
    )
    layout(turned.height, turned.width) {
        turned.placeWithLayer((turned.height - turned.width) / 2, (turned.width - turned.height) / 2) { rotationZ = 90f * direction }
    }
}
