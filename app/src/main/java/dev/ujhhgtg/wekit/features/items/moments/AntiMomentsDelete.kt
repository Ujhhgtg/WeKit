package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.serialization.ProtoWire

/**
 * Keeps friends' moments that their authors deleted, marking them with [INTERCEPT_MARKER].
 *
 * WeChat drops a deleted moment through several paths, all covered here:
 * - `insertSnsList`: after each timeline / profile page, clears that list's sourceType bit from
 *   every local row the server page omitted (via `SnsInfoStorage.update`).
 * - `updateFilterTimeLine` / `updateFilterUserName`: raw SQL clearing the same bits. The `*Above`
 *   variants hit rows newer than the page's first item (e.g. the newest moment was deleted) and run
 *   *before* `insertSnsList`, so those rows never reach the update path.
 * - `processSnsDelAction`: the sync push for a deleted moment, which queues the moment's local
 *   media and SnsImproveStorage row for deletion on the next timeline refresh.
 * - SnsCommentDetailUI: when the detail request returns no object, deletes the SnsInfo row, its
 *   media and its SnsImproveStorage row.
 *
 * The marker lives in the moment's text (TimeLineObject.contentDesc), so the raw-SQL filters can
 * skip kept rows with `instr` on the content BLOB. sourceType can't carry a flag: SnsInfoStorage
 * queries profile pages with `sourceType IN (...)` lists of the known bit combinations.
 *
 * The server also omits moments the author made private, restricted by a visibility window (e.g.
 * "仅三天可见"), or hid from you; those are kept and marked as well.
 */
object AntiMomentsDelete : SwitchFeature(), IResolveDex {
    override val technicalId = "朋友圈防撤回"
    override val nameRes = R.string.feature_anti_moments_delete_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_anti_moments_delete_description

    private const val TAG = "AntiMomentsDelete"
    const val INTERCEPT_MARKER = "[拦截删除]"

    /**
     * Timeline (2), own profile page (4) and others' profile pages (8): lists that return every
     * visible moment.
     */
    private const val PROTECTED_SOURCE_MASK = 2 or 4 or 8

    /** TimeLineObject.contentDesc — the moment's text. */
    private const val TIMELINE_CONTENT_DESC = 5

    private val MARKED_CONTENT_EXCLUSION =
        " AND instr(content, X'" +
            INTERCEPT_MARKER.toByteArray().joinToString("") { "%02X".format(it.toInt() and 0xFF) } +
            "') = 0"

    private const val SNS_INFO_STORAGE = "com.tencent.mm.plugin.sns.storage.SnsInfoStorage"

    // SnsInfoStorageLogic.insertSnsList(userName, srcType, snsObjects, maxSeq)
    private val methodInsertSnsList by dexMethod {
        usingEqStrings(
            "insertSnsList",
            "com.tencent.mm.plugin.sns.model.SnsInfoStorageLogic",
        )
    }

    // SnsInfoStorage.updateFilterTimeLine(whereSuffix): "UPDATE SnsInfo SET sourceType = sourceType
    // & -3" + whereSuffix
    private val methodUpdateFilterTimeLine by dexMethod {
        usingEqStrings(
            "updateFilterTimeLine",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
        )
    }

    // SnsInfoStorage.updateFilterUserName(userName, isSelf, whereSuffix): clears bit 4 / 8
    private val methodUpdateFilterUserName by dexMethod {
        usingEqStrings(
            "updateFilterUserName",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
        )
    }

    // SnsInfoStorage.updateFilterTimeLineAbove(firstSeq) → updateFilterTimeLine
    private val methodUpdateFilterTimeLineAbove by dexMethod {
        usingEqStrings(
            "updateFilterTimeLineAbove",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
        )
    }

    // SnsInfoStorage.updateFilterUserNameAbove(userName, isSelf, firstSeq) → updateFilterUserName
    private val methodUpdateFilterUserNameAbove by dexMethod {
        usingEqStrings(
            "updateFilterUserNameAbove",
            "com.tencent.mm.plugin.sns.storage.SnsInfoStorage",
        )
    }

    // SnsInfoStorage.delete(long snsId)
    private val methodDeleteSnsInfo by dexMethod {
        paramTypes("long")
        usingEqStrings("delete", "com.tencent.mm.plugin.sns.storage.SnsInfoStorage")
    }

    // NetSceneSnsSync.processSnsDelAction(action, actionInfo, snsId, parentId, clientId, handler)
    private val methodProcessSnsDelAction by dexMethod {
        usingEqStrings(
            "processSnsDelAction",
            "com.tencent.mm.plugin.sns.model.NetSceneSnsSync",
        )
    }

    // SnsResourceManager.cleanLocalResource(SnsInfo): deletes the moment's cached media
    private val methodCleanLocalResource by dexMethod {
        usingEqStrings(
            "cleanLocalResource",
            "com.tencent.mm.plugin.sns.storage.SnsResourceManager",
        )
    }

