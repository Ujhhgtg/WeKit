package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.core.graphics.get
import androidx.core.graphics.toColorInt
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Upload_file
import com.tencent.mm.ui.base.AnimImageView
import com.tencent.mm.ui.widget.MMNeat7extView
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.beautify.CustomMessageBubbles.ICON_TINT_TAG
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.exists
import androidx.compose.ui.graphics.Color as ComposeColor

@Feature(name = "自定义消息气泡", categories = ["界面美化", "聊天"], description = "自定义聊天中的消息气泡图片和颜色")
object CustomMessageBubbles : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private val TAG = this::class.simpleName

    // Guards the parse-failure toast so it fires at most once per Feature lifecycle.
    private var colorParseErrorToasted = false

    // The bubble images are source nine-patch PNGs (with black border markers), read in applyBubble.
    private const val LEFT_BUBBLE_FILE = "left_bubble.9.png"   // 对方
    private const val RIGHT_BUBBLE_FILE = "right_bubble.9.png" // 自己

    private enum class BubbleSide(val title: String, val fileName: String) {
        OTHER("对方消息", LEFT_BUBBLE_FILE),
        SELF("自己消息", RIGHT_BUBBLE_FILE),
    }

    private data class BubbleForm(
        val foregroundLight: String,
        val foregroundDark: String,
        val backgroundLight: String,
        val backgroundDark: String,
        val imageExists: Boolean,
    ) {
        val hasValidColors: Boolean
            get() = listOf(foregroundLight, foregroundDark, backgroundLight, backgroundDark)
                .all { it.isBlank() || runCatching { it.toColorInt() }.isSuccess }
    }

    // View tag holding the icon tint color (Int) for an AnimImageView. WeChat swaps in the play
    // animation frames on click (after our bind hook runs), so we hook setCompoundDrawables* to
    // re-tint whatever drawable it installs, reading the target color back from this tag.
    private const val ICON_TINT_TAG = 0x7e4b17_01

    override fun onEnable() {
        colorParseErrorToasted = false
//        hookVoiceIconTint()
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    /**
     * The voice play icon is a compound drawable whose frames are built via `uk.e()`, which bakes a
     * PorterDuff color filter into each drawable. A View-level tint list can't override a baked-in
     * filter, and the playing frames are set on the AnimImageView on click (after bind), so tinting
     * at bind time misses them entirely. Instead, hook the AnimImageView's compound-drawable setter
     * and overwrite the filter on every drawable it receives, using the per-message color we stash
     * on the view as [ICON_TINT_TAG] during bind.
     */
//    private fun hookVoiceIconTint() {
//        TextView::class
//            .firstMethod { name = "setCompoundDrawablesWithIntrinsicBounds"; parameterCount = 4 }
//            .hookAfter {
//                if (thisObject !is AnimImageView) return@hookAfter
//                val view = thisObject as? AnimImageView ?: return@hookAfter
//                val color = view.getTag(ICON_TINT_TAG) as? Int ?: return@hookAfter
//                args.forEach { applyIconColorFilter(it as? Drawable, color) }
//            }
//    }

    private var thatLight by prefOption("custom_bubbles_color_that_light", "black")
    private var thatDark by prefOption("custom_bubbles_color_that_dark", "white")
    private var thisLight by prefOption("custom_bubbles_color_this_light", "black")
    private var thisDark by prefOption("custom_bubbles_color_this_dark", "black")

    private var bgThatLight by prefOption("custom_bubbles_bg_that_light", "#00000000")
    private var bgThatDark by prefOption("custom_bubbles_bg_that_dark", "#00000000")
    private var bgThisLight by prefOption("custom_bubbles_bg_this_light", "#00000000")
    private var bgThisDark by prefOption("custom_bubbles_bg_this_dark", "#00000000")

    private data class Range(val start: Int, val end: Int)

    private fun getRanges(bitmap: Bitmap, z: Boolean, z2: Boolean): ArrayList<Range> {
        val width = if (z) bitmap.width else bitmap.height
        val i = width - 1
        var i2 = -1
        return ArrayList<Range>().apply {
            for (i3 in 1 until i) {
                val pixel = if (z && z2) {
                    bitmap[i3, bitmap.height - 1]
                } else if (z) {
                    bitmap[i3, 0]
                } else {
                    if (z2) bitmap[bitmap.width - 1, i3] else bitmap[0, i3]
                }
                val iAlpha = Color.alpha(pixel)
                val iRed = Color.red(pixel)
                val iGreen = Color.green(pixel)
                val iBlue = Color.blue(pixel)
                if (iAlpha == 255 && iRed == 0 && iGreen == 0 && iBlue == 0) {
                    if (i2 == -1) {
                        i2 = i3 - 1
                    }
                } else if (i2 != -1) {
                    add(Range(i2, i3 - 1))
                    i2 = -1
                }
            }
            if (i2 != -1) {
                add(Range(i2, width - 2))
            }
        }
    }

    private fun hasBubbleTag(view: View): Boolean =
        view.javaClass == TextView::class.java
                && view.tag?.javaClass?.name?.startsWith("com.tencent.mm.ui.chatting.viewitems") == true

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)

        @Suppress("DEPRECATION")
        when (msgInfo.type) {
            MessageType.TEXT, MessageType.LINK, MessageType.GROUP_NOTE, MessageType.QUOTE -> {
                val neatTextView = view.findViewWhich<MMNeat7extView> { it is MMNeat7extView }!!
                applyForegroundColor(neatTextView, msgInfo.isSelfSender)
                applyBubble(neatTextView, msgInfo.isSelfSender)
            }

            MessageType.VOIP -> {
                val bubbleView = view.findViewWhich<LinearLayout> {
                    it.javaClass == LinearLayout::class.java
                            && it.tag?.javaClass?.name?.startsWith("com.tencent.mm.ui.chatting.viewitems") == true
                }!!
                val bubbleTextView = bubbleView.findViewWhich<TextView> { it is TextView }!!
                val bubbleIconView = bubbleView.findViewWhich<LinearLayout> { it !== bubbleView && it is LinearLayout }!!
                applyForegroundColor(bubbleTextView, msgInfo.isSelfSender)
                applyForegroundColorByBackgroundColorFilter(bubbleIconView, msgInfo.isSelfSender)
                applyBubble(bubbleView, msgInfo.isSelfSender)
            }

            MessageType.VOICE -> {
                // The voice item stacks overlapping bubble views:
                //   - the static bubble: a plain TextView tagged with a viewitems holder,
                //     shown while idle (only carries the play-icon drawable, no text)
                //   - the animated wave overlay: an AnimImageView shown on top during playback,
                //     whose bubble background WeChat resets on every bind
                // Style every bubble-bearing view so the overlay keeps our custom bubble too.
                view.findViewsWhich<View> { hasBubbleTag(it) || it is AnimImageView }
                    .forEach { applyBubble(it, msgInfo.isSelfSender) }

                // The visible duration text (e.g. "5''") lives in a separate untagged TextView
                // that shares the innermost bubble container with the static bubble and the wave
                // overlay. The tagged bubble itself has no text, so color every TextView in that
                // container instead. Locate the container structurally (no obfuscated ids): the
                // group whose direct children include both a tagged bubble and an AnimImageView.
                val container = view.findViewWhich<ViewGroup> { v ->
                    v is ViewGroup
                            && (0 until v.childCount).any { hasBubbleTag(v.getChildAt(it)) }
                            && (0 until v.childCount).any { v.getChildAt(it) is AnimImageView }
                }
                container.findViewsWhich<TextView> { it is TextView }
                    .forEach { applyForegroundColor(it, msgInfo.isSelfSender) }

                // The play icon is a compound drawable, not text, so setTextColor never touches it.
                // Its frames are built via uk.e() which bakes in a PorterDuff color filter, so a tint
                // list can't override them either. Overwrite the filter directly. The idle icon sits
                // on the static bubble now; the playing frames get swapped onto the AnimImageView on
                // click, so we stash the color as a tag and let hookVoiceIconTint() re-apply it then.
//                val iconColor = getForegroundColor(view.context, msgInfo.isSelfSender)
//                if (iconColor != -1) {
//                    container.findViewsWhich<TextView> { it is TextView }.forEach { tv ->
//                        if (tv is AnimImageView) tv.setTag(ICON_TINT_TAG, iconColor)
//                        tv.compoundDrawables.forEach { applyIconColorFilter(it, iconColor) }
//                    }
//                }
            }

            else -> {}
        }
    }

    private fun applyBubble(bubbleView: View, isSelfSender: Boolean) {
        val context = bubbleView.context

        val rawColor = if (isSelfSender) {
            if (context.isDarkMode) bgThisDark else bgThisLight
        } else {
            if (context.isDarkMode) bgThatDark else bgThatLight
        }
        val color = parseColor(context, rawColor, label = "背景色", fallback = 0)

        val fileName = if (isSelfSender) RIGHT_BUBBLE_FILE else LEFT_BUBBLE_FILE
        val file = KnownPaths.moduleAssets / fileName

        val bitmap = if (file.exists()) {
            runCatching { BitmapFactory.decodeFile(file.absolutePathString()) }.getOrNull()
        } else null

        if (bitmap == null) {
            if (color != 0) {
                bubbleView.background?.mutate()?.setTint(color)
            }
            return
        }

        val paddingLeft = bubbleView.paddingLeft
        val paddingTop = bubbleView.paddingTop
        val paddingRight = bubbleView.paddingRight
        val paddingBottom = bubbleView.paddingBottom
        val resources = bubbleView.resources

        val bitmapCreateBitmap = Bitmap.createBitmap(bitmap, 1, 1, bitmap.width - 2, bitmap.height - 2)
        val arrayList1 = getRanges(bitmap, z = true, z2 = false)
        val arrayList2 = getRanges(bitmap, z = false, z2 = false)
        val range1 = getRanges(bitmap, z = true, z2 = true).firstOrNull()
        val range2 = getRanges(bitmap, z = false, z2 = true).firstOrNull()
        val rect = Rect(
            range1?.start ?: 0,
            range2?.start ?: 0,
            if (range1 != null) bitmap.width - 2 - range1.end else 0,
            if (range2 != null) bitmap.height - 2 - range2.end else 0
        )

        val byteBuffer = ByteBuffer.allocate((arrayList2.size + arrayList1.size) * 8 + 68).apply {
            order(ByteOrder.nativeOrder())
            put(1.toByte())
            put((arrayList1.size * 2).toByte())
            put((arrayList2.size * 2).toByte())
            put(9.toByte())
            putInt(0)
            putInt(0)
            putInt(rect.left)
            putInt(rect.right)
            putInt(rect.top)
            putInt(rect.bottom)
            putInt(0)
            for (r in arrayList1) {
                putInt(r.start)
                putInt(r.end)
            }
            for (r in arrayList2) {
                putInt(r.start)
                putInt(r.end)
            }
            repeat(9) {
                putInt(1)
            }
        }

        val ninePatchDrawable = NinePatchDrawable(resources, bitmapCreateBitmap, byteBuffer.array(), rect, null).apply {
            if (color != 0) setTint(color)
        }

        val stateListDrawable = StateListDrawable().apply {
            ninePatchDrawable.constantState?.let { constantState ->
                val drawableMutate = constantState.newDrawable().mutate().apply {
                    val fArr = FloatArray(3).apply {
                        Color.colorToHSV(if (color != 0) color else -1, this)
                        this[2] *= 0.8f
                    }
                    setTint(Color.HSVToColor(fArr))
                }
                addState(intArrayOf(android.R.attr.state_pressed), drawableMutate)
                addState(intArrayOf(android.R.attr.state_focused), drawableMutate)
                addState(intArrayOf(), ninePatchDrawable)
            }
        }

        bubbleView.apply {
            background = stateListDrawable
            setPadding(paddingLeft, paddingTop, paddingRight, paddingBottom)
        }
    }

    /**
     * Parses a user-entered color. Empty input means "no override" and returns [fallback] silently.
     * A genuine parse failure returns [fallback] too, but surfaces a toast at most once per Feature
     * lifecycle (these run per message view bind, so an unguarded toast would spam).
     */
    private fun parseColor(context: Context, rawColor: String, label: String, fallback: Int): Int {
        if (rawColor.isBlank()) return fallback

        return runCatching { rawColor.toColorInt() }.getOrElse {
            if (!colorParseErrorToasted) {
                colorParseErrorToasted = true
                showToast(context, "有气泡${label}解析失败! 请检查格式")
            }
            fallback
        }
    }

    private fun getForegroundColor(context: Context, isSelfSender: Boolean): Int {
        val rawColor = if (isSelfSender) {
            if (context.isDarkMode) thisDark else thisLight
        } else {
            if (context.isDarkMode) thatDark else thatLight
        }
        return parseColor(context, rawColor, label = "前景色", fallback = -1)
    }

    private fun applyForegroundColor(view: MMNeat7extView, isSelfSender: Boolean) {
        val color = getForegroundColor(view.context, isSelfSender)
        if (color == -1) return

        view.setTextColor(color)
    }

    private fun applyForegroundColor(view: TextView, isSelfSender: Boolean) {
        val color = getForegroundColor(view.context, isSelfSender)
        if (color == -1) return

        view.setTextColor(color)
    }

    private fun applyForegroundColorByBackgroundColorFilter(view: View, isSelfSender: Boolean) {
        val color = getForegroundColor(view.context, isSelfSender)
        if (color == -1) return

        view.background?.mutate()?.colorFilter = PorterDuffColorFilter(
            color,
            PorterDuff.Mode.SRC_IN
        )
    }

    /**
     * Overwrites a drawable's color filter with [color] (SRC_ATOP), mutating first so shared
     * drawable state isn't affected. Used for the voice play icon, whose frames are built via
     * uk.e() with a baked-in PorterDuff filter that a View-level tint list cannot override.
     */
