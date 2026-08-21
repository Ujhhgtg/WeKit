package dev.ujhhgtg.wekit.features.items.contacts

import android.content.Context
import android.icu.text.Transliterator
import android.os.Build
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
 * 流程：多选好友 → 配置正则（源字段 + 匹配正则 + 替换串）→ 后台逐个按正则生成新备注并落库。
 * 备注在微信中存储于 `rcontact.conRemark`，WeKit 读取备注也是直接查该字段，故落库采用
 * 直接 SQL 更新，与项目既有的数据库访问风格一致（如同 [BatchAddLabel] 走 [WeDatabaseApi]）。
 *
 * 本功能不解析任何微信类，因此不实现 `IResolveDex`——好友列表与数据库写入均由
 * [WeDatabaseApi]（ApiFeature）自身解析，避免无谓的 DexKit 匹配。
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
                            ) { Text(if (src == SRC_NICKNAME) "[源: 昵称]" else "源: 昵称") }
                            TextButton(
                                onClick = { src = SRC_REMARK },
                            ) { Text(if (src == SRC_REMARK) "[源: 原备注]" else "源: 原备注") }
                        }

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
                        onDismiss()
                        applyChanges(context, selected, pattern, replacement, src)
                    }) { Text("开始修改") }
                }
            )
        }

    private fun applyChanges(
        context: Context,
        selected: List<WeContact>,
        pattern: String,
        replacement: String,
        src: String
    ) {
        showComposeDialog(context, directlyDismissable = false) {
            val completed = remember { mutableIntStateOf(0) }
            var done by remember { mutableStateOf(false) }
            val total = selected.size
            var lastError by remember { mutableStateOf<String?>(null) }
            val samples = remember { mutableStateListOf<String>() }

            LaunchedEffect(Unit) {
                CoroutineScope(Dispatchers.IO).launch {
                    val regex = Regex(pattern)
                    selected.forEachIndexed { index, contact ->
                        val sourceText = if (src == SRC_REMARK) contact.remarkName else contact.nickname
                        val newRemark = runCatching { regex.replace(sourceText, replacement) }
                            .getOrDefault(sourceText)
                        if (newRemark.isNotBlank()) {
                            runCatching { setRemark(contact.wxId, newRemark) }.onFailure {
                                WeLogger.e(TAG, "setRemark failed for ${contact.wxId}", it)
                                lastError = it.message
                            }
                            // 诊断：直接把库里真实写回的 pyInitial 读出来，用于定位「分组不动」根因。
                            val py = readPyInitial(contact.wxId)
                            if (samples.size < 6) {
                                samples.add("${contact.nickname.take(6)} | ${newRemark.take(6)} → pyInitial=$py")
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
            AlertDialogContent(
                title = { Text(if (done) "修改完成（含诊断）" else "正在修改备注") },
                text = {
                    DefaultColumn {
                        Text(
                            if (done) {
                                buildString {
                                    append("已处理 $completedValue/$total 位好友的备注。\n")
                                    append("诊断 — 库里实际写回的 pyInitial：\n")
                                    if (samples.isEmpty()) append("(无样本)")
                                    else samples.forEach { append("• $it\n") }
                                    append("\n若 pyInitial 以 Z 开头、而通讯录仍停在旧字母区，说明微信不读取直接写入的列（需改用微信自身联系人更新接口）。")
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
     * 备注存储于 `rcontact.conRemark`。但微信通讯录的右侧字母分组（J/C/Z…）是按 `pyInitial`
     * 列的拼音首字母排序的，而 `quanPin` 是全拼——这俩列微信只在「走自家 ContactStorage 更新」
     * 时才重算。我们走直接 SQL，所以必须自己把 `pyInitial`/`quanPin` 也写对，否则会出现
     * 「备注文字改了、人却还停在旧字母分组下」的现象。
     *
     * 拼音用与联系人多选列表相同的 ICU `Transliterator`（Han-Latin; Any-Latin; Latin-ASCII），
     * API 29+ 才可用；低于该版本时退化为「只改 conRemark」，分组字母保持原样（备注文本仍正确）。
     */
    private fun setRemark(username: String, remark: String) {
        val pinyin = computePinyin(remark)
        val (sql, args) = if (pinyin != null) {
            val (pyInitial, quanPin) = pinyin
            "UPDATE rcontact SET conRemark = ?, pyInitial = ?, quanPin = ? WHERE username = ?" to
                arrayOf<Any>(remark, pyInitial, quanPin, username)
        } else {
            "UPDATE rcontact SET conRemark = ? WHERE username = ?" to
                arrayOf<Any>(remark, username)
        }
        WeDatabaseApi.execStatement(sql, args)
    }

    /**
     * 诊断用：把 [username] 在 `rcontact` 里真实的 `pyInitial` 读回来，确认我们写的拼音分组列是否落库。
     * 返回 `null` 表示读不到（通常不会发生，除非 username 不在表里）。
     */
    private fun readPyInitial(username: String): String? {
        return runCatching {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT pyInitial FROM rcontact WHERE username = ?",
                arrayOf<Any>(username)
            )
            cursor.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    /**
     * 由备注文本算 `(pyInitial, quanPin)`，格式与微信 `rcontact` 一致：
     * - `pyInitial`：决定所在字母分组区（如「初中同学」→ "CZTX"，「Z初中同学」→ "Z…"）。
     * - `quanPin`：去非字母后的全拼大写。
     *
     * 关键坑：微信进程里 `Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")` 往往抛异常
     * （ICU 拼音数据未加载），旧实现因此整体返回 `null`、只改 `conRemark`，导致分组字母卡在旧值。
     * 这里改为：**首字符是拉丁字母时直接取它当分组字母，完全不依赖 ICU**（如「Z初中同学」→ 必进 Z 区）；
     * 中文首字才走 ICU，ICU 不可用则退化为 `null`（保留原分组，绝不误分组）。逻辑对齐
     * `ContactSelectors.initialOf`，它在微信进程里对拉丁首字正是直接返回该字母。
     */
    private fun computePinyin(remark: String): Pair<String, String>? {
        val name = remark.trim()
        if (name.isEmpty()) return null
        val firstUpper = name.first().uppercaseChar()
        // 首字符是拉丁字母（如 "Z初中同学"）：分组字母直接取它，无需 ICU，最稳。
        if (firstUpper in 'A'..'Z') {
            val (pyInitial, quanPin) = icuPinyin(name) ?: run {
                val latin = name.filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase()
                val fallback = latin.ifEmpty { firstUpper.toString() }
                return firstUpper.toString() to fallback
            }
            return pyInitial to quanPin
        }
        // 首字符是中文：必须靠 ICU 转出拼音首字母。
        return icuPinyin(name)
    }

    /** 仅当 ICU `Transliterator` 可用时返回 `(pyInitial, quanPin)`，否则 `null`。 */
    private fun icuPinyin(name: String): Pair<String, String>? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val transliterator = runCatching {
            Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")
        }.getOrNull() ?: return null
        val raw = synchronized(transliterator) { transliterator.transliterate(name) }
        val letters = raw.filter { it in 'a'..'z' || it in 'A'..'Z' }.uppercase()
        if (letters.isEmpty()) return null
        val pyInitial = if (raw.contains(' ')) {
            raw.split(' ').filter { it.isNotBlank() }
                .mapNotNull { syl -> syl.firstOrNull { c -> c in 'a'..'z' || c in 'A'..'Z' }?.uppercase() }
                .joinToString("")
        } else {
            letters.first().toString()
        }
        if (pyInitial.isEmpty()) return null
        return pyInitial to letters
    }
}
