package dev.ujhhgtg.wekit.features.items.beautify

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.ResourcesImpl
import android.content.res.loader.ResourcesProvider
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.materialkolor.ktx.DynamicScheme
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.InfoTipCard
import dev.ujhhgtg.wekit.ui.content.m3.RadioButtonWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.ui.utils.theme.SeedResolver
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.postOnUiThread
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.monet.MonetAssetInjector
import dev.ujhhgtg.wekit.utils.monet.MonetBindings
import dev.ujhhgtg.wekit.utils.monet.MonetBubbleStyle
import dev.ujhhgtg.wekit.utils.monet.MonetDexEvidenceCollector
import dev.ujhhgtg.wekit.utils.monet.MonetResolveProgress
import dev.ujhhgtg.wekit.utils.monet.MonetResolveStage
import dev.ujhhgtg.wekit.utils.monet.MonetResourceResolver
import dev.ujhhgtg.wekit.utils.monet.MonetRuntimePackageWriter
import dev.ujhhgtg.wekit.utils.monet.MonetThemeRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread
import kotlin.io.path.div
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runtime Monet for WeChat: injects an ID-preserving resource table into WeChat's AssetManagers
 * (see [MonetAssetInjector]) and rebuilds it live whenever the WeKit theme or a Monet option
 * changes. Replaces the former RRO/Magisk module generator; no root is involved.
 */
object MonetEngine : ClickableFeature() {

    override val technicalId = "莫奈引擎"
    override val nameRes = R.string.feature_monet_engine_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_monet_engine_description

    private const val TAG = "MonetEngine"

    private var bubbleStylePref by KvStore.prefOption("monet_bubble_style", MonetBubbleStyle.MODERN.name)
    private var multiSceneCornersPref by KvStore.prefOption("monet_multi_scene_corners", true)
    private var errorColorsPref by KvStore.prefOption("monet_error_colors", false)

    /** Compose state mirrors of the prefs so the live observer sees option changes. */
    private val options by lazy {
        mutableStateOf(Options(MonetBubbleStyle.valueOf(bubbleStylePref), multiSceneCornersPref, errorColorsPref))
    }
    private val bindingsState = mutableStateOf<MonetBindings?>(null)
    private val resolving = mutableStateOf(false)

    /** Bumped on configuration changes: wallpaper-derived colors are re-read, not observable. */
    private val configTick = mutableIntStateOf(0)

    private val cacheDir by lazy { (KnownPaths.moduleCache / "monet").toFile() }
    private var scope: CoroutineScope? = null
    private var configCallbacks: ComponentCallbacks? = null
    private var appliedKey: String? = null
    private var pendingResolve = false

    /** Held so the provider in use is never finalized; replaced ones are left to GC, never closed. */
    private var currentProvider: ResourcesProvider? = null

    /**
     * Resolved ARGB colors of the applied palette, for surfaces WeKit draws itself (island list,
     * group tabs, FAB) so they match the recolored WeChat UI.
     */
    data class SurfaceColors(
        /** Page behind cards and lists. */
        val page: Int,
        /** Cards and list rows. */
        val surface: Int,
        /** Raised controls such as secondary FABs. */
        val surfaceHigh: Int,
        val divider: Int,
        val primary: Int,
        val primaryContainer: Int,
        val onPrimaryContainer: Int,
    )

    /** Snapshot state: Compose readers recompose and View binders read the latest value on bind. */
    private val surfaceColorsState = mutableStateOf<Pair<SurfaceColors, SurfaceColors>?>(null)

    /** Colors of the applied Monet table, or `null` while none is applied (feature off / not ready). */
    fun surfaceColors(dark: Boolean): SurfaceColors? =
        surfaceColorsState.value?.let { (light, night) -> if (dark) night else light }

    private data class Options(
        val bubbleStyle: MonetBubbleStyle,
        val multiSceneCorners: Boolean,
        val errorColors: Boolean,
    )

