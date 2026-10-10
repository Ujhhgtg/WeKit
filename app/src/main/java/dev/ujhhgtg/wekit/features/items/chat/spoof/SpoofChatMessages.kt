package dev.ujhhgtg.wekit.features.items.chat.spoof

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.children
import androidx.exifinterface.media.ExifInterface
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit_note
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.data.entity.SpoofedChatMessageEntity
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.localizedChatString
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.android.postOnUiThread
import org.luckypray.dexkit.DexKitBridge
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Locally spoofs how text, image, video and transfer messages display. Every spoof is
 * display-only: message rows, the image gallery, the video player and the transfer detail page
 * render the spoofed value, while the stored message and anything sent stay untouched.
 */
@SuppressLint("ThreadConstraint")
object SpoofChatMessages : ClickableFeature(), IResolveDex, WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "伪造聊天消息"
    override val nameRes = R.string.feature_spoof_chat_messages_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_spoof_chat_messages_description

    private const val MENU_ITEM_ID = 777002

    // Tag keys outside every resource package id, like CustomLocalFriendAvatars' tags.
    private const val VIEW_TAG_ROW = 0x53504f01
    private const val VIEW_TAG_TEXT = 0x53504f02
    private const val VIEW_TAG_MEDIA = 0x53504f03

    private const val IMAGE_MVVM_VIEW = "com.tencent.mm.ui.chatting.viewitems.mvvmview.ChattingImgMvvmView"
    private const val VIDEO_MVVM_VIEW = "com.tencent.mm.ui.chatting.viewitems.mvvmview.ChattingVideoMvvmView"
    private const val STRETCH_FRAME_LAYOUT = "com.tencent.mm.view.StretchFrameLayout"
    private const val NEAT_TEXT_VIEW = "com.tencent.mm.ui.widget.MMNeat7extView"
    private const val TRANSFER_DETAIL_UI = "com.tencent.mm.plugin.remittance.ui.RemittanceDetailUI"
    private val THUMB_PLAYER_VIEWS = listOf(
        "com.tencent.mm.plugin.thumbplayer.view.ThumbPlayerVideoView",
        "com.tencent.mm.plugin.thumbplayer.view.ThumbPlayerSurfaceView",
    )

    private val methodGalleryImagePath by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.gallery")
        matcher {
            returnType = "java.lang.String"
            paramTypes(WeMessageApi.classMsgInfo.data.name, null, "boolean")
            usingEqStrings(
                "MicroMsg.ImageGalleryHolderImage",
                "getImagePath is null because of isTryToGetProgress %s img.isGetCompleted() %s",
            )
        }
    }
    private val methodGalleryPreviewPath by dexMethod {
        matcher {
            declaredClass = methodGalleryImagePath.data.declaredClassName
            returnType = "java.lang.String"
            paramTypes(WeMessageApi.classMsgInfo.data.name, null)
            usingEqStrings("get previewPath img not completed")
        }
    }
    private val methodGalleryCachedPath by dexMethod {
        matcher {
            declaredClass = methodGalleryImagePath.data.declaredClassName
            returnType = "java.lang.String"
            paramTypes(WeMessageApi.classMsgInfo.data.name, null, "boolean")
            addInvoke(methodGalleryImagePath.data.descriptor)
        }
    }
    private val methodGalleryInitImage by dexMethod {
        matcher {
            declaredClass = methodGalleryImagePath.data.declaredClassName
            returnType = "boolean"
            usingEqStrings("initImageView image : %s bigImgPath %s position : %s")
        }
    }
    private val methodChattingImageMask by dexMethod {
        matcher {
            modifiers(Modifier.STATIC)
            paramTypes("android.graphics.Bitmap", "int")
            returnType = "android.graphics.Bitmap"
            usingEqStrings("MicroMsg.BitmapUtil", "[createChattingImage] maskBitmap is null.")
        }
    }
    private val methodImageViewBind by dexMethod {
        matcher {
            declaredClass = IMAGE_MVVM_VIEW
            usingEqStrings("MicroMsg.ChattingImgMvvmView", "viewModel.thumbImgW: ")
        }
    }
    private val methodThumbnailSize by dexMethod()
    private val methodImageServiceGetter by dexMethod()
    private val classImageSizeService by dexClass()

    override fun resolveDex(dexKit: DexKitBridge) {
        val invokes = methodImageViewBind.data.invokes.distinctBy { it.descriptor }
        val size = invokes.single { method ->
            method.paramTypeNames == listOf("int", "int") && method.returnTypeName != "void" &&
                !method.returnTypeName.startsWith("android.") &&
                method.returnType?.fields?.count { !Modifier.isStatic(it.modifiers) && it.typeName == "int" } == 2
        }
        methodThumbnailSize.setDescriptor(size)
        methodImageServiceGetter.setDescriptor(invokes.single {
            Modifier.isStatic(it.modifiers) && it.paramTypeNames == listOf("java.lang.Class")
        })
        classImageSizeService.setDescriptor(size.declaredClass!!.interfaces.single { service ->
            // The host looks up an empty kernel-service marker, then casts it to the concrete
            // image service to call its sizing method.
            service.interfaces.any { it.name == methodImageServiceGetter.data.returnTypeName }
        })
    }

    /** Views a row registered for spoofing; cleared before the row binds another message. */
    private class RowBinding {
        val views = mutableListOf<WeakReference<View>>()
    }

    /** A text slot whose host-set text is replaced by [render]. */
    private class TextBinding(
        val key: SpoofKey,
        val render: (SpoofedChatMessageEntity, CharSequence?) -> CharSequence?,
    )

    /**
     * A thumbnail ImageView, its mask and its container. The row is sized for the spoofed media,
     * remembering the host's own sizes so they can be restored.
     */
    private class MediaBinding(val key: SpoofKey, content: ImageView, owner: View, mvvm: View) {
        val content = WeakReference(content)
        val owner = WeakReference(owner)
        val mvvm = WeakReference(mvvm)
        var mask: WeakReference<View>? = null
        var geometry: Geometry? = null
    }

    private class NativeSize(val view: WeakReference<View>, var width: Int, var height: Int)

    private class Geometry(
        val sizes: List<NativeSize>,
        val ownerWidth: Int,
        val ownerHeight: Int,
        var nativeConfig: Any?,
        var width: Int,
        var height: Int,
    )

    // Written and read on the main thread only, like every hooked view method.
    @SuppressLint("StaticFieldLeak")
    private var applyingImage: ImageView? = null
    private var applyingLayout = false
    private val adapters: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())
    private val sourceSizes = HashMap<String, Pair<Int, Int>>()
    private val thumbnails = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 12
    }

    private val neatViewClass by lazy { NEAT_TEXT_VIEW.toClass() }
    private val neatSetText: Method by lazy {
        neatViewClass.reflekt().firstMethod { parameters(CharSequence::class); returnType = Void.TYPE }.self
    }
    private val stretchLayoutClass by lazy { STRETCH_FRAME_LAYOUT.toClass() }
    private val getLayoutConfig: Method by lazy {
        stretchLayoutClass.reflekt().firstMethod { name = "getLayoutConfig"; parameters() }.self
    }
    private val setLayoutConfig: Method by lazy {
        stretchLayoutClass.reflekt().firstMethod { name = "setLayoutConfig"; parameterCount = 1 }.self
    }

    private val idContent by lazy { hostResource("bkm", "id") }
    private val idMask by lazy { hostResource("bko", "id") }
    private val idVideoInfoBar by lazy { hostResource("brf", "id") }
    private val idTransferAmount by lazy { hostResource("a48", "id") }
    private val idTransferDetailAmount by lazy { hostResource("lvk", "id") }
    private val drawableImageMask by lazy { hostResource("bzt", "drawable") }

    override fun onClick(context: ComponentActivity) {
        context.startActivity(Intent(context, SpoofChatMessagesActivity::class.java))
    }

    override fun getMenuItems() = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            MENU_ITEM_ID,
            localizedChatString(R.string.chat_spoof_message_menu),
            EditIcon,
            MaterialSymbols.Outlined.Edit_note,
            { msg -> SpoofKind.of(msg) != null },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { _, chattingContext, msg ->
            SpoofMessageEditor.show(chattingContext.activity, SpoofTarget.of(msg))
        }
    )

    override fun onEnable() {
        SpoofedMessageStore.ensureLoaded()
        WeChatMessageContextMenuApi.addProvider(this)

        WeMessageApi.methodChattingDataAdapterOnBindViewHolder.hookBefore {
            unbindRow(itemView(args[0]!!))
        }
        WeMessageApi.methodChattingDataAdapterOnBindViewHolder.hookAfter {
            val adapter = thisObject!!
            adapters += adapter
            if (SpoofedMessageStore.isEmpty) return@hookAfter
            val item = adapter.reflekt().firstMethod {
                name = "getItem"
                parameters(Int::class)
                superclass()
            }.invoke(args[1] as Int)!!
            val msg = MessageInfo(item)
            val spoof = SpoofedMessageStore.find(msg) ?: return@hookAfter
            bindRow(itemView(args[0]!!), spoof)
        }

        // Both overrides: the one-argument setter also hands the text to accessibility and the
        // selection listener, which must see the same text as the laid-out view.
        neatSetText.hookBefore { replaceText(thisObject as View, args) }
        neatViewClass.reflekt().firstMethod {
            parameters(CharSequence::class.java, TextView.BufferType::class.java, Boolean::class.javaObjectType)
        }.hookBefore { replaceText(thisObject as View, args) }
        TextView::class.java.reflekt().firstMethod {
            name = "setText"
            parameters(CharSequence::class, TextView.BufferType::class)
        }.hookBefore { replaceText(thisObject as View, args) }

        ImageView::class.java.reflekt().firstMethod {
            name = "setImageDrawable"
            parameters(Drawable::class)
        }.hookBefore {
            val image = thisObject as ImageView
            if (image === applyingImage) return@hookBefore
            val binding = image.getTag(VIEW_TAG_MEDIA) as MediaBinding? ?: return@hookBefore
            // The mask and the container share the binding; only the thumbnail is replaced.
            if (binding.content.get() !== image) return@hookBefore
            SpoofedMessageStore[binding.key] ?: return@hookBefore
            args[0] = renderMedia(binding)
        }
        View::class.java.reflekt().firstMethod {
            name = "setLayoutParams"
            parameters(ViewGroup.LayoutParams::class)
        }.hookBefore { keepSpoofedSize(thisObject as View, args[0] as ViewGroup.LayoutParams) }
        setLayoutConfig.hookBefore {
            if (applyingLayout) return@hookBefore
            val owner = thisObject as View
            val binding = owner.getTag(VIEW_TAG_MEDIA) as MediaBinding? ?: return@hookBefore
            val geometry = binding.geometry ?: return@hookBefore
            geometry.nativeConfig = args[0]
            if (args[0] != null) args[0] = layoutConfig(binding, geometry.width, geometry.height)
        }

        listOf(methodGalleryImagePath, methodGalleryPreviewPath, methodGalleryCachedPath).forEach { target ->
            target.hookBefore {
                // The host's preview path helper accepts a null message.
                val msg = args[0] ?: return@hookBefore
                result = spoofedImage(MessageInfo(msg))?.path ?: return@hookBefore
            }
        }
        val galleryMessageIndex = methodGalleryInitImage.method.parameterTypes.indexOf(WeMessageApi.classMsgInfo.clazz)
        methodGalleryInitImage.hookBefore {
            val msg = args[galleryMessageIndex] ?: return@hookBefore
            val file = spoofedImage(MessageInfo(msg)) ?: return@hookBefore
            // initImageView picks between its thumbnail and full image paths; replace both so
            // the real thumbnail never flashes first.
            args[2] = file.path
            args[3] = file.path
        }

        THUMB_PLAYER_VIEWS.forEach { className ->
            className.toClass().reflekt().firstMethod {
                name = "setVideoPath"
                parameters(String::class)
            }.hookBefore {
                val path = args[0] as String? ?: return@hookBefore
                val spoof = SpoofedMessageStore.findByVideoFileName(File(path).name) ?: return@hookBefore
                args[0] = SpoofedMessageStore.mediaFile(spoof).path
            }
        }

        TRANSFER_DETAIL_UI.toClass().hookAfterOnCreate {
            val activity = thisObject as Activity
            // The page also opens from bills and notifications without a chat message.
            val transferId = activity.intent.getStringExtra("transfer_id") ?: return@hookAfterOnCreate
            val spoof = SpoofedMessageStore.findByTransferId(transferId) ?: return@hookAfterOnCreate
            val amount = activity.findViewById<TextView>(idTransferDetailAmount)
            amount.setTag(VIEW_TAG_TEXT, TextBinding(spoof.key) { entity, text -> text?.withSpoofAmount(entity.amount!!) })
            amount.text = amount.text
        }
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
        adapters.clear()
        thumbnails.clear()
    }

    /** Rebinds visible chat rows after spoofs change, so they show the current values. */
    fun onSpoofsChanged() {
        if (!isActive) return
        postOnUiThread {
            thumbnails.clear()
            adapters.toList().forEach { adapter ->
                adapter.reflekt().firstMethod { name = "notifyDataSetChanged"; parameters(); superclass() }.invoke()
            }
        }
    }

    private fun itemView(holder: Any) = holder.reflekt().firstField { name = "itemView"; superclass() }.get() as View

    private fun bindRow(root: View, spoof: SpoofedChatMessageEntity) {
        val row = RowBinding()
        root.setTag(VIEW_TAG_ROW, row)
        when (spoof.spoofKind) {
            SpoofKind.TEXT -> {
                val content = root.tag!!.reflekt().firstMethod {
                    name = "getMainContainerView"
                    parameters()
                    superclass()
                }.invoke() as View
                row.bindText(content, spoof.key) { entity, _ -> entity.text!! }
                neatSetText.invoke(content, spoof.text)
            }
            SpoofKind.IMAGE -> {
                // Media-group rows lay several messages out in their own views; there only the
                // gallery shows the spoof.
                val mvvm = root.findDescendant(IMAGE_MVVM_VIEW) ?: return
                val content = mvvm.reflekt().firstMethod { name = "getMainContentIv" }.invoke() as ImageView
                row.bindMedia(spoof.key, content, mvvm)
            }
            SpoofKind.VIDEO -> {
                // As for images, media-group rows only spoof playback.
                val mvvm = root.findDescendant(VIDEO_MVVM_VIEW) ?: return
                row.bindMedia(spoof.key, mvvm.findViewById(idContent), mvvm)
                // The info bar holds the size label, then the duration label.
                val duration = mvvm.findViewById<ViewGroup>(idVideoInfoBar).children.filterIsInstance<TextView>().last()
                row.bindText(duration, spoof.key) { entity, text ->
                    // The host clears the label while the video is being sent.
                    if (text.isNullOrEmpty()) text else formatSpoofDuration(entity.videoDurationSeconds!!)
                }
                duration.text = duration.text
            }
            SpoofKind.TRANSFER -> {
                val amount = root.findViewById<TextView>(idTransferAmount)
                row.bindText(amount, spoof.key) { entity, text ->
                    // Group transfers prefix the amount with the receiver; replace only the amount.
                    text?.replacing(entity.original!!, formatSpoofAmount(entity.original, entity.amount!!))
                }
                amount.text = amount.text
            }
        }
    }

    private fun RowBinding.bindText(
        view: View,
        key: SpoofKey,
        render: (SpoofedChatMessageEntity, CharSequence?) -> CharSequence?,
    ) {
        view.setTag(VIEW_TAG_TEXT, TextBinding(key, render))
        views += WeakReference(view)
    }

    private fun RowBinding.bindMedia(key: SpoofKey, content: ImageView, mvvm: View) {
        val owner = content.parent as View
        val binding = MediaBinding(key, content, owner, mvvm)
        content.setTag(VIEW_TAG_MEDIA, binding)
        owner.setTag(VIEW_TAG_MEDIA, binding)
        views += WeakReference(content)
        applyingImage = content
        try {
            content.setImageDrawable(renderMedia(binding))
        } finally {
            applyingImage = null
        }
    }

    private fun unbindRow(root: View) {
        val row = root.getTag(VIEW_TAG_ROW) as RowBinding? ?: return
        root.setTag(VIEW_TAG_ROW, null)
        row.views.mapNotNull { it.get() }.forEach { view ->
            view.setTag(VIEW_TAG_TEXT, null)
            val binding = view.getTag(VIEW_TAG_MEDIA) as MediaBinding? ?: return@forEach
            // Untag first so restoring the host's sizes isn't taken for a new host size.
            listOfNotNull(binding.content.get(), binding.owner.get(), binding.mask?.get()).forEach {
                it.setTag(VIEW_TAG_MEDIA, null)
            }
            restoreGeometry(binding)
        }
    }

    private fun replaceText(view: View, args: Array<Any?>) {
        val binding = view.getTag(VIEW_TAG_TEXT) as TextBinding? ?: return
        val spoof = SpoofedMessageStore[binding.key] ?: return
        args[0] = binding.render(spoof, args[0] as CharSequence?)
    }

    private fun spoofedImage(msg: MessageInfo): File? {
        val spoof = SpoofedMessageStore.find(msg)?.takeIf { it.spoofKind == SpoofKind.IMAGE } ?: return null
        return SpoofedMessageStore.mediaFile(spoof)
    }

    /** The spoofed thumbnail, at the size the host would give an image of the same shape. */
    private fun renderMedia(binding: MediaBinding): Drawable {
        val spoof = SpoofedMessageStore[binding.key]!!
        val file = SpoofedMessageStore.previewFile(spoof)
        val (sourceWidth, sourceHeight) = sourceSize(file)
        val service = methodImageServiceGetter.method.invoke(null, classImageSizeService.clazz)!!
        val size = methodThumbnailSize.method.invoke(service, sourceWidth, sourceHeight)!!.reflekt()
        val width = size.getField("a") as Int
        val height = size.getField("b") as Int
        updateGeometry(binding, width, height)
        val content = binding.content.get()!!
        return thumbnail(file, width, height).toDrawable(content.resources)
    }

    private fun sourceSize(file: File): Pair<Int, Int> = sourceSizes.getOrPut(file.path) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val transposed = when (
            ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        ) {
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE -> true
            else -> false
        }
        if (transposed) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
    }

    private fun thumbnail(file: File, width: Int, height: Int): Bitmap {
        val cacheKey = "${file.path}|$width|$height"
        // The host may recycle bitmaps of drawables it drops.
        thumbnails[cacheKey]?.takeIf { !it.isRecycled }?.let { return it }
        val (sourceWidth, sourceHeight) = sourceSize(file)
        // ImageDecoder applies the EXIF orientation; sampling keeps at least the target size.
        val source = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            var sample = 1
            while (sourceWidth / (sample * 2) >= width && sourceHeight / (sample * 2) >= height) sample *= 2
            decoder.setTargetSampleSize(sample)
        }
        val cropped = createBitmap(width, height)
        val scale = max(width.toFloat() / source.width, height.toFloat() / source.height)
        val left = (width - source.width * scale) / 2f
        val top = (height - source.height * scale) / 2f
        Canvas(cropped).drawBitmap(
            source, null, RectF(left, top, left + source.width * scale, top + source.height * scale),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        // The host's own chat-image mask, as its thumbnail pipeline applies.
        val masked = methodChattingImageMask.method.invoke(null, cropped, drawableImageMask) as Bitmap
        thumbnails[cacheKey] = masked
        return masked
    }

    private fun updateGeometry(binding: MediaBinding, width: Int, height: Int) {
        val owner = binding.owner.get()!!
        var geometry = binding.geometry
        val firstApplication = geometry == null
        if (geometry == null) {
            val content = binding.content.get()!!
            val mask = owner.findViewById<View>(idMask)
            mask.setTag(VIEW_TAG_MEDIA, binding)
            binding.mask = WeakReference(mask)
            geometry = Geometry(
                sizes = listOf(content, mask).map {
                    NativeSize(WeakReference(it), it.layoutParams.width, it.layoutParams.height)
                },
                ownerWidth = owner.layoutParams.width,
                ownerHeight = owner.layoutParams.height,
                nativeConfig = if (stretchLayoutClass.isInstance(owner)) getLayoutConfig.invoke(owner) else null,
                width = width,
                height = height,
            )
            binding.geometry = geometry
        }
        val changed = firstApplication || geometry.width != width || geometry.height != height
        geometry.width = width
        geometry.height = height
        applyingLayout = true
        try {
            geometry.sizes.forEach { saved ->
                val view = saved.view.get() ?: return@forEach
                if (view.layoutParams.width != width || view.layoutParams.height != height) {
                    view.layoutParams = view.layoutParams.apply { this.width = width; this.height = height }
                }
            }
            // Before the host's first config the stretch layout is unconfigured; it is replaced on arrival.
            if (changed && geometry.nativeConfig != null) {
                setLayoutConfig.invoke(owner, layoutConfig(binding, width, height))
            }
        } finally {
            applyingLayout = false
        }
    }

    private fun restoreGeometry(binding: MediaBinding) {
        val geometry = binding.geometry ?: return
        binding.geometry = null
        geometry.sizes.forEach { saved ->
            val view = saved.view.get() ?: return@forEach
            view.layoutParams = view.layoutParams.apply { width = saved.width; height = saved.height }
        }
        val owner = binding.owner.get() ?: return
        if (stretchLayoutClass.isInstance(owner)) {
            setLayoutConfig.invoke(owner, geometry.nativeConfig)
            // An unset config never sized the container; undo the sizes the spoofed config set.
            if (geometry.nativeConfig == null) {
                owner.layoutParams = owner.layoutParams.apply {
                    width = geometry.ownerWidth
                    height = geometry.ownerHeight
                }
            }
        }
    }

    /** Keeps the host from resizing a spoofed thumbnail, while recording the size it asked for. */
    private fun keepSpoofedSize(view: View, params: ViewGroup.LayoutParams) {
        if (applyingLayout) return
        val binding = view.getTag(VIEW_TAG_MEDIA) as MediaBinding? ?: return
        val geometry = binding.geometry ?: return
        // The container shares the binding but keeps the host's size.
        val size = geometry.sizes.firstOrNull { it.view.get() === view } ?: return
        if (params.width != geometry.width || params.height != geometry.height) {
            size.width = params.width
            size.height = params.height
        } else if (view !== geometry.sizes.first().view.get()) {
            // The mask copied the spoofed thumbnail size; its own size is the thumbnail's.
            size.width = geometry.sizes.first().width
            size.height = geometry.sizes.first().height
        }
        params.width = geometry.width
        params.height = geometry.height
    }

    /** The host's stretch layout config for a thumbnail of [width] × [height]. */
    private fun layoutConfig(binding: MediaBinding, width: Int, height: Int): Any {
        val mvvm = binding.mvvm.get()!!
        return mvvm.javaClass.reflekt().firstMethod {
            parameters(Int::class, Int::class)
            returnType = getLayoutConfig.returnType
        }.invoke(mvvm, width, height)!!
    }

    private fun View.findDescendant(className: String): View? {
        if (javaClass.name == className) return this
        if (this is ViewGroup) for (child in children) child.findDescendant(className)?.let { return it }
        return null
    }

    private fun CharSequence.replacing(old: String, new: String): CharSequence {
        val start = indexOf(old)
        if (start < 0) return this
        return SpannableStringBuilder(this).replace(start, start + old.length, new)
    }

    @Suppress("DiscouragedApi")
    private fun hostResource(name: String, type: String): Int =
        HostInfo.application.resources.getIdentifier(name, type, PackageNames.WECHAT)
            .also { check(it != 0) { "missing host $type/$name" } }
}
