package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.core.WeKitOperation
import dev.ujhhgtg.wekit.utils.HostInfo
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in `builtin-info` tools: read-only environment/runtime lookups the agent can call on demand
 * (§ info). These exist so the system prompt does NOT have to embed volatile, per-request values
 * (the current time, etc.) — embedding a live clock at the head of the prompt would bust prompt
 * caching on every turn and re-bill the whole context (see
 * [dev.ujhhgtg.wekit.agent.engine.PromptComposer.promptAnchorTime]). Instead the prompt carries a
 * frozen session-creation time and the model fetches the real current values through these tools
 * when it actually needs them.
 *
 * All tools are `sideEffect = false` → factory-default ENABLED (pure reads, never mutate anything).
 */
object WeInfoToolBindings {

    private const val GROUP = WeKitOperation.BUILTIN_INFO

    @WeKitOperation(
        name = "get-current-time",
        description = "Get the REAL current date and time (the system prompt's time is only the session-creation time and does not advance). Returns the local time, timezone, UTC time, epoch milliseconds, and the day of week. Optionally pass a Java time-zone id (e.g. 'UTC', 'America/New_York') to format the local part in that zone instead of the device's.",
        sideEffect = false,
        group = GROUP,
    )
    fun getCurrentTime(
        @Param("Optional IANA/Java time-zone id to format in (default: device zone)") timeZoneId: String?,
    ): String {
        val now = Instant.now()
        val zone = timeZoneId?.takeIf { it.isNotBlank() }?.let {
            runCatching { ZoneId.of(it) }.getOrNull()
                ?: return "Error: unknown time-zone id '$it'. Use a Java/IANA id like 'UTC' or 'Asia/Shanghai'."
        } ?: ZoneId.systemDefault()

        val local = now.atZone(zone)
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val utc = now.atZone(ZoneId.of("UTC"))
        return buildString {
            append("local=").append(local.format(fmt)).append(" (").append(zone.id).append(")\n")
            append("weekday=").append(local.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())).append("\n")
            append("utc=").append(utc.format(fmt)).append(" (UTC)\n")
            append("epochMillis=").append(now.toEpochMilli())
        }
    }

    @WeKitOperation(
        name = "get-environment-info",
        description = "Get static information about the runtime environment WeAgent is running in: the host app (usually WeChat) package name, version name and version code, the Android OS version and SDK level, the device model, the device's default locale, and the device's default time-zone id. Read-only.",
        sideEffect = false,
        group = GROUP,
    )
    fun getEnvironmentInfo(): String {
        val locale = Locale.getDefault()
        val tz = TimeZone.getDefault()
        return buildString {
            append("host.package=").append(HostInfo.packageName).append("\n")
            append("host.versionName=").append(HostInfo.versionName).append("\n")
            append("host.versionCode=").append(HostInfo.versionCode).append("\n")
            append("android.release=").append(android.os.Build.VERSION.RELEASE).append("\n")
            append("android.sdkInt=").append(android.os.Build.VERSION.SDK_INT).append("\n")
            append("device.manufacturer=").append(android.os.Build.MANUFACTURER).append("\n")
            append("device.model=").append(android.os.Build.MODEL).append("\n")
            append("locale=").append(locale.toLanguageTag()).append("\n")
            append("timeZone=").append(tz.id)
        }
    }
}
