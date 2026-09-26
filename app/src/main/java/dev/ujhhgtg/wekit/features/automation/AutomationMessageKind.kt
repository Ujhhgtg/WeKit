package dev.ujhhgtg.wekit.features.automation

/** The message kinds automation rules distinguish (block rules, notification details). */
enum class AutomationMessageKind {
    TEXT, QUOTE, IMAGE, VIDEO, VOICE,

    /** Links, files, notes and forwarded chat records. */
    LINK,
    MUSIC, MINI_PROGRAM, CARD, EMOJI, RED_PACKET, TRANSFER, VOIP, LOCATION, SYSTEM, PAT, CHANNELS_VIDEO, UNKNOWN;

    /** Text and quotes: kinds that keyword rules look at. */
    val isTextLike: Boolean get() = this == TEXT || this == QUOTE

    companion object {
        private val APP_TYPE_REGEX = Regex("<appmsg[^>]*>.*?<type>(\\d+)</type>", RegexOption.DOT_MATCHES_ALL)
        private val TITLE_REGEX = Regex("<title>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</title>", RegexOption.DOT_MATCHES_ALL)

        /** Classifies a raw (AddMsg / message-table) type and its content, group prefix already stripped. */
        fun classify(rawType: Int, content: String): AutomationMessageKind = when (rawType) {
            1 -> TEXT
            3 -> IMAGE
            34 -> VOICE
            42 -> CARD
            43, 62 -> VIDEO
            47, 1048625 -> EMOJI
            48 -> LOCATION
            50, 52, 53 -> VOIP
            10002 -> if (content.contains("type=\"pat\"")) PAT else SYSTEM
            9999, 10000 -> SYSTEM
            49 -> classifyApp(APP_TYPE_REGEX.find(content)?.groupValues?.get(1)?.toIntOrNull())
            else -> classifyComposite(rawType)
        }

        private fun classifyApp(appType: Int?): AutomationMessageKind = when (appType) {
            57 -> QUOTE
            62 -> PAT
            2000 -> TRANSFER
            2001 -> RED_PACKET
            3, 76 -> MUSIC
            33, 36, 44 -> MINI_PROGRAM
            51, 63 -> CHANNELS_VIDEO
            8 -> EMOJI
            4, 5, 6, 19, 24, 74 -> LINK
            else -> UNKNOWN
        }

        /** Stored `message` rows use WeChat's composite codes for app messages. */
        private fun classifyComposite(code: Int): AutomationMessageKind = when (code) {
            822083633 -> QUOTE
            922746929 -> PAT
            419430449 -> TRANSFER
            436207665, 469762097 -> RED_PACKET
            16777265, 974127153, 1090519089, 805306417 -> LINK
            1040187441 -> MUSIC
            754974769, 771751985, 973078577 -> CHANNELS_VIDEO
            else -> UNKNOWN
        }

        /** The text keyword rules match: the message text, or a quote's reply title. */
        fun textOf(kind: AutomationMessageKind, content: String): String = when (kind) {
            TEXT -> content
            QUOTE -> TITLE_REGEX.find(content)?.groupValues?.get(1).orEmpty()
            else -> ""
        }
    }
}

/** Which message kinds a rule covers: all of them, or an explicit set. */
data class AutomationMessageTypeRule(
    val all: Boolean = true,
    val kinds: Set<AutomationMessageKind> = emptySet(),
) {
    fun contains(kind: AutomationMessageKind): Boolean = all || kind in kinds
}
