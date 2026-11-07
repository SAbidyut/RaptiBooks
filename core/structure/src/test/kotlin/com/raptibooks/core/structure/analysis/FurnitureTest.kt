package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.TestPages.words
import com.raptibooks.core.structure.raw.RawPage
import com.raptibooks.core.structure.raw.TextFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A copyright notice of several lines above the foot of every page (a solutions manual). */
class FurnitureTest {
    private val notice = listOf(
        "PROPRIETARY MATERIAL. Copyright 2007 The Publisher. All rights reserved. No part",
        "of this Manual may be displayed, reproduced or distributed in any form or by any means",
        "written permission of the publisher, or used beyond the limited distribution to teachers",
        "permitted by the publisher for their individual course preparation. If you are a student",
        "you are using it without permission.",
    )

    private fun page(index: Int): RawPage {
        val frags = mutableListOf<TextFragment>()
        frags += words("Chapter 1, Problem ${index + 1}.", 90f, 85f, size = 12f, bold = true)
        frags += words("Find the current through the resistor when the source is switched on now.", 90f, 115f, size = 12f)
        frags += words("Chapter 1, Solution ${index + 1}", 90f, 160f, size = 12f, bold = true)
        frags += words("The current is the voltage divided by the resistance of the circuit.", 90f, 190f, size = 12f)
        for ((k, l) in notice.withIndex()) frags += words(l, 90f, 696f + k * 11.5f, size = 10f)
        return RawPage(index, 612f, 792f, frags)
    }

    @Test
    fun aNoticeAboveThePageFootIsNoBodyTextAndIsDropped() {
        val survey = BodySizeSurvey()
        for (i in 0 until 8) survey.add(page(i).fragments)
        assertEquals(12f, survey.bodySize()!!, 0.01f)

        val analyzer = PageAnalyzer(12f)
        val layouts = (0 until 8).map { analyzer.analyze(page(it)) }
        val profile = DocumentStats().apply { layouts.forEach { add(it) } }.profile()
        assertEquals(12f, profile.bodySize, 0.01f)
        val elements = PageStructurer(profile).structure(layouts[3])
        val text = elements.joinToString("\n") { it.toString() }
        assertTrue(text, elements.none { it is StructuredElement.Paragraph && it.text.contains("PROPRIETARY") })
        val headings = elements.filterIsInstance<StructuredElement.Heading>()
        assertEquals(text, listOf("Chapter 1, Problem 4." to 3, "Chapter 1, Solution 4" to 4), headings.map { it.text to it.level })
    }
}
