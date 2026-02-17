package moe.ouom.wekit.hooks.item.script

import android.content.Context
import android.util.Base64
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.customview.customView
import com.afollestad.materialdialogs.list.listItems
import com.google.android.material.button.MaterialButton
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgManager
import moe.ouom.wekit.hooks.sdk.protocol.intf.IWePkgInterceptor
import moe.ouom.wekit.ui.CommonContextWrapper
import moe.ouom.wekit.ui.creator.dialog.BaseSettingsDialog
import moe.ouom.wekit.util.WeProtoData
import moe.ouom.wekit.util.common.Toasts.showToast
import moe.ouom.wekit.util.log.WeLogger
import moe.ouom.wekit.util.script.ScriptEvalManager
import moe.ouom.wekit.util.script.ScriptFileManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 脚本配置管理器Hook项（包含对话框）
 */
@HookItem(
    path = "脚本管理/脚本开关",
    desc = "管理 JavaScript 脚本配置"
)
class ScriptConfigHookItem : BaseClickableFunctionHookItem(), IWePkgInterceptor {

    override fun entry(classLoader: ClassLoader) {
        // 注册拦截器
        WePkgManager.addInterceptor(this)
    }

    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        try {
            // 解析 Protobuf 数据
            val data = WeProtoData()
            data.fromBytes(reqBytes)
            // 转换为 JSON 进行处理
            val json = data.toJSON()
            // 应用脚本修改
            val modifiedJson = ScriptEvalManager.getInstance().executeOnRequest(uri, cgiId, json)
            // 应用修改并转回字节数组
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e("ScriptConfig", e)
        }

