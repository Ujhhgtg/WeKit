package dev.ujhhgtg.wekit.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.style.ClickableSpan
import android.util.AttributeSet
import android.util.LruCache
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HeaderViewListAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.Space
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.withStyledAttributes
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.graphics.toColorInt
import androidx.core.view.get
import androidx.core.view.isEmpty
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.view.iterator
import androidx.core.view.postDelayed
import androidx.core.view.size
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.conversation.ConversationListView
import com.tencent.mm.ui.conversation.MainUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.beautify.Themes.THEMES_PATH
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.currentHookBridge
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import dev.ujhhgtg.wekit.utils.reflection.any
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.isDirectory
import kotlin.math.roundToInt

/**
 * 主题功能 —— 从 cherrywechat_deobf 忠实移植。
 *
 * 目录结构（`moduleData/themes/<主题ID>/`）：
 * - `manifest.json`：名称/作者/版本/描述；
 * - `colors.json` / `strings.json`：颜色/字符串键值（键名按场景分组）；
 * - `home/`、`chat/`、`chat/bubbles/`、`chat/emoji_tabs/`、`plus/`、`settings/`、`splash/`：图片。
 *
 * Hook 结构按 cherrywechat P5（BackgroundBanHook/BounceViewHook/ChatHook/ConversationUIHook/
 * HomeUIHook/MMActivityHook/MMSwitchBtnHook/PopupWindowHook/SettingActivityHook/SplashHook/
 * TextColorBanHook），并包含同批注册的 WeImageViewHook(EJ)/PreferenceHook(C0465dy) 与
 * GK/O8/W6/AbstractC0501em/H8/G8/I8/RunnableC0550fn 等辅助逻辑的内联忠实复刻。
 */
@Suppress("DEPRECATION")
@SuppressLint("DiscouragedApi", "InternalInsetResource")
object Themes : ClickableFeature(), IResolveDex {

    override val technicalId = "主题"
    override val nameRes = R.string.feature_themes_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_themes_description

    private const val TAG = "Themes"

    /** 与 cherrywechat 一致：主题根目录位于模块数据目录下 */
    private val THEMES_PATH by lazy { (KnownPaths.moduleData / "themes").createDirsSafe() }

    private const val KEY_CURRENT_THEME = "themes_current_id"

    /** 「无」主题 id，与 cherrywechat ThemeManager 的默认主题 id 一致 */
    private const val DEFAULT_THEME_ID = "0"

    private var currentThemeId by prefOption(KEY_CURRENT_THEME, DEFAULT_THEME_ID)

    // ------------------------------------------------------------------
    // cherrywechat 使用的 view tag 常量
    // ------------------------------------------------------------------
    private const val VIEW_TAG = 1426719277          // 背景已被主题接管
    private const val TEXT_COLOR_TAG = 1426719273    // 文字颜色已被主题接管
    private const val VIEW_TAG_HIDDEN = 1426719263   // 已隐藏的 1px 分割线
    private const val VIEW_TAG_HOME_DONE = 1426719264 // LauncherUI 背景已添加
    private const val RES_TAG = 1426719278           // ImageView 的原始资源名 tag

    // ------------------------------------------------------------------
    // 主题数据（cherrywechat ThemeInfo）
    // ------------------------------------------------------------------
    data class ThemeInfo(
        val id: String,
        val name: String,
        val author: String,
        val version: String,
        val description: String
    )

    private fun getDefaultTheme(context: Context) = ThemeInfo(
        id = DEFAULT_THEME_ID,
        name = context.localizedBeautifyString(R.string.beautify_theme_none),
        author = "—",
        version = "",
        description = context.localizedBeautifyString(R.string.beautify_theme_none_summary)
    )

    private fun getThemePath(themeId: String): File = (THEMES_PATH / themeId).toFile()

    /** 扫描 [THEMES_PATH]：每个含 `manifest.json` 的文件夹都是一个主题。 */
    private fun scanThemes(): List<ThemeInfo> {
        val root = THEMES_PATH.toFile()
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.sortedBy { it.name }.mapNotNull { loadThemeInfo(it) }
    }

    private fun loadThemeInfo(dir: File): ThemeInfo? {
        val manifest = File(dir, "manifest.json")
        if (!manifest.isFile) return null
        return runCatching {
            val json = JSONObject(manifest.readText())
            ThemeInfo(
                id = dir.name,
                name = json.optString("name", dir.name),
                author = json.optString("author", localizedBeautifyString(R.string.unknown_author)),
                version = json.optString("version", "1.0"),
                description = json.optString("description", "")
            )
        }.getOrElse { e ->
            WeLogger.w(TAG, "parse manifest failed: ${dir.name}", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // 主题配置（ThemeProvider native 的 Kotlin 替代）
    // ------------------------------------------------------------------

    /** 颜色名 -> 颜色值；由 colors.json 填充 */
    private val themeColors = ConcurrentHashMap<String, Int>()

    /** 字符串名 -> 字符串值；由 strings.json 填充 */
    private val themeStrings = ConcurrentHashMap<String, String>()

    /** 主题图片缓存，避免每次 hook 回调都重新解码 */
    private val drawableCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** 启动时加载当前主题配置 */
    private fun loadCurrentTheme() {
        themeColors.clear()
        themeStrings.clear()
        if (currentThemeId == DEFAULT_THEME_ID) return
        val dir = THEMES_PATH.resolve(currentThemeId)
        if (!dir.isDirectory()) {
            WeLogger.w(TAG, "current theme dir missing, reset to none: $currentThemeId")
            currentThemeId = DEFAULT_THEME_ID
            return
        }
        loadThemeConfig(dir.toFile())
    }

    /** 读取 colors.json / strings.json，填充主题配置 */
    private fun loadThemeConfig(dir: File) {
        val colorsFile = File(dir, "colors.json")
        if (colorsFile.isFile) {
            runCatching {
                val json = JSONObject(colorsFile.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    parseColor(json.get(key))?.let { themeColors[key] = it }
                }
            }.onFailure {
                WeLogger.w(TAG, "load colors.json failed", it)
            }
        }
        val stringsFile = File(dir, "strings.json")
        if (stringsFile.isFile) {
            runCatching {
                val json = JSONObject(stringsFile.readText())
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    themeStrings[key] = json.optString(key)
                }
            }.onFailure {
                WeLogger.w(TAG, "load strings.json failed", it)
            }
        }
    }

    private fun parseColor(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.toInt()
        is String -> {
            val s = value.trim().removePrefix("#")
            when (s.length) {
                6 -> runCatching { "#FF$s".toColorInt() }.getOrNull()
                8 -> runCatching { "#$s".toColorInt() }.getOrNull()
                else -> s.toIntOrNull(16)?.let { 0xFF shl 24 or it }
            }
        }
        else -> null
    }

    /** 等价于 cherrywechat VB.c(...) */
    private fun themeColor(name: String, default: Int): Int = themeColors[name] ?: default

    /** 等价于 cherrywechat VB.e(...) */
    private fun themeString(name: String, default: String): String = themeStrings[name] ?: default

    /**
     * 等价于 cherrywechat VB.getDrawable(...)：`actionbar/` 子目录下的图标（非 background）
     * 按 32dp 加载，其余按原尺寸加载（由 native ThemeProvider 改为直接读取主题文件夹）。
     */
    private fun themedDrawable(name: String?): Drawable? {
        if (name.isNullOrEmpty()) return null
        val isActionbarIcon = name.contains("/actionbar/") && !name.endsWith("background.png")
        return if (isActionbarIcon) {
            themedDrawableForSize(name, withDensity(32), withDensity(32))
        } else {
            loadThemedDrawable(name)
        }
    }

    private fun loadThemedDrawable(name: String): Drawable? {
        val themeId = currentThemeId
        if (themeId == DEFAULT_THEME_ID) return null
        val file = resolveThemeFile(themeId, name) ?: return null
        val cacheKey = "$themeId/$name"
        var bitmap = drawableCache.get(cacheKey)
        if (bitmap == null) {
            bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            drawableCache.put(cacheKey, bitmap)
        }
        return bitmap.toDrawable(HostInfo.application.resources)
    }

    /** 等价于 cherrywechat VB.getDrawableForSize(...) */
    private fun themedDrawableForSize(name: String?, width: Int, height: Int): Drawable? {
        val base = loadThemedDrawable(name ?: return null) ?: return null
        if (width <= 0 || height <= 0) return base
        val source = (base as? BitmapDrawable)?.bitmap ?: return base
        if (source.width == width && source.height == height) return base
        return source.scale(width, height).toDrawable(HostInfo.application.resources)
    }

    /** 主题文件夹内的图片按相对路径读取（如 `home/background.png`、`chat/bubbles/text_left.png`） */
    private fun resolveThemeFile(themeId: String, name: String): File? {
        val file = File(getThemePath(themeId), name)
        return file.takeIf { it.isFile }
    }

    // ------------------------------------------------------------------
    // 视图辅助函数（cherrywechat GK/O8/W6/S5 的内联等价实现）
    // ------------------------------------------------------------------

    /** cherrywechat GK.setNullBackgroundAndTag */
    private fun setNullBg(view: View?) {
        if (view != null) {
            view.background = null
            view.setTag(VIEW_TAG, any)
        }
    }

    /** cherrywechat GK.setNullBackgroundAndTagRecursively_：递归清空（含 TextView） */
    private fun setNullBgRecursivelyWithTv(viewGroup: ViewGroup?) {
        if (viewGroup == null) return
        setNullBg(viewGroup)
        for (child in viewGroup) {
            if (child is ViewGroup) setNullBgRecursivelyWithTv(child) else setNullBg(child)
        }
    }

    /** cherrywechat GK.setNullBackgroundAndTagRecursively：递归清空（跳过 TextView） */
    private fun setNullBgRecursively(viewGroup: ViewGroup?) {
        if (viewGroup == null) return
        setNullBg(viewGroup)
        for (child in viewGroup) {
            if (child is ViewGroup) setNullBgRecursively(child)
            else if (child !is TextView) setNullBg(child)
        }
    }

    /** cherrywechat GK.Q：设置背景并打上“已接管”tag */
    private fun setThemedBackground(view: View?, drawable: Drawable?) {
        if (view == null) return
        view.setTag(VIEW_TAG, null)
        view.background = drawable
        view.setTag(VIEW_TAG, any)
    }

    /** cherrywechat GK.S：设置文字颜色并打上“已接管”tag */
    private fun setThemedTextColor(textView: TextView?, color: Int) {
        if (textView == null) return
        textView.setTag(TEXT_COLOR_TAG, null)
        textView.setTextColor(color)
        textView.setTag(TEXT_COLOR_TAG, TextView::class.java)
    }

    /** cherrywechat GK.A：CENTER_CROP 的 ImageView */
    private fun themedImageView(context: Context, drawable: Drawable?): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(drawable)
        }

    /** cherrywechat GK.firstChildViewGroupWithClass */
    private fun firstChildViewGroupWithClass(viewGroup: ViewGroup?, className: String): View? {
        if (viewGroup == null) return null
        for (child in viewGroup) {
            if (child.javaClass.name == className) return child
            if (child is ViewGroup) {
                firstChildViewGroupWithClass(child, className)?.let { return it }
            }
        }
        return null
    }

    /** cherrywechat GK.F：按文字内容查找 TextView */
    private fun findViewWithText(viewGroup: ViewGroup?, text: String): TextView? {
        if (viewGroup == null) return null
        for (child in viewGroup) {
            if (child is TextView && child.text?.toString() == text) return child
            if (child is ViewGroup) {
                findViewWithText(child, text)?.let { return it }
            }
        }
        return null
    }

    /** cherrywechat GK.H：按 RES_TAG 查找 ImageView */
    private fun findImageViewByResTag(viewGroup: ViewGroup?, resName: String): ImageView? {
        if (viewGroup == null) return null
        for (child in viewGroup) {
            if (child is ImageView && child.getTag(RES_TAG) == resName) return child
            if (child is ViewGroup) {
                findImageViewByResTag(child, resName)?.let { return it }
            }
        }
        return null
    }

    /** cherrywechat GK.G：收集高度为 1px 的视图 */
    private fun collectHeightOneViews(viewGroup: ViewGroup, list: MutableList<View>) {
        for (child in viewGroup) {
            if (child.height == 1) list.add(child)
            if (child is ViewGroup) collectHeightOneViews(child, list)
        }
    }

    /** cherrywechat GK.L：按索引路径取子视图 */
    private fun viewByPath(root: ViewGroup?, vararg indexes: Int): View? {
        if (root == null) return null
        var current: View = root
        for ((i, index) in indexes.withIndex()) {
            val vg = current as? ViewGroup ?: return null
            if (index !in 0 until vg.childCount) return null
            current = vg.getChildAt(index)
            if (i == indexes.lastIndex) return current
        }
        return current
    }

    /** cherrywechat O8：从实例（含父类）按类型查找第一个字段值 */
    private fun firstFieldValue(instance: Any?, type: Class<*>): Any? {
        if (instance == null) return null
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (field.type == type) {
                    field.isAccessible = true
                    val v = runCatching { field.get(instance) }.getOrNull()
                    if (v != null) return v
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** cherrywechat O8.g(name).d()：按字段名（含父类）取字段值 */
    private fun fieldValueByName(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (field.name == name) {
                    field.isAccessible = true
                    return runCatching { field.get(instance) }.getOrNull()
                }
            }
            clazz = clazz.superclass
        }
        return null
    }

    /** cherrywechat O8(5).d = View.class 语义：实例（含父类）中指定类型的字段值 */
    private fun fieldValueOfType(instance: Any?, type: Class<*>): Any? = firstFieldValue(instance, type)

    /** 收集实例（含父类）所有指定类型的字段值 */
    private fun declaredViewsOfType(instance: Any?, type: Class<*>): List<View> {
        if (instance == null) return emptyList()
        val result = mutableListOf<View>()
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            for (field in clazz.declaredFields) {
                if (type.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val v = runCatching { field.get(instance) }.getOrNull()
                    if (v is View) result += v
                }
            }
            clazz = clazz.superclass
        }
        return result
    }

    /** cherrywechat XposedHelpers.callMethod 的等价实现（找不到方法时返回 null） */
    private fun callMethod(instance: Any?, name: String, vararg args: Any?): Any? =
        runCatching { instance?.reflekt()?.invokeMethod(name, *args) }.getOrNull()

