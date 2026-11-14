package dev.ujhhgtg.wekit.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [EdgeTtsDrm] 的固定向量移植测试。
 *
 * 期望值由 edge-tts 7.3.1 的 `edge_tts.drm.generate_sec_ms_gec_v2(unix_timestamp)` 生成: 算法 (魔改 SHA-256 +
 * CS64) 或时间 窗口处理一旦改动, 这些向量就会失效, 说明移植需要与上游同步更新。
 */
class EdgeTtsDrmTest {
    private val trustedClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    @Test
    fun matchesUpstreamV2Vectors() {
        val vectors =
            listOf(
                // Unix 纪元起点。
                0.0 to
                    "52BC7A17B50DFEC9-" +
                        "28C4394775C8751CD2F6859C030C06DB1489ACAB3B3A7651831FC26895177ED6",
                // 同一 5 分钟窗口内的整数 / 带小数时间戳必须得到同一个 token。
                1761393000.0 to
                    "B53ABE6381B6A3A5-" +
                        "C4AC4EA99251742335FB6F493B8E5EEDDC91B66B6EA91D0E21941EFC1DA1AFDF",
                1761393123.456 to
                    "B53ABE6381B6A3A5-" +
                        "C4AC4EA99251742335FB6F493B8E5EEDDC91B66B6EA91D0E21941EFC1DA1AFDF",
                1761393199.999 to
                    "B53ABE6381B6A3A5-" +
                        "C4AC4EA99251742335FB6F493B8E5EEDDC91B66B6EA91D0E21941EFC1DA1AFDF",
                // 下一个 5 分钟窗口。
                1761393300.0 to
                    "B059F2B091355DE3-" +
                        "85C764EEA300F06B9AD2F33069CCF62F405ACED09FA7F3D1C722553F6E6114C4",
                // 带小数且不在窗口起始点的时间戳。
                1782000000.987654 to
                    "3FFC0BD25AED7B3F-" +
                        "19A416253ACE59E9003EC439D85888CE180747F528FFDD96AA15FAA79810916E",
                // 远期时间戳, 确保 100ns ticks 的整数格式化在 2^53 之后仍正确。
                2500000000.0 to
                    "77E4A1CAF8365F3C-" +
                        "ABADFDC0AE77B9501DF18027293D8E1F1594CD8D28612E45459A8E2BC4E46D85",
            )

        for ((unixTimestamp, expected) in vectors) {
            assertEquals(
                expected,
                EdgeTtsDrm.generateSecMsGec(trustedClientToken, unixTimestamp),
                "unixTimestamp=$unixTimestamp",
            )
        }
    }

    @Test
    fun tokenUsesV2Shape() {
        val token = EdgeTtsDrm.generateSecMsGec(trustedClientToken, 1761393000.0)
        assertTrue(
            token.matches(Regex("[0-9A-F]{16}-[0-9A-F]{64}")),
            "unexpected token shape: $token",
        )
    }
}
