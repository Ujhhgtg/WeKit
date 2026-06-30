package dev.ujhhgtg.wekit.utils.strings


private val MAP_REGEX = Regex("\\[[^]]+]")

private val RICH_CONTENT_MAP = mapOf(
    "[图片]" to "\uD83D\uDDBC\uFE0F",
    "[视频]" to "\uD83C\uDFA5",
    "[文件]" to "\uD83D\uDCC1",
    "[语音]" to "\uD83D\uDDE3\uFE0F",
    "[位置]" to "\uD83D\uDDFA\uFE0F",
    "[红包]" to "\uD83E\uDDE7",
    "[转账]" to "\uD83D\uDCB5",
    "[动画表情]" to "[贴纸表情]"
)

fun String.replaceRichContent(): String {
    return MAP_REGEX.replace(this) { matchResult ->
        RICH_CONTENT_MAP[matchResult.value] ?: matchResult.value
    }
}

private val EMOJI_MAP = mapOf(
    "[微笑]" to "🙂",
    "[撇嘴]" to "😕",
    "[色]" to "😍",
    "[发呆]" to "😳",
    "[得意]" to "😎",
    "[流泪]" to "😭",
    "[害羞]" to "😊",
    "[闭嘴]" to "🤐",
    "[睡]" to "😴",
    "[大哭]" to "😫",
    "[尴尬]" to "😅",
    "[发怒]" to "😡",
    "[调皮]" to "😜",
    "[呲牙]" to "😁",
    "[惊讶]" to "😱",
    "[难过]" to "🙁",
    "[囧]" to "😨",
    "[抓狂]" to "😫",
    "[吐]" to "🤮",
    "[偷笑]" to "🤭",
    "[愉快]" to "😊",
    "[白眼]" to "🙄",
    "[傲慢]" to "😏",
    "[困]" to "🥱",
    "[惊恐]" to "😨",
    "[憨笑]" to "😃",
    "[悠闲]" to "☕",
    "[咒骂]" to "🤬",
    "[疑问]" to "❓",
    "[嘘]" to "🤫",
    "[晕]" to "😵",
    "[衰]" to "☹️",
    "[骷髅]" to "💀",
    "[敲打]" to "🔨",
    "[再见]" to "👋",
    "[擦汗]" to "😓",
    "[抠鼻]" to "👃",
    "[鼓掌]" to "👏",
    "[坏笑]" to "😏",
    "[右哼哼]" to "😒",
    "[鄙视]" to "🙄",
    "[委屈]" to "🥺",
    "[快哭了]" to "😭",
    "[阴险]" to "😈",
    "[亲亲]" to "😘",
    "[可怜]" to "🥺",
    "[笑脸]" to "😄",
    "[生病]" to "😷",
    "[脸红]" to "😳",
    "[破涕为笑]" to "😂",
    "[恐惧]" to "😨",
    "[失望]" to "😞",
    "[无语]" to "😶",
    "[嘿哈]" to "🕺",
    "[捂脸]" to "🤦",
    "[奸笑]" to "😏",
    "[机智]" to "😏",
    "[皱眉]" to "😟",
    "[耶]" to "✌️",
    "[吃瓜]" to "🍉",
    "[加油]" to "💪",
    "[汗]" to "😓",
    "[天啊]" to "😱",
    "[Emm]" to "🤔",
    "[社会社会]" to "🤝",
    "[旺柴]" to "\uD83D\uDC36",
    "[好的]" to "👌",
    "[打脸]" to "🖐️",
    "[哇]" to "🤩",
    "[翻白眼]" to "🙄",
    "[666]" to "🤙",
    "[让我看看]" to "🫣",
    "[叹气]" to "😮‍💨",
    "[苦涩]" to "😭",
    "[嘴唇]" to "👄",
    "[爱心]" to "❤️",
    "[心碎]" to "💔",
    "[拥抱]" to "🤗",
    "[强]" to "👍",
    "[弱]" to "👎",
    "[握手]" to "🤝",
    "[胜利]" to "✌️",
    "[抱拳]" to "🙏",
    "[勾引]" to "☝️",
    "[拳头]" to "👊",
    "[OK]" to "👌",
    "[合十]" to "🙏",
    "[啤酒]" to "🍺",
    "[咖啡]" to "☕",
    "[蛋糕]" to "🎂",
    "[玫瑰]" to "🌹",
    "[凋谢]" to "🥀",
    "[菜刀]" to "🔪",
    "[炸弹]" to "💣",
    "[便便]" to "💩",
    "[月亮]" to "🌙",
    "[太阳]" to "☀️",
    "[庆祝]" to "🎉",
    "[礼物]" to "🎁",
    "[红包]" to "🧧",
    "[發]" to "🀅",
    "[福]" to "🧧",
    "[烟花]" to "🎆",
    "[爆竹]" to "🧨",
    "[猪头]" to "🐷",
    "[跳跳]" to "💃",
    "[发抖]" to "🫨",
    "[转圈]" to "🌀"
)

fun String.replaceEmojis(): String {
    return MAP_REGEX.replace(this) { matchResult ->
        EMOJI_MAP[matchResult.value] ?: matchResult.value
    }
}

private val WXID_PREFIX_REGEX = Regex("""^wxid_[^:]+:\n(.*)$""", setOf(RegexOption.DOT_MATCHES_ALL))

fun String.stripWxId(): String {
    val match = WXID_PREFIX_REGEX.find(this)
    return match?.groupValues?.get(1) ?: this
}