    private fun disableColorFilter(imageView: ImageView) {
        runCatching { imageView.reflekt().invokeMethod("setEnableColorFilter", false) }
    }

    private fun withDensity(dp: Number): Int {
        val density = HostInfo.application.resources.displayMetrics.density
        return (dp.toFloat() * density).roundToInt()
    }

    /** cherrywechat S5.a(context, "status_bar_height") */
    private fun statusBarHeight(context: Context): Int {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else withDensity(25)
    }

    @Volatile
    private var actionBarHeight = 0

    /** cherrywechat GK.J(activity) */
    private fun actionBarHeightOf(activity: Activity): Int {
        if (actionBarHeight != 0) return actionBarHeight
        val h = callMethod(activity, "getActionBarHeightFromTheme") as? Int ?: 0
        if (h != 0) actionBarHeight = h
        return h
    }

    private fun resourceId(pkg: String, name: String): Int =
        HostInfo.application.resources.getIdentifier(name, "id", pkg)

    /** cherrywechat W6 的资源 id（懒解析） */
    private val idText by lazy { resourceId("com.tencent.mapsdk", "text") }
    private val idIcon by lazy { resourceId("com.tencent.mapsdk", "icon") }
    private val idIconTv by lazy { resourceId("com.tencent.mm", "icon_tv") }
    private val idActionbarUpIndicator by lazy { resourceId("com.tencent.mm", "actionbar_up_indicator") }

    // ------------------------------------------------------------------
    // 图标映射（cherrywechat AbstractC0502en / AbstractC0597gm.k/i / C0977lv 11）
    // ------------------------------------------------------------------

    /** 首页列表项文字/键名 -> 主题图片路径 */
    private val HOME_ITEM_ICONS = mapOf(
        "新的朋友" to "home/items/new_friend.png",
        "仅聊天的朋友" to "home/items/only_chat_friends.png",
        "群聊" to "home/items/group_chat.png",
        "标签" to "home/items/label.png",
        "公众号" to "home/items/official_account.png",
        "服务号" to "home/items/service_account.png",
        "企业微信联系人" to "home/items/wework_contact.png",
        "album_dyna_photo_ui_title" to "home/discovery/moments.png",
        "find_friends_by_finder" to "home/discovery/channels.png",
        "find_friends_by_finder_live" to "home/discovery/live.png",
        "find_friends_by_qrcode" to "home/discovery/scan.png",
        "find_friends_by_ting" to "home/discovery/listen.png",
        "find_friends_by_look" to "home/discovery/look.png",
        "find_friends_by_search" to "home/discovery/search.png",
        "find_live_friends_by_near" to "home/discovery/nearby.png",
        "find_friends_by_near" to "home/discovery/nearby.png",
        "find_friends_by_near_v3" to "home/discovery/nearby.png",
        "jd_market_entrance" to "home/discovery/shopping.png",
        "more_tab_game_recommend" to "home/discovery/games.png",
        "app_brand_entrance" to "home/discovery/mini_programs.png",
        "settings_mm_wallet" to "home/me/services.png",
        "settings_mm_favorite" to "home/me/favorites.png",
        "settings_my_album" to "home/me/moments.png",
        "settings_my_finder_album" to "home/me/moments.png",
        "settings_my_finder_and_biz" to "home/me/moments.png",
        "settings_mm_cardpackage" to "home/me/cards.png",
        "settings_mm_cardpackage_new" to "home/me/cards.png",
        "settings_emoji_store" to "home/me/emoji.png",
        "more_setting" to "home/me/settings.png"
    )

    /** 聊天「+」面板图标（cherrywechat AbstractC0597gm.k） */
    private val CHAT_PLUS_ICONS = mapOf(
        "panel_icon_pic" to "plus/album.png",
        "panel_icon_camera" to "plus/camera.png",
        "panel_icon_voip" to "plus/video_call.png",
        "panel_icon_voipvoice" to "plus/video_call.png",
        "panel_icon_multitalk" to "plus/video_call.png",
        "panel_icon_location" to "plus/location.png",
        "panel_icon_luckymoney" to "plus/red_packet.png",
        "panel_icon_transfer" to "plus/transfer.png",
        "panel_icon_voiceinput" to "plus/voice_input.png",
        "panel_icon_fav" to "plus/favorites.png",
        "panel_icon_friendcard" to "plus/business_card.png",
        "panel_icon_file_explorer" to "plus/file.png",
        "icon_music_filled" to "plus/music.png",
        "icons_filled_grouptool" to "plus/group_tools.png",
        "icons_outlined_continued_form" to "plus/chain.png",
        "panel_icon_live" to "plus/live.png",
        "panel_icon_card" to "plus/card.png",
        "icons_filled_gift_chatting" to "plus/gift.png"
    )

    /** 标题栏图标（cherrywechat AbstractC0597gm.i，配合 EJ/M8(0)） */
    private val ACTIONBAR_ICONS = mapOf(
        "icons_outlined_multitask" to "home/actionbar/multitask.png",
        "icons_outlined_add2" to "home/actionbar/plus.png",
        "actionbar_icon_dark_search2" to "home/actionbar/search.png",
        "actionbar_icon_dark_more" to "chat/actionbar/more.png",
        "icons_outlined_me" to "chat/actionbar/more.png",
        "arrow_left_regular" to "chat/actionbar/back.png",
        "actionbar_setting_icon" to "chat/actionbar/settings.png",
        "actionbar_icon_dark_search" to "chat/actionbar/search.png",
        "actionbar_menu_list_icon" to "chat/actionbar/more.png"
    )

    // ------------------------------------------------------------------
    // 主题辅助函数（cherrywechat 私有辅助的忠实复刻）
    // ------------------------------------------------------------------

