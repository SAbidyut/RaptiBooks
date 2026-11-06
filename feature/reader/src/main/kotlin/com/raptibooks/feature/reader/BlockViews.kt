package com.raptibooks.feature.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import com.raptibooks.core.model.InlinePicture
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.raptibooks.core.model.BoxSpan
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
import com.raptibooks.core.model.MarkKey
import com.raptibooks.core.model.ParagraphBlock
import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.model.SeparatorBlock
import com.raptibooks.core.model.TableBlock
import com.raptibooks.core.ui.ReaderColors
import com.raptibooks.core.ui.RaptiFonts
import com.raptibooks.core.ui.rememberLocalImage
import kotlin.math.roundToInt

/** Typography of the reading surface, derived from [com.raptibooks.core.model.ReaderSettings]. */
@Immutable
data class ReaderStyle(
    val colors: ReaderColors,
    val bodyFont: FontFamily,
    val headingFont: FontFamily,
    val fontSize: TextUnit,
    val lineSpacing: Float,
    val justify: Boolean,
    /** Side margin of the reading column; figures may extend into it. */
    val margin: Dp = 0.dp,
) {
    val body: TextStyle
        get() = TextStyle(
            fontFamily = bodyFont,
            fontSize = fontSize,
            lineHeight = (fontSize.value * lineSpacing).sp,
            color = colors.text,
            textAlign = if (justify) TextAlign.Justify else TextAlign.Start,
            hyphens = Hyphens.Auto,
            lineBreak = LineBreak.Paragraph,
            textDirection = TextDirection.Content,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
        )
}

@Composable
fun BlockView(
    block: ContentBlock,
    style: ReaderStyle,
    highlight: String?,
    onContentsClick: (title: String) -> Unit = {},
    onImageClick: (path: String, caption: String?, ink: Boolean, block: Int) -> Unit,
) {
    when (block) {
        is HeadingBlock -> HeadingView(block, style, highlight)
        is ParagraphBlock -> when (block.kind) {
            ParagraphKind.TOC -> ContentsEntryView(block, style, highlight, onContentsClick)
            ParagraphKind.OPTIONS -> OptionsView(block, style, highlight)
            else -> ParagraphView(block, style, highlight)
        }
        is ListItemBlock -> ListItemView(block, style, highlight)
        is ImageBlock -> if (block.kind == ImageKind.EQUATION) EquationView(block, style, onImageClick) else ImageView(block, style, onImageClick)
        is TableBlock -> TableView(block, style, onImageClick)
        is SeparatorBlock -> Text(
            "⁂", style = style.body.copy(textAlign = TextAlign.Center, color = style.colors.secondary),
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
    }
}

/** Placeholder with a plausible height while a chunk loads. */
@Composable
fun BlockPlaceholder(style: ReaderStyle) {
    Spacer(Modifier.fillMaxWidth().height((style.fontSize.value * style.lineSpacing * 4).dp))
}

@Composable
private fun HeadingView(block: HeadingBlock, style: ReaderStyle, highlight: String?) {
    val scale = when (block.level) {
        1 -> 1.55f
        2 -> 1.3f
        3 -> 1.15f
        else -> 1.04f
    }
    val size = style.fontSize.value * scale
    Column(Modifier.fillMaxWidth().padding(top = if (block.level == 1) 40.dp else 22.dp, bottom = if (block.level == 1) 18.dp else 10.dp)) {
        if (block.level == 1) {
            Box(Modifier.width(40.dp).height(3.dp).background(style.colors.accent, RoundedCornerShape(2.dp)))
            Spacer(Modifier.height(14.dp))
        }
        MarkableText(
            blockIndex = block.index,
            text = styled(block.text, emptyList(), highlight, style.colors),
            style = TextStyle(
                fontFamily = style.headingFont,
                fontWeight = if (block.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = size.sp,
                lineHeight = (size * 1.3f).sp,
                color = LocalBoxAccent.current?.takeIf { block.level >= 3 }
                    ?: if (block.level == 1) style.colors.text else style.colors.text.copy(alpha = 0.92f),
                textDirection = TextDirection.Content,
            ),
        )
    }
}

@Composable
private fun ParagraphView(block: ParagraphBlock, style: ReaderStyle, highlight: String?) {
    val text = remember(block, highlight, style.colors) { styled(block.text, block.styles, highlight, style.colors).withPictures(block.pictures) }
    val pictures = pictureContent(block.pictures, style)
    val base = style.body
    val spacing = (style.fontSize.value * 0.75f).dp
    when (block.kind) {
        ParagraphKind.BODY -> MarkableText(
            block.index, text, style = base, modifier = Modifier.fillMaxWidth().padding(bottom = spacing), inlineContent = pictures,
        )
        ParagraphKind.CENTERED -> MarkableText(
            block.index, text, style = base.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing / 2).padding(bottom = spacing / 2), inlineContent = pictures,
        )
        ParagraphKind.QUOTE -> {
            val bar = style.colors.accent.copy(alpha = 0.6f)
            MarkableText(
                block.index, text, style = base.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = spacing)
                    .drawBehind { drawLine(bar, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 3.dp.toPx()) }
                    .padding(start = 16.dp),
                inlineContent = pictures,
            )
        }
        ParagraphKind.CAPTION -> MarkableText(
            block.index, text, style = base.copy(fontSize = (style.fontSize.value * 0.85f).sp, fontStyle = FontStyle.Italic, color = style.colors.secondary, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing),
        )
        ParagraphKind.FORMULA -> FormulaView(text, style)
        ParagraphKind.CODE -> CodeView(block.index, text, style)
        ParagraphKind.NOTE, ParagraphKind.TOC, ParagraphKind.OPTIONS -> MarkableText(
            block.index, text, style = base.copy(fontSize = (style.fontSize.value * 0.82f).sp, color = style.colors.secondary, textAlign = TextAlign.Start),
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing / 2), inlineContent = pictures,
        )
    }
}

