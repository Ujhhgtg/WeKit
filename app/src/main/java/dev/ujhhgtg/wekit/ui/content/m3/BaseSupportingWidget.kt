// SPDX-License-Identifier: GPL-3.0-only
// Copyright (C) 2026 InstallerX Revived contributors
package dev.ujhhgtg.wekit.ui.content.m3

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * A setting widget whose supporting-content slot hosts arbitrary inline controls (text fields,
 * sliders, color fields). The sanctioned home for free-form values inside a settings list: the
 * field lives inline below the title instead of floating bare in the list or behind a swap view.
 *
 * Renders with the same visual grammar as [IntNumberPickerWidget]'s rows: a SegmentedColumn-shaped
 * surfaceBright card, a titleMedium title and an optional bodyMedium description, then the caller's
 * [supportingContent] below them. Callers pad their content to align with the title column.
 *
 * @param title The primary text displayed in the widget.
 * @param modifier The [Modifier] to be applied to the widget.
 * @param description Optional supporting text displayed below the title.
 * @param onClick Optional click callback for the whole card; when null the card is not clickable.
 * @param enabled Whether the card's click affordance is enabled.
 * @param supportingContent Inline controls displayed below the title/description block.
 */
@Composable
fun BaseSupportingWidget(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    iconPlaceholder: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    supportingContent: @Composable () -> Unit
) {
    val shape = LocalSegmentedItemShape.current
    val backgroundColor = MaterialTheme.colorScheme.surfaceBright

    val body: @Composable () -> Unit = {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (iconPlaceholder) Spacer(modifier = Modifier.size(24.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (description != null) {
                        Text(
                            text = description,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            supportingContent()

            Spacer(modifier = Modifier.size(8.dp))
        }
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.fillMaxWidth(),
            color = backgroundColor,
            shape = shape,
            content = body
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            color = backgroundColor,
            shape = shape,
            content = body
        )
    }
}
