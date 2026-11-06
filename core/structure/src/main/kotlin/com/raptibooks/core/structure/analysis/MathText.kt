package com.raptibooks.core.structure.analysis

import com.raptibooks.core.structure.text.TextCleaner
import kotlin.math.max

/** Recognises lines of mathematics ("P = V × I = 230 × 0.43 ≈ 99 W") in running text. */
object MathText {
    const val RELATIONS = "=≈≠≤≥<>≜≡∝→⇒⟹↔≅∼≃"
    const val BIG_OPERATORS = "∫∬∭∮∑∏√∂∇∞"

    private val MATH_FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "log", "exp", "lim", "max", "min", "det",
        "sinh", "cosh", "tanh", "arg", "mod", "rms", "avg",
    )

    /** Units and symbols written with three or more Latin letters that are not prose words. */
    private val UNITS = setOf(
        "kwh", "mwh", "kva", "kvar", "var", "mva", "rpm", "mah", "kwp", "dod", "eff", "awg", "swg", "rms",
        "khz", "mhz", "ghz", "kohm", "mohm", "ohm", "ohms", "sec", "hrs", "min", "mm²", "cm²",
    )

    /** "প্রদত্ত :", "উত্তর:", "Given:" at the start of a line. */
    private val LABEL = Regex("^\\s*[^\\s:ঃ=]+(?:\\s+[^\\s:ঃ=]+){0,3}\\s*[:ঃ]")

    /** A word of prose: three Latin letters in a row (not a function or unit), or a Bangla word. */
    fun isWord(t: String): Boolean {
        if (t.length < 2) return false
        var latin = 0
        var maxLatin = 0
        var bangla = 0
        for (c in t) {
            if (c in 'a'..'z' || c in 'A'..'Z') {
                latin++
                maxLatin = max(maxLatin, latin)
            } else {
                latin = 0
            }
            if (c in 'অ'..'হ' || c in '\u09DC'..'\u09DF') bangla++
        }
        if (bangla >= 2) return true
        val lower = t.lowercase()
        return maxLatin >= 3 && lower !in MATH_FUNCTIONS && lower !in UNITS && !functionWithBase(lower)
    }

    /**
     * A function name with its subscript or argument written against it ("log3", "logy" for
     * log with a base y, "log10", "sin2θ"): the line text has no space where the base was set
     * lower, so it reads as one word.
     */
    private fun functionWithBase(lower: String): Boolean {
        val fn = MATH_FUNCTIONS.firstOrNull { lower.startsWith(it) && lower.length > it.length } ?: return false
        return lower.substring(fn.length).count { it in 'a'..'z' } <= 1
    }

    class Stats(val words: Int, val wordChars: Int, val relations: Int, val big: Int, val unknown: Int, val solid: Int) {
        val nonWordRatio: Float get() = if (solid == 0) 0f else (solid - wordChars).toFloat() / solid
    }

    fun stats(text: String): Stats {
        var w = 0
        var wordChars = 0
        for (token in text.split(' ', ' ')) {
            val t = token.trim { !it.isLetterOrDigit() }
            if (isWord(t)) {
                w++
                wordChars += t.length
            }
        }
        return Stats(
            w, wordChars, text.count { it in RELATIONS }, text.count { it in BIG_OPERATORS },
            text.count { it == TextCleaner.PLACEHOLDER }, text.count { !it.isWhitespace() },
        )
    }

    /** A labelled line ("প্রদত্ত : V = 230 V"): data of a worked example, not a formula. */
    fun hasLabel(text: String): Boolean {
        val m = LABEL.find(text) ?: return false
        return m.value.any { it in 'অ'..'হ' || it.isLetter() } && m.value.none { it in RELATIONS }
    }

    /**
     * A line that is a formula or a step of a calculation: a relation sign, at most two words
     * (a unit name, "টাকা") and mostly symbols and numbers.
     */
    fun isFormula(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3 || hasLabel(t) || Patterns.listMarker.containsMatchIn(t)) return false
        val s = stats(t)
        return s.relations > 0 && s.words <= 2 && s.nonWordRatio >= 0.5f && !t.endsWith('।')
    }
}
