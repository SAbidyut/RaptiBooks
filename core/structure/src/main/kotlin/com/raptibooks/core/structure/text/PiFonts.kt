package com.raptibooks.core.structure.text

/**
 * Text of "pi fonts": the symbol fonts typesetters of engineering and science textbooks use for
 * math (Linotype Mathematical Pi, Greek and operator fonts). Their glyphs either have no Unicode
 * mapping at all (only a glyph name such as "H11005") or a misleading one (a Greek font that maps
 * θ to "u", an operator font that maps "<" to "6"). Without this the text loses its "=", "ω",
 * "θ", "<" and "Δ", or shows letters where symbols belong.
 */
object PiFonts {

    /** Linotype Mathematical Pi and its relatives: glyph names "H11005", "HS11005". */
    private val MATH_PI_FONT = Regex("mathematicalpi|mathpi|universalmath|universalgreek", RegexOption.IGNORE_CASE)
    private val PI_GLYPH = Regex("^HS?\\d{4,5}$")

    /** Mathematical Pi glyph names (operators, relations, delimiters). */
    private val MATH_PI = mapOf(
        "H11001" to "+", "H11002" to "−", "H11003" to "×", "H11004" to "÷", "H11005" to "=",
        "HS11005" to "≠", "H11006" to "±", "H11007" to "∓", "H11009" to "∞", "H11015" to "≈", "H11017" to "≤",
        "H11021" to "<", "H11022" to ">", "H11028" to "∠", "H11032" to "′", "H11033" to "″", "H11034" to "°", "H11061" to "≅",
        "H33361" to "≅", "H20904" to "|",
        "H11084" to "≥", "H11229" to "≃", "H11349" to "≤", "H11350" to "≥",
        // bold set (Mathematical Pi Four)
        "H11543" to "°", "H11545" to "+", "H11546" to "−", "H11549" to "=", "H11554" to "·", "H11557" to "∞",
        "H20648" to "‖", "H20848" to "∫", "H20858" to "∑", "H20862" to "/", "H20900" to "[", "H20901" to "]",
        "H20910" to "∫", "H5129" to "ℓ", "H5133" to "ℓ",
        "H9278" to "φ", "H9280" to "ϵ",
    )

    /** "Optr": an operator font whose mapping names the Latin key of each symbol. */
    private val OPERATORS = mapOf(
        "6" to "<", "7" to ">", "¢" to "Δ", "ß" to "Π", "l" to "∠", "¿" to "ℓ", "1" to "⇒", "3" to "⇔",
        "p" to "⋯", "/" to "⋮", "o" to "⋮", "S" to "→", "¡" to "⟶", "V" to "≪", "W" to "≫", ";" to "±",
        "0" to "∂", "r" to "∝", "–" to "″",
    )

    /** Big delimiters ("PBB"): parentheses, brackets, braces and bars; other pieces carry no text. */
    private val DELIMITERS = mapOf("a" to "(", "b" to ")", "c" to "[", "d" to "]", "e" to "{", "f" to "}", "`" to "|", "0" to "|")

    /** The same delimiters in a cut of the font known by glyph names only. */
    private val DELIMITER_GLYPHS = mapOf("one" to "(", "two" to ")", "greater" to "/")

    /** "Math Technical" fonts: π and μ typed as p and m. */
    private val TECHNICAL = mapOf("p" to "π", "m" to "μ")

    /** Greek typed on a Greek keyboard layout: u = θ, c = ψ, v = ω, j = ξ (capitals: bold). */
    private val GREEK_KEYBOARD = mapOf(
        'a' to "α", 'b' to "β", 'g' to "γ", 'd' to "δ", 'e' to "ε", 'z' to "ζ", 'h' to "η", 'u' to "θ",
        'i' to "ι", 'k' to "κ", 'l' to "λ", 'm' to "μ", 'n' to "ν", 'j' to "ξ", 'o' to "ο", 'p' to "π",
        'r' to "ρ", 's' to "σ", 'w' to "ς", 't' to "τ", 'y' to "υ", 'f' to "φ", 'x' to "χ", 'c' to "ψ", 'v' to "ω",
    )

    private val SCRIPT = mapOf("F" to "ℱ", "L" to "ℒ", "H" to "ℋ", "E" to "ℰ", "B" to "ℬ", "R" to "ℛ", "I" to "ℐ", "M" to "ℳ")

    /**
     * The text of one glyph of [fontName] (glyph name [glyph], mapped text [unicode]):
     * null if the font is not a known pi font (use its own mapping), "" if it is but this glyph
     * is not known (to be identified from its shape, or dropped), else the character.
     */
    fun decode(fontName: String, glyph: String, unicode: String?): String? {
        val family = fontName.substringAfter('+')
        if (PI_GLYPH.matches(glyph) && MATH_PI_FONT.containsMatchIn(family)) return mathPi(glyph) ?: ""
        if (family.startsWith("PBB")) return unicode?.let { DELIMITERS[it] } ?: DELIMITER_GLYPHS[glyph] ?: ""
        val text = unicode ?: return null
        return when {
            family.startsWith("Optr") -> OPERATORS[text] ?: ""
            family.startsWith("MathTechnical") -> TECHNICAL[text] ?: text
            family == "Grk" || family.startsWith("Grk-") -> {
                val c = text.singleOrNull() ?: return null
                GREEK_KEYBOARD[c.lowercaseChar()] ?: text
            }
            family.startsWith("Fancy") || family.startsWith("CMSY") -> SCRIPT[text]
            family.startsWith("Rads") -> if (text in setOf("1", "2", "A", "B")) "√" else ""
            family.startsWith("Mtx") -> "" // brace and matrix-bracket pieces
            family.startsWith("ISUIP") -> if (text == "a") "∑" else ""
            else -> null
        }
    }

    /** Greek follows Unicode order: H9001… capitals, H9251… lowercase; operators by table. */
    private fun mathPi(glyph: String): String? {
        MATH_PI[glyph]?.let { return it }
        val n = glyph.removePrefix("H").toIntOrNull() ?: return null
        return when (n) {
            // Α…Ρ, then Σ…Ω (Unicode leaves U+03A2 empty)
            in 9001..9017 -> (0x391 + n - 9001).toChar().toString()
            in 9018..9024 -> (0x391 + n - 9000).toChar().toString()
            // α…ρ, σ, ς, τ…ω
            in 9251..9267 -> (0x3B1 + n - 9251).toChar().toString()
            9268 -> "σ"
            9269 -> "ς"
            in 9270..9275 -> (0x3B1 + n - 9251).toChar().toString()
            else -> null
        }
    }
}
