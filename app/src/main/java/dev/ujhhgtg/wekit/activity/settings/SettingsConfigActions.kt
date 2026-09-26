package dev.ujhhgtg.wekit.activity.settings

import android.content.Context
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.restartHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Shared configuration I/O used by both settings engines. */
object SettingsConfigActions {
    fun export(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val exportLauncher = registerForActivityResult(
                ActivityResultContracts.CreateDocument("application/zip"),
            ) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val temporary = File(platformContext.cacheDir, ".wekit-export-${UUID.randomUUID()}.wekitbackup")
                    val result = runCatching {
                        BackupCoordinator.create(platformContext, temporary)
                        platformContext.contentResolver.openOutputStream(uri, "w")!!.use { output ->
                            temporary.inputStream().use { it.copyTo(output) }
                        }
                    }
                    result.exceptionOrNull()?.let {
                        showToastSuspend(localizedContext().getString(R.string.config_export_failed))
                        WeLogger.e("BackupCoordinator", "failed to export backup", it)
                    }
                    if (result.isSuccess) {
                        showToastSuspend(localizedContext().getString(R.string.config_export_success))
                    }
                    temporary.delete()
                    withContext(Dispatchers.Main) { finish() }
                }
            }
            exportLauncher.launch("wekit_backup.wekitbackup")
        }
    }

    fun importFromDocument(platformContext: Context, localizedContext: () -> Context) {
        TransparentActivity.launch(platformContext) {
            val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) {
                    finish()
                    return@registerForActivityResult
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val temporary = File(platformContext.cacheDir, ".wekit-import-${UUID.randomUUID()}.wekitbackup")
                    val result = runCatching {
                        platformContext.contentResolver.openInputStream(uri)!!.use { input ->
                            temporary.outputStream().use { output -> input.copyTo(output) }
                        }
                        // Preserve a complete rollback point before replacing any live file.
                        BackupCoordinator.createPreImportBackup(platformContext)
                        BackupCoordinator.import(platformContext, temporary)
                    }
                    result.exceptionOrNull()?.let {
                        showToastSuspend(localizedContext().getString(R.string.config_import_failed))
                        WeLogger.e("BackupCoordinator", "failed to import backup", it)
                    }
                    if (result.isSuccess) {
                        showToastSuspend(localizedContext().getString(R.string.config_import_success))
                    }
                    temporary.delete()
                    withContext(Dispatchers.Main) {
                        finish()
                        if (result.isSuccess) BackupCoordinator.restartAfterImport()
                    }
                }
            }
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-wekitbackup"))
        }
    }

    fun clear() {
        BackupCoordinator.clearAll(HostInfo.application)
    }

    fun clearLegacyData() {
        BackupCoordinator.clearLegacyData(HostInfo.application)
    }

    fun clearAndRestart() {
        clear()
        restartHost()
    }
}
