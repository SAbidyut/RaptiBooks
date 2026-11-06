package com.raptibooks.core.structure.text

/**
 * Bangla text of PDFs whose ToUnicode map lost the cluster boundaries.
 *
 * Generators built on Pango/HarfBuzz (WeasyPrint, many "HTML to PDF" tools and the PDFs an AI
 * writes for you) shape Bangla into glyphs and then record, for each glyph, only the text *between
 * its cluster and the next glyph's cluster*: the **last glyph of a cluster carries the whole
 * cluster text**, the glyphs before it map to nothing, and a glyph keeps whatever it was given the
 * first time it was drawn. Copying such a PDF gives "পদা র্থবজ্ঞান" for "পদার্থবিজ্ঞান": the vowel
 * signs ি ে ৈ are lost (they are glyphs of their own that map to ""), "র্থ" sits on the reph, a
 * "ক্তি" ligature has taken the ি of the glyph before it, a ya-phala has become "্যু".
 *
 * What the PDF still has is the font itself: its `cmap` names the glyph of every plain character
 * (ি is the glyph the cmap maps U+09BF to), and the shape of a glyph tells a vowel sign from a
 * ligature. [classify] gives each glyph its *own* text and its part in the syllable; [assemble]
 * cuts the glyph run into syllables and puts each into logical Unicode order (the sign ি/ে/ৈ drawn
 * before its consonant goes after the consonant cluster, the reph drawn after it goes in front).
 */
object BanglaGlyphs {

    /** The part a glyph plays in a syllable, in the visual order the glyphs are drawn. */
    enum class Role {
        /** Pre-base vowel sign ি ে ৈ (drawn left of the consonant it follows in logical order). */
        PRE,

        /** A consonant, conjunct ligature or independent vowel: the syllable's body. */
        BASE,

        /** A visible hasanta joining the next BASE to this one. */
        JOINER,

        /** Post-base conjunct form drawn after the consonant ("্য", "্র"). */
        PHALA,

        /** The reph "র্" drawn above and after the consonant it precedes in logical order. */
        REPH,

        /** Dependent vowel signs and marks that follow the consonant: া ী ু ূ ৃ ৗ ঁ ং ঃ ়. */
        POST,

        /** A narrow glyph that is either a pre-base ে or a ya-phala: the neighbours decide ([resolve]). */
        AMBIGUOUS,

        /** A glyph that is part of a letter but has no usable text (a conjunct that mapped to ""). */
        UNKNOWN,
    }

    /**
     * One glyph's own text and role. [key] identifies the glyph (font and glyph id) for the
     * decisions [resolve] takes per glyph.
     */
    class Glyph(val role: Role, val text: String, val key: Long = 0L) {
        fun withRole(r: Role, t: String = text) = Glyph(r, t, key)
        override fun toString(): String = "$role($text)"
    }

    /** A glyph's outline measured in em: how far it advances and where its ink is. */
    class Shape(val advance: Float, val xMax: Float, val yMin: Float, val yMax: Float)

    private const val HASANTA = '্'
    private const val REPH_TEXT = "র্"

    private fun bengali(c: Char) = c in 'ঀ'..'৿'

    private fun digit(c: Char) = c in '০'..'৯'

    private fun roleOf(c: Char): Role = when (c) {
        'ি', 'ে', 'ৈ' -> Role.PRE
        HASANTA -> Role.JOINER
        'ঁ', 'ং', 'ঃ', '়', 'া', 'ী', 'ু', 'ূ', 'ৃ', 'ৄ', 'ো', 'ৌ', 'ৗ', 'ৢ', 'ৣ' -> Role.POST
        else -> Role.BASE
    }

    private fun preBase(c: Char) = c == 'ি' || c == 'ে' || c == 'ৈ'

    /**
     * Gives a glyph its own text and role, or null when it is not Bangla (the ordinary text
     * extraction handles it).
     *
     * @param cmapChar the character the font's own cmap maps to this glyph, if any: the glyph is
     *   then exactly that character
     * @param toUnicode what the PDF's ToUnicode says (the cluster's text, or nothing)
     * @param shape the glyph's outline, for a glyph that has neither
     */
    fun classify(cmapChar: String?, toUnicode: String?, shape: Shape?, key: Long = 0L): Glyph? {
        if (cmapChar != null) {
            val c = cmapChar.firstOrNull() ?: return null
            if (!bengali(c) || digit(c)) return null
            return Glyph(roleOf(c), cmapChar, key)
        }
        val t = (toUnicode ?: "").filter { !it.isWhitespace() && it != '​' && it != '‌' && it != '‍' && it != '﻿' }
        val advance = shape?.advance
        if (t.isEmpty()) return classifyBare(shape, key)
        if (!bengali(t[0]) || digit(t[0])) return null
        val narrow = advance != null && advance <= NARROW
        // A mark drawn above the consonant that came before it: the text it was given is the
        // cluster's, starting with "র্".
        if (t.length > 2 && t.startsWith(REPH_TEXT) && (advance == null || advance <= MARK)) return Glyph(Role.REPH, REPH_TEXT, key)
        if (t.length == 2 && t == REPH_TEXT && narrow) return Glyph(Role.REPH, REPH_TEXT, key)
        if (t[0] == HASANTA && t.length >= 2) return Glyph(Role.PHALA, t.substring(0, 2), key)
        if (narrow && t.contains(HASANTA) && t.last() != HASANTA) {
            // A narrow glyph is a post-base form; the cluster text it was given may start earlier.
            val h = t.lastIndexOf(HASANTA)
            if (h + 1 < t.length) return Glyph(Role.PHALA, t.substring(h, h + 2), key)
        }
        // The vowel signs drawn before the consonant are glyphs of their own, so a ligature's text
        // that ends with one has taken it from them.
        var end = t.length
        while (end > 0 && preBase(t[end - 1])) end--
        if (end == 0) return Glyph(Role.PRE, t.take(1), key)
        // (a two-part vowel's right half that has a glyph of its own with a mark: "ৌঁ" is "ৗ" + "ঁ"
        // after the ে that was drawn before the consonant)
        val body = t.substring(0, end).replace('ো', 'া').replace('ৌ', 'ৗ')
        return Glyph(roleOf(body[0]), body, key)
    }

