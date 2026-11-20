package dev.ujhhgtg.wekit.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import dev.ujhhgtg.wekit.data.entity.ConversationFolderEntity
import dev.ujhhgtg.wekit.data.entity.ConversationFolderMemberEntity
import dev.ujhhgtg.wekit.data.entity.ConversationGroupEntity
import dev.ujhhgtg.wekit.data.entity.ConversationGroupMemberEntity
import dev.ujhhgtg.wekit.data.structured.BuiltInGroupLabel
import dev.ujhhgtg.wekit.data.structured.ConversationFolder
import dev.ujhhgtg.wekit.data.structured.ConversationFolderType
import dev.ujhhgtg.wekit.data.structured.ConversationGroup
import dev.ujhhgtg.wekit.data.structured.ConversationGroupType
import dev.ujhhgtg.wekit.data.structured.LegacyConversationCollections

data class ConversationGroupRows(
    @Embedded val group: ConversationGroupEntity,
    @Relation(parentColumn = "id", entityColumn = "groupId") val members: List<ConversationGroupMemberEntity>,
) {
    fun toGroup() = ConversationGroup(
        group.id, group.name, members.sortedBy { it.position }.map { it.wxId },
        ConversationGroupType.valueOf(group.type), group.selectFields, group.whereClause,
        group.builtInLabel?.let(BuiltInGroupLabel::valueOf),
    )
}

data class ConversationFolderRows(
    @Embedded val folder: ConversationFolderEntity,
    @Relation(parentColumn = "id", entityColumn = "folderId") val members: List<ConversationFolderMemberEntity>,
) {
    fun toFolder() = ConversationFolder(
        folder.id, folder.name, members.sortedBy { it.position }.map { it.wxId },
        ConversationFolderType.valueOf(folder.type), folder.selectFields, folder.whereClause, folder.pinFlag,
    )
}

@Dao
interface ConversationCollectionDao {
    @Transaction
    @Query("SELECT * FROM conversation_groups ORDER BY position, id")
    suspend fun groupRows(): List<ConversationGroupRows>

    @Transaction
    @Query("SELECT * FROM conversation_folders ORDER BY position, id")
    suspend fun folderRows(): List<ConversationFolderRows>

    suspend fun getGroups(): List<ConversationGroup> = groupRows().map { it.toGroup() }
    suspend fun getFolders(): List<ConversationFolder> = folderRows().map { it.toFolder() }

    @Query("SELECT position FROM conversation_groups WHERE id = :id")
    suspend fun groupPosition(id: String): Int?

    @Query("SELECT position FROM conversation_folders WHERE id = :id")
    suspend fun folderPosition(id: String): Int?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM conversation_groups")
    suspend fun nextGroupPosition(): Int

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM conversation_folders")
    suspend fun nextFolderPosition(): Int

    @Upsert suspend fun putGroupRow(group: ConversationGroupEntity)
    @Insert suspend fun insertFolderRow(folder: ConversationFolderEntity)
    @Insert suspend fun insertGroupMembers(members: List<ConversationGroupMemberEntity>)
    @Insert suspend fun insertFolderMembers(members: List<ConversationFolderMemberEntity>)

    @Query("DELETE FROM conversation_group_members WHERE groupId = :id")
    suspend fun deleteGroupMembers(id: String)

    @Query("DELETE FROM conversation_folder_members WHERE folderId = :id")
    suspend fun deleteFolderMembers(id: String)

    @Query("DELETE FROM conversation_groups WHERE id = :id")
    suspend fun deleteGroupRow(id: String)

    @Query("DELETE FROM conversation_folders WHERE id = :id")
    suspend fun removeFolder(id: String)

    @Query("UPDATE conversation_groups SET position = :position WHERE id = :id")
    suspend fun positionGroup(id: String, position: Int)

    @Query("SELECT id FROM conversation_groups ORDER BY position, id")
    suspend fun groupIds(): List<String>

