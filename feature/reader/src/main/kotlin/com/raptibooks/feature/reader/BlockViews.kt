package com.raptibooks.feature.reader

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
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
import com.raptibooks.core.model.ContentBlock
import com.raptibooks.core.model.HeadingBlock
import com.raptibooks.core.model.ImageBlock
import com.raptibooks.core.model.ImageKind
import com.raptibooks.core.model.InlineStyle
import com.raptibooks.core.model.ListItemBlock
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
    onImageClick: (path: String, caption: String?) -> Unit,
) {
    when (block) {
        is HeadingBlock -> HeadingView(block, style, highlight)
        is ParagraphBlock -> ParagraphView(block, style, highlight)
        is ListItemBlock -> ListItemView(block, style, highlight)
        is ImageBlock -> ImageView(block, style, onImageClick)
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
        Text(
            text = styled(block.text, emptyList(), highlight, style.colors),
            style = TextStyle(
                fontFamily = style.headingFont,
                fontWeight = if (block.level <= 3) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = size.sp,
                lineHeight = (size * 1.3f).sp,
                color = if (block.level == 1) style.colors.text else style.colors.text.copy(alpha = 0.92f),
                textDirection = TextDirection.Content,
            ),
        )
    }
}

@Composable
private fun ParagraphView(block: ParagraphBlock, style: ReaderStyle, highlight: String?) {
    val text = remember(block, highlight, style.colors) { styled(block.text, block.styles, highlight, style.colors) }
    val base = style.body
    val spacing = (style.fontSize.value * 0.75f).dp
    when (block.kind) {
        ParagraphKind.BODY -> Text(text, style = base, modifier = Modifier.fillMaxWidth().padding(bottom = spacing))
        ParagraphKind.CENTERED -> Text(
            text, style = base.copy(textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(vertical = spacing / 2).padding(bottom = spacing / 2),
        )
        ParagraphKind.QUOTE -> {
            val bar = style.colors.accent.copy(alpha = 0.6f)
            Text(
                text, style = base.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = spacing)
                    .drawBehind { drawLine(bar, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 3.dp.toPx()) }
                    .padding(start = 16.dp),
            )
        }
        ParagraphKind.CAPTION -> Text(
            text, style = base.copy(fontSize = (style.fontSize.value * 0.85f).sp, fontStyle = FontStyle.Italic, color = style.colors.secondary, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing),
        )
        ParagraphKind.NOTE -> Text(
            text, style = base.copy(fontSize = (style.fontSize.value * 0.82f).sp, color = style.colors.secondary, textAlign = TextAlign.Start),
            modifier = Modifier.fillMaxWidth().padding(bottom = spacing / 2),
        )
    }
}

@Composable
private fun ListItemView(block: ListItemBlock, style: ReaderStyle, highlight: String?) {
    val text = remember(block, highlight, style.colors) { styled(block.text, block.styles, highlight, style.colors) }
    val markerWidth = (style.fontSize.value * if (block.marker.length <= 1) 1.4f else 2.4f).dp
    Row(Modifier.fillMaxWidth().padding(start = (block.level * 20).dp, bottom = (style.fontSize.value * 0.4f).dp)) {
        Text(
            block.marker,
            style = style.body.copy(textAlign = TextAlign.End, color = style.colors.accent),
            modifier = Modifier.width(markerWidth).padding(end = 8.dp),
        )
        Text(text, style = style.body.copy(textAlign = TextAlign.Start), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ImageView(block: ImageBlock, style: ReaderStyle, onClick: (String, String?) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
        val density = LocalDensity.current
        // Figures rendered at ~3 px/pt; show small ones at their natural size, big ones full width.
        val natural = with(density) { (block.width / 2.2f).toDp() }
        val width: Dp = if (block.kind == ImageKind.PAGE) maxWidth else minOf(maxWidth, maxOf(natural, maxWidth * 0.6f))
        val widthPx = with(density) { width.toPx().roundToInt() }
        val image by rememberLocalImage(block.path, widthPx)
        val aspect = if (block.height > 0) block.width.toFloat() / block.height else 1.5f
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .width(width)
                    .aspectRatio(aspect.coerceIn(0.2f, 6f))
                    .clip(RoundedCornerShape(6.dp))
                    .background(style.colors.surface)
                    .clickable { onClick(block.path, block.caption) },
                contentAlignment = Alignment.Center,
            ) {
                val img = image
                if (img != null) {
                    Image(
                        img, contentDescription = block.caption, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio(aspect.coerceIn(0.2f, 6f)),
                        colorFilter = if (style.colors.isDark) DIM else null,
                    )
                } else {
                    CircularProgressIndicator(Modifier.size(24.dp), color = style.colors.accent, strokeWidth = 2.dp)
                }
            }
            block.caption?.let {
                Text(
                    it,
                    style = style.body.copy(
                        fontSize = (style.fontSize.value * 0.82f).sp, fontStyle = FontStyle.Italic,
                        color = style.colors.secondary, textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun TableView(block: TableBlock, style: ReaderStyle, onImageClick: (String, String?) -> Unit) {
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
            val widths = remember(block, style.fontSize) { columnWidths(block.rows, columns, style.fontSize.value) }
            val border = colors.secondary.copy(alpha = 0.35f)
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, border, RoundedCornerShape(8.dp))
                    .horizontalScroll(rememberScrollState()),
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
                                Text(
                                    row.getOrElse(c) { "" },
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
                                )
                            }
                        }
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
    return ReaderStyle(colors, body, heading, settings.fontSize.sp, settings.lineSpacing, settings.justify)
}
