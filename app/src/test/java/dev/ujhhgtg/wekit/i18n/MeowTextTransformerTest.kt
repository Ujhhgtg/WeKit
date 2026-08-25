package dev.ujhhgtg.wekit.i18n

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MeowTextTransformerTest {
    @Test
    fun insertsMeowBeforeTrailingPunctuation() {
        val cases = mapOf(
            "加载中..." to "加载中喵...",
            "确定。" to "确定喵。",
            "完成？！" to "完成喵？！",
            "“完成！”" to "“完成喵！”",
            "..." to "喵...",
            "没有标点" to "没有标点喵",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, MeowTextTransformer.transform(source), source)
        }
    }

    @Test
    fun insertsPanguSpaceBetweenAsciiLettersOrDigitsAndMeow() {
        val cases = mapOf(
            "处理数量: 3" to "处理数量: 3 喵",
            "GitHub" to "GitHub 喵",
            "GitHub..." to "GitHub 喵...",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, MeowTextTransformer.transform(source), source)
        }
    }

    @Test
    fun keepsTrailingPunctuationAndItsLeadingSpaceAfterMeow() {
        val cases = mapOf(
            "（一段文本）" to "（一段文本喵）",
            "一段文本（）" to "一段文本喵（）",
            "一段文本 ()" to "一段文本喵 ()",
        )

        cases.forEach { (source, expected) ->
            assertEquals(expected, MeowTextTransformer.transform(source), source)
        }
    }

    @Test
    fun treatsDegreeSignAsPartOfWesternTextBeforeMeow() {
        assertEquals("体感 33° 喵", MeowTextTransformer.transform("体感 33°"))
    }

    @Test
    fun preservesTrailingWhitespaceAndLeavesBlankTextAlone() {
        assertEquals("加载中喵...  \n", MeowTextTransformer.transform("加载中...  \n"))
        assertEquals("完成喵\u00A0\u202F", MeowTextTransformer.transform("完成\u00A0\u202F"))
        assertEquals("", MeowTextTransformer.transform(""))
        assertEquals(" \n", MeowTextTransformer.transform(" \n"))
        assertEquals("\u00A0\u202F", MeowTextTransformer.transform("\u00A0\u202F"))
    }

    @Test
    fun doesNotAppendASecondMeowToAlreadyTransformedText() {
        assertEquals("加载中喵...", MeowTextTransformer.transform("加载中喵..."))
        assertEquals("完成喵  ", MeowTextTransformer.transform("完成喵  "))
        assertEquals("GitHub 喵...", MeowTextTransformer.transform("GitHub 喵..."))
    }
}
