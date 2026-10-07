package com.raptibooks.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkingTest {
    private val red = MarkColor.RED
    private val blue = MarkColor.BLUE

    @Test
    fun marksEmptyBlock() {
        assertEquals(listOf(MarkSpan(2, 7, red)), Marking.apply(emptyList(), listOf(2..6), red))
    }

    @Test
    fun newColourCutsTheOldMark() {
        val result = Marking.apply(listOf(MarkSpan(0, 10, red)), listOf(3..4), blue)
        assertEquals(listOf(MarkSpan(0, 3, red), MarkSpan(3, 5, blue), MarkSpan(5, 10, red)), result)
    }

    @Test
    fun erasingCutsAndRemoves() {
        assertEquals(
            listOf(MarkSpan(0, 3, red), MarkSpan(5, 10, red)),
            Marking.apply(listOf(MarkSpan(0, 10, red)), listOf(3..4), null),
        )
        assertEquals(emptyList<MarkSpan>(), Marking.apply(listOf(MarkSpan(2, 5, red)), listOf(0..9), null))
    }

    @Test
    fun joinsTouchingMarksOfTheSameColour() {
        val result = Marking.apply(listOf(MarkSpan(0, 3, red), MarkSpan(6, 9, red)), listOf(3..5), red)
        assertEquals(listOf(MarkSpan(0, 9, red)), result)
    }

    @Test
    fun severalRangesOfOneBlock() {
        val result = Marking.apply(emptyList(), listOf(0..3, 10..12), blue)
        assertEquals(listOf(MarkSpan(0, 4, blue), MarkSpan(10, 13, blue)), result)
    }
}
