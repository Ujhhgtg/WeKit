package moe.ouom.wekit.ui.content

import android.content.Context
import android.text.InputType
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.ModuleRes
import moe.ouom.wekit.utils.logging.WeLogger

// ---------------------------------------------------------------------------
//  Internal state model
// ---------------------------------------------------------------------------

private sealed class PrefRow {
    /** Stable key used to address this row in dependency maps. */
    abstract val rowKey: String

    data class Category(override val rowKey: String, val title: String) : PrefRow()

    data class Switch(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val summary: String,
        val iconName: String?,
    ) : PrefRow()

    data class EditText(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val baseSummary: String,
        val defaultValue: String,
        val hint: String?,
        val inputType: Int,
        val maxLength: Int,
        val singleLine: Boolean,
        val iconName: String?,
        val summaryFormatter: ((String) -> String)?,
    ) : PrefRow()

    data class Select(
        override val rowKey: String,
        val configKey: String,
        val title: String,
        val baseSummary: String,
        val options: Map<Int, String>,
        val defaultValue: Int,
        val iconName: String?,
    ) : PrefRow()

    data class Plain(
        override val rowKey: String,
        val title: String,
        val summary: String?,
        val iconName: String?,
        val onClick: (() -> Unit)?,
    ) : PrefRow()
}

private data class DepInfo(
    val dependentRowKey: String,
    val enableWhen: Boolean,
    val hideWhenDisabled: Boolean,
)

// ---------------------------------------------------------------------------
//  Abstract base class – public API is identical to the original
// ---------------------------------------------------------------------------

