package dev.ujhhgtg.wekit.hooks.items.beautify

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.view.isGone
import androidx.core.view.isVisible
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.hooks.api.core.models.SelfProfileField
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import org.luckypray.dexkit.DexKitBridge

@HookItem(
    name = "资料卡居中",
    categories = ["界面美化", "个人资料"],
    description = "居中「我」界面的资料卡",
)
object CenterProfileCard : ClickableHookItem(), IResolvesDex {

    private val TAG = This.Class.simpleName
    private const val CENTER_CARD_TAG = "wekit_account_info_center_card"
    private const val ACCOUNT_INFO_PREFERENCE = "com.tencent.mm.pluginsdk.ui.preference.AccountInfoPreference"

    private const val DEFAULT_AVATAR_TOP_MARGIN_DP = 40
    private const val DEFAULT_AVATAR_SIZE_DP = 80
    private const val DEFAULT_NAME_TOP_MARGIN_DP = 4
    private const val DEFAULT_ALIAS_TOP_MARGIN_DP = 4
    private const val DEFAULT_SIGNATURE_TOP_MARGIN_DP = 4
    private const val DEFAULT_LIGHT_BG = "#FFFFFFFF"
    private const val DEFAULT_DARK_BG = "#FF191919"

    private const val KEY_AVATAR_TOP_MARGIN = "account_info_center_avatar_top_margin"
    private const val KEY_AVATAR_SIZE = "account_info_center_avatar_size"
    private const val KEY_NAME_TOP_MARGIN = "account_info_center_name_top_margin"
    private const val KEY_ALIAS_TOP_MARGIN = "account_info_center_alias_top_margin"
    private const val KEY_SIGNATURE_TOP_MARGIN = "account_info_center_signature_top_margin"
    private const val KEY_LIGHT_BG = "account_info_center_light_bg"
    private const val KEY_DARK_BG = "account_info_center_dark_bg"
    private const val KEY_SHOW_NAME = "account_info_center_show_name"
    private const val KEY_SHOW_ALIAS = "account_info_center_show_alias"
    private const val KEY_SHOW_SIGNATURE = "account_info_center_show_signature"
    private const val KEY_NAME_TEXT = "account_info_center_name_text"
    private const val KEY_ALIAS_TEXT = "account_info_center_alias_text"
    private const val KEY_SIGNATURE_TEXT = "account_info_center_signature_text"

    private var avatarTopMarginPref by WePrefs.prefOption(KEY_AVATAR_TOP_MARGIN, DEFAULT_AVATAR_TOP_MARGIN_DP)
    private var avatarSizePref by WePrefs.prefOption(KEY_AVATAR_SIZE, DEFAULT_AVATAR_SIZE_DP)
    private var nameTopMarginPref by WePrefs.prefOption(KEY_NAME_TOP_MARGIN, DEFAULT_NAME_TOP_MARGIN_DP)
    private var aliasTopMarginPref by WePrefs.prefOption(KEY_ALIAS_TOP_MARGIN, DEFAULT_ALIAS_TOP_MARGIN_DP)
    private var signatureTopMarginPref by WePrefs.prefOption(KEY_SIGNATURE_TOP_MARGIN, DEFAULT_SIGNATURE_TOP_MARGIN_DP)
    private var lightBgPref by WePrefs.prefOption(KEY_LIGHT_BG, DEFAULT_LIGHT_BG)
    private var darkBgPref by WePrefs.prefOption(KEY_DARK_BG, DEFAULT_DARK_BG)
    private var showNamePref by WePrefs.prefOption(KEY_SHOW_NAME, true)
    private var showAliasPref by WePrefs.prefOption(KEY_SHOW_ALIAS, true)
    private var showSignaturePref by WePrefs.prefOption(KEY_SHOW_SIGNATURE, true)
    private var nameTextPref by WePrefs.prefOption(KEY_NAME_TEXT, "")
    private var aliasTextPref by WePrefs.prefOption(KEY_ALIAS_TEXT, "")
    private var signatureTextPref by WePrefs.prefOption(KEY_SIGNATURE_TEXT, "")

    private val methodBindAccountInfo by dexMethod()

