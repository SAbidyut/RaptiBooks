package com.raptibooks.core.structure.text

import java.text.Normalizer

/**
 * Right-to-left scripts (Arabic, Persian, Urdu, Hebrew) in a PDF's text layer.
 *
 * A PDF draws a line from left to right, so its text comes out in visual order: an Arabic word
 * reads backwards, and so does a whole Arabic line. [visualToLogical] puts the units of a line
 * (glyphs with their marks, spaces) back into reading order, keeping numbers and Latin words in
 * their own left-to-right order, the way the Unicode bidirectional algorithm would display them.
 */
object RtlText {
    /** A letter of a right-to-left script (not its digits, which run left to right). */
    fun isRtl(c: Char): Boolean = when (c) {
        in '֐'..'׿' -> c.isLetter()
        in '؀'..'ۿ' -> c !in '٠'..'٩' && c !in '۰'..'۹' && (c.isLetter() || c == '؟' || c == '،' || c == '؛')
        in 'ݐ'..'ݿ', in 'ࢠ'..'ࣿ', in 'יִ'..'ﭏ' -> c.isLetter()
        in 'ﭐ'..'﷿', in 'ﹰ'..'﻿' -> c.isLetter()
        else -> false
    }

    fun hasRtl(text: CharSequence): Boolean = text.any { isRtl(it) }

    fun isMark(c: Char): Boolean = Character.getType(c) == Character.NON_SPACING_MARK.toInt()

    private fun isDigit(c: Char) = c in '0'..'9' || c in '٠'..'٩' || c in '۰'..'۹'

    private enum class Dir { L, R, N }

    private fun classify(unit: String): Dir = when {
        unit.any { isRtl(it) } -> Dir.R
        unit.any { it.isLetter() || isDigit(it) } -> Dir.L
        else -> Dir.N
    }

    /** More right-to-left letters than others: the line reads from the right. */
    fun rtlBase(text: CharSequence): Boolean {
        var r = 0
        var l = 0
        for (c in text) {
            if (isRtl(c) || c == '\u200F') r++ else if (c.isLetter()) l++
        }
        return r > l
    }

    /**
     * Reading order of a line's [units] given in visual (left to right) order: indices into
     * [units]. A unit is a glyph (with its combining marks) or a space.
     */
    fun visualToLogical(units: List<String>, rtlBase: Boolean): IntArray {
        val n = units.size
        val dirs = Array(n) { classify(units[it]) }
        // Neutrals (spaces, punctuation) take the direction of the strong units around them if
        // both sides agree, otherwise the line's direction.
        val resolved = Array(n) { dirs[it] }
        for (i in 0 until n) {
            if (dirs[i] != Dir.N) continue
            var a = i - 1
            while (a >= 0 && dirs[a] == Dir.N) a--
            var b = i + 1
            while (b < n && dirs[b] == Dir.N) b++
            val left = if (a >= 0) dirs[a] else null
            val right = if (b < n) dirs[b] else null
            resolved[i] = if (left != null && left == right) left else if (rtlBase) Dir.R else Dir.L
        }
        val order = IntArray(n) { it }
        if (rtlBase) {
            // the whole line runs right to left; left-to-right runs (numbers, Latin) keep their order
            order.reverse()
            var i = 0
            while (i < n) {
                if (resolved[order[i]] != Dir.L) { i++; continue }
                var j = i
                while (j + 1 < n && resolved[order[j + 1]] == Dir.L) j++
                order.reverse(i, j + 1)
                i = j + 1
            }
        } else {
            // right-to-left runs inside a left-to-right line are reversed in place, and the numbers
            // inside them restored
            var i = 0
            while (i < n) {
                if (resolved[i] != Dir.R) { i++; continue }
                var j = i
                while (j + 1 < n && (resolved[j + 1] == Dir.R || (dirs[j + 1] == Dir.L && !units[j + 1].any { it.isLetter() } && rightOf(resolved, j + 1) == Dir.R))) j++
                order.reverse(i, j + 1)
                var k = i
                while (k <= j) {
                    if (dirs[order[k]] != Dir.L) { k++; continue }
                    var m = k
                    while (m + 1 <= j && dirs[order[m + 1]] == Dir.L) m++
                    order.reverse(k, m + 1)
                    k = m + 1
                }
                i = j + 1
            }
        }
        return order
    }

    private fun rightOf(dirs: Array<Dir>, from: Int): Dir? {
        for (k in from + 1 until dirs.size) if (dirs[k] != Dir.L) return dirs[k]
        return null
    }

    private val MIRROR = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    /** A bracket printed in right-to-left text faces the other way in the character it stands for. */
    fun mirrored(unit: String): String = if (unit.length == 1) MIRROR[unit[0]]?.toString() ?: unit else unit

    /** Arabic presentation forms (contextual glyph shapes) as the letters they stand for. */
    fun normalizeForms(text: String): String {
        if (text.none { it in 'ﭐ'..'﷿' || it in 'ﹰ'..'﻿' }) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            if (c in 'ﭐ'..'﷿' || c in 'ﹰ'..'﻾') sb.append(Normalizer.normalize(c.toString(), Normalizer.Form.NFKC)) else sb.append(c)
        }
        return sb.toString()
    }

    fun isNeutralBracket(unit: String): Boolean = unit.length == 1 && unit[0] in MIRROR
}
