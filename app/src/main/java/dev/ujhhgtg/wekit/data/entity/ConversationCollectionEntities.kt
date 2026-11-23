package dev.ujhhgtg.wekit.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_groups")
data class ConversationGroupEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val name: String,
    val type: String,
    val selectFields: String,
    val whereClause: String,
    val builtInLabel: String?,
)

@Entity(
    tableName = "conversation_group_members",
    primaryKeys = ["groupId", "position"],
    foreignKeys = [ForeignKey(
        entity = ConversationGroupEntity::class,
        parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("groupId", "wxId")],
)
data class ConversationGroupMemberEntity(val groupId: String, val position: Int, val wxId: String)

@Entity(tableName = "conversation_folders")
data class ConversationFolderEntity(
    @PrimaryKey val id: String,
    val position: Int,
    val name: String,
    val type: String,
    val selectFields: String,
    val whereClause: String,
    val pinFlag: Long,
)

@Entity(
    tableName = "conversation_folder_members",
    primaryKeys = ["folderId", "position"],
    foreignKeys = [ForeignKey(
        entity = ConversationFolderEntity::class,
        parentColumns = ["id"], childColumns = ["folderId"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("folderId", "wxId")],
)
data class ConversationFolderMemberEntity(val folderId: String, val position: Int, val wxId: String)

val collectionSchemaSql = listOf(
    "CREATE TABLE IF NOT EXISTS `conversation_groups` (`id` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `selectFields` TEXT NOT NULL, `whereClause` TEXT NOT NULL, `builtInLabel` TEXT, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `conversation_group_members` (`groupId` TEXT NOT NULL, `position` INTEGER NOT NULL, `wxId` TEXT NOT NULL, PRIMARY KEY(`groupId`, `position`), FOREIGN KEY(`groupId`) REFERENCES `conversation_groups`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS `index_conversation_group_members_groupId_wxId` ON `conversation_group_members` (`groupId`, `wxId`)",
    "CREATE TABLE IF NOT EXISTS `conversation_folders` (`id` TEXT NOT NULL, `position` INTEGER NOT NULL, `name` TEXT NOT NULL, `type` TEXT NOT NULL, `selectFields` TEXT NOT NULL, `whereClause` TEXT NOT NULL, `pinFlag` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    "CREATE TABLE IF NOT EXISTS `conversation_folder_members` (`folderId` TEXT NOT NULL, `position` INTEGER NOT NULL, `wxId` TEXT NOT NULL, PRIMARY KEY(`folderId`, `position`), FOREIGN KEY(`folderId`) REFERENCES `conversation_folders`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS `index_conversation_folder_members_folderId_wxId` ON `conversation_folder_members` (`folderId`, `wxId`)",
)
