package dev.ujhhgtg.wekit.data

import androidx.room.withTransaction
import dev.ujhhgtg.wekit.data.entity.ContactRealNamePartEntity
import dev.ujhhgtg.wekit.data.entity.DocumentEntity
import dev.ujhhgtg.wekit.data.entity.FeatureFlagOverrideEntity
import dev.ujhhgtg.wekit.data.entity.MomentCustomDetailEntity
import dev.ujhhgtg.wekit.data.entity.RealNameScanProgressEntity
import dev.ujhhgtg.wekit.data.structured.LegacyConversationCollections
import dev.ujhhgtg.wekit.features.items.moments.MomentsAutomationSettings
import dev.ujhhgtg.wekit.features.items.moments.StoredMomentAutomationConfig
import dev.ujhhgtg.wekit.features.items.payment.RedPacketSettings
import dev.ujhhgtg.wekit.features.items.payment.TransferSettings
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.LegacyPaths
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** One-time JSON conversion. Normal feature reads and writes go straight to their DAOs. */
object JsonDataMigration {
    const val MARKER_PREFIX = "json-tables-v1/"
    private data class Source(val namespace: String, val key: String, val file: String) {
        val id get() = "$namespace/$key"
    }

    private val sources = listOf(
        Source("chat", "groups", "conversation_groups.json"),
        Source("chat", "folders", "chat_folders.json"),
        Source("moments", "custom_bottom_details", "moments_custom_bottom_details.json"),
        Source("feature_flags", "overrides", "feature_flag_overrides.json"),
        Source("chat", "real_names_first_char", "real_names_first_char.json"),
        Source("chat", "real_names_last_char", "real_names.json"),
        Source("chat", "real_names_first_char_progress", "real_names_first_char_progress.json"),
        Source("json", "RedPacketSettings", "red_packet_settings.json"),
        Source("json", "TransferSettings", "auto_accept_transfer_settings.json"),
        Source("json", "AutoLikeMomentsSettings", "auto_like_moments_settings.json"),
        Source("json", "AutoRepostMomentsSettings", "auto_repost_moments_settings.json"),
    )
    private val oldRedPacketMembers = Source("json", "red_packet_group_members", "red_packet_group_members.json")

    val legacyFiles: List<String> get() = sources.map { it.file } + oldRedPacketMembers.file

    @Volatile
    private var completed: Set<String> = emptySet()

    fun isCompleted(namespace: String, key: String): Boolean = "$namespace/$key" in completed

    fun requireCompleted(namespace: String, key: String) {
        check(isCompleted(namespace, key)) { "JSON migration has not completed: $namespace/$key" }
    }

    /** Startup calls this in MAIN before enabling features; their caches remain lazily loaded. */
    fun run() = runBlocking(Dispatchers.IO) {
        completed = emptySet()
        val db = WeKitDatabase.instance
        for (source in sources) {
            try {
                if (db.documentDao().get("migration", MARKER_PREFIX + source.id) == null) {
                    migrate(db, source)
                }
                completed = completed + source.id
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // Failed transactions leave no completion marker and are retried next startup.
                // Parser messages may contain user content; log the source and exception type only.
                WeLogger.e("JsonDataMigration", "${source.id}: ${error.javaClass.simpleName}; original data retained")
            }
        }
    }

