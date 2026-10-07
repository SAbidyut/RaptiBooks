package com.raptibooks.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.raptibooks.core.model.Book
import com.raptibooks.core.model.Bookmark
import com.raptibooks.core.model.ReaderFont
import com.raptibooks.core.model.ReaderSettings
import com.raptibooks.core.model.ReaderTheme
import com.raptibooks.core.model.SearchHit
import com.raptibooks.core.model.TocEntry
import com.raptibooks.core.ui.RaptiFonts
import com.raptibooks.core.ui.ReaderColors
import com.raptibooks.core.ui.rememberLocalImage
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContentsSheet(
    toc: List<TocEntry>,
    bookmarks: List<Bookmark>,
    currentIndex: Int,
    colors: ReaderColors,
    onJump: (Int) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, { tab = 0 }, text = { Text(stringResource(R.string.reader_contents)) })
            Tab(tab == 1, { tab = 1 }, text = { Text("${stringResource(R.string.reader_bookmarks)} (${bookmarks.size})") })
        }
        if (tab == 0) {
            if (toc.isEmpty()) {
                EmptyNote(stringResource(R.string.reader_no_contents))
            } else {
                val current = toc.indexOfLast { it.blockIndex <= currentIndex }
                val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
                LazyColumn(state = state, contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.heightIn(max = 640.dp)) {
                    items(toc.size) { i ->
                        val e = toc[i]
                        val selected = i == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(e.blockIndex) }
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) else Color.Transparent)
                                .padding(start = (20 + (e.level - 1) * 18).dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
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
        } else {
            if (bookmarks.isEmpty()) {
                EmptyNote(stringResource(R.string.reader_no_bookmarks))
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 32.dp), modifier = Modifier.heightIn(max = 640.dp)) {
                    items(bookmarks, key = { it.id }) { b ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onJump(b.blockIndex) }.padding(start = 20.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.reader_page_short, b.pageIndex + 1), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                Text(b.snippet.ifBlank { "…" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onDeleteBookmark(b) }) { Icon(Icons.Default.Delete, stringResource(R.string.reader_bookmark_remove)) }
                        }
                        HorizontalDivider(Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyNote(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSheet(settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Label(stringResource(R.string.settings_theme))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((theme, label) in listOf(
                    ReaderTheme.LIGHT to R.string.settings_theme_light,
                    ReaderTheme.SEPIA to R.string.settings_theme_sepia,
                    ReaderTheme.DARK to R.string.settings_theme_dark,
                    ReaderTheme.BLACK to R.string.settings_theme_black,
                )) {
                    val c = ReaderColors.of(theme)
                    val selected = settings.theme == theme
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onChange { it.copy(theme = theme) } }) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .background(c.background, CircleShape)
                                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", color = c.text, fontFamily = RaptiFonts.serif, fontSize = 18.sp) }
                        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }

            Label(stringResource(R.string.settings_font_size) + " · ${settings.fontSize.roundToInt()}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontSize = 14.sp)
                Slider(
                    value = settings.fontSize,
                    onValueChange = { v -> onChange { it.copy(fontSize = v) } },
                    valueRange = ReaderSettings.MIN_FONT_SIZE..ReaderSettings.MAX_FONT_SIZE,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Text("A", fontSize = 24.sp)
            }

            Label(stringResource(R.string.settings_font))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FontChip(ReaderFont.SERIF, R.string.settings_font_serif, settings, onChange, Modifier.weight(1f))
                FontChip(ReaderFont.SANS, R.string.settings_font_sans, settings, onChange, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FontChip(ReaderFont.SYSTEM_SERIF, R.string.settings_font_system_serif, settings, onChange, Modifier.weight(1f))
                FontChip(ReaderFont.SYSTEM_SANS, R.string.settings_font_system_sans, settings, onChange, Modifier.weight(1f))
            }

            Label(stringResource(R.string.settings_line_spacing) + " · " + "%.1f".format(settings.lineSpacing))
            Slider(
                value = settings.lineSpacing,
                onValueChange = { v -> onChange { it.copy(lineSpacing = (v * 10).roundToInt() / 10f) } },
                valueRange = ReaderSettings.MIN_LINE_SPACING..ReaderSettings.MAX_LINE_SPACING,
            )
            Label(stringResource(R.string.settings_margins) + " · ${settings.margin} dp")
            Slider(
                value = settings.margin.toFloat(),
                onValueChange = { v -> onChange { it.copy(margin = v.roundToInt()) } },
                valueRange = ReaderSettings.MIN_MARGIN.toFloat()..ReaderSettings.MAX_MARGIN.toFloat(),
            )
            SwitchRow(stringResource(R.string.settings_justify), settings.justify) { v -> onChange { it.copy(justify = v) } }
            SwitchRow(stringResource(R.string.settings_tap_turn), settings.tapToTurn) { v -> onChange { it.copy(tapToTurn = v) } }
            SwitchRow(stringResource(R.string.settings_keep_on), settings.keepScreenOn) { v -> onChange { it.copy(keepScreenOn = v) } }
        }
    }
}

