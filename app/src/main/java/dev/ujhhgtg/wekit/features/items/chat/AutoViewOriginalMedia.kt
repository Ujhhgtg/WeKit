package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.util.WeakHashMap
import org.luckypray.dexkit.DexKitBridge

object AutoViewOriginalMedia : SwitchFeature(), IResolveDex {

    override val technicalId = "自动查看原图"
    override val nameRes = R.string.feature_auto_view_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_view_original_media_description

    private const val MEDIA_DOWNLOAD_TEXT_CLASS =
        "com.tencent.mm.plugin.media.view.download.MediaDownloadText"

    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()
    private val classMediaGalleryVideoBottomBarLayer by dexClass()
    private val methodUpdateMediaGalleryVideoOriginButton by dexMethod()
    private val classMediaGalleryChatLiveBottomBarLayer by dexClass()
    private val methodBindMediaGalleryChatLiveBottomBar by dexMethod()

    private val lastLivePhotoBindings = WeakHashMap<Any, Any>()

    private val originalMediaKeywords = listOf(
        "查看原图", "Full Image", "View Full Image",
        "查看原视频", "Original quality", "View Full Video",
    )

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

        classMediaGalleryVideoBottomBarLayer.find(dexKit) {
            matcher {
                usingEqStrings("MediaGallery.VideoBottomBarLayer")
            }
        }

        val chatLiveBottomBarLayers = dexKit.findClass {
            matcher {
                methods {
                    add {
                        name = "<init>"
                        paramCount = 1
                        usingEqStrings("MediaGallery.ChatLiveBottomBarLayer")
                    }
                }
            }
        }

        when (chatLiveBottomBarLayers.size) {
            0 -> {
                classMediaGalleryChatLiveBottomBarLayer.setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "chat live-photo bottom bar is absent; using common media controls",
                )
                methodUpdateMediaGalleryVideoOriginButton.setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "layered original-video controls are absent",
                )
                methodBindMediaGalleryChatLiveBottomBar.setPlaceholderDescriptor(
                    expectedFailure = true,
                    reason = "chat live-photo bottom bar is absent",
                )
                return
            }

            1 -> classMediaGalleryChatLiveBottomBarLayer.setDescriptor(
                chatLiveBottomBarLayers.single()
            )

            else -> error(
                "multiple MediaGallery.ChatLiveBottomBarLayer classes found: " +
                    chatLiveBottomBarLayers.joinToString { it.name }
            )
        }

        methodBindMediaGalleryChatLiveBottomBar.find(dexKit) {
            matcher {
                declaredClass(classMediaGalleryChatLiveBottomBarLayer.data.name)
                paramCount = 1
                returnType = "void"
                usingEqStrings("bindContext", "msgInfo")
            }
        }

        val updateVideoOriginButtonMethods = dexKit.findMethod {
            matcher {
                declaredClass(classMediaGalleryVideoBottomBarLayer.data.name)
                paramTypes("java.lang.String", "boolean")
                returnType = "void"
                usingEqStrings("getString(...)")
            }
        }

        when (updateVideoOriginButtonMethods.size) {
            0 -> methodUpdateMediaGalleryVideoOriginButton.setPlaceholderDescriptor(
                expectedFailure = true,
                reason = "layered original-video controls are absent",
            )

            1 -> methodUpdateMediaGalleryVideoOriginButton.setDescriptor(
                updateVideoOriginButtonMethods.single()
            )

            else -> error(
                "multiple layered original-video update methods found: " +
                    updateVideoOriginButtonMethods.joinToString { it.descriptor }
            )
        }
    }

    override fun onEnable() {
        listOf(
            methodSetImageHdImgBtnVisibility,
            methodCheckNeedShowOriginVideoBtn
        ).forEach { method ->
            if (method.isPlaceholder) return@forEach

            method.hookAfter {
                clickVisibleOriginalMediaButtons(thisObject!!)
            }
        }

        if (!methodUpdateMediaGalleryVideoOriginButton.isPlaceholder) {
            methodUpdateMediaGalleryVideoOriginButton.hookAfter {
                if (args[1] as Boolean) return@hookAfter

                val binding = thisObject!!.reflekt().firstField {
                    type { !it.isPrimitive }
                }.get()!!
                val originalVideoButton = binding.reflekt().firstField {
                    type = Button::class
                }.get() as Button
                clickOriginalMediaButton(originalVideoButton)
            }
            methodBindMediaGalleryChatLiveBottomBar.hookAfter {
                val layer = thisObject!!
                val bindContext = args[0]!!
                if (lastLivePhotoBindings[layer] === bindContext) return@hookAfter
                lastLivePhotoBindings[layer] = bindContext

                val binding = layer.reflekt().firstField {
                    type { candidate ->
                        candidate.reflekt().fields {
                            type = MEDIA_DOWNLOAD_TEXT_CLASS
                        }.isNotEmpty()
                    }
                }.get()!!
                val originalImageControl = binding.reflekt().firstField {
                    type = MEDIA_DOWNLOAD_TEXT_CLASS
                }.get() as View

                originalImageControl.post {
                    if (lastLivePhotoBindings[layer] === bindContext &&
                        originalImageControl.isShown &&
                        originalImageControl.isEnabled &&
                        originalImageControl.hasVisibleOriginalMediaLabel()
                    ) {
                        originalImageControl.performClick()
                    }
                }
            }
        }
    }

    private fun clickVisibleOriginalMediaButtons(owner: Any) {
        owner.reflekt().fields {
            type = Button::class
        }.forEach {
            clickOriginalMediaButton(it.get() as Button)
        }
    }

    private fun clickOriginalMediaButton(button: Button) {
        if (button.isVisible && button.text.isOriginalMediaLabel()) {
            button.performClick()
        }
    }

    private fun View.hasVisibleOriginalMediaLabel(): Boolean =
        reflekt().fields {
            type = TextView::class
        }.any {
            val textView = it.get() as TextView
            textView.isVisible && textView.text.isOriginalMediaLabel()
        }

    private fun CharSequence.isOriginalMediaLabel(): Boolean =
        originalMediaKeywords.any { contains(it, ignoreCase = true) }
}
