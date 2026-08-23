package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.android.showToast

object BlockAbnormalSizeStickers : SwitchFeature(), IResolveDex {

    override val technicalId = "拦截异常大小贴纸表情"
    override val nameRes = R.string.feature_block_abnormal_size_stickers_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_block_abnormal_size_stickers_description

    override fun onEnable() {
        ctorMmWxgfDrawable.hookBefore {
            val inputBytes = args[0] as? ByteArray? ?: return@hookBefore
            val magicBytes = "wxgf".toByteArray()

            val isWxgf = inputBytes.size >= magicBytes.size &&
                    magicBytes.indices.all { i -> inputBytes[i] == magicBytes[i] }

            if (isWxgf && inputBytes.size >= 11) {
                // Read 16-bit Big-Endian integers for width (bytes 7-8) and height (bytes 9-10)
                val width = inputBytes[7].toInt() and 0xFF shl 8 or (inputBytes[8].toInt() and 0xFF)
                val height = inputBytes[9].toInt() and 0xFF shl 8 or (inputBytes[10].toInt() and 0xFF)

                // If raw pixel data size (width * height * 4 bytes per pixel) exceeds 50MB
                if (width.toLong() * height.toLong() * 4L > 52_428_800L) {
                    showToast(localizedChatString(R.string.chat_abnormal_sticker_blocked))

                    // Patch the dimensions down to a safe 32x32 stub to prevent OOM/Exploits
                    inputBytes[7] = 0.toByte()
                    inputBytes[8] = 32.toByte()
                    inputBytes[9] = 0.toByte()
                    inputBytes[10] = 32.toByte()
                }
            }
        }
    }

    private val ctorMmWxgfDrawable by dexConstructor {
        searchPackages("com.tencent.mm.plugin.gif")
        matcher {
            usingEqStrings("MicroMsg.GIF.MMWXGFDrawable", "Cpan WXGF get option failed. result:%d")
        }
    }
}
