package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import android.os.SystemClock
import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object ChatAutoReply : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "聊天自动回复"
    override val nameRes = R.string.feature_chat_auto_reply_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_auto_reply_description

    private const val TAG = "ChatAutoReply"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ChatAutoReply").apply { isDaemon = true }
    }
    private val cooldowns = ConcurrentHashMap<String, Long>()
    private val generation = AtomicLong()

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        cooldowns.clear()
        generation.incrementAndGet()
    }

    override fun onClick(context: ComponentActivity) {
        AutoReplySettings.showMainDialog(context)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val type = values.getAsInteger("type") ?: return
        if (MessageType.fromCode(type)?.isText != true) return
        val isSend = values.getAsInteger("isSend") ?: 1
        if (isSend != 0) return
        val talker = values.getAsString("talker") ?: return
        val content = values.getAsString("content") ?: return
        val sender = values.getAsString("sender").takeIf { talker.isGroupChatWxId }

        val rules = AutoReplySettings.resolve(talker, sender)
        if (!rules.enabled.enabled) return
        if (!rules.timeRange.matches()) return

        val gen = generation.get()
        executor.execute {
            try {
                process(rules, talker, content, gen)
            } catch (e: Throwable) {
                WeLogger.e(TAG, "auto reply processing failed", e)
            }
        }
    }

    private fun process(rules: AutoReplyRuleSet, talker: String, content: String, gen: Long) {
        rules.tasks.forEachIndexed { index, task ->
            if (gen != generation.get()) return
            if (!task.enabled) return@forEachIndexed
            if (!task.keyword.matches(content)) return@forEachIndexed

            val now = SystemClock.elapsedRealtime()
            val cooldownKey = "$index:$talker"
            val cooldown = task.cooldownMs.toLongOrNull() ?: 0L
            if (cooldown > 0 && now - (cooldowns[cooldownKey] ?: 0L) < cooldown) {
                WeLogger.i(TAG, "task skipped by cooldown: task=${task.name}, talker=$talker")
                return@forEachIndexed
            }

            val delay = task.delayMs.toLongOrNull()?.coerceIn(0L, 60000L) ?: 0L
            if (delay > 0) Thread.sleep(delay)
            if (gen != generation.get()) return

            val reply = task.reply
            val sent = when (reply.type) {
                AutoReplyType.TEXT ->
                    reply.text.isNotBlank() && WeMessageApi.sendText(talker, reply.text)

                AutoReplyType.IMAGE ->
                    reply.path.isNotBlank() && File(reply.path).isFile &&
                        WeMessageApi.sendImage(talker, reply.path)

                AutoReplyType.VIDEO ->
                    reply.path.isNotBlank() && File(reply.path).isFile &&
                        WeMessageApi.sendVideo(talker, reply.path)

                AutoReplyType.VOICE -> {
                    val duration = reply.voiceDurationMs.toIntOrNull() ?: 0
                    reply.path.isNotBlank() && File(reply.path).isFile && duration in 1..60000 &&
                        WeMessageApi.sendVoice(talker, reply.path, duration)
                }
            }
            if (sent) cooldowns[cooldownKey] = SystemClock.elapsedRealtime()
            if (task.stopAfterMatch) return
        }
    }
}
