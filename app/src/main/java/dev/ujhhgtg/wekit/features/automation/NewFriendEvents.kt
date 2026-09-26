package dev.ujhhgtg.wekit.features.automation

import android.content.ContentValues
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** An incoming friend request. [verifyUsername] (`v3_…@stranger` when present) is what `verifyUser` needs. */
data class NewFriendRequest(
    val wxId: String,
    val verifyUsername: String,
    val ticket: String,
    val scene: Int,
    val nickname: String = "",
)

/**
 * Friend requests from both the `fmessage_msginfo` insert and type-37 messages, deduplicated
 * within [DEDUP_WINDOW_MS]. Listeners run on WeChat's DB thread.
 */
object NewFriendEvents : WeDatabaseListenerApi.IInsertListener {
    private const val DEDUP_WINDOW_MS = 3000L
    private const val VERIFY_ALIAS_WINDOW_MS = 10 * 60 * 1000L

    private val listeners = CopyOnWriteArrayList<(NewFriendRequest) -> Unit>()
    private val recent = ConcurrentHashMap<String, Long>()
    private val aliases = ConcurrentHashMap<String, Pair<NewFriendRequest, Long>>()

    @Synchronized
    fun subscribe(listener: (NewFriendRequest) -> Unit) {
        if (listeners.isEmpty()) WeDatabaseListenerApi.addListener(this)
        listeners += listener
    }

    @Synchronized
    fun unsubscribe(listener: (NewFriendRequest) -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) WeDatabaseListenerApi.removeListener(this)
    }

    /** Maps a real wxid seen in a recent request back to its verify username (for `verifyUser`). */
    fun resolveVerifyUsername(wxId: String, ticket: String = ""): String {
        if (isVerifyUsername(wxId)) return wxId
        val now = System.currentTimeMillis()
        return aliases.values.asSequence()
            .filter { (request, time) -> now - time <= VERIFY_ALIAS_WINDOW_MS && request.wxId == wxId }
            .filter { (request, _) -> ticket.isEmpty() || request.ticket == ticket }
            .maxByOrNull { it.second }?.first?.verifyUsername ?: wxId
    }

    override fun onInsert(table: String, values: ContentValues) {
        val request = when (table) {
            "fmessage_msginfo" -> {
                if ((values.getAsInteger("isSend") ?: 0) != 0) return
                parse(
                    raw = listOf("msgContent", "fmsgContent", "content", "msgSource")
                        .mapNotNull { values.getAsString(it)?.takeIf(String::isNotBlank) }.joinToString("\n"),
                    fallbackWxId = firstNotBlank(
                        values.getAsString("fromusername"), values.getAsString("encryptusername"),
                        values.getAsString("encryptTalker"), values.getAsString("talker"),
                    ),
                    fallbackTicket = firstNotBlank(values.getAsString("ticket"), values.getAsString("antispamticket")),
                    fallbackScene = firstNotBlank(values.getAsString("scene"), values.getAsString("sceneid")),
                )
            }

            "message" -> {
                if ((values.getAsInteger("type") ?: 0) != MessageType.FRIEND_VERIFY.code) return
                val message = MessageInfo.fromContentValues(values)
                if (message.isSelfSender) return
                parse(message.content, fallbackWxId = message.talker)
            }

            else -> return
        } ?: return
        dispatch(request)
    }

    private fun dispatch(request: NewFriendRequest) {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { now - it.value > DEDUP_WINDOW_MS }
        aliases.entries.removeIf { now - it.value.second > VERIFY_ALIAS_WINDOW_MS }
        if (request.wxId != request.verifyUsername) aliases["${request.wxId}|${request.ticket}"] = request to now
        val key = "${request.verifyUsername}|${request.ticket}|${request.scene}"
        if (recent.putIfAbsent(key, now) != null) return
        listeners.forEach { it(request) }
    }

    /**
     * Parses a friend-request payload (the `<msg … />` element, possibly with extra text). Values
     * may be attributes, child tags or `?key=value` query parameters, as WeChat versions differ.
     */
    fun parse(
        raw: String,
        fallbackWxId: String = "",
        fallbackTicket: String = "",
        fallbackScene: String = "",
    ): NewFriendRequest? {
        if (raw.isBlank() && fallbackWxId.isBlank()) return null
        fun lookup(vararg names: String) = firstNotBlank(*names.flatMap { listOf(tag(raw, it), attr(raw, it), query(raw, it)) }.toTypedArray())

        val encrypted = firstNotBlank(
            lookup("encryptusername", "encryptuser"),
            fallbackWxId.takeIf(::isVerifyUsername).orEmpty(),
        ).clean().takeIf(::isValidRequester).orEmpty()
        val contact = firstNotBlank(
            lookup("fromusername", "username"),
            fallbackWxId.takeUnless(::isVerifyUsername).orEmpty(),
        ).clean().takeIf { isValidRequester(it) && !isVerifyUsername(it) }.orEmpty()
        val wxId = contact.ifEmpty { encrypted }
        val ticket = firstNotBlank(lookup("ticket", "antispamticket", "antispam_ticket", "verifyticket", "verify_ticket"), fallbackTicket).clean()
        if (wxId.isEmpty() || ticket.isEmpty()) return null
        val scene = firstNotBlank(lookup("scene", "scence", "sceneid", "scene_id"), fallbackScene).clean().toIntOrNull() ?: 0
        return NewFriendRequest(
            wxId = wxId,
            verifyUsername = encrypted.ifEmpty { wxId },
            ticket = ticket,
            scene = scene,
            nickname = lookup("fromnickname", "nickname").clean(),
        )
    }

    private fun isValidRequester(value: String): Boolean =
        value.isNotBlank() && !value.equals("fmessage", ignoreCase = true) &&
                !value.endsWith("@chatroom") && !value.endsWith("@openim") && !value.endsWith("@im.chatroom") &&
                !value.startsWith("gh_") && value.any(Char::isLetterOrDigit)

    private fun isVerifyUsername(value: String): Boolean = value.trim().let {
        it.endsWith("@stranger", ignoreCase = true) || it.startsWith("v1_", ignoreCase = true) ||
                it.startsWith("v2_", ignoreCase = true) || it.startsWith("v3_", ignoreCase = true)
    }

    private fun tag(source: String, name: String): String =
        Regex("<$name>(?:<!\\[CDATA\\[)?(.*?)(?:]]>)?</$name>", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1).orEmpty()

    private fun attr(source: String, name: String): String =
        Regex("""\b${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""", RegexOption.IGNORE_CASE).find(source)?.groupValues?.get(2).orEmpty()

    private fun query(source: String, name: String): String =
        Regex("""(?:[?&]|&amp;)${Regex.escape(name)}=([^&\s<"']+)""", RegexOption.IGNORE_CASE).find(source)
            ?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }.orEmpty()

    private fun String.clean(): String = trim()
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .trim().trim('"', '\'', ' ', '\n', '\r', '\t')

    private fun firstNotBlank(vararg values: String?): String = values.firstOrNull { !it.isNullOrBlank() }.orEmpty()
}
