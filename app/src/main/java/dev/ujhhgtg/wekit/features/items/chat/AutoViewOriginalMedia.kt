package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.util.WeakHashMap

object AutoViewOriginalMedia : SwitchFeature() {
    override val technicalId = "自动查看原图"
    override val nameRes = R.string.feature_auto_view_original_media_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_view_original_media_description

    private const val MEDIA_DOWNLOAD_TEXT_CLASS =
        "com.tencent.mm.plugin.media.view.download.MediaDownloadText"

    private val lastLivePhotoBindings = WeakHashMap<Any, Any>()

    private val originalMediaKeywords =
        listOf(
            "查看原图",
            "Full Image",
            "View Full Image",
            "查看原视频",
            "Original quality",
            "View Full Video",
        )

    private val methodSetImageHdImgBtnVisibility by dexMethodFrom {
        val results =
            DexResolutionContext.findMethods {
                    declaredClass {
                        className = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                    }
                    usingEqString("setHdImageActionDownloadable")
                }
                .ifEmpty {
                    DexResolutionContext.findMethods {
                        declaredClass {
                            className = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                        }
                        usingEqString("setImageHdImgBtnVisibility")
                    }
                }
        results.single()
    }
    private val methodCheckNeedShowOriginVideoBtn by dexMethod {
        declaredClass {
            className = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
        }
        usingEqString("checkNeedShowOriginVideoBtn")
    }
    private val classMediaGalleryVideoBottomBarLayer by dexClass {
        usingEqString("MediaGallery.VideoBottomBarLayer")
    }
    private val classMediaGalleryChatLiveBottomBarLayer by
        dexClass(allowFailure = true) {
            method {
                name = "<init>"
                paramCount = 1
                usingEqString("MediaGallery.ChatLiveBottomBarLayer")
            }
        }
    private val methodBindMediaGalleryChatLiveBottomBar by classMediaGalleryChatLiveBottomBarLayer
        .dexMethod {
            declaredClass {
                className = classMediaGalleryChatLiveBottomBarLayer.data.name
            }
            paramCount = 1
            returnType = "void"
            usingEqString("bindContext")
            usingEqString("msgInfo")
        }
    private val methodUpdateMediaGalleryVideoOriginButton by
        classMediaGalleryChatLiveBottomBarLayer.dexMethod(allowFailure = true) {
            declaredClass {
                className = classMediaGalleryVideoBottomBarLayer.data.name
            }
            paramTypes("java.lang.String", "boolean")
            returnType = "void"
            usingEqString("getString(...)")
        }

    override fun onEnable() {
        listOf(
                methodSetImageHdImgBtnVisibility,
                methodCheckNeedShowOriginVideoBtn,
            )
            .forEach { method ->
                if (!method.isFound) return@forEach

                method.hookAfter {
                    clickVisibleOriginalMediaButtons(thisObject!!)
                }
            }

        if (methodUpdateMediaGalleryVideoOriginButton.isFound) {
            methodUpdateMediaGalleryVideoOriginButton.hookAfter {
                if (args[1] as Boolean) return@hookAfter

                val binding =
                    thisObject!!
                        .reflekt()
                        .firstField {
                            type { !it.isPrimitive }
                        }
                        .get()!!
                val originalVideoButton =
                    binding
                        .reflekt()
                        .firstField {
                            type = Button::class
                        }
                        .get() as Button
                clickOriginalMediaButton(originalVideoButton)
            }
            methodBindMediaGalleryChatLiveBottomBar.hookAfter {
                val layer = thisObject!!
                val bindContext = args[0]!!
                if (lastLivePhotoBindings[layer] === bindContext) return@hookAfter
                lastLivePhotoBindings[layer] = bindContext

                val binding =
                    layer
                        .reflekt()
                        .firstField {
                            type { candidate ->
                                candidate
                                    .reflekt()
                                    .fields {
                                        type = MEDIA_DOWNLOAD_TEXT_CLASS
                                    }
                                    .isNotEmpty()
                            }
                        }
                        .get()!!
                val originalImageControl =
                    binding
                        .reflekt()
                        .firstField {
                            type = MEDIA_DOWNLOAD_TEXT_CLASS
                        }
                        .get() as View

                originalImageControl.post {
                    if (
                        lastLivePhotoBindings[layer] === bindContext &&
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
        owner
            .reflekt()
            .fields {
                type = Button::class
            }
            .forEach {
                clickOriginalMediaButton(it.get() as Button)
            }
    }

    private fun clickOriginalMediaButton(button: Button) {
        if (button.isVisible && button.text.isOriginalMediaLabel()) {
            button.performClick()
        }
    }

    private fun View.hasVisibleOriginalMediaLabel(): Boolean =
        reflekt()
            .fields {
                type = TextView::class
            }
            .any {
                val textView = it.get() as TextView
                textView.isVisible && textView.text.isOriginalMediaLabel()
            }

    private fun CharSequence.isOriginalMediaLabel(): Boolean = originalMediaKeywords.any {
        contains(it, ignoreCase = true)
    }
}