    /** AbstractC0398ce.R：递归设置聊天标题栏文字颜色 */
    private fun setActionbarTitleColors(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is TextView) {
                setThemedTextColor(child, themeColor("chat.actionbar.title_text", -16777216))
            } else if (child is ViewGroup) {
                setActionbarTitleColors(child)
            }
        }
    }

    /** AbstractC0398ce.m：标题栏文字颜色 + 「返回」右侧的未读角标 */
    private fun setActionbarUnreadBadge(viewGroup: ViewGroup) {
        setActionbarTitleColors(viewGroup)
        val list = ArrayList<View>()
        viewGroup.findViewsWithText(list, "返回", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
        if (list.isEmpty()) return
        val view = list[0]
        list.clear()
        val parent = view.parent as? LinearLayout ?: return
        val textView = parent.getChildAt(1) as? TextView
        if (textView != null) {
            setThemedBackground(textView, themedDrawable("chat/actionbar/unread_badge.png"))
        }
        val color = themeColor("chat.actionbar.unread_badge_text", -16777216)
        if (textView != null) {
            setThemedTextColor(textView, color)
        }
    }

    /** AbstractC0454dm.j：状态栏透明 */
    private fun setStatusBarTransparent(activity: Activity) {
        runCatching {
            val attributes = activity.window.attributes
            activity.window.clearFlags(67108864)
            activity.window.addFlags(-2147483648)
            activity.window.statusBarColor = 0
            activity.window.decorView.systemUiVisibility =
                activity.window.decorView.systemUiVisibility or 1024
            attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            activity.window.attributes = attributes
        }
    }

    /** GK.P：递归设置首页列表项文字颜色 */
    private fun setHomeItemColors(
        viewGroup: ViewGroup?,
        black: Int,
        gray: Int,
        forceGray: Boolean
    ) {
        if (viewGroup == null) return
        for (child in viewGroup) {
            if (child is TextView) {
                if (forceGray) {
                    if (gray != 0) setThemedTextColor(child, gray)
                } else if (child.textSize < withDensity(13)) {
                    if (gray != 0) child.setTextColor(gray)
                } else if (black != 0) {
                    child.setTextColor(black)
                }
            }
            if (child is ViewGroup) setHomeItemColors(child, black, gray, false)
        }
    }

    /** AbstractC0501em.a：设置页 actionbar 背景 */
    private fun addSettingActionbarBackground(viewGroup: ViewGroup) {
        viewGroup.addView(
            ImageView(viewGroup.context).apply {
                background = themedDrawable("settings/actionbar/background.png")
            },
            0,
            ViewGroup.LayoutParams(
                -1,
                statusBarHeight(viewGroup.context) + actionBarHeight
            )
        )
    }

    /** AbstractC0501em.p：设置页 actionbar 返回/更多/搜索图标与标题颜色 */
    private fun setSettingActionbarIcons(viewGroup: ViewGroup) {
        var relativeLayout: RelativeLayout? = null
        val upIndicator = viewGroup.findViewById<View>(idActionbarUpIndicator)
        if (upIndicator != null) {
            val parent = upIndicator.parent as? RelativeLayout ?: return
            relativeLayout = parent
            themedDrawable("settings/actionbar/back.png")?.let { d ->
                upIndicator.alpha = 0f
                val iv = themedImageView(viewGroup.context, d)
                relativeLayout.addView(
                    iv,
                    RelativeLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                        addRule(RelativeLayout.CENTER_VERTICAL)
                        leftMargin = withDensity(6)
                        rightMargin = withDensity(10)
                    }
                )
            }
        }
        findImageViewByResTag(viewGroup, "icons_outlined_more")?.let { more ->
            themedDrawable("settings/actionbar/more.png")?.let { d ->
                more.alpha = 0f
                val iv = themedImageView(viewGroup.context, d)
                relativeLayout?.addView(
                    iv,
                    RelativeLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                        addRule(RelativeLayout.CENTER_VERTICAL)
                        addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                        rightMargin = withDensity(6)
                    }
                )
            }
        }
        findImageViewByResTag(viewGroup, "actionbar_icon_dark_search")?.let { search ->
            themedDrawable("settings/actionbar/search.png")?.let { d ->
                search.alpha = 0f
                val iv = themedImageView(viewGroup.context, d)
                relativeLayout?.addView(
                    iv,
                    RelativeLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                        addRule(RelativeLayout.CENTER_VERTICAL)
                        addRule(RelativeLayout.ALIGN_PARENT_RIGHT)
                        rightMargin = withDensity(6)
                    }
                )
            }
        }
        val title = relativeLayout?.findViewById<TextView>(android.R.id.title)
        setThemedTextColor(title, themeColor("settings.actionbar.title_text", -16777216))
    }

    /** AbstractC0501em.q：气泡左右侧 padding 对称 */
    private fun setBubbleSidePadding(view: View, isRight: Boolean) {
        if (isRight) {
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingLeft, view.paddingBottom)
        } else {
            view.setPadding(view.paddingRight, view.paddingTop, view.paddingRight, view.paddingBottom)
        }
    }

    /** AbstractC0501em.r：递归清空气泡子项背景并按类型着色 */
    private fun setBubbleChildrenBackgrounds(
        viewGroup: ViewGroup,
        color: Int,
        colorEnabled: Boolean,
        isRight: Boolean
    ) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            child.background = null
            setBubbleSidePadding(child, isRight)
            if (colorEnabled && child.javaClass.name == "com.tencent.mm.ui.widget.MMTextView") {
                child.reflekt().invokeMethod(
                    "setTextColor",
                    Color.argb(155, Color.red(color), Color.green(color), Color.blue(color))
                )
            } else if (colorEnabled && child.javaClass.name == "com.tencent.mm.ui.widget.MMNeat7extView") {
                child.reflekt().invokeMethod("setTextColor", color)
            }
            if (child is ViewGroup) setBubbleChildrenBackgrounds(child, color, colorEnabled, isRight)
        }
    }

    /** AbstractC0501em.s：灰色提示文字颜色（NeatTextView 或 LinearLayout 首子项 + timeTV） */
    private fun setGrayTipsColor(holder: Any?) {
        var childAt: View?
        val neat = fieldValueOfType(holder, "com.tencent.neattextview.textview.view.NeatTextView".toClass())
        if (neat is View) {
            childAt = neat
        } else {
            val linear = fieldValueOfType(holder, LinearLayout::class.java) as? ViewGroup
            childAt = linear?.getChildAt(0)
        }
        val color = themeColor("chat.tips_text", 0)
        if (color != 0 && childAt != null) {
            childAt.reflekt().invokeMethod("setTextColor", color)
        }
        if (color != 0) {
            val timeTV = fieldValueByName(holder, "timeTV") as? TextView
            timeTV?.setTextColor(color)
        }
    }

    /** AbstractC0501em.t：普通文本/图片消息气泡 */
    private fun setTextBubbleTheme(holder: Any?, isRight: Boolean) {
        val mainContainer = holder?.let { callMethod(it, "getMainContainerView") as? View }
        if (mainContainer == null) return
        val d = themedDrawable(
            if (isRight) "chat/bubbles/text_right.png" else "chat/bubbles/text_left.png"
        )
        if (d != null) {
            setBubbleSidePadding(mainContainer, isRight)
            mainContainer.background = d
            val color = themeColor(
                if (isRight) "chat.text_bubble.right_text" else "chat.text_bubble.left_text",
                0
            )
            if (color != 0) {
                mainContainer.reflekt().invokeMethod("setTextColor", color)
            }
        }
    }

    /** AbstractC0549fm.n：聊天底部（公众号切换按钮等） */
    private fun setFooterTheme(viewGroup: ViewGroup) {
        setNullBg(viewGroup)
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                setFooterTheme(child)
            } else {
                if (child is TextView) {
                    setThemedTextColor(child, themeColor("chat.input.bottom_text", -16777216))
                } else if (child is ImageView) {
                    if ("切换到发消息" == child.contentDescription && child.layoutParams.width != 0) {
                        val lp = child.layoutParams
                        lp.width = 0
                        lp.height = 0
                        themedDrawable("chat/public_account_switch.png")?.let { d ->
                            val parent = child.parent as? ViewGroup ?: return@let
                            val iv = themedImageView(child.context, d)
                            parent.addView(
                                iv,
                                LinearLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                                    gravity = 16
                                }
                            )
                            // ViewOnClickListenerC1078o(1, imageView)：点击转发给原按钮
                            iv.setOnClickListener { child.performClick() }
                        }
                    }
                }
                if (child.layoutParams.width > withDensity(1)) {
                    setNullBg(child)
                }
            }
        }
    }

    /** H8(1)：聊天底部完整重排 */
    private fun setChatFooterTheme(viewGroup: ViewGroup) {
        setNullBgRecursivelyWithTv(viewGroup)
        val footerClass = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter".toClass()
        for (field in footerClass.declaredFields) {
            field.isAccessible = true
            val view = runCatching { field.get(viewGroup) }.getOrNull() as? View ?: continue
            val viewName = view.javaClass.name
            if (viewName == "com.tencent.mm.ui.widget.imageview.WeImageButton" &&
                view.parent is LinearLayout
            ) {
                themedDrawable("chat/public_account_switch.png")?.let { d ->
                    val parent = view.parent as ViewGroup
                    val original = parent.getChildAt(0)
                    original.alpha = 0f
                    val linear = original.parent as? LinearLayout ?: return@let
                    val index = linear.indexOfChild(original)
                    val lp = original.layoutParams
                    linear.removeView(original)
                    val frame = FrameLayout(view.context)
                    frame.addView(original)
                    frame.addView(
                        themedImageView(view.context, d),
                        FrameLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                            gravity = 17
                        }
                    )
                    linear.addView(frame, index, lp)
                    frame.visibility = original.visibility
                    original.viewTreeObserver.addOnPreDrawListener {
                        frame.visibility = original.visibility
                        true
                    }
                }
                themedDrawable("chat/bottom_voice.png")?.let { d ->
                    view.alpha = 0f
                    val parent = view.parent as ViewGroup
                    val index = parent.indexOfChild(view)
                    val lp = view.layoutParams
                    parent.removeView(view)
                    val frame = FrameLayout(view.context)
                    frame.addView(view)
                    frame.addView(
                        themedImageView(view.context, d),
                        FrameLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                            gravity = 17
                        }
                    )
                    parent.addView(frame, index, lp)
                }
            }
            if (viewName == "com.tencent.mm.ui.widget.imageview.WeImageButton" &&
                view.getTag(RES_TAG) == "icons_outlined_emoji"
            ) {
                themedDrawable("chat/bottom_emoji.png")?.let { d ->
                    view.alpha = 0f
                    val parent = view.parent as? RelativeLayout ?: return@let
                    parent.addView(
                        themedImageView(view.context, d),
                        RelativeLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                            addRule(RelativeLayout.CENTER_IN_PARENT)
                        }
                    )
                }
            }
            if (viewName == "com.tencent.mm.ui.widget.imageview.WeImageButton") {
                val tag = view.getTag(RES_TAG)
                if (tag != "icons_outlined_emoji" && tag != "icons_outlined_voice" &&
                    tag != "arrow_line_right_regular" && view.parent is RelativeLayout
                ) {
                    val relative = view.parent as RelativeLayout
                    val textView = relative.getChildAt(2) as? TextView
                    if (textView != null) {
                        (textView.parent as? View)?.layoutParams?.width = withDensity(50)
                    }
                    themedDrawable("chat/bottom_plus.png")?.let { d ->
                        view.alpha = 0f
                        val iv = themedImageView(view.context, d)
                        relative.addView(
                            iv,
                            RelativeLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                                addRule(RelativeLayout.CENTER_IN_PARENT)
                            }
                        )
                        relative.viewTreeObserver.addOnPreDrawListener {
                            if (textView?.visibility != View.VISIBLE) {
                                iv.alpha = 1f
                            } else {
                                iv.alpha = 0f
                            }
                            true
                        }
                    }
                    themedDrawable("chat/bottom_send.png")?.let { d ->
                        if (textView != null) {
                            setThemedBackground(textView, d)
                            setThemedTextColor(
                                textView,
                                themeColor("chat.input.send_text", -16777216)
                            )
                        }
                    }
                }
            }
            if (viewName == "com.tencent.mm.pluginsdk.ui.chat.ChatFooterBottom") {
                themedDrawable("chat/plus_panel_background.png")?.let { d ->
                    (view as? ViewGroup)?.addView(
                        themedImageView(view.context, d),
                        0,
                        ViewGroup.LayoutParams(-1, -1)
                    )
                }
            }
            if (viewName == "com.tencent.mm.view.MaxHeightScrollView") {
                setThemedBackground(view, themedDrawable("chat/input_background.png"))
            }
            try {
                val color = themeColor("chat.input.bottom_text", 0)
                if (color != 0 && view is LinearLayout && view.parent is LinearLayout) {
                    val lp = view.layoutParams
                    if (lp is LinearLayout.LayoutParams && lp.weight == 1f &&
                        view.getChildAt(0) is TextView
                    ) {
                        (view.getChildAt(0) as TextView).setTextColor(color)
                        val sibling = (view.parent as ViewGroup).getChildAt(1) as? ViewGroup
                        sibling?.getChildAt(0)?.let { callMethod(it, "setIconColor", color) }
                    }
                }
            } catch (_: Exception) {
            }
            if (viewName == Button::class.java.name && view.isSoundEffectsEnabled) {
                themeColor("chat.input.bottom_text", 0).takeIf { it != 0 }?.let { c ->
                    (view as? Button)?.setTextColor(c)
                }
            }
        }
        viewGroup.getChildAt(0)?.let {
            setThemedBackground(it, themedDrawable("chat/bottom_background.png"))
        }
    }

    /** H8(2)：ChatFooterCustom 底部背景 */
    private fun setChatFooterCustomTheme(viewGroup: ViewGroup) {
        setFooterTheme(viewGroup)
        setThemedBackground(viewGroup, themedDrawable("chat/bottom_background.png"))
    }

    /** J5(3)：首页标题文字监听 */
    private class HomeTitleWatcher(private val textView: TextView) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (s == null) return
            val text = s.toString()
            when {
                text == "微信" || text.startsWith("微信(") -> {
                    val replaced = themeString("home.tabs.wechat_title", "微信")
                    if (replaced != "微信") textView.text = text.replace("微信", replaced)
                }
                text == "通讯录" -> {
                    val replaced = themeString("home.tabs.contact_title", "通讯录")
                    if (replaced != "通讯录") textView.text = "通讯录".replace("通讯录", replaced)
                }
                text == "发现" -> {
                    val replaced = themeString("home.tabs.discovery_title", "发现")
                    if (replaced != "发现") textView.text = "发现".replace("发现", replaced)
                }
            }
        }
    }

    /** C0646hn + O3(7)：tab 未读角标监听 */
    private class UnreadBadgeWatcher(
        private val badge: TextView,
        private val source: TextView
    ) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        @SuppressLint("SetTextI18n")
        override fun afterTextChanged(s: Editable?) {
            if (s == null) return
            if (s.isEmpty()) {
                badge.postDelayed(200L) {
                    if (source.isInvisible) {
                        badge.visibility = View.INVISIBLE
                    } else {
                        badge.visibility = View.VISIBLE
                        badge.text = "99+"
                    }
                }
            } else {
                badge.visibility = View.VISIBLE
                badge.text = s
            }
        }
    }

    /** RunnableC0550fn：首页底部 tab 完整重排 */
    private fun applyBottomTabTheme(
        contentView: ViewGroup,
        actionbarBg: ImageView,
        bottomTab: ViewGroup,
        viewPager: ViewGroup
    ) {
        setNullBgRecursivelyWithTv(contentView)
        val title = contentView.findViewById<TextView>(android.R.id.title) ?: return
        val titleText = title.text?.toString().orEmpty()
        title.text = titleText.replace("微信", themeString("home.tabs.wechat_title", "微信"))
        setThemedTextColor(title, themeColor("home.actionbar.title_text", -16777216))
        title.addTextChangedListener(HomeTitleWatcher(title))
        contentView.viewTreeObserver.addOnPreDrawListener(
            object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val curIdx = callMethod(bottomTab, "getCurIdx") as? Int ?: 0
                    if (curIdx == 3 || title.isGone) {
                        if (actionbarBg.visibility != View.GONE) actionbarBg.visibility = View.GONE
                    } else if (actionbarBg.isGone) {
                        actionbarBg.visibility = View.VISIBLE
                    }
                    if (contentView.translationY != actionbarBg.translationY) {
                        actionbarBg.translationY = contentView.translationY
                    }
                    if (contentView.visibility == actionbarBg.visibility ||
                        contentView.translationY == 0f
                    ) {
                        return true
                    }
                    actionbarBg.visibility = contentView.visibility
                    return true
                }
            }
        )
        bottomTab.layoutParams.height = withDensity(56)
        setThemedBackground(bottomTab, themedDrawable("home/bottom_tab_background.png"))
        val tabBar = bottomTab.getChildAt(0) as? LinearLayout ?: return
        setNullBg(tabBar)
        for (i in 0 until tabBar.childCount) {
            val tab = tabBar.getChildAt(i) as? ViewGroup ?: continue
            val iconTv = tab.findViewById<TextView>(idIconTv)
            iconTv.visibility = View.GONE
            val label = TextView(iconTv.context).apply {
                setTextSize(1, 12f)
                id = idText
            }
            val labelColor = if (i == 0) {
                themeColor("home.tabs.selected_text", -16777216)
            } else {
                themeColor("home.tabs.unselected_text", -7829368)
            }
            setThemedTextColor(label, labelColor)
            label.text = when (i) {
                0 -> themeString("home.tabs.wechat_title", "微信")
                1 -> themeString("home.tabs.contact_title", "通讯录")
                2 -> themeString("home.tabs.discovery_title", "发现")
                else -> themeString("home.tabs.me_title", "我")
            }
            val iconParent = iconTv.parent as? ViewGroup ?: continue
            iconParent.addView(label, iconTv.layoutParams)
            val iconContainer = iconParent.getChildAt(0) as? ViewGroup ?: continue
            iconContainer.getChildAt(0).alpha = 0f
            val unreadTv = iconContainer.getChildAt(1) as? TextView ?: continue
            unreadTv.alpha = 0f
            val badge = TextView(unreadTv.context).apply {
                setTextSize(1, 12f)
                setTextColor(themeColor("home.tabs.unread_badge_text", -16777216))
                gravity = 17
                visibility = View.GONE
                setSingleLine(true)
                includeFontPadding = false
                background = themedDrawable("home/tabs/unread_badge.png")
            }
            iconContainer.addView(badge, unreadTv.layoutParams)
            unreadTv.addTextChangedListener(UnreadBadgeWatcher(badge, unreadTv))
            val redTip = iconContainer.getChildAt(2)
            val gradient = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(themeColor("home.tabs.unread_red_tip", -65536))
            }
            setThemedBackground(redTip, gradient)
            val iconDrawable = when (i) {
                0 -> themedDrawable("home/tabs/conversation_selected.png")
                1 -> themedDrawable("home/tabs/contact_unselected.png")
                2 -> themedDrawable("home/tabs/discovery_selected.png")
                else -> themedDrawable("home/tabs/me_unselected.png")
            }
            val iconIv = themedImageView(iconContainer.context, iconDrawable)
            label.tag = iconIv
            iconContainer.addView(
                iconIv,
                0,
                RelativeLayout.LayoutParams(withDensity(34), withDensity(34)).apply {
                    addRule(RelativeLayout.CENTER_IN_PARENT)
                }
            )
        }
        for (page in 1..3) {
            val pageView = viewPager.getChildAt(page) as? ViewGroup ?: continue
            setNullBgRecursivelyWithTv(pageView)
        }
        val pages = listOf(
            "home/conversation_background.png",
            "home/tab_contact_background.png",
            "home/tab_discovery_background.png",
            "home/tab_me_background.png"
        )
        for ((index, name) in pages.withIndex()) {
            val pageView = viewPager.getChildAt(index) as? ViewGroup ?: continue
            themedDrawable(name)?.let { d ->
                pageView.addView(themedImageView(viewPager.context, d), 0, ViewGroup.LayoutParams(-1, -1))
            }
        }
    }

    /** C0977lv 10：聊天长按菜单 */
    private fun applyLongPressMenuTheme(viewGroup: ViewGroup) {
        val bg = themedDrawable("chat/long_press_menu_background.png") ?: return
        val threePart = viewGroup.childCount == 3 &&
            viewGroup.getChildAt(0) is ImageView &&
            viewGroup.getChildAt(1) is LinearLayout &&
            viewGroup.getChildAt(2) is ImageView
        if (threePart) {
            val middle = viewGroup.getChildAt(1) as ViewGroup
            middle.background = bg
            themedDrawable("chat/long_press_menu_arrow_up.png")?.let { up ->
                val arrow = viewGroup.getChildAt(0) as ImageView
                disableColorFilter(arrow)
                arrow.setImageDrawable(up)
            }
            themedDrawable("chat/long_press_menu_arrow_down.png")?.let { down ->
                val arrow = viewGroup.getChildAt(2) as ImageView
                disableColorFilter(arrow)
                arrow.setImageDrawable(down)
            }
            if (middle.childCount > 1) middle.getChildAt(1).alpha = 0f
            val color = themeColor("chat.long_press_menu.item_text", 0)
            if (color != 0) {
                for (index in listOf(0, 2)) {
                    val part = middle.getChildAt(index) as? ViewGroup ?: continue
                    for (i in 0 until part.childCount) {
                        val item = part.getChildAt(i) as? ViewGroup ?: continue
                        item.findViewById<View>(idIcon)?.let { callMethod(it, "setIconColor", color) }
                        item.findViewById<TextView>(idText)?.setTextColor(color)
                    }
                    part.setOnHierarchyChangeListener(
                        object : ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewAdded(parent: View?, child: View?) {
                                val item = child as? ViewGroup ?: return
                                item.findViewById<View>(idIcon)
                                    ?.let { callMethod(it, "setIconColor", color) }
                                item.findViewById<TextView>(idText)?.setTextColor(color)
                            }

                            override fun onChildViewRemoved(parent: View?, child: View?) {}
                        }
                    )
                }
            }
        } else if (viewGroup.childCount == 2 &&
            viewGroup.getChildAt(0) is LinearLayout &&
            viewGroup.getChildAt(1) is ImageView
        ) {
            val linear = viewGroup.getChildAt(0) as LinearLayout
            val first = linear.getChildAt(0)
            if (first is TextView && first.text?.toString() == "定位到原文位置") {
                linear.background = bg
                themedDrawable("chat/long_press_menu_arrow_down.png")?.let { down ->
                    val arrow = viewGroup.getChildAt(1) as ImageView
                    disableColorFilter(arrow)
                    arrow.setImageDrawable(down)
                }
                themeColor("chat.long_press_menu.item_text", 0).takeIf { it != 0 }
                    ?.let { first.setTextColor(it) }
            }
        }
    }

    /** P5.c：隐藏首页全屏 ImageView（ColorfulSelfQRCodeUI） */
    private fun hideFullScreenImageViews(viewGroup: ViewGroup) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ViewGroup) {
                hideFullScreenImageViews(child)
            } else if (child is ImageView) {
                if (child.paddingTop == 0) {
                    val lp = child.layoutParams
                    if (lp.width == -1 && lp.height == -1 &&
                        child.scaleType == ImageView.ScaleType.CENTER_CROP
                    ) {
                        child.alpha = 0f
                        return
                    }
                }
            }
        }
    }

    /** C0465dy.c：递归查找正方形 ImageView（并缓存 id） */
    @Volatile
    private var homeItemIconId = -1

    private fun findSquareImageView(viewGroup: ViewGroup): ImageView? {
        if (homeItemIconId != -1) {
            return viewGroup.findViewById(homeItemIconId) as? ImageView
        }
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is ImageView) {
                val width = child.layoutParams.width
                if (width == child.layoutParams.height && width > 0) {
                    homeItemIconId = child.id
                    return child
                }
            }
            if (child is ViewGroup) {
                findSquareImageView(child)?.let { return it }
            }
        }
        return null
    }

    /** C0465dy.c：Preference 的 key 字段名缓存 */
    @Volatile
    private var homePreferenceKeyField = ""

    /** C0977lv 11：Preference 图标 */
    private fun applyHomePreferenceTheme(preference: Any?, view: View) {
        if (view is ViewGroup) {
            setNullBgRecursively(view)
            val textView = view.findViewById<TextView>(android.R.id.summary)
            if (textView != null) {
                val icon = findSquareImageView(view)
                if (icon != null && icon.context.javaClass.name == "com.tencent.mm.ui.LauncherUI" &&
                    icon.isVisible
                ) {
                    val key: String? = if (homePreferenceKeyField.isNotEmpty()) {
                        fieldValueByName(preference, homePreferenceKeyField) as? String
                    } else {
                        var result: String? = null
                        for (field in "com.tencent.mm.ui.base.preference.Preference".toClass().fields) {
                            if (field.type == String::class.java) {
                                val value = runCatching { field.get(preference) }.getOrNull() as? String
                                if (value != null && value.contains("_")) {
                                    homePreferenceKeyField = field.name
                                    result = value
                                }
                            }
                        }
                        result
                    }
                    themedDrawable(HOME_ITEM_ICONS[key])?.let { d ->
                        themeColor("home.item.primary_text", 0).takeIf { it != 0 }
                            ?.let { textView.setTextColor(it) }
                        icon.setLayerPaint(null)
                        val lp = icon.layoutParams
                        lp.width = withDensity(32)
                        lp.height = withDensity(32)
                        icon.setImageDrawable(d)
                    }
                }
            }
        } else {
            setNullBg(view)
        }
    }

    /** EJ：WeImageView/WeImageButton 资源名 tag + 监听分发（FD 8-11） */
    private val ejListeners = mutableListOf<(ImageView, String) -> Unit>()

    private fun registerEjImageHooks() {
        for (clazzName in listOf(
            "com.tencent.mm.ui.widget.imageview.WeImageView",
            "com.tencent.mm.ui.widget.imageview.WeImageButton"
        )) {
            val clazz = clazzName.toClass()
            clazz.reflekt().firstConstructor { parameterCount = 2 }.hookAfter {
                val imageView = thisObject as? ImageView ?: return@hookAfter
                val attributeSet = args.getOrNull(1) as? AttributeSet ?: return@hookAfter
                imageView.context.withStyledAttributes(
                    attributeSet,
                    intArrayOf(16843033)
                ) {
                    val resourceId = getResourceId(0, 0)
                    if (resourceId != 0) {
                        val resName = imageView.context.resources.getResourceName(resourceId)
                        val shortName = resName.substring(resName.lastIndexOf('/') + 1)
                        imageView.setTag(RES_TAG, shortName)
                        for (listener in ejListeners) listener(imageView, shortName)
                    }
                }
            }
            clazz.reflekt().firstMethod { name = "setImageResource" }.hookAfter {
                val imageView = thisObject as? ImageView ?: return@hookAfter
                val resId = args.getOrNull(0) as? Int ?: return@hookAfter
                if (resId != 0) {
                    val resName = imageView.context.resources.getResourceName(resId)
                    val shortName = resName.substring(resName.lastIndexOf('/') + 1)
                    imageView.setTag(RES_TAG, shortName)
                    for (listener in ejListeners) listener(imageView, shortName)
                }
            }
        }
        // M8(0)：LauncherUI/ChattingUI/ConvBox 的标题栏图标
        ejListeners += { imageView, resName ->
            val contextName = imageView.context.javaClass.name
            if (contextName == "com.tencent.mm.ui.LauncherUI" ||
                contextName == "com.tencent.mm.ui.chatting.ChattingUI" ||
                contextName == "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
            ) {
                ACTIONBAR_ICONS[resName]?.let { drawableName ->
                    themedDrawable(drawableName)?.let { d ->
                        disableColorFilter(imageView)
                        imageView.scaleType = ImageView.ScaleType.CENTER
                        imageView.setImageDrawable(d)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Dex 解析（cherrywechat FK.cachedMethods 对应项，8.0.65–8.0.76 均存在）
    // ------------------------------------------------------------------

    private val methodChattingBackgroundComponentInitBg by dexMethod {
        matcher {
            usingStrings("MicroMsg.ChattingUI.ChattingBackgroundComponent", "initBackground:")
        }
    }

    private val methodHistoryMsgTongueShow1 by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.HistoryMsgTongueComponent", "[update] mGoBackToHistoryMsgLayout VISIBLE")
        }
    }

    private val methodHistoryMsgTongueShow2 by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.HistoryMsgTongueComponent", "[showMsgAtSomeoneTongue] has show!!!")
        }
    }

    private val methodHomeUiUpdateStatusBar by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.LauncherUI.HomeUI", "updateStatusBar %s")
        }
    }

    private val methodHomeUiSetActionBarColor by dexMethod {
        matcher {
            usingEqStrings("com/tencent/mm/ui/HomeUI", "setActionBarColor")
        }
    }

    private val methodMoreTabUiOnTabCreate by dexMethod {
        matcher {
            usingStrings("MicroMsg.MoreTabUI", "onTabCreate:  %s")
        }
    }

    private val methodMoreTabUiHandleTabSwitchInForStatus by dexMethod {
        matcher {
            usingStrings("MicroMsg.MoreTabUI", "handleTabSwitchInForStatus wiht no status")
        }
    }

    private val methodFMessageContactViewInitView by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.FMessageContactView", "initNoNew failed. context is null.")
        }
    }

    private val classEnterpriseBizViewItem by dexClass {
        matcher {
            usingStrings("MicroMsg.EnterpriseBizViewItem", "contact is null, %s")
        }
    }

    private val classEnterpriseDefaultViewItem by dexClass {
        matcher {
            usingStrings("openim_acct_type_icon", "cloudim", "openim_acct_type_title")
        }
    }

    private val classAddressItemConvert by dexClass {
        matcher {
            // 两个 AddressItemConvert 候选类共用日志 tag，用 addressbook_cell 消歧
            usingStrings("MicroMsg.Mvvm.AddressItemConvert", "onBindViewHolder ", "addressbook_cell")
        }
    }

    private val classMmPopupWindow by dexClass {
        matcher {
            usingStrings("MicroMsg.MMPopupWindow", "dismiss exception, e = ")
        }
    }

    private val classSmileyTabAdapter by dexClass {
        matcher {
            usingStrings("MicroMsg.emoji.SmileyPanel.SmileyTabAdapter", "setSelection: %s")
        }
    }

    // 8.0.65 中 BaseSettingConvert/setOptionView 不存在，8.0.67+ 才有
    private val classBaseSettingConvert by dexClass(allowFailure = true) {
        matcher {
            usingStrings("BaseSettingConvert", "setOptionView")
        }
    }

    // ------------------------------------------------------------------
    // Hook 入口（cherrywechat P5 全部 11 组 + EJ + C0465dy）
    // ------------------------------------------------------------------

    override fun onEnable() {
        loadCurrentTheme()
        registerEjImageHooks()
        hookB() // BackgroundBanHook
        hookC() // BounceViewHook
        hookD() // ChatHook
        hookE() // ConversationUIHook
        hookF() // HomeUIHook
        hookG() // MMActivityHook
        hookH() // MMSwitchBtnHook
        hookI() // PopupWindowHook
        hookJ() // SettingActivityHook
        hookK() // SplashHook
        hookL() // TextColorBanHook
        hookPreference() // C0465dy PreferenceHook
    }

    /** P5.b —— View.setBackgroundDrawable 拦截（C0838j 17） */
    private fun hookB() {
        View::class.reflekt().firstMethod { name = "setBackgroundDrawable" }.hookBefore {
            val view = thisObject as? View ?: return@hookBefore
            if (view.getTag(VIEW_TAG) == any) args[0] = null
        }
    }

    /** P5.c —— WeUIBounceViewV2 背景色置 0（C0838j 18） */
    private fun hookC() {
        val weUiBounceViewV2 = "com.tencent.mm.ui.widget.pulldown.WeUIBounceViewV2".toClass().reflekt()
        listOf(
            "setStart2EndBgColorByActionBar",
            "setEnd2StartBgColorByNavigationBar",
            "setStart2EndBgColor",
            "setEnd2StartBgColor",
            "setBgColor"
        ).forEach {
            weUiBounceViewV2.firstMethod { name = it }.hookBefore {
                val view = thisObject as? View ?: return@hookBefore
                if (view.context.javaClass.name != "com.tencent.mm.ui.LauncherUI") return@hookBefore
                args[0] = 0
            }
        }
    }

    /** G8(0)：标题栏文字颜色 + 状态栏透明 */
    private fun g8Case0(viewGroup: ViewGroup, activity: Activity) {
        setActionbarUnreadBadge(viewGroup)
        setStatusBarTransparent(activity)
    }

    /** G8(1)：状态栏透明 + 内容区不绘制系统栏 */
    private fun g8Case1(viewGroup: ViewGroup, activity: Activity) {
        setStatusBarTransparent(activity)
        viewGroup.fitsSystemWindows = false
        viewGroup.setPadding(0, 0, 0, 0)
    }

    /** G8(2)：标题栏 + 设置页返回按钮 */
    private fun g8Case2(viewGroup: ViewGroup, activity: Activity) {
        setActionbarUnreadBadge(viewGroup)
        val upIndicator = viewGroup.findViewById<ViewGroup>(idActionbarUpIndicator) ?: return
        upIndicator.getChildAt(0).layoutParams.width = 0
        themedDrawable("settings/actionbar/back.png")?.let { d ->
            val iv = themedImageView(activity, d)
            iv.setOnClickListener { activity.finish() }
            upIndicator.addView(
                iv,
                0,
                LinearLayout.LayoutParams(withDensity(32), withDensity(32)).apply {
                    gravity = 16
                    leftMargin = withDensity(10)
                    rightMargin = withDensity(6)
                }
            )
        }
    }

    /** G8(3)：新版设置页背景 */
    private fun g8Case3(viewGroup: ViewGroup, activity: Activity) {
        callMethod(activity, "setActionbarColor", 0)
        callMethod(activity, "setIsDarkActionbarBg", false)
        val swipe = firstChildViewGroupWithClass(viewGroup, "com.tencent.mm.ui.widget.SwipeBackLayout")
        val swipeChild = (swipe as? ViewGroup)?.getChildAt(0) as? ViewGroup
        swipeChild?.setPadding(0, 0, 0, 0)
        firstChildViewGroupWithClass(
                viewGroup,
                "androidx.appcompat.widget.ActionBarContainer"
            )?.setPadding(0, statusBarHeight(activity), 0, 0)
        setNullBgRecursively(viewGroup)
        if (swipeChild != null) {
            addSettingActionbarBackground(swipeChild)
            setSettingActionbarIcons(swipeChild)
            themedDrawable("settings/background.png")?.let { d ->
                swipeChild.addView(
                    themedImageView(activity, d),
                    0,
                    ViewGroup.LayoutParams(-1, -1)
                )
            }
        }
    }

    /** H8(4)：VAS 设置页背景（W6.p 向上 5 层） */
    private fun h8Case4(viewGroup: ViewGroup) {
        var root: View = viewGroup.findViewById(idActionbarUpIndicator) ?: return
        repeat(5) {
            root = root.parent as? View ?: return
        }
        val container = root as? ViewGroup ?: return
        setNullBgRecursively(viewGroup)
        addSettingActionbarBackground(container)
        setSettingActionbarIcons(container)
        themedDrawable("settings/background.png")?.let { d ->
            container.addView(
                themedImageView(container.context, d),
                0,
                ViewGroup.LayoutParams(-1, -1)
            )
        }
    }

    /** I8(0)：标题栏文字颜色 + 状态栏透明 + 内容区不绘制系统栏 */
    private fun i8Case0(viewGroup: ViewGroup, activity: Activity, contentView: ViewGroup) {
        setActionbarUnreadBadge(viewGroup)
        setStatusBarTransparent(activity)
        contentView.fitsSystemWindows = false
        contentView.setPadding(0, 0, 0, 0)
    }

    /** I8 默认分支：指定 Activity 的设置页背景 */
    private fun i8Case2(activity: Activity, swipeChild: ViewGroup?) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val sibling = (content.parent as? ViewGroup)?.getChildAt(1) as? ViewGroup
        activity.window.statusBarColor = 0
        if (sibling != null) setNullBgRecursively(sibling)
        setNullBgRecursively(swipeChild)
        if (activity.javaClass.name !in listOf(
                "com.tencent.mm.plugin.setting.ui.setting.ColorfulChatroomQRCodeUI",
                "com.tencent.mm.plugin.setting.ui.setting.ColorfulSelfQRCodeUI",
                "com.tencent.mm.ui.chatting.search.multi.FTSChattingConvMultiTabUI",
                "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
            )
        ) {
            themedDrawable("settings/actionbar/background.png")?.let { d ->
                content.addView(
                    ImageView(content.context).apply { background = d },
                    0,
                    ViewGroup.LayoutParams(
                        -1,
                        statusBarHeight(activity) + actionBarHeightOf(activity)
                    )
                )
            }
        }
        if (sibling != null) setSettingActionbarIcons(sibling)
        if (activity.javaClass.name.contains("ColorfulSelfQRCodeUI")) {
            hideFullScreenImageViews(content)
        }
        themedDrawable("settings/background.png")?.let { d ->
            content.addView(
                themedImageView(activity, d),
                0,
                ViewGroup.LayoutParams(-1, -1)
            )
        }
    }

    /** P5.d —— ChatHook */
    private fun hookD() {
        // C0838j 19 —— ChattingUIFragment.dealContentView
        "com.tencent.mm.ui.chatting.ChattingUIFragment".toClass().reflekt()
            .firstMethod { name = "dealContentView" }.hookAfter {
                val contentView = thisObject as? ViewGroup ?: return@hookAfter
                val activity = contentView.context as? Activity ?: return@hookAfter
                val chatFooterFinder = {
                    contentView.findViewWhich {
                        it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"
                    } as ViewGroup?
                }
                when (activity.javaClass.name) {
                    "com.tencent.mm.ui.LauncherUI" -> {
                        val chattingUiLayout = contentView.findViewWhich {
                            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"
                        } as ViewGroup? ?: return@hookAfter
                        themedDrawable("chat/actionbar/background.png")?.let { d ->
                            val parent = chattingUiLayout.parent as? ViewGroup ?: return@let
                            parent.addView(
                                View(activity).apply { background = d },
                                0,
                                ViewGroup.LayoutParams(
                                    -1,
                                    actionBarHeightOf(activity) + statusBarHeight(activity)
                                )
                            )
                        }
                        setNullBg(chattingUiLayout)
                        val firstChild = chattingUiLayout.getChildAt(0) as? ViewGroup
                        if (firstChild != null) setNullBgRecursivelyWithTv(firstChild)
                        // G8(0)
                        firstChild?.post { g8Case0(firstChild, activity) }
                        // H8(1)
                        val footer = chatFooterFinder()
                        footer?.post { setChatFooterTheme(footer) }
                    }

                    "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI" -> {
                        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
                            ?: return@hookAfter
                        setNullBgRecursivelyWithTv(contentRoot)
                        val sibling = (contentRoot.parent as? ViewGroup)?.getChildAt(1) as? ViewGroup
                        if (sibling != null) setNullBgRecursivelyWithTv(sibling)
                        // G8(1)
                        contentRoot.post { g8Case1(contentRoot, activity) }
                        // G8(2)
                        contentView.post { g8Case2(contentView, activity) }
                        addSettingActionbarBackground(contentRoot)
                        firstChildViewGroupWithClass(contentRoot, ListView::class.java.name)?.let { list ->
                            list.setPadding(0, actionBarHeightOf(activity), 0, 0)
                            themedDrawable("settings/background.png")?.let { d ->
                                contentRoot.addView(
                                    themedImageView(list.context, d),
                                    0,
                                    ViewGroup.LayoutParams(-1, -1)
                                )
                            }
                        }
                        val chattingUiLayout = contentView.findViewWhich {
                            it.javaClass.name == "com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout"
                        } as ViewGroup?
                        val chatFooter = chatFooterFinder()
                        if (chattingUiLayout != null) {
                            setNullBg(chattingUiLayout)
                            chatFooter?.post { setChatFooterTheme(chatFooter) }
                            val chatChild = chattingUiLayout.getChildAt(0) as? ViewGroup
                            if (chatChild != null) {
                                // H8(0)
                                chatChild.post { setActionbarUnreadBadge(chatChild) }
                                setNullBgRecursivelyWithTv(chatChild)
                                val wrapper = FrameLayout(activity).apply { elevation = 1f }
                                themedDrawable("chat/actionbar/background.png")?.let { d ->
                                    wrapper.addView(
                                        ImageView(wrapper.context).apply { background = d },
                                        0,
                                        ViewGroup.LayoutParams(
                                            -1,
                                            actionBarHeightOf(activity) + statusBarHeight(activity)
                                        )
                                    )
                                }
                                val column = LinearLayout(wrapper.context).apply {
                                    orientation = LinearLayout.VERTICAL
                                }
                                column.addView(Space(wrapper.context), -1, statusBarHeight(activity))
                                chattingUiLayout.removeView(chatChild)
                                column.addView(chatChild, -1, -2)
                                wrapper.addView(column, -1, -2)
                                (chattingUiLayout.parent as? ViewGroup)
                                    ?.addView(wrapper, 0, ViewGroup.LayoutParams(-1, -2))
                                chattingUiLayout.addView(
                                    Space(activity),
                                    0,
                                    ViewGroup.LayoutParams(-1, actionBarHeightOf(activity))
                                )
                            }
                        }
                    }

                    else -> {
                        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
                            ?: return@hookAfter
                        val sibling = (contentRoot.parent as? ViewGroup)?.getChildAt(1) as? ViewGroup
                        if (sibling != null) {
                            // I8(0)
                            sibling.post { i8Case0(sibling, activity, contentRoot) }
                            setNullBgRecursivelyWithTv(sibling)
                        }
                        themedDrawable("chat/actionbar/background.png")?.let { d ->
                            contentRoot.addView(
                                View(activity).apply {
                                    background = d
                                    elevation = 1f
                                },
                                0,
                                ViewGroup.LayoutParams(
                                    -1,
                                    actionBarHeightOf(activity) + statusBarHeight(activity)
                                )
                            )
                        }
                        val footer = chatFooterFinder()
                        footer?.post { setChatFooterTheme(footer) }
                    }
                }
            }

        // C0838j 20 —— ChattingBackgroundComponent.initBackground
        methodChattingBackgroundComponentInitBg.hookAfter {
            themedDrawable("chat/background.png")?.let { d ->
                val imageView = fieldValueOfType(thisObject, ImageView::class.java) as? ImageView
                    ?: return@let
                val firstChild = (imageView.parent as? ViewGroup)?.getChildAt(0) as? ImageView
                if (firstChild?.drawable is ColorDrawable) firstChild.setImageDrawable(d)
            }
        }

        // C0838j 21 —— ChattingImageBGView 构造函数
        "com.tencent.mm.ui.chatting.ChattingImageBGView".toClass().constructors.forEach { ctor ->
            ctor.hookAfter {
                themedDrawable("chat/background.png")?.let { d ->
                    (thisObject as? ImageView)?.setImageDrawable(d)
                }
            }
        }

        // C0838j 22 —— ChattingDataAdapterV3.onBindViewHolder
        WeMessageApi.methodChattingDataAdapterOnBindViewHolder.hookAfter {
            val holderView = args.getOrNull(0) ?: return@hookAfter
            val position = args.getOrNull(1) as? Int ?: return@hookAfter
            applyChatBubbleTheme(holderView, position, thisObject)
        }

        // C0838j 23 —— MMKRichText 链接颜色
        runCatching {
            val linkSpanClass = $$"com.tencent.kinda.framework.widget.base.MMKRichText$MMKLink$LinkClickableSpan"
            linkSpanClass.toClass().superclass!!.reflekt()
                .firstMethod {
                    name = "setColor"
                    parameters(Int::class, Int::class)
                }.hookBefore {
                    val span = thisObject ?: return@hookBefore
                    if (span.javaClass.superclass?.canonicalName != ClickableSpan::class.java.canonicalName) {
                        return@hookBefore
                    }
                    val color = themeColor("chat.link_text", 0)
                    if (color == 0) return@hookBefore
                    val c1 = args.getOrNull(0) as? Int ?: return@hookBefore
                    val c2 = args.getOrNull(1) as? Int ?: return@hookBefore
                    if (c1 == -11048043 && c2 == 436207616 || c1 == -13152126 && c2 == 234881023
                    ) {
                        args[0] = color
                        args[1] = Color.argb(66, Color.red(color), Color.green(color), Color.blue(color))
                    }
                }
        }.onFailure {
            WeLogger.w(TAG, "hook link color failed", it)
        }

        // N8 5 —— 历史消息小舌头（show1/show2）
        listOf(
            methodHistoryMsgTongueShow1,
            methodHistoryMsgTongueShow2
        ).forEach { method ->
            method.hookAfter {
                val textView = fieldValueOfType(thisObject, TextView::class.java) as? TextView
                    ?: return@hookAfter
                themeColor("chat.history_tongue.text", 0).takeIf { it != 0 }
                    ?.let { textView.setTextColor(it) }
                val parent = textView.parent as? ViewGroup ?: return@hookAfter
                themedDrawable("chat/history_tongue_background.png")?.let { parent.background = it }
                val arrow = parent.getChildAt(0) as? ImageView ?: return@hookAfter
                val up = themedDrawable("chat/history_tongue_arrow_up.png")
                val down = themedDrawable("chat/history_tongue_arrow_down.png")
                when {
                    up != null && arrow.rotation == 0f -> {
                        disableColorFilter(arrow)
                        arrow.setImageDrawable(up)
                    }
                    down != null && arrow.rotation == 180f -> {
                        disableColorFilter(arrow)
                        arrow.setImageDrawable(down)
                    }
                }
            }
        }

        // N8 2/3 + P8(0) —— 红包打开页
        val luckyMoneyClass = runCatching {
            HostInfo.application.classLoader.loadClass(
                "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNewReceiveUI"
            )
        }.getOrElse {
            runCatching {
                HostInfo.application.classLoader.loadClass(
                    "com.tencent.mm.plugin.luckymoney.ui.LuckyMoneyNotHookReceiveUI"
                )
            }.getOrNull()
        }
        if (luckyMoneyClass != null) {
            luckyMoneyClass.reflekt().firstMethod { name = "onCreate" }.hookBefore {
                val intent = (thisObject as? Activity)?.intent ?: return@hookBefore
                intent.removeExtra("key_receive_envelope_fission_info")
                intent.putExtra("key_material_flag", 0)
                intent.putExtra("key_has_story", false)
                intent.putExtra("key_way", 0)
                intent.putExtra("key_receive_envelope_widget_status_flag", 0)
                intent.removeExtra("key_receive_envelope_dynamic_md5")
                intent.removeExtra("key_receive_envelope_dynamic_url")
                intent.putExtra("key_packet_source", 0)
                intent.putExtra("key_receive_envelope_dynamic_type", 0)
                intent.removeExtra("key_packet_id")
                intent.removeExtra("key_receive_envelope_md5")
                intent.removeExtra("key_receive_envelope_url")
                intent.removeExtra("key_detail_envelope_md5")
                intent.removeExtra("key_detail_envelope_url")
                intent.removeExtra("key_receive_envelope_widget_md5")
                intent.removeExtra("key_receive_envelope_widget_url")
                intent.removeExtra("key_detail_envelope_dynamic_md5")
                intent.removeExtra("key_detail_envelope_dynamic_url")
                intent.removeExtra("key_about_url")
                intent.removeExtra("key_receive_envelope_atmosphere_dynamic_url")
                intent.removeExtra("key_detail_envelope_atmosphere_dynamic_url")
                val nativeUrl = intent.getStringExtra("key_native_url")
                intent.putExtra("key_native_url", nativeUrl?.replace("showsourcemac", "1"))
            }
            luckyMoneyClass.reflekt().firstMethod { name = "initView" }.hookAfter {
                themedDrawable("chat/red_packet_background.png")?.let { bg ->
                    val activity = thisObject as? Activity ?: return@let
                    activity.intent.putExtra("hook", true)
                    val root = fieldValueOfType(activity, ViewGroup::class.java) as? ViewGroup
                        ?: return@let
                    viewByPath(root, 1, 0, 0, 1, 1)?.visibility = View.INVISIBLE
                    viewByPath(root, 1, 0, 0, 1, 5)?.alpha = 0f
                    viewByPath(root, 1, 0, 0, 1, 6)?.let {
                        setThemedBackground(it, themedDrawable("chat/red_packet_open.png"))
                    }
                    viewByPath(root, 1, 0, 0, 1)?.let { setThemedBackground(it, bg) }
                    themeColor("chat.red_packet.open_text", 0).takeIf { it != 0 }?.let { c ->
                        (viewByPath(root, 1, 0, 0, 1, 3, 0, 1) as? TextView)?.setTextColor(c)
                        (viewByPath(root, 1, 0, 0, 1, 3, 2) as? TextView)?.setTextColor(c)
                    }
                }
            }
            luckyMoneyClass.reflekt().firstMethod { name = "onSceneEnd" }.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                if (!activity.intent.getBooleanExtra("hook", false)) return@hookAfter
                themeColor("chat.red_packet.open_text", 0).takeIf { it != 0 }?.let { c ->
                    for (field in luckyMoneyClass.declaredFields) {
                        if (field.type != TextView::class.java) continue
                        field.isAccessible = true
                        val tv = runCatching { field.get(activity) }.getOrNull() as? TextView
                            ?: continue
                        if (tv.text?.toString() == "看看大家的手气") {
                            tv.setTextColor(c)
                            val parent = tv.parent as? ViewGroup ?: continue
                            val next = parent.getChildAt(parent.indexOfChild(tv) + 1) as? ImageView
                            next?.colorFilter = PorterDuffColorFilter(
                                Color.rgb(Color.red(c), Color.green(c), Color.blue(c)),
                                PorterDuff.Mode.MULTIPLY
                            )
                            next?.imageAlpha = Color.alpha(c)
                        }
                    }
                }
            }
        }

        // C0838j 26 —— MMEditText 构造函数
        EditText::class.java.constructors.forEach { ctor ->
            ctor.hookAfter {
                val editText = thisObject as? EditText ?: return@hookAfter
                if (editText.javaClass.name != "com.tencent.mm.ui.widget.MMEditText") return@hookAfter
                themeString("chat.input.hint", "").takeIf { it.isNotEmpty() }
                    ?.let { editText.setHint(it) }
                editText.setHintTextColor(themeColor("chat.input.hint_text", -7829368))
                themeColor("chat.input.text", 0).takeIf { it != 0 }
                    ?.let { setThemedTextColor(editText, it) }
            }
        }

        // C0838j 27 —— SpeechInputLayout 构造函数
        "com.tencent.mm.pluginsdk.ui.SpeechInputLayout".toClass().reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                val layout = thisObject as? FrameLayout ?: return@hookAfter
                themedDrawable("chat/speech_speed.png")?.let { d ->
                    if (layout.isNotEmpty()) layout.getChildAt(0).alpha = 0f
                    layout.addView(
                        themedImageView(layout.context, d),
                        FrameLayout.LayoutParams(withDensity(25), withDensity(25)).apply {
                            gravity = 17
                        }
                    )
                }
            }
        }

        // C0838j 28 —— 表情面板 tab
        classSmileyTabAdapter.clazz.reflekt()
            .firstMethod { name = "onBindViewHolder" }.hookAfter {
                val holder = args.getOrNull(0) ?: return@hookAfter
                val position = args.getOrNull(1) as? Int ?: return@hookAfter
                val drawableName = when (position) {
                    0 -> "chat/emoji_tabs/search.png"
                    1 -> "chat/emoji_tabs/system.png"
                    2 -> "chat/emoji_tabs/favorites.png"
                    else -> "chat/emoji_tabs/custom.png"
                }
                for (field in holder.javaClass.fields) {
                    if (field.type != ImageView::class.java) continue
                    val imageView = runCatching { field.get(holder) }.getOrNull() as? ImageView
                        ?: continue
                    val parent = imageView.parent as? ViewGroup ?: continue
                    val d = themedDrawable(drawableName) ?: continue
                    if (parent.getChildAt(parent.childCount - 1).tag == "111") continue
                    for (i in 0 until parent.childCount) parent.getChildAt(i).alpha = 0f
                    parent.addView(
                        ImageView(parent.context).apply {
                            setImageDrawable(d)
                            tag = "111"
                        },
                        RelativeLayout.LayoutParams(withDensity(30), withDensity(30)).apply {
                            addRule(RelativeLayout.CENTER_IN_PARENT)
                        }
                    )
                    break
                }
            }

        // C0838j 29 —— 聊天「+」面板
        WeChatInputBarMenuApi.methodAppGridGetView.hookAfter {
            val view = result as? View ?: return@hookAfter
            for ((resName, drawableName) in CHAT_PLUS_ICONS) {
                val imageView = findImageViewByResTag(view as? ViewGroup, resName) ?: continue
                val wrapper = imageView.parent?.parent as? ViewGroup ?: continue
                val iconRow = wrapper.getChildAt(0) as? ViewGroup ?: continue
                themeColor("chat.plus_panel.icon_text", 0).takeIf { it != 0 }?.let { c ->
                    (wrapper.getChildAt(1) as? TextView)?.let { setThemedTextColor(it, c) }
                }
                val target: ImageView
                if (iconRow.childCount == 3) {
                    for (i in 0 until 3) iconRow.getChildAt(i).alpha = 0f
                    target = ImageView(iconRow.context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    iconRow.addView(
                        target,
                        RelativeLayout.LayoutParams(withDensity(40), withDensity(40)).apply {
                            addRule(RelativeLayout.CENTER_IN_PARENT)
                        }
                    )
                } else {
                    target = iconRow.getChildAt(3) as? ImageView ?: continue
                }
                themedDrawable(drawableName)?.let { target.setImageDrawable(it) }
            }
        }

        // N8 0 —— ChatTipsBarGroup 构造函数
        "com.tencent.mm.ui.tipsbar.ChatTipsBarGroup".toClass().reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                setNullBgRecursivelyWithTv(thisObject as? ViewGroup)
            }
        }

        // N8 1 —— EmojiPanelSlideIndicatorView 构造函数
        "com.tencent.mm.view.EmojiPanelSlideIndicatorView".toClass().reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                setNullBg(thisObject as? View)
            }
        }

        // G(7) —— TestTimeForChatting.fitSystemWindows
        "com.tencent.mm.ui.tools.TestTimeForChatting".toClass().reflekt()
            .firstMethod { name = "fitSystemWindows" }.hookBefore {
                result = false
            }

        // N8 6 → H8(2) —— ChatFooterCustom 底部背景
        "com.tencent.mm.ui.chatting.ChatFooterCustom".toClass().reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                val viewGroup = thisObject as? ViewGroup ?: return@hookAfter
                viewGroup.postDelayed(100L) { setChatFooterCustomTheme(viewGroup) }
            }
        }
    }

    /** C0838j 22 的气泡主题（忠实复刻 holder 字段约定） */
    private fun applyChatBubbleTheme(holderView: Any, position: Int, adapter: Any?) {
        val itemView = fieldValueOfType(holderView, View::class.java) as? View ?: return
        val tag = itemView.tag ?: return
        val item = callMethod(adapter, "getItem", position) ?: return
        val type = callMethod(item, "getType") as? Int ?: return
        val isRight = fieldValueByName(item, "field_isSend") as? Int == 1
        val clickArea = fieldValueByName(tag, "clickArea") as? View
        val timeTV = fieldValueByName(tag, "timeTV") as? TextView
        val userTV = fieldValueByName(tag, "userTV") as? TextView
        val mainContainer = callMethod(tag, "getMainContainerView") as? View

        themeColor("chat.nickname_text", 0).takeIf { it != 0 }?.let { c ->
            userTV?.setTextColor(c)
        }
        when (type) {
            1, 16777265, 822083633, 805306417 -> setTextBubbleTheme(tag, isRight)
            570425393, 10000, 268445456, 922746929 -> setGrayTipsColor(tag)

            // 文件消息
            1090519089 -> {
                themedDrawable(
                    if (isRight) "chat/bubbles/file_right.png" else "chat/bubbles/file_left.png"
                )?.let { d ->
                    clickArea?.foreground = null
                    declaredViewsOfType(tag, LinearLayout::class.java)
                        .forEach { it.background = null }
                    val color = themeColor(
                        if (isRight) "chat.file_bubble.right_text" else "chat.file_bubble.left_text",
                        0
                    )
                    if (color != 0) {
                        fieldValueOfType(
                                                tag,
                                                "com.tencent.mm.ui.widget.MMNeat7extView".toClass()
                                            )?.reflekt()?.invokeMethod("setTextColor", color)
                    }
                    (mainContainer?.parent?.parent as? ViewGroup)?.let { parent ->
                        setBubbleChildrenBackgrounds(parent, color, color != 0, isRight)
                    }
                    clickArea?.background = d
                }
            }

            // 语音气泡
            34 -> {
                val container = mainContainer as? ViewGroup
                container?.getChildAt(0)?.background = null
                val d = themedDrawable(
                    if (isRight) "chat/bubbles/text_right.png" else "chat/bubbles/text_left.png"
                )
                if (d != null && container != null) {
                    container.background = d
                    setBubbleSidePadding(container, isRight)
                }
                for (field in tag.javaClass.declaredFields) {
                    if (field.type.name != "com.tencent.mm.ui.base.AnimImageView") continue
                    field.isAccessible = true
                    val anim = runCatching { field.get(tag) }.getOrNull() as? View ?: continue
                    if (anim.parent is FrameLayout) {
                        anim.background = d
                        val frame = anim.parent as ViewGroup
                        for (i in 0 until frame.childCount) {
                            val child = frame.getChildAt(i)
                            if (child is TextView &&
                                child.javaClass.name == TextView::class.java.name
                            ) {
                                val color = themeColor(
                                    if (isRight) "chat.text_bubble.right_text"
                                    else "chat.text_bubble.left_text",
                                    0
                                )
                                if (color != 0) {
                                    child.compoundDrawables[if (isRight) 2 else 0]?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
                                    child.setTextColor(color)
                                }
                            }
                        }
                    }
                }
            }

            // 转账消息
            419430449 -> {
                val received = declaredViewsOfType(tag, TextView::class.java)
                    .any { (it as? TextView)?.text?.toString()?.contains("已") == true }
                val d = when {
                    received && isRight -> themedDrawable("chat/bubbles/transfer_right_received.png")
                    received -> themedDrawable("chat/bubbles/transfer_left_received.png")
                    isRight -> themedDrawable("chat/bubbles/transfer_right.png")
                    else -> themedDrawable("chat/bubbles/transfer_left.png")
                }
                if (d != null) {
                    mainContainer?.background = d
                    mainContainer?.let { setBubbleSidePadding(it, isRight) }
                    val color = themeColor(
                        if (received) {
                            if (isRight) "chat.transfer_bubble.right_received_text"
                            else "chat.transfer_bubble.left_received_text"
                        } else {
                            if (isRight) "chat.transfer_bubble.right_text"
                            else "chat.transfer_bubble.left_text"
                        },
                        -65536
                    )
                    if (color != 0) {
                        declaredViewsOfType(tag, TextView::class.java)
                            .forEach { (it as? TextView)?.setTextColor(color) }
                    }
                    themedDrawable(
                        if (isRight) "chat/bubbles/transfer_right_icon.png"
                        else "chat/bubbles/transfer_left_icon.png"
                    )?.let { icon ->
                        declaredViewsOfType(tag, ImageView::class.java)
                            .forEach { (it as? ImageView)?.setImageDrawable(icon) }
                    }
                }
            }

            // 红包消息
            436207665 -> {
                val bubble = mainContainer as? ViewGroup ?: return
                val mmTextParent = firstChildViewGroupWithClass(
                    bubble,
                    "com.tencent.mm.ui.widget.MMTextView"
                )?.parent as? ViewGroup
                val texts = mmTextParent?.let { g -> (0 until g.childCount).map { g.getChildAt(it) } }
                    .orEmpty()
                themeColor("chat.red_packet_bubble.left_text", 0).takeIf { it != 0 }?.let { c ->
                    (texts.getOrNull(0) as? TextView)?.setTextColor(c)
                }
                themeColor("chat.red_packet_bubble.right_text", 0).takeIf { it != 0 }?.let { c ->
                    (texts.getOrNull(1) as? TextView)?.setTextColor(c)
                }
                val receivedVisible = texts.getOrNull(1)?.visibility == View.VISIBLE
                val d = when {
                    receivedVisible && isRight -> themedDrawable("chat/bubbles/red_packet_right_received.png")
                    receivedVisible -> themedDrawable("chat/bubbles/red_packet_left_received.png")
                    isRight -> themedDrawable("chat/bubbles/red_packet_right.png")
                    else -> themedDrawable("chat/bubbles/red_packet_left.png")
                }
                if (d != null) {
                    ((texts.getOrNull(1)?.parent as? ViewGroup)?.getChildAt(0) as? ImageView)
                        ?.setImageDrawable(themedDrawable("chat/red_packet.png"))
                    clickArea?.background = d
                    clickArea?.let { setBubbleSidePadding(it, isRight) }
                    firstChildViewGroupWithClass(
                        bubble,
                        "com.tencent.mm.ui.chatting.view.BubbleCornorLayout"
                    )?.let { setNullBg(it) }
                    findViewWithText(bubble, "试用")?.let { setNullBg(it.parent as? View) }
                }
            }

            // 文本气泡
            50 -> {
                val container = mainContainer as? ViewGroup
                container?.background = themedDrawable(
                    if (isRight) "chat/bubbles/text_right.png" else "chat/bubbles/text_left.png"
                )
                container?.let { setBubbleSidePadding(it, isRight) }
                val color = themeColor(
                    if (isRight) "chat.text_bubble.right_text" else "chat.text_bubble.left_text",
                    0
                )
                if (color != 0 && container != null) {
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (child is TextView) {
                            child.setTextColor(color)
                        } else if (child.background != null) {
                            val mutated = child.background.mutate()
                            mutated.colorFilter = PorterDuffColorFilter(
                                color or 0xFF000000.toInt(),
                                PorterDuff.Mode.SRC_ATOP
                            )
                            child.background = mutated
                        }
                    }
                }
            }
        }
        themeColor("chat.tips_text", 0).takeIf { it != 0 }?.let { c ->
            timeTV?.setTextColor(c)
        }
    }

    /** P5.e —— ConversationUIHook */
    @SuppressLint("SetTextI18n")
    private fun hookE() {
        // N8 29 —— 会话列表项
        HeaderViewListAdapter::class.reflekt()
            .firstMethod { name = "getView" }.hookAfter {
                val view = result as? View ?: return@hookAfter
                if (view.context.javaClass.name != "com.tencent.mm.ui.LauncherUI" ||
                    view !is ViewGroup
                ) {
                    return@hookAfter
                }
                val tag = view.tag ?: return@hookAfter
                val isTop = fieldValueOfType(
                    tag,
                    Boolean::class.javaPrimitiveType!!
                ) as? Boolean ?: return@hookAfter
                val nicknameTv = view.findViewWithTag<View>("nickname_tv") as? TextView
                val updateTimeTv = view.findViewWithTag<View>("update_time_tv") as? TextView
                val lastMsgTv = view.findViewWithTag<View>("last_msg_tv") as? TextView
                val unreadNumIv = viewByPath(view, 0, 0, 2) as? ImageView
                val unreadNumTv = viewByPath(view, 0, 0, 1) as? TextView
                val item = (thisObject as? HeaderViewListAdapter)
                    ?.getItem(args.getOrNull(0) as? Int ?: 0)
                if (item != null) callMethod(item, "convertTo")
                view.backgroundTintList = ColorStateList.valueOf(0)
                setThemedBackground(
                    view.getChildAt(0),
                    themedDrawable(
                        if (isTop) "home/conversation_top_item_background.png"
                        else "home/conversation_item_background.png"
                    )
                )
                themeColor("home.conversation_item.red_tip", 0).takeIf { it != 0 }?.let { c ->
                    unreadNumIv?.backgroundTintList = ColorStateList.valueOf(c)
                }
                themedDrawable("home/conversation_item_unread_badge.png")?.let { d ->
                    if (unreadNumTv != null) {
                        val text = unreadNumTv.text
                        if ((text == null || text.isEmpty()) &&
                            unreadNumTv.isVisible
                        ) {
                            unreadNumTv.text = "99+"
                        }
                        unreadNumTv.background = d
                        themeColor("home.conversation_item.unread_badge_text", 0)
                            .takeIf { it != 0 }?.let { unreadNumTv.setTextColor(it) }
                    }
                }
                themeColor("home.conversation_item.primary_text", 0).takeIf { it != 0 }?.let { c ->
                    nicknameTv?.let { callMethod(it, "setTextColor", c) }
                }
                themeColor("home.conversation_item.secondary_text", 0).takeIf { it != 0 }?.let { c ->
                    updateTimeTv?.let { callMethod(it, "setTextColor", c) }
                    lastMsgTv?.let { callMethod(it, "setTextColor", c) }
                }
                val lastChild = view.getChildAt(view.childCount - 1) as? ViewGroup
                if (lastChild != null) {
                    setNullBgRecursivelyWithTv(lastChild)
                    if (view.findViewWithTag<View>(VIEW_TAG_HIDDEN) == null) {
                        val list = mutableListOf<View>()
                        collectHeightOneViews(view, list)
                        list.firstOrNull()?.let { hidden ->
                            hidden.alpha = 0f
                            view.setTag(VIEW_TAG_HIDDEN, hidden)
                        }
                    }
                }
            }

        // C0540fd 0 —— getEmptyFooter
        ConversationListView::class.reflekt()
            .firstMethod { name = "getEmptyFooter" }.hookAfter {
                setNullBgRecursivelyWithTv(result as? ViewGroup)
            }

        // C0540fd 1 —— MainUI.onCreate
        MainUI::class.reflekt()
            .firstMethod { parameters(Bundle::class) }.hookAfter {
                val conversationListView = fieldValueOfType(
                    thisObject,
                    "com.tencent.mm.ui.conversation.ConversationListView".toClass()
                ) as? View ?: return@hookAfter
                setNullBg(conversationListView)
                (conversationListView.parent as? ViewGroup)?.getChildAt(3)?.let { setNullBg(it) }
            }

        // C0540fd 3 —— TaskBarContainer 构造函数：隐藏 TaskBarBottomView 兄弟项
        "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer".toClass().reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                val bottom = fieldValueOfType(
                    thisObject,
                    "com.tencent.mm.plugin.taskbar.ui.TaskBarBottomView".toClass()
                ) as? View
                val parent = bottom?.parent as? ViewGroup ?: return@hookAfter
                for (i in 0 until parent.childCount) {
                    val lp = parent.getChildAt(i).layoutParams
                    lp.width = 0
                    lp.height = 0
                }
            }
        }

        // N8 25 —— attachViewToParent
        ConversationListView::class.reflekt()
            .firstMethod { name = "attachViewToParent" }.hookAfter {
                val obj = thisObject ?: return@hookAfter
                for (field in obj.javaClass.fields) {
                    if (field.type != View::class.java) continue
                    val view = runCatching { field.get(obj) }.getOrNull() as? View ?: continue
                    if (view.javaClass != View::class.java) continue
                    val lp = view.layoutParams
                    if (lp is FrameLayout.LayoutParams && lp.width == -1) {
                        setNullBg(view)
                    }
                }
            }

        // N8 27 —— ConversationListView 构造函数：Paint 置零
        ConversationListView::class.java.constructors.forEach { ctor ->
            ctor.hookAfter {
                val obj = thisObject ?: return@hookAfter
                for (field in obj.javaClass.fields) {
                    if (field.type != Paint::class.java) continue
                    field.isAccessible = true
                    runCatching { field.set(obj, ZeroColorPaint(1)) }
                }
            }
        }

        // FD 13 —— DynamicBgContainer / GradientColorBackgroundView / TaskBarBottomView 绘制置空
        "com.tencent.mm.plugin.multitask.ui.bg.DynamicBgContainer".toClass().reflekt()
            .firstMethod {
                modifiers(Modifiers.SYNCHRONIZED)
                parameterCount = 0
            }.hookBefore {
                result = null
            }
        "com.tencent.mm.dynamicbackground.view.GradientColorBackgroundView".toClass().reflekt()
            .firstMethod { name = "onDraw" }.hookBefore {
                result = null
            }
        "com.tencent.mm.plugin.taskbar.ui.TaskBarBottomView".toClass().reflekt()
            .firstMethod { name = "onDraw" }.hookBefore {
                result = null
            }

        // N8 28 —— TaskBarContainer 遮罩
        "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer".toClass().reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                val container = thisObject as? ViewGroup ?: return@hookAfter
                val mask = View(container.context).apply {
                    background = themeColor("home.taskbar.mask", 1426063360).toDrawable()
                }
                container.addView(
                    mask,
                    0,
                    FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = withDensity(100) }
                )
            }
        }

        // C0540fd 4 —— AppBrandDesktopContainerView：桌面背景
        "com.tencent.mm.plugin.appbrand.widget.desktop.AppBrandDesktopContainerView".toClass().reflekt()
            .firstConstructor { parameterCount = 3 }.hookAfter {
                val containerView = thisObject as? ViewGroup ?: return@hookAfter
                val child = containerView.getChildAt(0) as? ViewGroup ?: return@hookAfter
                val drawable = themedDrawable("home/conversation_background.png")
                    ?: themedDrawable("home/background.png")
                if (drawable == null) return@hookAfter
                val imageView = themedImageView(child.context, drawable)
                callMethod(
                    imageView,
                    "setMaskColor",
                    themeColor("home.taskbar.mask", 0x55000000)
                )
                child.addView(imageView, 0, ViewGroup.LayoutParams(-1, -1))
            }

        // C0540fd 2 —— setFoldBanner
        ConversationListView::class.reflekt()
            .firstMethod { name = "setFoldBanner" }.hookAfter {
                val banner = args.getOrNull(0) as? ViewGroup ?: return@hookAfter
                setNullBgRecursivelyWithTv(banner)
                setThemedBackground(
                    banner,
                    (if (!banner.context.isDarkMode) -285212673 else -301989888).toDrawable()
                )
            }
    }

    /** P5.f —— HomeUIHook */
    private fun hookF() {
        // C0540fd 17 —— LauncherUI.onCreateOptionsMenu
        LauncherUI::class.reflekt()
            .firstMethod { name = "onCreateOptionsMenu" }.hookAfter {
                val menu = args.getOrNull(0) as? Menu ?: return@hookAfter
                if (menu.size != 2) return@hookAfter
                val item0 = menu[0]
                val item1 = menu[1]
                menu.clear()
                val search = menu.add(0, item0.itemId, 0, item0.title)
                search.icon = themedDrawable("home/actionbar/search.png") ?: item0.icon
                val plus = menu.add(0, item1.itemId, 0, item1.title)
                plus.icon = themedDrawable("home/actionbar/plus.png") ?: item1.icon
            }

        // C0540fd 18 —— LauncherUI.onCreate：记录 actionbar 高度
        LauncherUI::class.reflekt()
            .firstMethod { name = "onCreate" }.hookAfter {
                actionBarHeight =
                    callMethod(thisObject, "getActionBarHeightFromTheme") as? Int ?: 0
            }

        // C0540fd 14/15 —— HomeUI 状态栏颜色
        methodHomeUiUpdateStatusBar.hookAfter { setHomeStatusBarTransparent(thisObject) }
        methodHomeUiSetActionBarColor.hookAfter { setHomeStatusBarTransparent(thisObject) }

        // C0977lv 4 —— MoreTabUI.handleTabSwitchInForStatus
        methodMoreTabUiHandleTabSwitchInForStatus.hookBefore {
            result = null
        }

        // C0977lv 6 —— MoreTabUI.onTabCreate
        methodMoreTabUiOnTabCreate.hookAfter {
            val ui = thisObject ?: return@hookAfter
            for (field in ui.javaClass.fields) {
                if (field.type != View::class.java) continue
                val view = runCatching { field.get(ui) }.getOrNull() as? View ?: continue
                val lp = view.layoutParams
                if (lp is RelativeLayout.LayoutParams && lp.width == -1 && lp.height == -1) {
                    setNullBg(view)
                    (view.parent as? ViewGroup)?.setOnHierarchyChangeListener(
                        object : ViewGroup.OnHierarchyChangeListener {
                            override fun onChildViewAdded(parent: View?, child: View?) {
                                if (child != null) setNullBg(child)
                            }

                            override fun onChildViewRemoved(parent: View?, child: View?) {}
                        }
                    )
                }
            }
        }

        // C0977lv 5 —— AccountInfoPreference（O3(12)）
        "com.tencent.mm.pluginsdk.ui.preference.AccountInfoPreference".toClass().reflekt()
            .firstMethod { parameters(View::class) }.hookAfter {
                val viewGroup = args.getOrNull(0) as? ViewGroup ?: return@hookAfter
                viewGroup.post {
                    val black = themeColor("home.item.primary_text", 0)
                    val gray = themeColor("home.item.secondary_text", 0)
                    setHomeItemColors(viewGroup, black, gray, true)
                    if (black != 0) {
                        val noMeasured = firstChildViewGroupWithClass(
                            viewGroup,
                            "com.tencent.mm.ui.base.NoMeasuredTextView"
                        )
                        ((noMeasured?.parent as? ViewGroup)?.getChildAt(1) as? ImageView)
                            ?.setColorFilter(black)
                        (noMeasured as? TextView)?.setTextColor(black)
                    }
                }
            }

        // N8 13 —— MvvmAddressUIFragment 通讯录入口
        "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment".toClass().reflekt()
            .firstMethod { parameters(Bundle::class) }.hookAfter {
                val entrance = fieldValueOfType(
                    thisObject,
                    "com.tencent.mm.ui.contact.BizContactEntranceView".toClass()
                ) as? View ?: return@hookAfter
                val parent = entrance.parent as? LinearLayout ?: return@hookAfter
                for (i in 0 until parent.childCount) {
                    val child = parent.getChildAt(i) as? ViewGroup ?: continue
                    if (child is LinearLayout) {
                        // RunnableC0663i3(8)
                        child.postDelayed(150L) {
                            setNullBgRecursivelyWithTv(child)
                            setHomeItemColors(
                                child,
                                themeColor("home.item.primary_text", 0),
                                themeColor("home.item.secondary_text", 0),
                                false
                            )
                            val weworkTexts = ArrayList<View>()
                            child.findViewsWithText(
                                weworkTexts,
                                "企业微信联系人",
                                View.FIND_VIEWS_WITH_TEXT
                            )
                            if (weworkTexts.isEmpty()) return@postDelayed
                            themedDrawable(HOME_ITEM_ICONS["企业微信联系人"])?.let { d ->
                                val mask = firstChildViewGroupWithClass(
                                    child,
                                    "com.tencent.mm.ui.base.MaskLayout"
                                ) as? ViewGroup
                                (mask?.getChildAt(0) as? ImageView)?.setImageDrawable(d)
                            }
                        }
                    } else {
                        setNullBgRecursivelyWithTv(child)
                    }
                    val texts = ArrayList<View>()
                    for (text in listOf("新的朋友", "仅聊天的朋友", "群聊", "标签", "公众号", "服务号")) {
                        child.findViewsWithText(texts, text, View.FIND_VIEWS_WITH_TEXT)
                    }
                    if (!texts.isEmpty()) {
                        val textView = texts[0] as? TextView
                        themeColor("home.item.primary_text", 0).takeIf { it != 0 }?.let { c ->
                            textView?.setTextColor(c)
                        }
                        themedDrawable(HOME_ITEM_ICONS[textView?.text?.toString()])?.let { d ->
                            val mask = firstChildViewGroupWithClass(
                                child,
                                "com.tencent.mm.ui.base.MaskLayout"
                            )
                            (callMethod(mask, "getContentView") as? ImageView)?.setImageDrawable(d)
                        }
                    }
                }
            }

        // N8 14 —— addressItemConvert.onBindViewHolder
        classAddressItemConvert.clazz.reflekt()
            .firstMethod {
                parameters("androidx.recyclerview.widget.RecyclerView", View::class.java)
            }.hookAfter {
                val viewGroup = args.getOrNull(1) as? ViewGroup ?: return@hookAfter
                setNullBgRecursivelyWithTv(viewGroup)
                setNullBgRecursively(viewGroup)
                setHomeItemColors(
                    viewGroup,
                    themeColor("home.item.primary_text", 0),
                    themeColor("home.item.secondary_text", 0),
                    false
                )
            }

        // N8 15 —— ContactCountView 构造函数
        "com.tencent.mm.ui.contact.ContactCountView".toClass().reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                val viewGroup = thisObject as? ViewGroup ?: return@hookAfter
                setNullBgRecursivelyWithTv(viewGroup)
                setHomeItemColors(
                    viewGroup,
                    themeColor("home.item.primary_text", 0),
                    themeColor("home.item.secondary_text", 0),
                    false
                )
            }
        }

        // N8 16 —— FMessageContactView.initView
        methodFMessageContactViewInitView.hookAfter {
            val viewGroup = thisObject as? ViewGroup ?: return@hookAfter
            setNullBgRecursivelyWithTv(viewGroup)
            val texts = ArrayList<View>()
            viewGroup.findViewsWithText(texts, "新的朋友", View.FIND_VIEWS_WITH_TEXT)
            if (!texts.isEmpty()) {
                val textView = texts[0] as? TextView
                themeColor("home.item.primary_text", 0).takeIf { it != 0 }?.let { c ->
                    textView?.setTextColor(c)
                }
                themedDrawable(HOME_ITEM_ICONS[textView?.text?.toString()])?.let { d ->
                    val mask = firstChildViewGroupWithClass(
                        viewGroup,
                        "com.tencent.mm.ui.base.MaskLayout"
                    )
                    (callMethod(mask, "getContentView") as? ImageView)?.setImageDrawable(d)
                }
            }
        }

        // N8 17 —— EnterpriseBizViewItem 构造函数
        classEnterpriseBizViewItem.clazz.reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                val viewGroup = thisObject as? ViewGroup ?: return@hookAfter
                setNullBgRecursivelyWithTv(viewGroup)
                val maskParent = firstChildViewGroupWithClass(
                    viewGroup,
                    "com.tencent.mm.ui.base.MaskLayout"
                )?.parent as? ViewGroup
                (maskParent?.getChildAt(1) as? TextView)?.let { tv ->
                    themeColor("home.item.primary_text", 0).takeIf { it != 0 }
                        ?.let { tv.setTextColor(it) }
                }
            }
        }

        // N8 18 —— EnterpriseDefaultViewItem 构造函数
        classEnterpriseDefaultViewItem.clazz.reflekt().constructors().forEach { ctor ->
            ctor.hookAfter {
                val viewGroup = thisObject as? ViewGroup ?: return@hookAfter
                setNullBgRecursivelyWithTv(viewGroup)
                val maskParent = firstChildViewGroupWithClass(
                    viewGroup,
                    "com.tencent.mm.ui.base.MaskLayout"
                )?.parent as? ViewGroup
                val child = maskParent?.getChildAt(1) as? ViewGroup
                (child?.getChildAt(0) as? TextView)?.let { tv ->
                    themeColor("home.item.primary_text", 0).takeIf { it != 0 }
                        ?.let { tv.setTextColor(it) }
                }
                themedDrawable("home/items/wework_contact.png")?.let { d ->
                    val mask = firstChildViewGroupWithClass(
                        viewGroup,
                        "com.tencent.mm.ui.base.MaskLayout"
                    )
                    (callMethod(mask, "getContentView") as? ImageView)?.setImageDrawable(d)
                }
            }
        }

        // C0540fd 13 —— LauncherUIBottomTabView.setTo
        "com.tencent.mm.ui.LauncherUIBottomTabView".toClass().reflekt()
            .firstMethod { name = "setTo" }.hookAfter {
                val viewGroup = thisObject as? ViewGroup ?: return@hookAfter
                val selected = args.getOrNull(0) as? Int ?: return@hookAfter
                if (viewGroup.isEmpty()) return@hookAfter
                val tabBar = viewGroup.getChildAt(0) as? LinearLayout ?: return@hookAfter
                for (i in 0 until tabBar.childCount) {
                    val tab = tabBar.getChildAt(i) as? ViewGroup ?: continue
                    val textView = tab.findViewById<TextView>(idText)
                    val color = themeColor(
                        if (i == selected) "home.tabs.selected_text" else "home.tabs.unselected_text",
                        if (i == selected) -16777216 else -7829368
                    )
                    if (textView != null) setThemedTextColor(textView, color)
                    val icon = tab.tag as? ImageView ?: continue
                    val iconName = when (i) {
                        0 -> if (0 == selected) "home/tabs/conversation_selected.png"
                        else "home/tabs/conversation_unselected.png"
                        1 -> if (i == selected) "home/tabs/contact_selected.png"
                        else "home/tabs/contact_unselected.png"
                        2 -> if (i == selected) "home/tabs/discovery_unselected.png"
                        else "home/tabs/discovery_selected.png"
                        else -> if (i == selected) "home/tabs/me_selected.png"
                        else "home/tabs/me_unselected.png"
                    }
                    icon.setImageDrawable(themedDrawable(iconName))
                }
            }

        // C0540fd 12 —— HomeUI$FitSystemWindowLayoutView 构造函数
        $$"com.tencent.mm.ui.HomeUI$FitSystemWindowLayoutView".toClass().reflekt().constructors()
            .forEach { ctor ->
                ctor.hookAfter {
                    (thisObject as? View)?.background =
                        themeColor("home.taskbar.mask", 0x55000000).toDrawable()
                }
            }

        // C0540fd 16 —— LauncherUI.onResume：首页全局背景 + 底部 tab
        LauncherUI::class.reflekt()
            .firstMethod { name = "onResume" }.hookAfter {
                val activity = thisObject as Activity
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    ?: return@hookAfter
                if (content.getTag(VIEW_TAG_HOME_DONE) == any) return@hookAfter
                content.setTag(VIEW_TAG_HOME_DONE, any)
                val parent = content.parent as? ViewGroup ?: return@hookAfter
                val sibling = parent.getChildAt(1) as? ViewGroup
                val bottomTab = firstChildViewGroupWithClass(
                    content,
                    "com.tencent.mm.ui.LauncherUIBottomTabView"
                ) as? ViewGroup
                val viewPager = firstChildViewGroupWithClass(
                    content,
                    "com.tencent.mm.ui.base.CustomViewPager"
                ) as? ViewGroup
                var actionbarBg: ImageView? = null
                themedDrawable("home/actionbar/background.png")?.let { d ->
                    val iv = ImageView(activity).apply {
                        background = d
                        elevation = 1f
                    }
                    actionbarBg = iv
                    parent.addView(
                        iv,
                        0,
                        ViewGroup.LayoutParams(
                            -1,
                            actionBarHeightOf(activity) + statusBarHeight(activity)
                        )
                    )
                }
                themedDrawable("home/background.png")?.let { d ->
                    val bg = themedImageView(activity, d)
                    parent.addView(
                        bg,
                        0,
                        RelativeLayout.LayoutParams(-1, -1).apply {
                            addRule(RelativeLayout.CENTER_IN_PARENT)
                        }
                    )
                }
                if (sibling != null && bottomTab != null && viewPager != null &&
                    actionbarBg != null
                ) {
                    parent.post {
                        applyBottomTabTheme(sibling, actionbarBg, bottomTab, viewPager)
                    }
                }
            }
    }

    /** C0540fd 14/15：HomeUI 状态栏颜色 */
    private fun setHomeStatusBarTransparent(instance: Any?) {
        val activity =
            fieldValueOfType(
                instance,
                "com.tencent.mm.ui.MMFragmentActivity".toClass()
            ) as? Activity ?: instance as? Activity
        activity?.window?.statusBarColor = 0
    }

    /** P5.g —— MMActivityHook */
    private fun hookG() {
        // C0540fd 21 —— MMActivity.onCreate（指定 Activity 套用设置页背景）
        "com.tencent.mm.ui.MMActivity".toClass().reflekt()
            .firstMethod { parameters(Bundle::class) }.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                if (activity.javaClass.name !in SPECIFIC_ACTIVITIES) return@hookAfter
                val contentView = callMethod(activity, "getContentView") as? ViewGroup
                    ?: return@hookAfter
                val swipeChild = (callMethod(activity, "getSwipeBackLayout") as? ViewGroup)
                    ?.getChildAt(0) as? ViewGroup
                contentView.post { i8Case2(activity, swipeChild) }
            }

        // C0540fd 22 —— initSwipeBack：fixStatusbar 置 false
        "com.tencent.mm.ui.MMActivity".toClass().reflekt()
            .firstMethod { name = "initSwipeBack" }.hookBefore {
                val activity = thisObject as? Activity ?: return@hookBefore
                if (activity.javaClass.name !in SPECIFIC_ACTIVITIES) return@hookBefore
                activity.reflekt()
                    .firstField {
                        name = "fixStatusbar"
                        superclass()
                    }.set(false)
            }
    }

    /** P5.h —— MMSwitchBtnHook（C0540fd 23） */
    private fun hookH() {
        val switchClass = "com.tencent.mm.ui.widget.MMSwitchBtn".toClass()
        switchClass.constructors.forEach { ctor ->
            ctor.hookAfter {
                val btn = thisObject ?: return@hookAfter
                val color = themeColor("settings.switch_thumb", 0)
                if (color == 0) return@hookAfter
                for (field in switchClass.declaredFields) {
                    if (field.type != Int::class.javaPrimitiveType) continue
                    field.isAccessible = true
                    val v = runCatching { field.getInt(btn) }.getOrDefault(0)
                    if (v == -16268960) {
                        runCatching { field.setInt(btn, color) }
                    }
                }
            }
        }
    }

    /** P5.i —— PopupWindowHook（C0977lv 9/10） */
    private fun hookI() {
        val popupClass = classMmPopupWindow.clazz
        popupClass.reflekt().firstMethod { name = "setBackgroundDrawable" }.hookBefore {
            val stackContains = Thread.currentThread().stackTrace.any {
                it.toString().contains("onOptionsItemSelected")
            }
            if (stackContains) {
                themedDrawable("home/plus_menu_background.png")?.let { args[0] = it }
            }
        }
        popupClass.reflekt().firstMethod { name = "setContentView" }.hookBefore {
            val viewGroup = args.getOrNull(0) as? ViewGroup ?: return@hookBefore
            val contextName = viewGroup.context.javaClass.name
            if (contextName !in setOf(
                    "com.tencent.mm.ui.LauncherUI",
                    "com.tencent.mm.ui.chatting.ChattingUI",
                    "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"
                )
            ) {
                return@hookBefore
            }
            applyLongPressMenuTheme(viewGroup)
        }
    }

    /** P5.j —— SettingActivityHook（C0977lv 20/21/22/23） */
    private fun hookJ() {
        // C0977lv 20 —— VASBaseFragment.onViewCreated → H8(4)
        "com.tencent.mm.ui.vas.fragment.VASBaseFragment".toClass().reflekt()
            .firstMethod { name = "onViewCreated" }.hookAfter {
                val viewGroup = args.getOrNull(0) as? ViewGroup ?: return@hookAfter
                val activity = viewGroup.context as? Activity ?: return@hookAfter
                val name = activity.javaClass.name
                if (name == "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI" ||
                    name == "com.tencent.mm.feature.forward.ui.ForwardMsgPreviewUI"
                ) {
                    return@hookAfter
                }
                viewGroup.post { h8Case4(viewGroup) }
            }

        // C0977lv 21/22/23 —— 新版设置页（cherrywechat 以 versionCode >= 2980 作为开关）
        if (HostInfo.versionCode < 2980) return
        "com.tencent.mm.plugin.setting.ui.setting_new.base.BaseSettingPrefUI".toClass().reflekt()
            .firstMethod { name = "onCreate" }.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                val decor = activity.window?.decorView as? ViewGroup ?: return@hookAfter
                decor.post { g8Case3(decor, activity) }
            }
        if (!classBaseSettingConvert.isPlaceholder) {
            classBaseSettingConvert.clazz.reflekt()
                .firstMethod {
                    parameterCount = 6
                    parameters { it.size == 6 && it.last() == List::class.java }
                }.hookAfter {
                    (args.getOrNull(0) as? ViewGroup)?.let { setNullBgRecursively(it) }
                }
        }
    }

    /** P5.k —— SplashHook（C0977lv 27/28/29 + FD0/Xs） */
    @Suppress("DEPRECATION")
    private fun hookK() {
        // C0977lv 27 —— SplashActivity.onCreate：全屏主题启动图
        "com.tencent.mm.splash.SplashActivity".toClass().reflekt()
            .firstMethod { name = "onCreate" }.hookBefore {
                val activity = thisObject as? Activity ?: return@hookBefore
                val frame = activity.findViewById<FrameLayout>(android.R.id.content)
                    ?: return@hookBefore
                themedDrawable("splash/background.png")?.let { d ->
                    frame.addView(
                        themedImageView(frame.context, d).apply { elevation = 1f },
                        -1,
                        -1
                    )
                    // 原实现：a.i(activity).d() == com.gyf.immersionbar.a.i(activity).d()
                    // （ImmersionBar.with(activity).init()，沉浸式初始化）
                    setStatusBarTransparent(activity)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val controller = activity.window.insetsController
                        controller?.hide(WindowInsets.Type.statusBars())
                        controller?.hide(WindowInsets.Type.navigationBars())
                    } else {
                        activity.window.decorView.systemUiVisibility =
                            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    }
                }
            }

        // C0977lv 28/29 + FD0 —— SplashActivity 的公开非 final 方法置空返回，1 秒后恢复
        runCatching {
            "com.tencent.mm.splash.SplashActivity".toClass().reflekt().firstMethod {
                name { !it.startsWith("on") }
                modifiers { Modifiers.PUBLIC in it && Modifiers.FINAL !in it }
            }.hookBefore {
                result = null
                val decor = (thisObject as? Activity)?.window?.decorView
                val member = member as Method
                val thisObject = thisObject
                val args = args
                decor?.postDelayed(1000L) {
                    currentHookBridge.invokeOriginalMethod(member, thisObject, args)
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "hook splash method failed", it)
        }
    }

    /** P5.l —— TextColorBanHook（FD1/2） */
    private fun hookL() {
        TextView::class.reflekt().apply {
            firstMethod {
                name = "setTextColor"
                parameters(Int::class)
            }.hookBefore {
                val tv = thisObject as? TextView ?: return@hookBefore
                if (tv.getTag(TEXT_COLOR_TAG) == TextView::class.java) result = null
            }
            firstMethod {
                name = "setTextColor"
                parameters(ColorStateList::class)
            }.hookBefore {
                val tv = thisObject as? TextView ?: return@hookBefore
                if (tv.getTag(TEXT_COLOR_TAG) == TextView::class.java) result = null
            }
        }
    }

    /** C0465dy.a —— PreferenceHook（C0977lv 11） */
    private fun hookPreference() {
        "com.tencent.mm.ui.base.preference.Preference".toClass().reflekt()
            .firstMethod { parameters(View::class, ViewGroup::class) }.hookAfter {
                val view = result as? View ?: return@hookAfter
                applyHomePreferenceTheme(thisObject, view)
            }
    }

    private class ZeroColorPaint(flags: Int) : Paint(flags) {

        override fun setAlpha(a: Int) {
            super.setAlpha(0)
        }

        override fun setColor(color: Int) {
            super.setColor(0)
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

    // ------------------------------------------------------------------
    // 配置 UI：THEMES_PATH 主题列表 + Radio + 顶部「无」
    // ------------------------------------------------------------------

    override fun onClick(context: ComponentActivity) {
        val themes = listOf(getDefaultTheme(context)) + scanThemes()
        showComposeDialog(context) {
            val localizedContext = LocalWeKitLocalizedContext.current
            var selectedId by remember {
                mutableStateOf(
                    currentThemeId.takeIf { id ->
                        id == DEFAULT_THEME_ID || themes.any { it.id == id }
                    } ?: DEFAULT_THEME_ID
                )
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_theme_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item(key = "info") {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.beautify_theme_directory, THEMES_PATH),
                                    description = stringResource(R.string.beautify_theme_instructions),
                                )
                            }
                            themes.forEach { theme ->
                                item(key = theme.id) {
                                    RadioButtonWidget(
                                        iconPlaceholder = false,
                                        title = theme.name,
                                        description = buildString {
                                            append(localizedContext.getString(R.string.beautify_theme_author, theme.author))
                                            if (theme.version.isNotBlank()) {
                                                append(localizedContext.getString(R.string.beautify_theme_version, theme.version))
                                            }
                                            if (theme.description.isNotBlank()) {
                                                append("\n")
                                                append(theme.description)
                                            }
                                        },
                                        selected = selectedId == theme.id,
                                        onClick = {
                                            selectedId = theme.id
                                            if (theme.id != currentThemeId) {
                                                currentThemeId = theme.id
                                                showToast(localizedContext.getString(R.string.beautify_theme_saved))
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
