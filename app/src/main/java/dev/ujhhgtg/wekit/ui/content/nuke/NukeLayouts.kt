package dev.ujhhgtg.wekit.ui.content.nuke

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NukePageScaffold(
    title: String,
    onBack: (Offset) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = 18.dp,
        end = 18.dp,
        bottom = 20.dp,
    ),
    itemSpacing: Dp = 12.dp,
    topBarActions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(NukeTheme.colors.background)
    ) {
        NukeTopAppBar(
            title = title,
            onBack = onBack,
            actions = topBarActions,
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(itemSpacing),
            content = content,
        )
    }
}

@Composable
fun NukeEmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(18.dp)
    ) {
        NukeText(
            text = title,
            color = NukeTheme.colors.textPrimary,
            fontSize = 15,
            lineHeight = 20,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(5.dp))
        NukeText(
            text = description,
            color = NukeTheme.colors.textSecondary,
            fontSize = 13,
            lineHeight = 18,
        )
    }
}
