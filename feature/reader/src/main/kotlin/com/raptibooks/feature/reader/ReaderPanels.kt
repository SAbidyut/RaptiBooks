package com.raptibooks.feature.reader

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
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
import com.raptibooks.core.model.InkStroke
import com.raptibooks.core.model.MarkColor
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

/**
 * Keeps a picture of size [picture] (at scale 1, centred) that is shown [scale] times larger on
 * the screen: it may move only as far as its edges reach past the screen's edges.
 */
internal fun clampOffset(offset: Offset, picture: Size, viewport: Size, scale: Float): Offset {
    val maxX = ((picture.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((picture.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/**
 * Full-screen image viewer with pinch-zoom, pan and double-tap zoom. For pictures of the book
 * (not equations) the colour menu is there too: pick a colour and draw anywhere on the picture;
 * the lines stay where they were drawn. With no colour chosen, one finger pans and zooms.
 */
@Composable
internal fun ImageViewer(
    path: String,
    caption: String?,
    equation: Boolean,
    strokes: List<InkStroke>,
    colors: ReaderColors,
    penOnStart: Boolean,
    onDraw: (MarkColor, FloatArray) -> Unit,
    onErase: (List<Long>) -> Unit,
    onClose: () -> Unit,
    /** The picture's text as the book's text layer has it (a formula's, a table's), if any. */
    bookText: String? = null,
    /** Reads the text printed in the picture (OCR). */
    readText: (suspend () -> String?)? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        var tool by remember { mutableStateOf(MarkTool.NONE) }
        var penOpen by remember { mutableStateOf(penOnStart) }
        var textOpen by remember { mutableStateOf(false) }
        // The picture's text, read once when first asked for (null while reading).
        var pictureText by remember { mutableStateOf<PictureOcr?>(null) }
        val scope = rememberCoroutineScope()
        BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
            val density = LocalDensity.current
            val widthPx = with(density) { (maxWidth.toPx() * 2).roundToInt() }
            val image by rememberLocalImage(path, widthPx)
            val img = image
            if (img != null) {
                val aspect = img.width.toFloat() / maxOf(1, img.height)
                val fitWidth = minOf(constraints.maxWidth.toFloat(), constraints.maxHeight * aspect)
                val picture = Size(fitWidth, fitWidth / aspect)
                val viewport = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
                // Zoom around a point (the fingers, a double tap) so that it stays where it is, and
                // never let the picture be dragged off the screen.
                fun zoomTo(newScale: Float, focus: Offset, pan: Offset = Offset.Zero) {
                    val target = newScale.coerceIn(1f, 6f)
                    val center = Offset(viewport.width / 2f, viewport.height / 2f)
                    val point = (focus - center - offset) / scale
                    offset = clampOffset(focus - center - point * target + pan, picture, viewport, target)
                    scale = target
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(picture, viewport) {
                            detectTapGestures(onDoubleTap = { tap ->
                                if (scale > 1.05f) { scale = 1f; offset = Offset.Zero } else zoomTo(2.5f, tap)
                            })
                        }
                        .pointerInput(picture, viewport) {
                            detectTransformGestures { centroid, pan, zoom, _ -> zoomTo(scale * zoom, centroid, pan) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(with(density) { picture.width.toDp() }, with(density) { picture.height.toDp() })
                            // Figures with a transparent background must not vanish on the black viewer.
                            .then(if (equation) Modifier else Modifier.background(Color.White))
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                    ) {
                        Image(
                            img, caption, contentScale = ContentScale.Fit,
                            // Equations are ink masks: white on the black viewer.
                            colorFilter = if (equation) ColorFilter.tint(Color.White) else null,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (!equation) {
                            InkCanvas(strokes, Modifier.fillMaxSize())
                            if (tool != MarkTool.NONE) {
                                MarkOverlay(
                                    tool = tool,
                                    onStroke = { points, _ ->
                                        val color = tool.color
                                        if (color != null) {
                                            val line = normalizedInk(points, picture, 2.5f * density.density)
                                            if (line.size >= 2) onDraw(color, line)
                                        } else {
                                            onErase(inkHitIds(strokes, points, picture, 14f * density.density))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            } else {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
            }
            IconButton(onClick = onClose, modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                Icon(Icons.Default.Close, stringResource(R.string.image_close), tint = Color.White)
            }
            if (readText != null && img != null && tool == MarkTool.NONE) {
                TextButton(
                    onClick = {
                        textOpen = !textOpen
                        if (textOpen && pictureText == null) {
                            pictureText = PictureOcr.Reading
                            scope.launch {
                                val text = try {
                                    readText()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    null
                                }
                                pictureText = PictureOcr.Read(text)
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
                ) {
                    Text(stringResource(R.string.image_text), color = Color.White)
                }
            }
            val ocr = pictureText
            if (textOpen && ocr != null) {
                PictureTextPanel(
                    ocr, bookText, onClose = { textOpen = false },
                    modifier = Modifier.align(Alignment.BottomCenter).heightIn(max = maxHeight * 0.55f),
                )
            } else if (caption != null) {
                SelectionContainer(Modifier.align(Alignment.BottomCenter)) {
                    Text(
                        caption, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).padding(16.dp),
                    )
                }
            }
            // The colour menu is only here when the pen was switched on while reading.
            if (!equation && img != null && penOpen) {
                MarkPalette(
                    tool = tool, colors = colors, onSelect = { tool = it },
                    onClose = { penOpen = false; tool = MarkTool.NONE },
                    withNote = false,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                )
            }
        }
    }
}

/** Reading the text printed in a picture: under way, or done (null text: none found). */
private sealed interface PictureOcr {
    data object Reading : PictureOcr
    class Read(val text: String?) : PictureOcr
}

/**
 * The text of a picture, to select or copy: what the OCR reads in it, and the text the book's
 * own text layer has for it (when there is one and it says something else).
 */
@Composable
private fun PictureTextPanel(ocr: PictureOcr, bookText: String?, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val reading = ocr == PictureOcr.Reading
    val text = (ocr as? PictureOcr.Read)?.text
    val book = bookText?.trim()?.takeIf { it.isNotEmpty() && it != text }
    Surface(color = Color(0xF0202124), contentColor = Color.White, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.image_text_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.image_close), tint = Color.White) }
            }
            val found = text
            when {
                reading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.image_text_reading))
                }
                found != null -> CopyableText(found)
                else -> Text(stringResource(R.string.image_text_none), color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(vertical = 8.dp))
            }
            if (book != null && !reading) {
                Text(
                    stringResource(R.string.image_text_book), style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 12.dp),
                )
                CopyableText(book)
            }
        }
    }
}

/** A passage of the book held with two fingers: its text, to select a part of or copy whole. */
@Composable
internal fun TextCopyDialog(text: String, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.image_text)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                CopyableText(text)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.image_close)) } },
    )
}

/** Text that can be selected, with a button that copies all of it. */
@Composable
private fun CopyableText(text: String) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.text_copied)
    SelectionContainer {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
    }
    TextButton(onClick = {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("text", text)))
            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
        }
    }) {
        Text(stringResource(R.string.text_copy))
    }
}
