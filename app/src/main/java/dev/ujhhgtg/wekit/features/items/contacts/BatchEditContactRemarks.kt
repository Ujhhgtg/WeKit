package dev.ujhhgtg.wekit.features.items.contacts

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.features.api.core.WeContactApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 批量修改联系人备注。
 *
 * 流程：多选好友 → 配置（源字段 + 匹配正则 + 替换串）→ 后台逐个按正则生成新备注并落库。
 * 备注在微信中存储于 `rcontact.conRemark`，微信通讯录右侧字母分组（J/C/Z…）按
 * `rcontact.conRemarkPYFull`（备注拼音全拼）排序——这正是此前「备注改了、人还停在旧字母区」的根因。
 *
 * 写入方式固定为 **方案 B · 微信原生接口**：复用微信 `ContactStorage.q0(username, z3)` 写回，
 * 该函数内部自带：① 重算拼音首字母/全拼（通讯录字母区归位）；② 写回 rcontact；③ 云端同步层。
 * 这与你手动在微信里改备注走的是**同一个函数**，因此分组立即归位且备注会同步到微信云端
 * （换设备/重装仍保留）。
 *
 * 健壮性：若方案 B 在当前微信版本上因内部类名变化而不可用，会自动回退到方案 A（直接写库 +
 * 本地用微信自带 SpellMap 算拼音，仅本机生效、不上传云端），保证功能始终可用。回退对 UI 透明。
 *
 * 好友列表由 [WeDatabaseApi]（ApiFeature）解析；方案 B 的反射锚定在 [WeContactApi] 内完成。
 * 本功能本身不实现 `IResolveDex`。
 */
@Feature(
    name = "批量改备注",
    categories = ["批量操作"],
    description = "多选好友后, 使用正则表达式批量重命名其备注。源文本可选昵称或原备注, 通过正则替换为新备注。"
)
object BatchEditContactRemarks : ClickableFeature() {

    private const val TAG = "BatchEditContactRemarks"

    override val noSwitchWidget = true

    /** Space out writes to avoid hammering the contact DB / triggering host-side warnings. */
    private const val APPLY_INTERVAL_MS = 800L

    private const val SRC_NICKNAME = "nickname"
    private const val SRC_REMARK = "remark"

    /** 写入策略：B=走微信原生 q0（重算拼音+云端同步），A=纯本地写库（仅作为 B 不可用时的内部回退）。 */
    private const val STRATEGY_DIRECT = "A"
    private const val STRATEGY_MODELCONTACT = "B"

    // ---- preferences (so the rule survives across runs) ----
    private var regexPattern by WePrefs.prefOption("batch_remark_regex_pattern", "")
    private var regexReplacement by WePrefs.prefOption("batch_remark_regex_replacement", "")
    private var sourceField by WePrefs.prefOption("batch_remark_source_field", SRC_NICKNAME)

