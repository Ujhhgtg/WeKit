package moe.ouom.wekit.hooks.items.shortvideos

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.ui.WeShortVideosShareMenuApi
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.utils.common.ModuleRes
import moe.ouom.wekit.utils.common.ToastUtils
import moe.ouom.wekit.utils.formatBytesSize
import java.util.Locale

@SuppressLint("StaticFieldLeak")
@HookItem(
    path = "视频号/查看媒体下载链接",
    desc = "向视频分享菜单中添加 '复制链接' 菜单项 (下载还没写, 目前先自己手动下载)"
)
object DisplayMediaDownloadLinks : SwitchHookItem(),
    WeShortVideosShareMenuApi.IMenuItemsProvider {

    override fun onLoad() {
        WeShortVideosShareMenuApi.addProvider(this)
    }

    override fun onUnload() {
        WeShortVideosShareMenuApi.removeProvider(this)
    }

    override fun getMenuItems(
        param: XC_MethodHook.MethodHookParam,
    ): List<WeShortVideosShareMenuApi.MenuItem> {
        return listOf(
            WeShortVideosShareMenuApi.MenuItem(
                777004,
                "复制链接",
                ModuleRes.getDrawable("link_24px")
            )
            { _, mediaType, mediaList ->
                if (mediaType == 2) {
                    val imageUrls = mediaList.map { json ->
                        json.getString("url") + json.getString("url_token")
                    }

                    val clipboard = HostInfo.application
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Url", imageUrls.joinToString("\n"))
                    clipboard.setPrimaryClip(clip)
                    ToastUtils.showToast("已复制")
                    return@MenuItem
                }

                if (mediaType == 4) {
                    val json = mediaList[0]

                    val clipItems = mutableListOf<Pair<String, String>>()

                    val duration = json.getInt("videoDuration")
                    val size = json.getInt("fileSize")
                    val displayDuration = "%02d:%02d:%02d".format(
                        Locale.CHINA,
                        duration / 3600, (duration % 3600) / 60, duration % 60
                    )
                    val displaySize = formatBytesSize(size.toLong())
                    clipItems += "时长" to displayDuration
                    clipItems += "大小" to displaySize

                    val cdnInfo = json.optJSONObject("media_cdn_info")
                    if (cdnInfo == null || !cdnInfo.has("pcdn_url")) {
                        val url = json.getString("url")
                        val urlToken = json.getString("url_token")
                        val decodeKey = json.getString("decodeKey")
                        clipItems += "密链" to (url + urlToken)
                        clipItems += "密钥" to decodeKey
                    } else {
                        clipItems += "链接" to json.getString("pcdn_url")
                    }

                    val cm = HostInfo.application
                        .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(
                        "Content",
                        clipItems.joinToString("\n") { pair -> "${pair.first}: ${pair.second}" })
                    cm.setPrimaryClip(clip)
                    ToastUtils.showToast("已复制")

                    return@MenuItem
                }

                ToastUtils.showToast("未知的媒体类型, 无法复制链接")
            }
        )
    }
}