package dev.ujhhgtg.wekit.features.items.beautify

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
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.SelfProfileField
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseItemContainer
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.ColorPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.IntNumberPickerWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode

object CenterProfileCard : ClickableFeature(), IResolveDex {

    override val technicalId = "资料卡居中"
    override val nameRes = R.string.feature_center_profile_card_name
    override val categoryIds = listOf(FeatureCategoryIds.BEAUTIFY, FeatureCategoryIds.PROFILE)
    override val descriptionRes = R.string.feature_center_profile_card_description

    private const val TAG = "CenterProfileCard"
    private const val CENTER_CARD_TAG = "wekit_account_info_center_card"

    private const val DEFAULT_AVATAR_TOP_MARGIN_DP = 40
    private const val DEFAULT_AVATAR_SIZE_DP = 80
    private const val DEFAULT_NAME_TOP_MARGIN_DP = 4
    private const val DEFAULT_ALIAS_TOP_MARGIN_DP = 4
    private const val DEFAULT_SIGNATURE_TOP_MARGIN_DP = 4
    private const val DEFAULT_LIGHT_BG = "#FFFFFFFF"
    private const val DEFAULT_DARK_BG = "#FF191919"

    private const val MIN_LAYOUT_DP = 0
    private const val MAX_LAYOUT_DP = 200

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