//    private fun applyIconColorFilter(drawable: Drawable?, color: Int) {
//        drawable?.mutate()?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_ATOP)
//    }

    /**
     * Launches a system image picker and copies the chosen file's raw bytes into [fileName] under
     * the module assets dir. Raw copy (not re-encode) preserves the nine-patch border markers.
     */
    private fun importBubbleImage(context: Context, fileName: String, label: String, onDone: () -> Unit) {
        TransparentActivity.launch(context) {
            val launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                finish()
                if (uri == null) return@registerForActivityResult

                val ok = runCatching {
                    contentResolver.openInputStream(uri)?.use { input ->
                        (KnownPaths.moduleAssets / fileName).toFile().outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("failed to open input stream")
                }.onFailure {
                    WeLogger.e(TAG, "failed to import $label bubble image", it)
                }.isSuccess

                showToast(context, if (ok) "$label 气泡图片导入成功" else "$label 气泡图片导入失败!")
                if (ok) onDone()
            }
            launcher.launch("image/*")
        }
    }

    private fun deleteBubbleImage(context: Context, side: BubbleSide): Boolean {
        val file = KnownPaths.moduleAssets / side.fileName
        val ok = runCatching {
            !file.exists() || file.deleteIfExists()
        }.onFailure {
            WeLogger.e(TAG, "failed to delete ${side.title} bubble image", it)
        }.getOrDefault(false)

        showToast(context, if (ok) "${side.title}气泡图片已删除" else "${side.title}气泡图片删除失败!")
        return ok
    }

    @Composable
    private fun ColorField(
        label: String,
        value: String,
        onValueChange: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val parsedColor = remember(value) {
            value.takeIf { it.isNotBlank() }?.let { runCatching { it.toColorInt() }.getOrNull() }
        }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            isError = value.isNotBlank() && parsedColor == null,
            trailingIcon = parsedColor?.let { color ->
                {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(ComposeColor(color))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    )
                }
            },
            modifier = modifier,
        )
    }

    @Composable
    private fun BubbleEditor(
        form: BubbleForm,
        onFormChange: (BubbleForm) -> Unit,
        onImport: () -> Unit,
        onDelete: () -> Unit,
    ) {
        Text(
            text = "文字颜色",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorField(
                label = "亮色模式",
                value = form.foregroundLight,
                onValueChange = { onFormChange(form.copy(foregroundLight = it)) },
                modifier = Modifier.weight(1f),
            )
            ColorField(
                label = "暗色模式",
                value = form.foregroundDark,
                onValueChange = { onFormChange(form.copy(foregroundDark = it)) },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = "背景颜色",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ColorField(
                label = "亮色模式",
                value = form.backgroundLight,
                onValueChange = { onFormChange(form.copy(backgroundLight = it)) },
                modifier = Modifier.weight(1f),
            )
            ColorField(
                label = "暗色模式",
                value = form.backgroundDark,
                onValueChange = { onFormChange(form.copy(backgroundDark = it)) },
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = "气泡图片 (.9.png)",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (form.imageExists) "已导入" else "未导入",
                color = if (form.imageExists) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onImport) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Upload_file,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (form.imageExists) "更换" else "导入")
            }
            IconButton(
                onClick = onDelete,
                enabled = form.imageExists,
            ) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Delete,
                    contentDescription = "删除已导入的气泡图片",
                    tint = if (form.imageExists) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var selectedSide by remember { mutableStateOf(BubbleSide.OTHER) }
            var pendingDeletion by remember { mutableStateOf<BubbleSide?>(null) }
            var otherForm by remember {
                mutableStateOf(
                    BubbleForm(
                        foregroundLight = thatLight,
                        foregroundDark = thatDark,
                        backgroundLight = bgThatLight,
                        backgroundDark = bgThatDark,
                        imageExists = (KnownPaths.moduleAssets / LEFT_BUBBLE_FILE).exists(),
                    )
                )
            }
            var selfForm by remember {
                mutableStateOf(
                    BubbleForm(
                        foregroundLight = thisLight,
                        foregroundDark = thisDark,
                        backgroundLight = bgThisLight,
                        backgroundDark = bgThisDark,
                        imageExists = (KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE).exists(),
                    )
                )
            }

            fun updateForm(side: BubbleSide, transform: (BubbleForm) -> BubbleForm) {
                when (side) {
                    BubbleSide.OTHER -> otherForm = transform(otherForm)
                    BubbleSide.SELF -> selfForm = transform(selfForm)
                }
            }

            val deleteSide = pendingDeletion
            if (deleteSide != null) {
                AlertDialogContent(
                    title = { Text("删除气泡图片") },
                    text = { Text("确定删除${deleteSide.title}已导入的气泡图片？") },
                    dismissButton = {
                        TextButton(onClick = { pendingDeletion = null }) { Text("取消") }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                if (deleteBubbleImage(context, deleteSide)) {
                                    updateForm(deleteSide) { it.copy(imageExists = false) }
                                    pendingDeletion = null
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) {
                            Text("删除")
                        }
                    },
                )
            } else {
                val selectedForm = when (selectedSide) {
                    BubbleSide.OTHER -> otherForm
                    BubbleSide.SELF -> selfForm
                }

                AlertDialogContent(
                    title = { Text("自定义消息气泡") },
                    text = {
                        DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                BubbleSide.entries.forEachIndexed { index, side ->
                                    SegmentedButton(
                                        selected = selectedSide == side,
                                        onClick = { selectedSide = side },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index,
                                            BubbleSide.entries.size,
                                        ),
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text(side.title)
                                    }
                                }
                            }

                            BubbleEditor(
                                form = selectedForm,
                                onFormChange = { next -> updateForm(selectedSide) { next } },
                                onImport = {
                                    val side = selectedSide
                                    importBubbleImage(context, side.fileName, side.title) {
                                        updateForm(side) { it.copy(imageExists = true) }
                                    }
                                },
                                onDelete = { pendingDeletion = selectedSide },
                            )
                        }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                    confirmButton = {
                        Button(
                            enabled = otherForm.hasValidColors && selfForm.hasValidColors,
                            onClick = {
                                thatLight = otherForm.foregroundLight
                                thatDark = otherForm.foregroundDark
                                thisLight = selfForm.foregroundLight
                                thisDark = selfForm.foregroundDark

                                bgThatLight = otherForm.backgroundLight
                                bgThatDark = otherForm.backgroundDark
                                bgThisLight = selfForm.backgroundLight
                                bgThisDark = selfForm.backgroundDark
                                onDismiss()
                            },
                        ) {
                            Text("确定")
                        }
                    },
                )
            }
        }
    }
}