    @Query("UPDATE conversation_folders SET name = :name, type = :type, selectFields = :selectFields, whereClause = :whereClause WHERE id = :id")
    suspend fun updateFolderDetails(id: String, name: String, type: String, selectFields: String, whereClause: String)

    @Query("UPDATE conversation_folders SET pinFlag = :pinFlag WHERE id = :id")
    suspend fun putFolderPinFlag(id: String, pinFlag: Long)

    @Query("SELECT type FROM conversation_folders WHERE id = :id")
    suspend fun folderType(id: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM conversation_folder_members WHERE folderId = :id AND wxId = :wxId)")
    suspend fun hasFolderMember(id: String, wxId: String): Boolean

    @Query("INSERT INTO conversation_folder_members (folderId, position, wxId) SELECT :id, COALESCE(MAX(position), -1) + 1, :wxId FROM conversation_folder_members WHERE folderId = :id")
    suspend fun appendFolderMemberRow(id: String, wxId: String)

    @Query("DELETE FROM conversation_folder_members WHERE folderId = :id AND wxId = :wxId")
    suspend fun removeFolderMemberRow(id: String, wxId: String)

    @Transaction
    suspend fun putGroup(group: ConversationGroup) {
        val position = groupPosition(group.id) ?: nextGroupPosition()
        putGroupRow(ConversationGroupEntity(group.id, position, group.name, group.type.name,
            group.selectFields, group.whereClause, group.builtInLabel?.name))
        deleteGroupMembers(group.id)
        insertGroupMembers(group.members.mapIndexed { index, wxId -> ConversationGroupMemberEntity(group.id, index, wxId) })
    }

    @Transaction
    suspend fun removeGroup(id: String) {
        require(id != LegacyConversationCollections.ALL_TAB_ID) { "The all-conversations group cannot be deleted" }
        deleteGroupRow(id)
    }

    @Transaction
    suspend fun reorderGroups(ids: List<String>) {
        require(ids.distinct().size == ids.size) { "Duplicate group IDs in order" }
        val current = groupIds()
        val ordered = ids.filter { it in current } + current.filterNot { it in ids }
        ordered.forEachIndexed { position, id -> positionGroup(id, position) }
    }

    @Transaction
    suspend fun putFolder(folder: ConversationFolder) {
        if (folderPosition(folder.id) == null) {
            insertFolderRow(ConversationFolderEntity(folder.id, nextFolderPosition(), folder.name,
                folder.type.name, folder.selectFields, folder.whereClause, folder.pinFlag))
        } else {
            // The editor owns these fields; a concurrent host pin notification owns pinFlag.
            updateFolderDetails(folder.id, folder.name, folder.type.name, folder.selectFields, folder.whereClause)
        }
        deleteFolderMembers(folder.id)
        insertFolderMembers(folder.members.mapIndexed { index, wxId -> ConversationFolderMemberEntity(folder.id, index, wxId) })
    }

    @Transaction
    suspend fun appendFolderMember(id: String, wxId: String) {
        require(folderType(id) == ConversationFolderType.MANUAL.name) { "Only manual folders have editable members" }
        if (!hasFolderMember(id, wxId)) appendFolderMemberRow(id, wxId)
    }

    @Transaction
    suspend fun removeFolderMember(id: String, wxId: String) {
        require(folderType(id) == ConversationFolderType.MANUAL.name) { "Only manual folders have editable members" }
        removeFolderMemberRow(id, wxId)
    }

    @Transaction
    suspend fun putFolderPinFlags(flags: Map<String, Long>) {
        flags.forEach { (id, flag) -> putFolderPinFlag(id, flag) }
    }

    @Transaction
    suspend fun importGroups(groups: List<ConversationGroup>) {
        groups.forEach { putGroup(it) }
    }

    @Transaction
    suspend fun importFolders(folders: List<ConversationFolder>) {
        folders.forEach { putFolder(it) }
    }
}
