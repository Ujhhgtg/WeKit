package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.SendIcon
import dev.ujhhgtg.wekit.ui.utils.ShareIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.io.path.absolutePathString
import kotlin.io.path.div

@Feature(name = "转发 & 一键转发", categories = ["朋友圈"], description = "转发他人的朋友圈\n如果图片/视频转发后是空白, 请点击查看/播放后重试")
object ReMoment : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    private val TAG = This.Class.simpleName

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777013,
                "转发",
                ShareIcon,
                { _, _ -> true },
            ) { moment ->
                try {
                    repostMoment(moment)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "forward failed", e)
                }
            },
            WeMomentsContextMenuApi.MenuItem(
                777014,
                "一键转发",
                SendIcon,
                { _, _ -> true },
            ) { moment ->
                try {
                    quickRepostMoment(moment)
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "quick forward failed", e)
                }
            }
        )
    }

    private fun repostMoment(context: WeMomentsContextMenuApi.MomentsContext) {
        val activity = context.activity
        val data = WeMomentsApi.getMomentContent(context.snsInfo, context.timelineObject) ?: return
        val contentText = data.contentText

        when (data.type) {
            1, 54 -> { // 图片
                val tempPaths = WeMomentsApi.prepareImagePaths(data.mediaList, data.nativeMediaList, warnOnThumb = true)
                if (tempPaths == null) {
                    showToast(activity, "未找到本地缓存的图片!")
                    return
                }

                WeMomentsApi.sendImagesInUi(activity, tempPaths, contentText)
            }
            15, 5 -> { // 视频
                val videoPath = WeMomentsApi.fetchVideoPath(data.nativeMediaList)
                if (videoPath == null) {
                    showToast(activity, "未找到本地缓存的视频, 请播放一次后再转发!")
                    return
                }

                val tempVideo = activity.externalCacheDir!!.asPath / "wekit_repost_${System.currentTimeMillis()}.mp4"
                val tempPath = tempVideo.absolutePathString()

                if (WeMomentsApi.copyVfsFile(videoPath, tempPath)) {
                    WeMomentsApi.sendVideoInUi(activity, tempPath, contentText)
                } else {
                    showToast("视频文件准备失败!")
                }
            }
            else -> { // 文字
                WeLogger.i(TAG, "reposting type ${data.type}")
                WeMomentsApi.sendTextInUi(activity, contentText)
            }
        }
    }

    private fun quickRepostMoment(context: WeMomentsContextMenuApi.MomentsContext) {
        val activity = context.activity
        val data = WeMomentsApi.getMomentContent(context.snsInfo, context.timelineObject) ?: return

        showToast(activity, "正在一键转发...")

        CoroutineScope(Dispatchers.Main).launch {
            val result = WeMomentsApi.quickForward(data)
            showToastSuspend(activity, result.message)
        }
    }
}
