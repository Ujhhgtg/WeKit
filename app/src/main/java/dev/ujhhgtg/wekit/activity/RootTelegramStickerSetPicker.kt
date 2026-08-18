package dev.ujhhgtg.wekit.activity

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.topjohnwu.superuser.Shell
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramInstalledStickerSet
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.TelegramStickerDatabase
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal data class RootTelegramInstance(
    val packageName: String,
    val databasePath: String? = null,
)

internal object RootTelegramStickerSetRepository {
    private const val TAG = "TelegramDatabaseRoot"

    private val knownPackages = setOf(
        "org.telegram.messenger",
        "org.telegram.messenger.beta",
        "org.telegram.plus",
        "nekox.messenger",
        "com.jasonkhew96.pigeongram",
        "app.nicegram",
        "xyz.nextalone.nagram",
        "xyz.nextalone.nnngram",
        "com.xtaolabs.pagergram",
        "org.telegram.messenger.web",
        "com.cool2645.nekolite",
        "com.iMe.android",
        "org.telegram.BifToGram",
        "ua.itaysonlab.messenger",
        "org.forkclient.messenger.beta",
        "org.aka.messenger",
        "ellipi.messenger",
        "me.luvletter.nekox",
        "org.nift4.catox",
        "icu.ketal.yunigram",
        "icu.ketal.yunigram.lspatch",
        "icu.ketal.yunigram.beta",
        "icu.ketal.yunigram.lspatch.beta",
        "org.forkgram.messenger",
        "com.blxueya.gugugram",
        "com.radolyn.ayugram",
        "com.blxueya.gugugramx",
        "com.evildayz.code.telegraher",
        "com.exteragram.messenger",
    )

    @SuppressLint("SdCardPath")
    fun discoverInstances(
        context: Context,
        androidUserId: Int,
    ): Result<List<RootTelegramInstance>> = runCatching {
        require(Shell.isAppGrantedRoot() == true) {
            context.moduleAppString(R.string.telegram_root_permission_required)
        }
        val userDataDir = "/data/user/$androidUserId"
        val appDirectoryGlob = "${userDataDir.shellLiteral()}/*"
        val scan = executeRootCommand(
            $$"""
            for app_dir in $$appDirectoryGlob; do
                selected_account=0
                config="${app_dir}/shared_prefs/userconfing.xml"
                if [ -f "${config}" ]; then
                    configured_account="$(sed -n 's/.*name="selectedAccount"[^>]*value="\([0-9][0-9]*\)".*/\1/p' "${config}" | head -n 1)"
                    [ -n "${configured_account}" ] && selected_account="${configured_account}"
                fi
                if [ "${selected_account}" = 0 ]; then
                    database="${app_dir}/files/cache4.db"
                else
                    database="${app_dir}/files/account${selected_account}/cache4.db"
                fi
                [ -f "${database}" ] && printf '%s\n' "${database}"
            done
            exit 0
            """.trimIndent(),
        )
        require(scan.isSuccess) {
            context.moduleAppString(
                R.string.telegram_database_scan_failed,
                scan.output.joinToString("; ").take(300).ifBlank {
                    context.moduleAppString(R.string.telegram_root_command_failed)
                },
            )
        }
        val discoveredPaths = scan.output.asSequence()
            .map(String::trim)
            .filter { it.startsWith("/data/") && it.endsWith("/cache4.db") }
            .distinct()
            .toList()
        val discoveredPackages = discoveredPaths.mapNotNull(::packageNameFromDatabasePath).distinct()
        val instances = discoveredPaths.mapNotNull { path ->
            val packageName = packageNameFromDatabasePath(path) ?: return@mapNotNull null
            path.takeIf {
                packageName.contains("gram", ignoreCase = true) ||
                        knownPackages.any { it.equals(packageName, ignoreCase = true) }
            }?.let { RootTelegramInstance(packageName, it) }
        }
            .sortedBy(RootTelegramInstance::packageName)
        WeLogger.i(
            TAG,
            "discovery user=$androidUserId cacheDatabases=${discoveredPaths.size} " +
                    "packages=${discoveredPackages.joinToString()} matched=${instances.map { it.packageName }}",
        )
        require(instances.isNotEmpty()) {
            if (discoveredPaths.isEmpty()) {
                context.moduleAppString(R.string.telegram_database_not_found)
            } else {
                context.moduleAppString(
                    R.string.telegram_database_app_unrecognized,
                    discoveredPaths.size,
                    discoveredPackages.joinToString(),
                )
            }
        }
        instances
    }

    fun readInstalledSets(
        context: Context,
        cacheDir: File,
        applicationUid: Int,
        instance: RootTelegramInstance,
    ): Result<List<TelegramInstalledStickerSet>> = runCatching {
        val databasePath = requireNotNull(instance.databasePath) {
            context.moduleAppString(R.string.telegram_database_path_unavailable)
        }
        val sessionDir = File(cacheDir, "telegram-root-import-${UUID.randomUUID()}")
        require(sessionDir.mkdirs()) {
            context.moduleAppString(R.string.telegram_database_temp_directory_failed)
        }
        try {
            val destination = File(sessionDir, "cache4.db")
            copyDatabaseSnapshot(context, databasePath, destination, applicationUid).getOrThrow()
            TelegramStickerDatabase.readInstalledSets(destination.asPath).getOrThrow()
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        } finally {
            sessionDir.deleteRecursively()
        }
    }