/**
 * A formula or a step of a calculation printed on its own line: centred, a little larger than
 * the text, never broken across lines (a long one scrolls sideways).
 */
@Composable
private fun FormulaView(text: AnnotatedString, style: ReaderStyle) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = (style.fontSize.value * 0.3f).dp)) {
        val width = maxWidth
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(
                text,
                style = style.body.copy(
                    fontSize = (style.fontSize.value * 1.05f).sp,
                    textAlign = TextAlign.Center,
                    hyphens = Hyphens.None,
                ),
                softWrap = false,
                modifier = Modifier.widthIn(min = width).padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * Program code or terminal output: monospace on a tinted panel, lines and indentation as
 * printed, never wrapped (a long line scrolls sideways).
 */
@Composable
private fun CodeView(blockIndex: Int, text: AnnotatedString, style: ReaderStyle) {
    val size = style.fontSize.value * 0.8f
    Box(
        Modifier
            .fillMaxWidth()
            .padding(bottom = (style.fontSize.value * 0.75f).dp)
            .clip(RoundedCornerShape(8.dp))
            .background(style.colors.text.copy(alpha = 0.07f))
            .horizontalScroll(rememberScrollState()),
    ) {
        MarkableText(
            blockIndex, text,
            style = style.body.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = size.sp,
                lineHeight = (size * 1.5f).sp,
                textAlign = TextAlign.Start,
                hyphens = Hyphens.None,
            ),
            softWrap = false,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/** Accent colour of the callout box the current block is in (for its headings). */
internal val LocalBoxAccent = compositionLocalOf<Color?> { null }

/**
 * Draws a block as part of a callout card — a worked example, definition, note or warning as
 * the book printed it in a tinted box: the box's colour as a bar on the left and a light tint
 * behind, rounded at the first and last block of the box.
 */
@Composable
fun CalloutBox(span: BoxSpan?, index: Int, style: ReaderStyle, content: @Composable () -> Unit) {
    if (span == null) {
        content()
        return
    }
    val colors = style.colors
    val accent = remember(span.accent, colors) { boxAccent(span.accent, colors) }
    val first = index == span.startIndex
    val last = index == span.endIndex
    val r = 10.dp
    val shape = RoundedCornerShape(
        topStart = if (first) r else 0.dp, topEnd = if (first) r else 0.dp,
        bottomStart = if (last) r else 0.dp, bottomEnd = if (last) r else 0.dp,
    )
    val tint = accent.copy(alpha = if (colors.isDark) 0.16f else 0.075f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = if (first) 8.dp else 0.dp, bottom = if (last) 14.dp else 0.dp)
            .clip(shape)
            .background(tint)
            .drawBehind { drawRect(accent, size = Size(4.dp.toPx(), size.height)) }
            .padding(start = 16.dp, end = 12.dp, top = if (first) 10.dp else 0.dp, bottom = if (last) 6.dp else 0.dp),
    ) {
        CompositionLocalProvider(LocalBoxAccent provides accent) { content() }
    }
}

/** The box's printed accent colour, adjusted to stay readable on the current theme. */
private fun boxAccent(rgb: Int, colors: ReaderColors): Color {
    val c = Color(0xFF000000.toInt() or rgb)
    return when {
        colors.isDark -> lerp(c, Color.White, 0.45f)
        c.luminance() > 0.45f -> lerp(c, Color.Black, 0.35f)
        else -> c
    }
}

/** Answer options of a multiple-choice question: short ones two per row (as printed), long ones one per row. */
@Composable
private fun OptionsView(block: ParagraphBlock, style: ReaderStyle, highlight: String?) {
    val options = remember(block.text) {
        block.text.split('\n').map { line ->
            val tab = line.indexOf('\t')
            if (tab >= 0) line.substring(0, tab) to line.substring(tab + 1) else "" to line
        }
    }
    val size = (style.fontSize.value * 0.95f).sp
    val twoColumns = options.size % 2 == 0 && options.all { it.second.length <= 16 }
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, bottom = (style.fontSize.value * 0.7f).dp)) {
        for ((rowNumber, row) in options.chunked(if (twoColumns) 2 else 1).withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                for ((column, option) in row.withIndex()) {
                    val (marker, text) = option
                    val optionNumber = rowNumber * (if (twoColumns) 2 else 1) + column
                    Row(Modifier.weight(1f)) {
                        Text(
                            marker,
                            style = style.body.copy(fontSize = size, color = style.colors.accent, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start),
                            modifier = Modifier.padding(end = 6.dp),
                        )
                        MarkableText(
                            block.index,
                            remember(text, highlight, style.colors) { styled(text, emptyList(), highlight, style.colors) },
                            style = style.body.copy(fontSize = size, textAlign = TextAlign.Start),
                            modifier = Modifier.weight(1f, fill = false),
                            part = MarkKey.option(optionNumber),
                        )
                    }
                }
            }
        }
    }
}

