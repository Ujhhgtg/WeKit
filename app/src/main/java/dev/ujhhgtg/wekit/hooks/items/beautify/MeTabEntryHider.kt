package dev.ujhhgtg.wekit.hooks.items.beautify

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.isGone
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import org.luckypray.dexkit.DexKitBridge
import java.util.Collections
import java.util.WeakHashMap


@HookItem(
    name = "我的页面精简",
    categories = ["个人资料"],
    description = "精简我的页面的部分组件",
)
object MeTabEntryHider : ClickableHookItem(), IResolvesDex {

    private const val KEY_HIDE_MOMENTS = "key_hide_me_album"
    private const val KEY_HIDE_FINDER = "key_hide_me_finder"
    private const val KEY_HIDE_CARDS = "key_hide_me_card"
    private const val KEY_HIDE_EMOJI = "key_hide_me_emoji"

    private const val RECYCLER_VIEW = "androidx.recyclerview.widget.RecyclerView"

    private var isHideMoments by WePrefs.prefOption(KEY_HIDE_MOMENTS, defValue = false)
    private var isHideFinder by WePrefs.prefOption(KEY_HIDE_FINDER, defValue = false)
    private var isHideCards by WePrefs.prefOption(KEY_HIDE_CARDS, defValue = false)
    private var isHideEmoji by WePrefs.prefOption(KEY_HIDE_EMOJI, defValue = false)

    private val installedRoots = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>()),
    )

    private val methodOnViewCreated by dexMethod()

    override fun onEnable() {
        methodOnViewCreated.hookAfter {
            if (!isHookActive()) return@hookAfter
            val root = args.getOrNull(0) as? ViewGroup ?: return@hookAfter
            installLayoutListener(root)
            root.post { applyEntryRules(root) }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodOnViewCreated.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.ui.MoreTabUI"
                name = "onViewCreated"
            }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var hideMoments by remember { mutableStateOf(isHideMoments) }
            var hideFinder by remember { mutableStateOf(isHideFinder) }
            var hideCards by remember { mutableStateOf(isHideCards) }
            var hideEmoji by remember { mutableStateOf(isHideEmoji) }

            AlertDialogContent(
                title = { Text("我与发现重构") },
                text = {
                    DefaultColumn {
                        OptionRow(
                            title = "隐藏朋友圈标签",
                            summary = "隐藏「我」页的朋友圈/相册入口",
                            checked = hideMoments,
                            onCheckedChange = { hideMoments = it },
                        )
                        OptionRow(
                            title = "隐藏视频号标签",
                            summary = "隐藏视频号和作品入口",
                            checked = hideFinder,
                            onCheckedChange = { hideFinder = it },
                        )
                        OptionRow(
                            title = "隐藏卡包标签",
                            summary = "隐藏卡包/小店与卡包入口",
                            checked = hideCards,
                            onCheckedChange = { hideCards = it },
                        )
                        OptionRow(
                            title = "隐藏表情标签",
                            summary = "隐藏表情包管理入口",
                            checked = hideEmoji,
                            onCheckedChange = { hideEmoji = it },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        isHideMoments = hideMoments
                        isHideFinder = hideFinder
                        isHideCards = hideCards
                        isHideEmoji = hideEmoji
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                },
            )
        }
    }

    private fun installLayoutListener(root: ViewGroup) {
        if (!installedRoots.add(root)) return

        root.viewTreeObserver.addOnGlobalLayoutListener {
            if (!isHookActive()) return@addOnGlobalLayoutListener
            applyEntryRules(root)
        }
    }

    private fun applyEntryRules(root: ViewGroup) {
        val targets = selectedTargets()
        if (targets.isEmpty()) return
        traverse(root) { view ->
            val textView = view as? TextView ?: return@traverse
            val label = textView.text?.toString()?.trim().orEmpty()
            if (targets.any { label == it }) {
                hideContainerFor(textView)
            }
        }
    }

    private fun selectedTargets(): Set<String> {
        val targets = linkedSetOf<String>()
        if (isHideMoments) {
            targets += "朋友圈"
        }
        if (isHideFinder) {
            targets += "视频号"
            targets += "作品"
        }
        if (isHideCards) {
            targets += "卡包"
            targets += "小店与卡包"
        }
        if (isHideEmoji) {
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
            if (parent is ListView || parent.javaClass.name == RECYCLER_VIEW) return current
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
        if (measuredHeight >= (30f * density)) return

        previous.isGone = true
        previous.layoutParams?.let { params ->
            params.height = 1
            previous.layoutParams = params
        }
    }

    private inline fun traverse(root: View, action: (View) -> Unit) {
        val queue = ArrayDeque<View>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val view = queue.removeFirst()
            action(view)
            (view as? ViewGroup)?.let { group ->
                for (i in 0 until group.childCount) {
                    queue.add(group.getChildAt(i))
                }
            }
        }
    }

    private fun isHookActive(): Boolean {
        return hasEnabled && isEnabled
    }
}

@androidx.compose.runtime.Composable
private fun OptionRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.clickable { onCheckedChange(!checked) },
    )
}
