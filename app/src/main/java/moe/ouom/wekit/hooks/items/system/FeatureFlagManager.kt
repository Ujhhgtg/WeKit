package moe.ouom.wekit.hooks.items.system

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.createInstance
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.IconButton
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.ToastUtils
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

@HookItem(path = "系统与隐私/灰度测试管理器", desc = "覆盖应用灰度测试 (Feature Flag) 的值")
object FeatureFlagManager : ClickableHookItem(), IResolvesDex {

    private val TAG = nameof(FeatureFlagManager)

    private const val KEY_HOOKED_FEATURE_FLAGS = "hooked_feature_flags"

    // explanation: i: int, f: float, l: long, s: string
    // example: "RepairerConfig_QuoteJumpOpt_Int;i;1"
    //          "RepairerConfig_TimelineAd_LandingPageHalfScreen_Int;i;1"
    private val classRepairerConfigBaseImpl by dexClass()
    private val methodRepairerConfigApiGet by dexMethod()

    sealed class FeatureFlagOverride(val internalName: String) {
        data class StringValue(val name: String, val value: String) : FeatureFlagOverride(name)
        data class IntValue(val name: String, val value: Int) : FeatureFlagOverride(name)
        data class FloatValue(val name: String, val value: Float) : FeatureFlagOverride(name)
        data class LongValue(val name: String, val value: Long) : FeatureFlagOverride(name)
    }

    private fun loadOverrides(): List<FeatureFlagOverride> {
        val flags = WePrefs.getStringSet(KEY_HOOKED_FEATURE_FLAGS, setOf())

        if (flags.isEmpty()) return emptyList()

        return flags.mapNotNull { argsStr ->
            val args = argsStr.split(',')
            if (args.size < 3) return@mapNotNull null

            val internalName = args[0]
            val type = args[1]
            val valueStr = args[2]

            when (type) {
                "i" -> FeatureFlagOverride.IntValue(internalName, valueStr.toInt())
                "s" -> FeatureFlagOverride.StringValue(internalName, valueStr)
                "f" -> FeatureFlagOverride.FloatValue(internalName, valueStr.toFloat())
                "l" -> FeatureFlagOverride.LongValue(internalName, valueStr.toLong())
                else -> null
            }
        }
    }

    private fun saveOverrides(overrides: List<FeatureFlagOverride>) {
        val strSet = overrides.map { override ->
            val typeChar = when (override) {
                is FeatureFlagOverride.IntValue -> "i"
                is FeatureFlagOverride.StringValue -> "s"
                is FeatureFlagOverride.FloatValue -> "f"
                is FeatureFlagOverride.LongValue -> "l"
            }

            val value = when (override) {
                is FeatureFlagOverride.IntValue -> override.value
                is FeatureFlagOverride.StringValue -> override.value
                is FeatureFlagOverride.FloatValue -> override.value
                is FeatureFlagOverride.LongValue -> override.value
            }

            // 拼接成 "internalName,type,value"
            "${override.internalName},$typeChar,$value"
        }.toSet()
        WePrefs.putStringSet(KEY_HOOKED_FEATURE_FLAGS, strSet)
    }

