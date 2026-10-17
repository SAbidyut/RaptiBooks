package com.raptibooks.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raptibooks.core.data.appContainer
import com.raptibooks.core.model.ReaderSettings
import com.raptibooks.core.ui.RaptiIcons
import com.raptibooks.core.ui.ReaderColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderRoute(
    bookId: Long,
    jumpToPage: Int?,
    onJumpConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenOriginal: (page: Int) -> Unit,
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
            is ReaderViewModel.Load.Ready -> Reader(vm, load, settings, colors, jumpToPage, onJumpConsumed, onBack, onOpenOriginal)
        }
    }
}

private enum class Panel { NONE, CONTENTS, SETTINGS, SEARCH, INFO }

@OptIn(FlowPreview::class)
@Composable
private fun Reader(
    vm: ReaderViewModel,
    ready: ReaderViewModel.Load.Ready,
    settings: ReaderSettings,
    colors: ReaderColors,
    jumpToPage: Int?,
    onJumpConsumed: () -> Unit,
    onBack: () -> Unit,
    onOpenOriginal: (Int) -> Unit,
) {
    val book = ready.book
    val source = ready.source
    val listState = rememberLazyListState(ready.initial.blockIndex, ready.initial.offset)
    val scope = rememberCoroutineScope()
    var chrome by rememberSaveable { mutableStateOf(true) }
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    var viewer by remember { mutableStateOf<Triple<String, String?, Boolean>?>(null) }
    val style = remember(colors, settings) { readerStyle(colors, settings) }
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()

    SystemBars(dark = colors.isDark, visible = chrome || panel != Panel.NONE)
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
            vm.blockForPage(jumpToPage)?.let { listState.scrollToItem(it) }
            onJumpConsumed()
        }
    }
    DisposableEffect(Unit) {
        onDispose { vm.savePosition(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }
    BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }

    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val currentPage = source.peek(firstVisible)?.pageIndex ?: 0
    val chapter = remember(firstVisible, ready.toc) { ready.toc.lastOrNull { it.blockIndex <= firstVisible } }
    // derivedStateOf: recompose when the visible range changes, not on every scrolled pixel.
    val lastVisible by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: listState.firstVisibleItemIndex } }
    val bookmarked = bookmarks.any { it.blockIndex in firstVisible..maxOf(firstVisible, lastVisible) }
    val progress = if (book.blockCount <= 1) 1f else firstVisible.toFloat() / (book.blockCount - 1)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportPx = constraints.maxHeight.toFloat()
        val margin = settings.margin.dp
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(settings.tapToTurn) {
                    detectTapGestures(
                        onTap = { pos ->
                            val w = size.width
                            when {
                                settings.tapToTurn && pos.x < w * 0.2f -> {
                                    chrome = false
                                    scope.launch { listState.animateScrollBy(-viewportPx * 0.88f) }
                                }
                                settings.tapToTurn && pos.x > w * 0.8f -> {
                                    chrome = false
                                    scope.launch { listState.animateScrollBy(viewportPx * 0.88f) }
                                }
                                else -> chrome = !chrome
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
                        BlockView(
                            block, style, vm.highlight,
                            onContentsClick = { title ->
                                scope.launch { vm.contentsTarget(title, block.index, ready.toc)?.let { listState.animateScrollToItem(it) } }
                            },
                        ) { path, caption, ink -> viewer = Triple(path, caption, ink) }
                    }
                }
            }
        }

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
                onOriginal = { onOpenOriginal(currentPage) },
                onInfo = { panel = Panel.INFO },
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
    viewer?.let { (path, caption, ink) -> ImageViewer(path, caption, ink, onClose = { viewer = null }) }
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

@Composable
private fun SystemBars(dark: Boolean, visible: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity() ?: return
    DisposableEffect(dark, visible) {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller.show(WindowInsetsCompat.Type.systemBars()) }
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
