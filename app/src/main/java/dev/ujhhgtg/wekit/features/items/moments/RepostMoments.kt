package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.SendIcon
import dev.ujhhgtg.wekit.ui.utils.ShareIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(
    name = "转发 & 一键转发",
    categories = ["朋友圈"],
    description = "转发他人的朋友圈, 支持实况图片\n图片/视频会在转发前自动缓存, 无需先点开; 实况视频如转发后空白请先播放一次"
)
object RepostMoments : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    private const val TAG = "RepostMoments"

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
        val data = WeMomentsApi.getMomentContent(context.snsInfo, context.timelineObject)
        if (data == null) {
            WeLogger.w(
                TAG,
                "failed to resolve Moments content: activity=${activity.javaClass.name}, " +
                        "snsInfo=${context.snsInfo?.javaClass?.name}, timeline=${context.timelineObject?.javaClass?.name}"
            )
            showToast(activity, "朋友圈内容解析失败!")
            return
        }
        val contentText = data.contentText

        when (data.type) {
            1, 54 -> { // 图片 / 实况
                if (data.hasLivePhoto) {
                    editLivePhotoRepost(context, data)
                    return
                }

                showToast(activity, "正在准备图片...")
                CoroutineScope(Dispatchers.Main).launch {
                    val tempPaths = WeMomentsApi.ensureImagePathsForEditor(activity, data.mediaList, data.nativeMediaList)
                    if (tempPaths == null) {
                        showToastSuspend(activity, "图片下载失败或超时!")
                        return@launch
                    }
                    WeMomentsApi.postImagesInUi(activity, tempPaths, contentText)
                }
            }

            15, 5 -> { // 视频
                showToast(activity, "正在准备视频...")
                CoroutineScope(Dispatchers.Main).launch {
                    val video = WeMomentsApi.ensureVideoPaths(activity, data)
                    if (video == null) {
                        showToastSuspend(activity, "视频下载失败或超时!")
                        return@launch
                    }

                    WeLogger.i(TAG, "forward video to editor: video=${video.videoPath}, thumb=${video.thumbPath}")
                    val albumVideoPath = WeMomentsApi.saveVideoToAlbumPath(activity, video.videoPath)
                    if (albumVideoPath == null) {
                        showToastSuspend(activity, "视频保存到相册失败!")
                        return@launch
                    }
                    WeLogger.i(TAG, "dispatch video album result: video=$albumVideoPath")
                    if (!WeMomentsApi.openMomentVideoEditorFromAlbumResult(activity, contentText, albumVideoPath, context.source)) {
                        showToastSuspend(activity, "视频自动选择失败!")
                    }
                }
            }

            in WeMomentsApi.CARD_CONTENT_TYPES -> { // 链接 / 音乐 / 视频号短视频等卡片
                WeLogger.i(TAG, "reposting card type ${data.type}")
                if (!WeMomentsApi.openCardEditor(activity, data)) {
                    WeLogger.i(TAG, "card type ${data.type} not editor-capable")
                    showToast(activity, "该类型卡片不支持编辑转发!")
                }
            }

            else -> { // 文字
                WeLogger.i(TAG, "reposting type ${data.type}")
                WeMomentsApi.postTextInUi(activity, contentText)
            }
        }
    }

    private fun editLivePhotoRepost(
        context: WeMomentsContextMenuApi.MomentsContext,
        data: WeMomentsApi.MomentContent
    ) {
        val activity = context.activity
        showToast(activity, "正在准备实况...")
        CoroutineScope(Dispatchers.Main).launch {
            val result = WeMomentsApi.openMomentLivePhotoEditorFromAlbumResult(
                activity = activity,
                text = data.contentText,
                content = data,
                source = context.source
            )
            if (!result.success && result.message.isNotBlank()) {
                showToastSuspend(activity, result.message)
            }
        }
    }

    private fun quickRepostMoment(context: WeMomentsContextMenuApi.MomentsContext) {
        val activity = context.activity
        val data = WeMomentsApi.getMomentContent(context.snsInfo, context.timelineObject)
        if (data == null) {
            WeLogger.w(
                TAG,
                "failed to resolve Moments content for quick repost: activity=${activity.javaClass.name}, " +
                        "snsInfo=${context.snsInfo?.javaClass?.name}, timeline=${context.timelineObject?.javaClass?.name}"
            )
            showToast(activity, "朋友圈内容解析失败!")
            return
        }

        showToast(activity, "正在一键转发...")

        CoroutineScope(Dispatchers.Main).launch {
            val result = WeMomentsApi.quickRepostEnsuringCached(data)
            showToastSuspend(activity, result.message)
        }
    }
}