/** A line of the book's printed contents: title and page number, tap to go to the chapter. */
@Composable
private fun ContentsEntryView(block: ParagraphBlock, style: ReaderStyle, highlight: String?, onClick: (String) -> Unit) {
    val tab = block.text.indexOf('\t')
    val title = if (tab >= 0) block.text.substring(0, tab) else block.text
    val page = if (tab >= 0) block.text.substring(tab + 1) else ""
    val main = block.styles.any { it.bold }
    val text = remember(block, highlight, style.colors) { styled(title, block.styles, highlight, style.colors) }
    val size = style.fontSize.value * if (main) 1f else 0.94f
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { onClick(title) }
                .padding(start = if (main) 2.dp else 16.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text,
                style = style.body.copy(fontSize = size.sp, lineHeight = (size * 1.4f).sp, textAlign = TextAlign.Start),
                modifier = Modifier.weight(1f),
            )
            Text(
                page,
                style = style.body.copy(fontSize = (size * 0.86f).sp, color = style.colors.secondary, textAlign = TextAlign.End),
                modifier = Modifier.padding(start = 14.dp, top = 1.dp),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = style.colors.secondary.copy(alpha = 0.18f))
    }
}

@Composable
private fun ListItemView(block: ListItemBlock, style: ReaderStyle, highlight: String?) {
    val text = remember(block, highlight, style.colors) { styled(block.text, block.styles, highlight, style.colors).withPictures(block.pictures) }
    val pictures = pictureContent(block.pictures, style)
    val markerWidth = (style.fontSize.value * if (block.marker.length <= 1) 1.4f else 2.4f).dp
    Row(Modifier.fillMaxWidth().padding(start = (block.level * 20).dp, bottom = (style.fontSize.value * 0.4f).dp)) {
        Text(
            block.marker,
            style = style.body.copy(textAlign = TextAlign.End, color = style.colors.accent),
            modifier = Modifier.width(markerWidth).padding(end = 8.dp),
        )
        MarkableText(block.index, text, style = style.body.copy(textAlign = TextAlign.Start), modifier = Modifier.weight(1f), inlineContent = pictures)
    }
}

private const val INLINE_CONTENT_TAG = "androidx.compose.foundation.text.inlineContent"

/** Marks where the block's word pictures stand in its text (see [InlinePicture]). */
internal fun AnnotatedString.withPictures(pictures: List<InlinePicture>): AnnotatedString {
    if (pictures.isEmpty()) return this
    val base = this
    return buildAnnotatedString {
        append(base)
        for ((k, p) in pictures.withIndex()) {
            if (p.start in base.text.indices) addStringAnnotation(INLINE_CONTENT_TAG, "pic$k", p.start, p.start + 1)
        }
    }
}

