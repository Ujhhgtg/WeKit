package dev.ujhhgtg.wekit.features.items.beautify

import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.isGone
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import java.util.Collections
import java.util.WeakHashMap


@Feature(
    name = "「我」页面精简",
    categories = ["界面美化"],
    description = "精简我的页面的部分组件",
)
object HideMeTabPageItems : ClickableFeature(), IResolveDex {

    private var hideMoments by WePrefs.prefOption("hide_me_moments", false)
    private var hideFinder by WePrefs.prefOption("hide_me_finder", false)
    private var hideCards by WePrefs.prefOption("hide_me_cards", false)
    private var hideEmoji by WePrefs.prefOption("hide_me_emoji", false)

    private val methodOnViewCreated by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.MoreTabUI"
            name = "onViewCreated"
        }
    }

    override fun onEnable() {
        methodOnViewCreated.hookAfter {
            val root = args.getOrNull(0) as? ViewGroup ?: return@hookAfter
            installLayoutListener(root)
            root.post { applyEntryRules(root) }
        }
    }

    private val installedRoots = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>()),
    )

    private fun installLayoutListener(root: ViewGroup) {
        if (!installedRoots.add(root)) return

        root.viewTreeObserver.addOnGlobalLayoutListener {
            applyEntryRules(root)
        }
    }

    private fun applyEntryRules(root: ViewGroup) {
        val targets = selectedTargets
        if (targets.isEmpty()) return
        root.findViewsWhich { it is TextView && it.text?.toString()?.trim().orEmpty() in targets }.forEach {
            hideContainerFor(it)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var hideMomentsInput by remember { mutableStateOf(hideMoments) }
            var hideFinderInput by remember { mutableStateOf(hideFinder) }
            var hideCardsInput by remember { mutableStateOf(hideCards) }
            var hideEmojiInput by remember { mutableStateOf(hideEmoji) }

            AlertDialogContent(
                title = { Text("「我」页面精简") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { hideMomentsInput = !hideMomentsInput },
                            trailingContent = {
                                Switch(
                                    checked = hideMomentsInput,
                                    onCheckedChange = null
                                )
                            },
                            content = { Text("隐藏朋友圈标签") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { hideFinderInput = !hideFinderInput },
                            leadingContent = null,
                            trailingContent = {
                                Switch(
                                    checked = hideFinderInput,
                                    onCheckedChange = null
                                )
                            },
                            supportingContent = { Text("取决于微信版本, 可能为「视频号」「视频号和公众号」「作品」") },
                            content = { Text("隐藏作品标签") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { hideCardsInput = !hideCardsInput },
                            trailingContent = {
                                Switch(
                                    checked = hideCardsInput,
                                    onCheckedChange = null
                                )
                            },
                            supportingContent = { Text("取决于微信版本, 可能为「卡包」「小店与卡包」") },
                            content = { Text("隐藏卡包标签") },
                        )
                        ListItem(
                            modifier = Modifier.clickable { hideEmojiInput = !hideEmojiInput },
                            trailingContent = {
                                Switch(
                                    checked = hideEmojiInput,
                                    onCheckedChange = null
                                )
                            },
                            content = { Text("隐藏表情标签") },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        hideMoments = hideMomentsInput
                        hideFinder = hideFinderInput
                        hideCards = hideCardsInput
                        hideEmoji = hideEmojiInput
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                },
            )
        }
    }

    private val selectedTargets: Set<String>
        get() {
            val targets = linkedSetOf<String>()
            if (hideMoments) {
                targets += "朋友圈"
            }
            if (hideFinder) {
                targets += "视频号"
                targets += "视频号和公众号"
                targets += "作品"
            }
            if (hideCards) {
                targets += "卡包"
                targets += "小店与卡包"
            }
            if (hideEmoji) {
                targets += "表情"
            }
            return targets
        }

    private fun hideContainerFor(labelView: View) {
        val container = findRowContainer(labelView) ?: return
        if (container.isGone && container.layoutParams?.height == 1) return

        container.isGone = true
        container.minimumHeight = 0
        container.setPadding(0, 0, 0, 0)
        container.layoutParams?.let { params ->
            params.height = 1
            if (params is ViewGroup.MarginLayoutParams) {
                params.setMargins(0, 0, 0, 0)
            }
            container.layoutParams = params
        }

        hidePreviousDivider(container)
        (container.parent as? View)?.requestLayout()
    }

    private fun findRowContainer(view: View): View? {
        var current = view
        while (true) {
            val parent = current.parent
            if (parent !is ViewGroup) return null
            if (parent is ListView || parent.javaClass.name == "androidx.recyclerview.widget.RecyclerView") return current
            current = parent
        }
    }

    private fun hidePreviousDivider(container: View) {
        val parent = container.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(container)
        if (index <= 0) return

        val previous = parent.getChildAt(index - 1)
        if (previous is TextView) return

        val density = previous.resources.displayMetrics.density
        val measuredHeight = previous.height.takeIf { it > 0 } ?: return
        if (measuredHeight >= 30f * density) return

        previous.isGone = true
        previous.layoutParams?.let { params ->
            params.height = 1
            previous.layoutParams = params
        }
    }
}