    /** Everything a runtime table depends on; [key] names its cached APK. */
    private data class ThemeState(
        val source: String,
        val seed: Int,
        val paletteStyle: String,
        val colorSpec: String,
        val options: Options,
    ) {
        // RENDER_VERSION: cached tables are reused by key, so output-format changes must bump it.
        val key: String get() = MonetResourceResolver.sha256("$RENDER_VERSION:$this").take(16)
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (!newState) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showUnsupportedDialog(context)
            return false
        }
        val fingerprint = MonetResourceResolver.fingerprint(hostApks().map(::File))
        if (MonetResourceResolver.load(cacheDir, fingerprint) != null) return true
        showResolveDialog(context) { applyToggle(true) }
        return false
    }

    override fun onEnable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            WeLogger.w(TAG, "ResourcesLoader needs API 30, not enabling")
            return
        }
        enableRuntime()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun enableRuntime() {
        val apks = hostApks()
        WeLogger.i(TAG, "WeChat resource APKs: $apks")
        MonetAssetInjector.init(apks)
        val fingerprint = MonetResourceResolver.fingerprint(apks.map(::File))
        pruneCache(fingerprint)

        // Implementations created from now on: new ResourcesManager keys, configuration
        // contexts and WeChat's MMResources wrappers (their constructor creates one).
        ResourcesImpl::class.reflekt().constructors().forEach {
            it.hookAfter { MonetAssetInjector.registerImpl(thisObject as ResourcesImpl, flush = false) }
        }
        // The global MMResources was created during Application.onCreate, before WeKit; it is
        // only reachable through the activities that return it from getResources().
        Instrumentation::class.java.reflekt().methods { name = "callActivityOnCreate" }.forEach {
            it.hookBefore {
                val activity = args[0] as Activity
                MonetAssetInjector.register(activity.resources, flush = true)
                MonetAssetInjector.register(activity.baseContext.resources, flush = true)
                if (pendingResolve) {
                    pendingResolve = false
                    // After onCreate so the dialog has a window to attach to.
                    activity.window.decorView.post { showResolveDialog(activity) }
                }
            }
        }
        MonetAssetInjector.register(HostInfo.application.resources, flush = true)
        MonetAssetInjector.managedImpls().forEach { MonetAssetInjector.registerImpl(it, flush = true) }

        val cached = MonetResourceResolver.load(cacheDir, fingerprint)
        if (cached != null) {
            bindingsState.value = cached
            // Synchronous on first apply so the first WeChat screens are already themed.
            val state = currentState()
            applyTable(cached, state, build(cached, state))
        } else {
            // WeChat (or its Tinker patch) changed since the last analysis: ask again on the first screen.
            WeLogger.i(TAG, "no bindings for $fingerprint, resolving on the first activity")
            pendingResolve = true
        }
        startObserver()
    }

    override fun onDisable() {
        scope?.cancel()
        scope = null
        configCallbacks?.let(HostInfo.application::unregisterComponentCallbacks)
        configCallbacks = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            postOnUiThread {
                MonetAssetInjector.swap(null)
                currentProvider = null
                appliedKey = null
                surfaceColorsState.value = null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @OptIn(FlowPreview::class)
    private fun startObserver() {
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = observerScope
        observerScope.launch {
            snapshotFlow {
                configTick.intValue
                bindingsState.value?.let { it to currentState() }
            }.drop(1).debounce(250.milliseconds).collect { request ->
                val (bindings, state) = request ?: return@collect
                if ("${bindings.fingerprint}-${state.key}" == appliedKey) return@collect
                val file = withContext(Dispatchers.IO) { build(bindings, state) }
                applyTable(bindings, state, file)
            }
        }
        configCallbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                configTick.intValue++
                Snapshot.sendApplyNotifications()
            }

            @Deprecated("Deprecated in Java", ReplaceWith("Unit"))
            override fun onLowMemory() = Unit
        }.also(HostInfo.application::registerComponentCallbacks)
    }

    /** Must run on the main thread: provider swaps propagate into live AssetManagers. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun applyTable(bindings: MonetBindings, state: ThemeState, file: File) {
        val provider = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use {
            ResourcesProvider.loadFromApk(it)
        }
        MonetAssetInjector.swap(provider)
        currentProvider = provider
        appliedKey = "${bindings.fingerprint}-${state.key}"
        surfaceColorsState.value = surfaceColors(state, dark = false) to surfaceColors(state, dark = true)
        Snapshot.sendApplyNotifications()
        WeLogger.i(TAG, "applied ${file.name} (${bindings.roles.size} roles, ${bindings.unresolved.size} unresolved)")
    }

    private fun build(bindings: MonetBindings, state: ThemeState): File {
        val file = File(cacheDir, "runtime-${bindings.fingerprint}-${state.key}.apk")
        if (file.isFile) return file
        val color = colorSource(state)
        val palette = MonetThemeRenderer.palette(color)
        val output = MonetThemeRenderer.render(
            bindings,
            palette,
            MonetThemeRenderer.Options(state.options.bubbleStyle, state.options.multiSceneCorners, state.options.errorColors),
            color,
        )
        val temp = File(cacheDir, ".${file.name}.tmp")
        MonetRuntimePackageWriter.write(temp, bindings.typeNames, output.colors, output.drawables, "m${state.key}")
        check(temp.renameTo(file)) { "failed to move $temp" }
        return file
    }

    private fun currentState(): ThemeState {
        // The M3 surface-container names only exist on API 34+; older wallpaper palettes are
        // reproduced from the wallpaper accent instead.
        val system = ThemeSettings.dynamicWallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        return ThemeState(
            source = if (system) SOURCE_SYSTEM else SOURCE_SEED,
            seed = if (system) 0 else SeedResolver.customSeed(HostInfo.application, false),
            paletteStyle = ThemeSettings.paletteStyle.name,
            colorSpec = ThemeSettings.effectiveColorSpec.name,
            options = options.value,
        )
    }

    /** Framework color name → encoded color (see [MonetThemeRenderer]). */
    @SuppressLint("DiscouragedApi")
    private fun colorSource(state: ThemeState): (String) -> Int? = if (state.source == SOURCE_SYSTEM) {
        { name -> Resources.getSystem().getIdentifier(name, "color", "android").takeIf { it != 0 } }
    } else {
        seedColors(state.seed)
    }

    private fun seedColors(seed: Int): (String) -> Int? {
        val light = SeedResolver.materialScheme(seed, false)
        val dark = SeedResolver.materialScheme(seed, true)
        val palettes = DynamicScheme(
            seedColor = Color(seed),
            isDark = false,
            style = ThemeSettings.paletteStyle.materialKolor,
            specVersion = ThemeSettings.effectiveColorSpec.materialKolor,
        )
        return { name ->
            val tonal = TONAL_NAME.matchEntire(name)
            if (tonal != null) {
                val palette = when (tonal.groupValues[1]) {
                    "accent1" -> palettes.primaryPalette
                    "accent2" -> palettes.secondaryPalette
                    "accent3" -> palettes.tertiaryPalette
                    "neutral1" -> palettes.neutralPalette
                    else -> palettes.neutralVariantPalette
                }
                SHADE_TONES[tonal.groupValues[2].toInt()]?.let(palette::tone)
            } else {
                val scheme = if (name.endsWith("_dark")) dark else light
                when (name.removePrefix("system_").removeSuffix("_light").removeSuffix("_dark")) {
                    "surface" -> scheme.surface
                    "surface_container" -> scheme.surfaceContainer
                    "surface_container_high" -> scheme.surfaceContainerHigh
                    "primary" -> scheme.primary
                    "primary_container" -> scheme.primaryContainer
                    "on_primary_container" -> scheme.onPrimaryContainer
                    else -> null
                }?.toArgb()
            }
        }
    }

    private fun surfaceColors(state: ThemeState, dark: Boolean): SurfaceColors {
        val color = colorSource(state)
        val palette = MonetThemeRenderer.palette(color)
        val mode = if (dark) "dark" else "light"
        fun on(role: String, background: Int): Int = color("system_${role}_$mode")?.let(::argb)
            ?: if (luminance(background) > 0.5f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        val primary = argb(if (dark) palette.primaryDark else palette.primaryLight)
        val primaryContainer = argb(if (dark) palette.primaryContainerDark else palette.primaryContainerLight)
        return SurfaceColors(
            page = argb(if (dark) palette.surfaceDark else palette.surfaceContainerLight),
            surface = argb(if (dark) palette.surfaceContainerDark else palette.surfaceLight),
            surfaceHigh = argb(if (dark) palette.surfaceContainerHighDark else palette.surfaceContainerHighLight),
            divider = argb(if (dark) palette.surfaceContainerHighDark else palette.surfaceContainerHighLight),
            primary = primary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = on("on_primary_container", primaryContainer),
        )
    }

    private fun luminance(argb: Int): Float =
        (0.2126f * (argb shr 16 and 0xff) + 0.7152f * (argb shr 8 and 0xff) + 0.0722f * (argb and 0xff)) / 255f

    private fun argb(value: Int): Int =
        if (value ushr 24 == 0x01) Resources.getSystem().getColor(value, null) else value

    @SuppressLint("SdCardPath")
    @RequiresApi(Build.VERSION_CODES.R)
    private fun hostApks(): List<String> {
        val info = HostInfo.appInfo
        return MonetAssetInjector.hostResourceApks(
            HostInfo.application.assets,
            listOf(info.sourceDir) + info.splitSourceDirs.orEmpty(),
            listOf(info.dataDir, "/data/data/${info.packageName}"),
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun resolveInBackground(
        apks: List<String>,
        fingerprint: String,
        onProgress: (MonetResolveProgress) -> Unit,
        onDone: (Throwable?) -> Unit,
    ) {
        resolving.value = true
        thread(name = "MonetResolverThread") {
            var current = MonetResolveProgress(MonetResolveStage.LOADING_APKS, "准备分析", 0, 1)
            val result = runCatching {
                MonetResourceResolver.resolve(apks.map(::File), fingerprint, MonetDexEvidenceCollector::collect) {
                    current = it
                    onProgress(it)
                }.also { MonetResourceResolver.store(cacheDir, it) }
            }
            postOnUiThread {
                resolving.value = false
                result.onSuccess { bindings ->
                    WeLogger.i(TAG, "resolved ${bindings.roles.size} roles, unresolved: ${bindings.unresolved}")
                    bindingsState.value = bindings
                }.onFailure {
                    WeLogger.e(TAG, "resolution failed during ${current.stage}: ${current.detail}", it)
                }
                Snapshot.sendApplyNotifications()
                onDone(result.exceptionOrNull()?.let { RuntimeException("${current.stage}: ${it.message}", it) })
            }
        }
    }

    /** Keeps only the current WeChat build's bindings and its most recent runtime tables. */
    private fun pruneCache(fingerprint: String) {
        val files = cacheDir.listFiles().orEmpty()
        files.filterNot { fingerprint in it.name }.forEach { it.deleteRecursively() }
        files.filter { it.name.startsWith("runtime-$fingerprint") }
            .sortedByDescending(File::lastModified)
            .drop(MAX_RUNTIME_TABLES)
            .forEach(File::delete)
    }

    private fun updateOptions(transform: Options.() -> Options) {
        val next = options.value.transform()
        options.value = next
        bubbleStylePref = next.bubbleStyle.name
        multiSceneCornersPref = next.multiSceneCorners
        errorColorsPref = next.errorColors
    }

    override fun onClick(context: ComponentActivity) {
        // Everything below reaches MonetAssetInjector, whose ResourcesLoader needs API 30.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            showUnsupportedDialog(context)
            return
        }
        showComposeDialog(context) {
            val current by options
            val bindings by bindingsState
            val isResolving by resolving
            AlertDialogContent(
                title = { Text(stringResource(R.string.monet_options_title)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        InfoTipCard(
                            text = stringResource(R.string.monet_color_source_summary),
                            noPadding = true,
                        )
                        SegmentedColumn(contentPadding = PaddingValues(top = 16.dp)) {
                            listOf(
                                MonetBubbleStyle.MODERN to R.string.monet_bubble_modern,
                                MonetBubbleStyle.CLASSIC to R.string.monet_bubble_classic,
                                MonetBubbleStyle.PRO to R.string.monet_bubble_pro,
                            ).forEach { (style, label) ->
                                item {
                                    RadioButtonWidget(
                                        title = stringResource(label),
                                        selected = current.bubbleStyle == style,
                                        onClick = { updateOptions { copy(bubbleStyle = style) } },
                                    )
                                }
                            }
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.monet_multi_scene_corners),
                                    checked = current.multiSceneCorners,
                                    onCheckedChange = { updateOptions { copy(multiSceneCorners = it) } },
                                )
                            }
                            item {
                                SwitchWidget(
                                    title = stringResource(R.string.monet_error_colors),
                                    checked = current.errorColors,
                                    onCheckedChange = { updateOptions { copy(errorColors = it) } },
                                )
                            }
                        }
                        SegmentedColumn(
                            title = stringResource(R.string.monet_status),
                            contentPadding = PaddingValues(top = 16.dp),
                        ) {
                            item {
                                BaseWidget(
                                    title = stringResource(R.string.monet_reresolve),
                                    description = when {
                                        isResolving -> stringResource(R.string.monet_status_running)
                                        bindings == null -> stringResource(R.string.monet_status_missing)
                                        else -> bindings!!.let {
                                            stringResource(
                                                R.string.monet_status_summary,
                                                it.roles.size,
                                                it.roles.size + it.unresolved.size,
                                            )
                                        }
                                    },
                                    enabled = !isResolving && isActive,
                                    onClick = { showResolveDialog(context) },
                                )
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    private fun showUnsupportedDialog(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_monet_engine_name)) },
                text = { Text(stringResource(R.string.monet_unsupported)) },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    /**
     * The only way analysis runs: first enable, re-analysis, and a WeChat update (missing bindings
     * at startup) all go through this dialog. Screens already open keep their old resources, so a
     * successful analysis asks for a WeChat restart.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun showResolveDialog(context: Context, onResolved: () -> Unit = {}) {
        showComposeDialog(context, directlyDismissable = false) {
            var progress by remember { mutableStateOf(MonetResolveProgress(MonetResolveStage.LOADING_APKS, "准备分析", 0, 1)) }
            var failure by remember { mutableStateOf<String?>(null) }
            val localizedContext = LocalWeKitLocalizedContext.current
            LaunchedEffect(Unit) {
                val apks = hostApks()
                resolveInBackground(
                    apks,
                    MonetResourceResolver.fingerprint(apks.map(::File)),
                    onProgress = { postOnUiThread { progress = it } },
                    onDone = { error ->
                        if (error == null) {
                            onDismiss()
                            onResolved()
                            showToast(localizedContext.getString(R.string.restart_wechat_to_apply))
                        } else {
                            failure = error.message ?: error.toString()
                        }
                    },
                )
            }
            AlertDialogContent(
                title = { Text(stringResource(R.string.monet_resolve_title)) },
                text = {
                    val message = failure
                    if (message != null) {
                        Text(stringResource(R.string.monet_resolve_failed, progress.detail, message))
                    } else {
                        ResolveProgress(progress)
                    }
                },
                dismissButton = if (failure != null) {
                    { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
                } else {
                    null
                },
            )
        }
    }

    private const val RENDER_VERSION = 3
    private const val SOURCE_SYSTEM = "system"
    private const val SOURCE_SEED = "seed"
    private const val MAX_RUNTIME_TABLES = 4
    private val TONAL_NAME = Regex("system_(accent[123]|neutral[12])_(\\d+)")
    private val SHADE_TONES = mapOf(
        0 to 100, 10 to 99, 50 to 95, 100 to 90, 200 to 80, 300 to 70, 400 to 60,
        500 to 50, 600 to 40, 700 to 30, 800 to 20, 900 to 10, 1000 to 0,
    )
}

@Composable
private fun ResolveProgress(progress: MonetResolveProgress) {
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