@Composable
private fun FontChip(font: ReaderFont, label: Int, settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit, modifier: Modifier) {
    FilterChip(
        selected = settings.font == font,
        onClick = { onChange { it.copy(font = font) } },
        label = {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text("অআ Aa", fontFamily = RaptiFonts.forReader(font), fontSize = 17.sp)
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun SearchPanel(vm: ReaderViewModel, colors: ReaderColors, onResult: (SearchHit) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_search_close)) }
                TextField(
                    value = vm.query,
                    onValueChange = vm::search,
                    placeholder = { Text(stringResource(R.string.reader_search_hint)) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (vm.query.isNotEmpty()) IconButton(onClick = { vm.clearSearch(); vm.highlight = null }) { Icon(Icons.Default.Close, stringResource(R.string.reader_clear_highlight)) }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                )
            }
            val results = vm.results
            when {
                vm.searching -> Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                results != null -> {
                    Text(
                        if (results.isEmpty()) stringResource(R.string.reader_search_none) else stringResource(R.string.reader_search_results, results.size),
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        items(results, key = { it.blockIndex * 31 + it.matchStart }) { hit ->
                            Column(Modifier.fillMaxWidth().clickable { onResult(hit) }.padding(horizontal = 20.dp, vertical = 12.dp)) {
                                Text(stringResource(R.string.reader_page_short, hit.pageIndex + 1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(
                                    buildAnnotatedString {
                                        val s = hit.snippet
                                        val a = hit.matchStart.coerceIn(0, s.length)
                                        val b = hit.matchEnd.coerceIn(a, s.length)
                                        append(s.substring(0, a))
                                        withStyle(SpanStyle(background = colors.highlight, fontWeight = FontWeight.Bold)) { append(s.substring(a, b)) }
                                        append(s.substring(b))
                                    },
                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = RaptiFonts.serif, fontSize = 16.sp),
                                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                                )
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BookInfoDialog(book: Book, onDismiss: () -> Unit) {
    val language = when (book.language) {
        "bn" -> stringResource(R.string.lang_bn)
        "en" -> stringResource(R.string.lang_en)
        "bn+en" -> stringResource(R.string.lang_mixed)
        else -> stringResource(R.string.lang_unknown)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                book.author?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                InfoRow(stringResource(R.string.info_file), book.fileName)
                InfoRow(stringResource(R.string.info_pages), book.pageCount.toString())
                InfoRow(stringResource(R.string.info_size), "%.1f MB".format(book.fileSize / 1_048_576f))
                InfoRow(stringResource(R.string.info_language), language)
                InfoRow(stringResource(R.string.info_ocr), stringResource(if (book.usedOcr) R.string.info_ocr_yes else R.string.info_ocr_no))
                InfoRow(stringResource(R.string.info_blocks), book.blockCount.toString())
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(140.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Full-screen image viewer with pinch-zoom, pan and double-tap zoom. */
@Composable
internal fun ImageViewer(path: String, caption: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 6f)
            offset = if (scale == 1f) Offset.Zero else offset + pan
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val widthPx = with(LocalDensity.current) { (maxWidth.toPx() * 2).roundToInt() }
            val image by rememberLocalImage(path, widthPx)
            val img = image
            if (img != null) {
                Image(
                    img, caption, contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero } else scale = 2.5f
                            })
                        }
                        .transformable(state)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                )
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.image_close), tint = Color.White)
            }
            if (caption != null) {
                Text(
                    caption, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
                )
            }
        }
    }
}