abstract class BasePrefDialog(
    protected val context: Context,
    private val title: String,
) {
    // -----------------------------------------------------------------------
    //  Internal mutable state – built up during initPreferences()
    // -----------------------------------------------------------------------

    private val rows = mutableListOf<PrefRow>()
    private val dependencies = mutableMapOf<String, MutableList<DepInfo>>() // configKey -> deps
    private var rowCounter = 0

    // Compose state holders – set when the Compose UI is active
    private var _dismissCallback: (() -> Unit)? = null

    // -----------------------------------------------------------------------
    //  Public abstract / lifecycle
    // -----------------------------------------------------------------------

    abstract fun initPreferences()

    // -----------------------------------------------------------------------
    //  show / dismiss – mirrors the AppCompatDialog API
    // -----------------------------------------------------------------------

    open fun show() {
        rows.clear()
        dependencies.clear()
        rowCounter = 0
        initPreferences()

        showComposeDialog(context) {
            _dismissCallback = onDismiss
            DialogContent(
                title = title,
                rows = rows,
                dependencies = dependencies,
                onDismiss = {
                    _dismissCallback?.invoke()
                    _dismissCallback = null
                }
            )
        }
    }

    // -----------------------------------------------------------------------
    //  Public builder methods (same signatures as the original)
    // -----------------------------------------------------------------------

    protected fun addCategory(title: String) {
        rows += PrefRow.Category(rowKey = nextKey("cat"), title = title)
    }

    protected fun addSwitchPreference(
        key: String,
        title: String,
        summary: String,
        iconName: String? = null,
    ): String {
        val rk = nextKey("sw_$key")
        rows += PrefRow.Switch(rk, key, title, summary, iconName)
        return rk
    }

    protected fun addEditTextPreference(
        key: String,
        title: String,
        summary: String,
        defaultValue: String = "",
        hint: String? = null,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        maxLength: Int = 0,
        singleLine: Boolean = true,
        iconName: String? = null,
        summaryFormatter: ((String) -> String)? = null,
    ) {
        val rk = nextKey("et_$key")
        rows += PrefRow.EditText(
            rowKey = rk, configKey = key,
            title = title, baseSummary = summary,
            defaultValue = defaultValue, hint = hint,
            inputType = inputType, maxLength = maxLength,
            singleLine = singleLine, iconName = iconName,
            summaryFormatter = summaryFormatter,
        )
    }

    protected fun addSelectPreference(
        key: String,
        title: String,
        summary: String,
        options: Map<Int, String>,
        defaultValue: Int,
        iconName: String? = null,
    ) {
        val rk = nextKey("sel_$key")
        rows += PrefRow.Select(rk, key, title, summary, options, defaultValue, iconName)
    }

    protected fun addPreference(
        title: String,
        summary: String? = null,
        iconName: String? = null,
        onClick: (() -> Unit)? = null,
    ) {
        val rk = nextKey("pref_$title")
        rows += PrefRow.Plain(rk, title, summary, iconName, onClick)
    }

    protected fun setDependency(
        dependentKey: String,
        dependencyKey: String,
        enableWhen: Boolean = true,
        hideWhenDisabled: Boolean = false,
    ) {
        dependencies
            .getOrPut(dependencyKey) { mutableListOf() }
            .add(DepInfo(dependentKey, enableWhen, hideWhenDisabled))
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private fun nextKey(base: String) = "${base}_${rowCounter++}"
}

// ---------------------------------------------------------------------------
//  Compose UI
// ---------------------------------------------------------------------------

private const val TAG = "BasePrefDialog"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DialogContent(
    title: String,
    rows: List<PrefRow>,
    dependencies: Map<String, List<DepInfo>>,
    onDismiss: () -> Unit,
) {
    // Per-switch live state: configKey -> checked
    val switchStates = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            rows.filterIsInstance<PrefRow.Switch>().forEach { row ->
                map[row.configKey] = WePrefs.getBoolOrFalse(row.configKey)
            }
        }
    }

    // Per-edittext / select live summary state: configKey -> display string
    val summaryStates = remember {
        mutableStateMapOf<String, String>().also { map ->
            rows.filterIsInstance<PrefRow.EditText>().forEach { row ->
                val v = WePrefs.getStringOrDef(row.configKey, row.defaultValue)
                map[row.configKey] = row.summaryFormatter?.invoke(v)
                    ?: if (v.isEmpty()) row.baseSummary else "${row.baseSummary}: $v"
            }
            rows.filterIsInstance<PrefRow.Select>().forEach { row ->
                val v = WePrefs.getIntOrDef(row.configKey, row.defaultValue)
                map[row.configKey] = row.options[v] ?: "${row.baseSummary}: $v"
            }
        }
    }

    // Helper: resolve enabled/hidden state for a row key
    fun rowEnabled(rowKey: String): Boolean {
        dependencies.forEach { (configKey, depList) ->
            val value = switchStates[configKey]
                ?: WePrefs.getBoolOrFalse(configKey)
            depList.forEach { dep ->
                if (dep.dependentRowKey == rowKey) {
                    return if (dep.enableWhen) value else !value
                }
            }
        }
        return true
    }

    fun rowVisible(rowKey: String): Boolean {
        dependencies.forEach { (configKey, depList) ->
            val value = switchStates[configKey]
                ?: WePrefs.getBoolOrFalse(configKey)
            depList.forEach { dep ->
                if (dep.dependentRowKey == rowKey && dep.hideWhenDisabled) {
                    return if (dep.enableWhen) value else !value
                }
            }
        }
        return true
    }

    // Input dialog state
    var inputDialogRow by remember { mutableStateOf<PrefRow.EditText?>(null) }
    var selectDialogRow by remember { mutableStateOf<PrefRow.Select?>(null) }

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .fillMaxHeight(0.85f),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ──────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 0.dp),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // ── Scrollable preference list ────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp),
            ) {
                rows.forEach { row ->
                    when (row) {
                        // ── Category header ───────────────────────────
                        is PrefRow.Category -> {
                            Text(
                                text = row.title,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(
                                    start = 16.dp, end = 16.dp,
                                    top = 20.dp, bottom = 4.dp,
                                ),
                            )
                        }

                        // ── Switch ────────────────────────────────────
                        is PrefRow.Switch -> {
                            val visible = rowVisible(row.rowKey)
                            val enabled = rowEnabled(row.rowKey)
                            AnimatedVisibility(visible = visible) {
                                SwitchRow(
                                    row = row,
                                    checked = switchStates[row.configKey] ?: false,
                                    enabled = enabled,
                                    onCheckedChange = { checked ->
                                        switchStates[row.configKey] = checked
                                        WePrefs.putBool(row.configKey, checked)
                                        WeLogger.d(
                                            TAG,
                                            "Config changed [${row.configKey}] -> $checked"
                                        )
                                    },
                                )
                            }
                        }

                        // ── EditText ──────────────────────────────────
                        is PrefRow.EditText -> {
                            val visible = rowVisible(row.rowKey)
                            val enabled = rowEnabled(row.rowKey)
                            AnimatedVisibility(visible = visible) {
                                SimpleRow(
                                    title = row.title,
                                    summary = summaryStates[row.configKey] ?: row.baseSummary,
                                    iconName = row.iconName,
                                    enabled = enabled,
                                    showArrow = true,
                                    onClick = { inputDialogRow = row },
                                )
                            }
                        }

                        // ── Select ────────────────────────────────────
                        is PrefRow.Select -> {
                            val visible = rowVisible(row.rowKey)
                            val enabled = rowEnabled(row.rowKey)
                            AnimatedVisibility(visible = visible) {
                                SimpleRow(
                                    title = row.title,
                                    summary = summaryStates[row.configKey] ?: row.baseSummary,
                                    iconName = row.iconName,
                                    enabled = enabled,
                                    showArrow = true,
                                    onClick = { selectDialogRow = row },
                                )
                            }
                        }

                        // ── Plain preference ──────────────────────────
                        is PrefRow.Plain -> {
                            val visible = rowVisible(row.rowKey)
                            val enabled = rowEnabled(row.rowKey)
                            AnimatedVisibility(visible = visible) {
                                SimpleRow(
                                    title = row.title,
                                    summary = row.summary,
                                    iconName = row.iconName,
                                    enabled = enabled,
                                    showArrow = row.onClick != null,
                                    onClick = if (row.onClick != null) {
                                        { row.onClick.invoke() }
                                    } else null,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Input dialog ─────────────────────────────────────────────────────────
    inputDialogRow?.let { row ->
        InputDialog(
            row = row,
            onConfirm = { newValue ->
                WePrefs.putString(row.configKey, newValue)
                val display = row.summaryFormatter?.invoke(newValue)
                    ?: if (newValue.isEmpty()) row.baseSummary else "${row.baseSummary}: $newValue"
                summaryStates[row.configKey] = display
                WeLogger.d(TAG, "Config changed [${row.configKey}] -> $newValue")
                inputDialogRow = null
            },
            onDismiss = { inputDialogRow = null },
        )
    }

    // ── Select dialog ─────────────────────────────────────────────────────────
    selectDialogRow?.let { row ->
        SelectDialog(
            row = row,
            onSelect = { value, displayText ->
                WePrefs.putInt(row.configKey, value)
                summaryStates[row.configKey] = displayText
                WeLogger.d(TAG, "Config changed [${row.configKey}] -> $value")
                selectDialogRow = null
            },
            onDismiss = { selectDialogRow = null },
        )
    }
}

// ---------------------------------------------------------------------------
//  Row composables
// ---------------------------------------------------------------------------

@Composable
private fun SwitchRow(
    row: PrefRow.Switch,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
            ) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.iconName != null) {
            val drawable = remember(row.iconName) { ModuleRes.getDrawable(row.iconName) }
            if (drawable != null) {
                Icon(
                    painter = rememberDrawablePainter(drawable),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (row.summary.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = row.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = if (enabled) onCheckedChange else null,
            enabled = enabled,
        )
    }
}

@Composable
private fun SimpleRow(
    title: String,
    summary: String?,
    iconName: String?,
    enabled: Boolean,
    showArrow: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .then(
                if (onClick != null && enabled)
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(),
                        onClick = onClick,
                    )
                else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconName != null) {
            val drawable = remember(iconName) { ModuleRes.getDrawable(iconName) }
            if (drawable != null) {
                Icon(
                    painter = rememberDrawablePainter(drawable),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!summary.isNullOrEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showArrow) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
//  Sub-dialogs
// ---------------------------------------------------------------------------

@Composable
private fun InputDialog(
    row: PrefRow.EditText,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val current = WePrefs.getStringOrDef(row.configKey, row.defaultValue)
    var text by remember { mutableStateOf(current) }

    val keyboardType = when {
        row.inputType and InputType.TYPE_CLASS_NUMBER != 0 -> KeyboardType.Number
        row.inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0 -> KeyboardType.Decimal
        else -> KeyboardType.Text
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = if (row.maxLength > 0 && it.length > row.maxLength)
                        it.take(row.maxLength) else it
                },
                placeholder = { if (row.hint != null) Text(row.hint) },
                singleLine = row.singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun SelectDialog(
    row: PrefRow.Select,
    onSelect: (Int, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember {
        mutableIntStateOf(WePrefs.getIntOrDef(row.configKey, row.defaultValue))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.title) },
        text = {
            Column {
                row.options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = value
                                onSelect(value, label)
                            }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == value,
                            onClick = {
                                selected = value
                                onSelect(value, label)
                            },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
