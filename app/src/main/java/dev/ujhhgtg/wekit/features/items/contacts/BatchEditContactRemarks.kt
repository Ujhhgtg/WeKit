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
 * 流程：多选好友 → 配置（源字段 + 匹配正则 + 替换串 + 写入方式）→ 后台逐个按正则生成新备注并落库。
 * 备注在微信中存储于 `rcontact.conRemark`，微信通讯录右侧字母分组（J/C/Z…）按
 * `rcontact.conRemarkPYFull`（备注拼音全拼）排序——这正是此前「备注改了、人还停在旧字母区」的根因。
 *
 * 本功能提供两种写入方式，用户在配置对话框里二选一（持久化到 [WePrefs]，下次默认沿用）：
 * - **方案 A · 直接写库**：用微信自带 `SpellMap` 在本地算出 `conRemarkPYFull` 等拼音列后直接
 *   `UPDATE rcontact`。零微信版本兼容成本；但微信进程内通讯录缓存可能不立即重算，需杀进程才刷新。
 * - **方案 B · 微信 modContact 接口**：复用微信 `ContactStorageLogic.toModContactOplog` 构造 oplog
 *   (cmd 2 / funcId 681) 发出，微信自己重算拼音、写回 `conRemarkPYFull` 并刷新列表，分组立即归位。
 *   代价是需 DexKit 锚定微信内部方法（`[WeContactApi.setRemarkViaModContact]`）；若锚定失败会
 *   自动回退方案 A。该 oplog 走微信进程内同步通道，拼音计算与列表刷新纯本地完成，无云控拼音风险。
 *
 * 好友列表与方案 A 的数据库写入由 [WeDatabaseApi]（ApiFeature）自身解析；方案 B 的 DexKit 锚定在
 * [WeContactApi] 内完成。本功能本身不实现 `IResolveDex`。
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

    /** 写入策略：A=直接写库（SpellMap 算拼音），B=走微信 modContact oplog（微信自己重算拼音并刷新列表）。 */
    private const val STRATEGY_DIRECT = "A"
    private const val STRATEGY_MODELCONTACT = "B"

    // ---- preferences (so the rule survives across runs) ----
    private var regexPattern by WePrefs.prefOption("batch_remark_regex_pattern", "")
    private var regexReplacement by WePrefs.prefOption("batch_remark_regex_replacement", "")
    private var sourceField by WePrefs.prefOption("batch_remark_source_field", SRC_NICKNAME)
    private var strategy by WePrefs.prefOption("batch_remark_strategy", STRATEGY_MODELCONTACT)

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
        var strat by remember { mutableStateOf(strategy) }

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
                            ) { Text(if (src == SRC_NICKNAME) "[源: 昵称]" else "源: 昵称") }
                            TextButton(
                                onClick = { src = SRC_REMARK },
                            ) { Text(if (src == SRC_REMARK) "[源: 原备注]" else "源: 原备注") }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = { strat = STRATEGY_DIRECT },
                            ) { Text(if (strat == STRATEGY_DIRECT) "[A·纯本地]" else "A·纯本地") }
                            TextButton(
                                onClick = { strat = STRATEGY_MODELCONTACT },
                            ) { Text(if (strat == STRATEGY_MODELCONTACT) "[B·同步云端]" else "B·同步云端") }
                        }

                        Text(
                            "写入方式：A·纯本地＝直接改本机 rcontact（备注+拼音），零兼容成本，但分组可能需杀进程才刷新，且不会上传服务器；" +
                            "B·同步云端＝走微信自身 modContact 接口，分组立即归位，微信会把它同步到你的云端备注（换设备/重装仍在），" +
                            "代价是需 DexKit 锚定微信内部方法（锚不到会自动回退到 A）。"
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
                            "新备注 = 源文本.replace(正则, 替换串)。",
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
                        strategy = strat
                        onDismiss()
                        applyChanges(context, selected, pattern, replacement, src, strat)
                    }) { Text("开始修改") }
                }
            )
        }

    private fun applyChanges(
        context: Context,
        selected: List<WeContact>,
        pattern: String,
        replacement: String,
        src: String,
        strat: String
    ) {
        showComposeDialog(context, directlyDismissable = false) {
            val completed = remember { mutableIntStateOf(0) }
            var done by remember { mutableStateOf(false) }
            val total = selected.size
            var lastError by remember { mutableStateOf<String?>(null) }
            val fallbackCount = remember { mutableIntStateOf(0) }
            val samples = remember { mutableStateListOf<String>() }

            LaunchedEffect(Unit) {
                CoroutineScope(Dispatchers.IO).launch {
                    val regex = Regex(pattern)
                    val useModContact = strat == STRATEGY_MODELCONTACT
                    selected.forEachIndexed { index, contact ->
                        val sourceText = if (src == SRC_REMARK) contact.remarkName else contact.nickname
                        val newRemark = runCatching { regex.replace(sourceText, replacement) }
                            .getOrDefault(sourceText)
                        if (newRemark.isNotBlank()) {
                            var appliedStrategy = strat
                            if (useModContact) {
                                // 方案 B：走微信 modContact oplog。失败（DexKit 锚定不到等）自动回退方案 A。
                                val ok = runCatching { WeContactApi.setRemarkViaModContact(contact.wxId, newRemark) }
                                    .getOrDefault(false)
                                if (!ok) {
                                    fallbackCount.intValue += 1
                                    appliedStrategy = STRATEGY_DIRECT
                                    runCatching { setRemark(contact.wxId, newRemark) }.onFailure {
                                        WeLogger.e(TAG, "setRemark (fallback) failed for ${contact.wxId}", it)
                                        lastError = it.message
                                    }
                                }
                            } else {
                                // 方案 A：直接写库（SpellMap 算拼音）。
                                runCatching { setRemark(contact.wxId, newRemark) }.onFailure {
                                    WeLogger.e(TAG, "setRemark failed for ${contact.wxId}", it)
                                    lastError = it.message
                                }
                            }
                            // 诊断：把库里真实的 conRemarkPYFull 读出来（微信排序分组真正看的列）。
                            val py = readPyInitial(contact.wxId)
                            if (samples.size < 6) {
                                val tag = if (appliedStrategy == STRATEGY_MODELCONTACT) "B" else "A"
                                samples.add("[$tag] ${contact.nickname.take(6)} | ${newRemark.take(6)} → conRemarkPYFull=$py")
                            }
                        } else {
                            WeLogger.w(TAG, "empty result for ${contact.wxId}, skipped")
                        }
                        completed.intValue = index + 1
                        if (index < total - 1) delay(APPLY_INTERVAL_MS.milliseconds)
                    }
                    done = true
                }
            }

            val completedValue by completed
            val fallbackValue = fallbackCount.intValue
            AlertDialogContent(
                title = { Text(if (done) "修改完成（含诊断）" else "正在修改备注") },
                text = {
                    DefaultColumn {
                        Text(
                            if (done) {
                                buildString {
                                    append("已处理 $completedValue/$total 位好友的备注。\n")
                                    append("写入方式：${if (strat == STRATEGY_MODELCONTACT) "B · 同步云端(modContact)" else "A · 纯本地写库"}\n")
                                    if (strat == STRATEGY_MODELCONTACT && fallbackValue > 0) {
                                        append("⚠️ $fallbackValue 人因微信接口不可用已自动回退到方案 A（纯本地写库，不会上传云端）。\n")
                                    }
                                    append("\n诊断 — 库里实际写回的 conRemarkPYFull（微信分组排序真看的列）：\n")
                                    if (samples.isEmpty()) append("(无样本)")
                                    else samples.forEach { append("• $it\n") }
                                    if (strat == STRATEGY_DIRECT) {
                                        append("\n方案 A·纯本地：改完后请彻底杀掉微信进程(强制停止)再打开, 联系人应归入对应字母区。备注只存在本机, 不上传服务器。若 conRemarkPYFull 以 Z 开头却仍在旧区, 才需进一步排查微信缓存。")
                                    } else {
                                        append("\n方案 B·同步云端：微信会本地重算拼音并刷新列表, 分组应立即归位; 同时该备注会同步到你的微信云端(换设备/重装仍保留)。如未刷新请强制停止微信后重开。")
                                    }
                                }
                            } else {
                                "正在修改备注, 请稍候...\n已完成: $completedValue/$total"
                            }
                        )
                        if (lastError != null) {
                            Text("注意: 部分写入可能失败 ($lastError)")
                        }
                        LinearWavyProgressIndicator(
                            progress = { if (total == 0) 1f else completedValue.toFloat() / total }
                        )
                    }
                },
                confirmButton = if (done) {
                    { Button(onDismiss) { Text("关闭") } }
                } else null
            )
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
        val sql = buildString {
            append("UPDATE rcontact SET conRemark = ?")
            if (pyFull != null) append(", conRemarkPYFull = ?, conRemarkPYShort = ?, pyInitial = ?, quanPin = ?")
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
