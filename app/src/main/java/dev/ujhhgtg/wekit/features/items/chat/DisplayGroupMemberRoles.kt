package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.WeColorField
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.collections.LruCache
import dev.ujhhgtg.wekit.utils.unreachable
import kotlin.math.roundToInt

@Feature(
    id = "显示群成员身份",
    nameRes = "feature_display_group_member_roles_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_display_group_member_roles_description",
)
object DisplayGroupMemberRoles : ClickableFeature(), IResolveDex,
    WeChatMessageViewApi.ICreateViewListener {

    private val methodGetChatroomData by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.ChatRoomMember", "getChatroomData hashMap is null!")
        }
    }

    // Pair<groupId: String, sender: String>, type: Int (1=owner, 2=admin, 3=member)
    private val resolvedRoles = LruCache<Pair<String, String>, Int>()

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
    }

    private const val DEFAULT_OWNER_BG = "#FFFFC107"
    private const val DEFAULT_ADMIN_BG = "#FF2196F3"
    private const val DEFAULT_MEMBER_BG = "#FF9E9E9E"
    private const val DEFAULT_OWNER_FG = "#FFFFFFFF"
    private const val DEFAULT_ADMIN_FG = "#FFFFFFFF"
    private const val DEFAULT_MEMBER_FG = "#FFFFFFFF"

    private var ownerBg by WePrefs.prefOption("group_role_owner_bg", DEFAULT_OWNER_BG)
    private var adminBg by WePrefs.prefOption("group_role_admin_bg", DEFAULT_ADMIN_BG)
    private var memberBg by WePrefs.prefOption("group_role_member_bg", DEFAULT_MEMBER_BG)
    private var ownerFg by WePrefs.prefOption("group_role_owner_fg", DEFAULT_OWNER_FG)
    private var adminFg by WePrefs.prefOption("group_role_admin_fg", DEFAULT_ADMIN_FG)
    private var memberFg by WePrefs.prefOption("group_role_member_fg", DEFAULT_MEMBER_FG)
    private var ownerText by WePrefs.prefOption("group_role_owner_text", "")
    private var adminText by WePrefs.prefOption("group_role_admin_text", "")
    private var memberText by WePrefs.prefOption("group_role_member_text", "")

    private var showMember by WePrefs.prefOption("group_role_show_member", true)

    private fun parseColor(value: String, fallback: String): Int =
        runCatching { value.toColorInt() }.getOrElse { fallback.toColorInt() }

    private fun roleText(value: String, defaultRes: Int): String =
        value.ifBlank { localizedChatString(defaultRes) }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var ob by remember { mutableStateOf(ownerBg) }
            var ab by remember { mutableStateOf(adminBg) }
            var mb by remember { mutableStateOf(memberBg) }
            var of by remember { mutableStateOf(ownerFg) }
            var af by remember { mutableStateOf(adminFg) }
            var mf by remember { mutableStateOf(memberFg) }
            val ownerDefault = stringResource(R.string.chat_group_role_owner)
            val adminDefault = stringResource(R.string.chat_group_role_admin)
            val memberDefault = stringResource(R.string.chat_group_role_member)
            var ot by remember { mutableStateOf(roleText(ownerText, R.string.chat_group_role_owner)) }
            var at by remember { mutableStateOf(roleText(adminText, R.string.chat_group_role_admin)) }
            var mt by remember { mutableStateOf(roleText(memberText, R.string.chat_group_role_member)) }
            var showMem by remember { mutableStateOf(showMember) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_display_group_member_roles_name)) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.chat_group_role_show_member),
                                    checked = showMem,
                                    onCheckedChange = { showMem = it },
                                )
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.chat_group_role_owner),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_owner_background),
                                ) {
                                    InlineColorField(value = ob, onValueChange = { ob = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_owner_foreground),
                                ) {
                                    InlineColorField(value = of, onValueChange = { of = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_owner_text),
                                ) {
                                    InlineRoleTextField(value = ot, onValueChange = { ot = it })
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.chat_group_role_admin),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_admin_background),
                                ) {
                                    InlineColorField(value = ab, onValueChange = { ab = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_admin_foreground),
                                ) {
                                    InlineColorField(value = af, onValueChange = { af = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_admin_text),
                                ) {
                                    InlineRoleTextField(value = at, onValueChange = { at = it })
                                }
                            }
                        }

                        SegmentedColumn(
                            title = stringResource(R.string.chat_group_role_member),
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_member_background),
                                ) {
                                    InlineColorField(value = mb, onValueChange = { mb = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_member_foreground),
                                ) {
                                    InlineColorField(value = mf, onValueChange = { mf = it })
                                }
                            }
                            item {
                                BaseSupportingWidget(
                                    title = stringResource(R.string.chat_group_role_member_text),
                                ) {
                                    InlineRoleTextField(value = mt, onValueChange = { mt = it })
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(onClick = {
                        ownerBg = ob
                        adminBg = ab
                        memberBg = mb
                        ownerFg = of
                        adminFg = af
                        memberFg = mf
                        showMember = showMem
                        ownerText = ot.takeUnless { it == ownerDefault }.orEmpty()
                        adminText = at.takeUnless { it == adminDefault }.orEmpty()
                        memberText = mt.takeUnless { it == memberDefault }.orEmpty()
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }

    override fun onCreateView(
        param: HookParam,
        view: View
    ) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        if (!msgInfo.isInGroupChat) return
        if (msgInfo.isSend != 0) return
        val sender = runCatching { msgInfo.sender }.getOrNull() ?: return
        val groupId = msgInfo.talker

        val role = resolvedRoles.getOrPut(groupId to sender) {
            val group = WeConversationApi.getGroup(groupId)
            val senderIsGroupOwner = group.reflekt()
                .firstField {
                    name = "field_roomowner"
                    superclass()
                }
                .get() as? String? == sender

            if (senderIsGroupOwner) return@getOrPut 1

            val memberData = methodGetChatroomData.method.invoke(group, sender) ?: return
            val memberRoleFlags = memberData.reflekt()
                .firstField {
                    type = Int::class
                }
                .get()!! as Int
            val senderIsGroupManager = memberRoleFlags and 2048 != 0

            return@getOrPut if (senderIsGroupManager) 2 else 3
        }

        // "成员" badge is optional; when hidden, leave the name untouched so downstream
        // hooks (e.g. LimitGroupMemberNicknameLength) see no role ReplacementSpan prefix.
        if (role == 3 && !showMember) return

        val tag = view.tag
        val textView = tag.reflekt()
            .firstField {
                name = "userTV"
                superclass()
            }
            // might be null and throw NPE, although it doesn't affect functionality, I don't want it to litter the error logs
            .get() as? TextView? ?: return
        val displayName = textView.text

        val roleText = when (role) {
            1 -> roleText(ownerText, R.string.chat_group_role_owner)
            2 -> roleText(adminText, R.string.chat_group_role_admin)
            3 -> roleText(memberText, R.string.chat_group_role_member)
            else -> unreachable()
        }

        val sb = SpannableStringBuilder()
        sb.append(roleText)
        sb.append(" ")
        sb.append(displayName)

        val bgColor = when (role) {
            1 -> parseColor(ownerBg, DEFAULT_OWNER_BG)
            2 -> parseColor(adminBg, DEFAULT_ADMIN_BG)
            3 -> parseColor(memberBg, DEFAULT_MEMBER_BG)
            else -> unreachable()
        }

        val fgColor = when (role) {
            1 -> parseColor(ownerFg, DEFAULT_OWNER_FG)
            2 -> parseColor(adminFg, DEFAULT_ADMIN_FG)
            3 -> parseColor(memberFg, DEFAULT_MEMBER_FG)
            else -> unreachable()
        }

        sb.setSpan(
            RoundedBackgroundSpan(
                backgroundColor = bgColor,
                textColor = fgColor,
                cornerRadius = 16f,
                padding = 10f
            ),
            0,
            roleText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        textView.text = sb
    }
}

/** Inline color field filling a [BaseSupportingWidget] supporting slot. */
@Composable
private fun InlineColorField(value: String, onValueChange: (String) -> Unit) {
    WeColorField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.color_picker_hex_value),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

/** Single-line inline text field filling a [BaseSupportingWidget] supporting slot. */
@Composable
private fun InlineRoleTextField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    )
}

private class RoundedBackgroundSpan(
    private val backgroundColor: Int,
    private val textColor: Int,
    private val cornerRadius: Float = 12f,
    private val padding: Float = 16f
) : ReplacementSpan() {

    override fun getSize(
        paint: Paint,
        text: CharSequence,
        start: Int,
        end: Int,
        fm: Paint.FontMetricsInt?
    ): Int {
        return (paint.measureText(text, start, end) + padding * 2).roundToInt()
    }

    override fun draw(
        canvas: Canvas,
        text: CharSequence,
        start: Int,
        end: Int,
        x: Float,
        top: Int,
        y: Int,
        bottom: Int,
        paint: Paint
    ) {
        val width = paint.measureText(text, start, end)

        val rect = RectF(x, top.toFloat(), x + width + padding * 2, bottom.toFloat())

        paint.color = backgroundColor
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

        paint.color = textColor
        canvas.drawText(text, start, end, x + padding, y.toFloat(), paint)
    }
}
