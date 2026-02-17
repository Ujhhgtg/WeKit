package moe.ouom.wekit.ui.creator.center

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.drawable.toDrawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.util.common.ModuleRes
import moe.ouom.wekit.util.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Dex 方法查找对话框
 * 支持并行扫描、双层进度条、异常处理和结果展示
 */
// TODO: port to Compose
class DexFinderDialog(
    context: Context,
    private val appInfo: ApplicationInfo,
    private val outdatedItems: List<IDexFind>
) : Dialog(context, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar_MinWidth) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI 组件
    private lateinit var tvTip: TextView
    private lateinit var tvCurrentTask: TextView
    private lateinit var progressMain: ProgressBar
    private lateinit var tvProgressMain: TextView
    private lateinit var progressSub: ProgressBar
    private lateinit var layoutErrorDetails: LinearLayout
    private lateinit var tvErrorDetails: TextView
    private lateinit var btnCopyError: Button
    private lateinit var btnStart: Button
    private lateinit var btnClose: Button

    // 扫描结果
    private val scanResults = mutableMapOf<String, ScanResult>()
    private var allSuccess = false
    private var taskCounter = 0 // 任务计数器

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置背景透明
        window?.apply {
            setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }

        // 使用 ModuleRes 加载布局
        val rootView = ModuleRes.inflate("dialog_dex_update_layout", null)
        if (rootView == null) {
            setContentView(View(context))
            return
        }

        setContentView(rootView)

        // 点击外部、返回键不消失
        setCanceledOnTouchOutside(false)
        setCancelable(false)

        initViews(rootView)
    }

    @SuppressLint("SetTextI18n")
    private fun initViews(rootView: View) {
        // 使用 ModuleRes 动态查找 ID
        tvTip = rootView.findViewById(ModuleRes.getId("tv_tip", "id"))
        tvCurrentTask = rootView.findViewById(ModuleRes.getId("tv_current_task", "id"))
        progressMain = rootView.findViewById(ModuleRes.getId("progress_main", "id"))
        tvProgressMain = rootView.findViewById(ModuleRes.getId("tv_progress_main", "id"))
        progressSub = rootView.findViewById(ModuleRes.getId("progress_sub", "id"))
        layoutErrorDetails = rootView.findViewById(ModuleRes.getId("layout_error_details", "id"))
        tvErrorDetails = rootView.findViewById(ModuleRes.getId("tv_error_details", "id"))
        btnCopyError = rootView.findViewById(ModuleRes.getId("btn_copy_error", "id"))
        btnStart = rootView.findViewById(ModuleRes.getId("btn_start", "id"))
        btnClose = rootView.findViewById(ModuleRes.getId("btn_close", "id"))

        // 设置按钮点击事件
        btnStart.setOnClickListener {
            startScanning()
        }


        btnClose.setOnClickListener {
            restartApp()
            dismiss()
        }

        btnCopyError.setOnClickListener {
            copyErrorToClipboard()
        }

        // 显示需要更新的项目数量
        tvTip.text =
            "检测到 ${outdatedItems.size} 个功能需要更新 DEX 缓存，点击开始适配后将自动扫描并更新。若直接关闭窗口，相关功能将不会被加载"
    }

    /**
     * 开始扫描
     */
    private fun startScanning() {
        // 隐藏开始按钮
        btnStart.visibility = View.GONE
        btnClose.visibility = View.GONE

        // 显示进度条
        tvCurrentTask.visibility = View.VISIBLE
        progressMain.visibility = View.VISIBLE
        tvProgressMain.visibility = View.VISIBLE
        progressSub.visibility = View.VISIBLE

        // 设置进度条最大值
        progressMain.max = outdatedItems.size

        // 启动并行扫描
        scope.launch {
            try {
                performParallelScanning()
            } catch (e: Exception) {
                WeLogger.e("[DexFinderDialog] Scanning failed", e)
                showError("扫描过程中发生未知错误: ${e.message}")
            }
        }
    }

    /**
     * 执行并行扫描
     */
    private suspend fun performParallelScanning() = withContext(Dispatchers.IO) {
        val dexKit = DexKitBridge.create(appInfo.sourceDir)

        try {
            // 创建进度更新 Channel
            val progressChannel = Channel<ScanProgress>(Channel.UNLIMITED)

            // 启动进度更新协程
            launch(Dispatchers.Main) {
                for (progress in progressChannel) {
                    updateProgress(progress)
                }
            }

            // 并行扫描
            val results = outdatedItems.asFlow()
                .map { item ->
                    async {
                        scanItem(item, dexKit, progressChannel)
                    }
                }
                .buffer(8) // 并发数为 8
                .map { it.await() }
                .toList()

            // 关闭进度 Channel
            progressChannel.close()

            // 处理扫描结果
            withContext(Dispatchers.Main) {
                handleScanResults(results)
            }
        } finally {
            dexKit.close()
        }
    }

    /**
     * 扫描单个 Item
     */
    private suspend fun scanItem(
        item: IDexFind,
        dexKit: DexKitBridge,
        progressChannel: Channel<ScanProgress>
    ): ScanResult {
        // 获取 path
        val path = if (item is BaseHookItem) item.path else item::class.java.simpleName

        return try {
            // 发送开始扫描进度
            progressChannel.send(ScanProgress.Start(path))

            // 执行 Dex 查找,直接获取 descriptors
            val descriptors = item.dexFind(dexKit)

            WeLogger.i(
                "[DexFinderDialog]",
                "Total descriptors collected: ${descriptors.size}, keys: ${descriptors.keys}"
            )

            // 保存缓存
            DexCacheManager.saveCache(item, descriptors)

            // 发送完成进度
            progressChannel.send(ScanProgress.Complete(path))

            ScanResult.Success(path)
        } catch (e: Exception) {
            WeLogger.e("[DexFinderDialog] Failed to scan item: $path", e)

            // 发送失败进度
            progressChannel.send(ScanProgress.Failed(path, e))

            ScanResult.Failed(path, e)
        }
    }

    /**
     * 更新进度
     */
    @SuppressLint("SetTextI18n")
    private fun updateProgress(progress: ScanProgress) {
        when (progress) {
            is ScanProgress.Start -> {
                taskCounter++
                val total = outdatedItems.size
                tvCurrentTask.text = "正在适配: ${progress.path} ($taskCounter/$total)..."
                tvProgressMain.text = "总进度: ${scanResults.size}/$total"
            }

            is ScanProgress.Complete -> {
                scanResults[progress.path] = ScanResult.Success(progress.path)
                val completed = scanResults.size
                val total = outdatedItems.size
                tvCurrentTask.text = "已完成: ${progress.path}"
                progressMain.progress = completed
                tvProgressMain.text = "总进度: $completed/$total"
            }

            is ScanProgress.Failed -> {
                scanResults[progress.path] = ScanResult.Failed(progress.path, progress.error)
                val completed = scanResults.size
                val total = outdatedItems.size
                tvCurrentTask.text = "失败: ${progress.path}"
                progressMain.progress = completed
                tvProgressMain.text = "总进度: $completed/$total"
            }
        }
    }

    /**
     * 处理扫描结果
     */
    @SuppressLint("SetTextI18n")
    private fun handleScanResults(results: List<ScanResult>) {
        // 隐藏进度条
        progressMain.visibility = View.GONE
        tvProgressMain.visibility = View.GONE
        progressSub.visibility = View.GONE
        tvCurrentTask.visibility = View.GONE

        // 检查是否全部成功
        val failedResults = results.filterIsInstance<ScanResult.Failed>()

        if (failedResults.isEmpty()) {
            // 全部成功
            allSuccess = true
            tvTip.text = "适配完成！所有功能已成功更新 DEX 缓存"
            btnClose.visibility = View.VISIBLE
            btnClose.text = "手动重启微信"
        } else {
            // 有失败
            allSuccess = false
            tvTip.text = "适配完成，但有 ${failedResults.size} 个功能失败"
            showErrorDetails(failedResults)
            btnClose.visibility = View.VISIBLE
        }
    }

    /**
     * 显示错误详情
     */
    private fun showErrorDetails(failedResults: List<ScanResult.Failed>) {
        layoutErrorDetails.visibility = View.VISIBLE

        val errorText = buildString {
            failedResults.forEachIndexed { index, result ->
                append("${index + 1}. ${result.path}\n")
                append("   错误: ${result.error.message}\n\n")
            }
        }

        tvErrorDetails.text = errorText
    }

    /**
     * 显示错误
     */
    private fun showError(message: String) {
        tvTip.text = message
        btnClose.visibility = View.VISIBLE
    }

    /**
     * 复制错误信息到剪贴板
     */
    private fun copyErrorToClipboard() {
        val failedResults = scanResults.values.filterIsInstance<ScanResult.Failed>()

        val fullErrorText = buildString {
            append("=== WeKit Dex 扫描错误报告 ===\n\n")
            failedResults.forEachIndexed { index, result ->
                append("${index + 1}. ${result.path}\n")
                append("   错误信息: ${result.error.message}\n")
                append("   堆栈跟踪:\n")

                val sw = StringWriter()
                result.error.printStackTrace(PrintWriter(sw))
                append(sw.toString())
                append("\n\n")
            }
        }

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("WeKit Dex Finder Error", fullErrorText)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(context, "错误信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    /**
     * 重启应用
     */
    private fun restartApp() {
        // TODO
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    /**
     * 扫描进度
     */
    private sealed class ScanProgress {
        data class Start(val path: String) : ScanProgress()
        data class Complete(val path: String) : ScanProgress()
        data class Failed(val path: String, val error: Exception) : ScanProgress()
    }

    /**
     * 扫描结果
     */
    private sealed class ScanResult {
        data class Success(val path: String) : ScanResult()
        data class Failed(val path: String, val error: Exception) : ScanResult()
    }
}
