package com.raptibooks.core.structure.text

import java.text.Normalizer

/**
 * Bangla (Bengali script) helpers.
 *
 * PDF generators draw Bangla in *visual* glyph order: the vowel signs ি ে ৈ are drawn before
 * the consonant they belong to and the reph (র্) is drawn after its consonant. When the PDF's
 * ToUnicode map is glyph-by-glyph, text extraction therefore yields e.g. "িক" instead of "কি".
 * [visualToLogical] restores Unicode logical order; [visualOrderEvidence] decides whether a
 * document needs it, because applying it to text that is already logical would corrupt it.
 */
object BanglaText {
    const val HASANTA = '্'
    const val NUKTA = '়'
    const val ZWJ = '‍'
    const val ZWNJ = '‌'
    private const val RA = 'র'

    fun isBengali(c: Char): Boolean = c in 'ঀ'..'৿'

    fun isConsonant(c: Char): Boolean =
        c in 'ক'..'হ' || c == 'ড়' || c == 'ঢ়' || c == 'য়' || c == 'ৰ' || c == 'ৱ'

    fun isIndependentVowel(c: Char): Boolean = c in 'অ'..'ঔ'

    fun isPreBaseMatra(c: Char): Boolean = c == 'ি' || c == 'ে' || c == 'ৈ'

    /** Dependent vowel signs (kar), including the AU length mark. */
    fun isMatra(c: Char): Boolean = c in 'া'..'ৌ' || c == 'ৗ'

    fun isSign(c: Char): Boolean = c in 'ঁ'..'ঃ'

    fun isBengaliDigit(c: Char): Boolean = c in '০'..'৯'

    fun isBengaliLetter(c: Char): Boolean = isConsonant(c) || isIndependentVowel(c) || c == 'ৎ'

    /**
     * Counts pre-base vowel signs that sit where logical Unicode never puts them (at the start
     * of a word, or after a vowel). Returns (impossible, total pre-base signs).
     */
    fun visualOrderEvidence(text: String): Pair<Int, Int> {
        var impossible = 0
        var total = 0
        for (i in text.indices) {
            val c = text[i]
            if (!isPreBaseMatra(c)) continue
            total++
            val prev = if (i > 0) text[i - 1] else ' '
            val next = if (i + 1 < text.length) text[i + 1] else ' '
            val prevAllowsMatra = isConsonant(prev) || prev == NUKTA || prev == ZWJ || prev == ZWNJ
            if (!prevAllowsMatra && isConsonant(next)) impossible++
        }
        return impossible to total
    }

    /** Index just past the consonant cluster starting at [start] (C (nukta)? (hasanta [zw] C (nukta)?)*). */
    private fun clusterEnd(s: CharSequence, start: Int): Int {
        var k = start
        if (k >= s.length || !isConsonant(s[k])) return start
        k++
        if (k < s.length && s[k] == NUKTA) k++
        while (k < s.length && s[k] == HASANTA) {
            var j = k + 1
            if (j < s.length && (s[j] == ZWJ || s[j] == ZWNJ)) j++
            if (j < s.length && isConsonant(s[j])) {
                k = j + 1
                if (k < s.length && s[k] == NUKTA) k++
            } else {
                break
            }
        }
        return k
    }