    private val methodBindAccountInfo by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.preference.AccountInfoPreference"
            paramTypes(View::class.java)
        }
    }

    override fun onEnable() {
        methodBindAccountInfo.hookAfter {
            val root = args[0] as? ViewGroup ?: return@hookAfter
            root.post {
                runCatching { applyCenterCard(root) }
                    .onFailure { WeLogger.e(TAG, "failed to center account info card", it) }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var avatarTopMargin by remember {
                mutableIntStateOf(avatarTopMarginPref.coerceIn(MIN_LAYOUT_DP, MAX_LAYOUT_DP))
            }
            var avatarSize by remember {
                mutableIntStateOf(avatarSizePref.coerceIn(MIN_LAYOUT_DP, MAX_LAYOUT_DP))
            }
            var nameTopMargin by remember {
                mutableIntStateOf(nameTopMarginPref.coerceIn(MIN_LAYOUT_DP, MAX_LAYOUT_DP))
            }
            var aliasTopMargin by remember {
                mutableIntStateOf(aliasTopMarginPref.coerceIn(MIN_LAYOUT_DP, MAX_LAYOUT_DP))
            }
            var signatureTopMargin by remember {
                mutableIntStateOf(signatureTopMarginPref.coerceIn(MIN_LAYOUT_DP, MAX_LAYOUT_DP))
            }
            var avatarTopMarginChanged by remember { mutableStateOf(false) }
            var avatarSizeChanged by remember { mutableStateOf(false) }
            var nameTopMarginChanged by remember { mutableStateOf(false) }
            var aliasTopMarginChanged by remember { mutableStateOf(false) }
            var signatureTopMarginChanged by remember { mutableStateOf(false) }
            var lightBg by remember { mutableStateOf(lightBgPref) }
            var darkBg by remember { mutableStateOf(darkBgPref) }
            var showName by remember { mutableStateOf(showNamePref) }
            var showAlias by remember { mutableStateOf(showAliasPref) }
            var showSignature by remember { mutableStateOf(showSignaturePref) }
            var nameText by remember { mutableStateOf(nameTextPref) }
            var aliasText by remember { mutableStateOf(aliasTextPref) }
            var signatureText by remember { mutableStateOf(signatureTextPref) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_profile_card_title)) },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        SegmentedColumn(
                            title = stringResource(R.string.beautify_profile_card_visibility),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.beautify_profile_card_show_nickname),
                                    checked = showName,
                                    onCheckedChange = { showName = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.beautify_profile_card_show_wechat_id),
                                    checked = showAlias,
                                    onCheckedChange = { showAlias = it },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.beautify_profile_card_show_signature),
                                    checked = showSignature,
                                    onCheckedChange = { showSignature = it },
                                )
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.beautify_profile_card_text_replacements),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.beautify_profile_card_custom_nickname),
                                ) {
                                    InlineTextField(value = nameText, onValueChange = { nameText = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.beautify_profile_card_custom_wechat_id),
                                ) {
                                    InlineTextField(value = aliasText, onValueChange = { aliasText = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.beautify_profile_card_custom_signature),
                                ) {
                                    InlineTextField(value = signatureText, onValueChange = { signatureText = it })
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.beautify_profile_card_layout),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.beautify_profile_card_avatar_top_margin),
                                        value = avatarTopMargin,
                                        startInt = MIN_LAYOUT_DP,
                                        endInt = MAX_LAYOUT_DP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            avatarTopMargin = it
                                            avatarTopMarginChanged = true
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.beautify_profile_card_avatar_size),
                                        value = avatarSize,
                                        startInt = MIN_LAYOUT_DP,
                                        endInt = MAX_LAYOUT_DP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            avatarSize = it
                                            avatarSizeChanged = true
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.beautify_profile_card_nickname_top_margin),
                                        value = nameTopMargin,
                                        startInt = MIN_LAYOUT_DP,
                                        endInt = MAX_LAYOUT_DP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            nameTopMargin = it
                                            nameTopMarginChanged = true
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.beautify_profile_card_wechat_id_top_margin),
                                        value = aliasTopMargin,
                                        startInt = MIN_LAYOUT_DP,
                                        endInt = MAX_LAYOUT_DP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            aliasTopMargin = it
                                            aliasTopMarginChanged = true
                                        },
                                    )
                                }
                            }
                            item {
                                BaseItemContainer {
                                    IntNumberPickerWidget(
                                        title = stringResource(R.string.beautify_profile_card_signature_top_margin),
                                        value = signatureTopMargin,
                                        startInt = MIN_LAYOUT_DP,
                                        endInt = MAX_LAYOUT_DP,
                                        stepSize = 1,
                                        valueSuffix = "dp",
                                        onValueChange = {
                                            signatureTopMargin = it
                                            signatureTopMarginChanged = true
                                        },
                                    )
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.beautify_profile_card_background_colors),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.beautify_profile_card_light_background),
                                    value = lightBg,
                                    onValueChange = { lightBg = it },
                                )
                            }
                            item {
                                ColorPickerWidget(
                                    title = stringResource(R.string.beautify_profile_card_dark_background),
                                    value = darkBg,
                                    onValueChange = { darkBg = it },
                                )
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        if (avatarTopMarginChanged) avatarTopMarginPref = avatarTopMargin
                        if (avatarSizeChanged) avatarSizePref = avatarSize.coerceAtLeast(1)
                        if (nameTopMarginChanged) nameTopMarginPref = nameTopMargin
                        if (aliasTopMarginChanged) aliasTopMarginPref = aliasTopMargin
                        if (signatureTopMarginChanged) signatureTopMarginPref = signatureTopMargin
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
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        }
    }

    /** Single-line inline text field filling a [BaseSupportingWidget] supporting slot. */
    @Composable
    private fun InlineTextField(value: String, onValueChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        )
    }

    private fun applyCenterCard(root: ViewGroup) {
        // 1. Remove the old centered card if it already exists to avoid duplication
        val oldCard = root.findViewWithTag<View>(CENTER_CARD_TAG)
        if (oldCard != null) {
            root.removeView(oldCard)
        }

        // 2. Temporarily make original views VISIBLE so collectSource can find them properly
        for (i in 0 until root.childCount) {
            root.getChildAt(i).isVisible = true
        }

        // 3. Collect the updated source data from the original views safely
        val source = collectSource(root)

        // 4. Hide all original views so they don't render or alter layout measurements
        for (i in 0 until root.childCount) {
            root.getChildAt(i).isVisible = false
        }

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
        val avatarImage = root.findViewsWhich { view ->
            view is ImageView && view.isVisible && view.drawable != null
        }.map { it as ImageView }.maxByOrNull { it.visibleArea() } ?: error("failed to find avatar image view")

        val self = WeDatabaseApi.getFriend(WeApi.selfWxId)

        val name = nameTextPref.ifBlank { self?.nickname ?: "" }
        val customWxId = if (aliasTextPref.isNotBlank()) {
            root.context.localizedBeautifyString(R.string.beautify_profile_card_wechat_id_value, aliasTextPref)
        } else {
            val alias = WeApi.selfCustomWxId
            if (alias.isNotBlank()) root.context.localizedBeautifyString(R.string.beautify_profile_card_wechat_id_value, alias) else ""
        }
        val signature = signatureTextPref.ifBlank {
            WeDatabaseApi.getSelfProfileField(
                SelfProfileField.SIGNATURE,
                root.context.localizedBeautifyString(R.string.beautify_profile_card_signature_unavailable),
            ).toString()
        }

        return AccountInfoSource(
            avatarDrawable = avatarImage.drawable,
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
