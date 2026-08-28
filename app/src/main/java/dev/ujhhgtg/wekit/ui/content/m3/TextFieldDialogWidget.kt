// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Visibility
import com.composables.icons.materialsymbols.outlined.Visibility_off

/**
 * A setting widget that displays the current string value on a standard clickable row and
 * edits it in a dialog, following InstallerX's row-driven input pattern.
 *
 * @param title The row headline.
 * @param value The current value, shown as the row description.
 * @param onValueChange Invoked with the confirmed dialog input.
 * @param dialogTitle Title of the edit dialog.
 * @param confirmLabel Label of the dialog's confirm action.
 * @param dismissLabel Label of the dialog's dismiss action.
 * @param enabled Whether the row opens the dialog.
 * @param keyboardType Keyboard type for the dialog field.
 * @param filter Input filter applied while typing (e.g. digits only).
 * @param password Masks the value on the row and in the dialog, with a reveal toggle.
 * @param valueHint Description shown when [value] is blank.
 * @param singleLine Whether the dialog field is single-line (false for JSON and other blobs).
 */
@Composable
fun TextFieldDialogWidget(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    dialogTitle: String,
    confirmLabel: String,
    dismissLabel: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    filter: (String) -> String = { it },
    password: Boolean = false,
    valueHint: String? = null,
    singleLine: Boolean = true,
) {
    var showDialog by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf(value) }
    var reveal by remember { mutableStateOf(false) }

    BaseWidget(
        modifier = modifier,
        iconPlaceholder = false,
        title = title,
        description = when {
            value.isBlank() -> valueHint
            password -> "••••••••"
            else -> value
        },
        enabled = enabled,
        onClick = {
            draft = value
            reveal = false
            showDialog = true
        },
        trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
    )

    if (!showDialog) return
    AlertDialog(
        onDismissRequest = { showDialog = false },
        title = { Text(dialogTitle) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = filter(it) },
                singleLine = singleLine,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (password && !reveal) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                trailingIcon = if (password) {
                    {
                        IconButton(onClick = { reveal = !reveal }) {
                            Icon(
                                imageVector = if (reveal) {
                                    MaterialSymbols.Outlined.Visibility_off
                                } else {
                                    MaterialSymbols.Outlined.Visibility
                                },
                                contentDescription = null,
                            )
                        }
                    }
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    showDialog = false
                    onValueChange(draft)
                },
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = { showDialog = false }) { Text(dismissLabel) }
        },
    )
}
