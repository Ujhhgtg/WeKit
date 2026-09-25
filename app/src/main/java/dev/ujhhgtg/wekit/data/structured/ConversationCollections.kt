package dev.ujhhgtg.wekit.data.structured

import kotlinx.serialization.Serializable

@Serializable
enum class ConversationGroupType { MANUAL, PRESET_UNREAD, PRESET_GROUPS, PRESET_FRIENDS, PRESET_OFFICIALS, SQL }

@Serializable
enum class BuiltInGroupLabel { UNREAD, GROUPS, FRIENDS, OFFICIALS }

@Serializable
data class ConversationGroup(
    val id: String = "",
    val name: String = "",
    val members: List<String> = emptyList(),
    val type: ConversationGroupType = ConversationGroupType.MANUAL,
    val selectFields: String = "",
    val whereClause: String = "",
    val builtInLabel: BuiltInGroupLabel? = null,
)

@Serializable
enum class ConversationFolderType { MANUAL, PRESET_GROUPS, PRESET_OFFICIALS, SQL }

@Serializable
data class ConversationFolder(
    val id: String = "",
    val name: String = "",
    val members: List<String> = emptyList(),
    val type: ConversationFolderType = ConversationFolderType.MANUAL,
    val selectFields: String = "",
    val whereClause: String = "",
    val pinFlag: Long = 0L,
)

/** The fixed all-conversations tab, stored as an ordinary group row so its position persists. */
const val ALL_CONVERSATIONS_GROUP_ID = "wekit_group_all"
