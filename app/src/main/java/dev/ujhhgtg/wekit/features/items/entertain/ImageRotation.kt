package dev.ujhhgtg.wekit.features.items.entertain

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import java.util.WeakHashMap

@Feature(
    id = "图片旋转",
    nameRes = "feature_image_rotation_name",
    categoryIds = [FeatureCategoryIds.ENTERTAIN],
    descriptionRes = "feature_image_rotation_description",
)
object ImageRotation : ClickableFeature() {

    private data class RotationState(
        val originalRotation: Float,
        val animator: ObjectAnimator,
    )

    private val viewStateMap = WeakHashMap<View, RotationState>()

    private var onlyAvatars by prefOption("image_rotation_only_avatars", false)
    private var durationMs by prefOption("image_rotation_duration", 1000)

    override fun onEnable() {
        if (onlyAvatars) {
            // Nuke 1.0.2 ChatAvatarRotator 逻辑: 仅挂钩聊天头像
            "com.tencent.mm.ui.chatting.view.ChattingAvatarImageView".toClass().reflekt()
                .firstConstructor().hookAfter {
                    applyRotation(thisObject as View)
                }
        } else {
            // 现有 WeKit 逻辑
            ImageView::class.reflekt()
                .firstConstructor { parameterCount = 4 }.hookAfter {
                    applyRotation(thisObject as View)
                }

            "com.tencent.mm.ui.widget.QImageView".toClass().reflekt()
                .firstConstructor().hookAfter {
                    applyRotation(thisObject as View)
                }
        }
    }

    override fun onDisable() {
        viewStateMap.forEach { (view, state) ->
            state.animator.cancel()
            view.rotation = state.originalRotation
        }
        viewStateMap.clear()
    }

    private fun applyRotation(view: View) {
        view.post {
            if (!isActive || viewStateMap.containsKey(view)) return@post

            val animator = ObjectAnimator.ofFloat(view, "rotation", 0f, 360f).apply {
                duration = durationMs.toLong()
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            viewStateMap[view] = RotationState(view.rotation, animator)
            animator.start()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var avatars by remember { mutableStateOf(onlyAvatars) }
            var duration by remember { mutableStateOf(durationMs.toString()) }
            val durationValid = (duration.toIntOrNull() ?: 0) > 0

            AlertDialogContent(
                title = { Text("图片旋转") },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("只对头像生效", modifier = Modifier.weight(1f))
                            Switch(checked = avatars, onCheckedChange = { avatars = it })
                        }
                        OutlinedTextField(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            value = duration,
                            onValueChange = { duration = it.filter(Char::isDigit).take(6) },
                            label = { Text("旋转周期 (毫秒)") },
                            supportingText = { Text("请输入大于 0 的毫秒数") },
                            isError = !durationValid,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    Button(
                        enabled = durationValid,
                        onClick = {
                            val newDuration = duration.toIntOrNull() ?: 1000
                            val newOnlyAvatars = avatars
                            val changed = newDuration != durationMs || newOnlyAvatars != onlyAvatars
                            durationMs = newDuration
                            onlyAvatars = newOnlyAvatars
                            if (changed && isActive) {
                                disable()
                                enable()
                            }
                            onDismiss()
                        },
                    ) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }
}
