package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.SendIcon
import dev.ujhhgtg.wekit.ui.utils.ShareIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object RepostMoments : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    override val technicalId = "转发 & 一键转发"
    override val nameRes = R.string.feature_repost_moments_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_repost_moments_description

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
                localizedMomentsString(R.string.moments_repost_menu),
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
                localizedMomentsString(R.string.moments_quick_repost_menu),
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
            showToast(activity, activity.localizedMomentsString(R.string.moments_repost_parse_failed))
            return
        }
        val contentText = data.contentText

        when (data.type) {
            1, 54 -> { // 图片 / 实况
                if (data.hasLivePhoto) {
                    editLivePhotoRepost(context, data)
                    return
                }

                showToast(activity, activity.localizedMomentsString(R.string.moments_repost_preparing_images))
                CoroutineScope(Dispatchers.Main).launch {
                    val tempPaths = WeMomentsApi.ensureImagePathsForEditor(activity, data.mediaList, data.nativeMediaList)
                    if (tempPaths == null) {
                        showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_repost_image_download_failed))
                        return@launch
                    }
                    WeMomentsApi.postImagesInUi(activity, tempPaths, contentText)
                }
            }

            15, 5 -> { // 视频
                showToast(activity, activity.localizedMomentsString(R.string.moments_repost_preparing_video))
                CoroutineScope(Dispatchers.Main).launch {
                    val video = WeMomentsApi.ensureVideoPaths(activity, data)
                    if (video == null) {
                        showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_repost_video_download_failed))
                        return@launch
                    }

                    WeLogger.i(TAG, "forward video to editor: video=${video.videoPath}, thumb=${video.thumbPath}")
                    val albumVideoPath = WeMomentsApi.saveVideo(activity, video.videoPath)
                    if (albumVideoPath == null) {
                        showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_repost_video_save_failed))
                        return@launch
                    }
                    WeLogger.i(TAG, "dispatch video album result: video=$albumVideoPath")
                    if (!WeMomentsApi.openMomentVideoEditorFromAlbumResult(activity, contentText, albumVideoPath, context.source)) {
                        showToastSuspend(activity, activity.localizedMomentsString(R.string.moments_repost_video_select_failed))
                    }
                }
            }

            in WeMomentsApi.CARD_CONTENT_TYPES -> { // 链接 / 音乐 / 视频号短视频等卡片
                WeLogger.i(TAG, "reposting card type ${data.type}")
                if (!WeMomentsApi.openCardEditor(activity, data)) {
                    WeLogger.i(TAG, "card type ${data.type} not editor-capable")
                    showToast(activity, activity.localizedMomentsString(R.string.moments_repost_card_unsupported))
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
        showToast(activity, activity.localizedMomentsString(R.string.moments_repost_preparing_live_photo))
        CoroutineScope(Dispatchers.Main).launch {
            val result = WeMomentsApi.openMomentLivePhotoEditorFromAlbumResult(
                activity = activity,
                text = data.contentText,
                content = data,
                source = context.source
            )
            if (!result.success) {
                showToastSuspend(
                    activity,
                    activity.localizedRepostResult(result),
                )
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
            showToast(activity, activity.localizedMomentsString(R.string.moments_repost_parse_failed))
            return
        }

        showToast(activity, activity.localizedMomentsString(R.string.moments_quick_repost_preparing))

        CoroutineScope(Dispatchers.Main).launch {
            val result = WeMomentsApi.quickRepostEnsuringCached(data)
            showToastSuspend(
                activity,
                activity.localizedRepostResult(result),
            )
        }
    }

    private fun Context.localizedRepostResult(result: WeMomentsApi.ActionResult): String =
        result.messageRes?.let(::localizedMomentsString) ?: result.message
}