    // FIXME: currently, to prevent lag, overrides are loaded only once, so we have to restart host app for changes to take effect
    private val overrides by lazy { loadOverrides() }
    override fun onEnable() {
        methodRepairerConfigApiGet.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val key = param.args[0]
                    val override =
                        overrides.firstOrNull { it.internalName == key } ?: return@beforeIfEnabled
                    param.result = when (override) {
                        is FeatureFlagOverride.FloatValue -> override.value
                        is FeatureFlagOverride.IntValue -> override.value
                        is FeatureFlagOverride.LongValue -> override.value
                        is FeatureFlagOverride.StringValue -> override.value
                    }
                }
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        classRepairerConfigBaseImpl.find(dexKit, descriptors) {
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

        methodRepairerConfigApiGet.find(dexKit, descriptors) {
            matcher {
                declaredClass {
                    usingEqStrings("RepairerConfigThread", "ValueStrategy_")
                }
                usingEqStrings("String", "Int", "Long", "Float", "key", "defaultValue")
                paramTypes(String::class.java, Any::class.java)
                returnType(Any::class.java)
            }
        }

        return descriptors
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var isLoading by remember { mutableStateOf(true) }
            var featureFlagClasses by remember { mutableStateOf<List<String>>(emptyList()) }
            // 1. 搜索状态
            var searchQuery by remember { mutableStateOf("") }

            // 过滤后的列表
            val filteredClasses = remember(searchQuery, featureFlagClasses) {
                if (searchQuery.isEmpty()) featureFlagClasses
                else featureFlagClasses.filter { it.contains(searchQuery, ignoreCase = true) }
            }

            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    val bridge = DexKitBridge.create(context.applicationInfo.sourceDir)
                    val superClassName = classRepairerConfigBaseImpl.clazz.name

                    val results = bridge.findClass {
                        matcher {
                            superClass {
                                superClass {
                                    superClass = superClassName
                                }
                            }
                            modifiers(Modifier.FINAL)
                        }
                    }
                    featureFlagClasses = results.map { it.name }.sorted()
                    bridge.close()
                    isLoading = false
                }
            }

            AlertDialogContent(
                title = { Text("灰度测试管理器") },
                text = {
                    Column(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 500.dp)
                    ) {
                        if (isLoading) {
                            Box(
                                androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularWavyProgressIndicator()
                                    Spacer(
                                        modifier = androidx.compose.ui.Modifier.height(
                                            8.dp
                                        )
                                    )
                                    Text("正在扫描灰度测试类, 请稍等...")
                                }
                            }
                        } else {
                            // 2. 搜索框
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = androidx.compose.ui.Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                placeholder = { Text("搜索类名...") },
                                singleLine = true,
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = {
                                            searchQuery = ""
                                        }) {
                                            Text("×") // 简单的清除按钮
                                        }
                                    }
                                }
                            )

                            if (filteredClasses.isEmpty()) {
                                Box(
                                    androidx.compose.ui.Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "未找到匹配的类",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = androidx.compose.ui.Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    items(filteredClasses) { className ->
                                        var showActionDialog by remember { mutableStateOf(false) }

                                        // 操作选项对话框
                                        if (showActionDialog) {
                                            var showOverrideDialog by remember {
                                                mutableStateOf(
                                                    false
                                                )
                                            }

                                            var internalName by remember { mutableStateOf("null") }
                                            var description by remember { mutableStateOf("") }
                                            var configKey by remember { mutableStateOf("null") }
                                            LaunchedEffect(Unit) {
                                                val flagInstance =
                                                    className.toClass().createInstance()
                                                flagInstance
                                                    .asResolver()
                                                    .method {
                                                        returnType = String::class
                                                    }.apply {
                                                        internalName = this[0].invoke()!! as String
                                                        description = this[1].invoke()!! as String
                                                        for (m in this) {
                                                            val str = m.invoke()!! as String
                                                            if (str.startsWith("clicfg")) {
                                                                configKey = str
                                                            }
                                                        }
                                                    }
                                            }

                                            AlertDialog(
                                                onDismissRequest = { showActionDialog = false },
                                                title = {
                                                    Text(
                                                        text = className.substringAfterLast('.'),
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                },
                                                text = {
                                                    Column(
                                                        modifier = androidx.compose.ui.Modifier
                                                            .fillMaxWidth()
                                                            .clip(MaterialTheme.shapes.large)
                                                    ) {
                                                        ListItem(
                                                            headlineContent = {
                                                                Text(
                                                                    "复制完整类名",
                                                                    style = MaterialTheme.typography.bodyLarge
                                                                )
                                                            },
                                                            supportingContent = { Text(className) },
                                                            modifier = androidx.compose.ui.Modifier.clickable {
                                                                val clipboard =
                                                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                clipboard.setPrimaryClip(
                                                                    ClipData.newPlainText(
                                                                        "ClassName",
                                                                        className
                                                                    )
                                                                )
                                                            })

                                                        ListItem(
                                                            headlineContent = {
                                                                Text(
                                                                    "复制功能内部名称",
                                                                    style = MaterialTheme.typography.bodyLarge
                                                                )
                                                            },
                                                            supportingContent = { Text(internalName) },
                                                            modifier = androidx.compose.ui.Modifier.clickable {
                                                                val clipboard =
                                                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                clipboard.setPrimaryClip(
                                                                    ClipData.newPlainText(
                                                                        "InternalName",
                                                                        internalName
                                                                    )
                                                                )
                                                            })

                                                        ListItem(
                                                            headlineContent = {
                                                                Text(
                                                                    "复制功能简介",
                                                                    style = MaterialTheme.typography.bodyLarge
                                                                )
                                                            },
                                                            supportingContent = { Text(description) },
                                                            modifier = androidx.compose.ui.Modifier.clickable {
                                                                val clipboard =
                                                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                clipboard.setPrimaryClip(
                                                                    ClipData.newPlainText(
                                                                        "Description",
                                                                        description
                                                                    )
                                                                )
                                                            })

                                                        ListItem(
                                                            headlineContent = {
                                                                Text(
                                                                    "复制配置键名",
                                                                    style = MaterialTheme.typography.bodyLarge
                                                                )
                                                            },
                                                            supportingContent = { Text(configKey) },
                                                            modifier = androidx.compose.ui.Modifier.clickable {
                                                                val clipboard =
                                                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                                clipboard.setPrimaryClip(
                                                                    ClipData.newPlainText(
                                                                        "ConfigKey",
                                                                        configKey
                                                                    )
                                                                )
                                                            })

                                                        ListItem(
                                                            headlineContent = {
                                                                Text(
                                                                    "覆盖功能取值",
                                                                    style = MaterialTheme.typography.bodyLarge
                                                                )
                                                            },
                                                            supportingContent = { Text("为该灰度测试项覆盖其当前取值") },
                                                            modifier = androidx.compose.ui.Modifier.clickable {
                                                                showOverrideDialog = true
                                                            })
                                                    }
                                                },
                                                confirmButton = {
                                                    TextButton(onClick = {
                                                        showActionDialog = false
                                                    }) {
                                                        Text("取消")
                                                    }
                                                }
                                            )

                                            if (showOverrideDialog) {
                                                var type by remember { mutableStateOf("") }
                                                var rawValue by remember { mutableStateOf("") }

                                                LaunchedEffect(Unit) {
                                                    val overrides = loadOverrides()
                                                    val override =
                                                        overrides.firstOrNull { o -> o.internalName == internalName }
                                                            ?: run {
                                                                WeLogger.w(
                                                                    TAG,
                                                                    "override not found for $internalName"
                                                                )
                                                                return@LaunchedEffect
                                                            }
                                                    when (override) {
                                                        is FeatureFlagOverride.FloatValue -> {
                                                            type = "f"
                                                            rawValue = override.value.toString()
                                                        }

                                                        is FeatureFlagOverride.StringValue -> {
                                                            type = "s"
                                                            rawValue = override.value
                                                        }

                                                        is FeatureFlagOverride.IntValue -> {
                                                            type = "i"
                                                            rawValue = override.value.toString()
                                                        }

                                                        is FeatureFlagOverride.LongValue -> {
                                                            type = "l"
                                                            rawValue = override.value.toString()
                                                        }
                                                    }
                                                }

                                                // TODO: refine this UI
                                                AlertDialog(
                                                    onDismissRequest = {
                                                        showOverrideDialog = false
                                                    },
                                                    title = { Text("设置覆盖值") },
                                                    text = {
                                                        Column {
                                                            TextField(
                                                                value = type,
                                                                onValueChange = { type = it },
                                                                singleLine = true,
                                                                label = { Text("类型 ([s]tring/[f]loat/[i]nt/[l]ong)") })
                                                            Spacer(
                                                                androidx.compose.ui.Modifier.height(
                                                                    8.dp
                                                                )
                                                            )
                                                            TextField(
                                                                value = rawValue,
                                                                onValueChange = { rawValue = it },
                                                                singleLine = true,
                                                                label = { Text("值") })
                                                        }
                                                    },
                                                    dismissButton = {
                                                        TextButton(onClick = {
                                                            showOverrideDialog = false
                                                        }) { Text("取消") }
                                                        TextButton(onClick = {
                                                            val overrides =
                                                                loadOverrides().toMutableList()
                                                            val existingIndex =
                                                                overrides.indexOfFirst { o -> o.internalName == internalName }
                                                            if (existingIndex == -1) {
                                                                WeLogger.w(
                                                                    TAG,
                                                                    "tried to remove override for $internalName but not found"
                                                                )
                                                                ToastUtils.showToast("未找到该灰度测试的覆盖值!")
                                                                return@TextButton
                                                            }

                                                            WeLogger.i(
                                                                TAG,
                                                                "override found for $internalName, removing it"
                                                            )
                                                            overrides.removeAt(existingIndex)
                                                            saveOverrides(overrides)
                                                            showOverrideDialog = false
                                                        }) { Text("清除") }
                                                    },
                                                    confirmButton = {
                                                        Button(onClick = {
                                                            val override = when (type) {
                                                                "s", "string" -> FeatureFlagOverride.StringValue(
                                                                    internalName,
                                                                    rawValue
                                                                )

                                                                "i", "int" -> FeatureFlagOverride.IntValue(
                                                                    internalName,
                                                                    rawValue.toIntOrNull() ?: run {
                                                                        ToastUtils.showToast("值格式不正确, 请重新输入")
                                                                        return@Button
                                                                    })

                                                                "l", "long" -> FeatureFlagOverride.LongValue(
                                                                    internalName,
                                                                    rawValue.toLongOrNull() ?: run {
                                                                        ToastUtils.showToast("值格式不正确, 请重新输入")
                                                                        return@Button
                                                                    })

                                                                "f", "float" -> FeatureFlagOverride.FloatValue(
                                                                    internalName,
                                                                    rawValue.toFloatOrNull()
                                                                        ?: run {
                                                                            ToastUtils.showToast("值格式不正确, 请重新输入")
                                                                            return@Button
                                                                        })

                                                                else -> {
                                                                    ToastUtils.showToast("类型格式不正确, 请重新输入")
                                                                    return@Button
                                                                }
                                                            }
                                                            val overrides =
                                                                loadOverrides().toMutableList()
                                                            val existingIndex =
                                                                overrides.indexOfFirst { o -> o.internalName == internalName }
                                                            if (existingIndex == -1) {
                                                                WeLogger.i(
                                                                    TAG,
                                                                    "override not found for $internalName, adding new one"
                                                                )
                                                                overrides.add(override)
                                                            } else {
                                                                WeLogger.i(
                                                                    TAG,
                                                                    "override found for $internalName, modifying it"
                                                                )
                                                                overrides[existingIndex] = override
                                                            }
                                                            saveOverrides(overrides)
                                                            showOverrideDialog = false
                                                        }) { Text("确定") }
                                                    })
                                            }
                                        }

                                        // 列表条目
                                        Text(
                                            text = className.substringAfterLast('.'),
                                            modifier = androidx.compose.ui.Modifier
                                                .fillMaxWidth()
                                                .clickable { showActionDialog = true }
                                                .padding(vertical = 12.dp),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        HorizontalDivider(
                                            androidx.compose.ui.Modifier.alpha(
                                                0.3f
                                            )
                                        )
                                    }
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
    }
}