    // The glyph maps to nothing: pre-base vowel sign variants (the sign is drawn narrower or wider
    // for the consonant it stands before), a reph, a ya-phala, or a conjunct whose cluster text went
    // to another glyph.
    private fun classifyBare(shape: Shape?, key: Long): Glyph {
        if (shape == null) return Glyph(Role.UNKNOWN, "", key)
        if (shape.advance <= MARK && shape.yMin >= 0.4f) return Glyph(Role.REPH, REPH_TEXT, key)
        if (shape.advance >= WIDE) return Glyph(Role.UNKNOWN, "", key)
        if (shape.xMax > 1.6f * maxOf(shape.advance, 0.05f)) return Glyph(Role.PRE, "ি", key)
        if (shape.yMax > 0.78f) return Glyph(Role.PRE, "ৈ", key)
        return Glyph(Role.AMBIGUOUS, "ে", key)
    }

    private const val MARK = 0.12f
    private const val NARROW = 0.32f
    private const val WIDE = 0.46f

    /**
     * Decides the [Role.AMBIGUOUS] glyphs of a page's glyph run (null entries end a run: a space,
     * a Latin letter, another line). A pre-base ে starts a syllable and is never followed by a
     * space or a vowel sign; a ya-phala hangs on the consonant before it. Evidence from any
     * place the glyph is used decides for all of them; with none it is the far more common ে.
     */
    fun resolve(glyphs: List<Glyph?>): List<Glyph?> {
        if (glyphs.none { it?.role == Role.AMBIGUOUS }) return glyphs
        val startsSyllable = HashSet<Long>()
        val hangsOn = HashSet<Long>()
        for (i in glyphs.indices) {
            val g = glyphs[i] ?: continue
            if (g.role != Role.AMBIGUOUS) continue
            val prev = glyphs.getOrNull(i - 1)
            val next = glyphs.getOrNull(i + 1)
            val prevEnds = prev == null || prev.role == Role.POST || prev.role == Role.PHALA || prev.role == Role.REPH
            val nextEnds = next == null || next.role == Role.POST || next.role == Role.PRE || next.role == Role.PHALA || next.role == Role.REPH
            if (prevEnds) startsSyllable += g.key
            if (nextEnds && prev != null && prev.role == Role.BASE) hangsOn += g.key
        }
        return glyphs.map { g ->
            if (g == null || g.role != Role.AMBIGUOUS) g
            else if (g.key in hangsOn && g.key !in startsSyllable) g.withRole(Role.PHALA, "্য")
            else g.withRole(Role.PRE, "ে")
        }
    }

    /** A syllable: the indices of its glyphs in the run and its text in logical order. */
    class Syllable(val members: List<Int>, val text: String, val unknown: Boolean = false)

    /** Cuts a resolved glyph run into syllables ([resolve] first; null entries end a syllable). */
    fun assemble(glyphs: List<Glyph?>): List<Syllable> {
        val out = ArrayList<Syllable>()
        var cur = ArrayList<Int>()
        var hasBase = false
        var joined = false
        fun flush() {
            if (cur.isNotEmpty()) out += Syllable(cur, logical(cur.map { glyphs[it]!! }))
            cur = ArrayList()
            hasBase = false
            joined = false
        }
        for (i in glyphs.indices) {
            val g = glyphs[i]
            if (g == null) {
                flush()
                continue
            }
            when (g.role) {
                Role.UNKNOWN -> {
                    flush()
                    out += Syllable(listOf(i), TextCleaner.PLACEHOLDER.toString(), unknown = true)
                }
                Role.PRE -> {
                    if (hasBase) flush()
                    cur += i
                }
                Role.BASE -> {
                    if (hasBase && !joined) flush()
                    cur += i
                    hasBase = true
                    joined = false
                }
                Role.JOINER -> {
                    cur += i
                    joined = hasBase
                }
                else -> { // PHALA, REPH, POST, AMBIGUOUS left over
                    cur += i
                    joined = false
                }
            }
        }
        flush()
        return out
    }

    /** One syllable's glyphs (visual order) as logical text: reph, consonants, phalas, ে ি ৈ, then marks. */
    fun logical(glyphs: List<Glyph>): String {
        val sb = StringBuilder()
        for (g in glyphs) if (g.role == Role.REPH) sb.append(g.text)
        for (g in glyphs) if (g.role == Role.BASE || g.role == Role.JOINER) sb.append(g.text)
        for (g in glyphs) if (g.role == Role.PHALA) sb.append(g.text)
        for (g in glyphs) if (g.role == Role.PRE) sb.append(g.text)
        for (g in glyphs) if (g.role == Role.POST || g.role == Role.AMBIGUOUS) sb.append(g.text)
        return sb.toString()
    }
}
