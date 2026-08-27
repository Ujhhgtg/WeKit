package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.os.Build
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.extensions.ExtensionPackDialogs
import dev.ujhhgtg.wekit.extensions.ExtensionPacks
import dev.ujhhgtg.wekit.extensions.MonetDexEvidenceCollector
import dev.ujhhgtg.wekit.extensions.MonetGeneratorPack
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEvent
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResult
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStage
import dev.ujhhgtg.wekit.extensions.monet.api.MonetLogLevel
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlin.concurrent.thread
import kotlin.io.path.div

object MonetEngineModuleGenerator : ClickableFeature() {

    override val technicalId = "莫奈引擎 (模块)"
    override val nameRes = R.string.feature_monet_module_generator_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_monet_module_generator_description

    private const val TAG = "MonetEngineModuleGenerator"

    override fun onClick(context: ComponentActivity) {
        val activity = context as Activity
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            showUnsupportedDialog(activity)
            return
        }
        ExtensionPacks.refresh(MonetGeneratorPack)
        if (!MonetGeneratorPack.isInstalled()) {
            ExtensionPackDialogs.requireInstall(activity, MonetGeneratorPack)
            return
        }
        showOptionsDialog(activity)
    }

    private fun showUnsupportedDialog(activity: Activity) {
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_monet_module_generator_name)) },
                text = { Text(stringResource(R.string.monet_generator_unsupported)) },
                confirmButton = {
                    Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun showOptionsDialog(activity: Activity) {
        showComposeDialog(activity) {
            var bubbleStyle by remember { mutableStateOf(MonetBubbleStyle.MODERN) }
            var corners by remember { mutableStateOf(true) }
            var tabStyle by remember { mutableStateOf(MonetTabStyle.SOLID) }
            var userScope by remember { mutableStateOf(MonetUserScope.CURRENT) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.monet_options_title)) },
                text = {
                    Column {
                        Text(stringResource(R.string.monet_bubble_style), style = MaterialTheme.typography.titleSmall)
                        RadioOption(stringResource(R.string.monet_bubble_modern), bubbleStyle == MonetBubbleStyle.MODERN) { bubbleStyle = MonetBubbleStyle.MODERN }
                        RadioOption(stringResource(R.string.monet_bubble_classic), bubbleStyle == MonetBubbleStyle.CLASSIC) { bubbleStyle = MonetBubbleStyle.CLASSIC }
                        RadioOption(stringResource(R.string.monet_bubble_pro), bubbleStyle == MonetBubbleStyle.PRO) { bubbleStyle = MonetBubbleStyle.PRO }
                        Row(
                            Modifier.fillMaxWidth().clickable { corners = !corners }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.monet_multi_scene_corners), Modifier.weight(1f))
                            Switch(corners, { corners = it })
                        }
                        Text(stringResource(R.string.monet_tab_style), style = MaterialTheme.typography.titleSmall)
                        RadioOption(stringResource(R.string.monet_tab_solid), tabStyle == MonetTabStyle.SOLID) { tabStyle = MonetTabStyle.SOLID }
                        RadioOption(stringResource(R.string.monet_tab_blur), tabStyle == MonetTabStyle.BLUR) { tabStyle = MonetTabStyle.BLUR }
                        Text(stringResource(R.string.monet_user_scope), style = MaterialTheme.typography.titleSmall)
                        RadioOption(stringResource(R.string.monet_user_current), userScope == MonetUserScope.CURRENT) { userScope = MonetUserScope.CURRENT }
                        RadioOption(stringResource(R.string.monet_user_all), userScope == MonetUserScope.ALL) { userScope = MonetUserScope.ALL }
                    }
                },
                confirmButton = {
                    Button({
                        onDismiss()
                        showGeneratorDialog(
                            activity,
                            MonetGenerationOptions(
                                bubbleStyle = bubbleStyle,
                                multiSceneCorners = corners,
                                tabStyle = tabStyle,
                                userScope = userScope,
                                currentUserId = Process.myUid() / 100000,
                            ),
                        )
                    }) { Text(stringResource(R.string.monet_generate)) }
                },
                dismissButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
            )
        }
    }

    private fun showGeneratorDialog(activity: Activity, options: MonetGenerationOptions) {
        val resolvedPack = try {
            requireNotNull(MonetGeneratorPack.resolve())
        } catch (error: Throwable) {
            WeLogger.e(TAG, "failed to load Monet generator extension", error)
            showInvalidPackDialog(activity)
            return
        }

        showComposeDialog(activity, directlyDismissable = false) {
            var state by remember {
                mutableStateOf<GeneratorUiState>(
                    GeneratorUiState.Running(
                        MonetGenerationEvent.Progress(
                            MonetGenerationStage.LOADING_APKS,
                            "准备生成",
                            0,
                            1,
                        ),
                    ),
                )
            }

            LaunchedEffect(Unit) {
                thread(name = "monet-module-generator") {
                    var currentProgress = MonetGenerationEvent.Progress(
                        MonetGenerationStage.LOADING_APKS,
                        "准备生成",
                        0,
                        1,
                    )
                    try {
                        val resolvedOutputZip =
                            (KnownPaths.downloads / "monet_engine_module.zip").toFile()
                        val workDir = (KnownPaths.moduleCache / "monet").toFile()
                        val request = MonetGenerationRequest(
                            resources = HostInfo.application.resources,
                            packageName = HostInfo.packageName,
                            sourceApkPath = HostInfo.appInfo.sourceDir,
                            sourceApkPaths = listOf(HostInfo.appInfo.sourceDir) +
                                HostInfo.appInfo.splitSourceDirs.orEmpty(),
                            versionCode = HostInfo.versionCode,
                            versionName = HostInfo.versionName,
                            sdkInt = Build.VERSION.SDK_INT,
                            dexEvidenceProvider = MonetDexEvidenceCollector::collect,
                            options = options,
                            payloadDir = resolvedPack.payloadDir,
                            workDir = workDir,
                            outputZip = resolvedOutputZip,
                        )
                        val result = resolvedPack.generator.generate(
                            request,
                        ) { event ->
                            when (event) {
                                is MonetGenerationEvent.Progress -> {
                                    currentProgress = event
                                    window.decorView.post {
                                        state = GeneratorUiState.Running(event)
                                    }
                                }

                                is MonetGenerationEvent.Log -> logEvent(event)
                            }
                        }
                        window.decorView.post { state = GeneratorUiState.Done(result) }
                    } catch (error: Throwable) {
                        WeLogger.e(
                            TAG,
                            "generation failed during ${currentProgress.stage}: ${currentProgress.detail}",
                            error,
                        )
                        window.decorView.post {
                            state = GeneratorUiState.Failed(
                                currentProgress,
                                error.message ?: error.toString(),
                            )
                        }
                    }
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_monet_module_generator_name)) },
                text = {
                    when (val current = state) {
                        is GeneratorUiState.Running -> RunningContent(current.progress)
                        is GeneratorUiState.Done -> DoneContent(current.result)
                        is GeneratorUiState.Failed -> Text(
                            stringResource(
                                R.string.monet_generator_failed,
                                current.progress.detail,
                                current.message,
                            ),
                        )
                    }
                },
                confirmButton = {
                    if (state !is GeneratorUiState.Running) {
                        Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                    }
                },
            )
        }
    }

    private fun showInvalidPackDialog(activity: Activity) {
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_monet_module_generator_name)) },
                text = { Text(stringResource(R.string.monet_generator_pack_invalid)) },
                confirmButton = {
                    Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun logEvent(event: MonetGenerationEvent.Log) {
        val error = event.error
        when (event.level) {
            MonetLogLevel.DEBUG -> if (error == null) {
                WeLogger.d(TAG, event.message)
            } else {
                WeLogger.d(TAG, event.message, error)
            }

            MonetLogLevel.INFO -> if (error == null) {
                WeLogger.i(TAG, event.message)
            } else {
                WeLogger.i(TAG, event.message, error)
            }

            MonetLogLevel.WARN -> if (error == null) {
                WeLogger.w(TAG, event.message)
            } else {
                WeLogger.w(TAG, event.message, error)
            }

            MonetLogLevel.ERROR -> if (error == null) {
                WeLogger.e(TAG, event.message)
            } else {
                WeLogger.e(TAG, event.message, error)
            }
        }
    }
}

private sealed interface GeneratorUiState {
    data class Running(val progress: MonetGenerationEvent.Progress) : GeneratorUiState
    data class Done(val result: MonetGenerationResult) : GeneratorUiState
    data class Failed(val progress: MonetGenerationEvent.Progress, val message: String) : GeneratorUiState
}

@Composable
private fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onSelect)
        Text(label)
    }
}

@Composable
private fun RunningContent(progress: MonetGenerationEvent.Progress) {
    Column {
        Text(progress.detail)
        Spacer(Modifier.height(8.dp))
        val completed = progress.completed
        val total = progress.total
        if (completed != null && total != null) {
            LinearProgressIndicator(
                progress = { completed.toFloat() / total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "$completed/$total",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DoneContent(result: MonetGenerationResult) {
    Column {
        Text(stringResource(R.string.monet_generator_output, result.outputZip.absolutePath))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                R.string.monet_generator_counts,
                result.kept + result.added,
                result.kept,
                result.added,
                result.pruned,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.monet_generator_install_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
