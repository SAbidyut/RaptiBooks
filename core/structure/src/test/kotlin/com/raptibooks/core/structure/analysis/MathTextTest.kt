package com.raptibooks.core.structure.analysis

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTextTest {
    @Test
    fun aFunctionWithItsBaseSetAgainstItIsNotAWord() {
        // "log" with a lowered base: the line text has no space between them
        assertFalse(MathText.isWord("logy"))
        assertFalse(MathText.isWord("log3"))
        assertFalse(MathText.isWord("log10"))
        assertFalse(MathText.isWord("log"))
        // prose that merely starts like a function is still a word
        assertTrue(MathText.isWord("logic"))
        assertTrue(MathText.isWord("single"))
    }

    @Test
    fun aDerivationWithLoweredBasesIsAFormula() {
        assertTrue(MathText.isFormula("RHS = logy √y3 = log3 √33 = log3(33/2) = ৩ log3 3 = ২"))
        assertFalse(MathText.isFormula("The logic of this proof is simple and short."))
    }
}
