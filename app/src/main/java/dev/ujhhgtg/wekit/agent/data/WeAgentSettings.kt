package dev.ujhhgtg.wekit.agent.data

import dev.ujhhgtg.wekit.agent.data.WeAgentSettings.load
import dev.ujhhgtg.wekit.agent.data.entity.SettingEntity
import dev.ujhhgtg.wekit.agent.model.local.LocalLlama
import dev.ujhhgtg.wekit.agent.tool.PermissionLevel
import dev.ujhhgtg.wekit.agent.tool.ToolLoadingMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Typed accessor over the `settings` key-value table for WeAgent global configuration (§2.1, §3.3,
 * §5.4). Values are cached in memory after [load]; writes update both the DB and cache.
 * Kept deliberately small — per-feature UI reads/writes go through the named helpers here.
 */
object WeAgentSettings {

    private val db get() = WeAgentDatabase.instance
    private val cache = ConcurrentHashMap<String, String>()

    // Keys
    const val KEY_TOOL_LOADING_MODE = "tool_loading_mode"            // §3.3 STATIC | DYNAMIC
    const val KEY_SMALL_MODEL_ID = "small_model_id"                  // §5.4 ("" = same as main)
    const val KEY_DEFAULT_MODEL_ID = "default_model_id"             // new-session default
    const val KEY_DEFAULT_SYSTEM_PROMPT_ID = "default_system_prompt_id" // new-session default binding
    const val KEY_DEFAULT_PERMISSION_LEVEL = "default_permission_level" // §3.1 session permission default
    const val KEY_DEFAULT_LINUX_ENVIRONMENT_ID = "default_linux_environment_id"
    const val KEY_NATIVE_LINUX_WORKING_DIRECTORY = "native_linux_working_directory"
    const val KEY_NATIVE_LINUX_ENVIRONMENT_VARIABLES = "native_linux_environment_variables"
    const val KEY_SEND_WHILE_RUNNING = "send_while_running"         // QUEUE_AFTER_TURN | QUEUE_AS_STEER
    const val KEY_OVERLAY_MODE = "overlay_mode"                     // DISABLED | FOREGROUND_ONLY | ALWAYS
    const val KEY_LOCAL_COMPUTE_BACKEND = "local_compute_backend"

    /** Superseded by [KEY_OVERLAY_MODE]; still read once for migration of existing installs. */
    const val KEY_OVERLAY_FOREGROUND_ONLY = "overlay_foreground_only"

    suspend fun load() {
        cache.clear()
    }

    private suspend fun get(key: String): String? = cache[key] ?: db.settingDao().getValue(key)?.also { cache[key] = it }

    suspend fun set(key: String, value: String) {
        db.settingDao().upsert(SettingEntity(key, value))
        cache[key] = value
    }

    /** Deletes the row for [key] and drops it from the in-memory cache together. */
    suspend fun clear(key: String) {
        db.settingDao().delete(key)
        cache.remove(key)
    }

    /**
     * Drops [key] from the in-memory cache after its row was already deleted inside a caller's
     * Room transaction (the DB write goes through [SettingDao.delete] there, not through [clear]).
     */
    fun clearCached(key: String) {
        cache.remove(key)
    }

    /**
     * Setting keys holding a model id present in [modelIds] — the defaults that must be deleted
     * when those models disappear. Read outside any transaction; the caller deletes the returned
     * rows inside its own Room transaction and calls [clearCached] afterwards.
     */
    suspend fun modelDefaultKeysFor(modelIds: Set<String>): List<String> =
        listOf(KEY_DEFAULT_MODEL_ID, KEY_SMALL_MODEL_ID).filter { key ->
            val v = get(key); v != null && v in modelIds
        }

    suspend fun systemPromptDefaultKeysFor(promptIds: Set<String>): List<String> =
        listOf(KEY_DEFAULT_SYSTEM_PROMPT_ID).filter { key ->
            val v = get(key); v != null && v in promptIds
        }

    suspend fun toolLoadingMode(): ToolLoadingMode =
        when (get(KEY_TOOL_LOADING_MODE)) {
            "DYNAMIC" -> ToolLoadingMode.DYNAMIC
            else -> ToolLoadingMode.STATIC
        }

