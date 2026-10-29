package com.raptibooks.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raptibooks.core.data.appContainer
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.Note
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.model.NoteKind
import com.raptibooks.core.model.ReaderSettings
import com.raptibooks.core.ui.RaptiIcons
import com.raptibooks.core.ui.ReaderColors
import com.raptibooks.core.ui.SystemBarIcons
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.raptibooks.core.model.MarkKey
import kotlin.math.roundToInt

@Composable
fun ReaderRoute(
    bookId: Long,
    jumpToPage: Int?,
    onJumpConsumed: () -> Unit,
    onBack: () -> Unit,
    /** Opens the original page, with the text held there (to light it up on the page). */
    onOpenOriginal: (page: Int, passage: String?) -> Unit,
    /** A printed line of [jumpToPage] held on the original page: lit up on arrival. */
    jumpPassage: String? = null,
) {
    val context = LocalContext.current
    val vm: ReaderViewModel = viewModel(
        key = "reader-$bookId",
        factory = viewModelFactory {
            initializer {
                val c = context.appContainer()
                ReaderViewModel(bookId, c.reader, c.settings)
            }
        },
    )
    val settings by vm.settings.collectAsStateWithLifecycle()
    val colors = ReaderColors.of(settings.theme)
    Box(Modifier.fillMaxSize().background(colors.background)) {
        when (val load = vm.load) {
            ReaderViewModel.Load.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent)
            ReaderViewModel.Load.NotReady -> Column(Modifier.align(Alignment.Center).padding(32.dp)) {
                Text(stringResource(R.string.reader_not_ready), color = colors.text)
            }
            is ReaderViewModel.Load.Ready -> Reader(vm, load, settings, colors, jumpToPage, jumpPassage, onJumpConsumed, onBack, onOpenOriginal)
        }
    }
}

private enum class Panel { NONE, CONTENTS, SETTINGS, SEARCH, INFO }

internal enum class NoteStep { CHOOSE, WRITE, RECORD }

/** A picture opened full screen; [block] is where the user's drawing on it is kept. */
private class ViewerState(val path: String, val caption: String?, val equation: Boolean, val block: Int, val pen: Boolean, val text: String? = null)

/** A place in the book chosen for a new note. */
private class NoteSpot(val block: Int, val page: Int, val x: Float, val y: Float)

