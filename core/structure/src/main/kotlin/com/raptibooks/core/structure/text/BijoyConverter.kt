package com.raptibooks.core.structure.text

import java.text.Normalizer

/**
 * Converts text set in Bijoy/ANSI Bangla fonts (SutonnyMJ and the other "...MJ" fonts that are
 * still common in Bangladeshi documents) to Unicode. Those fonts draw Bangla glyphs at Latin-1
 * code points, so a PDF text layer contains e.g. "Avgvi †mvbvi evsjv" for "আমার সোনার বাংলা".
 *
 * The mapping covers the standard SutonnyMJ layout; uncommon conjunct glyphs may be missing,
 * in which case the source character is kept. Re-converting a book with OCR is the fallback.
 */
object BijoyConverter {

    fun isBijoyFont(fontName: String): Boolean {
        val name = fontName.substringAfter('+') // strip subset prefix "ABCDEF+"
        val lower = name.lowercase()
        return name.contains("MJ") || lower.contains("sutonny") || lower.contains("bijoy")
    }

    /**
     * True if [text] drawn with a Bijoy-named font is actually ANSI-encoded: newer "OMJ" fonts
     * are Unicode and must not be converted.
     */
    fun needsConversion(text: String): Boolean {
        var bengali = 0
        var latin = 0
        for (c in text) {
            if (BanglaText.isBengali(c)) bengali++ else if (c.code in 0x21..0x2FF || c in '‐'..'‰') latin++
        }
        return latin > 0 && bengali * 4 < latin
    }

    /** Pattern-based detection for documents whose font names were stripped. */
    fun looksLikeBijoy(text: String): Boolean {
        var marks = 0
        var letters = 0
        for (c in text) {
            if (c.isLetter()) letters++
            if (c == '†' || c == '‡' || c == 'ˆ' || c == '©' || c == 'ª' ||
                c == '¨' || c == 'Š' || c == '…'
            ) {
                marks++
            }
        }
        if (letters < 20) return false
        val vRatio = text.count { it == 'v' }.toFloat() / letters
        return marks.toFloat() / letters > 0.03f && vRatio > 0.06f
    }

    // Longest keys first; values are Unicode in *visual* (typing) order.
    private val table: List<Pair<String, String>> = listOf(
        "Av" to "আ",
        "A" to "অ", "B" to "ই", "C" to "ঈ", "D" to "উ", "E" to "ঊ", "F" to "ঋ", "G" to "এ",
        "H" to "ঐ", "I" to "ও", "J" to "ঔ",
        "K" to "ক", "L" to "খ", "M" to "গ", "N" to "ঘ", "O" to "ঙ", "P" to "চ", "Q" to "ছ",
        "R" to "জ", "S" to "ঝ", "T" to "ঞ", "U" to "ট", "V" to "ঠ", "W" to "ড", "X" to "ঢ",
        "Y" to "ণ", "Z" to "ত", "_" to "থ", "`" to "দ", "a" to "ধ", "b" to "ন", "c" to "প",
        "d" to "ফ", "e" to "ব", "f" to "ভ", "g" to "ম", "h" to "য", "i" to "র", "j" to "ল",
        "k" to "শ", "l" to "ষ", "m" to "স", "n" to "হ", "o" to "ড়", "p" to "ঢ়", "q" to "য়",
        "r" to "ৎ", "s" to "ং", "t" to "ঃ", "u" to "ঁ",
        "v" to "া", "w" to "ি", "x" to "ী", "y" to "ু", "z" to "ু", "~" to "ূ", "\"" to "ূ",
        "…" to "ৃ", "„" to "ৃ", "‡" to "ে", "†" to "ে", "ˆ" to "ৈ",
        "‰" to "ৈ", "Š" to "ৗ",
        "&" to "্", "|" to "।",
        "0" to "০", "1" to "১", "2" to "২", "3" to "৩", "4" to "৪",
        "5" to "৫", "6" to "৬", "7" to "৭", "8" to "৮", "9" to "৯",
        // phala / reph
        "©" to "র্", "ª" to "্র", "«" to "্র", "Ö" to "্র", "¨" to "্য",
        "¦" to "্ব", "¡" to "্ব", "¢" to "্ভ", "£" to "্ভ্র", "¥" to "্ম",
        "§" to "্ম", "¬" to "্ল", "­" to "্ল", "è" to "্ন", "ú" to "্প",
        "¿" to "্ত্র",
        // conjuncts
        "°" to "ক্ক", "±" to "ক্ট", "²" to "ক্ষ্ম", "³" to "ক্ত", "´" to "ক্ম",
        "µ" to "ক্র", "¶" to "ক্ষ", "·" to "ক্স", "¸" to "গু", "¹" to "জ্ঞ",
        "º" to "গ্দ", "»" to "গ্ধ", "¼" to "ঙ্ক", "½" to "ঙ্গ", "¾" to "জ্জ",
        "À" to "জ্ঝ", "Á" to "জ্ঞ", "Â" to "ঞ্চ", "Ã" to "ঞ্ছ", "Ä" to "ঞ্জ",
        "Å" to "ঞ্ঝ", "Æ" to "ট্ট", "Ç" to "ড্ড", "È" to "ণ্ট", "É" to "ণ্ঠ",
        "Ê" to "ণ্ড", "Ë" to "ত্ত", "Ì" to "ত্থ", "Í" to "ত্ম", "Î" to "ত্র",
        "Ï" to "দ্দ", "×" to "দ্ধ", "Ø" to "দ্ব", "Ù" to "দ্ম", "Ú" to "ন্ঠ",
        "Û" to "ন্ড", "Ü" to "ন্ধ", "Ý" to "ন্স", "Þ" to "প্ট", "ß" to "প্ত",
        "à" to "প্প", "á" to "প্স", "â" to "ব্জ", "ã" to "ব্দ", "ä" to "ব্ধ",
        "å" to "ভ্র", "æ" to "ম্ন", "ç" to "ম্ফ", "é" to "ল্ক", "ê" to "ল্গ",
        "ë" to "ল্ট", "ì" to "ল্ড", "í" to "ল্প", "î" to "ল্ফ", "ï" to "শু",
        "ð" to "শ্চ", "ñ" to "শ্ছ", "ò" to "ষ্ণ", "ó" to "ষ্ট", "ô" to "ষ্ঠ",
        "õ" to "ষ্ফ", "ö" to "স্খ", "÷" to "স্ট", "ø" to "স্ন", "ù" to "স্ফ",
        "û" to "হু", "ü" to "হৃ", "ý" to "হ্ন", "þ" to "হ্ম", "®" to "ষ্ক",
        "¯" to "স্ক",
        // punctuation
        "Ò" to "“", "Ó" to "”", "Ô" to "‘", "Õ" to "’",
        "Ð" to "-", "Ñ" to "-",
    ).sortedByDescending { it.first.length }

    private val single: Map<Char, String> = table.filter { it.first.length == 1 }.associate { it.first[0] to it.second }
    private val multi: List<Pair<String, String>> = table.filter { it.first.length > 1 }

    fun convert(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder(input.length + 8)
        var i = 0
        outer@ while (i < input.length) {
            for ((k, v) in multi) {
                if (input.startsWith(k, i)) {
                    sb.append(v)
                    i += k.length
                    continue@outer
                }
            }
            val c = input[i]
            sb.append(single[c] ?: c.toString())
            i++
        }
        val logical = BanglaText.visualToLogical(sb.toString())
        return Normalizer.normalize(logical, Normalizer.Form.NFC)
    }
}
