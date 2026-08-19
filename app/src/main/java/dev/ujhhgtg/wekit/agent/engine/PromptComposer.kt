package dev.ujhhgtg.wekit.agent.engine

import dev.ujhhgtg.wekit.agent.data.entity.ConditionalPromptEntity
import dev.ujhhgtg.wekit.agent.tool.ToolLoadingMode
import dev.ujhhgtg.wekit.agent.environment.EnvironmentSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Assembles the prompt layers (§6) into the system message and per-turn user message, and evaluates
 * conditional prompts against model output. The "role/profile" concept was removed: prompts are
 * four flat lists.
 *
 * System message = default system prompt + effective Linux environment context +
 *   [skills catalog] (skills present) + the session's bound system prompt (if any).
 * Per-turn user message = all enabled per-turn prompts + the user's raw message (transient).
 */
class PromptComposer(
    private val toolLoadingMode: ToolLoadingMode,
    private val environment: EnvironmentSnapshot,
    /** Enabled skills as (name, description) pairs, advertised as a catalog (dynamic discovery). */
    private val skillCatalog: List<Pair<String, String>> = emptyList(),
    /**
     * The instant baked into the "当前日期时间" line of the system prompt. This MUST be a value that
     * is stable across every request of a session (we pass the session's `createdAt`), NOT a fresh
     * `now()` per turn: the system prompt is the head of the cacheable prefix, so a per-request clock
     * (down to the second) would bust prompt caching on every turn and re-bill the whole context as
     * fresh input tokens. The model can fetch the live time on demand via the `get-current-time`
     * tool (builtin-info) when it actually needs the real current time.
     */
    private val promptAnchorTime: Instant = Instant.now(),
) {

    /** Builds the full system message. [systemPromptContent] is the session's bound system prompt, or null. */
    fun composeSystemMessage(systemPromptContent: String?): String = buildString {
        append(defaultSystemPrompt())
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
            append("\n\n# 其他\n")
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
        // Anchor the displayed time to the session's fixed [promptAnchorTime], NOT a per-turn now():
        // this line sits at the head of the cacheable prefix, so a live clock would bust prompt
        // caching every turn. The model calls `get-current-time` when it needs the real current time.
        val anchored = promptAnchorTime.atZone(ZoneId.systemDefault())
        val dateTime = anchored.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        val tz = anchored.zone.id
        return buildString {
            append(
                """
                你是 WeAgent，一个运行在微信 App 内部的智能体，通过 WeKit 模块以 Xposed Hook 方式获得对微信客户端的操作能力。

                # 当前环境
                - 会话创建时间：$dateTime（$tz）。注意：这是本会话开始时的时间，不会随对话推进而更新；需要获取「真实的当前时间」时，请调用 get-current-time 工具，不要依赖此处的时间。
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
            append("\n\n# 当前 Linux 执行环境\n")
            append("- 名称与类型：${environment.displayName} (${environment.type})\n")
            append("- 系统与架构：${environment.operatingSystem} / ${environment.architecture}\n")
            append("- Shell 与工作目录：${environment.shell} / ${environment.workingDirectory}\n")
            append("- 权限边界：${environment.privilegesAndCapabilities}\n")
            append("- invoke_tool：${environment.bridgeLocation ?: "不可用"}")
            if (environment.type == dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType.PROOT || environment.type == dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType.CHROOT) {
                append("\n- 本地 Arch Linux 共享 Android 内核；${if (environment.type == dev.ujhhgtg.wekit.agent.environment.LinuxEnvironmentType.PROOT) "PRoot 无内核级隔离" else "chroot 不是安全边界"}。")
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
        private const val SKILLS_USAGE_NOTE =
            "# 技能用途说明\n你拥有一批「技能」——针对特定任务的操作手册。下方仅列出每个技能的名称与简介；" +
                    "当某个技能与当前任务相关时，调用 load_skill 工具（传入技能名称）加载它的完整说明后再据此操作。" +
                    "技能可能附带额外资源文件，可用 load_skill 的 resource 参数读取。"
    }
}
