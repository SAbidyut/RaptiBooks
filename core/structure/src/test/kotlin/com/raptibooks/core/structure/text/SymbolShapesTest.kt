package com.raptibooks.core.structure.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File

class SymbolShapesTest {

    private val fonts: List<Font> = listOf(
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSerif-Regular.ttf",
    ).map { File(it) }.filter { it.exists() }.map { Font.createFont(Font.TRUETYPE_FONT, it) }
        .ifEmpty { listOf(Font(Font.SANS_SERIF, Font.PLAIN, 1), Font(Font.SERIF, Font.PLAIN, 1)) }

    /** Renders [text] and classifies the glyph at [index] like the conversion pipeline does. */
    private fun classify(font: Font, text: String, index: Int, px: Float): Char? {
        val f = font.deriveFont(px)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().apply { this.font = f }
        val fm = probe.fontMetrics
        val w = fm.stringWidth(text) + (px * 0.6f).toInt()
        val h = (px * 1.5f).toInt()
        val baseline = px * 1.1f
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = f
        val x0 = px * 0.3f
        g.drawString(text, x0, baseline)
        val left = x0 + fm.stringWidth(text.substring(0, index))
        val right = left + fm.charWidth(text[index])
        val gray = IntArray(w * h) { i -> (img.getRGB(i % w, i / w) and 0xFF) }
        return SymbolShapes.classify(gray, w, h, left, right, baseline, px)
    }

    @Test
    fun recognisesCommonOperators() {
        for (font in fonts) for (px in listOf(24f, 40f, 64f)) {
            for (c in listOf('=', '+', '−', '×', '÷', '±', '→')) {
                assertEquals("${font.fontName} ${px}px '$c'", c, classify(font, c.toString(), 0, px))
            }
        }
    }

    @Test
    fun recognisesOperatorsBetweenLetters() {
        for (font in fonts) {
            assertEquals(font.fontName, '=', classify(font, "p = vi", 2, 40f))
            assertEquals(font.fontName, '×', classify(font, "2 × 10", 2, 40f))
            assertEquals(font.fontName, '+', classify(font, "a + b", 2, 40f))
        }
    }

    @Test
    fun leavesLettersAndDigitsUnknown() {
        for (font in fonts) for (c in "aAbgHkmxX0234578wQ") {
            assertNull("${font.fontName} '$c'", classify(font, c.toString(), 0, 40f))
        }
    }
}
