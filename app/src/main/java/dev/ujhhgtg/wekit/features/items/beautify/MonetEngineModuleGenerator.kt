package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import dev.ujhhgtg.wekit.extensions.HostMonetDexEvidenceProvider
import dev.ujhhgtg.wekit.extensions.MonetGeneratorPack
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBlurPalette
import dev.ujhhgtg.wekit.extensions.monet.api.MonetBubbleStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationEventV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationListenerV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationOptions
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationRequestV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationResultV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetGenerationStageV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetLogLevelV2
import dev.ujhhgtg.wekit.extensions.monet.api.MonetTabStyle
import dev.ujhhgtg.wekit.extensions.monet.api.MonetUserScope
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.androidUserId
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.lang.ref.WeakReference
import kotlin.concurrent.thread
import kotlin.io.path.div

object MonetEngineModuleGenerator : ClickableFeature() {

    override val technicalId = "莫奈引擎 (模块)"
    override val nameRes = R.string.feature_monet_module_generator_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_monet_module_generator_description

    private const val TAG = "MonetEngineModuleGenerator"
    private const val PREF_BUBBLE_STYLE = "monet_generator_bubble_style"
    private const val PREF_MULTI_SCENE_CORNERS = "monet_generator_multi_scene_corners"
    private const val PREF_TAB_STYLE = "monet_generator_tab_style"
    private const val PREF_USER_SCOPE = "monet_generator_user_scope"

    private val lightBlurColorNames = listOf(
        "system_surface_container_light",
        "system_surface_container_high_light",
        "system_surface_light",
        "system_primary_container_light",
    )
    private val nightBlurColorNames = listOf(
        "system_surface_container_dark",
        "system_surface_container_high_dark",
        "system_surface_dark",
        "system_primary_container_dark",
    )

    private var bubbleStyleName by prefOption(PREF_BUBBLE_STYLE, MonetBubbleStyle.MODERN.name)
    private var multiSceneCornersEnabled by prefOption(PREF_MULTI_SCENE_CORNERS, true)
    private var tabStyleName by prefOption(PREF_TAB_STYLE, MonetTabStyle.SOLID.name)
    private var userScopeName by prefOption(PREF_USER_SCOPE, MonetUserScope.CURRENT.name)

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
        val currentUserId = androidUserId

