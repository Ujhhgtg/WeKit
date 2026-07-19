package dev.ujhhgtg.wekit.agent.data

import androidx.room.TypeConverter
import dev.ujhhgtg.wekit.agent.data.entity.ApprovalStatus
import dev.ujhhgtg.wekit.agent.data.entity.McpTransport
import dev.ujhhgtg.wekit.agent.data.entity.MessageRole
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.tool.ProviderKind
import dev.ujhhgtg.wekit.agent.tool.ToolMode
import java.time.Instant

/**
 * Room type converters for the WeAgent database. Enums are stored by name (stable across
 * refactors as long as constant names are preserved); [Instant] is stored as epoch millis.
 */
class WeAgentConverters {

    @TypeConverter
    fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()
    @TypeConverter
    fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun messageRoleToString(v: MessageRole?): String? = v?.name
    @TypeConverter
    fun stringToMessageRole(v: String?): MessageRole? = v?.let(MessageRole::valueOf)

    @TypeConverter
    fun approvalToString(v: ApprovalStatus?): String? = v?.name
    @TypeConverter
    fun stringToApproval(v: String?): ApprovalStatus? = v?.let(ApprovalStatus::valueOf)

    @TypeConverter
    fun providerKindToString(v: ProviderKind?): String? = v?.name
    @TypeConverter
    fun stringToProviderKind(v: String?): ProviderKind? = v?.let(ProviderKind::valueOf)

    @TypeConverter
    fun mcpTransportToString(v: McpTransport?): String? = v?.name
    @TypeConverter
    fun stringToMcpTransport(v: String?): McpTransport? = v?.let(McpTransport::valueOf)

    @TypeConverter
    fun toolModeToString(v: ToolMode?): String? = v?.name
    @TypeConverter
    fun stringToToolMode(v: String?): ToolMode? = v?.let(ToolMode::valueOf)

    @TypeConverter
    fun modelProviderTypeToString(v: ModelProviderType?): String? = v?.name
    @TypeConverter
    fun stringToModelProviderType(v: String?): ModelProviderType? = v?.let(ModelProviderType::valueOf)

}
