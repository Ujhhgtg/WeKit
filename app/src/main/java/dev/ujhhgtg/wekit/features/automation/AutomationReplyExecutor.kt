package dev.ujhhgtg.wekit.features.automation

import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeFavoriteApi
import dev.ujhhgtg.wekit.features.api.core.WeGroupApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.io.path.div
import kotlin.random.Random

/** The message (or event) a reply answers; drives variables, quoting and group-only actions. */
data class AutomationReplyContext(
    val talker: String,
    val sender: String = talker,
    val content: String = "",
    /** Local id of the message to quote when a rule replies as quote; 0 when there is none. */
    val msgId: Long = 0L,
    /** Extra `$name` variables (e.g. `amount`), applied before the built-in ones. */
    val variables: Map<String, String> = emptyMap(),
) {
    val isGroup: Boolean get() = AutomationTalkerCategory.of(talker) == AutomationTalkerCategory.GROUP
}

/** Sends [AutomationReplyStep] sequences. All sends block, so callers go through [launch]. */
object AutomationReplyExecutor {
    private const val TAG = "AutomationReplyExecutor"
    private const val AT_ALL = "notify@all"
    private const val PART_GAP_MS = 300L
    private val AT_REGEX = Regex("""\[AtWx=([^\]]+)]""")
    private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "aac", "m4a", "amr", "flac", "opus", "silk")

    /** Variables offered by every reply editor; features add their own (e.g. `$amount`). */
    val BASE_PLACEHOLDERS = listOf(
        $$"$nickname", $$"$senderName", $$"$senderWxid", $$"$talker", $$"$groupName", $$"$content",
        $$"$atSender", $$"$atAll", $$"$time", $$"$date",
    )

    fun launch(
        steps: List<AutomationReplyStep>,
        context: AutomationReplyContext,
        asQuote: Boolean = false,
        initialDelayMs: Long = 0L,
        onFinished: (sentAny: Boolean) -> Unit = {},
    ) {
        if (steps.isEmpty()) return
        thread(name = "AutomationReply") {
            onFinished(run(steps, context, asQuote, initialDelayMs))
        }
    }

    fun launch(rule: AutomationReplyRule, context: AutomationReplyContext, initialDelayMs: Long = 0L) {
        if (rule.enabled) launch(rule.steps, context, rule.asQuote, initialDelayMs)
    }

    /** Runs [steps] on the calling thread; returns whether anything was sent. */
    fun run(
        steps: List<AutomationReplyStep>,
        context: AutomationReplyContext,
        asQuote: Boolean = false,
        initialDelayMs: Long = 0L,
    ): Boolean {
        if (initialDelayMs > 0) Thread.sleep(initialDelayMs)
        var sent = false
        steps.forEachIndexed { index, step ->
            val delay = step.delayMs + if (step.randomDelay) Random.nextLong(0L, 2001L) else 0L
            if (delay > 0) Thread.sleep(delay)
            sent = runCatching { send(step, context, asQuote) }
                .onFailure { WeLogger.e(TAG, "failed to send ${step.kind} step to ${context.talker}", it) }
                .getOrDefault(false) || sent
            if (index < steps.lastIndex && step.delayMs <= 0L) Thread.sleep(PART_GAP_MS)
        }
        return sent
    }

    private fun send(step: AutomationReplyStep, context: AutomationReplyContext, asQuote: Boolean): Boolean {
        val target = context.talker
        return when (step.kind) {
            AutomationReplyStepKind.TEXT -> {
                val parts = format(step.content, context).split('|').map(String::trim).filter(String::isNotEmpty)
                var sent = false
                parts.forEachIndexed { index, text ->
                    sent = sendText(target, text, context, asQuote) || sent
                    if (index < parts.lastIndex) Thread.sleep(PART_GAP_MS)
                }
                sent
            }

            AutomationReplyStepKind.IMAGE -> sendPaths(step.content) { WeMessageApi.sendImage(target, it) }
            AutomationReplyStepKind.VOICE -> sendPaths(step.content) { sendVoice(target, it) }
            AutomationReplyStepKind.RANDOM_VOICE -> randomAudioFile(step.content)?.let { sendVoice(target, it) } ?: run {
                WeLogger.w(TAG, "random voice step has no audio files: ${step.content}")
                false
            }

            AutomationReplyStepKind.EMOJI -> sendPaths(step.content) { WeMessageApi.sendSticker(target, it) }
            AutomationReplyStepKind.VIDEO -> sendPaths(step.content) { WeMessageApi.sendVideo(target, it) }
            AutomationReplyStepKind.FILE -> sendPaths(step.content) { WeMessageApi.sendFile(target, it, File(it).name) }
            AutomationReplyStepKind.FAVORITE -> eachValue(step.content) { id -> WeFavoriteApi.send(target, id.toLong()) }

            AutomationReplyStepKind.CARD -> eachValue(step.content) { WeMessageApi.sendShareCard(target, it) }
            AutomationReplyStepKind.INVITE_GROUP -> {
                val member = context.sender
                eachValue(step.content) { groupId ->
                    WeGroupApi.inviteMember(groupId, member)
                    true
                }
            }

            AutomationReplyStepKind.XML -> WeMessageApi.sendXmlAppMsg(target, ensureXml(format(step.content, context)))
        }
    }

    /** Replaces `$name` variables; longer names first so `$senderName` isn't eaten by a shorter one. */
    fun format(template: String, context: AutomationReplyContext): String {
        val senderName = senderName(context)
        val now = Date()
        val variables = context.variables + mapOf(
            "nickname" to senderName,
            "senderName" to senderName,
            "senderWxid" to context.sender,
            "talker" to context.talker,
            "groupName" to if (context.isGroup) WeDatabaseApi.getDisplayName(context.talker) else "",
            "content" to context.content,
            "atSender" to "[AtWx=${context.sender}]",
            "atAll" to if (context.isGroup) "[AtWx=$AT_ALL]" else "@所有人",
            "time" to SimpleDateFormat("HH:mm", Locale.getDefault()).format(now),
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now),
        )
        return variables.keys.sortedByDescending { it.length }.fold(template) { text, name ->
            text.replace("$$name", variables.getValue(name))
        }
    }

    private fun senderName(context: AutomationReplyContext): String {
        val name = if (context.isGroup && context.sender != context.talker) {
            WeDatabaseApi.getGroupMemberDisplayName(context.talker, context.sender)
        } else {
            WeDatabaseApi.getDisplayName(context.sender)
        }
        return name.ifBlank { context.sender }
    }

    /** Sends text, expanding `[AtWx=wxid]` into real mentions; mentions can't be quoted. */
    private fun sendText(target: String, text: String, context: AutomationReplyContext, asQuote: Boolean): Boolean {
        if (text.isBlank()) return false
        val atList = AT_REGEX.findAll(text).map { it.groupValues[1].trim() }.filter(String::isNotEmpty).toList()
        if (atList.isEmpty()) {
            return if (asQuote && context.msgId > 0L) WeMessageApi.sendQuoteTextByMsgId(target, context.msgId, text)
            else WeMessageApi.sendText(target, text)
        }
        val expanded = AT_REGEX.replace(text) { match ->
            val wxId = match.groupValues[1].trim()
            val name = if (wxId == AT_ALL) "所有人" else {
                WeDatabaseApi.getGroupMemberDisplayName(target, wxId)
                    .ifBlank { WeDatabaseApi.getDisplayName(wxId) }
                    .ifBlank { wxId }
            }
            "@$name "
        }
        return WeMessageApi.sendTextWithAtList(target, expanded, atList)
    }

    private fun sendPaths(content: String, sender: (String) -> Boolean): Boolean = eachValue(content) { path ->
        if (File(path).isFile) sender(path) else {
            WeLogger.w(TAG, "reply file is missing: $path")
            false
        }
    }

    private fun eachValue(content: String, sender: (String) -> Boolean): Boolean {
        val values = splitReplyValues(content)
        var sent = false
        values.forEachIndexed { index, value ->
            sent = sender(value) || sent
            if (index < values.lastIndex) Thread.sleep(PART_GAP_MS)
        }
        return sent
    }

    /** Sends an audio file as a voice message, converting to SILK unless it already is one. */
    private fun sendVoice(target: String, path: String): Boolean {
        if (path.endsWith(".silk", ignoreCase = true)) {
            return WeMessageApi.sendVoice(target, path, voiceDuration(path))
        }
        val dir = (KnownPaths.moduleCache / "automation_voice").createDirsSafe()
        val silk = (dir / "${UUID.randomUUID()}.silk").toFile()
        return try {
            if (!AudioUtils.anyToSilk(path, silk.absolutePath)) {
                WeLogger.w(TAG, "failed to convert $path to SILK")
                return false
            }
            WeMessageApi.sendVoice(target, silk.absolutePath, voiceDuration(path))
        } finally {
            silk.delete()
        }
    }

    private fun voiceDuration(path: String): Int =
        AudioUtils.getDurationMs(path).coerceIn(1000L, Int.MAX_VALUE.toLong()).toInt()

    /** One random audio file from the listed files, or else from the first listed folder. */
    private fun randomAudioFile(content: String): String? {
        val entries = splitReplyValues(content).map(::File)
        fun File.isAudio() = isFile && extension.lowercase() in AUDIO_EXTENSIONS
        entries.filter { it.isAudio() }.takeIf { it.isNotEmpty() }?.let { return it.random().absolutePath }
        return entries.filter(File::isDirectory)
            .flatMap { it.listFiles().orEmpty().filter { f -> f.isAudio() } }
            .randomOrNull()?.absolutePath
    }

    private fun ensureXml(content: String): String {
        if (content.trim().startsWith("<")) return content
        val safe = content.replace("]]>", "]]]]><![CDATA[>")
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><msg><appmsg appid=\"\" sdkver=\"0\"><title><![CDATA[$safe]]></title>" +
                "<des><![CDATA[$safe]]></des><type>1</type><content><![CDATA[$safe]]></content></appmsg></msg>"
    }
}
