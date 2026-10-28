package com.raptibooks.feature.reader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.raptibooks.core.model.Note
import com.raptibooks.core.model.NoteKind
import com.raptibooks.core.ui.ReaderColors
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------------- pins in the page

private val TextPin = Color(0xFFF59E0B)
private val VoicePin = Color(0xFF00897B)

/** Width kept free at the right of a block that has notes: the pins sit there, beside the text. */
internal val NoteStrip = 26.dp
private val PinSize = 22.dp

/**
 * The pins of one block. Each pin sits in the free strip at the right of the text, level with
 * the place it belongs to, and an arrow runs from it to that place, so the text itself stays
 * clear. The layer covers the whole block (text area plus strip).
 */
@Composable
internal fun NotesLayer(notes: List<Note>, onOpen: (Note) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val pin = with(density) { PinSize.roundToPx() }
    val strip = with(density) { NoteStrip.roundToPx() }
    val gap = with(density) { 3.dp.roundToPx() }
    val line = with(density) { 1.4.dp.toPx() }
    val dash = with(density) { floatArrayOf(5.dp.toPx(), 4.dp.toPx()) }
    val head = with(density) { 9.dp.toPx() }
    Layout(
        content = { for (n in notes) NotePin(n, PinSize) { onOpen(n) } },
        modifier = modifier.drawBehind {
            val tops = pinTops(notes, size.height.roundToInt(), pin, gap)
            val textWidth = size.width - strip
            val startX = size.width - strip + (strip - pin) / 2f
            for ((i, n) in notes.withIndex()) {
                val color = (if (n.kind == NoteKind.VOICE) VoicePin else TextPin).copy(alpha = 0.85f)
                val from = Offset(startX, tops[i] + pin / 2f)
                val tip = Offset(n.x.coerceIn(0f, 1f) * textWidth, n.y.coerceIn(0f, 1f) * size.height)
                val away = from - tip
                val length = away.getDistance()
                if (length < 1f) continue
                val unit = away / length
                val base = tip + unit * minOf(head, length)
                drawLine(color, from, base, strokeWidth = line, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(dash))
                val side = Offset(-unit.y, unit.x) * (head * 0.45f)
                val arrow = Path().apply {
                    moveTo(tip.x, tip.y)
                    lineTo(base.x + side.x, base.y + side.y)
                    lineTo(base.x - side.x, base.y - side.y)
                    close()
                }
                drawPath(arrow, color)
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints.fixed(pin, pin)) }
        val tops = pinTops(notes, constraints.maxHeight, pin, gap)
        layout(constraints.maxWidth, constraints.maxHeight) {
            val x = constraints.maxWidth - strip + (strip - pin) / 2
            placeables.forEachIndexed { i, p -> p.place(x, tops[i]) }
        }
    }
}

/**
 * The pins of an original page, each standing just above and left of its place (fractions of
 * the page), with its point on the place, so the printed words stay readable.
 */
@Composable
internal fun PagePins(notes: List<Note>, onOpen: (Note) -> Unit, modifier: Modifier = Modifier) {
    val pin = with(LocalDensity.current) { PinSize.roundToPx() }
    Layout(content = { for (n in notes) NotePin(n, PinSize) { onOpen(n) } }, modifier = modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints.fixed(pin, pin)) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, p ->
                val x = (notes[i].x.coerceIn(0f, 1f) * constraints.maxWidth - pin * 0.85f).roundToInt()
                val y = (notes[i].y.coerceIn(0f, 1f) * constraints.maxHeight - pin * 0.85f).roundToInt()
                p.place(x.coerceIn(0, max(0, constraints.maxWidth - pin)), y.coerceIn(0, max(0, constraints.maxHeight - pin)))
            }
        }
    }
}

/** Top of each pin: level with its place, pushed down where two would overlap. */
private fun pinTops(notes: List<Note>, height: Int, pin: Int, gap: Int): IntArray {
    val tops = IntArray(notes.size)
    var free = 0
    for (i in notes.indices.sortedBy { notes[it].y }) {
        val wanted = (notes[i].y * height - pin / 2f).roundToInt().coerceIn(0, max(0, height - pin))
        tops[i] = max(wanted, free)
        free = tops[i] + pin + gap
    }
    return tops
}

