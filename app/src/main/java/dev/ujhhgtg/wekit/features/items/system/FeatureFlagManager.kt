package dev.ujhhgtg.wekit.features.items.system

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.system.FeatureFlagManager.cacheLock
import dev.ujhhgtg.wekit.features.items.system.FeatureFlagManager.markCacheDirty
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.reflection.withDexKit
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import org.luckypray.dexkit.query.matchers.ClassMatcher
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.lang.reflect.Modifier as JavaModifier

/**
 * WeChat feature flag type names, matching [ly4.e.h()] return values.
 *
 * WeChat's obfuscated class hierarchy for feature flags (verified at 8.0.69):
 * - [ly4.h] root abstract class: [b]()=name, [c]()=desc, [e]()=group
 * - [ly4.e] extends [h]: [h]()=typeName("Int" default), [i]()=defaultValue, [l]()=b() + '_' + h()
 * - [ly4.d] extends [e]: [n]()=labels, [o]()=values
 * - [ly4.i] extends [d]: concrete boolean-like (Int, labels=["关闭","打开"], values=["0","1"])
 * - [ly4.f] extends [e]: h()="String"
 * - [ly4.g] extends [h]: empty
 *
 * API entry: [fd5.d1].[b](String key, Object defaultValue) — central get method.
 * Key format: fullKey = b() + '_' + h()    (via [ly4.e.l])
 */
@Feature(name = "灰度测试管理器", categories = ["系统与隐私"], description = "覆盖微信灰度测试 (Feature Flag) 的值")
object FeatureFlagManager : ClickableFeature(), IResolveDex {

    private val overridesFile by lazy { KnownPaths.moduleData / "feature_flag_overrides.json" }

    /**
     * Base class for all feature flags: [ly4.e] (verified from WeChat 8.0.69).
     * Identified by its [i]() method containing "Int", "Float", "String", "Long", "".
     * Hierarchy: [ly4.h]->[ly4.e]->[ly4.d]->[ly4.i] (Int) or [ly4.e]->[ly4.f] (String).
     */
    private val classFeatureFlagBase by dexClass {
        matcher {
            addMethod {
                usingEqStrings("Int")
            }
            addMethod {
                usingEqStrings("Int", "Float", "String", "Long", "")
            }
            addMethod {
                usingEqStrings("")
            }
        }
    }

