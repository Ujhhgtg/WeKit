package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.tool.ToolLoadingMode
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Assembles the prompt layers (§6) into the system message and per-turn user message, and evaluates
 * conditional prompts against model output. The "role/profile" concept was removed: prompts are
 * four flat lists.
 *
 * System message = default system prompt + [memory usage note + MEMORY.md] (memory on) +
 *   [skills catalog] (skills present) + the session's bound system prompt (if any).
 * Per-turn user message = all enabled per-turn prompts + the user's raw message (transient).
 */
class PromptComposer(
    private val toolLoadingMode: ToolLoadingMode,
    private val workspaceEnabled: Boolean,
    private val memoryEnabled: Boolean,
    private val memoryIndexContent: String?,
    /** Enabled skills as (name, description) pairs, advertised as a catalog (dynamic discovery). */
    private val skillCatalog: List<Pair<String, String>> = emptyList(),
) {

    /** Builds the full system message. [systemPromptContent] is the session's bound system prompt, or null. */
    fun composeSystemMessage(systemPromptContent: String?): String = buildString {
        append(defaultSystemPrompt())
        if (memoryEnabled) {
            append("\n\n")
            append(MEMORY_USAGE_NOTE)
            if (!memoryIndexContent.isNullOrBlank()) {
                append("\n\n# MEMORY.md\n")
                append(memoryIndexContent.trim())
            }
        }
        if (skillCatalog.isNotEmpty()) {
            append("\n\n")
            append(SKILLS_USAGE_NOTE)
            append("\n\n# 可用技能\n")
            skillCatalog.forEach { (name, desc) ->
                append("- ").append(name)
                if (desc.isNotBlank()) append("：").append(desc.trim())
                append('\n')
            }
        }
        systemPromptContent?.takeIf { it.isNotBlank() }?.let {
            append("\n\n")
            append(it.trim())
        }
    }

    /**
     * Builds the user message actually sent to the model for this turn: all globally-enabled per-turn
     * prompts (§6) prepended to the raw [userMessage]. This augmented copy is transient — only the
     * raw message is persisted/displayed — so the prefix never compounds across reloads. The previous
     * assistant reply is NOT re-added here; it is already in the message history.
     */
    fun composeTurnUserMessage(
        perTurnPrompts: List<String>,
        userMessage: String,
    ): String = buildString {
        perTurnPrompts.forEach { p ->
            p.takeIf { it.isNotBlank() }?.let { append(it.trim()); append("\n\n") }
        }
        append(userMessage)
    }

    /**
     * Evaluates conditional prompts (§6) against a model response. For each matching regex, returns
     * the corresponding content to be appended as a new user message prefixed with "\[系统提醒\]".
     * Compilation failures are skipped silently.
     */
    fun matchConditionalPrompts(
        conditionals: List<ConditionalPromptEntity>,
        modelResponseText: String,
    ): List<String> = conditionals.mapNotNull { cp ->
        val matched = runCatching { Regex(cp.regex).containsMatchIn(modelResponseText) }.getOrDefault(false)
        if (matched) "[系统提醒] ${cp.content}" else null
    }

    private fun defaultSystemPrompt(): String {
        val now = ZonedDateTime.now()
        val dateTime = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val tz = now.zone.id
        return buildString {
            append(
                """
                你是 WeAgent，一个运行在微信 App 内部的智能体，通过 WeKit 模块以 Xposed Hook 方式获得对微信客户端的操作能力。

                # 当前环境
                - 当前日期时间：$dateTime（$tz）
                - 运行环境：Android 微信客户端内嵌 Agent，你的操作会直接影响用户的真实微信账号与真实聊天记录，请谨慎操作，尤其是发送消息、删除数据等不可逆操作。
                - 你通过工具调用（function calling）与外部世界交互；除了工具调用外，你不能以任何其他方式影响微信或用户设备。

                # 工具使用说明
                - 每次你的回复中如果包含工具调用，系统会执行工具并把结果作为新一轮消息返回给你，你可以据此继续调用工具或给出最终文本回复。
                - 部分工具执行前需要用户手动审批或经过安全审查，可能会被拒绝执行；如果工具调用被拒绝，你会收到拒绝原因（并会标明该理由来自用户还是来自审查），请据此调整方案而不是重复相同调用。
                """.trimIndent()
            )
            if (toolLoadingMode == ToolLoadingMode.DYNAMIC) {
                append("\n")
                append("- 当前为动态工具发现模式：初始只提供 discover_tools 元工具。调用它 (action=list_providers/list_tools/search_tools) 来获取可用工具及其完整 JSON Schema，被发现的工具随后即可直接调用。")
            }
            if (workspaceEnabled) {
                append("\n")
                append("- 工作区：以 /workspace/ 为根的虚拟文件系统，可用文件读写工具访问；路径必须以 /workspace/ 开头，越界访问会失败。")
            }
            if (memoryEnabled) {
                append("\n")
                append("- 记忆：以 /memory/ 为根的虚拟文件系统。MEMORY.md 是索引，已注入下方；需要某条记忆细节时用文件读取工具打开对应 .md 文件。MEMORY.md 应保持精简，每条一行，不要把细节写进索引，细节应写入对应的记忆文件。")
            }
            append(
                """

                # 触发器
                - 你可以用 trigger-* 工具为自己设定「触发器」，让自己在定时（trigger-create-schedule）、收到新消息（trigger-create-message）或检测到数据库操作（trigger-create-sql）时被自动唤起运行。
                - 触发器有两种作用域：session（默认）绑定当前会话，触发时就在「本会话」里继续运行、结果出现在当前对话；global 则每次触发都新建一个独立会话运行。
                - 除非用户明确要求「每次都新开一个会话/独立对话」，否则一律使用 session（即省略 scope 参数或传 "session"）；不要主动选 global。用户说「收到消息时回复我 / 提醒我」这类需求默认就是 session。
                - 触发器触发时，本轮开头会带有「[系统提醒] 本轮由触发器…自动触发」以及事件时间线；这类轮次并非用户手动发起，请据此判断该做什么。
                - 用 trigger-list 查看已有触发器，trigger-set-enabled / trigger-delete 管理它们。

                # 行为准则
                - 涉及不可逆或高风险操作（发送消息、删除数据、批量操作等）前，除非工具本身已配置为直接放行，否则默认更谨慎，先向用户确认意图。
                - 你的回复应简洁、直接，避免不必要的寒暄。
                """.trimIndent()
            )
        }
    }

    companion object {
        private const val MEMORY_USAGE_NOTE =
            "# 记忆用途说明\n你拥有一个持久化记忆系统 (/memory/)。下面的 MEMORY.md 是记忆索引，" +
                    "列出了每条记忆文件及其简介。需要具体细节时，用文件读取工具打开对应的 .md 文件。"

        private const val SKILLS_USAGE_NOTE =
            "# 技能用途说明\n你拥有一批「技能」——针对特定任务的操作手册。下方仅列出每个技能的名称与简介；" +
                    "当某个技能与当前任务相关时，调用 load_skill 工具（传入技能名称）加载它的完整说明后再据此操作。" +
                    "技能可能附带额外资源文件，可用 load_skill 的 resource 参数读取。"
    }
}
