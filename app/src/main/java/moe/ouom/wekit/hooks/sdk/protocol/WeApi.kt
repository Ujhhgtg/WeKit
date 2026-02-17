package moe.ouom.wekit.hooks.sdk.protocol

import android.annotation.SuppressLint
import android.content.SharedPreferences
import moe.ouom.wekit.config.RuntimeConfig
import moe.ouom.wekit.hooks.sdk.api.WeMessageApi
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.util.log.WeLogger
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date

object WeApi {
    /**
     * 获取当前登录的微信ID
     */
    fun getSelfWxId(): String {
        val sharedPreferences: SharedPreferences =
            HostInfo.getApplication().getSharedPreferences("com.tencent.mm_preferences", 0)

        RuntimeConfig.setLogin_weixin_username(
            sharedPreferences.getString(
                "login_weixin_username",
                ""
            )
        )
        RuntimeConfig.setLast_login_nick_name(
            sharedPreferences.getString(
                "last_login_nick_name",
                ""
            )
        )
        RuntimeConfig.setLogin_user_name(sharedPreferences.getString("login_user_name", ""))
        RuntimeConfig.setLast_login_uin(sharedPreferences.getString("last_login_uin", "0"))

        return RuntimeConfig.getLogin_weixin_username()
    }

    /**
     * 生成 ClientMsgId
     */
    fun generateClientMsgId(wxId: String, timeMs: Long): Int {
        val rawString = generateClientMsgIdString(wxId, timeMs)
        return rawString.hashCode()
    }

    @SuppressLint("SimpleDateFormat")
    private fun generateClientMsgIdString(str: String?, j16: Long): String {
        val str2: String
        val str3 = SimpleDateFormat("ssHHmmMMddyy").format(Date(j16))
        if (str == null || str.length <= 1) {
            str2 = str3 + "fffffff"
        } else {
            val md5Hex = md5V2(str.toByteArray())
            str2 = str3 + md5Hex.take(7)
        }
        val suffixHex = String.format("%04x", j16 % 65535)
        val suffixNum = (j16 % 7) + 100
        return str2 + suffixHex + suffixNum
    }


    /**
     * 获取自己的微信号
     */
    fun getSelfAlias(): String {
        return WeMessageApi.INSTANCE?.getSelfAlias() ?: ""
    }

    /**
     * MD5 实现
     */
    private fun md5V2(bytes: ByteArray): String {
        return try {
            val digest = MessageDigest.getInstance("MD5")
            digest.update(bytes)
            val bArrDigest = digest.digest()
            val sb = StringBuilder()
            for (b in bArrDigest) {
                val i = b.toInt() and 0xFF
                var hex = Integer.toHexString(i)
                if (hex.length < 2) {
                    hex = "0$hex"
                }
                sb.append(hex)
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }
}

/**
 * MsgId 预览工具
 */
object MsgIdProvider {

    private const val MMKV_FILE_ID = "db_max_id_record"
    private const val KEY_PREFIX = "msg."

    /**
     * 获取下一个可用的 MsgId
     * @param tableName 表名，主表为 "message"，小程序为 "appbrandmessage" 等
     */
    fun previewNextId(tableName: String): Long {
        return try {
            val mmkvClass = Class.forName("com.tencent.mmkv.MMKV")
            val mmkvWithIDMethod = mmkvClass.getDeclaredMethod(
                "mmkvWithID",
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val mmkvInstance = mmkvWithIDMethod.invoke(null, MMKV_FILE_ID, 2)
            val decodeLongMethod = mmkvClass.getDeclaredMethod(
                "decodeLong",
                String::class.java,
                Long::class.javaPrimitiveType
            )

            val currentId =
                decodeLongMethod.invoke(mmkvInstance, "$KEY_PREFIX$tableName", 0L) as Long

            if (currentId == 0L) {
                getInitialId(tableName)
            } else {
                calculateNext(tableName, currentId)
            }
        } catch (e: Exception) {
            WeLogger.e("MsgIdProvider", "MMKV 反射失败: ${e.message}")
            System.currentTimeMillis()
        }
    }

    private fun calculateNext(tableName: String, current: Long): Long {
        return when (tableName) {
            "message" -> when (current) {
                1_000_000L -> 10_000_000L
                90_000_000L -> 500_000_001L
                else -> current + 1
            }

            "qmessage" -> if (current == 1_500_000L) 90_000_001L else current + 1
            "tmessage" -> if (current == 2_000_000L) 93_000_001L else current + 1
            "bottlemessage" -> if (current == 2_500_000L) 96_000_001L else current + 1
            "bizchatmessage" -> if (current == 3_000_000L) 99_000_001L else current + 1
            "appbrandmessage" -> if (current == 3_500_000L) 102_000_001L else current + 1
            "findermessage006" -> if (current == 4_500_000L) 108_000_001L else current + 1
            "gamelifemessage" -> if (current == 5_000_000L) 208_000_001L else current + 1
            "textstatusmessage" -> if (current == 5_500_000L) 308_000_001L else current + 1
            "bizfansmessage" -> if (current == 6_000_000L) 408_000_001L else current + 1
            else -> current + 1
        }
    }

    private fun getInitialId(tableName: String): Long {
        return when (tableName) {
            "message" -> 1L
            "qmessage" -> 1_000_001L
            "tmessage" -> 1_500_001L
            "bottlemessage" -> 2_000_001L
            "bizchatmessage" -> 2_500_001L
            "appbrandmessage" -> 3_000_001L
            "findermessage006" -> 4_000_001L
            "gamelifemessage" -> 4_500_001L
            "textstatusmessage" -> 5_000_001L
            "bizfansmessage" -> 5_500_001L
            else -> 1L
        }
    }
}