    /** Moves pre-base vowel signs after the consonant cluster that follows them. */
    fun visualToLogical(text: String): String {
        if (text.none { isPreBaseMatra(it) }) return fixReph(text)
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isPreBaseMatra(c)) {
                // Several pre-base glyphs may stack (rare); take the cluster after the last one.
                val end = clusterEnd(text, i + 1)
                if (end > i + 1) {
                    sb.append(text, i + 1, end)
                    sb.append(c)
                    i = end
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return fixReph(sb.toString())
    }

    /**
     * Reph drawn after its consonant ("কমর্" for "কর্ম"): moves "র্" that is followed by a
     * non-consonant back in front of the preceding cluster. "র্" + non-consonant is invalid in
     * logical text (except before ZWJ/ZWNJ), so this is safe to apply to visual-order text.
     */
    fun fixReph(text: String): String {
        if (!text.contains("$RA$HASANTA")) return text
        val sb = StringBuilder(text)
        var i = 0
        while (i < sb.length - 1) {
            if (sb[i] == RA && sb[i + 1] == HASANTA) {
                val after = if (i + 2 < sb.length) sb[i + 2] else ' '
                val legit = isConsonant(after) || after == ZWJ || after == ZWNJ
                if (!legit && i > 0) {
                    // Walk back over dependent vowels/signs, then over one consonant cluster.
                    var j = i
                    while (j > 0 && (isMatra(sb[j - 1]) || isSign(sb[j - 1]))) j--
                    val matraStart = j
                    var clusterStart = -1
                    var k = j - 1
                    while (k >= 0) {
                        if (isConsonant(sb[k])) {
                            clusterStart = k
                            if (k >= 2 && sb[k - 1] == HASANTA && isConsonant(sb[k - 2])) {
                                k -= 2
                                continue
                            }
                            break
                        } else if (sb[k] == NUKTA) {
                            k--
                        } else {
                            break
                        }
                    }
                    if (clusterStart >= 0 && clusterStart < matraStart) {
                        sb.delete(i, i + 2)
                        sb.insert(clusterStart, "$RA$HASANTA")
                        i += 2
                        continue
                    }
                }
            }
            i++
        }
        return sb.toString()
    }

    /** Counts Bengali vs Latin letters. */
    fun scriptCounts(text: CharSequence): Pair<Int, Int> {
        var bn = 0
        var latin = 0
        for (c in text) {
            if (isBengaliLetter(c) || isMatra(c)) bn++ else if (c in 'a'..'z' || c in 'A'..'Z') latin++
        }
        return bn to latin
    }
}

/** General text cleanup shared by all extracted text. */
object TextCleaner {
    /**
     * Stands for a glyph that has no usable Unicode mapping (typically a math symbol). It keeps
     * the glyph's place during layout analysis and is dropped from the final text.
     */
    const val PLACEHOLDER = '\uFFFC'

    private val deltaAsCent = Regex("¢(?=\\p{L})")

    /** Final text: placeholders removed, "¢q"-style Δ from Greek/math fonts repaired. */
    fun finish(text: String): String {
        var t = text
        if (t.indexOf(PLACEHOLDER) >= 0) t = t.replace(PLACEHOLDER.toString(), "")
        if (t.indexOf('¢') >= 0) t = deltaAsCent.replace(t, "Δ")
        return t
    }

    private val ligatures = mapOf(
        'ﬀ' to "ff", 'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi",
        'ﬄ' to "ffl", 'ﬅ' to "st", 'ﬆ' to "st",
    )

    fun clean(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '­' || c == '​' || c == '﻿' || c == '◌' || c == '�' -> Unit
                c == ' ' || c == ' ' || c == ' ' || c == '\t' -> sb.append(' ')
                ligatures.containsKey(c) -> sb.append(ligatures.getValue(c))
                c.isISOControl() && c != '\n' -> Unit
                c in ''..'' -> Unit // private use area: unmapped symbol glyphs
                else -> sb.append(c)
            }
        }
        return Normalizer.normalize(sb, Normalizer.Form.NFC)
    }

    /** Collapses runs of spaces (keeps newlines). */
    fun collapseSpaces(text: String): String {
        val sb = StringBuilder(text.length)
        var lastSpace = false
        for (c in text) {
            if (c == ' ') {
                if (!lastSpace) sb.append(c)
                lastSpace = true
            } else {
                sb.append(c)
                lastSpace = false
            }
        }
        return sb.toString()
    }
}

/** Heuristic text-layer quality check used to decide whether a page needs OCR. */
object TextQuality {
    /**
     * Fraction (0..1) of characters that look like garbage: replacement characters, private
     * use code points, control characters, or Latin-1 symbols that typically come from broken
     * font encodings.
     */
    fun garbageRatio(text: String): Float {
        var bad = 0
        var total = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            total++
            if (c == '�' || c in ''..'' || (c.isISOControl()) ||
                c in '\u0080'..'¿' || c in '†'..'‰' || c == 'ƒ'
            ) {
                bad++
            }
        }
        return if (total == 0) 0f else bad.toFloat() / total
    }

    /** Letters (any script) and digits. */
    fun letterCount(text: CharSequence): Int = text.count { it.isLetterOrDigit() || BanglaText.isMatra(it) }
}
