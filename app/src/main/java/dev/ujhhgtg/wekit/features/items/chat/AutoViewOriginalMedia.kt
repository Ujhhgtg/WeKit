package dev.ujhhgtg.wekit.features.items.chat

import android.widget.Button
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import org.luckypray.dexkit.DexKitBridge

@Feature(name = "自动查看原图", categories = ["聊天"], description = "在打开图片和视频时自动点击查看原图")
object AutoViewOriginalMedia : SwitchFeature(), IResolveDex {

    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        val results = dexKit.findMethod {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("setHdImageActionDownloadable")
            }
        }.ifEmpty {
            dexKit.findMethod {
                matcher {
                    declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                    usingEqStrings("setImageHdImgBtnVisibility")
                }
            }
        }
        methodSetImageHdImgBtnVisibility.setDescriptor(results.single())

        methodCheckNeedShowOriginVideoBtn.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("checkNeedShowOriginVideoBtn")
            }
        }
    }

    override fun onEnable() {
        listOf(
            methodSetImageHdImgBtnVisibility,
            methodCheckNeedShowOriginVideoBtn
        ).forEach { method ->
            if (method.isPlaceholder) return@forEach

            method.hookAfter {
                thisObject!!.reflekt().fields {
                    type = Button::class
                }.forEach {
                    (it.get() as Button?)?.let { imgBtn ->
                        if (imgBtn.isVisible) {
                            val keywords = listOf(
                                "查看原图", "Full Image",
                                "查看原视频", "Original quality",
                            )
                            if (keywords.any { text ->
                                    imgBtn.text.contains(
                                        text,
                                        ignoreCase = true
                                    )
                                }) {
                                imgBtn.performClick()
                            }
                        }
                    }
                }
            }
        }
    }
}
