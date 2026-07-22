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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.graphics.toColorInt
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.collections.LruCache
import dev.ujhhgtg.wekit.utils.unreachable
import kotlin.math.roundToInt

@Feature(name = "显示群成员身份", categories = ["聊天"], description = "在群聊中显示群成员的身份: 群主, 管理员, 成员")
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
    private var ownerText by WePrefs.prefOption("group_role_owner_text", "群主")
    private var adminText by WePrefs.prefOption("group_role_admin_text", "管理员")
    private var memberText by WePrefs.prefOption("group_role_member_text", "成员")

    private var showMember by WePrefs.prefOption("group_role_show_member", true)

    private fun parseColor(value: String, fallback: String): Int =
        runCatching { value.toColorInt() }.getOrElse { fallback.toColorInt() }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var ob by remember { mutableStateOf(ownerBg) }
            var ab by remember { mutableStateOf(adminBg) }
            var mb by remember { mutableStateOf(memberBg) }
            var of by remember { mutableStateOf(ownerFg) }
            var af by remember { mutableStateOf(adminFg) }
            var mf by remember { mutableStateOf(memberFg) }
            var ot by remember { mutableStateOf(ownerText) }
            var at by remember { mutableStateOf(adminText) }
            var mt by remember { mutableStateOf(memberText) }
            var showMem by remember { mutableStateOf(showMember) }

            AlertDialogContent(
                title = { Text("显示群成员身份") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            modifier = Modifier.clickable { showMem = !showMem },
                            trailingContent = { Switch(showMem, null) },
                            headlineContent = { Text("显示「成员」标签") },
                        )
                        TextField(
                            label = { Text("群主 | 背景色") },
                            value = ob,
                            onValueChange = { ob = it })
                        TextField(
                            label = { Text("群主 | 前景色") },
                            value = of,
                            onValueChange = { of = it })
                        TextField(
                            label = { Text("管理员 | 背景色") },
                            value = ab,
                            onValueChange = { ab = it })
                        TextField(
                            label = { Text("管理员 | 前景色") },
                            value = af,
                            onValueChange = { af = it })
                        TextField(
                            label = { Text("成员 | 背景色") },
                            value = mb,
                            onValueChange = { mb = it })
                        TextField(
                            label = { Text("成员 | 前景色") },
                            value = mf,
                            onValueChange = { mf = it })
                        TextField(
                            label = { Text("群主 | 文本") },
                            value = ot,
                            onValueChange = { ot = it })
                        TextField(
                            label = { Text("管理员 | 文本") },
                            value = at,
                            onValueChange = { at = it })
                        TextField(
                            label = { Text("成员 | 文本") },
                            value = mt,
                            onValueChange = { mt = it })
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        ownerBg = ob
                        adminBg = ab
                        memberBg = mb
                        ownerFg = of
                        adminFg = af
                        memberFg = mf
                        showMember = showMem
                        ownerText = ot
                        adminText = at
                        memberText = mt
                        onDismiss()
                    }) { Text("确定") }
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
            1 -> ownerText
            2 -> adminText
            3 -> memberText
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
