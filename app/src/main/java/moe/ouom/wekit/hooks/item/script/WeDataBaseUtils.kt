@file:Suppress("unused")

package moe.ouom.wekit.hooks.item.script

import moe.ouom.wekit.hooks.sdk.base.WeDatabaseApi
import moe.ouom.wekit.utils.log.WeLogger
import org.json.JSONArray
import org.json.JSONObject

object WeDataBaseUtils {
    private const val TAG = "WeDataBaseUtils"

    fun query(sql: String): Any {
        return try {
            WeDatabaseApi.executeQuery(sql).map { row ->
                val jsonObject = JSONObject()
                for ((key, value) in row) {
                    jsonObject.put(key, value)
                }
                jsonObject
            }?.let { JSONArray(it) } ?: JSONArray()
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "SQL执行异常: ${e.message}")
            JSONArray()
        }
    }

    fun getAllContacts(): Any {
        return try {
            WeDatabaseApi.getContacts().map { contact ->
                val jsonObject = JSONObject()
                jsonObject.put("username", contact.username)
                jsonObject.put("nickname", contact.nickname)
                jsonObject.put("alias", contact.alias)
                jsonObject.put("conRemark", contact.conRemark)
                jsonObject.put("pyInitial", contact.pyInitial)
                jsonObject.put("quanPin", contact.quanPin)
                jsonObject.put("avatarUrl", contact.avatarUrl)
                jsonObject.put("encryptUserName", contact.encryptUserName)
                jsonObject
            }?.let { JSONArray(it) } ?: JSONArray()
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "获取联系人异常: ${e.message}")
            JSONArray()
        }
    }

    fun getContactList(): Any {
        return try {
            WeDatabaseApi.getFriends().map { contact ->
                val jsonObject = JSONObject()
                jsonObject.put("username", contact.username)
                jsonObject.put("nickname", contact.nickname)
                jsonObject.put("alias", contact.alias)
                jsonObject.put("conRemark", contact.conRemark)
                jsonObject.put("pyInitial", contact.pyInitial)
                jsonObject.put("quanPin", contact.quanPin)
                jsonObject.put("avatarUrl", contact.avatarUrl)
                jsonObject.put("encryptUserName", contact.encryptUserName)
                jsonObject
            }?.let { JSONArray(it) } ?: JSONArray()
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "获取好友异常: ${e.message}")
            JSONArray()
        }
    }

    fun getChatrooms(): Any {
        return try {
            WeDatabaseApi.getGroups().map { group ->
                val jsonObject = JSONObject()
                jsonObject.put("username", group.username)
                jsonObject.put("nickname", group.nickname)
                jsonObject.put("pyInitial", group.pyInitial)
                jsonObject.put("quanPin", group.quanPin)
                jsonObject.put("avatarUrl", group.avatarUrl)
                jsonObject
            }?.let { JSONArray(it) } ?: JSONArray()
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "获取群聊异常: ${e.message}")
            JSONArray()
        }
    }

    fun getOfficialAccounts(): Any {
        return try {
            WeDatabaseApi.getOfficialAccounts().map { account ->
                val jsonObject = JSONObject()
                jsonObject.put("username", account.username)
                jsonObject.put("nickname", account.nickname)
                jsonObject.put("alias", account.alias)
                jsonObject.put("signature", account.signature)
                jsonObject.put("avatarUrl", account.avatarUrl)
                jsonObject
            }?.let { JSONArray(it) } ?: JSONArray()
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "获取公众号异常: ${e.message}")
            JSONArray()
        }
    }

    fun getMessages(wxid: String, page: Int = 1, pageSize: Int = 20): Any {
        return try {
            if (wxid.isEmpty()) return JSONArray()
            WeDatabaseApi.getMessages(wxid, page, pageSize).map { message ->
                val jsonObject = JSONObject()
                jsonObject.put("msgId", message.msgId)
                jsonObject.put("talker", message.talker)
                jsonObject.put("content", message.content)
                jsonObject.put("type", message.type)
                jsonObject.put("createTime", message.createTime)
                jsonObject.put("isSend", message.isSend)
                jsonObject
            }?.let { JSONArray(it) } ?: JSONArray()
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "获取消息异常: ${e.message}")
            JSONArray()
        }
    }

    fun getAvatarUrl(wxid: String): String {
        return try {
            WeDatabaseApi.getAvatarUrl(wxid)
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "获取头像异常: ${e.message}")
            ""
        }
    }

    fun getGroupMembers(chatroomId: String): Any {
        return try {
            if (!chatroomId.endsWith("@chatroom")) return JSONArray()
            WeDatabaseApi.getGroupMembers(chatroomId).map { member ->
                val jsonObject = JSONObject()
                jsonObject.put("username", member.username)
                jsonObject.put("nickname", member.nickname)
                jsonObject.put("alias", member.alias)
                jsonObject.put("conRemark", member.conRemark)
                jsonObject.put("pyInitial", member.pyInitial)
                jsonObject.put("quanPin", member.quanPin)
                jsonObject.put("avatarUrl", member.avatarUrl)
                jsonObject.put("encryptUserName", member.encryptUserName)
                jsonObject
            }?.let { JSONArray(it) } ?: JSONArray()
        } catch (e: Exception) {
            WeLogger.e("WeDatabaseApi", "获取群成员异常: ${e.message}")
            JSONArray()
        }
    }
}