@OptIn(FlowPreview::class)
@Composable
private fun Reader(
    vm: ReaderViewModel,
    ready: ReaderViewModel.Load.Ready,
    settings: ReaderSettings,
    colors: ReaderColors,
    jumpToPage: Int?,
    jumpPassage: String?,
    onJumpConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenOriginal: (page: Int, passage: String?) -> Unit,
) {
    val book = ready.book
    val source = ready.source
    val listState = rememberLazyListState(ready.initial.blockIndex, ready.initial.offset)
    val scope = rememberCoroutineScope()
    var chrome by rememberSaveable { mutableStateOf(true) }
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    var penMenu by rememberSaveable { mutableStateOf(false) }
    var tool by rememberSaveable { mutableStateOf(MarkTool.NONE) }
    var viewer by remember { mutableStateOf<ViewerState?>(null) }
    // The passage selected with two fingers, to copy.
    val selection = remember { TwoFingerSelection() }
    var spot by remember { mutableStateOf<NoteSpot?>(null) }
    var noteStep by remember { mutableStateOf(NoteStep.CHOOSE) }
    var openNote by remember { mutableStateOf<Note?>(null) }
    val notes by vm.notes.collectAsStateWithLifecycle()
    val style = remember(colors, settings) { readerStyle(colors, settings) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val highlights by vm.highlights.collectAsStateWithLifecycle()
    val registry = remember { MarkRegistry() }
    val flash = remember { PassageFlash() }
    // (where the page is on screen: the texts are placed in root coordinates)
    val listPlace = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val ink by vm.ink.collectAsStateWithLifecycle()
    val marks = remember(highlights, ink, registry) { ReaderMarks(highlights, ink, registry) }
    val density = LocalDensity.current.density

    SystemBars(dark = colors.isDark)
    KeepScreenOn(settings.keepScreenOn)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .debounce(400)
            .collect { (i, o) -> vm.savePosition(i, o) }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) chrome = false }
    }
    LaunchedEffect(jumpToPage) {
        if (jumpToPage != null) {
            val line = jumpPassage
            val found = line?.let { vm.findPassage(jumpToPage, it) }
            onJumpConsumed()
            if (found == null) {
                vm.blockForPage(jumpToPage)?.let { listState.scrollToItem(it) }
            } else {
                // (in its own scope: consuming the jump restarts this effect)
                scope.launch {
                    listState.scrollToItem(found)
                    // the held line a third of the way down the screen, however long its paragraph
                    // (as soon as its text is laid out: the block may still be loading)
                    fun lineTop(): Float? = registry.targets.entries.filter { MarkKey.block(it.key) == found }
                        .firstNotNullOfOrNull { (_, t) ->
                            val l = t.layout ?: return@firstNotNullOfOrNull null
                            val c = t.coords?.takeIf { it.isAttached } ?: return@firstNotNullOfOrNull null
                            val r = Passage.rangeIn(l.layoutInput.text.text, line)?.second ?: return@firstNotNullOfOrNull null
                            c.positionInRoot().y + l.getLineTop(l.getLineForOffset(r.first))
                        }
                    var text: Float? = null
                    for (frame in 0 until 90) {
                        withFrameNanos { }
                        text = lineTop()
                        if (text != null) break
                    }
                    val place = listPlace[0]
                    if (text != null && place != null && place.isAttached) {
                        val want = place.positionInRoot().y + listState.layoutInfo.viewportSize.height * 0.3f
                        listState.scrollBy(text - want)
                    }
                    flash.show(found, line)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { vm.savePosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    BackHandler(enabled = panel != Panel.NONE || penMenu) {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            tool == MarkTool.NOTE -> tool = MarkTool.NONE
            else -> {
                penMenu = false
                tool = MarkTool.NONE
            }
        }
    }

    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val currentPage = source.peek(firstVisible)?.pageIndex ?: 0
    val chapter = remember(firstVisible, ready.toc) { ready.toc.lastOrNull { it.blockIndex <= firstVisible } }
    // derivedStateOf: recompose when the visible range changes, not on every scrolled pixel.
    val lastVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: listState.firstVisibleItemIndex } }
    val bookmarked = bookmarks.any { it.blockIndex in firstVisible..maxOf(firstVisible, lastVisible) }
    val progress = if (book.blockCount <= 1) 1f else firstVisible.toFloat() / (book.blockCount - 1)

    CompositionLocalProvider(LocalMarks provides marks, LocalSelection provides selection, LocalFlash provides flash) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = settings.margin.dp
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                // One finger held still for 0.6 s opens the original page (and the same there
                // comes back); two fingers dragged over the text select it to copy.
                .onGloballyPositioned { listPlace[0] = it }
                .holdGestures(enabled = tool == MarkTool.NONE, onHold = { at ->
                    // the words under the finger go along, to be lit up on the page
                    val root = listPlace[0]?.takeIf { it.isAttached }?.let { it.positionInRoot() + at } ?: at
                    val pos = charAt(root, registry)
                    val held = pos?.let { p -> registry.targets[p.key]?.layout?.layoutInput?.text?.text?.let { Passage.around(it, p.offset) } }
                    val page = pos?.let { source.peek(MarkKey.block(it.key))?.pageIndex } ?: currentPage
                    if (held != null) vm.prefetchOriginal(page)
                    onOpenOriginal(page, held)
                })
                .twoFingerSelect(selection, registry, listState, enabled = tool == MarkTool.NONE)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { pos ->
                            if (selection.anchor != null) {
                                // (a tap lets go of the selection)
                                selection.clear()
                            } else if (pos.x > size.width * 0.8f) {
                                // Right edge: the pen menu.
                                penMenu = !penMenu
                                if (!penMenu) tool = MarkTool.NONE
                            } else {
                                chrome = !chrome
                            }
                        },
                    )
                },
            contentPadding = PaddingValues(start = margin, end = margin, top = topInset + 28.dp, bottom = bottomInset + 64.dp),
        ) {
            items(
                count = source.count,
                key = { it },
                contentType = { index -> source.peek(index)?.let { it::class.simpleName } ?: "placeholder" },
            ) { index ->
                val block = source.get(index)
                if (block == null) {
                    BlockPlaceholder(style)
                } else {
                    CalloutBox(ready.boxes.find(index), index, style) {
                        val pins = notes[index]
                        // A block with notes keeps a free strip at the right for the pins.
                        val strip = if (pins.isNullOrEmpty()) 0.dp else NoteStrip
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(
                                    if (tool == MarkTool.NOTE) {
                                        // Choosing a place for a note: the next tap anywhere in the block
                                        // (also on a picture or a table) is it; scrolling still works.
                                        Modifier.pointerInput(index, strip) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                                    if (event.changes.size > 1 || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
                                                    if (change.changedToUp()) {
                                                        change.consume()
                                                        val textWidth = (size.width - strip.toPx()).coerceAtLeast(1f)
                                                        spot = NoteSpot(
                                                            block.index, block.pageIndex,
                                                            (down.position.x / textWidth).coerceIn(0f, 1f), (down.position.y / size.height).coerceIn(0f, 1f),
                                                        )
                                                        noteStep = NoteStep.CHOOSE
                                                        tool = MarkTool.NONE
                                                        break
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                        ) {
                            Box(Modifier.fillMaxWidth().padding(end = strip)) {
                                BlockView(
                                    block, style, vm.highlight,
                                    onContentsClick = { title ->
                                        scope.launch { vm.contentsTarget(title, block.index, ready.toc)?.let { listState.animateScrollToItem(it) } }
                                    },
                                ) { path, caption, equation, blockIndex ->
                                    viewer = ViewerState(path, caption, equation, blockIndex, penMenu, pictureBookText(source.peek(blockIndex)))
                                }
                            }
                            if (!pins.isNullOrEmpty()) NotesLayer(pins, onOpen = { openNote = it }, modifier = Modifier.matchParentSize())
                        }
                    }
                }
            }
        }

        // The status and navigation bars stay on screen. Text scrolls away under a cover the colour of the page (pictures never show behind the icons),
        // and touches in the strip along the bottom edge are not given to the page, so a swipe
        // up to go home never scrolls the book.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(topInset).background(colors.background))
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomInset + 14.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, colors.background.copy(alpha = 0.96f))))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false).consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                },
        )

        // The passage selected with two fingers: copy it, or let it go.
        SelectionBar(selection, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))

        // Minimal progress line while reading without chrome.
        AnimatedVisibility(!chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Text(
                "${(progress * 100).roundToInt()}%",
                color = colors.secondary.copy(alpha = 0.7f),
                fontSize = 11.sp,
                modifier = Modifier.navigationBarsPadding().padding(bottom = 6.dp),
            )
        }

        AnimatedVisibility(
            chrome, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        ) {
            TopBar(
                title = book.title,
                subtitle = chapter?.title,
                colors = colors,
                bookmarked = bookmarked,
                onBack = onBack,
                onSearch = { panel = Panel.SEARCH },
                onContents = { panel = Panel.CONTENTS },
                onBookmark = { vm.toggleBookmark(firstVisible, maxOf(firstVisible, lastVisible)) },
                onSettings = { panel = Panel.SETTINGS },
                onOriginal = { onOpenOriginal(currentPage, null) },
                onInfo = { panel = Panel.INFO },
                onMarker = { penMenu = true; chrome = false },
            )
        }
        AnimatedVisibility(
            chrome, Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
        ) {
            BottomBar(
                colors = colors,
                chapter = chapter?.title,
                page = currentPage + 1,
                pageCount = book.pageCount,
                progress = progress,
                onSeek = { f -> scope.launch { listState.scrollToItem(((source.count - 1) * f).roundToInt().coerceIn(0, maxOf(0, source.count - 1))) } },
            )
        }

        // Pen: while a colour (or the eraser) is chosen, a finger draws instead of scrolling.
        if (tool != MarkTool.NONE && tool != MarkTool.NOTE) {
            MarkOverlay(
                tool = tool,
                onStroke = { points, origin ->
                    val root = points.map { it + origin }
                    val picture = imageAt(root.first(), registry)
                    if (picture != null) {
                        // Started on a picture: freehand line (or erase lines) on it.
                        val (block, pictureOrigin, size) = picture
                        val local = root.map { it - pictureOrigin }
                        val color = tool.color
                        if (color != null) {
                            val line = normalizedInk(local, size, 2.5f * density)
                            if (line.size >= 2) vm.addInk(block, color, line)
                        } else {
                            vm.eraseInk(inkHitIds(ink[block].orEmpty(), local, size, 14f * density))
                        }
                    } else {
                        val edits = strokeToRanges(root, registry, density)
                        if (edits.isNotEmpty()) vm.mark(edits, tool.color)
                    }
                },
            )
        }
        AnimatedVisibility(
            penMenu && tool != MarkTool.NOTE, Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            MarkPalette(
                tool = tool, colors = colors,
                onSelect = { tool = it; if (it != MarkTool.NONE) chrome = false },
                onClose = { penMenu = false; tool = MarkTool.NONE },
                modifier = Modifier.padding(end = 10.dp),
            )
        }
        AnimatedVisibility(
            tool == MarkTool.NOTE, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        ) {
            NoteHint(colors, onCancel = { tool = MarkTool.NONE })
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
                    vm.addNote(place.block, place.page, place.x, place.y, NoteKind.TEXT, text, null, 0L)
                    done()
                },
            )
            NoteStep.RECORD -> VoiceRecordDialog(
                newFile = vm::newVoiceFile, onDismiss = ::done,
                onSaved = { file, duration ->
                    vm.addNote(place.block, place.page, place.x, place.y, NoteKind.VOICE, "", file.absolutePath, duration)
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

    when (panel) {
        Panel.CONTENTS -> ContentsSheet(
            toc = ready.toc,
            bookmarks = bookmarks,
            currentIndex = firstVisible,
            colors = colors,
            onJump = { index -> panel = Panel.NONE; scope.launch { listState.scrollToItem(index) } },
            onDeleteBookmark = { vm.removeBookmark(it) },
            onDismiss = { panel = Panel.NONE },
        )
        Panel.SETTINGS -> SettingsSheet(settings, onChange = vm::updateSettings, onDismiss = { panel = Panel.NONE })
        Panel.SEARCH -> SearchPanel(
            vm = vm,
            colors = colors,
            onResult = { hit ->
                vm.highlight = vm.query.trim()
                panel = Panel.NONE
                chrome = false
                scope.launch { listState.scrollToItem(hit.blockIndex) }
            },
            onClose = { panel = Panel.NONE },
        )
        Panel.INFO -> BookInfoDialog(book, onDismiss = { panel = Panel.NONE })
        Panel.NONE -> Unit
    }
    viewer?.let { v ->
        ImageViewer(
            path = v.path, caption = v.caption, equation = v.equation,
            strokes = if (v.equation) emptyList() else ink[v.block].orEmpty(),
            colors = colors,
            penOnStart = v.pen,
            onDraw = { color, line -> vm.addInk(v.block, color, line) },
            onErase = vm::eraseInk,
            onClose = { viewer = null },
            bookText = v.text,
            readText = { vm.pictureText(v.path) },
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    subtitle: String?,
    colors: ReaderColors,
    bookmarked: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onContents: () -> Unit,
    onBookmark: () -> Unit,
    onSettings: () -> Unit,
    onOriginal: () -> Unit,
    onInfo: () -> Unit,
    onMarker: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = colors.surface, contentColor = colors.text, shadowElevation = 4.dp) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onSearch) { Icon(Icons.Default.Search, stringResource(R.string.reader_search)) }
            IconButton(onClick = onContents) { Icon(RaptiIcons.Toc, stringResource(R.string.reader_contents)) }
            IconButton(onClick = onBookmark) {
                Icon(
                    if (bookmarked) RaptiIcons.Bookmark else RaptiIcons.BookmarkBorder,
                    stringResource(if (bookmarked) R.string.reader_bookmark_remove else R.string.reader_bookmark_add),
                    tint = if (bookmarked) colors.accent else colors.text,
                )
            }
            IconButton(onClick = onSettings) { Icon(RaptiIcons.TextFormat, stringResource(R.string.reader_text_settings)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.reader_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_marker)) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; onMarker() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_original)) }, leadingIcon = { Icon(RaptiIcons.Pdf, null) }, onClick = { menu = false; onOriginal() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.reader_info)) }, leadingIcon = { Icon(Icons.Default.Info, null) }, onClick = { menu = false; onInfo() })
                }
            }
        }
    }
}

