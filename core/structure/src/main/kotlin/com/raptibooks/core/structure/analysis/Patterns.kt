package com.raptibooks.core.structure.analysis

/** Text patterns used by the structure analysis (English + Bangla). */
object Patterns {
    private const val NUM = "[0-9০-৯]"

    private const val CAPTION_WORDS =
        "fig\\.?|figure|chart|diagram|graph|plate|illustration|image|photo|picture|map|table|exhibit|" +
            "চিত্র|ছবি|রেখাচিত্র|নকশা|মানচিত্র|সারণি|সারণী|টেবিল|ডায়াগ্রাম|লেখচিত্র"

    /** "Figure 3:", "Fig. 2.1", "চিত্র ৪ -", "Table 1." */
    val caption = Regex(
        "^\\s*(?:$CAPTION_WORDS)\\s*(?:$NUM+(?:[.\\-]$NUM+)*|[IVXivx]+)?\\s*[:.।\\-–—)]|" +
            "^\\s*(?:$CAPTION_WORDS)\\s+$NUM+",
        RegexOption.IGNORE_CASE,
    )

    val tableCaption = Regex("^\\s*(?:table|সারণি|সারণী|টেবিল)\\s*$NUM*", RegexOption.IGNORE_CASE)

    /** Bullet or enumerator at the start of a line, followed by whitespace. */
    val listMarker = Regex(
        "^\\s*(" +
            "[•●○◦▪▫■□♦◆◇►▸▹➢➤✓✔☐☑❖✦✧*·]" +
            "|\\(?$NUM{1,3}[.)।]" +
            "|\\($NUM{1,3}\\)" +
            "|\\(?[a-z][.)]" +
            "|\\([a-zA-Z]\\)" +
            "|[A-Z]\\)" +
            "|\\(?[ivx]{1,5}[.)]" +
            "|[IVX]{1,5}[.)]" +
            "|\\(?[ক-হ][.)।]" +
            "|\\([ক-হ]\\)" +
            ")\\s+",
    )

    private const val CHAPTER_WORDS =
        "chapter|part|book|unit|lesson|appendix|অধ্যায়|অধ্যায়|পরিচ্ছেদ|খণ্ড|খন্ড|পর্ব|ভাগ|পাঠ|ইউনিট|পরিশিষ্ট"

    private const val FRONT_MATTER_WORDS =
        "prologue|epilogue|preface|foreword|introduction|contents|table of contents|acknowledgements?|" +
            "acknowledgments?|bibliography|references|index|glossary|abstract|summary|conclusion|" +
            "ভূমিকা|মুখবন্ধ|প্রস্তাবনা|উপসংহার|সূচিপত্র|সূচি|সূচীপত্র|গ্রন্থপঞ্জি|তথ্যসূত্র|উৎসর্গ|কৃতজ্ঞতা|সারসংক্ষেপ"

    /** "Chapter 4", "অধ্যায় ৩", "Part II" */
    val chapter = Regex(
        "^\\s*(?:$CHAPTER_WORDS)\\s*(?:$NUM+|[IVXLC]+|[ivxlc]+|one|two|three|four|five|six|seven|eight|nine|ten|" +
            "এক|দুই|তিন|চার|পাঁচ|ছয়|সাত|আট|নয়|দশ|প্রথম|দ্বিতীয়|তৃতীয়|চতুর্থ|পঞ্চম)?(?=[\\s:.\\-–—]|$)",
        RegexOption.IGNORE_CASE,
    )

    val frontMatter = Regex("^\\s*(?:$FRONT_MATTER_WORDS)\\s*[:.]?\\s*$", RegexOption.IGNORE_CASE)

    /** "2.3 Methods" -> group 1 = "2.3" */
    val numberedHeading = Regex("^\\s*($NUM{1,2}(?:\\.$NUM{1,2}){0,3})\\.?\\s+\\S")

    /** Lines that are only a page number ("12", "- 12 -", "Page 3 of 10", "পৃষ্ঠা ৫", "xii"). */
    val pageNumber = Regex(
        "^[\\s\\-–—(\\[|]*(?:(?:page|p\\.|pg\\.?|পৃষ্ঠা|পৃঃ|পৃ\\.?)\\s*)?(?:$NUM{1,4}|[ivxlcdm]{1,7}|[IVXLCDM]{1,7})" +
            "(?:\\s*(?:of|/|এর)\\s*$NUM{1,4})?[\\s\\-–—)\\]|]*$",
        RegexOption.IGNORE_CASE,
    )

    private val terminal = Regex("[.!?।॥…:;]['\"”’)\\]]*\\s*$")

    fun endsSentence(text: String): Boolean = terminal.containsMatchIn(text)

    /** Normalised form used to recognise repeated running headers/footers. */
    fun signature(text: String): String {
        val sb = StringBuilder(text.length)
        var lastWasDigit = false
        for (c in text.lowercase()) {
            val digit = c in '0'..'9' || c in '০'..'৯'
            if (digit) {
                if (!lastWasDigit) sb.append('#')
            } else if (!c.isWhitespace()) {
                sb.append(c)
            }
            lastWasDigit = digit
        }
        return sb.toString()
    }
}
