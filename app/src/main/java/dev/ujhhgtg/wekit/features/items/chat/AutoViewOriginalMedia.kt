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
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.dexkit.matcher.TypeSpec
import dev.ujhhgtg.wekit.dexkit.matcher.StringSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameMatch
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.dsl.find
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

    override fun resolveDex() {
        val results = DexResolutionContext.matcher.findMethods(
            MethodSpec(
                declaredClass = ClassSpec(
                    className = NameSpec("com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"),
                ),
                usingStrings = listOf(
                    StringSpec("setHdImageActionDownloadable", NameMatch.Equals),
                ),
            ),
        ).ifEmpty {
            DexResolutionContext.matcher.findMethods(
                MethodSpec(
                    declaredClass = ClassSpec(
                        className = NameSpec("com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"),
                    ),
                    usingStrings = listOf(
                        StringSpec("setImageHdImgBtnVisibility", NameMatch.Equals),
                    ),
                ),
            )
        }
        methodSetImageHdImgBtnVisibility.setDescriptor(results.single())

        methodCheckNeedShowOriginVideoBtn.find(
            MethodSpec(
                declaredClass = ClassSpec(
                    className = NameSpec("com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"),
                ),
                usingStrings = listOf(
                    StringSpec("checkNeedShowOriginVideoBtn", NameMatch.Equals),
                ),
            ),
        )

        classMediaGalleryVideoBottomBarLayer.find(
            ClassSpec(
                usingStrings = listOf(
                    StringSpec("MediaGallery.VideoBottomBarLayer", NameMatch.Equals),
                ),
            ),
        )

        val chatLiveBottomBarLayers = DexResolutionContext.matcher.findClasses(
            ClassSpec(
                methods = listOf(
                    MethodSpec(
                        name = NameSpec("<init>"),
                        paramCount = 1..1,
                        usingStrings = listOf(
                            StringSpec("MediaGallery.ChatLiveBottomBarLayer", NameMatch.Equals),
                        ),
                    ),
                ),
            ),
        )

        when (chatLiveBottomBarLayers.size) {
            0 -> {
                classMediaGalleryChatLiveBottomBarLayer.markExpectedFailure()
                methodUpdateMediaGalleryVideoOriginButton.markExpectedFailure()
                methodBindMediaGalleryChatLiveBottomBar.markExpectedFailure()
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

        methodBindMediaGalleryChatLiveBottomBar.find(
            MethodSpec(
                declaredClass = ClassSpec(
                    className = NameSpec(classMediaGalleryChatLiveBottomBarLayer.data.name),
                ),
                paramCount = 1..1,
                returnType = ClassSpec(className = NameSpec("void")),
                usingStrings = listOf(
                    StringSpec("bindContext", NameMatch.Equals),
                    StringSpec("msgInfo", NameMatch.Equals),
                ),
            ),
        )

        val updateVideoOriginButtonMethods = DexResolutionContext.matcher.findMethods(
            MethodSpec(
                declaredClass = ClassSpec(
                    className = NameSpec(classMediaGalleryVideoBottomBarLayer.data.name),
                ),
                paramTypes = listOf(TypeSpec("java.lang.String"), TypeSpec("boolean")),
                returnType = ClassSpec(className = NameSpec("void")),
                usingStrings = listOf(
                    StringSpec("getString(...)", NameMatch.Equals),
                ),
            ),
        )

        when (updateVideoOriginButtonMethods.size) {
            0 -> methodUpdateMediaGalleryVideoOriginButton.markExpectedFailure()

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
            if (!method.isFound) return@forEach

            method.hookAfter {
                clickVisibleOriginalMediaButtons(thisObject!!)
            }
        }

        if (methodUpdateMediaGalleryVideoOriginButton.isFound) {
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