        return null
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        try {
            // 解析 Protobuf 数据
            val data = WeProtoData()
            data.fromBytes(respBytes)
            // 转换为 JSON 进行处理
            val json = data.toJSON()
            // 应用脚本修改
            val modifiedJson = ScriptEvalManager.getInstance().executeOnResponse(uri, cgiId, json)
            // 应用修改并转回字节数组
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e("ScriptConfig", e)
        }
        return null
    }

    override fun unload(classLoader: ClassLoader) {
        WePkgManager.removeInterceptor(this)
        super.unload(classLoader)
    }

    override fun onClick(context: Context) {
        val scriptManager = ScriptFileManager.getInstance()
        val jsEvalManager = ScriptEvalManager.getInstance()
        ScriptManagerDialog(context, scriptManager, jsEvalManager).show()
    }

    /**
     * 脚本管理器对话框
     */
    inner class ScriptManagerDialog(
        context: Context,
        private val scriptManager: ScriptFileManager,
        private val scriptEvalManager: ScriptEvalManager
    ) : BaseSettingsDialog(context, "脚本管理器") {

        private val scripts = mutableListOf<ScriptFileManager.ScriptConfig>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        override fun initList() {
            contentContainer.removeAllViews()
            loadScripts()
            renderScriptList()
        }

        private fun loadScripts() {
            scripts.clear()
            scripts.addAll(scriptManager.getAllScripts())
        }

        private fun renderScriptList() {
            if (scripts.isEmpty()) {
                renderEmptyView()
                renderActionButtons()
                return
            }

            scripts.sortBy { it.order }
            scripts.forEachIndexed { index, script ->
                renderScriptItem(index, script)
            }

            renderActionButtons()
        }

        private fun renderEmptyView() {
            val emptyView = TextView(context).apply {
                text = "暂无脚本，点击下方按钮添加"
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(32, 64, 32, 64)
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            }
            contentContainer.addView(emptyView)
        }

        private fun renderScriptItem(index: Int, script: ScriptFileManager.ScriptConfig) {
            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 16, 32, 16)
                setBackgroundResource(android.R.color.transparent)
            }

            // 标题行
            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val tvTitle = TextView(context).apply {
                text = "${index + 1}. ${script.name}"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val cbEnabled = androidx.appcompat.widget.AppCompatCheckBox(context).apply {
                isChecked = script.enabled
                setOnCheckedChangeListener { _, isChecked ->
                    script.enabled = isChecked
                    scriptManager.saveScript(script)
                    showToast(context, if (isChecked) "已启用" else "已禁用")
                }
            }

            titleRow.addView(tvTitle)
            titleRow.addView(cbEnabled)

            // 脚本信息行 (UUID和创建时间)
            val infoRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 4, 0, 0)
            }

            // UUID显示
            val tvUuid = TextView(context).apply {
                text = "ID: ${script.id}..."
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            }

            // 时间信息
            val tvTime = TextView(context).apply {
                val createdTime = dateFormat.format(Date(script.createdTime))
                val modifiedTime = dateFormat.format(Date(script.modifiedTime))
                text = "创建: $createdTime | 修改: $modifiedTime"
                textSize = 10f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
            }

            infoRow.addView(tvUuid)
            infoRow.addView(tvTime)

            // 描述和预览
            if (script.description.isNotEmpty()) {
                val tvDesc = TextView(context).apply {
                    text = "描述: ${script.description}"
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    setPadding(0, 4, 0, 0)
                }
                itemLayout.addView(tvDesc)
            }

            // 操作按钮行
            val buttonRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 8
                }
            }

            val btnCopy = MaterialButton(context).apply {
                text = "复制"
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginEnd = 8
                }
                setOnClickListener {
                    copyFullScriptInfo(script)
                }
            }

            val btnActions = MaterialButton(context).apply {
                text = "操作"
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    marginStart = 8
                }
                setOnClickListener {
                    showActionMenu(index, script)
                }
            }

            buttonRow.addView(btnCopy)
            buttonRow.addView(btnActions)

            itemLayout.addView(titleRow)
            itemLayout.addView(infoRow)
            itemLayout.addView(buttonRow)

            contentContainer.addView(itemLayout)

            if (index < scripts.size - 1) {
                val divider = TextView(context).apply {
                    setBackgroundColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(32, 16, 32, 16)
                    }
                }
                contentContainer.addView(divider)
            }
        }

        /**
         * 显示操作菜单
         */
        private fun showActionMenu(index: Int, script: ScriptFileManager.ScriptConfig) {
            val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
            val options = mutableListOf<String>()
            val isTop = index == 0
            val isBottom = index == scripts.size - 1

            options.add("上移")
            options.add("下移")
            options.add("编辑")
            options.add("删除")
            options.add("测试")

            MaterialDialog(wrappedContext)
                .title(text = "脚本操作")
                .listItems(items = options) { dialog, optionIndex, _ ->
                    when (optionIndex) {
                        0 -> {
                            // 上移
                            if (isTop) {
                                showToast(context, "已在顶部，无法上移")
                                return@listItems
                            }
                            moveScriptUp(index)
                        }

                        1 -> {
                            // 下移
                            if (isBottom) {
                                showToast(context, "已在底部，无法下移")
                                return@listItems
                            }
                            moveScriptDown(index)
                        }

                        2 -> showEditDialog(index)
                        3 -> confirmDeleteScript(index)
                        4 -> showTestDialog(index)
                    }

                    dialog.dismiss()
                }
                .negativeButton(text = "取消")
                .show()
        }

        /**
         * 复制完整的脚本信息
         */
        private fun copyFullScriptInfo(script: ScriptFileManager.ScriptConfig) {
            val contentEncoded =
                Base64.encodeToString(script.content.toByteArray(Charsets.UTF_8), Base64.DEFAULT)
            val jsonObject = JSONObject().apply {
                put("name", script.name)
                put("id", script.id)
                put("description", script.description)
                put("content", contentEncoded)
            }

            copyToClipboard("脚本JSON", jsonObject.toString(2))
            showToast(context, "已复制脚本JSON格式")
        }

        private fun renderActionButtons() {
            val buttonLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 32)
            }

            val btnAdd = MaterialButton(context).apply {
                text = "添加脚本"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 16
                }
                setOnClickListener {
                    showAddDialog()
                }
            }

            val btnImport = MaterialButton(context).apply {
                text = "导入"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    showImportOptions()
                }
            }

            buttonLayout.addView(btnAdd)
            buttonLayout.addView(btnImport)
            contentContainer.addView(buttonLayout)
        }

        private fun showAddDialog() {
            showEditDialog(-1)
        }

        private fun showEditDialog(index: Int) {
            val isNew = index == -1
            val script = if (isNew) {
                val newId = UUID.randomUUID().toString()
                ScriptFileManager.ScriptConfig(
                    id = newId,
                    name = "",
                    content = "",
                    order = scripts.size
                )
            } else {
                scripts.getOrNull(index) ?: return
            }

            val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

            val dialogView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
            }

            // UUID显示
            val tvUuidLabel = TextView(context).apply {
                text = "脚本ID: ${script.id}"
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
                setPadding(0, 0, 0, 8)
            }

            val tvNameLabel = TextView(context).apply {
                text = "脚本名称:"
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }

            val etName = AppCompatEditText(context).apply {
                hint = "输入脚本名称"
                setText(script.name)
                textSize = 14f
            }

            val tvDescLabel = TextView(context).apply {
                text = "描述（可选）:"
                textSize = 14f
                setPadding(0, 16, 0, 8)
            }

            val etDesc = AppCompatEditText(context).apply {
                hint = "输入脚本描述"
                setText(script.description)
                textSize = 14f
            }

            val tvContentLabel = TextView(context).apply {
                text = "脚本内容 (JavaScript):"
                textSize = 14f
                setPadding(0, 16, 0, 8)
            }

            val etContent = AppCompatEditText(context).apply {
                hint = "输入JavaScript代码..."
                setText(script.content)
                minLines = 10
                maxLines = 20
                gravity = Gravity.START or Gravity.TOP
            }

            dialogView.addView(tvUuidLabel)
            dialogView.addView(tvNameLabel)
            dialogView.addView(etName)
            dialogView.addView(tvDescLabel)
            dialogView.addView(etDesc)
            dialogView.addView(tvContentLabel)
            dialogView.addView(etContent)

            MaterialDialog(wrappedContext)
                .customView(view = dialogView)
                .title(text = if (isNew) "添加脚本" else "编辑脚本")
                .positiveButton(text = "保存") {
                    val name = etName.text.toString().trim()
                    val description = etDesc.text.toString().trim()
                    val content = etContent.text.toString().trim()

                    if (name.isEmpty()) {
                        showToast(context, "请输入脚本名称")
                        return@positiveButton
                    }

                    if (content.isEmpty()) {
                        showToast(context, "请输入脚本内容")
                        return@positiveButton
                    }

                    saveScript(script, name, description, content, isNew)
                }
                .neutralButton(text = "复制脚本内容") {
                    val content = etContent.text.toString().trim()
                    if (content.isNotEmpty()) {
                        copyToClipboard("脚本内容", content)
                        showToast(context, "已复制脚本内容")
                    } else {
                        showToast(context, "脚本内容为空")
                    }
                }
                .negativeButton(text = "取消")
                .show()
        }

        /**
         * 显示测试对话框
         */
        private fun showTestDialog(index: Int) {
            val script = scripts.getOrNull(index) ?: return
            val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

            val dialogView = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
            }

            val tvScriptName = TextView(context).apply {
                text = "测试脚本: ${script.name}"
                textSize = 16f
                setPadding(0, 0, 0, 16)
            }

            val tvInputLabel = TextView(context).apply {
                text = "输入要执行的JavaScript代码:"
                textSize = 14f
                setPadding(0, 0, 0, 8)
            }

            val etInput = AppCompatEditText(context).apply {
                hint = "例如: wekit.log(onRequest({uri: 'uri',cgiId: 0,jsonData: {}}));"
                textSize = 14f
                minLines = 5
                maxLines = 10
                gravity = Gravity.START or Gravity.TOP
            }

            dialogView.addView(tvScriptName)
            dialogView.addView(tvInputLabel)
            dialogView.addView(etInput)

            MaterialDialog(wrappedContext)
                .customView(view = dialogView)
                .title(text = "测试脚本执行")
                .positiveButton(text = "执行") {
                    val jsCode = etInput.text.toString().trim()
                    if (jsCode.isNotEmpty()) {
                        executeTestScript(script, jsCode)
                    } else {
                        showToast(context, "请输入要执行的JavaScript代码")
                    }
                }
                .negativeButton(text = "取消")
                .show()
        }

        /**
         * 执行测试脚本
         */
        private fun executeTestScript(script: ScriptFileManager.ScriptConfig, jsCode: String) {
            val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

            val result =
                scriptEvalManager.testExecuteCode(script.content, jsCode, "${script.name}+测试脚本")
            MaterialDialog(wrappedContext)
                .title(text = "执行结果")
                .message(text = result ?: "执行失败或无返回值")
                .positiveButton(text = "复制结果") {
                    copyToClipboard("执行结果", result ?: "执行失败")
                }
                .negativeButton(text = "关闭")
                .show()
        }

        private fun saveScript(
            script: ScriptFileManager.ScriptConfig,
            name: String,
            description: String,
            content: String,
            isNew: Boolean
        ) {
            // 检测脚本是否正常
            val testResult = scriptEvalManager.testScriptMethods(content)
            if (!testResult.isPassed()) {
                val errorMsg = testResult.getSummary()
                showToast(context, "脚本验证失败，请修正后保存")

                // 显示详细错误信息
                val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
                MaterialDialog(wrappedContext)
                    .title(text = "脚本验证失败")
                    .message(text = errorMsg)
                    .positiveButton(text = "返回编辑") {
                        showEditDialog(if (isNew) -1 else scripts.indexOf(script))
                    }
                    .negativeButton(text = "取消")
                    .show()
                return
            }

            script.name = name
            script.description = description
            script.content = content
            script.modifiedTime = System.currentTimeMillis()

            if (isNew) {
                script.createdTime = System.currentTimeMillis()
                scripts.add(script)
                scriptManager.saveScript(script)
                showToast(context, "脚本已添加")
            } else {
                scriptManager.saveScript(script)
                showToast(context, "脚本已更新")
            }

            contentContainer.removeAllViews()
            renderScriptList()
        }

        private fun showImportOptions() {
            val wrappedContext = CommonContextWrapper.createAppCompatContext(context)
            val options = listOf("从剪贴板导入", "导入示例")

            MaterialDialog(wrappedContext)
                .title(text = "导入脚本")
                .listItems(items = options) { dialog, optionIndex, _ ->
                    when (optionIndex) {
                        0 -> importFromClipboard()
                        1 -> importExample()
                    }
                    dialog.dismiss()
                }
                .negativeButton(text = "取消")
                .show()
        }

        private fun importFromClipboard() {
            try {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clipData = clipboard?.primaryClip

                if (clipData != null && clipData.itemCount > 0) {
                    val clipItem = clipData.getItemAt(0)
                    val text = clipItem.text?.toString()

                    if (!text.isNullOrBlank()) {
                        // 尝试解析为JSON格式
                        val jsonObject = JSONObject(text)

                        // 验证必需字段
                        if (jsonObject.has("name") && jsonObject.has("id") && jsonObject.has("description") && jsonObject.has(
                                "content"
                            )
                        ) {
                            val newName = jsonObject.optString("name", "从剪贴板导入")
                            val newId = jsonObject.optString("id", UUID.randomUUID().toString())
                            val newDescription = jsonObject.optString("description", "")
                            val contentEncoded = jsonObject.optString("content", "")

                            // 解码内容
                            val contentBytes = Base64.decode(contentEncoded, Base64.NO_WRAP)
                            val newContent = String(contentBytes, Charsets.UTF_8)

                            // 检查是否已存在相同ID的脚本
                            val existingScript = scripts.find { it.id == newId }
                            if (existingScript != null) {
                                // 覆盖现有脚本
                                existingScript.name = newName
                                existingScript.content = newContent
                                existingScript.description = newDescription
                                existingScript.modifiedTime = System.currentTimeMillis()

                                scriptManager.saveScript(existingScript)
                                showToast(context, "已覆盖相同ID的脚本")

                                contentContainer.removeAllViews()
                                renderScriptList()
                                return
                            }

                            val newScript = ScriptFileManager.ScriptConfig(
                                id = newId,
                                name = newName,
                                content = newContent,
                                description = newDescription,
                                order = scripts.size
                            )

                            scriptManager.saveScript(newScript)
                            scripts.add(newScript)

                            contentContainer.removeAllViews()
                            renderScriptList()
                            showToast(context, "已从剪贴板导入脚本")
                        } else {
                            showToast(
                                context,
                                "剪贴板内容格式不正确，缺少必要字段(name, id, description, content)"
                            )
                        }
                    } else {
                        showToast(context, "剪贴板内容为空")
                    }
                } else {
                    showToast(context, "剪贴板无内容")
                }
            } catch (e: Exception) {
                WeLogger.e("从剪贴板导入失败: ${e.message}")
                showToast(context, "导入失败，请确保剪贴板内容为有效的JSON格式")
            }
        }

        private fun importExample() {
            val exampleScripts = listOf(
                ScriptFileManager.ScriptConfig(
                    id = UUID.randomUUID().toString(),
                    name = "打印示例",
                    content = """
                        // 这是一个简单的示例脚本 将打印参数
                        function onRequest(data) {
                            const {uri,cgiId,jsonData} = data;
                            wekit.log('拦截请求:', uri, cgiId, JSON.stringify(jsonData));
                            return jsonData;
                        }
                        
                        function onResponse(data) {
                            const {uri,cgiId,jsonData} = data;
                            wekit.log('拦截响应:', uri, cgiId, JSON.stringify(jsonData));
                            return jsonData;
                        }
                    """.trimIndent(),
                    description = "最简单的示例脚本，包含onRequest和onResponse方法",
                    order = scripts.size
                )
            )

            exampleScripts.forEach { script ->
                scriptManager.saveScript(script)
                scripts.add(script)
            }

            contentContainer.removeAllViews()
            renderScriptList()
            showToast(context, "已导入示例脚本")
        }

        private fun confirmDeleteScript(index: Int) {
            val script = scripts.getOrNull(index) ?: return
            val wrappedContext = CommonContextWrapper.createAppCompatContext(context)

            MaterialDialog(wrappedContext)
                .title(text = "确认删除")
                .message(text = "确定要删除脚本「${script.name}」吗？\nID: ${script.id}")
                .positiveButton(text = "删除") {
                    deleteScript(index)
                }
                .negativeButton(text = "取消")
                .show()
        }

        private fun deleteScript(index: Int) {
            val script = scripts.getOrNull(index) ?: return

            if (scriptManager.deleteScript(script.id)) {
                scripts.removeAt(index)

                scripts.forEachIndexed { i, s ->
                    s.order = i
                    scriptManager.saveScript(s)
                }

                contentContainer.removeAllViews()
                renderScriptList()
                showToast(context, "脚本已删除")
            } else {
                showToast(context, "删除失败")
            }
        }

        private fun moveScriptUp(index: Int) {
            if (index > 0) {
                val temp = scripts[index]
                scripts[index] = scripts[index - 1]
                scripts[index - 1] = temp

                scripts.forEachIndexed { i, s ->
                    s.order = i
                    scriptManager.saveScript(s)
                }

                contentContainer.removeAllViews()
                renderScriptList()
            }
        }

        private fun moveScriptDown(index: Int) {
            if (index < scripts.size - 1) {
                val temp = scripts[index]
                scripts[index] = scripts[index + 1]
                scripts[index + 1] = temp

                scripts.forEachIndexed { i, s ->
                    s.order = i
                    scriptManager.saveScript(s)
                }

                contentContainer.removeAllViews()
                renderScriptList()
            }
        }

        private fun copyToClipboard(label: String, text: String) {
            try {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText(label, text)
                clipboard?.setPrimaryClip(clip)
            } catch (e: Exception) {
                WeLogger.e("复制失败: ${e.message}")
            }
        }

    }

}