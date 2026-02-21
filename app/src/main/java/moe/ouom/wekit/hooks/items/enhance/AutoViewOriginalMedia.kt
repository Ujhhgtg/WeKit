package moe.ouom.wekit.hooks.items.enhance

import android.widget.Button
import androidx.core.view.isVisible
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天与消息/自动查看原图", desc = "在打开图片和视频时自动点击查看原图")
object AutoViewOriginalMedia : BaseSwitchFunctionHookItem(), IDexFind {
    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodSetImageHdImgBtnVisibility.find(dexKit, descriptors = descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("setHdImageActionDownloadable")
            }
        }

        methodCheckNeedShowOriginVideoBtn.find(dexKit, descriptors = descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("checkNeedShowOriginVideoBtn")
            }
        }

        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        listOf(methodSetImageHdImgBtnVisibility, methodCheckNeedShowOriginVideoBtn).forEach { method ->
            method.toDexMethod {
                hook {
                    afterIfEnabled { param ->
                        param.thisObject.asResolver().field {
                            type = Button::class
                        }.forEach {
                            it.get<Button>()?.let { imgBtn ->
                                if (imgBtn.isVisible) {
                                    val keywords = listOf(
                                        "查看原图", "Full Image",
                                        "查看原视频", "Original quality",
                                    )
                                    if (keywords.any { text -> imgBtn.text.contains(text, ignoreCase = true) }) {
                                        imgBtn.performClick()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun unload(classLoader: ClassLoader) {}
}