    // SnsImproveStorage.deleteByLocalId(localId, snsId, createTime): removes the timeline UI row
    private val methodImproveDeleteByLocalId by dexMethod {
        usingEqStrings(
            "deleteByLocalId",
            "com.tencent.mm.plugin.sns.ui.improve.repository.SnsImproveStorage",
        )
    }

    private val inInsertSnsList = ThreadLocal.withInitial { false }
    private val inFilterAbove = ThreadLocal.withInitial { false }

    override fun onEnable() {
        methodInsertSnsList.hookBefore { inInsertSnsList.set(true) }
        methodInsertSnsList.hookAfter { inInsertSnsList.set(false) }

        // Other removeSourceFlag callers (posting, privacy changes, hiding) only act on your own
        // moments or on your explicit request, so only insertSnsList's omissions count as
        // deletions.
        WeMomentsApi.methodUpdateSnsInfo.hookBefore {
            if (!inInsertSnsList.get()!!) return@hookBefore
            val snsInfo = args[1]!!
            val newSource = snsInfo.sourceType
            val stored = WeMomentsApi.getSnsInfoBySnsId(args[0] as Long) ?: return@hookBefore
            val lost = stored.sourceType and newSource.inv() and PROTECTED_SOURCE_MASK
            if (lost == 0 || snsInfo.isOwn) return@hookBefore

            snsInfo.sourceType = newSource or lost
            markIntercepted(snsInfo)
        }

        listOf(methodUpdateFilterTimeLineAbove, methodUpdateFilterUserNameAbove).forEach {
            it.hookBefore { inFilterAbove.set(true) }
            it.hookAfter { inFilterAbove.set(false) }
        }
        methodUpdateFilterTimeLine.hookBefore { guardFilter(suffixIndex = 0) }
        methodUpdateFilterUserName.hookBefore { guardFilter(suffixIndex = 2) }

        methodProcessSnsDelAction.hookBefore {
            val snsId = args[2] as Long
            val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return@hookBefore
            if (snsInfo.isOwn) return@hookBefore
            if (markIntercepted(snsInfo)) WeMomentsApi.updateSnsInfo(snsId, snsInfo)
            WeLogger.i(TAG, "blocked sync delete of moment $snsId")
            result = true
        }

        methodDeleteSnsInfo.hookBefore {
            val snsId = args[0] as Long
            val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return@hookBefore
            if (snsInfo.isOwn) return@hookBefore
            if (markIntercepted(snsInfo)) WeMomentsApi.updateSnsInfo(snsId, snsInfo)
            WeLogger.i(TAG, "blocked local delete of moment $snsId")
            result = false
        }

        methodCleanLocalResource.hookBefore {
            if (!args[0]!!.isOwn) result = null
        }

        methodImproveDeleteByLocalId.hookBefore {
            val snsInfo = WeMomentsApi.getSnsInfoBySnsId(args[1] as Long) ?: return@hookBefore
            if (!snsInfo.isOwn) result = false
        }
    }

    /**
     * Keeps already-marked rows out of a raw-SQL source filter. Rows newer than the page's first
     * item (the `*Above` filters) are missing from the server, so friends' rows there are marked
     * first and thereby excluded too.
     */
    private fun HookParam.guardFilter(suffixIndex: Int) {
        val suffix = args[suffixIndex] as String
        if (inFilterAbove.get()!!) {
            val snsIds =
                WeMomentsApi.rawQuerySnsInfo("SELECT snsId FROM SnsInfo$suffix").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
                }
            for (snsId in snsIds) {
                val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: continue
                if (!snsInfo.isOwn && markIntercepted(snsInfo)) {
                    WeMomentsApi.updateSnsInfo(snsId, snsInfo)
                }
            }
        }
        args[suffixIndex] = suffix + MARKED_CONTENT_EXCLUSION
    }

    /** Adds [INTERCEPT_MARKER] to the moment's text; returns false if it was already marked. */
    private fun markIntercepted(snsInfo: Any): Boolean {
        val reflekt = snsInfo.reflekt()
        val content = reflekt.getField("field_content", true) as ByteArray
        val text = ProtoWire.string(content, TIMELINE_CONTENT_DESC)
        if (text != null && text.contains(INTERCEPT_MARKER)) return false

        val markedText = if (text.isNullOrEmpty()) INTERCEPT_MARKER else "$INTERCEPT_MARKER $text"
        reflekt.setField(
            "field_content",
            ProtoWire.withString(content, TIMELINE_CONTENT_DESC, markedText),
            superclass = true,
        )
        // setAttrBuf recomputes contentByteMd5, the key of SnsInfo's parsed-TimeLineObject cache
        reflekt.invokeMethod("setAttrBuf", reflekt.getField("field_attrBuf", true))
        WeLogger.i(TAG, "marked moment ${reflekt.getField("field_snsId", true)}")
        return true
    }

    private var Any.sourceType: Int
        get() = reflekt().getField("field_sourceType", true) as Int
        set(value) = reflekt().setField("field_sourceType", value, superclass = true)

    private val Any.isOwn: Boolean
        get() = reflekt().getField("field_userName", true) == WeApi.selfWxId
}
