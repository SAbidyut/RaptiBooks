package com.raptibooks.core.structure.analysis

/** Text patterns used by the structure analysis (English + Bangla). */
object Patterns {
    private const val NUM = "[0-9০-৯]"

    private const val CAPTION_WORDS =
        "fig\\.?|figure|chart|diagram|graph|plate|illustration|image|photo|picture|map|table|exhibit|" +
            "চিত্র|ছবি|রেখাচিত্র|নকশা|মানচিত্র|সারণি|সারণী|টেবিল|ডায়াগ্রাম|লেখচিত্র"

    /** "Figure 1.6 shows the voltage…": a sentence about a figure, not its caption. */
    private const val REFERENCE =
        "\\s*(?:fig\\.?|figure|table)\\s*$NUM+(?:[.\\-]$NUM+)*(?:\\s*\\([a-z]\\))?\\s+" +
            "(?:shows?|illustrates|depicts|is|are|was|gives|presents|represents|displays|contains|has|can|will|summarizes|lists|compares|provides)\\b"

    /** "Figure 3:", "Fig. 2.1", "চিত্র ৪ -", "Table 1." */
    val caption = Regex(
        "^(?!$REFERENCE)(?:\\s*(?:$CAPTION_WORDS)\\s*(?:$NUM+(?:[.\\-]$NUM+)*|[IVXivx]+)?\\s*[:.।\\-–—)]|" +
            "\\s*(?:$CAPTION_WORDS)\\s+$NUM+)",
        RegexOption.IGNORE_CASE,
    )

    /** "Table 3", "সারণি ৪.৫:" — a number or a colon must follow ("সারণির শেষ সারি…" is prose). */
    val tableCaption = Regex("^\\s*(?:table|সারণি|সারণী|টেবিল)\\s*(?:$NUM|[:.।\\-–—])", RegexOption.IGNORE_CASE)

    /** Bullet or enumerator at the start of a line, followed by whitespace. */
    val listMarker = Regex(
        "^\\s*(" +
            "[•●○◦▪▫■□♦◆◇►▸▹➢➤✓✔☐☑❖✦✧*·]" +
            "|\\*?\\(?$NUM{1,3}[.)।]" + // ("*3." marks a challenging exercise)
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
            "এক|দুই|তিন|চার|পাঁচ|ছয়|সাত|আট|নয়|দশ|প্রথম|দ্বিতীয়|তৃতীয়|চতুর্থ|পঞ্চম)?" +
            // Not "অধ্যায় ১৬-এর…" or "অধ্যায় ৭.১০-এ…": a reference inside a sentence.
            "(?=[\\s:–—]|\\.(?![0-9০-৯])|-(?![\\u0980-\\u09FF])|$)",
        RegexOption.IGNORE_CASE,
    )

    val frontMatter = Regex("^\\s*(?:$FRONT_MATTER_WORDS)\\s*[:.]?\\s*$", RegexOption.IGNORE_CASE)

    /**
     * Table-of-contents line: a title, a dot leader and a page number
     * ("অধ্যায় ০২ টুলস.........13"). Group 1 = title, group 2 = page.
     */
    val tocEntry = Regex("^\\s*(.*?\\S)\\s*(?:[.·…‥_]\\s?){4,}\\s*($NUM{1,4}|[ivxlcdm]{1,6})\\s*$", RegexOption.IGNORE_CASE)

    /** Title of a printed table of contents ("Contents", "সূচিপত্র", "Index" as Bangla books call it). */
    val contentsTitle = Regex(
        "^\\s*(?:contents|table of contents|content|index|সূচিপত্র|সূচীপত্র|সূচি|সূচী|বিষয়সূচি|বিষয়সূচী|বিষয়সূচি|পাঠসূচি)\\s*[:ঃ]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Labels of worked examples and exercises ("Example 1.4", "Practice Problem 2.1", "উদাহরণ ৭.৬"). */
    val exampleLabel = Regex(
        "^\\s*(?:example|practice problem|problem|exercise|solution|answer|উদাহরণ|অনুশীলনী|সমাধান|সমস্যা|প্রশ্ন)" +
            "\\s*(?:$NUM+(?:[.\\-]$NUM+)*)?\\s*[:ঃ]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** A page number printed in a table of contents. */
    val contentsPage = Regex("^\\s*($NUM{1,4}|[ivxlc]{1,6})\\s*$", RegexOption.IGNORE_CASE)

    /** An enumerator inside a line, e.g. the "(খ)" of "(ক) তিন   (খ) চার" (multiple-choice options). */
    val inlineEnumerator = Regex("(?<=\\s)(\\(?(?:[ক-হ]|[a-hA-H]|$NUM{1,2})\\))\\s+")

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
