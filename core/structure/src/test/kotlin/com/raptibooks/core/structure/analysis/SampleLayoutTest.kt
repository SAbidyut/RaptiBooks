package com.raptibooks.core.structure.analysis

import com.raptibooks.core.model.ParagraphKind
import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.geometry.Rect
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Layouts of the sample books: a PLC manual, a two-column MCQ bank, a syllabus. */
class SampleLayoutTest {

    private fun structure(frags: List<TextFragment>, width: Float = 595f, body: Float = 10f): List<StructuredElement> {
        val layout = PageAnalyzer(body).analyze(RawPage(0, width, 842f, frags))
        val profile = DocumentStats().apply { add(layout) }.profile()
        return PageStructurer(profile).structure(layout)
    }

    @Test
    fun contentsWithChapterPageNumbersAndNumbersApart() {
        val frags = mutableListOf<TextFragment>()
        frags += words("Contents", 260f, 100f, size = 14f, bold = true)
        val entries = listOf("1.1" to "PLC Scan Method", "1.2" to "Current Flow", "1.3" to "NO Contact, NC Contact", "1.4" to "PLC Registers and Relays")
        for ((k, e) in entries.withIndex()) {
            val y = 140f + k * 14f
            frags += words(e.first, 99f, y, size = 12f)
            frags += words(e.second + " " + ".".repeat(40) + " 1-${k + 2}", 135f, y, size = 12f)
        }
        // a title that wraps
        frags += words("1.5.4 AND / ANI (Connect NO contact in series / Connect NC contact in", 111f, 210f, size = 12f)
        frags += words("series) " + ".".repeat(40) + " 1-6", 159f, 224f, size = 12f)
        val toc = structure(frags).filterIsInstance<StructuredElement.Paragraph>().filter { it.kind == ParagraphKind.TOC }.map { it.text }
        assertEquals(
            toc.toString(),
            listOf(
                "1.1 PLC Scan Method\t1-2", "1.2 Current Flow\t1-3", "1.3 NO Contact, NC Contact\t1-4", "1.4 PLC Registers and Relays\t1-5",
                "1.5.4 AND / ANI (Connect NO contact in series / Connect NC contact in series)\t1-6",
            ),
            toc,
        )
    }

    @Test
    fun questionsInTwoColumnsUnderATitleAcrossThemAreNoTable() {
        val frags = mutableListOf<TextFragment>()
        frags += words("Agencies for Persons with Disabilities", 45f, 120f, size = 26f)
        val left = listOf(
            "1. The Rehabilitation Council of India was", "initially set up as a registered society. In which",
            "year was it granted statutory status by an Act", "of Parliament?", "(A) 1986", "(B) 1992", "(C) 1995", "(D) 2000",
        )
        val right = listOf(
            "6. Consider the following statements regarding", "the developmental functions of the Council:",
            "I. Promoting research in rehabilitation and", "special education in the whole country.",
            "II. Encouraging continuing rehabilitation", "education for registered professionals.", "(A) I and II", "(B) II and III",
        )
        for (k in left.indices) {
            frags += words(left[k], if (left[k].startsWith("(")) 67f else 45f, 170f + k * 12.5f)
            frags += words(right[k], if (right[k].startsWith("(")) 337f else 319f, 170f + k * 12.5f)
        }
        val out = structure(frags, width = 576f)
        assertFalse(out.toString(), out.any { it is StructuredElement.Table })
    }

    @Test
    fun linesOfAParagraphSetLargerAreNoTitles() {
        val frags = mutableListOf<TextFragment>()
        // the book's text is small (9 pt); the syllabus is set at 11 pt
        for (k in 0 until 8) frags += words("Questions of the bank are set in the small type of the whole book here $k", 45f, 600f + k * 11f, size = 9f)
        frags += words("Harappan Civilization: Origin, Expansion, Archeological Sites,", 45f, 100f, size = 11f)
        frags += words("Characteristics. Religious Movement: Buddhism and Jainism.", 45f, 114f, size = 11f)
        frags += words("Development in Art and Architecture, Literature, Science and", 45f, 128f, size = 11f)
        frags += words("to Mira, Dadu and Khawaja Moin-ud-din Chishty.", 45f, 142f, size = 11f)
        val layout = PageAnalyzer(9f).analyze(RawPage(0, 576f, 842f, frags))
        val profile = DocumentStats().apply { add(layout); add(layout) }.profile()
        val out = PageStructurer(profile).structure(layout)
        assertTrue(out.toString(), out.none { it is StructuredElement.Heading })
    }

    @Test
    fun aTitleWrappedOntoALineInSmallLettersIsOneHeading() {
        val frags = mutableListOf<TextFragment>()
        for (k in 0 until 6) frags += words("The text of the section runs on in the type of the book here $k", 45f, 200f + k * 12f)
        frags += words("3.6 Nodal and Mesh Analyses", 45f, 120f, size = 16f, bold = true)
        frags += words("by Inspection", 45f, 139f, size = 16f, bold = true)
        val headings = structure(frags).filterIsInstance<StructuredElement.Heading>()
        assertEquals(listOf("3.6 Nodal and Mesh Analyses by Inspection"), headings.map { it.text })
    }

    @Test
    fun itemsInRowsThatDisagreeAreStillPutInOrder() {
        // (a manual's page: tall tables beside lines of two rows, so that "in one row" does not
        // carry over from one pair of items to the next, which the library's sort refuses)
        val rects = listOf(
            "80,290,298", "60,205,213", "110,190,198", "30,290,320", "70,225,233", "140,275,283", "60,30,38",
            "180,225,233", "10,295,303", "30,230,270", "30,215,223", "20,210,218", "110,220,228", "20,120,128",
            "170,15,23", "180,50,58", "90,70,78", "10,5,13", "40,75,125", "30,75,83", "110,5,13", "160,220,228",
            "170,105,113", "170,155,163", "60,35,43", "110,60,68", "50,15,23", "40,225,233", "110,280,288",
            "70,200,208", "100,255,263", "180,125,133", "100,90,98", "180,280,288", "190,40,90", "10,220,228",
            "80,150,158", "40,240,248", "150,70,78", "70,65,73", "150,200,240", "100,170,200", "180,120,128",
            "10,30,38", "170,270,320", "60,265,273", "40,90,98", "0,145,153", "70,30,38", "150,105,113", "190,55,63",
            "50,65,73", "30,235,243", "120,290,298", "110,220,228", "140,235,243", "80,95,103", "170,210,240",
        ).map { it.split(',').map(String::toFloat).let { (left, top, bottom) -> Rect(left, top, left + 40f, bottom) } }
        val sorted = ReadingOrder.sortTopDown(rects) { it }
        assertEquals(rects.toSet(), sorted.toSet())
        // the lines of one row are read left to right
        assertTrue(sorted.indexOf(rects[17]) < sorted.indexOf(rects[20]))
    }
}