        showComposeDialog(activity, directlyDismissable = false) {
            var state by remember {
                mutableStateOf<GeneratorUiState>(
                    GeneratorUiState.Configuring(loadOptions()),
                )
            }
            var generatedOptions by remember { mutableStateOf<MonetGenerationOptions?>(null) }
            val blurLightUnavailable = stringResource(
                R.string.monet_generator_blur_color_unavailable,
                stringResource(R.string.monet_generator_palette_light),
            )
            val blurNightUnavailable = stringResource(
                R.string.monet_generator_blur_color_unavailable,
                stringResource(R.string.monet_generator_palette_night),
            )

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_monet_module_generator_name)) },
                text = {
                    when (val current = state) {
                        is GeneratorUiState.Configuring -> ConfiguringContent(
                            options = current.options,
                            currentUserId = currentUserId,
                            onOptionsChange = { state = GeneratorUiState.Configuring(it) },
                        )
                        is GeneratorUiState.Running -> RunningContent(stageText(current.stage))
                        is GeneratorUiState.Done -> DoneContent(
                            result = current.result,
                            options = requireNotNull(generatedOptions),
                            currentUserId = currentUserId,
                        )
                        is GeneratorUiState.Failed -> FailedContent(
                            stage = current.stage,
                            message = current.message,
                        )
                    }
                },
                confirmButton = {
                    when (val current = state) {
                        is GeneratorUiState.Configuring -> Button(
                            onClick = {
                                val options = current.options
                                persistOptions(options)
                                generatedOptions = options
                                state = GeneratorUiState.Running(
                                    MonetGenerationStageV2.PREPARING,
                                )
                                startGeneration(
                                    resolvedPack = resolvedPack,
                                    options = options,
                                    currentUserId = currentUserId,
                                    decorView = window.decorView,
                                    blurLightUnavailable = blurLightUnavailable,
                                    blurNightUnavailable = blurNightUnavailable,
                                    onState = { state = it },
                                )
                            },
                        ) {
                            Text(stringResource(R.string.monet_generator_generate))
                        }

                        is GeneratorUiState.Done,
                        is GeneratorUiState.Failed,
                        -> Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }

                        is GeneratorUiState.Running -> Unit
                    }
                },
            )
        }
    }

    private fun loadOptions(): MonetGenerationOptions = MonetGenerationOptions(
        bubbleStyle = MonetBubbleStyle.entries.firstOrNull { it.name == bubbleStyleName }
            ?: MonetBubbleStyle.MODERN,
        multiSceneCornersEnabled = multiSceneCornersEnabled,
        tabStyle = MonetTabStyle.entries.firstOrNull { it.name == tabStyleName }
            ?: MonetTabStyle.SOLID,
        userScope = MonetUserScope.entries.firstOrNull { it.name == userScopeName }
            ?: MonetUserScope.CURRENT,
    )

    private fun persistOptions(options: MonetGenerationOptions) {
        bubbleStyleName = options.bubbleStyle.name
        multiSceneCornersEnabled = options.multiSceneCornersEnabled
        tabStyleName = options.tabStyle.name
        userScopeName = options.userScope.name
    }

    private fun startGeneration(
        resolvedPack: MonetGeneratorPack.Resolved,
        options: MonetGenerationOptions,
        currentUserId: Int,
        decorView: View,
        blurLightUnavailable: String,
        blurNightUnavailable: String,
        onState: (GeneratorUiState) -> Unit,
    ) {
        val decorViewReference = WeakReference(decorView)
        thread(name = "monet-module-generator") {
            var currentStage = MonetGenerationStageV2.PREPARING
            try {
                val outputZip = (KnownPaths.downloads / "monet_engine_module.zip").toFile()
                val workDir = (KnownPaths.moduleCache / "monet").toFile()
                val apkPaths = buildList {
                    add(HostInfo.appInfo.sourceDir)
                    addAll(HostInfo.appInfo.splitSourceDirs.orEmpty())
                }.distinct()
                val blurPalette = when (options.tabStyle) {
                    MonetTabStyle.SOLID -> null
                    MonetTabStyle.BLUR -> resolveBlurPalette(
                        lightUnavailable = blurLightUnavailable,
                        nightUnavailable = blurNightUnavailable,
                    )
                }
                val request = MonetGenerationRequestV2(
                    resources = HostInfo.application.resources,
                    packageName = HostInfo.packageName,
                    sourceApkPaths = apkPaths,
                    versionCode = HostInfo.versionCode,
                    versionName = HostInfo.versionName,
                    isGooglePlay = HostInfo.isHostGooglePlay,
                    sdkInt = Build.VERSION.SDK_INT,
                    currentUserId = currentUserId,
                    options = options,
                    blurPalette = blurPalette,
                    dexEvidenceProvider = HostMonetDexEvidenceProvider,
                    payloadDir = resolvedPack.payloadDir,
                    workDir = workDir,
                    outputZip = outputZip,
                )
                val result = resolvedPack.generator.generate(
                    request,
                    MonetGenerationListenerV2 { event ->
                        when (event) {
                            is MonetGenerationEventV2.Progress -> {
                                currentStage = event.stage
                                decorViewReference.get()?.post {
                                    onState(GeneratorUiState.Running(event.stage))
                                }
                            }

                            is MonetGenerationEventV2.Log -> logEvent(event)
                        }
                    },
                )
                decorViewReference.get()?.post { onState(GeneratorUiState.Done(result)) }
            } catch (error: Throwable) {
                WeLogger.e(TAG, "generation failed during $currentStage", error)
                decorViewReference.get()?.post {
                    onState(
                        GeneratorUiState.Failed(
                            currentStage,
                            error.message ?: error.toString(),
                        ),
                    )
                }
            }
        }
    }

    private fun resolveBlurPalette(
        lightUnavailable: String,
        nightUnavailable: String,
    ): MonetBlurPalette {
        val resources = HostInfo.application.resources
        val light = resolveFrameworkColor(resources, lightBlurColorNames)
            ?: error(lightUnavailable)
        val night = resolveFrameworkColor(resources, nightBlurColorNames)
            ?: error(nightUnavailable)
        return MonetBlurPalette(
            lightRgb = light.value,
            nightRgb = night.value,
            lightSource = light.name,
            nightSource = night.name,
        )
    }

    private fun resolveFrameworkColor(
        resources: Resources,
        names: List<String>,
    ): ResolvedFrameworkColor? {
        for (name in names) {
            val id = resources.getIdentifier(name, "color", "android")
            if (id == 0) continue
            try {
                return ResolvedFrameworkColor(name, resources.getColor(id, null))
            } catch (_: Resources.NotFoundException) {
                continue
            }
        }
        return null
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

    private fun logEvent(event: MonetGenerationEventV2.Log) {
        val error = event.error
        when (event.level) {
            MonetLogLevelV2.DEBUG -> if (error == null) {
                WeLogger.d(TAG, event.message)
            } else {
                WeLogger.d(TAG, event.message, error)
            }

            MonetLogLevelV2.INFO -> if (error == null) {
                WeLogger.i(TAG, event.message)
            } else {
                WeLogger.i(TAG, event.message, error)
            }

            MonetLogLevelV2.WARN -> if (error == null) {
                WeLogger.w(TAG, event.message)
            } else {
                WeLogger.w(TAG, event.message, error)
            }

            MonetLogLevelV2.ERROR -> if (error == null) {
                WeLogger.e(TAG, event.message)
            } else {
                WeLogger.e(TAG, event.message, error)
            }
        }
    }
}