    private fun copyDatabaseSnapshot(
        context: Context,
        sourcePath: String,
        destination: File,
        applicationUid: Int,
    ): Result<Unit> =
        runCatching {
            val source = sourcePath.shellLiteral()
            val target = destination.absolutePath.shellLiteral()
            val targetDirectory = requireNotNull(destination.parentFile).absolutePath.shellLiteral()
            val command = buildString {
                append("cp -f $source $target || exit 1; ")
                append("if [ -f ${"$sourcePath-wal".shellLiteral()} ]; then ")
                append("cp -f ${"$sourcePath-wal".shellLiteral()} ${"${destination.absolutePath}-wal".shellLiteral()} || exit 1; fi; ")
                append("if [ -f ${"$sourcePath-shm".shellLiteral()} ]; then ")
                append("cp -f ${"$sourcePath-shm".shellLiteral()} ${"${destination.absolutePath}-shm".shellLiteral()} || exit 1; fi; ")
                append("chown $applicationUid:$applicationUid $targetDirectory/*; ")
                append("chmod 600 $targetDirectory/*")
            }
            val result = executeRootCommand(command)
            require(result.isSuccess && destination.isFile && destination.length() > 0L) {
                result.output.joinToString("\n").ifBlank {
                    context.moduleAppString(R.string.telegram_database_copy_failed)
                }
            }
        }

    private fun packageNameFromDatabasePath(path: String): String? {
        val filesMarker = "/files/"
        if (filesMarker !in path) return null
        return path.substringBefore(filesMarker).substringAfterLast('/')
            .takeIf { PACKAGE_NAME_REGEX.matches(it) }
    }

    private fun executeRootCommand(command: String): RootCommandResult {
        val globalNamespaceResult = runCatching {
            val process = ProcessBuilder("su", "-mm", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readLines() }
            RootCommandResult(process.waitFor() == 0, output)
        }.getOrElse { error ->
            RootCommandResult(false, listOfNotNull(error.message))
        }
        if (globalNamespaceResult.isSuccess) return globalNamespaceResult

        WeLogger.w(
            TAG,
            "su -mm failed, falling back to libsu: ${globalNamespaceResult.output.joinToString("；").take(300)}",
        )
        val fallback = Shell.cmd(command).exec()
        return RootCommandResult(
            isSuccess = fallback.isSuccess,
            output = fallback.out + fallback.err,
        )
    }

    private fun String.shellLiteral(): String = "'${replace("'", "'\\''")}'"

    private data class RootCommandResult(
        val isSuccess: Boolean,
        val output: List<String>,
    )

    private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_.]+")
}

@Composable
internal fun RootTelegramStickerSetPickerContent(
    discoverInstances: () -> Result<List<RootTelegramInstance>>,
    readInstalledSets: (RootTelegramInstance) -> Result<List<TelegramInstalledStickerSet>>,
    onCancel: () -> Unit,
    onComplete: (List<TelegramInstalledStickerSet>) -> Unit,
) {
    var instances by remember { mutableStateOf<List<RootTelegramInstance>?>(null) }
    var selectedPackage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var parsing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scanFailed = stringResource(R.string.telegram_instance_scan_failed)
    val emptyStickerSets = stringResource(R.string.telegram_no_importable_sticker_sets)
    val parseFailed = stringResource(R.string.telegram_database_parse_failed)

    fun discover() {
        instances = null
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                discoverInstances()
            }
            result.fold(
                onSuccess = { discovered ->
                    instances = discovered
                    selectedPackage = discovered.singleOrNull()?.packageName
                },
                onFailure = { error = it.message ?: scanFailed },
            )
        }
    }

    LaunchedEffect(Unit) { discover() }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text(stringResource(R.string.telegram_choose_instance)) })
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onCancel, enabled = !parsing) {
                    Text(stringResource(R.string.dialog_cancel))
                }
                Button(
                    onClick = {
                        val instance = instances?.firstOrNull { it.packageName == selectedPackage }
                            ?: return@Button
                        parsing = true
                        error = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                readInstalledSets(instance)
                            }
                            parsing = false
                            result.fold(
                                onSuccess = { sets ->
                                    if (sets.isEmpty()) error = emptyStickerSets
                                    else onComplete(sets)
                                },
                                onFailure = { failure -> error = failure.message ?: parseFailed },
                            )
                        }
                    },
                    enabled = selectedPackage != null && !parsing,
                ) { Text(stringResource(R.string.dialog_confirm)) }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                instances == null && error == null -> CircularProgressIndicator()
                parsing -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.telegram_parsing_database),
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                error != null -> Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(requireNotNull(error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { discover() }) {
                        Text(stringResource(R.string.telegram_retry))
                    }
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(requireNotNull(instances), key = RootTelegramInstance::packageName) { instance ->
                        ListItem(
                            modifier = Modifier.clickable { selectedPackage = instance.packageName },
                            content = { Text(instance.packageName) },
                            supportingContent = { Text(stringResource(R.string.telegram_current_account)) },
                            leadingContent = {
                                RadioButton(
                                    selected = selectedPackage == instance.packageName,
                                    onClick = { selectedPackage = instance.packageName },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun Context.moduleAppString(resourceId: Int, vararg formatArgs: Any): String =
    LocalizedContextFactory.create(
        this,
        WeKitLocaleController.resolvedLocale,
        LocaleResourceMode.ModuleApp,
    ).getString(resourceId, *formatArgs)
