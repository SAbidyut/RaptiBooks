package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import com.raptibooks.core.structure.text.OcrWords
import com.raptibooks.core.structure.text.TextQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pages read by the OCR (scanned NCTB textbooks) and Bangla text layers that need it. */
class OcrPageTest {

    private fun ocrWord(text: String, left: Float, right: Float, baseline: Float, size: Float = 12f, bold: Boolean = false) =
        TextFragment(text, left, baseline - size, right, baseline + size * 0.2f, baseline, size, TextFragment.OCR_FONT, bold, spaceWidth = size * 0.3f)

    /** A line of OCR words (no space glyphs), each [step] wide. */
    private fun ocrLine(text: String, x: Float, baseline: Float, size: Float = 12f, bold: Boolean = false) =
        words(text, x, baseline, size, bold, font = TextFragment.OCR_FONT).filter { !it.isSpace }

    @Test
    fun wordsTheOcrReadArePartedEvenWhenTheirBoxesOverlap() {
        // Tesseract's box of a Bangla word often reaches into the next one
        val line = LineBuilder().build(
            listOf(
                ocrWord("সেটগুলো", 385.7f, 468.7f, 448f),
                ocrWord("গঠন", 428.2f, 445.4f, 448f),
                ocrWord("করা", 447f, 462f, 448f),
            ),
        ).single()
        assertEquals("সেটগুলো গঠন করা", line.text)
    }

    @Test
    fun banglaWhoseFontMapsTheWrongLettersIsReadByTheOcr() {
        fun page(text: String) = RawPage(0, 450f, 790f, words(text, 40f, 100f, font = "Kalpurush"))
        // a Word document saved with Kalpurush: "নিচের কোন বানানটি শুদ্ধ? বাংলা ভাষার..."
        val garbled = "বননরে কক্ান িানানবি শুদ্ধ? িাাংলা ভাষার আবে বনশেবন কক্ান িাক্যবি শুদ্ধ? ".repeat(4)
        assertTrue(TextQuality.banglaMisorder(garbled) > 0.1f)
        assertEquals(OcrDecision.Reason.BROKEN_ENCODING, OcrDecision.evaluate(page(garbled), 400, 400))
        // right Bangla, and Bangla in visual order (vowel signs before their consonant), are kept
        val right = "নিচের কোন বানানটি শুদ্ধ? বাংলা ভাষার আদি নিদর্শন কোন বাক্যটি শুদ্ধ? ".repeat(4)
        assertEquals(0f, TextQuality.banglaMisorder(right))
        assertEquals(OcrDecision.Reason.NONE, OcrDecision.evaluate(page(right), 400, 400))
        val visual = "িনেচর েকান বানানিট শুদ্ধ? বাংলা ভাষার আিদ িনদর্শন েকান বাক্যিট শুদ্ধ? ".repeat(4)
        assertEquals(0f, TextQuality.banglaMisorder(visual))
    }

    @Test
    fun aSymbolTheTextLayerHasRightIsKeptOverTheOcrsGuess() {
        val ocr = RawPage(
            0, 450f, 790f,
            listOf(ocrWord("শুদ্ধ?", 100f, 130f, 100f), ocrWord("V", 136f, 146f, 100f), ocrWord("মনীষী", 150f, 185f, 100f)),
            fromOcr = true,
        )
        val layer = RawPage(0, 450f, 790f, listOf(TextFragment("✔", 137f, 90f, 145f, 101f, 100f, 11f, "Wingdings")))
        val line = LineBuilder().build(OcrWords.keepSymbols(ocr, layer).fragments).single()
        assertEquals("শুদ্ধ? ✔ মনীষী", line.text)
    }

    private fun structure(frags: List<TextFragment>): List<StructuredElement> {
        val layout = PageAnalyzer(12f).analyze(RawPage(0, 522f, 683f, frags, fromOcr = true))
        val profile = DocumentStats().apply { add(layout) }.profile()
        return PageStructurer(profile).structure(layout)
    }

    @Test
    fun aBoldLineTheOcrReadIsAHeading() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 3) frags += ocrLine("যে সেটের উপাদান সংখ্যা গণনা করে নির্ধারণ করা যায় তাকে সসীম সেট বলে $k", 54f, 100f + k * 20f)
        // a bold heading, its estimated size a little smaller than the text's, set close to it
        frags += ocrLine("সার্বিক সেট (Universal Set)", 52f, 178f, size = 11.8f, bold = true)
        for (k in 0 until 3) frags += ocrLine("আলোচনায় সংশ্লিষ্ট সকল সেট একটি নির্দিষ্ট সেটের উপসেট হলে তাকে সার্বিক সেট বলে $k", 56f, 196f + k * 20f)
        val headings = structure(frags).filterIsInstance<StructuredElement.Heading>()
        assertEquals(listOf("সার্বিক সেট (Universal Set)"), headings.map { it.text })
    }

    @Test
    fun aLongSentenceTheOcrReadTallIsNoHeading() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 4) frags += ocrLine("যে সেটের উপাদান সংখ্যা গণনা করে শেষ করা যায় না তাকে অসীম সেট বলে $k", 54f, 100f + k * 20f)
        // (a stacked fraction in it made its box tall)
        frags += ocrLine("বাস্তব সংখ্যার সেট R ইত্যাদি অসীম সেট।", 54f, 196f, size = 19.8f)
        for (k in 0 until 3) frags += ocrLine("সকল স্বাভাবিক সংখ্যার সেট একটি অসীম সেট তা দেখানো যায় $k", 54f, 222f + k * 20f)
        assertFalse(structure(frags).any { it is StructuredElement.Heading })
    }
}