    override fun onClick(context: ComponentActivity) {
        val friends = runCatching { WeDatabaseApi.getFriends() }.getOrDefault(emptyList())
        if (friends.isEmpty()) {
            showToast("无法读取好友列表，请确认微信已登录")
            return
        }

        showComposeDialog(context) {
            ContactsSelector(
                title = "选择要改备注的好友",
                contacts = friends,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast("请选择至少一个好友")
                        return@ContactsSelector
                    }
                    onDismiss()
                    val selected = friends.filter { it.wxId in selectedWxIds }
                    showConfigDialog(context, selected)
                }
            )
        }
    }

    private fun showConfigDialog(context: Context, selected: List<WeContact>) {
        showComposeDialog(context) {
            ConfigDialogContent(context, selected, onDismiss)
        }
    }

    @Composable
    private fun ConfigDialogContent(
        context: Context,
        selected: List<WeContact>,
        onDismiss: () -> Unit
    ) {
        var pattern by remember { mutableStateOf(regexPattern) }
        var replacement by remember { mutableStateOf(regexReplacement) }
        var src by remember { mutableStateOf(sourceField) }

        AlertDialogContent(
                title = { Text("批量改备注（${selected.size} 人）") },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = { src = SRC_NICKNAME },
                            ) { Text(if (src == SRC_NICKNAME) "[源: 原昵称]" else "源: 原昵称") }
                            TextButton(
                                onClick = { src = SRC_REMARK },
                            ) { Text(if (src == SRC_REMARK) "[源: 原备注]" else "源: 原备注") }
                        }

                        Text(
                            "「原昵称」＝好友的微信昵称（未备注时的显示名）；「原备注」＝你此前已为TA设置的备注名。" +
                            "例：昵称含「(客户)」想提取到备注，源选原昵称；若想在已有备注基础上再加工，源选原备注。"
                        )

                        OutlinedTextField(
                            value = pattern,
                            onValueChange = { pattern = it },
                            label = { Text("匹配正则") },
                            placeholder = { Text("例如: .*\\(客户\\)") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = replacement,
                            onValueChange = { replacement = it },
                            label = { Text("替换为") },
                            placeholder = { Text("可用 \$1 引用分组, 例: (VIP)\$1") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Text(
                            "新备注 = 源文本.replace(正则, 替换串)。写入采用微信原生改备注接口, 备注会按拼音首字母归入通讯录对应字母区, 并同步到微信云端。",
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        if (pattern.isBlank()) {
                            showToast("请输入匹配正则")
                            return@Button
                        }
                        runCatching { Regex(pattern) }.onFailure {
                            showToast("正则语法无效: ${it.message}")
                            return@Button
                        }
                        regexPattern = pattern
                        regexReplacement = replacement
                        sourceField = src
                        onDismiss()
                        // 写入方式固定为方案 B（微信原生 q0）；B 不可用时内部自动回退方案 A。
                        applyChanges(context, selected, pattern, replacement, src, STRATEGY_MODELCONTACT)
                    }) { Text("开始修改") }
                }
            )
        }

    /** 一次批量处理的结果汇总，供 UI 渲染与诊断使用。 */
    private data class ProcessResult(
        val total: Int,
        val completed: Int,
        val fallbackCount: Int,
        val lastError: String?,
        val samples: List<String>
    )

    private fun applyChanges(
        context: Context,
        selected: List<WeContact>,
        pattern: String,
        replacement: String,
        src: String,
        strat: String
    ) {
        showComposeDialog(context, directlyDismissable = false) {
            val total = selected.size
            var result by remember { mutableStateOf<ProcessResult?>(null) }
            val completed = remember { mutableIntStateOf(0) }

            LaunchedEffect(Unit) {
                CoroutineScope(Dispatchers.IO).launch {
                    val r = processContacts(selected, pattern, replacement, src, strat)
                    result = r
                }
            }

            val completedValue by completed
            AlertDialogContent(
                title = { Text(if (result != null) "修改完成（含诊断）" else "正在修改备注") },
                text = {
                    DefaultColumn {
                        Text(
                            if (result != null) summaryText(result!!, total)
                            else "正在修改备注, 请稍候...\n已完成: $completedValue/$total"
                        )
                        if (result?.lastError != null) {
                            Text("注意: 部分写入可能失败 (${result?.lastError})")
                        }
                        LinearWavyProgressIndicator(
                            progress = { if (total == 0) 1f else completedValue.toFloat() / total }
                        )
                    }
                },
                confirmButton = if (result != null) {
                    { Button(onDismiss) { Text("关闭") } }
                } else null
            )
        }
    }

    /**
     * 遍历 [selected]，按正则生成新备注并落库。写入方式固定为方案 B（微信原生 q0），
     * 仅在方案 B 不可用时内部回退方案 A（纯本地写库）。返回处理结果汇总。
     */
    private fun processContacts(
        selected: List<WeContact>,
        pattern: String,
        replacement: String,
        src: String,
        strat: String
    ): ProcessResult {
        val regex = Regex(pattern)
        val useModContact = strat == STRATEGY_MODELCONTACT
        val samples = mutableListOf<String>()
        var fallbackCount = 0
        var lastError: String? = null

        selected.forEachIndexed { index, contact ->
            val newRemark = buildNewRemark(contact, regex, replacement, src)
            if (newRemark.isNotBlank()) {
                val applied = applyOneRemark(contact.wxId, newRemark, useModContact)
                if (applied == STRATEGY_DIRECT) fallbackCount += 1
                lastError = applied.lastError
                appendSample(samples, contact, newRemark, applied.strategy)
            } else {
                WeLogger.w(TAG, "empty result for ${contact.wxId}, skipped")
            }
        }
        return ProcessResult(
            total = selected.size,
            completed = selected.size,
            fallbackCount = fallbackCount,
            lastError = lastError,
            samples = samples
        )
    }

    /** 用 [regex] 对 [contact] 的源文本（昵称或原备注）做替换，得到新备注。 */
    private fun buildNewRemark(
        contact: WeContact,
        regex: Regex,
        replacement: String,
        src: String
    ): String {
        val sourceText = if (src == SRC_REMARK) contact.remarkName else contact.nickname
        return runCatching { regex.replace(sourceText, replacement) }.getOrDefault(sourceText)
    }

    /** 处理单个联系人的备注写入：优先方案 B，失败回退方案 A。返回实际采用的策略与错误信息。 */
    private fun applyOneRemark(username: String, newRemark: String, useModContact: Boolean): AppliedStrategy {
        if (!useModContact) {
            val err = runCatching { setRemark(username, newRemark) }
                .exceptionOrNull()?.also { WeLogger.e(TAG, "setRemark failed for $username", it) }
            return AppliedStrategy(STRATEGY_DIRECT, err?.message)
        }
        // 方案 B：走微信原生 q0（重算拼音+本地写入+云端同步）。失败自动回退方案 A，对 UI 透明。
        val ok = runCatching { WeContactApi.setRemarkViaModContact(username, newRemark) }.getOrDefault(false)
        if (ok) return AppliedStrategy(STRATEGY_MODELCONTACT, null)

        val err = runCatching { setRemark(username, newRemark) }
            .exceptionOrNull()?.also { WeLogger.e(TAG, "setRemark (fallback) failed for $username", it) }
        return AppliedStrategy(STRATEGY_DIRECT, err?.message)
    }

    private data class AppliedStrategy(val strategy: String, val lastError: String?)

    /** 诊断采样：把库里真实的 conRemarkPYFull 读出来（微信排序分组真正看的列）。 */
    private fun appendSample(
        samples: MutableList<String>,
        contact: WeContact,
        newRemark: String,
        applied: AppliedStrategy
    ) {
        if (samples.size >= 6) return
        val py = readPyInitial(contact.wxId)
        val tag = if (applied.strategy == STRATEGY_MODELCONTACT) "B" else "A"
        samples.add("[$tag] ${contact.nickname.take(6)} | ${newRemark.take(6)} → conRemarkPYFull=$py")
    }

    /** 处理完成后的结果说明文案（含诊断样本与按策略给出的提示）。 */
    private fun summaryText(result: ProcessResult, total: Int): String = buildString {
        append("已处理 ${result.completed}/$total 位好友的备注。\n")
        append("写入方式：微信原生改备注接口（自动重算拼音并归位字母区，同步云端）。\n")
        if (result.fallbackCount > 0) {
            append("⚠️ ${result.fallbackCount} 人因微信原生接口在当前版本不可用已自动回退到本地写库（仅本机生效，不上传云端）。\n")
        }
        append("\n诊断 — 库里实际写回的 conRemarkPYFull（微信分组排序真看的列）：\n")
        if (result.samples.isEmpty()) append("(无样本)")
        else result.samples.forEach { append("• $it\n") }
        if (result.fallbackCount == 0) {
            append("\n微信会本地重算拼音并刷新列表, 分组应立即归位; 同时该备注会同步到你的微信云端(换设备/重装仍保留)。如未刷新请强制停止微信后重开。")
        } else {
            append("\n本地写库：改完后请彻底杀掉微信进程(强制停止)再打开, 联系人应归入对应字母区。备注只存在本机, 不上传服务器。")
        }
    }

    /**
     * Persist a new remark for [username].
     *
     * 备注文本存于 `rcontact.conRemark`。但微信通讯录右侧字母分组（J/C/Z…）的真正排序键是
     * `conRemarkPYFull`（备注拼音全拼，大写），其优先级高于 `quanPin`（昵称拼音，见微信
     * ContactStorage 查询 SQL：`order by ... conRemarkPYFull ... quanPin ...`）。`conRemarkPYFull`
     * 为空时微信会 fallback 到 `quanPin`（旧昵称拼音），所以只改 `conRemark` 不会让分组挪动——
     * 这正是此前「备注改了、人还停在旧字母区」的根因（之前误写了不参与排序的 `pyInitial`）。
     *
     * 我们走直接 SQL，因此必须自己把 `conRemarkPYFull` / `conRemarkPYShort` 也算对。拼音用微信
     * 自带的 `com.tencent.mm.platformtools.SpellMap`（native 实现，微信进程内一定可用，不依赖 ICU），
     * 逐字符取拼音首字母拼成全拼/缩写。纯本地写库，不上传任何服务器。
     */
    private fun setRemark(username: String, remark: String) {
        val (pyFull, pyShort, pyInitial, quanPin) = computeRemarkPinyin(remark)
        // 必须带 WHERE username = ?，否则会变成 UPDATE 全表 rcontact（灾难）。
        // 占位符个数必须与 args 严格一致：conRemark + (4 个拼音列，仅当拼音可用时) + WHERE 的 username。
        val sql = buildString {
            append("UPDATE rcontact SET conRemark = ?")
            if (pyFull != null) append(", conRemarkPYFull = ?, conRemarkPYShort = ?, pyInitial = ?, quanPin = ?")
            append(" WHERE username = ?")
        }
        val args = buildList<Any> {
            add(remark as Any)
            if (pyFull != null) {
                add(pyFull as Any); add(pyShort as Any); add(pyInitial as Any); add(quanPin as Any)
            }
            add(username as Any)
        }
        WeDatabaseApi.execStatement(sql, args.toTypedArray())
    }

    /**
     * 诊断用：把 [username] 在 `rcontact` 里真实的 `conRemarkPYFull` 读回来（这才是微信排序分组真正看的列），
     * 确认我们写的拼音分组列是否落库。返回 `null` 表示读不到（通常不会发生，除非 username 不在表里）。
     */
    private fun readPyInitial(username: String): String? {
        return runCatching {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT conRemarkPYFull FROM rcontact WHERE username = ?",
                arrayOf<Any>(username)
            )
            cursor.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /**
     * 由备注文本算 `(conRemarkPYFull, conRemarkPYShort, pyInitial, quanPin)`，格式与微信 `rcontact` 一致：
     * - `conRemarkPYFull`：备注拼音全拼大写（如「Z初中同学」→ "ZZHONGCHUTONGXUE"），决定分组排序。
     * - `conRemarkPYShort`：每字拼音首字母连写（如「Z初中同学」→ "ZZCTX"）。
     * - `pyInitial` / `quanPin`：兼容字段，也一并写对。
     *
     * 关键：用微信自带 `SpellMap.a(char)` 算拼音（native，进程内可用），不再依赖微信进程里往往缺失的 ICU 数据。
     * 首字符是拉丁字母时直接采用（如「Z初中同学」→ Z 开头，必进 Z 区），中文才逐字查 SpellMap。
     * 任一字符 SpellMap 查不到（如表情/符号）则保留该字符本身，避免整条失败。
     */
    private fun computeRemarkPinyin(remark: String): SpellResult {
        val name = remark.trim()
        if (name.isEmpty()) return SpellResult(null, null, null, null)
        val sbFull = StringBuilder()   // 全拼
        val sbShort = StringBuilder()  // 每字首字母
        for (ch in name) {
            val upper = ch.uppercaseChar()
            if (upper in 'A'..'Z') {
                // 拉丁字母：全拼与缩写都直接用它
                sbFull.append(upper)
                sbShort.append(upper)
                continue
            }
            if (ch in '0'..'9') {
                sbFull.append(ch)
                sbShort.append(ch)
                continue
            }
            // 中文/其他：查微信 SpellMap 取拼音首字母
            val py = spellMapInitial(ch)
            if (py != null) {
                sbFull.append(py)
                sbShort.append(py.first())
            } else {
                // 查不到（符号/表情等）：保留原字符，不破坏整条
                sbFull.append(ch)
                sbShort.append(ch)
            }
        }
        val pyFull = sbFull.toString().uppercase()
        val pyShort = sbShort.toString().uppercase()
        val pyInitial = pyShort.firstOrNull()?.toString()
        // quanPin 用全拼（与微信 quanPin 字段语义对齐）
        return SpellResult(pyFull, pyShort, pyInitial, pyFull)
    }

    /** 微信 `SpellMap.a(char)` 的反射包装：返回该字符的拼音首字母（大写），查不到返回 `null`。 */
    private fun spellMapInitial(ch: Char): String? {
        return runCatching {
            val clazz = Class.forName("com.tencent.mm.platformtools.SpellMap")
            val method = clazz.getMethod("a", Char::class.javaPrimitiveType)
            val result = method.invoke(null, ch) as? String
            result?.uppercase()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private data class SpellResult(
        val conRemarkPYFull: String?,
        val conRemarkPYShort: String?,
        val pyInitial: String?,
        val quanPin: String?
    )
}
