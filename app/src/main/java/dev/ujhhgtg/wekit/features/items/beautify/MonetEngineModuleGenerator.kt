package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import dev.ujhhgtg.wekit.extensions.MonetGeneratorPack
import dev.ujhhgtg.wekit.extensions.MonetDexEvidenceCollector
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEvent
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListener
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequest
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResult
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStage
import dev.ujhhgtg.wekit.extensions.monet.api.MonetLogLevel
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
        showGeneratorDialog(activity)
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

    private fun showGeneratorDialog(activity: Activity) {
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
                    GeneratorUiState.Running(MonetGenerationStage.PREPARING),
                )
            }

            LaunchedEffect(Unit) {
                thread(name = "monet-module-generator") {
                    var currentStage = MonetGenerationStage.PREPARING
                    try {
                        val resolvedOutputZip =
                            (KnownPaths.downloads / "monet_engine_module.zip").toFile()
                        val workDir = (KnownPaths.moduleCache / "monet").toFile()
                        val request = MonetGenerationRequest(
                            resources = HostInfo.application.resources,
                            packageName = HostInfo.packageName,
                            sourceApkPath = HostInfo.appInfo.sourceDir,
                            versionCode = HostInfo.versionCode,
                            versionName = HostInfo.versionName,
                            sdkInt = Build.VERSION.SDK_INT,
                            dexEvidenceProvider = MonetDexEvidenceCollector::collect,
                            payloadDir = resolvedPack.payloadDir,
                            workDir = workDir,
                            outputZip = resolvedOutputZip,
                        )
                        val result = resolvedPack.generator.generate(
                            request,
                            MonetGenerationListener { event ->
                                when (event) {
                                    is MonetGenerationEvent.Progress -> {
                                        currentStage = event.stage
                                        window.decorView.post {
                                            state = GeneratorUiState.Running(event.stage)
                                        }
                                    }

                                    is MonetGenerationEvent.Log -> logEvent(event)
                                }
                            },
                        )
                        window.decorView.post { state = GeneratorUiState.Done(result) }
                    } catch (error: Throwable) {
                        WeLogger.e(TAG, "generation failed during $currentStage", error)
                        window.decorView.post {
                            state = GeneratorUiState.Failed(
                                currentStage,
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
                        is GeneratorUiState.Running -> RunningContent(stageText(current.stage))
                        is GeneratorUiState.Done -> DoneContent(current.result)
                        is GeneratorUiState.Failed -> Text(
                            stringResource(
                                R.string.monet_generator_failed,
                                stageText(current.stage),
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
    data class Running(val stage: MonetGenerationStage) : GeneratorUiState
    data class Done(val result: MonetGenerationResult) : GeneratorUiState
    data class Failed(val stage: MonetGenerationStage, val message: String) : GeneratorUiState
}

@Composable
private fun stageText(stage: MonetGenerationStage): String = stringResource(
    when (stage) {
        MonetGenerationStage.PREPARING -> R.string.monet_generator_preparing
        MonetGenerationStage.BUILDING_OVERLAY -> R.string.monet_generator_building
        MonetGenerationStage.SIGNING -> R.string.monet_generator_signing
        MonetGenerationStage.PACKAGING -> R.string.monet_generator_packaging
    },
)

@Composable
private fun RunningContent(status: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(status)
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
