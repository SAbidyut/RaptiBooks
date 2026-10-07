package com.raptibooks.feature.library

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.raptibooks.core.data.appContainer
import com.raptibooks.core.model.Book
import com.raptibooks.core.model.BookStatus
import com.raptibooks.core.model.OcrMode
import com.raptibooks.core.ui.RaptiFonts
import com.raptibooks.core.ui.RaptiIcons
import com.raptibooks.core.ui.rememberLocalImage
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun LibraryRoute(
    onOpenBook: (Long) -> Unit,
    onOpenOriginal: (Long) -> Unit,
    pendingImport: Uri?,
    onPendingImportConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val vm: LibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val c = context.appContainer()
                LibraryViewModel(c.library, c.settings)
            }
        },
    )
    LaunchedEffect(pendingImport) {
        if (pendingImport != null) {
            vm.import(pendingImport)
            onPendingImportConsumed()
        }
    }
    LibraryScreen(vm, onOpenBook, onOpenOriginal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(vm: LibraryViewModel, onOpenBook: (Long) -> Unit, onOpenOriginal: (Long) -> Unit) {
    val context = LocalContext.current
    val books by vm.books.collectAsStateWithLifecycle()
    val ocrMode by vm.defaultOcrMode.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var showOcrDialog by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var deleteCandidate by remember { mutableStateOf<Book?>(null) }

    val importedMsg = stringResource(R.string.library_imported)
    val stillConverting = stringResource(R.string.library_still_converting)
    LaunchedEffect(vm) {
        vm.messages.collect { m ->
            when (m) {
                LibraryMessage.Imported -> snackbar.showSnackbar(importedMsg)
                is LibraryMessage.Failed -> snackbar.showSnackbar(m.reason)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        picker.launch(arrayOf("application/pdf"))
    }
    val startImport = {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else picker.launch(arrayOf("application/pdf"))
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(RaptiIcons.Book, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_title), fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.action_more)) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_ocr_mode)) },
                            leadingIcon = { Icon(RaptiIcons.Ocr, null) },
                            onClick = { menuOpen = false; showOcrDialog = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_about)) },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menuOpen = false; showAbout = true },
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (!vm.importing) startImport() },
                icon = {
                    if (vm.importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(RaptiIcons.FileUpload, null)
                },
                text = { Text(stringResource(if (vm.importing) R.string.library_importing else R.string.library_import)) },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val list = books
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> EmptyLibrary(Modifier.padding(padding), onImport = startImport)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                items(list, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onOpen = {
                            when {
                                book.isReadable -> onOpenBook(book.id)
                                book.isConverting -> scope.launch { snackbar.showSnackbar(stillConverting) }
                                else -> Unit
                            }
                        },
                        onOpenOriginal = { onOpenOriginal(book.id) },
                        onDelete = { deleteCandidate = book },
                        onReconvert = { mode -> vm.reconvert(book, mode) },
                        onCancel = { vm.cancel(book) },
                        onRetry = { vm.retry(book) },
                    )
                }
            }
        }
    }

    deleteCandidate?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, book.title)) },
            confirmButton = {
                TextButton(onClick = { vm.delete(book); deleteCandidate = null }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (showOcrDialog) OcrModeDialog(ocrMode, onSelect = { vm.setDefaultOcrMode(it); showOcrDialog = false }, onDismiss = { showOcrDialog = false })
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
}

@Composable
private fun EmptyLibrary(modifier: Modifier, onImport: () -> Unit) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(112.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(RaptiIcons.Book, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.empty_steps),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        FilledTonalButton(onClick = onImport) {
            Icon(RaptiIcons.FileUpload, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.library_import))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    book: Book,
    onOpen: () -> Unit,
    onOpenOriginal: () -> Unit,
    onDelete: () -> Unit,
    onReconvert: (OcrMode) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onOpen, onLongClick = { menu = true })) {
        Box {
            Cover(book)
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menu = true }) {
                    Surface(shape = RoundedCornerShape(50), color = Color.Black.copy(alpha = 0.35f)) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.action_more), tint = Color.White, modifier = Modifier.padding(4.dp).size(18.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (book.isReadable) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_read)) }, leadingIcon = { Icon(RaptiIcons.Article, null) }, onClick = { menu = false; onOpen() })
                    }
                    if (book.pdfPath.isNotEmpty()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_original)) }, leadingIcon = { Icon(RaptiIcons.Pdf, null) }, onClick = { menu = false; onOpenOriginal() })
                    }
                    if (book.isConverting) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.action_cancel_conversion)) }, onClick = { menu = false; onCancel() })
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_reconvert)) },
                            leadingIcon = { Icon(Icons.Default.Settings, null) },
                            onClick = { menu = false; onReconvert(OcrMode.AUTO) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_reconvert_ocr)) },
                            leadingIcon = { Icon(RaptiIcons.Ocr, null) },
                            onClick = { menu = false; onReconvert(OcrMode.ALWAYS) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val sub = book.author ?: pluralPages(book.pageCount)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        when (book.status) {
            BookStatus.READY -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { book.readingProgress },
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (book.readingProgress <= 0f) stringResource(R.string.status_new) else "${(book.readingProgress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BookStatus.QUEUED, BookStatus.PROCESSING -> {
                LinearProgressIndicator(
                    progress = { book.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    book.progressMessage ?: stringResource(R.string.status_queued),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BookStatus.FAILED, BookStatus.CANCELLED -> {
                Text(
                    book.errorMessage ?: stringResource(R.string.status_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp)) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

@Composable
private fun pluralPages(count: Int): String = if (count == 1) stringResource(R.string.one_page) else stringResource(R.string.n_pages, count)

@Composable
private fun Cover(book: Book) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.72f)
            .shadow(6.dp, RoundedCornerShape(10.dp))
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val widthPx = with(LocalDensity.current) { maxWidth.toPx().roundToInt() }
        val cover by rememberLocalImage(book.coverPath, widthPx)
        val image = cover
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    book.title,
                    color = Color.White,
                    fontFamily = RaptiFonts.serif,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
        if (book.isConverting) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(progress = { book.progress.coerceAtLeast(0.02f) }, color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
                Text("${(book.progress * 100).roundToInt()}%", color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        } else if (book.status == BookStatus.FAILED) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }
        if (book.usedOcr && book.isReadable) {
            Surface(
                color = MaterialTheme.colorScheme.tertiary,
                shape = RoundedCornerShape(topEnd = 8.dp),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                Text("OCR", style = MaterialTheme.typography.labelSmall, color = Color.White, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun OcrModeDialog(current: OcrMode, onSelect: (OcrMode) -> Unit, onDismiss: () -> Unit) {
    val options = listOf(
        OcrMode.AUTO to (R.string.ocr_auto to R.string.ocr_auto_desc),
        OcrMode.ALWAYS to (R.string.ocr_always to R.string.ocr_always_desc),
        OcrMode.NEVER to (R.string.ocr_never to R.string.ocr_never_desc),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.menu_ocr_mode)) },
        text = {
            Column {
                Text(stringResource(R.string.ocr_explain), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                for ((mode, labels) in options) {
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = mode == current, role = Role.RadioButton, onClick = { onSelect(mode) }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == current, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(labels.first), style = MaterialTheme.typography.titleSmall)
                            Text(stringResource(labels.second), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(RaptiIcons.Book, null) },
        title = { Text(stringResource(R.string.app_title)) },
        text = { Text(stringResource(R.string.about_body), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