    /** Small model id for smart-approval & title generation; blank means "same as main model" (§5.4). */
    suspend fun smallModelId(): String? = get(KEY_SMALL_MODEL_ID)?.takeIf { it.isNotBlank() }

    suspend fun defaultModelId(): String? = get(KEY_DEFAULT_MODEL_ID)?.takeIf { it.isNotBlank() }

    /**
     * The default session permission level for sessions that don't bind one explicitly (§3.1).
     * REQUEST_APPROVAL preserves the historical factory behavior: side-effect-free tools run
     * directly, side-effecting tools wait for a manual decision.
     */
    suspend fun defaultPermissionLevel(): PermissionLevel =
        get(KEY_DEFAULT_PERMISSION_LEVEL)?.let { stored ->
            PermissionLevel.entries.firstOrNull { it.name == stored }
        } ?: PermissionLevel.REQUEST_APPROVAL

    suspend fun defaultSystemPromptId(): String? = get(KEY_DEFAULT_SYSTEM_PROMPT_ID)?.takeIf { it.isNotBlank() }
    suspend fun nativeLinuxWorkingDirectory(): String? = get(KEY_NATIVE_LINUX_WORKING_DIRECTORY)?.takeIf { it.isNotBlank() }
    suspend fun nativeLinuxEnvironmentVariables(): String = get(KEY_NATIVE_LINUX_ENVIRONMENT_VARIABLES) ?: "{}"
    suspend fun localComputeBackend(): String =
        get(KEY_LOCAL_COMPUTE_BACKEND)?.takeIf { it in LocalLlama.BACKENDS } ?: "auto"
    suspend fun setLocalComputeBackend(value: String) {
        require(value in LocalLlama.BACKENDS) { "unsupported local compute backend: $value" }
        set(KEY_LOCAL_COMPUTE_BACKEND, value)
    }
    suspend fun defaultLinuxEnvironmentId(): String? =
        get(KEY_DEFAULT_LINUX_ENVIRONMENT_ID)?.takeIf { it.isNotBlank() }

    /**
     * When the floating ball should be attached. An explicit [KEY_OVERLAY_MODE] value is
     * authoritative. When none exists yet, the mode is migrated once from the legacy feature
     * preference ([legacyFeatureEnabled]): enabled installs keep their old behavior
     * ([KEY_OVERLAY_FOREGROUND_ONLY] → [OverlayMode.FOREGROUND_ONLY], else [OverlayMode.ALWAYS]),
     * disabled/absent installs become [OverlayMode.DISABLED] — and the result is persisted.
     */
    suspend fun overlayMode(legacyFeatureEnabled: Boolean? = null): OverlayMode {
        get(KEY_OVERLAY_MODE)?.let { stored ->
            return OverlayMode.entries.firstOrNull { it.name == stored } ?: OverlayMode.DISABLED
        }
        val migrated = when {
            legacyFeatureEnabled == true && get(KEY_OVERLAY_FOREGROUND_ONLY)?.toBoolean() == true ->
                OverlayMode.FOREGROUND_ONLY
            legacyFeatureEnabled == true -> OverlayMode.ALWAYS
            else -> OverlayMode.DISABLED
        }
        set(KEY_OVERLAY_MODE, migrated.name)
        return migrated
    }

    /** Reads the send-while-running mode, defaulting to QUEUE_AFTER_TURN. */
    suspend fun sendWhileRunningMode(): dev.ujhhgtg.wekit.features.api.agent.WeAgentService.SendWhileRunningMode =
        when (get(KEY_SEND_WHILE_RUNNING)) {
            "QUEUE_AS_STEER" -> dev.ujhhgtg.wekit.features.api.agent.WeAgentService.SendWhileRunningMode.QUEUE_AS_STEER
            else -> dev.ujhhgtg.wekit.features.api.agent.WeAgentService.SendWhileRunningMode.QUEUE_AFTER_TURN
        }
}

/**
 * When the WeAgent floating ball is attached to the WindowManager. [label] is the user-facing
 * option text; declaration order is the order shown in the picker.
 */
enum class OverlayMode(val label: String) {
    DISABLED("禁用"),
    FOREGROUND_ONLY("仅在微信前台时显示"),
    ALWAYS("始终显示"),
}
