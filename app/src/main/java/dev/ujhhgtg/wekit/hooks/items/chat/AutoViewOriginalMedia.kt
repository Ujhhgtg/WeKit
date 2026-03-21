package dev.ujhhgtg.wekit.hooks.items.chat

import android.widget.Button
import androidx.core.view.isVisible
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "聊天/自动查看原图", desc = "在打开图片和视频时自动点击查看原图")
object AutoViewOriginalMedia : SwitchHookItem(), IResolvesDex {

    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        if (!methodSetImageHdImgBtnVisibility.find(dexKit, descriptors, throwOnFailure = false) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("setHdImageActionDownloadable")
            }
        }) {
            descriptors += "${nameof(AutoViewOriginalMedia)}:${nameof(methodSetImageHdImgBtnVisibility)}" to
                    "Lcom/tencent/mm/ui/LauncherUI;->()Lcom/tencent/mm/ui/LauncherUI;"
        }

        methodCheckNeedShowOriginVideoBtn.find(dexKit, descriptors) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("checkNeedShowOriginVideoBtn")
            }
        }

        return descriptors
    }

    override fun onEnable() {
        listOf(
            methodSetImageHdImgBtnVisibility,
            methodCheckNeedShowOriginVideoBtn
        ).forEach { method ->
            if (method.getDescriptorString() == "Lcom/tencent/mm/ui/LauncherUI;->()Lcom/tencent/mm/ui/LauncherUI;")
                return@forEach

            method.hookAfter { param ->
                param.thisObject.asResolver().field {
                    type = Button::class
                }.forEach {
                    it.get<Button>()?.let { imgBtn ->
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
