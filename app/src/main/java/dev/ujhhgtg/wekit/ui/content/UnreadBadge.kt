package dev.ujhhgtg.wekit.ui.content

import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import dev.ujhhgtg.wekit.R

/** Shared unread count/dot for the injected navigation bar and conversation groups. */
@Composable
fun UnreadBadge(count: Long = 0) {
    val background = Color(0xFFFF3B30)
    val foreground = Color.White
    Badge(
        containerColor = background,
        contentColor = foreground,
        content =
            if (count > 0) {
                {
                    Text(
                        text =
                            if (count <= 99) {
                                count.toString()
                            } else {
                                stringResource(R.string.badge_count_overflow)
                            },
                        color = foreground,
                        fontSize = 10.sp,
                    )
                }
            } else {
                null
            },
    )
}
