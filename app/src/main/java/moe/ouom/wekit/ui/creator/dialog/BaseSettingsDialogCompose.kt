package moe.ouom.wekit.ui.creator.dialog

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import moe.ouom.wekit.ui.compose.showComposeDialog

// ---------------------------------------------------------------------------
//  Sealed row type for CategorySettingsDialog items
// ---------------------------------------------------------------------------

internal sealed class SettingsRow {
    abstract val rowKey: String

    data class SwitchRow(
        override val rowKey: String,
        val title: String,
        val summary: String,
        val configKey: String,
        val initialChecked: Boolean,
        // Returns true if the toggle should be allowed to proceed
        val onBeforeToggle: (Boolean) -> Boolean,
        // The item calls this to register a callback; callback receives the authoritative new value
        val bindCompletionCallback: ((Boolean) -> Unit) -> Unit,
    ) : SettingsRow()

    data class ClickableRow(
        override val rowKey: String,
        val title: String,
        val summary: String,
        val showSwitch: Boolean,
        val configKey: String,
        val initialChecked: Boolean,
        val onBeforeToggle: (Boolean) -> Boolean,
        val bindCompletionCallback: ((Boolean) -> Unit) -> Unit,
        val onClick: () -> Unit,
    ) : SettingsRow()
}

// ---------------------------------------------------------------------------
//  Abstract base – mirrors BaseSettingsDialog public API
// ---------------------------------------------------------------------------

abstract class BaseSettingsDialogCompose(
    protected val context: Context,
    private val title: String,
) {
    private var _dismissCallback: (() -> Unit)? = null
    internal val rows = mutableListOf<SettingsRow>()
    private var rowCounter = 0

    internal fun nextKey(prefix: String) = "${prefix}_${rowCounter++}"

    // -----------------------------------------------------------------------
    //  Public lifecycle – same as android.app.Dialog
    // -----------------------------------------------------------------------

    abstract fun initList()

    open fun show() {
        rows.clear()
        rowCounter = 0
        initList()

        showComposeDialog(context) { onDismiss ->
            _dismissCallback = onDismiss
            BaseSettingsDialogContent(
                title = title,
                rows = rows,
                onDismiss = {
                    _dismissCallback?.invoke()
                    _dismissCallback = null
                },
            )
        }
    }

    open fun dismiss() {
        _dismissCallback?.invoke()
        _dismissCallback = null
    }
}

// ---------------------------------------------------------------------------
//  Compose UI
// ---------------------------------------------------------------------------

@Composable
internal fun BaseSettingsDialogContent(
    title: String,
    rows: List<SettingsRow>,
    onDismiss: () -> Unit,
) {
    // Per-row checked state: rowKey -> Boolean
    val checkedStates = remember {
        mutableStateMapOf<String, Boolean>().also { map ->
            rows.forEach { row ->
                when (row) {
                    is SettingsRow.SwitchRow -> map[row.rowKey] = row.initialChecked
                    is SettingsRow.ClickableRow -> map[row.rowKey] = row.initialChecked
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
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
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                // ── Scrollable list ───────────────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 16.dp),
                ) {
                    rows.forEach { row ->
                        when (row) {
                            is SettingsRow.SwitchRow -> {
                                val checked = checkedStates[row.rowKey] ?: row.initialChecked

                                // Re-register completion callback on each recompose so the
                                // lambda always closes over the current checkedStates map.
                                DisposableEffect(row.rowKey) {
                                    row.bindCompletionCallback { newValue ->
                                        checkedStates[row.rowKey] = newValue
                                    }
                                    onDispose { }
                                }

                                SwitchSettingsRow(
                                    title = row.title,
                                    summary = row.summary,
                                    checked = checked,
                                    onCheckedChange = { requested ->
                                        val allowed = row.onBeforeToggle(requested)
                                        if (allowed) {
                                            checkedStates[row.rowKey] = requested
                                        }
                                        // If not allowed, checkedStates stays unchanged → Switch bounces back
                                    },
                                )
                            }

                            is SettingsRow.ClickableRow -> {
                                val checked = checkedStates[row.rowKey] ?: row.initialChecked

                                DisposableEffect(row.rowKey) {
                                    row.bindCompletionCallback { newValue ->
                                        checkedStates[row.rowKey] = newValue
                                    }
                                    onDispose { }
                                }

                                ClickableSettingsRow(
                                    title = row.title,
                                    summary = row.summary,
                                    showSwitch = row.showSwitch,
                                    checked = checked,
                                    onCheckedChange = { requested ->
                                        val allowed = row.onBeforeToggle(requested)
                                        if (allowed) checkedStates[row.rowKey] = requested
                                    },
                                    onClick = row.onClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Row composables
// ---------------------------------------------------------------------------

@Composable
private fun SwitchSettingsRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ClickableSettingsRow(
    title: String,
    summary: String,
    showSwitch: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (summary.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (showSwitch) {
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}