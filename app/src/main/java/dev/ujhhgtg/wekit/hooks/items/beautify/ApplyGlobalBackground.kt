package dev.ujhhgtg.wekit.hooks.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HeaderViewListAdapter
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.get
import androidx.core.view.iterator
import androidx.core.view.postDelayed
import androidx.core.view.size
import coil3.load
import coil3.request.crossfade
import coil3.size.Scale
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.base.CustomViewPager
import com.tencent.mm.ui.conversation.ConversationListView
import com.tencent.mm.ui.conversation.MainUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge


@HookItem(path = "界面美化/应用全局背景", description = "将微信背景替换为图片或视频 (没写完)")
object ApplyGlobalBackground : ClickableHookItem(), IResolvesDex {

    private val TAG = This.Class.simpleName

//    private val THEME_PATH = (KnownPaths.moduleData / "theme").createDirectoriesNoThrow()

    private const val KEY_BACKGROUND_URI = "background_uri"
    private const val VIEW_TAG = 1426719277

    private fun createImageView(context: Context): ImageView {
        return applyToImageView(ImageView(context))
    }

    private fun applyToImageView(imageView: ImageView): ImageView {
        return imageView.apply {
            load(WePrefs.getString(KEY_BACKGROUND_URI) ?: error("Background is null")) {
                crossfade(true)
                scale(Scale.FILL)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    }

//    private fun applyToViewBackground(view: View, path: Path) {
//        val context = view.context
//        val imageLoader = ImageLoader(context)
//
//        imageLoader.enqueue(
//            ImageRequest.Builder(context)
//                .data(path.toFile())
//                .target { image ->
//                    val bitmap = image.toBitmap()
//                    view.background = bitmap.toDrawable(context.resources)
//                }
//                .build()
//        )
//    }

    override fun onEnable() {
        hookF()
        hookB() // ok
        hookC() // ok
        hookD()
        hookE()
        hookG()
        hookH()
    }

    @Suppress("ClassName")
    private data object VIEW_TAG_CONTENT

    private fun hookB() {
        View::class.resolve().firstMethod { name = "setBackgroundDrawable" }.hookBefore {
            val view = thisObject as? View? ?: return@hookBefore
            if (view.getTag(VIEW_TAG) != VIEW_TAG_CONTENT) return@hookBefore
            args[0] = null
        }
    }

    private fun hookC() {
        val weUiBounceViewV2 = "com.tencent.mm.ui.widget.pulldown.WeUIBounceViewV2".toClass().resolve()
        listOf(
            "setStart2EndBgColorByActionBar",
            "setEnd2StartBgColorByNavigationBar",
            "setStart2EndBgColor",
            "setEnd2StartBgColor",
            "setBgColor"
        ).forEach {
            weUiBounceViewV2.firstMethod { name = it }.hookBefore {
                val view = thisObject as? View? ?: return@hookBefore
                if (view.context !is LauncherUI) return@hookBefore
                args[0] = 0
                WeLogger.d(TAG, "WeUIBounceViewV2::$it")
            }
        }
    }

    private fun hookD() {
//        "com.tencent.mm.ui.chatting.ChattingUIFragment".toClass().asResolver()
//            .firstMethod { name = "dealContentView" }.hookAfter {
//                val viewGroup = thisObject as ViewGroup
//                val activity = viewGroup.context as Activity
//                when (activity.javaClass.name) {
//                    "com.tencent.mm.ui.LauncherUI" -> {
//                        val bgView = View(activity)
//                        applyToViewBackground(bgView, THEME_PATH / "bg_chat_actionbar.png")
//                        val chattingUiLayout = viewGroup.findViewWhich<ChattingUILayout> { view ->
//                            view is ChattingUILayout
//                        }!!
//                        setNullBg(chattingUiLayout)
//                        val childAt0 = chattingUiLayout[0] as ViewGroup
//                        setNullBgRecursively(childAt0)
//                        val parent = chattingUiLayout.parent as ViewGroup
//                        parent.addView(bgView, 0, ViewGroup.LayoutParams(-1, ))
//                    }
//
//                    "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI" -> {
//
//                    }
//
//                    else -> {
//
//                    }
//                }
//            }

        methodChattingBackgroundComponentInitBg.hookAfter {
            val imageView = thisObject.asResolver()
                .firstField { type = ImageView::class }.get() as? ImageView? ?: return@hookAfter

            val parent = imageView.parent as ViewGroup
            val imageView2 = parent[0] as ImageView
            if (imageView2.drawable is ColorDrawable) {
                applyToImageView(imageView2)
            }
        }

        "com.tencent.mm.ui.chatting.ChattingImageBGView".toClass().constructors.forEach {
            it.hookAfter {
                applyToImageView(thisObject as ImageView)
            }
        }

        "com.tencent.mm.ui.chatting.ChatFooterCustom".toClass().resolve()
            .firstConstructor { parameterCount = 3 }.hookAfter {
                val viewGroup = thisObject as ViewGroup
                viewGroup.postDelayed(100L) {
                    setNullBg(viewGroup)
                }
            }


    }

    private fun hookE() {
        HeaderViewListAdapter::class.resolve()
            .firstMethod { name = "getView" }.hookAfter {
                val view = result as View
                if (view.context !is LauncherUI || view !is ViewGroup) return@hookAfter

                val child = view.getChildAt(view.size - 1)
                if (child is ViewGroup) {
                    setNullBgRecursively(child)
                    if (view.findViewWithTag<View>(1426719263) == null) {
                        val list = mutableListOf<View>()
                        g(view, list)
                        list.firstOrNull()?.apply {
                            alpha = 0.0f
                            view.setTag(1426719263, this)
                        }
                    }
                }
            }

        ConversationListView::class.resolve()
            .firstMethod { name = "getEmptyFooter" }.hookAfter {
                val viewGroup = result as ViewGroup
                setNullBgRecursively(viewGroup)
            }

        MainUI::class.resolve()
            .firstMethod { parameters(Bundle::class) }.hookAfter {
                val thisObject = thisObject
                val conversationListView = thisObject.asResolver()
                    .firstField { type = "com.tencent.mm.ui.conversation.ConversationListView" }
                    .get() as? ConversationListView? ?: return@hookAfter
                setNullBg(conversationListView)
                (conversationListView.parent as ViewGroup).getChildAt(3).apply { setNullBg(this) }
                WeLogger.d(TAG, "MainUI::onCreate")
            }

        ConversationListView::class.resolve()
            .firstMethod { name = "attachViewToParent" }.hookAfter {
                thisObject.asResolver()
                    .field { type = View::class }
                    .forEach {
                        val view = it.get() as? View? ?: return@forEach
                        val lp = view.layoutParams
                        if (lp is FrameLayout.LayoutParams && lp.width == -1) {
                            setNullBg(view)
                        }
                    }
            }

        ConversationListView::class.java.constructors.forEach {
            it.hookBefore {
                thisObject.asResolver()
                    .field {
                        type = Paint::class
                        superclass()
                    }.forEach { field ->
                        field.set(ZeroColorPaint(0))
                    }
            }
        }

        "com.tencent.mm.plugin.multitask.ui.bg.DynamicBgContainer".toClass().resolve()
            .firstMethod {
                modifiers { it.contains(Modifiers.SYNCHRONIZED) }
                parameterCount = 0
            }.hookBefore {
                result = null
            }

        "com.tencent.mm.dynamicbackground.view.GradientColorBackgroundView".toClass().resolve()
            .firstMethod { name = "onDraw" }.hookBefore {
                result = null
            }

        "com.tencent.mm.plugin.appbrand.widget.desktop.AppBrandDesktopContainerView".toClass().resolve()
            .firstConstructor { parameterCount = 3 }.hookAfter {
                val containerView = thisObject as ViewGroup
                val child = containerView[0] as ViewGroup
                val imageView = createImageView(child.context)
                child.addView(imageView, 0, ViewGroup.LayoutParams(-1, -1))
            }

        ConversationListView::class.resolve()
            .firstMethod { name = "setFoldBanner" }.hookAfter {
                val banner = args[0] as ViewGroup
                setNullBgRecursively(banner)
                banner.background = (if (!banner.context.isDarkMode) -285212673 else -301989888).toDrawable()
            }
    }

    private fun hookF() {
        LauncherUI::class.resolve()
            .firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as Activity
                val viewPager = activity.rootView.findViewWhich<ViewGroup> { it is CustomViewPager }!!

                val lp = RelativeLayout.LayoutParams(-1, -1).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
                viewPager.addView(createImageView(activity), 0, lp)

                WeLogger.d(TAG, "LauncherUI::onResume")
            }
    }

    private fun hookG() {
        "com.tencent.mm.ui.MMActivity".toClass().resolve()
            .firstMethod { name = "initSwipeBack" }.hookBefore {
                val activity = thisObject as Activity
                if (activity.javaClass.name !in SPECIFIC_ACTIVITIES) return@hookBefore
                activity.asResolver()
                    .firstField {
                        name = "fixStatusbar"
                        superclass()
                    }.set(false)
            }
    }

    private val SPECIFIC_ACTIVITIES = listOf(
        "com.tencent.mm.plugin.setting.ui.setting.ColorfulChatroomQRCodeUI",
        "com.tencent.mm.chatroom.ui.ModRemarkRoomNameUI",
        "com.tencent.mm.ui.chatting.search.multi.FTSChattingConvMultiTabUI",
        "com.tencent.mm.ui.contact.ContactRemarkInfoModUI",
        "com.tencent.mm.ui.transmit.SelectConversationUI",
        "com.tencent.mm.plugin.profile.ui.ProfileSettingUI",
        "com.tencent.mm.plugin.profile.ui.PermissionSettingUI",
        "com.tencent.mm.plugin.profile.ui.CommonChatroomInfoUI",
        "com.tencent.mm.plugin.profile.ui.ContactMoreInfoUI",
        "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
        "com.tencent.mm.ui.SingleChatInfoUI",
        "com.tencent.mm.chatroom.ui.ChatroomInfoUI",
        "com.tencent.mm.plugin.setting.ui.setting.ColorfulSelfQRCodeUI",
        "com.tencent.mm.plugin.readerapp.ui.ReaderAppUI"
    )

    private fun hookH() {

    }

    private class ZeroColorPaint(flags: Int) : Paint(flags) {

        init {
            color = 0
        }

        override fun setAlpha(a: Int) {
            super.setAlpha(0)
        }

        override fun setColor(color: Int) {
            super.setColor(0)
        }
    }

    private fun g(viewGroup: ViewGroup, list: MutableList<View>) {
        val childCount = viewGroup.size
        for (i2 in 0..<childCount) {
            val child = viewGroup.getChildAt(i2)
            if (child.height == 1) {
                list.add(child)
            }
            if (child is ViewGroup) {
                g(child, list)
            }
        }
    }

    override fun onClick(context: Context) {
        TransparentActivity.launch(context) {
            val selMediaLauncher = registerForActivityResult(
                ActivityResultContracts.PickVisualMedia()
            ) { uri ->
                finish()

                if (uri == null) return@registerForActivityResult

                val contentResolver = HostInfo.application.contentResolver
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

                WePrefs.putString(KEY_BACKGROUND_URI, uri.toString())
                WeLogger.d(TAG, uri.toString())
            }

            selMediaLauncher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
    }

    private fun setNullBgRecursively(viewGroup: ViewGroup) {
        setNullBg(viewGroup)
        for (child in viewGroup) {
            if (child is ViewGroup)
                setNullBgRecursively(child)
            else
                setNullBg(child)
        }
    }

    private fun setNullBg(view: View) {
        view.background = null
        view.setTag(VIEW_TAG, VIEW_TAG_CONTENT)
    }

    private val methodChattingBackgroundComponentInitBg by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodChattingBackgroundComponentInitBg.find(dexKit) {
            matcher {
                usingStrings("MicroMsg.ChattingUI.ChattingBackgroundComponent", "initBackground:")
            }
        }
    }
}
