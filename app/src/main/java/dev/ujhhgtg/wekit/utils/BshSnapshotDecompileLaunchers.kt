package dev.ujhhgtg.wekit.utils

import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.utils.android.showToast

/**
 * Registers the file-picker and save-file launchers that drive the
 * "decompile BeanShell snapshot" flow and returns the launcher that opens the
 * input file picker. Launch it with a MIME filter such as all-types to start.
 *
 * Must be called before the host activity reaches the STARTED state, i.e.
 * during property init or `onCreate`.
 *
 * @param onFinished invoked at every terminal point of the flow (selection
 * cancelled, decompile error, save done or cancelled). Defaults to a no-op;
 * a transient host such as [dev.ujhhgtg.wekit.activity.TransparentActivity]
 * can pass `{ finish() }` to dismiss itself once the flow ends.
 */
fun ComponentActivity.registerBshSnapshotDecompileLaunchers(
    onFinished: () -> Unit = {}
): ActivityResultLauncher<String> {
    var pendingResult: String? = null

    val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            val text = pendingResult
            if (text != null) {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(text.toByteArray())
                    showToast(this, "已保存到 $uri")
                }
            }
            pendingResult = null
        }
        onFinished()
    }

    return registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                Log.i(BuildConfig.TAG, "file $uri chosen")
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    Log.i(BuildConfig.TAG, "decompiling file...")
                    val result = BshSnapshotDecompiler.decompileStream(inputStream).trim()
                    Log.i(BuildConfig.TAG, "decompiled successfully (${result.length} chars)")
                    if (result.isEmpty()) {
                        showToast(this, "错误: 反编译结果为空!")
                        onFinished()
                        return@use
                    }
                    pendingResult = result
                    val inputName = uri.lastPathSegment?.substringBeforeLast(".") ?: "decompiled"
                    saveFileLauncher.launch("$inputName.java")
                }
            } catch (ex: Exception) {
                Log.e(BuildConfig.TAG, "exception thrown", ex)
                showToast(this, "错误: ${ex.message}")
                onFinished()
            }
        } else {
            showToast(this, "文件选择已取消!")
            onFinished()
        }
    }
}