    override fun onEnable() {
        methodBindAccountInfo.hookAfter {
            val root = args.firstOrNull() as? ViewGroup ?: return@hookAfter
            root.post {
                runCatching { applyCenterCard(root) }
                    .onFailure { WeLogger.e(TAG, "failed to center account info card", it) }
            }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var avatarTopMargin by remember { mutableStateOf(avatarTopMarginPref.toString()) }
            var avatarSize by remember { mutableStateOf(avatarSizePref.toString()) }
            var nameTopMargin by remember { mutableStateOf(nameTopMarginPref.toString()) }
            var aliasTopMargin by remember { mutableStateOf(aliasTopMarginPref.toString()) }
            var signatureTopMargin by remember { mutableStateOf(signatureTopMarginPref.toString()) }
            var lightBg by remember { mutableStateOf(lightBgPref) }
            var darkBg by remember { mutableStateOf(darkBgPref) }
            var showName by remember { mutableStateOf(showNamePref) }
            var showAlias by remember { mutableStateOf(showAliasPref) }
            var showSignature by remember { mutableStateOf(showSignaturePref) }
            var nameText by remember { mutableStateOf(nameTextPref) }
            var aliasText by remember { mutableStateOf(aliasTextPref) }
            var signatureText by remember { mutableStateOf(signatureTextPref) }

            AlertDialogContent(
                title = { Text("资料卡居中") },
                text = {
                    DefaultColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            "内容可见性",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        ListItem(
                            modifier = Modifier.clickable { showName = !showName },
                            headlineContent = { Text("显示昵称") },
                            trailingContent = { Switch(showName, null) },
                        )
                        ListItem(
                            modifier = Modifier.clickable { showAlias = !showAlias },
                            headlineContent = { Text("显示微信号") },
                            trailingContent = { Switch(showAlias, null) },
                        )
                        ListItem(
                            modifier = Modifier.clickable { showSignature = !showSignature },
                            headlineContent = { Text("显示签名") },
                            trailingContent = { Switch(showSignature, null) },
                        )

                        Text(
                            "文本替换设定",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = nameText,
                            onValueChange = { nameText = it },
                            label = { Text("自定义昵称（留空使用原文）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = aliasText,
                            onValueChange = { aliasText = it },
                            label = { Text("自定义微信号（留空使用原文）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = signatureText,
                            onValueChange = { signatureText = it },
                            label = { Text("自定义签名（留空使用原文）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text(
                            "布局与尺寸配置",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = avatarTopMargin,
                            onValueChange = { avatarTopMargin = it.filter(Char::isDigit) },
                            label = { Text("头像顶部边距 dp") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = avatarSize,
                            onValueChange = { avatarSize = it.filter(Char::isDigit) },
                            label = { Text("头像大小 dp") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = nameTopMargin,
                            onValueChange = { nameTopMargin = it.filter(Char::isDigit) },
                            label = { Text("昵称顶部边距 dp") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = aliasTopMargin,
                            onValueChange = { aliasTopMargin = it.filter(Char::isDigit) },
                            label = { Text("微信号顶部边距 dp") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = signatureTopMargin,
                            onValueChange = { signatureTopMargin = it.filter(Char::isDigit) },
                            label = { Text("签名顶部边距 dp") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text(
                            "背景颜色配置",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        OutlinedTextField(
                            value = lightBg,
                            onValueChange = { lightBg = it },
                            label = { Text("亮色背景 ARGB") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = darkBg,
                            onValueChange = { darkBg = it },
                            label = { Text("暗色背景 ARGB") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        avatarTopMarginPref = avatarTopMargin.toPositiveInt(DEFAULT_AVATAR_TOP_MARGIN_DP)
                        avatarSizePref = avatarSize.toPositiveInt(DEFAULT_AVATAR_SIZE_DP).coerceAtLeast(1)
                        nameTopMarginPref = nameTopMargin.toPositiveInt(DEFAULT_NAME_TOP_MARGIN_DP)
                        aliasTopMarginPref = aliasTopMargin.toPositiveInt(DEFAULT_ALIAS_TOP_MARGIN_DP)
                        signatureTopMarginPref = signatureTopMargin.toPositiveInt(DEFAULT_SIGNATURE_TOP_MARGIN_DP)
                        lightBgPref = lightBg.takeIfValidColor(DEFAULT_LIGHT_BG)
                        darkBgPref = darkBg.takeIfValidColor(DEFAULT_DARK_BG)
                        showNamePref = showName
                        showAliasPref = showAlias
                        showSignaturePref = showSignature
                        nameTextPref = nameText.trim()
                        aliasTextPref = aliasText.trim()
                        signatureTextPref = signatureText.trim()
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                },
            )
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodBindAccountInfo.find(dexKit, allowMultiple = true) {
            matcher {
                declaredClass = ACCOUNT_INFO_PREFERENCE
                paramTypes(View::class.java.name)
            }
        }
    }

    private fun applyCenterCard(root: ViewGroup) {
        if (root.findViewWithTag<View>(CENTER_CARD_TAG) != null) return

        val source = collectSource(root)
        hideDescendants(root)

        val context = root.context
        val card = RelativeLayout(context).apply {
            tag = CENTER_CARD_TAG
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 12))
            setBackgroundColor(parseColor(if (context.isDarkMode) darkBgPref else lightBgPref, Color.TRANSPARENT))
            setOnClickListener { openPersonalInfoSettings(context) }
        }

        val avatarFrameId = View.generateViewId()
        val nameId = View.generateViewId()
        val aliasId = View.generateViewId()

        val avatarFrame = FrameLayout(context).apply {
            id = avatarFrameId
            layoutParams = RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                addRule(RelativeLayout.CENTER_HORIZONTAL)
                topMargin = dp(context, avatarTopMarginPref)
            }
        }
        val avatarPx = dp(context, avatarSizePref)
        avatarFrame.addView(ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(avatarPx, avatarPx)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(source.avatarDrawable ?: Color.TRANSPARENT.toDrawable())
        })

        card.addView(avatarFrame)
        card.addView(
            newTextView(
                context = context,
                id = nameId,
                anchorId = avatarFrameId,
                topMarginDp = nameTopMarginPref,
                textSizeSp = 18f,
                text = source.name,
                visible = showNamePref && source.name.isNotBlank(),
                maxLines = 1,
                style = Typeface.BOLD,
            )
        )
        card.addView(
            newTextView(
                context = context,
                id = aliasId,
                anchorId = nameId,
                topMarginDp = aliasTopMarginPref,
                textSizeSp = 16f,
                text = source.customWxId,
                visible = showAliasPref && source.customWxId.isNotBlank(),
                maxLines = Int.MAX_VALUE,
                style = Typeface.NORMAL,
            )
        )
        card.addView(
            newTextView(
                context = context,
                id = View.generateViewId(),
                anchorId = aliasId,
                topMarginDp = signatureTopMarginPref,
                textSizeSp = 14f,
                text = source.signature,
                visible = showSignaturePref && source.signature.isNotBlank(),
                maxLines = Int.MAX_VALUE,
                style = Typeface.NORMAL,
            )
        )

        root.addView(card)
    }

    private fun collectSource(root: ViewGroup): AccountInfoSource {
        val imageViews = mutableListOf<ImageView>()
        traverse(root) { view ->
            if (view is ImageView) {
                if (view.isVisible && view.drawable != null) imageViews += view
            }
        }

        val self = WeDatabaseApi.getFriend(WeApi.selfWxId)

        val name = nameTextPref.ifBlank { self?.nickname ?: "" }
        val customWxId = if (aliasTextPref.isNotBlank()) {
            "微信号: $aliasTextPref"
        } else {
            val alias = WeApi.selfCustomWxId
            if (alias.isNotBlank()) "微信号: $alias" else ""
        }
        val signature = if (signatureTextPref.isNotBlank()) {
            signatureTextPref
        } else {
            WeDatabaseApi.getSelfProfileField(SelfProfileField.SIGNATURE).toString()
        }

        return AccountInfoSource(
            avatarDrawable = imageViews.maxByOrNull { it.visibleArea() }?.drawable,
            name = name,
            customWxId = customWxId,
            signature = signature,
        )
    }

    private fun newTextView(
        context: Context,
        id: Int,
        anchorId: Int,
        topMarginDp: Int,
        textSizeSp: Float,
        text: String,
        visible: Boolean,
        maxLines: Int,
        style: Int,
    ): TextView = TextView(context).apply {
        this.id = id
        layoutParams = RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            addRule(RelativeLayout.CENTER_HORIZONTAL)
            addRule(RelativeLayout.BELOW, anchorId)
            topMargin = dp(context, topMarginDp)
        }
        gravity = Gravity.CENTER
        textSize = textSizeSp
        setText(text)
        this.maxLines = maxLines
        setTypeface(typeface, style)
        isVisible = visible
        if (context.isDarkMode) {
            setTextColor(Color.WHITE)
        } else {
            setTextColor(Color.BLACK)
        }
    }

    private fun hideDescendants(root: ViewGroup) {
        traverse(root) { view ->
            if (view !== root && view.tag != CENTER_CARD_TAG) {
                view.isGone = true
            }
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

    private fun openPersonalInfoSettings(context: Context) {
        runCatching {
            context.startActivity(Intent().setClassName(context, "com.tencent.mm.plugin.setting.ui.setting.SettingsPersonalInfoUI"))
        }.onFailure {
            WeLogger.e(TAG, "failed to open personal info settings", it)
        }
    }

    private fun View.visibleArea(): Int {
        val width = width.takeIf { it > 0 } ?: layoutParams?.width?.takeIf { it > 0 } ?: 0
        val height = height.takeIf { it > 0 } ?: layoutParams?.height?.takeIf { it > 0 } ?: 0
        return width * height
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun parseColor(value: String, fallback: Int): Int {
        return runCatching { value.toColorInt() }.getOrDefault(fallback)
    }

    private fun String.toPositiveInt(defaultValue: Int): Int {
        return toIntOrNull()?.coerceAtLeast(0) ?: defaultValue
    }

    private fun String.takeIfValidColor(defaultValue: String): String {
        val normalized = trim()
        return if (runCatching { normalized.toColorInt() }.isSuccess) normalized else defaultValue
    }

    private data class AccountInfoSource(
        val avatarDrawable: Drawable?,
        val name: String,
        val customWxId: String,
        val signature: String,
    )
}