/**
 * Words shown as printed (Arabic the PDF's text layer could not hold), set in the line like
 * words, in the colour of the text.
 */
@Composable
internal fun pictureContent(pictures: List<InlinePicture>, style: ReaderStyle): Map<String, InlineTextContent> {
    if (pictures.isEmpty()) return emptyMap()
    val ink = style.colors.text
    val fontPx = with(LocalDensity.current) { style.fontSize.toPx() }
    return remember(pictures, ink, fontPx) {
        pictures.withIndex().associate { (k, p) ->
            "pic$k" to InlineTextContent(Placeholder(p.width.em, p.height.em, PlaceholderVerticalAlign.TextCenter)) {
                val image by rememberLocalImage(p.path, (p.width * fontPx * 1.5f).roundToInt().coerceAtLeast(1))
                image?.let { Image(it, contentDescription = null, colorFilter = ColorFilter.tint(ink), modifier = Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun ImageView(block: ImageBlock, style: ReaderStyle, onClick: (String, String?, Boolean, Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        val density = LocalDensity.current
        val screenHeight = LocalConfiguration.current.screenHeightDp.dp
        val aspect = (if (block.height > 0) block.width.toFloat() / block.height else 1.5f).coerceIn(0.15f, 10f)
        // Diagrams use the full screen width (into the page margins); photos stay in the text column.
        val inBox = LocalBoxAccent.current != null
        val bleed = if (block.kind == ImageKind.PHOTO || inBox) 0.dp else (style.margin - 8.dp).coerceAtLeast(0.dp)
        val available = maxWidth + bleed * 2
        val fit = remember(block, available, style.fontSize, screenHeight) {
            figureSize(block, available, aspect, style.fontSize.value, screenHeight)
        }
        val widthPx = with(density) { fit.width.toPx().roundToInt() }
        val image by rememberLocalImage(block.path, widthPx)
        Column(Modifier.bleed(bleed).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val picture = @Composable {
                Box(
                    Modifier
                        .width(fit.width)
                        .aspectRatio(aspect)
                        .clip(RoundedCornerShape(6.dp))
                        .background(style.colors.surface)
                        .clickable { onClick(block.path, block.caption, false, block.index) },
                    contentAlignment = Alignment.Center,
                ) {
                    val img = image
                    if (img != null) {
                        Image(
                            img, contentDescription = block.caption, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                            colorFilter = if (style.colors.isDark) DIM else null,
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp), color = style.colors.accent, strokeWidth = 2.dp)
                    }
                    InkLayer(block.index)
                }
            }
            if (fit.scroll) {
                // Too wide to read at screen width: show it at a readable size, scrollable sideways.
                Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) { picture() }
                Text(
                    stringResource(R.string.figure_scroll_hint),
                    style = style.body.copy(fontSize = 12.sp, color = style.colors.secondary, textAlign = TextAlign.Center),
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                picture()
            }
            block.caption?.let {
                Text(
                    it,
                    style = style.body.copy(
                        fontSize = (style.fontSize.value * 0.82f).sp, fontStyle = FontStyle.Italic,
                        color = style.colors.secondary, textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp + bleed, end = 8.dp + bleed),
                )
            }
        }
    }
}

/**
 * A display equation, drawn from its ink mask in the text colour so it reads like the text
 * around it: its symbols are shown at the size of the body text. A formula too wide for the
 * screen keeps a readable size and scrolls sideways. The equation number sits at the right.
 */
@Composable
private fun EquationView(block: ImageBlock, style: ReaderStyle, onClick: (String, String?, Boolean, Int) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = (style.fontSize.value * 0.5f).dp)) {
        val density = LocalDensity.current
        val number = block.caption
        val numberWidth = if (number != null) (style.fontSize.value * 0.82f * (number.length * 0.6f) + 12f).dp else 0.dp
        val available = maxWidth - numberWidth
        val aspect = (if (block.height > 0) block.width.toFloat() / block.height else 4f).coerceIn(0.2f, 40f)
        // Size in the book (pt) scaled so that the formula's text matches the reader's text size.
        val natural = if (block.sourceWidth > 0f && block.textSize > 0f) {
            (block.sourceWidth * style.fontSize.value / block.textSize).dp
        } else {
            (block.width / 3f).dp
        }
        val scroll = natural * 0.8f > available
        val width = when {
            !scroll -> minOf(natural, available)
            else -> natural * 0.85f
        }
        val widthPx = with(density) { width.toPx().roundToInt() }
        val image by rememberLocalImage(block.path, widthPx)
        val ink = style.colors.text
        val picture = @Composable {
            Box(
                Modifier.width(width).aspectRatio(aspect).clickable { onClick(block.path, number, true, block.index) },
                contentAlignment = Alignment.Center,
            ) {
                val img = image
                if (img != null) {
                    Image(
                        img, contentDescription = block.altText, contentScale = ContentScale.Fit,
                        colorFilter = ColorFilter.tint(ink), modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                    )
                }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (scroll) {
                    Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) { picture() }
                } else {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { picture() }
                }
                if (number != null) {
                    Text(
                        number,
                        style = style.body.copy(fontSize = (style.fontSize.value * 0.82f).sp, color = style.colors.secondary, textAlign = TextAlign.End),
                        modifier = Modifier.width(numberWidth),
                        maxLines = 1,
                    )
                }
            }
            if (scroll) {
                Text(
                    stringResource(R.string.equation_scroll_hint),
                    style = style.body.copy(fontSize = 12.sp, color = style.colors.secondary, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            }
        }
    }
}

private class FigureSize(val width: Dp, val scroll: Boolean)

/**
 * Display width of a figure. When the size of its printed labels is known, the figure is shown
 * so that they stay readable next to the body text; a figure that would need to be wider than
 * the screen for that scrolls sideways instead of shrinking its text to unreadable sizes.
 */
private fun figureSize(block: ImageBlock, available: Dp, aspect: Float, fontSize: Float, screenHeight: Dp): FigureSize {
    if (block.kind == ImageKind.PAGE) return FigureSize(available, false)
    var width: Dp
    var scroll = false
    if (block.sourceWidth > 0f && block.textSize > 0f) {
        val pt = block.sourceWidth
        val comfortable = (pt * (fontSize * 0.72f / block.textSize).coerceIn(1f, 3f)).dp
        val readable = (pt * fontSize * 0.5f / block.textSize).dp
        when {
            comfortable <= available -> width = minOf(available, maxOf(comfortable, available * 0.7f))
            readable <= available -> width = available
            else -> {
                width = minOf(readable, available * 2.4f)
                scroll = true
            }
        }
    } else {
        // Older books / photos: rendered at ~3 px/pt; small ones at natural size, big ones full width.
        width = minOf(available, maxOf((block.width / 2.2f).dp, available * 0.6f))
    }
    // Keep tall figures within one screen so they can be seen whole.
    val maxHeight = screenHeight * 0.8f
    if (!scroll && width / aspect > maxHeight) width = maxOf(maxHeight * aspect, available * 0.5f)
    return FigureSize(width, scroll)
}

/** Lets content extend [extra] beyond both sides of the parent (into the page margins). */
private fun Modifier.bleed(extra: Dp): Modifier = if (extra <= 0.dp) this else layout { measurable, constraints ->
    val e = extra.roundToPx()
    val w = constraints.maxWidth + 2 * e
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(constraints.maxWidth, placeable.height) { placeable.place(-e, 0) }
}

@Composable
private fun TableView(block: TableBlock, style: ReaderStyle, onImageClick: (String, String?, Boolean, Int) -> Unit) {
    var printed by rememberSaveable(block.index) { mutableStateOf(false) }
    val colors = style.colors
    val cellSize = (style.fontSize.value * 0.82f).sp
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        block.caption?.let {
            Text(
                it, style = style.body.copy(fontSize = cellSize, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start),
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        val path = block.imagePath
        if (printed && path != null) {
            ImageView(ImageBlock(block.index, block.pageIndex, path, block.imageWidth, block.imageHeight, null, ImageKind.DIAGRAM), style, onImageClick)
        } else {
            val columns = block.rows.maxOfOrNull { it.size } ?: 0
            val natural = remember(block, style.fontSize) { columnWidths(block.rows, columns, style.fontSize.value) }
            val border = colors.secondary.copy(alpha = 0.35f)
            val scrollState = rememberScrollState()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
            val total = natural.fold(0.dp) { a, b -> a + b }
            val wide = total > maxWidth
            // Narrow tables use the whole width; wide ones keep readable columns and scroll.
            val widths = if (wide || total <= 0.dp) natural else natural.map { it * (maxWidth / total) }
            Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, border, RoundedCornerShape(8.dp))
                    .then(
                        if (wide && scrollState.canScrollForward) {
                            Modifier.drawWithContent {
                                drawContent()
                                val fade = 28.dp.toPx()
                                drawRect(
                                    Brush.horizontalGradient(listOf(Color.Transparent, colors.background), startX = size.width - fade, endX = size.width),
                                    topLeft = Offset(size.width - fade, 0f),
                                )
                            }
                        } else {
                            Modifier
                        },
                    )
                    .horizontalScroll(scrollState),
            ) {
                Column {
                    block.rows.forEachIndexed { r, row ->
                        val header = r < block.headerRows
                        Row(
                            Modifier
                                .height(IntrinsicSize.Min)
                                .background(
                                    when {
                                        header -> colors.accent.copy(alpha = 0.14f)
                                        r % 2 == 1 -> colors.surface.copy(alpha = 0.6f)
                                        else -> colors.background
                                    },
                                ),
                        ) {
                            for (c in 0 until columns) {
                                MarkableText(
                                    block.index,
                                    AnnotatedString(row.getOrElse(c) { "" }),
                                    style = TextStyle(
                                        fontFamily = style.bodyFont,
                                        fontSize = cellSize,
                                        lineHeight = (cellSize.value * 1.4f).sp,
                                        color = colors.text,
                                        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
                                        textDirection = TextDirection.Content,
                                    ),
                                    modifier = Modifier
                                        .width(widths[c])
                                        .fillMaxHeight()
                                        .border(0.5.dp, border)
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    part = MarkKey.cell(r, c),
                                )
                            }
                        }
                    }
                }
            }
            if (wide) {
                Text(
                    stringResource(R.string.table_scroll_hint),
                    style = style.body.copy(fontSize = 12.sp, color = style.colors.secondary, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            }
            }
        }
        if (path != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { printed = !printed }) {
                    Text(
                        stringResource(if (printed) R.string.table_show_text else R.string.table_show_original),
                        color = colors.accent, fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

private fun columnWidths(rows: List<List<String>>, columns: Int, fontSize: Float): List<Dp> =
    List(columns) { c ->
        val longest = rows.maxOfOrNull { row -> row.getOrNull(c)?.length ?: 0 } ?: 0
        val longestWord = rows.maxOfOrNull { row -> row.getOrNull(c)?.split(' ')?.maxOfOrNull { it.length } ?: 0 } ?: 0
        val chars = maxOf(minOf(longest, 28), longestWord)
        (chars * fontSize * 0.52f + 24f).coerceIn(64f, 260f).dp
    }

private val DIM = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.85f, 0f, 0f, 0f, 0f,
            0f, 0.85f, 0f, 0f, 0f,
            0f, 0f, 0.85f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/** Builds the styled text of a block: bold/italic/super/subscript ranges and search highlights. */
internal fun styled(text: String, styles: List<InlineStyle>, highlight: String?, colors: ReaderColors): AnnotatedString =
    buildAnnotatedString {
        append(text)
        for (s in styles) {
            val start = s.start.coerceIn(0, text.length)
            val end = s.end.coerceIn(start, text.length)
            if (end <= start) continue
            addStyle(
                SpanStyle(
                    fontWeight = if (s.bold) FontWeight.Bold else null,
                    fontStyle = if (s.italic) FontStyle.Italic else null,
                    baselineShift = when {
                        s.superscript -> BaselineShift.Superscript
                        s.subscript -> BaselineShift.Subscript
                        else -> null
                    },
                    fontSize = if (s.superscript || s.subscript) 0.72.em else TextUnit.Unspecified,
                ),
                start, end,
            )
        }
        if (!highlight.isNullOrBlank()) {
            var i = text.indexOf(highlight, ignoreCase = true)
            while (i >= 0) {
                addStyle(SpanStyle(background = colors.highlight), i, i + highlight.length)
                i = text.indexOf(highlight, i + highlight.length, ignoreCase = true)
            }
        }
    }

internal fun readerStyle(colors: ReaderColors, settings: com.raptibooks.core.model.ReaderSettings): ReaderStyle {
    val body = RaptiFonts.forReader(settings.font)
    val heading = if (settings.font == com.raptibooks.core.model.ReaderFont.SYSTEM_SERIF) FontFamily.Serif else RaptiFonts.sans
    return ReaderStyle(colors, body, heading, settings.fontSize.sp, settings.lineSpacing, settings.justify, settings.margin.dp)
}
