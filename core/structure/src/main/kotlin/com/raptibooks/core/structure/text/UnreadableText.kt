package com.raptibooks.core.structure.text

/**
 * Garbage in a text layer where the OCR that made it could not read the script: Acrobat's
 * ClearScan run on an English book about Arabic turns each Arabic word into glyphs without
 * Unicode ("￼") or into Latin junk such as "y\:iSll", "o.Jt;....", "11\".
 */
object UnreadableText {
    /** The words of [text] (split at spaces) with their character ranges. */
    fun tokens(text: String): List<Pair<IntRange, String>> {
        val out = ArrayList<Pair<IntRange, String>>()
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ') { i++; continue }
            var j = i
            while (j + 1 < text.length && text[j + 1] != ' ') j++
            out += (i..j) to text.substring(i, j + 1)
            i = j + 1
        }
        return out
    }

    private val LEADING_WORD = Regex("^([A-Za-z]{2,})([^A-Za-z].*)$")
    private val TRAILING_WORD = Regex("^(.*[^A-Za-z'])([A-Za-z]{2,}[.,;:!?\"”’)]*)$")

    /**
     * The words of [text] with garbage stuck to an English word split off it (the scan's text
     * layer has no space there): "￼￼means" is "￼￼" and "means", "written￼￼-If" three words.
     */
    fun words(text: String): List<Pair<IntRange, String>> {
        val out = ArrayList<Pair<IntRange, String>>()
        val queue = ArrayDeque(tokens(text))
        while (queue.isNotEmpty()) {
            val (range, t) = queue.removeFirst()
            if (!garbled(t)) { out += range to t; continue }
            val lead = LEADING_WORD.find(t)
            if (lead != null && isWord(lead.groupValues[1]) && junkPart(lead.groupValues[2])) {
                val n = lead.groupValues[1].length
                out += (range.first until range.first + n) to lead.groupValues[1]
                queue.addFirst((range.first + n..range.last) to lead.groupValues[2])
                continue
            }
            val trail = TRAILING_WORD.find(t)
            if (trail != null && isWord(trail.groupValues[2].trimEnd(*TRIM)) && junkPart(trail.groupValues[1])) {
                val n = trail.groupValues[1].length
                queue.addFirst((range.first + n..range.last) to trail.groupValues[2])
                queue.addFirst((range.first until range.first + n) to trail.groupValues[1])
                continue
            }
            out += range to t
        }
        return out
    }

    private fun isWord(w: String): Boolean = w.drop(1).all { it.isLowerCase() } && (englishLike(w) || w.lowercase() in ENGLISH)

    private fun junkPart(t: String): Boolean = garbled(t) || t.any { it in ODD || it == TextCleaner.PLACEHOLDER }

    private val TRIM = charArrayOf('"', '\'', '(', ')', ',', '.', ';', ':', '!', '?', '“', '”', '‘', '’', '-', '·', '•')

    private val ODD = charArrayOf('\\', '¥', '→', '←', '|', '~', '^', '{', '}', '`')

    /** A small letter, then punctuation, then a capital: "o.Jt", "c.JA", "Jt;", "U:a:J". */
    private val MIXED = Regex(
        "\\p{Ll}[.:;,=][\\p{Lu}]|\\p{L}[;:=]\\p{L}|[;:][.,;:]|[.,][;:]|_[;:.]|[;:.]_|\\d[,:;][,:;.]|\\p{L}=\\.|\\.{2,}\\p{L}|^\\.\\p{Lu}",
    )

    /** Certainly garbage: unmapped glyphs inside a word, or a backslash among letters. */
    fun strong(token: String): Boolean {
        val core = token.trim(*TRIM)
        if (core.isEmpty()) return false
        val unmapped = core.count { it == TextCleaner.PLACEHOLDER }
        if (unmapped >= 2 || (unmapped == 1 && core.length >= 2 && core.any { it.isLetterOrDigit() })) return true
        return core.contains('\\') && core.any { it.isLetterOrDigit() || it == TextCleaner.PLACEHOLDER }
    }

    /** The word is garbage (see [strong]), or junk made of letters and punctuation. */
    fun garbled(token: String): Boolean {
        val core = token.trim(*TRIM)
        if (core.isEmpty()) return false
        if (strong(token)) return true
        if (core.all { it == TextCleaner.PLACEHOLDER || it in ODD }) return true
        if (core.any { it in ODD } && core.any { it.isLetterOrDigit() }) return true
        return MIXED.containsMatchIn(core) || MIXED.containsMatchIn(token)
    }

    /** A word of ordinary text: three or more letters, small but for the first, with a vowel. */
    private val WORD = Regex("^[A-Za-z][a-z]{2,}$")

    fun englishLike(token: String): Boolean {
        val core = token.trim(*TRIM)
        return WORD.matches(core) && core.any { it.lowercaseChar() in "aeiouy" }
    }

    /** Punctuation junk: no word in it, two or more marks ("..", "_,", ".....,", ".J"). */
    fun junk(token: String): Boolean {
        val core = token.trim()
        if (core.length < 2 || englishLike(core)) return false
        val marks = core.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        return marks >= 2 || (marks >= 1 && core.any { it.isLetter() } && core.count { it.isLetter() } <= 2)
    }

    private val ENGLISH = setOf(
        "a", "an", "as", "at", "be", "by", "do", "go", "he", "if", "in", "is", "it", "me", "my", "no", "of", "on", "or",
        "so", "to", "up", "us", "we", "i", "and", "the", "for", "but", "are", "was", "not", "you", "its",
    )

    /** A short piece that is not an English word ("J", "o", "11"): part of the garbage around it. */
    fun fragment(token: String): Boolean {
        val core = token.trim(*TRIM)
        return core.length <= 3 && core.lowercase() !in ENGLISH
    }
}
