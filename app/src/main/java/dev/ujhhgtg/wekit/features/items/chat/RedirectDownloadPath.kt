package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.KvStore.prefOption
import dev.ujhhgtg.wekit.dex.abc.IResolveDex
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeVfsApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.i18n.localizedString
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.asPath
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

object RedirectDownloadPath : ClickableFeature(), IResolveDex {

    override val technicalId = "重定向文件下载路径"
    override val nameRes = R.string.feature_redirect_download_path_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_redirect_download_path_description

    private const val TAG = "RedirectDownloadPath"
    private var saveDir by prefOption("redirect_download_path_save_dir", "")

    override fun onEnable() {
        WeVfsApi.methodResolveDownloadDirectory.hookBefore {
            val type = args[0] as? String? ?: return@hookBefore
            if (type != "attachment") return@hookBefore
            result = ensureSaveDir()
        }

        methodInitDownloadAttach.hookBefore {
            val msgXml = args.getOrNull(2) as? String ?: return@hookBefore
            val currentPath = args.getOrNull(3) as? String
            val redirectedPath = buildRedirectedFilePath(msgXml, currentPath)
            if (redirectedPath != null) {
                args[3] = redirectedPath
                WeLogger.d(TAG, "redirect app attach download path: $redirectedPath")
            }
        }

        methodInsertDownloadAttach.hookBefore {
            val currentPath = args.getOrNull(0) as? String ?: return@hookBefore
            val redirectedPath = redirectExistingFilePath(currentPath)
            if (redirectedPath != null) {
                args[0] = redirectedPath
                WeLogger.d(TAG, "redirect app attach record path: $redirectedPath")
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var pathInput by remember { mutableStateOf(currentSaveDir()) }
            val normalizedPath = normalizeSaveDir(pathInput)
            val dir = normalizedPath.asPath
            val statusText =
                when {
                    dir.isDirectory() -> stringResource(R.string.chat_redirect_path_exists)
                    dir.exists() -> stringResource(R.string.chat_redirect_path_not_directory)
                    else -> stringResource(R.string.chat_redirect_path_will_create)
                }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_redirect_download_path_name)) },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = pathInput,
                            onValueChange = { pathInput = it },
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.chat_redirect_save_directory)) },
                            singleLine = true,
                        )
                        Text(
                            stringResource(R.string.chat_redirect_actual_directory, normalizedPath),
                        )
                        Text(statusText)
                        Text(
                            stringResource(
                                R.string.chat_redirect_default_directory_hint,
                                defaultSaveDir(),
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                    TextButton(
                        onClick = {
                            pathInput = defaultSaveDir()
                            saveDir = pathInput
                            showToast(
                                context,
                                context.localizedString(
                                    R.string.chat_redirect_default_restored,
                                ),
                            )
                        },
                    ) {
                        Text(stringResource(R.string.chat_redirect_restore_default))
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val saveDir = normalizeSaveDir(pathInput)
                            this@RedirectDownloadPath.saveDir = saveDir
                            runCatching { saveDir.asPath.createDirectories() }
                            showToast(
                                context,
                                context.localizedString(R.string.chat_redirect_saved),
                            )
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        }
    }

    private val methodInitDownloadAttach by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        returnType = "java.lang.String"
        paramTypes("long", "java.lang.String", "java.lang.String", "java.lang.String")
        declaredClass {
            usingStrings("MicroMsg.AppMsgLogic")
            usingStrings(
                "summerbig initDownloadAttach msgLocalId[%d], msgXml[%s], downloadPath[%s]",
            )
            usingStrings(
                "summerbig initDownloadAttach ret[%b], rowid[%d], field_totalLen[%d], type[%d], isLargeFile[%d], destFile[%s], msgLocalId[%s], stack[%s]",
            )
        }
    }
    private val methodInsertDownloadAttach by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.model.app")
        returnType = "java.lang.String"
        paramTypes(
            "java.lang.String",
            "long",
            "java.lang.String",
            "int",
            "java.lang.String",
            "java.lang.String",
            "long",
            "int",
            "java.lang.String",
            "int",
        )
        declaredClass {
            usingStrings("MicroMsg.AppMsgLogic")
            usingStrings(
                "summerbig initDownloadAttach ret[%b], rowid[%d], field_totalLen[%d], type[%d], isLargeFile[%d], destFile[%s], msgLocalId[%s], stack[%s]",
            )
        }
    }

    private fun ensureSaveDir(): String {
        val configured = currentSaveDir().asPath.toAbsolutePath()
        if (configured.exists() && !configured.isDirectory()) {
            WeLogger.w(
                TAG,
                "configured download path is not a directory: $configured",
            )
            return createDefaultSaveDir()
        }

        runCatching { configured.createDirectories() }
            .onFailure {
                WeLogger.w(TAG, "failed to create download path: $configured", it)
            }

        return configured.absolutePathString()
    }

    private fun currentSaveDir(): String {
        return normalizeSaveDir(saveDir)
    }

    private fun normalizeSaveDir(rawPath: String): String {
        val normalized = rawPath.trim().replace('\\', '/').trimEnd('/')
        if (normalized.isEmpty()) return defaultSaveDir()
        if (normalized.startsWith("/")) return normalized
        return KnownPaths.internalStorage.resolve(normalized).absolutePathString()
    }

    private fun defaultSaveDir(): String {
        return (KnownPaths.internalStorage / "Download" / "WeiXin").absolutePathString()
    }

    private fun createDefaultSaveDir(): String {
        return (KnownPaths.internalStorage / "Download" / "WeiXin")
            .createDirsSafe()
            .absolutePathString()
    }

    private fun buildRedirectedFilePath(msgXml: String, currentPath: String?): String? {
        val saveDir = ensureSaveDir().asPath
        val nameFromXml = extractOriginalFileName(msgXml)
        val extFromXml = extractXmlTag(msgXml, "fileext")
        val fallbackName = currentPath?.asPath?.name?.takeIf { it.isNotBlank() }
        val fileName = buildFileName(nameFromXml ?: fallbackName, extFromXml)
        return nextAvailableFile(saveDir, fileName).absolutePathString()
    }

    private fun redirectExistingFilePath(currentPath: String): String? {
        if (isUnderSaveDir(currentPath)) return null
        if (!looksLikeWechatAttachPath(currentPath)) return null

        val fileName =
            currentPath.replace('\\', '/').substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: return null

        return nextAvailableFile(ensureSaveDir().asPath, fileName).absolutePathString()
    }

    private fun buildFileName(rawName: String?, rawExt: String?): String {
        val ext = rawExt.orEmpty().trim().trimStart('.').substringBefore('/').substringBefore('\\')
        val baseName =
            rawName
                .orEmpty()
                .trim()
                .replace('\\', '/')
                .substringAfterLast('/')
                .replace(Regex("[\\u0000-\\u001F]"), "")
                .takeIf { it.isNotBlank() } ?: "da_${System.currentTimeMillis()}"

        if (ext.isEmpty()) return baseName
        return if (baseName.lowercase(Locale.ROOT).endsWith(".${ext.lowercase(Locale.ROOT)}")) {
            baseName
        } else {
            "$baseName.$ext"
        }
    }

    private fun nextAvailableFile(dir: Path, fileName: String): Path {
        val first = dir / fileName
        if (!first.exists()) return first

        repeat(19) { index ->
            val candidate = dir / "${index + 1}_$fileName"
            if (!candidate.exists()) return candidate
        }

        val dotIndex = fileName.lastIndexOf('.')
        val prefix = if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
        val suffix = if (dotIndex > 0) fileName.substring(dotIndex) else ""
        return dir / "${prefix}_${System.currentTimeMillis()}$suffix"
    }

    private fun isUnderSaveDir(path: String): Boolean {
        val saveDir = currentSaveDir().asPath.toAbsolutePath().normalize()
        return path.asPath.toAbsolutePath().normalize().startsWith(saveDir)
    }

    @SuppressLint("SdCardPath")
    private fun looksLikeWechatAttachPath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return normalized.startsWith("wcf://attachment/") ||
            normalized.contains("/micromsg/") && normalized.contains("/attachment/") ||
            normalized.startsWith("/data/data/com.tencent.mm/") ||
            normalized.startsWith("/data/user/0/com.tencent.mm/")
    }

    private fun extractOriginalFileName(xml: String): String? {
        val title = extractXmlTag(xml, "title")
        val attachFileName = extractXmlTag(xml, "filename")

        return title
            ?: attachFileName
            ?: extractXmlAttr(xml, "title")
            ?: extractXmlAttr(xml, "filename")
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val value =
            Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    private fun extractXmlAttr(xml: String, attr: String): String? {
        val value =
            Regex(
                    """\b$attr\s*=\s*(['"])(.*?)\1""",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                )
                .find(xml)
                ?.groupValues
                ?.getOrNull(2)
                ?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
        return value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }
}