private data class ResolvedFrameworkColor(val name: String, val value: Int)

private sealed interface GeneratorUiState {
    data class Configuring(val options: MonetGenerationOptions) : GeneratorUiState
    data class Running(val stage: MonetGenerationStageV2) : GeneratorUiState
    data class Done(val result: MonetGenerationResultV2) : GeneratorUiState
    data class Failed(val stage: MonetGenerationStageV2, val message: String) : GeneratorUiState
}

@Composable
private fun stageText(stage: MonetGenerationStageV2): String = stringResource(
    when (stage) {
        MonetGenerationStageV2.PREPARING -> R.string.monet_generator_preparing
        MonetGenerationStageV2.SCANNING_RESOURCES -> R.string.monet_generator_scanning
        MonetGenerationStageV2.RESOLVING_RESOURCES -> R.string.monet_generator_resolving
        MonetGenerationStageV2.BUILDING_OVERLAYS -> R.string.monet_generator_building
        MonetGenerationStageV2.SIGNING -> R.string.monet_generator_signing
        MonetGenerationStageV2.PACKAGING -> R.string.monet_generator_packaging
    },
)

@Composable
private fun ConfiguringContent(
    options: MonetGenerationOptions,
    currentUserId: Int,
    onOptionsChange: (MonetGenerationOptions) -> Unit,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SegmentedColumn(
            title = stringResource(R.string.monet_generator_bubble_style),
            contentPadding = PaddingValues(0.dp),
        ) {
            item(key = MonetBubbleStyle.MODERN.name) {
                RadioButtonWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_bubble_modern),
                    selected = options.bubbleStyle == MonetBubbleStyle.MODERN,
                    onClick = {
                        onOptionsChange(options.copy(bubbleStyle = MonetBubbleStyle.MODERN))
                    },
                )
            }
            item(key = MonetBubbleStyle.CLASSIC.name) {
                RadioButtonWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_bubble_classic),
                    selected = options.bubbleStyle == MonetBubbleStyle.CLASSIC,
                    onClick = {
                        onOptionsChange(options.copy(bubbleStyle = MonetBubbleStyle.CLASSIC))
                    },
                )
            }
            item(key = MonetBubbleStyle.PRO.name) {
                RadioButtonWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_bubble_pro),
                    selected = options.bubbleStyle == MonetBubbleStyle.PRO,
                    onClick = {
                        onOptionsChange(options.copy(bubbleStyle = MonetBubbleStyle.PRO))
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SegmentedColumn(
            title = stringResource(R.string.monet_generator_corner_style),
            contentPadding = PaddingValues(0.dp),
        ) {
            item(key = "multi_scene_corners") {
                SwitchWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_multi_scene_corners),
                    description = stringResource(
                        R.string.monet_generator_multi_scene_corners_description,
                    ),
                    checked = options.multiSceneCornersEnabled,
                    onCheckedChange = {
                        onOptionsChange(options.copy(multiSceneCornersEnabled = it))
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SegmentedColumn(
            title = stringResource(R.string.monet_generator_tab_style),
            contentPadding = PaddingValues(0.dp),
        ) {
            item(key = MonetTabStyle.SOLID.name) {
                RadioButtonWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_tab_solid),
                    selected = options.tabStyle == MonetTabStyle.SOLID,
                    onClick = {
                        onOptionsChange(options.copy(tabStyle = MonetTabStyle.SOLID))
                    },
                )
            }
            item(key = MonetTabStyle.BLUR.name) {
                RadioButtonWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_tab_blur),
                    description = stringResource(R.string.monet_generator_blur_limitation),
                    selected = options.tabStyle == MonetTabStyle.BLUR,
                    onClick = {
                        onOptionsChange(options.copy(tabStyle = MonetTabStyle.BLUR))
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        SegmentedColumn(
            title = stringResource(R.string.monet_generator_user_scope),
            contentPadding = PaddingValues(0.dp),
        ) {
            item(key = MonetUserScope.CURRENT.name) {
                RadioButtonWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_user_current),
                    description = stringResource(
                        R.string.monet_generator_user_current_description,
                        currentUserId,
                    ),
                    selected = options.userScope == MonetUserScope.CURRENT,
                    onClick = {
                        onOptionsChange(options.copy(userScope = MonetUserScope.CURRENT))
                    },
                )
            }
            item(key = MonetUserScope.ALL.name) {
                RadioButtonWidget(
                    iconPlaceholder = false,
                    title = stringResource(R.string.monet_generator_user_all),
                    description = stringResource(R.string.monet_generator_user_all_description),
                    selected = options.userScope == MonetUserScope.ALL,
                    onClick = {
                        onOptionsChange(options.copy(userScope = MonetUserScope.ALL))
                    },
                )
            }
        }
    }
}

