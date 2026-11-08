package dev.ujhhgtg.wekit.bootstrap.ui.content.m3

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

val CornerRadius = 16.dp
val ConnectionRadius = 5.dp
val LocalSegmentedItemShape = compositionLocalOf<Shape> { RoundedCornerShape(CornerRadius) }

@DslMarker annotation class SegmentedColumnDsl

@SegmentedColumnDsl
class SegmentedColumnScope {
    val items = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        items.add(content)
    }
}

@Composable
fun SegmentedColumn(
    modifier: Modifier = Modifier,
    title: String = "",
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    content: SegmentedColumnScope.() -> Unit,
) {
    val items = SegmentedColumnScope().apply(content).items
    Column(modifier = modifier.fillMaxWidth().padding(contentPadding)) {
        if (title.isNotEmpty()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 16.dp),
            )
        }
        items.forEachIndexed { index, item ->
            val shape =
                RoundedCornerShape(
                    topStart = if (index == 0) CornerRadius else ConnectionRadius,
                    topEnd = if (index == 0) CornerRadius else ConnectionRadius,
                    bottomStart = if (index == items.lastIndex) CornerRadius else ConnectionRadius,
                    bottomEnd = if (index == items.lastIndex) CornerRadius else ConnectionRadius,
                )
            CompositionLocalProvider(LocalSegmentedItemShape provides shape) {
                Column(
                    modifier =
                        Modifier.padding(
                            top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap,
                        ),
                ) {
                    item()
                }
            }
        }
    }
}
