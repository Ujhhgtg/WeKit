package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.ImageView
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WeServiceApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.baseActivity
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.asPath
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.math.roundToInt
import org.luckypray.dexkit.DexKitBridge

object ViewStickerAsImage : SwitchFeature(), IResolveDex {

    override val technicalId = "表情消息以图片打开"
    override val nameRes = R.string.feature_view_sticker_as_image_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_view_sticker_as_image_description

    private const val TAG = "ViewStickerAsImage"

    private val methodEmojiClickHandler by dexMethod()
    private val methodEmojiClickEntry by dexMethod()
    private val methodEmojiResolverGetter by dexMethod()
    private val methodResolveEmojiInfo by dexMethod()
    private val methodGetEmojiDecryptPath by dexMethod()

    override fun onEnable() {
        methodEmojiClickEntry.hookBefore {
            val clickedView = args[0] as View
            val hostMessage = args[2]!!
            val messageInfo = MessageInfo(hostMessage)
            if (messageInfo.type?.isSticker != true) return@hookBefore

            val activity = resolveUsableActivity(clickedView) ?: return@hookBefore
            val path = resolveViewerPath(clickedView, hostMessage, messageInfo) ?: return@hookBefore
            if (startNativeImageViewer(activity, path)) {
                result = null
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodEmojiClickHandler.find(dexKit) {
            searchPackages("com.tencent.mm.ui.chatting.viewitems")
            matcher {
                paramCount = 1
                returnType = "void"
                usingEqStrings("MicroMsg.EmojiClickListener", "exit in teen mode")
            }
        }

        methodEmojiClickEntry.find(dexKit) {
            matcher {
                declaredClass(methodEmojiClickHandler.data.declaredClassName)
                paramTypes("android.view.View", null, null)
                returnType = "void"
            }
        }

        val resolveEmojiInfo = dexKit.findMethod {
            matcher {
                paramTypes(methodEmojiClickEntry.data.paramTypeNames[2])
                returnType = "com.tencent.mm.storage.emotion.EmojiInfo"
            }
        }.single { !Modifier.isStatic(it.modifiers) }
        methodResolveEmojiInfo.setDescriptor(resolveEmojiInfo)

        methodEmojiResolverGetter.find(dexKit) {
            searchPackages("com.tencent.mm.feature.emoji")
            matcher {
                paramCount = 0
                returnType = methodResolveEmojiInfo.data.declaredClassName
            }
        }

        methodGetEmojiDecryptPath.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.storage.emotion.EmojiInfo"
                paramCount = 0
                returnType = "java.lang.String"
                usingEqStrings(
                    "MicroMsg.emoji.EmojiInfo",
                    "[cpan] get icon path failed. product id and md5 are null.",
                    "decrypt/",
                    "getDecryptPath decrypt %s",
                )
            }
        }
    }

    private fun resolveUsableActivity(view: View): Activity? {
        val fromView = view.context.baseActivity
        if (fromView != null && !fromView.isFinishing && !fromView.isDestroyed) return fromView
        return getTopMostActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
    }

    private fun resolveWechatDecodedPath(hostMessage: Any): Path? {
        return try {
            val resolver = methodEmojiResolverGetter.method
                .invoke(WeServiceApi.emojiFeatureService)!!
            val emojiInfo = methodResolveEmojiInfo.method
                .invoke(resolver, hostMessage)!!
            val path = (methodGetEmojiDecryptPath.method.invoke(emojiInfo) as String).asPath
            path.takeIf { it.isAbsolute && it.isRegularFile() && it.fileSize() > 0L }
        } catch (error: Exception) {
            WeLogger.e(TAG, "failed to resolve WeChat sticker decrypt path", error)
            null
        }
    }

    private fun resolveCachedGif(messageInfo: MessageInfo): Path? {
        val md5 = messageInfo.stickerMd5 ?: run {
            WeLogger.e(TAG, "failed to resolve sticker md5")
            return null
        }
        val directory = KnownPaths.moduleCache / "view-sticker-as-image" / "decoded"
        val destination = directory / "$md5.gif"
        if (!destination.isRegularFile() || destination.fileSize() <= 0L) {
            prunePreviewDirectory(directory, ".gif")
        }
        return WeMessageApi.decodeStickerToFile(md5, destination)
    }

    private fun prunePreviewDirectory(directory: Path, extension: String) {
        try {
            Files.createDirectories(directory)
            directory.listDirectoryEntries()
                .filter { it.isRegularFile() && it.name.endsWith(extension) }
                .sortedByDescending { Files.getLastModifiedTime(it).toMillis() }
                .drop(10)
                .forEach { it.deleteIfExists() }
        } catch (error: Exception) {
            WeLogger.w(TAG, "failed to prune sticker preview cache", error)
        }
    }

    private fun resolveViewerPath(
        clickedView: View,
        hostMessage: Any,
        messageInfo: MessageInfo,
    ): Path? = resolveWechatDecodedPath(hostMessage)
        ?: resolveCachedGif(messageInfo)
        ?: createSnapshot(clickedView)

    private fun createSnapshot(clickedView: View): Path? {
        val imageView = clickedView.findViewWhich {
            it is ImageView && it.drawable != null
        } as? ImageView ?: return null
        val drawable = imageView.drawable
        val sourceWidth = imageView.width.takeIf { it > 0 } ?: drawable.intrinsicWidth
        val sourceHeight = imageView.height.takeIf { it > 0 } ?: drawable.intrinsicHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null
        val scale = minOf(1.0, 2048.0 / maxOf(sourceWidth, sourceHeight))
        val outputWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val outputHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        val directory = KnownPaths.moduleCache / "view-sticker-as-image" / "snapshots"
        var output: Path? = null
        var bitmap: Bitmap? = null
        return try {
            prunePreviewDirectory(directory, ".png")
            output = Files.createTempFile(directory, "sticker-preview-", ".png")
            bitmap = Bitmap.createBitmap(
                outputWidth,
                outputHeight,
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            canvas.scale(
                outputWidth.toFloat() / sourceWidth,
                outputHeight.toFloat() / sourceHeight,
            )
            if (imageView.width > 0 && imageView.height > 0) {
                imageView.draw(canvas)
            } else {
                drawable.setBounds(0, 0, sourceWidth, sourceHeight)
                drawable.draw(canvas)
            }
            output.outputStream().buffered().use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            check(output.isRegularFile() && output.fileSize() > 0L) {
                "sticker snapshot is empty"
            }
            output
        } catch (error: Exception) {
            output?.deleteIfExists()
            WeLogger.e(TAG, "failed to create sticker snapshot", error)
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun startNativeImageViewer(activity: Activity, imagePath: Path): Boolean {
        return try {
            activity.startActivity(
                Intent().apply {
                    component = ComponentName(activity.packageName, "com.tencent.mm.ui.tools.ShowImageUI")
                    putExtra("key_image_path", imagePath.absolutePathString())
                },
            )
            true
        } catch (error: Exception) {
            WeLogger.e(TAG, "failed to start WeChat image viewer", error)
            false
        }
    }
}