    /**
     * Central API method: [fd5.d1].[b](String, Object) -> Object.
     * Key format: splits input key by '_', uses last segment for type dispatch.
     * Type names: "Int", "Float", "Long", "String" — matched by last _-segment.
     */
    private val methodRepairerConfigApiGet by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("RepairerConfigThread", "ValueStrategy_")
            }
            usingEqStrings("String", "Int", "Long", "Float", "key", "defaultValue")
            paramTypes(String::class.java, Any::class.java)
            returnType(Any::class.java)
        }
    }

    // ---------------------------------------------------------------------------
    // Flag details data model & reflective resolver
    // ---------------------------------------------------------------------------

    private data class FlagDetails(
        val internalName: String = "",
        val description: String = "(无)",
        val typeName: String = "",
        val configKey: String = ""
    )

    private fun resolveFlagDetails(className: String): FlagDetails {
        return runCatching {
            var internalName = ""
            var description = ""
            var typeName = ""
            var configKey = ""

            val flagInstance = className.toClass().createInstance()
            val methods = flagInstance.reflekt().methods { returnType = String::class }

            if (methods.isNotEmpty()) internalName = methods[0].invoke() as? String ?: ""
            if (methods.size > 1) description = methods[1].invoke() as? String ?: ""
            if (methods.size > 2) typeName = methods[2].invoke() as? String ?: ""

            for (method in methods) {
                val str = method.invoke() as? String ?: continue
                if (str.startsWith("clicfg")) {
                    configKey = str
                }
            }

            FlagDetails(
                internalName = internalName,
                description = description.ifBlank { "(无)" },
                typeName = typeName,
                configKey = configKey
            )
        }.getOrElse { e ->
            WeLogger.e(TAG, "failed to instantiate or inspect $className", e)
            FlagDetails(description = "(无)")
        }
    }

    // ---------------------------------------------------------------------------
    // Override data model
    // ---------------------------------------------------------------------------

    @Serializable
    private data class FeatureFlagOverride(
        val runtimeKey: String,
        val internalType: String,  // "i"|"f"|"l"|"s"
        val rawValue: String
    ) {
        /** The runtime value to set as hook result. */
        val value: Any
            get() = when (internalType) {
                "i" -> rawValue.toInt()
                "f" -> rawValue.toFloat()
                "l" -> rawValue.toLong()
                "s" -> rawValue
                else -> error("Unknown override type: $internalType")
            }
    }

    // ---------------------------------------------------------------------------
    // Override persistence
    // ---------------------------------------------------------------------------

    /**
     * Load overrides from JSON file.
     */
    private fun loadOverrides(): Map<String, FeatureFlagOverride> {
        val file = overridesFile
        if (!file.exists()) return emptyMap()
        return runCatching {
            val list = DefaultJson.decodeFromString<List<FeatureFlagOverride>>(file.readText())
            list.associateBy { it.runtimeKey }
        }.getOrElse { e ->
            WeLogger.e(TAG, "failed to load $overridesFile", e)
            emptyMap()
        }
    }

    /**
     * Persist overrides, then mark cache as dirty.
     */
    private fun saveOverrides(overrides: List<FeatureFlagOverride>) {
        saveOverridesRaw(overrides)
        markCacheDirty()
    }

    private fun saveOverridesRaw(overrides: List<FeatureFlagOverride>) {
        runCatching {
            overridesFile.writeText(DefaultJson.encodeToString(overrides))
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to save $overridesFile", e)
        }
    }

    // ---------------------------------------------------------------------------
    // Live-reloadable override cache
    // ---------------------------------------------------------------------------

    @Volatile
    private var overridesCache: Map<String, FeatureFlagOverride>? = null
    private val cacheLock = Any()

    /**
     * Returns the override map, loading it on first use after a [markCacheDirty].
     *
     * The load itself must happen under [cacheLock]: this is called from the central flag getter
     * hook, which WeChat invokes concurrently from many threads during startup. If the load throws,
     * nothing is cached, so the next caller simply retries instead of latching a broken state.
     */
    private fun getOverrides(): Map<String, FeatureFlagOverride> {
        overridesCache?.let { return it }
        return synchronized(cacheLock) {
            overridesCache ?: loadOverrides().also { overridesCache = it }
        }
    }

    private fun markCacheDirty() {
        synchronized(cacheLock) {
            overridesCache = null
        }
    }

    // ---------------------------------------------------------------------------
    // Hook
    // ---------------------------------------------------------------------------

    override fun onEnable() {
        methodRepairerConfigApiGet.hookBefore {
            val key = args[0] as? String ?: return@hookBefore
            val override = getOverrides()[key] ?: return@hookBefore
            result = override.value
        }
    }

    // ---------------------------------------------------------------------------
    // UI — Dialog
    // ---------------------------------------------------------------------------

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            FeatureFlagManagerDialog(onDismiss = onDismiss)
        }
    }

    // =====================================================================
    // Composable UI
    // =====================================================================

    @Composable
    private fun FeatureFlagManagerDialog(onDismiss: () -> Unit) {
        var isLoading by remember { mutableStateOf(true) }
        var featureFlagClasses by remember { mutableStateOf<List<String>>(emptyList()) }
        var searchQuery by remember { mutableStateOf("") }

        val detailsMap = remember { mutableStateMapOf<String, FlagDetails>() }
        val listState = rememberLazyListState()

        var selectedClassName by remember { mutableStateOf<String?>(null) }
        var isOverrideDialogOpen by remember { mutableStateOf(false) }

        // Dynamic reactive filtering: checks class name and description (with auto-update on detailsMap changes)
        val filteredClasses by remember(searchQuery, featureFlagClasses) {
            derivedStateOf {
                if (searchQuery.isBlank()) {
                    featureFlagClasses
                } else {
                    val query = searchQuery.trim()
                    featureFlagClasses.filter { className ->
                        className.contains(query, ignoreCase = true) ||
                                detailsMap[className]?.description?.contains(query, ignoreCase = true) == true
                    }
                }
            }
        }

        // DexKit class scanning
        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val superClassName = classFeatureFlagBase.clazz.name

                featureFlagClasses = withDexKit { dexKit ->
                    dexKit.findClass {
                        matcher {
                            modifiers(JavaModifier.FINAL)
                            anyOf(
                                ClassMatcher().apply {
                                    superClass { superClass = superClassName }
                                },
                                ClassMatcher().apply {
                                    superClass { superClass { superClass = superClassName } }
                                }
                            )
                        }
                    }.map { it.name }.sorted()
                }
                isLoading = false
            }
        }

        // Background lazy loading with viewport priority and dialog pause support
        LaunchedEffect(isLoading) {
            if (isLoading) return@LaunchedEffect

            withContext(Dispatchers.IO) {
                while (detailsMap.size < featureFlagClasses.size) {
                    if (selectedClassName != null || isOverrideDialogOpen) {
                        snapshotFlow { selectedClassName == null && !isOverrideDialogOpen }.first { it }
                    }

                    val targetClass = withContext(Dispatchers.Main.immediate) {
                        val visibleIndices = listState.layoutInfo.visibleItemsInfo.map { it.index }
                        val visibleClasses = visibleIndices.mapNotNull { filteredClasses.getOrNull(it) }

                        visibleClasses.firstOrNull { !detailsMap.containsKey(it) }
                            ?: filteredClasses.firstOrNull { !detailsMap.containsKey(it) }
                            ?: featureFlagClasses.firstOrNull { !detailsMap.containsKey(it) }
                    }

                    if (targetClass == null) break

                    val details = resolveFlagDetails(targetClass)

                    withContext(Dispatchers.Main.immediate) {
                        detailsMap[targetClass] = details
                    }
                    yield()
                }
            }
        }

        val context = LocalContext.current

        AlertDialogContent(
            title = { Text("灰度测试管理器") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 500.dp)
                ) {
                    if (isLoading) {
                        LoadingView()
                    } else {
                        SearchBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            onClear = { searchQuery = "" }
                        )
                        when {
                            filteredClasses.isEmpty() && featureFlagClasses.isEmpty() -> {
                                EmptyState()
                            }

                            filteredClasses.isEmpty() -> {
                                NoMatchState()
                            }

                            else -> {
                                FlagList(
                                    classNames = filteredClasses,
                                    listState = listState,
                                    detailsMap = detailsMap,
                                    onItemClick = { className ->
                                        selectedClassName = className
                                        showComposeDialog(context) {
                                            FlagActionDialog(
                                                className = className,
                                                initialDetails = detailsMap[className],
                                                onOpenOverrideDialog = { runtimeKey, typeName ->
                                                    isOverrideDialogOpen = true
                                                    showComposeDialog(context) {
                                                        OverrideValueDialog(
                                                            runtimeKey = runtimeKey,
                                                            typeName = typeName,
                                                            onDismiss = {
                                                                this.onDismiss()
                                                                isOverrideDialogOpen = false
                                                            }
                                                        )
                                                    }
                                                },
                                                onDismiss = {
                                                    this.onDismiss()
                                                    selectedClassName = null
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        )
    }

    @Composable
    private fun ColumnScope.LoadingView() {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularWavyProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("正在扫描灰度测试类, 请稍等...")
            }
        }
    }

    @Composable
    private fun ColumnScope.EmptyState() {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center
        ) {
            Text("未找到灰度测试类", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun ColumnScope.NoMatchState() {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f), contentAlignment = Alignment.Center
        ) {
            Text("未找到匹配的类或功能", style = MaterialTheme.typography.bodyMedium)
        }
    }

    @Composable
    private fun SearchBar(
        query: String,
        onQueryChange: (String) -> Unit,
        onClear: () -> Unit
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            placeholder = { Text("搜索类名或功能简介...") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Text("×")
                    }
                }
            }
        )
    }

    @Composable
    private fun ColumnScope.FlagList(
        classNames: List<String>,
        listState: LazyListState,
        detailsMap: Map<String, FlagDetails>,
        onItemClick: (String) -> Unit
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(classNames, key = { it }) { className ->
                FlagListItem(
                    modifier = Modifier.animateItem(),
                    className = className,
                    details = detailsMap[className],
                    onClick = { onItemClick(className) }
                )
            }
        }
    }

    @Composable
    private fun FlagListItem(
        className: String,
        details: FlagDetails?,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        ListItem(
            modifier = modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            content = {
                Text(
                    text = className.substringAfterLast('.'),
                    style = MaterialTheme.typography.bodyLarge
                )
            },
            supportingContent = {
                Text(
                    text = details?.description ?: "加载中...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
        HorizontalDivider(Modifier.alpha(0.3f))
    }

    @Composable
    private fun FlagActionDialog(
        className: String,
        initialDetails: FlagDetails?,
        onOpenOverrideDialog: (runtimeKey: String, typeName: String) -> Unit,
        onDismiss: () -> Unit
    ) {
        var details by remember { mutableStateOf(initialDetails) }

        LaunchedEffect(Unit) {
            if (details == null) {
                withContext(Dispatchers.IO) {
                    details = resolveFlagDetails(className)
                }
            }
        }

        val currentDetails = details ?: FlagDetails()
        val internalName = currentDetails.internalName
        val description = currentDetails.description
        val typeName = currentDetails.typeName
        val configKey = currentDetails.configKey

        val effectiveTypeName = typeName.ifEmpty { "Int" }
        val effectiveName = internalName.ifEmpty { configKey }

        val runtimeKey = if (effectiveName.isNotEmpty()) {
            "${effectiveName}_${effectiveTypeName}"
        } else null

        AlertDialogContent(
            title = {
                Text(
                    text = className.substringAfterLast('.'),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                ) {
                    CopyInfoItem("复制完整类名", className)
                    CopyInfoItem("复制功能内部名称", internalName)
                    CopyInfoItem("复制功能简介", description)
                    CopyInfoItem("复制配置键名", configKey)

                    ListItem(
                        modifier = Modifier.clickable {
                            if (runtimeKey == null) {
                                showToast("无法解析该灰度测试项的键名")
                                return@clickable
                            }
                            onOpenOverrideDialog(runtimeKey, effectiveTypeName)
                        },
                        supportingContent = { Text("为该灰度测试项覆盖其当前取值") },
                        content = {
                            Text("覆盖功能取值", style = MaterialTheme.typography.bodyLarge)
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        )
    }

    @Composable
    private fun CopyInfoItem(label: String, value: String) {
        if (value.isEmpty()) return
        val context = LocalContext.current
        ListItem(
            modifier = Modifier.clickable { copyToClipboard(context, value) },
            supportingContent = { Text(value) },
            content = { Text(label, style = MaterialTheme.typography.bodyLarge) },
        )
    }

    private const val TAG = "FeatureFlagManager"

    @Composable
    private fun OverrideValueDialog(
        runtimeKey: String,
        typeName: String,
        onDismiss: () -> Unit
    ) {
        // Map WeChat type name → internal type char
        val defaultTypeChar = when (typeName.ifEmpty { "Int" }) {
            "Int" -> "i"
            "Float" -> "f"
            "Long" -> "l"
            "String" -> "s"
            else -> "i"  // fallback
        }

        val existingOverride = remember {
            getOverrides()[runtimeKey]
        }

        var type by remember { mutableStateOf(existingOverride?.internalType ?: defaultTypeChar) }
        var rawValue by remember { mutableStateOf(existingOverride?.rawValue ?: "") }

        AlertDialogContent(
            title = { Text("设置覆盖值") },
            text = {
                Column {
                    TextField(
                        value = type,
                        onValueChange = { type = it },
                        singleLine = true,
                        label = { Text("类型 ([s]tring/[f]loat/[i]nt/[l]ong)") }
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = rawValue,
                        onValueChange = { rawValue = it },
                        singleLine = true,
                        label = { Text("值") }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
                TextButton(onClick = {
                    val overrides = loadOverrides().values.toMutableList()
                    val existingIndex = overrides.indexOfFirst { it.runtimeKey == runtimeKey }
                    if (existingIndex == -1) {
                        WeLogger.i(TAG, "override not found for $runtimeKey, nothing to clear")
                        showToast("未找到该灰度测试的覆盖值!")
                        return@TextButton
                    }
                    WeLogger.i(TAG, "removing override for $runtimeKey")
                    overrides.removeAt(existingIndex)
                    saveOverrides(overrides)
                    onDismiss()
                }) { Text("清除") }
            },
            confirmButton = {
                Button(onClick = {
                    val rawValueStr = rawValue
                    // Validate value based on type
                    val validated = when (type) {
                        "s", "string" -> FeatureFlagOverride(runtimeKey, "s", rawValueStr)
                        "i", "int" -> {
                            val v = rawValueStr.toIntOrNull()
                            if (v == null) {
                                showToast("值格式不正确, 请重新输入")
                                return@Button
                            }
                            FeatureFlagOverride(runtimeKey, "i", rawValueStr)
                        }

                        "l", "long" -> {
                            val v = rawValueStr.toLongOrNull()
                            if (v == null) {
                                showToast("值格式不正确, 请重新输入")
                                return@Button
                            }
                            FeatureFlagOverride(runtimeKey, "l", rawValueStr)
                        }

                        "f", "float" -> {
                            val v = rawValueStr.toFloatOrNull()
                            if (v == null) {
                                showToast("值格式不正确, 请重新输入")
                                return@Button
                            }
                            FeatureFlagOverride(runtimeKey, "f", rawValueStr)
                        }

                        else -> {
                            showToast("类型格式不正确, 请重新输入")
                            return@Button
                        }
                    }

                    val overrides = loadOverrides().values.toMutableList()
                    val existingIndex = overrides.indexOfFirst { it.runtimeKey == runtimeKey }
                    if (existingIndex == -1) {
                        WeLogger.i(TAG, "adding new override for $runtimeKey")
                        overrides.add(validated)
                    } else {
                        WeLogger.i(TAG, "updating override for $runtimeKey")
                        overrides[existingIndex] = validated
                    }
                    saveOverrides(overrides)
                    onDismiss()
                }) { Text("确定") }
            }
        )
    }
}
