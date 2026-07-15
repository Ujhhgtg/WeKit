package dev.ujhhgtg.wekit.features.items.moments

import androidx.compose.material3.Text
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.ShareIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(name = "转发", categories = ["朋友圈"], description = "转发他人的朋友圈到编辑界面, 支持实况图片\n图片/视频会在转发前自动缓存, 无需先点开")
object RepostMoments : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    private const val TAG = "RepostMoments"
    private const val PREPARING_IMAGE_TOAST = "正在准备图片..."
    private const val IMAGE_DOWNLOAD_FAILED_TOAST = "图片下载失败或超时!"

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
            showToast(activity, "无法解析这条朋友圈内容")
            return
        }
        val contentText = data.contentText

        when (data.type) {
            1, 54 -> { // 图片 / 实况相册
                if (data.hasLivePhoto) {
                    // Live photos use editable repost, with static-image fallback in the dialog.
                    promptLivePhotoRepost(context, data)
                    return
                }

                showToast(activity, PREPARING_IMAGE_TOAST)
                CoroutineScope(Dispatchers.Main).launch {
                    val tempPaths = WeMomentsApi.ensureImagePathsForEditor(activity, data.mediaList, data.nativeMediaList)
                    if (tempPaths == null) {
                        showToastSuspend(activity, IMAGE_DOWNLOAD_FAILED_TOAST)
                        return@launch
                    }
                    WeMomentsApi.sendImagesInUi(activity, tempPaths, contentText)
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
            else -> { // 文字
                WeLogger.i(TAG, "reposting type ${data.type}")
                WeMomentsApi.sendTextInUi(activity, contentText)
            }
        }
    }

    /**
     * Live photo repost choices: edit live photo, or fall back to static image editing.
     */
    private fun promptLivePhotoRepost(
        context: WeMomentsContextMenuApi.MomentsContext,
        data: WeMomentsApi.MomentContent
    ) {
        val activity = context.activity
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text("实况图片") },
                text = { Text("此朋友圈包含实况图片。\n可编辑实况转发，或静态图编辑。") },
                confirmButton = {
                    Button(onClick = {
                        onDismiss()
                        editLivePhotoRepost(context, data)
                    }) { Text("实况编辑转发") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        onDismiss()
                        showToast(activity, PREPARING_IMAGE_TOAST)
                        CoroutineScope(Dispatchers.Main).launch {
                            val tempPaths = WeMomentsApi.ensureImagePathsForEditor(activity, data.mediaList, data.nativeMediaList)
                            if (tempPaths == null) {
                                showToastSuspend(activity, IMAGE_DOWNLOAD_FAILED_TOAST)
                                return@launch
                            }
                            WeMomentsApi.sendImagesInUi(activity, tempPaths, data.contentText)
                        }
                    }) { Text("静态编辑转发") }
                }
            )
        }
    }

    private fun editLivePhotoRepost(
        context: WeMomentsContextMenuApi.MomentsContext,
        data: WeMomentsApi.MomentContent
    ) {
        val activity = context.activity
        showToast(activity, "正在准备实况图片...")
        CoroutineScope(Dispatchers.Main).launch {
            val result = WeMomentsApi.openMomentLivePhotoEditorFromAlbumResult(
                activity = activity,
                text = data.contentText,
                content = data,
                source = context.source
            )
            if (!result.success || result.message.isNotBlank()) {
                showToastSuspend(activity, result.message)
            }
        }
    }

}
