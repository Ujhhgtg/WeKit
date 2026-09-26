package dev.ujhhgtg.wekit.features.items.moments

import com.tencent.mm.protocal.protobuf.SnsObject
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.moments.AntiMomentsDelete.INTERCEPT_MARKER
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.ProtoWire

/**
 * Keeps comments that were deleted under a moment, marking text comments with [INTERCEPT_MARKER].
 *
 * The comments shown under a moment live in `SnsInfo.attrBuf` (`SnsObject.CommentUserList`), not
 * in the SnsComment table (which only feeds the notification list). They are removed by:
 * - `processCommentDelAction`, the sync push for a deleted comment, which also flags the
 *   notification row. It's skipped entirely, so the notification entry survives too.
 * - every refresh of the moment, which replaces attrBuf with the server's copy. WeChat's own
 *   `checkLocalDeletedComment` merge step runs first, so comments the server dropped are put back
 *   there.
 */
object AntiMomentCommentsDelete : SwitchFeature(), IResolveDex {

    override val technicalId = "朋友圈评论防撤回"
    override val nameRes = R.string.feature_anti_moment_comments_delete_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_anti_moment_comments_delete_description

    private const val TAG = "AntiMomentCommentsDelete"

    // SnsCommentInfo (CommentUserList element) field numbers
    private const val COMMENT_CONTENT = 5
    private const val COMMENT_CREATE_TIME = 6
    private const val COMMENT_ID = 7
    /** Set by WeChat on your own comment that the moment's author deleted; WeChat shows its own tip. */
    private const val COMMENT_DELETE_FLAG = 13

    // SnsActionInfo (sync action) field numbers
    private const val ACTION_FROM_USERNAME = 1
    private const val ACTION_COMMENT_ID = 10

    // NetSceneSnsSync.processCommentDelAction(action, actionInfo, snsId, parentId, clientId, handler)
    private val methodProcessCommentDelAction by dexMethod {
        matcher {
            usingEqStrings("processCommentDelAction", "com.tencent.mm.plugin.sns.model.NetSceneSnsSync")
        }
    }

    // SnsInfoStorageLogic.checkLocalDeletedComment(SnsInfo local, SnsObject incoming)
    private val methodCheckLocalDeletedComment by dexMethod {
        matcher {
            usingEqStrings("checkLocalDeletedComment", "com.tencent.mm.plugin.sns.model.SnsInfoStorageLogic")
        }
    }

    override fun onEnable() {
        methodProcessCommentDelAction.hookBefore {
            val action = args[1]!!.toProtoBytes()
            // You deleted it yourself, e.g. from another device
            if (ProtoWire.string(action, ACTION_FROM_USERNAME) == WeApi.selfWxId) return@hookBefore
            val snsId = args[2] as Long
            markComment(snsId, ProtoWire.varint(action, ACTION_COMMENT_ID)!!.toInt())
            WeLogger.i(TAG, "blocked sync delete of a comment on moment $snsId")
            result = true
        }

        methodCheckLocalDeletedComment.hookAfter {
            val local = args[0] ?: return@hookAfter
            val incoming = args[1] as? SnsObject? ?: return@hookAfter
            restoreMissingComments(local, incoming)
        }
    }

    private fun markComment(snsId: Long, commentId: Int) {
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        val reflekt = snsInfo.reflekt()
        val snsObject = parseSnsObject(reflekt.getField("field_attrBuf", true) as? ByteArray? ?: return)
        val comments = snsObject.CommentUserList
        val index = comments.indexOfFirst { it.commentId == commentId }
        if (index < 0) return
        comments[index] = markedCopy(comments[index]) ?: return

        reflekt.invokeMethod("setAttrBuf", snsObject.toByteArray())
        WeMomentsApi.updateSnsInfo(snsId, snsInfo)
    }

    private fun restoreMissingComments(local: Any, incoming: SnsObject) {
        val localBuf = local.reflekt().getField("field_attrBuf", true) as? ByteArray? ?: return
        val incomingIds = incoming.CommentUserList.mapTo(HashSet()) { it.commentId }
        val missing = parseSnsObject(localBuf).CommentUserList.filter {
            val id = it.commentId
            id != 0 && id !in incomingIds && ProtoWire.varint(it.toProtoBytes(), COMMENT_DELETE_FLAG) ?: 0L == 0L
        }
        if (missing.isEmpty()) return

        incoming.CommentUserList.addAll(missing.map { markedCopy(it) ?: it })
        incoming.CommentUserList.sortBy { ProtoWire.varint(it.toProtoBytes(), COMMENT_CREATE_TIME) ?: 0L }
        WeLogger.i(TAG, "restored ${missing.size} deleted comment(s)")
    }

    /**
     * Returns a copy of [comment] with [INTERCEPT_MARKER] prepended to its text, or null when it's
     * already marked or has no text (sticker comments, which may render differently with text added).
     */
    private fun markedCopy(comment: Any): Any? {
        val bytes = comment.toProtoBytes()
        val content = ProtoWire.string(bytes, COMMENT_CONTENT)
        if (content.isNullOrEmpty() || content.contains(INTERCEPT_MARKER)) return null
        return comment.javaClass.createInstance().apply {
            reflekt().invokeMethod("parseFrom", ProtoWire.withString(bytes, COMMENT_CONTENT, "$INTERCEPT_MARKER $content"), superclass = true)
        }
    }

    private fun parseSnsObject(bytes: ByteArray) =
        SnsObject().apply { reflekt().invokeMethod("parseFrom", bytes, superclass = true) }

    private fun Any.toProtoBytes() = reflekt().invokeMethod("toByteArray", superclass = true) as ByteArray

    private val Any.commentId: Int
        get() = ProtoWire.varint(toProtoBytes(), COMMENT_ID)?.toInt() ?: 0
}