    private suspend fun migrate(db: WeKitDatabase, source: Source) {
        val raw = read(db, source)
        val simple = db.simpleStructuredDao()
        val collections = db.conversationCollectionDao()
        val automation = db.automationDao()
        when (source.id) {
            "chat/groups" -> {
                val parsed = LegacyConversationCollections.groups(raw)
                WeLogger.i("JsonDataMigration", "groups: filtered parents=${parsed.filteredParents}, members=${parsed.filteredMembers}")
                commit(db, source) { collections.importGroups(parsed.items) }
            }
            "chat/folders" -> {
                val parsed = LegacyConversationCollections.folders(raw)
                WeLogger.i("JsonDataMigration", "folders: filtered parents=${parsed.filteredParents}, members=${parsed.filteredMembers}")
                commit(db, source) { collections.importFolders(parsed.items) }
            }
            "moments/custom_bottom_details" -> {
                val values = raw?.let { DefaultJson.decodeFromString<Map<String, String>>(it) }.orEmpty()
                val filtered = values.filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
                commit(db, source) {
                    filtered.forEach { (key, text) -> simple.putCustomDetail(MomentCustomDetailEntity(key, text)) }
                }
            }
            "feature_flags/overrides" -> {
                val values = raw?.let { DefaultJson.decodeFromString<List<FeatureFlagOverrideEntity>>(it) }.orEmpty()
                require(values.all { it.internalType in setOf("i", "f", "l", "s") }) { "Unknown flag type" }
                commit(db, source) { values.associateBy { it.runtimeKey }.values.forEach { simple.putFlagOverride(it) } }
            }
            "chat/real_names_first_char", "chat/real_names_last_char" -> {
                val values = raw?.let { DefaultJson.decodeFromString<Map<String, String>>(it) }.orEmpty()
                val kind = if (source.key == "real_names_first_char") "FIRST" else "MASKED"
                commit(db, source) { values.forEach { (key, value) -> simple.putRealNamePart(ContactRealNamePartEntity(key, kind, value)) } }
            }
            "chat/real_names_first_char_progress" -> {
                val values = raw?.let { DefaultJson.decodeFromString<Map<String, Int>>(it) }.orEmpty()
                commit(db, source) { values.forEach { (key, value) -> simple.putScanProgress(RealNameScanProgressEntity(key, value)) } }
            }
            "json/RedPacketSettings" -> {
                val value = if (raw == null) RedPacketSettings.migrateLegacyConfig(read(db, oldRedPacketMembers))
                    else DefaultJson.decodeFromString<RedPacketSettings.StoredConfig>(raw)
                require(value.version == 1) { "Unsupported red-packet configuration" }
                commit(db, source) { automation.insertRedPacketConfig(value) }
            }
            "json/TransferSettings" -> {
                val value = if (raw == null) TransferSettings.migrateLegacyConfig()
                    else DefaultJson.decodeFromString<TransferSettings.StoredConfig>(raw)
                require(value.version == 1) { "Unsupported transfer configuration" }
                commit(db, source) { automation.insertTransferConfig(value) }
            }
            "json/AutoLikeMomentsSettings", "json/AutoRepostMomentsSettings" -> {
                val like = source.key == "AutoLikeMomentsSettings"
                val value = if (raw == null) {
                    (if (like) MomentsAutomationSettings.Like else MomentsAutomationSettings.Repost).migrateLegacyConfig()
                } else DefaultJson.decodeFromString<StoredMomentAutomationConfig>(raw)
                require(value.version == 1) { "Unsupported moment configuration" }
                commit(db, source) { automation.insertMomentAutomationConfig(if (like) "LIKE" else "REPOST", value) }
            }
            else -> error("Unknown JSON migration source: ${source.id}")
        }
    }

    private suspend fun commit(db: WeKitDatabase, source: Source, write: suspend () -> Unit) {
        db.withTransaction {
            val dao = db.documentDao()
            val marker = MARKER_PREFIX + source.id
            write()
            // Even an empty collection is migrated. Never infer this from a table's row count.
            dao.upsert(DocumentEntity("migration", marker, "completed", updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun read(db: WeKitDatabase, source: Source): String? {
        var document = db.documentDao().get(source.namespace, source.key)
        if (document == null) {
            val roots = if (LegacyPaths.localSourcesAllowed) listOf(KnownPaths.moduleRoot, LegacyPaths.externalModuleRoot)
                else listOf(KnownPaths.moduleRoot)
            for (root in roots) {
                val file = root.resolve(source.file)
                val attributes = try {
                    withContext(Dispatchers.IO) {
                        Files.readAttributes(file, BasicFileAttributes::class.java)
                    }
                } catch (_: NoSuchFileException) {
                    continue
                }
                check(attributes.isRegularFile) { "Legacy JSON is not a regular file" }
                document = DocumentEntity(source.namespace, source.key, file.toFile().readText(Charsets.UTF_8),
                    updatedAt = attributes.lastModifiedTime().toMillis())
                db.documentDao().upsert(document)
                break
            }
        }
        // All supported business writers used exportable=true. Preserve unexpected sources instead
        // of silently changing their export policy or inventing a new policy system for them.
        require(document == null || (document.formatVersion == 1 && document.exportable)) { "Unsupported legacy document" }
        return document?.content
    }

    suspend fun incomplete(): List<String> {
        val dao = WeKitDatabase.instance.documentDao()
        return sources.filter { dao.get("migration", MARKER_PREFIX + it.id) == null }.map { it.id }
    }

    /** Called only by explicit old-data cleanup; completion markers are never removed. */
    suspend fun deleteArchivedDocuments() {
        val db = WeKitDatabase.instance
        db.withTransaction {
            check(incomplete().isEmpty()) { "JSON migration is incomplete" }
            (sources + oldRedPacketMembers).forEach { db.documentDao().delete(it.namespace, it.key) }
        }
    }
}