@Composable
private fun BottomBar(colors: ReaderColors, chapter: String?, page: Int, pageCount: Int, progress: Float, onSeek: (Float) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    Surface(color = colors.surface, contentColor = colors.text, shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(chapter ?: "", fontSize = 12.sp, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.reader_page, page, maxOf(page, pageCount)), fontSize = 12.sp, color = colors.secondary)
            }
            Slider(
                value = dragging ?: progress,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let(onSeek); dragging = null },
                colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.secondary.copy(alpha = 0.3f)),
            )
            Text(
                stringResource(R.string.reader_percent, ((dragging ?: progress) * 100).roundToInt()),
                fontSize = 12.sp, color = colors.secondary, modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/**
 * Keeps the status and navigation bars visible while reading, their icons readable on the
 * reading theme ([dark]: a dark page).
 */
@Composable
internal fun SystemBars(dark: Boolean) {
    SystemBarIcons(darkBackground = dark)
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return
    DisposableEffect(Unit) {
        WindowCompat.getInsetsController(activity.window, view).show(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

internal fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** The text the book's text layer has for a block shown as a picture: a formula's, a table's. */
private fun pictureBookText(block: ContentBlock?): String? = when (block) {
    is ImageBlock -> block.altText
    is TableBlock -> block.rows.joinToString("\n") { r -> r.joinToString("\t") }
    else -> null
}
