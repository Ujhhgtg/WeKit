package dev.ujhhgtg.wekit.data.structured

import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
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

data class CollectionMigrationResult<T>(val items: List<T>, val filteredParents: Int, val filteredMembers: Int)

class DuplicateCollectionIdsException(val duplicateCount: Int) :
    IllegalArgumentException("Duplicate collection parent IDs: $duplicateCount")

/** Old JSON decoding is confined to migration; runtime writes use typed rows. */
object LegacyConversationCollections {
    const val GROUP_PREFIX = "wekit_group_"
    const val ALL_TAB_ID = "${GROUP_PREFIX}all"
    const val FOLDER_PREFIX = "wekit_folder_"

    fun groups(raw: String?, seed: Long = System.currentTimeMillis()): CollectionMigrationResult<ConversationGroup> {
        if (raw == null) {
            val presets = listOf(
                ConversationGroupType.PRESET_UNREAD to BuiltInGroupLabel.UNREAD,
                ConversationGroupType.PRESET_GROUPS to BuiltInGroupLabel.GROUPS,
                ConversationGroupType.PRESET_FRIENDS to BuiltInGroupLabel.FRIENDS,
                ConversationGroupType.PRESET_OFFICIALS to BuiltInGroupLabel.OFFICIALS,
            )
            return CollectionMigrationResult(
                listOf(ConversationGroup(id = ALL_TAB_ID)) + presets.mapIndexed { index, (type, label) ->
                    ConversationGroup(id = "$GROUP_PREFIX${seed + index}", type = type, builtInLabel = label)
                }, 0, 0,
            )
        }
        val decoded = DefaultJson.decodeFromString<List<ConversationGroup>>(raw)
        var filteredMembers = 0
        val items = decoded.map { original ->
            val members = original.members.filter { it.isNotBlank() }
            filteredMembers += original.members.size - members.size
            val group = original.copy(members = members)
            if (group.id == ALL_TAB_ID || group.builtInLabel != null) group else {
                val label = when {
                    group.type == ConversationGroupType.PRESET_UNREAD && group.name == "未读" -> BuiltInGroupLabel.UNREAD
                    group.type == ConversationGroupType.PRESET_GROUPS && group.name == "群聊" -> BuiltInGroupLabel.GROUPS
                    group.type == ConversationGroupType.PRESET_FRIENDS && group.name == "好友" -> BuiltInGroupLabel.FRIENDS
                    group.type == ConversationGroupType.PRESET_OFFICIALS && group.name == "公众号" -> BuiltInGroupLabel.OFFICIALS
                    else -> null
                }
                if (label == null) group else group.copy(name = "", builtInLabel = label)
            }
        }.filter {
            it.id.startsWith(GROUP_PREFIX) &&
                (it.id == ALL_TAB_ID || it.name.isNotBlank() || it.builtInLabel != null ||
                    it.type in setOf(ConversationGroupType.PRESET_UNREAD, ConversationGroupType.PRESET_GROUPS,
                        ConversationGroupType.PRESET_FRIENDS, ConversationGroupType.PRESET_OFFICIALS))
        }
        rejectDuplicateIds(items.map { it.id })
        val withAll = if (items.any { it.id == ALL_TAB_ID }) items else listOf(ConversationGroup(id = ALL_TAB_ID)) + items
        return CollectionMigrationResult(withAll, decoded.size - items.size, filteredMembers)
    }

    fun folders(raw: String?): CollectionMigrationResult<ConversationFolder> {
        if (raw == null) return CollectionMigrationResult(emptyList(), 0, 0)
        val decoded = DefaultJson.decodeFromString<List<ConversationFolder>>(raw)
        var filteredMembers = 0
        val items = decoded.map { folder ->
            val members = folder.members.filter { it.isNotBlank() }
            filteredMembers += folder.members.size - members.size
            folder.copy(members = members)
        }.filter { it.id.startsWith(FOLDER_PREFIX) && it.name.isNotBlank() }
        rejectDuplicateIds(items.map { it.id })
        return CollectionMigrationResult(items, decoded.size - items.size, filteredMembers)
    }

    private fun rejectDuplicateIds(ids: List<String>) {
        val duplicateCount = ids.groupingBy { it }.eachCount().count { it.value > 1 }
        if (duplicateCount != 0) throw DuplicateCollectionIdsException(duplicateCount)
    }
}
