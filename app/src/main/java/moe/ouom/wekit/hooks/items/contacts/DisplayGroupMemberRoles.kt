package moe.ouom.wekit.hooks.items.contacts

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.widget.TextView
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import de.robv.android.xposed.XC_MethodHook
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.base.WeConversationApi
import moe.ouom.wekit.hooks.sdk.base.model.MessageInfo
import moe.ouom.wekit.hooks.sdk.ui.WeChatMessageViewApi
import moe.ouom.wekit.utils.LruCache
import org.luckypray.dexkit.DexKitBridge
import kotlin.math.roundToInt

@HookItem(
    path = "联系人与群组/显示群成员身份",
    desc = "在群聊中显示群成员的身份: 群主, 管理员, 成员"
)
object DisplayGroupMemberRoles : SwitchHookItem(), IDexFind,
    WeChatMessageViewApi.ICreateViewListener {

    private val methodGetChatroomData by dexMethod()

    // Pair<groupId: String, sender: String>, type: Int (1=owner, 2=admin, 3=member)
    private val resolvedRoles = LruCache<Pair<String, String>, Int>()

    override fun onLoad() {
        WeChatMessageViewApi.addListener(this)
    }

    override fun onUnload() {
        WeChatMessageViewApi.removeListener(this)
    }

    private const val OWNER_COLOR = 0xFFFFC107
    private const val ADMIN_COLOR = 0xFF2196F3
    private const val MEMBER_COLOR = 0xFF9E9E9E

    override fun onCreateView(
        param: XC_MethodHook.MethodHookParam,
        view: View,
        chattingContext: Any,
        msgInfo: MessageInfo
    ) {
        if (!msgInfo.isInGroupChat) return
        val sender = runCatching { msgInfo.sender }.getOrNull() ?: return
        val groupId = msgInfo.talker

        val role = resolvedRoles.getOrPut(groupId to sender) {
            val group = WeConversationApi.getGroup(groupId)
            val senderIsGroupOwner = group.asResolver()
                .firstField {
                    name = "field_roomowner"
                    superclass()
                }
                .get()!! as String == sender

            if (senderIsGroupOwner) return@getOrPut 1

            val memberData = methodGetChatroomData.method.invoke(group, sender) ?: return
            val memberRoleFlags = memberData.asResolver()
                .firstField {
                    type = Int::class
                }
                .get()!! as Int
            val senderIsGroupManager = (memberRoleFlags and 2048) != 0

            return@getOrPut if (senderIsGroupManager) 2 else 3
        }

        val tag = view.tag
        val textView = tag.asResolver()
            .firstField {
                name = "userTV"
                superclass()
            }
            // might be null and throw NPE, although it doesn't affect functionality, I don't want it to litter the error logs
            .get() as? TextView? ?: return
        val displayName = textView.text

        val roleText = when (role) {
            1 -> "群主"
            2 -> "管理员"
            3 -> "成员"
            else -> error("unreachable")
        }

        val fullText = "$roleText $displayName"
        val sb = SpannableStringBuilder(fullText)

        val bgColor = when (role) {
            1 -> OWNER_COLOR.toInt()
            2 -> ADMIN_COLOR.toInt()
            3 -> MEMBER_COLOR.toInt()
            else -> error("unreachable")
        }

        sb.setSpan(
            RoundedBackgroundSpan(
                backgroundColor = bgColor,
                textColor = 0xFFFFFFFF.toInt(),
                cornerRadius = 16f,
                padding = 10f
            ),
            0,
            roleText.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        textView.text = sb
    }

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // this is actually get group MEMBER data
        methodGetChatroomData.find(dexKit, descriptors) {
            matcher {
                usingEqStrings("MicroMsg.ChatRoomMember", "getChatroomData hashMap is null!")
            }
        }

        return descriptors
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