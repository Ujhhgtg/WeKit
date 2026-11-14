package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger

object WeGroupApi : ApiFeature() {
    override val technicalId = "群聊管理服务"
    override val nameRes = R.string.feature_we_group_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_group_api_description

    private const val TAG = "WeGroupApi"

    // ul.m(String chatRoomName, List<String> members, String sceneNote, Object historyInfo)
    private val ctorAddMember by dexConstructor {
        paramCount = 4
        paramTypes("java.lang.String", "java.util.List", "java.lang.String", "java.lang.Object")
        usingEqStrings("MicroMsg.NetSceneAddChatRoomMember")
    }

    // ul.p(String chatRoomName, List<String> members, int scene)
    private val ctorDelMember by dexConstructor {
        paramCount = 3
        paramTypes("java.lang.String", "java.util.List", "int")
        usingEqStrings("/cgi-bin/micromsg-bin/delchatroommember")
    }

    // ul.x(String chatRoomName, List<String> members, int scene, Object historyInfo)
    private val ctorInviteMember by dexConstructor {
        paramCount = 4
        paramTypes("java.lang.String", "java.util.List", "int", "java.lang.Object")
        usingEqStrings("MicroMsg.NetSceneInviteChatRoomMember")
    }

    fun addMember(groupId: String, memberWxId: String) {
        addMembers(groupId, listOf(memberWxId))
    }

    fun addMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorAddMember.newInstance(groupId, memberWxIds, null, null)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "addMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "addMembers failed for $groupId", e)
        }
    }

    fun delMember(groupId: String, memberWxId: String) {
        delMembers(groupId, listOf(memberWxId))
    }

    fun delMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorDelMember.newInstance(groupId, memberWxIds, 0)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "delMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "delMembers failed for $groupId", e)
        }
    }

    fun inviteMember(groupId: String, memberWxId: String) {
        inviteMembers(groupId, listOf(memberWxId))
    }

    fun inviteMembers(groupId: String, memberWxIds: List<String>) {
        try {
            val netScene = ctorInviteMember.newInstance(groupId, memberWxIds, 0, null)
            WeNetSceneApi.sendNetScene(netScene)
            WeLogger.i(TAG, "inviteMembers sent: group=$groupId count=${memberWxIds.size}")
        } catch (e: Exception) {
            WeLogger.e(TAG, "inviteMembers failed for $groupId", e)
        }
    }
}