@Composable
private fun NotePin(note: Note, size: Dp, onClick: () -> Unit) {
    val base = if (note.kind == NoteKind.VOICE) VoicePin else TextPin
    Box(
        Modifier
            .size(size)
            .shadow(4.dp, CircleShape)
            .background(Brush.verticalGradient(listOf(base.copy(alpha = 0.95f), base)), CircleShape)
            .border(1.2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NoteGlyph(note.kind, Color.White, size * 0.55f)
    }
}

/** A small drawing of a note (page with lines) or a microphone. */
@Composable
internal fun NoteGlyph(kind: NoteKind, color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width
        val stroke = Stroke(width = s * 0.09f, cap = StrokeCap.Round)
        if (kind == NoteKind.VOICE) {
            drawRoundRect(color, Offset(s * 0.35f, s * 0.04f), Size(s * 0.30f, s * 0.52f), CornerRadius(s * 0.15f))
            drawArc(color, 0f, 180f, false, Offset(s * 0.2f, s * 0.2f), Size(s * 0.6f, s * 0.5f), style = stroke)
            drawLine(color, Offset(s * 0.5f, s * 0.7f), Offset(s * 0.5f, s * 0.92f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
            drawLine(color, Offset(s * 0.32f, s * 0.94f), Offset(s * 0.68f, s * 0.94f), strokeWidth = s * 0.09f, cap = StrokeCap.Round)
        } else {
            drawRoundRect(color, Offset(s * 0.1f, s * 0.08f), Size(s * 0.8f, s * 0.84f), CornerRadius(s * 0.14f), style = stroke)
            for (i in 0..2) {
                val y = s * (0.32f + 0.18f * i)
                drawLine(color, Offset(s * 0.28f, y), Offset(s * (if (i == 2) 0.5f else 0.72f), y), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
            }
        }
    }
}

/** Shown while the reader is choosing where a note goes. */
@Composable
internal fun NoteHint(colors: ReaderColors, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    val glass = if (colors.isDark) Color(0xFF3A3A40) else Color.White
    Row(
        modifier
            .statusBarsPadding()
            .padding(top = 12.dp)
            .shadow(10.dp, shape)
            .background(glass.copy(alpha = 0.88f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.5f), shape)
            .padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.note_tap_hint), color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(6.dp))
        Icon(
            Icons.Default.Close, stringResource(R.string.note_cancel),
            tint = colors.secondary,
            modifier = Modifier.size(40.dp).clickable(onClick = onCancel).padding(10.dp),
        )
    }
}

// ---------------------------------------------------------------------- dialogs

/** After choosing a place: a voice note or a written note? */
@Composable
internal fun NoteChooserDialog(onVoice: () -> Unit, onText: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_choose_title)) },
        text = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Choice(NoteKind.VOICE, R.string.note_choose_voice, onVoice, Modifier.weight(1f))
                Choice(NoteKind.TEXT, R.string.note_choose_text, onText, Modifier.weight(1f))
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.note_cancel)) } },
    )
}

@Composable
private fun Choice(kind: NoteKind, label: Int, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        NoteGlyph(kind, MaterialTheme.colorScheme.onSurfaceVariant, 30.dp)
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
    }
}

/** Writes a new note, or edits (and can delete) an existing one. The keyboard opens at once. */
@Composable
internal fun NoteEditorDialog(initial: String, onSave: (String) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focus.requestFocus() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.note_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.note_text_hint)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp).focusRequester(focus),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text(stringResource(R.string.note_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.note_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.note_cancel)) }
            }
        },
    )
}

/** Records a voice note; [onSaved] gets the file and its length once the recording is stopped. */
@Composable
internal fun VoiceRecordDialog(newFile: () -> File, onSaved: (File, Long) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val recorder = remember { VoiceRecorder(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        if (recorder.start(newFile())) {
            recording = true
            val startedAt = SystemClock.elapsedRealtime()
            while (true) {
                elapsed = SystemClock.elapsedRealtime() - startedAt
                delay(200)
            }
        } else {
            failed = true
        }
    }
    // Leaving without saving throws the recording away.
    DisposableEffect(Unit) { onDispose { recorder.cancel() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    denied -> Text(stringResource(R.string.voice_no_permission), color = MaterialTheme.colorScheme.error)
                    failed -> Text(stringResource(R.string.voice_failed), color = MaterialTheme.colorScheme.error)
                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(12.dp).background(if (recording) Color(0xFFE53935) else Color.Gray, CircleShape))
                            Text(stringResource(R.string.voice_recording))
                        }
                        Text(formatTime(elapsed), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = recording && !failed,
                onClick = {
                    val file = recorder.stop()
                    recording = false
                    if (file != null) onSaved(file, elapsed) else failed = true
                },
            ) { Text(stringResource(R.string.voice_stop_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.note_cancel)) } },
    )
}

/** Plays a saved voice note. */
@Composable
internal fun VoiceNoteDialog(note: Note, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val player = remember { VoicePlayer() }
    var playing by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                NoteGlyph(NoteKind.VOICE, MaterialTheme.colorScheme.primary, 36.dp)
                Spacer(Modifier.size(8.dp))
                if (missing) {
                    Text(stringResource(R.string.voice_failed), color = MaterialTheme.colorScheme.error)
                } else {
                    Text(formatTime(note.durationMs), style = MaterialTheme.typography.headlineSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (playing) {
                        player.stop()
                        playing = false
                    } else {
                        val path = note.audioPath
                        if (path != null && player.play(path) { playing = false }) playing = true else missing = true
                    }
                },
            ) { Text(stringResource(if (playing) R.string.voice_stop else R.string.voice_play)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.note_delete), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
    )
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

// ---------------------------------------------------------------------- audio

/** Records the microphone to an AAC (.m4a) file. */
internal class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start(target: File): Boolean = try {
        target.parentFile?.mkdirs()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = r
        file = target
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(44_100)
        r.setAudioEncodingBitRate(64_000)
        r.setOutputFile(target.absolutePath)
        r.prepare()
        r.start()
        true
    } catch (e: Exception) {
        release()
        target.delete()
        false
    }

    /** Stops and returns the recorded file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        recorder = null
        file = null
        return try {
            r.stop()
            r.release()
            f
        } catch (e: Exception) {
            runCatching { r.release() }
            f?.delete()
            null
        }
    }

    /** Stops and deletes the recording (nothing happens if it was already stopped). */
    fun cancel() {
        val f = file
        release()
        f?.delete()
    }

    private fun release() {
        val r = recorder ?: return
        recorder = null
        file = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }
}

internal class VoicePlayer {
    private var player: MediaPlayer? = null

    /** Starts playing [path]; [onDone] runs when it ends. False if the file cannot be played. */
    fun play(path: String, onDone: () -> Unit): Boolean {
        release()
        return try {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.setOnCompletionListener { onDone() }
            p.prepare()
            p.start()
            player = p
            true
        } catch (e: Exception) {
            release()
            false
        }
    }

    fun stop() = release()

    fun release() {
        val p = player ?: return
        player = null
        runCatching { p.stop() }
        runCatching { p.release() }
    }
}