@Composable
private fun RunningContent(status: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(status)
    }
}

@Composable
private fun DoneContent(
    result: MonetGenerationResultV2,
    options: MonetGenerationOptions,
    currentUserId: Int,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(stringResource(R.string.monet_generator_output, result.outputZip.absolutePath))
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                R.string.monet_generator_overlays,
                result.overlays.size,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        result.overlays.forEach { overlay ->
            Text(
                stringResource(
                    R.string.monet_generator_overlay_result,
                    overlay.fileName,
                    overlay.kept,
                    overlay.added,
                    overlay.rewritten,
                    overlay.skipped,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        val kept = result.overlays.sumOf { it.kept }
        val added = result.overlays.sumOf { it.added }
        val rewritten = result.overlays.sumOf { it.rewritten }
        val skipped = result.overlays.sumOf { it.skipped }
        Text(
            stringResource(
                R.string.monet_generator_counts,
                kept,
                added,
                rewritten,
                skipped,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (result.skippedRoles.isEmpty()) {
            Text(
                stringResource(R.string.monet_generator_no_skipped_roles),
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Text(
                stringResource(
                    R.string.monet_generator_skipped_roles,
                    result.skippedRoles.size,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            result.skippedRoles.forEach { role ->
                Text(
                    stringResource(
                        R.string.monet_generator_skipped_role,
                        role.roleId,
                        role.reason,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                R.string.monet_generator_diagnostics,
                result.diagnosticsFile.absolutePath,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        val userScope = when (options.userScope) {
            MonetUserScope.CURRENT -> stringResource(
                R.string.monet_generator_selected_user_current,
                currentUserId,
            )
            MonetUserScope.ALL -> stringResource(R.string.monet_generator_selected_user_all)
        }
        Text(
            stringResource(R.string.monet_generator_selected_user_scope, userScope),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(
                when (options.userScope) {
                    MonetUserScope.CURRENT -> R.string.monet_generator_boot_restoration_current
                    MonetUserScope.ALL -> R.string.monet_generator_boot_restoration_all
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (options.tabStyle == MonetTabStyle.BLUR) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.monet_generator_blur_limitation),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.monet_generator_install_hint),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun FailedContent(stage: MonetGenerationStageV2, message: String) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(
                R.string.monet_generator_failed,
                stageText(stage),
                message,
            ),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(
                R.string.monet_generator_diagnostics_if_available,
                (KnownPaths.downloads / "monet-resolution.json").toFile().absolutePath,
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
