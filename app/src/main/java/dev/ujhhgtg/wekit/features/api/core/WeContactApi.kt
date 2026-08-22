package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeContactApi.deleteContact
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BlockContactProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.DelContactProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.OpLog
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.UserNameProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Feature(name = "联系人服务", categories = ["API"], description = "提供联系人管理能力")
object WeContactApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeContactApi"

    /** How aggressively [deleteContact] should remove a contact. */
    enum class DeleteMode {
        /** Remove the contact only. */
        DELETE_ONLY,

        /** Block the contact (add to blacklist), then remove it. */
        BLOCK_AND_DELETE
    }

    /**
     * Delete (and optionally block) a contact via the `oplog` CGI.
     *
     * Modern WeChat has no standalone `deletecontact` CGI; contact removal is funneled through
     * the generic oplog endpoint as [OpLog.CMD_DELETE_CONTACT] (and [OpLog.CMD_BLOCK_CONTACT]
     * for blocking). [DeleteMode.BLOCK_AND_DELETE] packs both operations into a single oplog request.
     *
     * Suspends until the server responds, returning `true` on success and `false` on failure.
     * Callers that delete in bulk should space out invocations themselves, as WeChat's server
     * rate-limits these requests.
     */
    suspend fun deleteContact(wxId: String, mode: DeleteMode = DeleteMode.DELETE_ONLY): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val operations = buildList {
                    if (mode == DeleteMode.BLOCK_AND_DELETE) {
                        add(OpLog.operation(OpLog.CMD_BLOCK_CONTACT, BlockContactProto(UserNameProto(wxId))))
                    }
                    add(OpLog.operation(OpLog.CMD_DELETE_CONTACT, DelContactProto(UserNameProto(wxId))))
                }

                WePacketHelper.sendCgi(
                    "/cgi-bin/micromsg-bin/oplog", 681, 0, 0, OpLog.encodeRequest(operations)
                ) {
                    onSuccess { _ -> if (cont.isActive) cont.resume(true) }
                    onFailure { errType, errCode, errMsg ->
                        WeLogger.w(TAG, "deleteContact $wxId failed: $errType, $errCode, $errMsg")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "deleteContact $wxId failed", e)
                if (cont.isActive) cont.resume(false)
            }
        }

    private val ctorNetSceneVerifyUser by dexConstructor {
        searchPackages("com.tencent.mm.pluginsdk.model")
        matcher {
            usingEqStrings("MicroMsg.NetSceneVerifyUser.dkverify", "getLabelIdList, %s")
        }
    }

    fun verifyUser(userId: String, ticket: String, scene: Int, privacy: Int = 0) {
        try {
            val netScene = ctorNetSceneVerifyUser.newInstance(3, userId, ticket, scene, "", privacy, null, null)
            WeNetSceneApi.sendNetScene(netScene)
        } catch (e: Exception) {
            WeLogger.e("WeContactApi", "verifyUser failed", e)
        }
    }

    // =========================================================================
    // 方案 B：走微信自身 modContact oplog 改备注
    // =========================================================================
    //
    // 微信通讯录右侧字母分组（J/C/Z…）按 `rcontact.conRemarkPYFull` 排序。直接 SQL 写库（方案 A）
    // 虽然能把拼音列写对，但微信进程内的通讯录缓存不会因此重算，分组字母常常不立即归位。
    //
    // 方案 B 改用微信自己吐 oplog 的链路：拿到联系人原生对象 `y3` → 设 `field_conRemark` →
    // 调 `ContactStorageLogic.toModContactOplog(y3)` 得到 `tn4` proto 字节 → 以 oplog cmd=2
    // (funcId 681) 发出。微信收到后**自己**重算拼音、写回 `conRemarkPYFull` 并通知列表刷新，
    // 分组字母立即归位。整条链路在微信进程内完成，**不依赖服务器返回任何拼音数据，纯本地**。
    //
    // 注：该 oplog 仍会走微信的同步通道（服务器最终也会记录备注，与你在微信里手动改备注等价），
    // 但拼音分组的计算与列表刷新是微信本地完成的，无"云控拼音"风险。

    /**
     * `com.tencent.mm.storage.ContactStorage` 的具体实现类（`get(String username)` 取原生联系人对象）。
     *
     * 锚点用实现类 `get(String)` 方法专属的日志串 `"[get] contact="` 配合 log tag `"MicroMsg.ContactStorage"`。
     * 经 dexdump 核对：8.0.71 实现类 = `com.tencent.mm.storage.j4`、8.0.76 = `com.tencent.mm.storage.i4`，
     * 两者都**唯一同时**含这两个串（其他含 `MicroMsg.ContactStorage` 的是接口/基类/UI 类，不含 `[get] contact=`）。
     * 之前用 `"get null with username:"`（属 ConversationStorage）和 `"rcontact db init select count: %d"`
     * （属 `com.tencent.mm.ui.contact.q` UI 类）都错配，导致 DexKit 找不到类。
     */
    private val classContactStorage by dexClass(allowFailure = true) {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.ContactStorage", "[get] contact=")
        }
    }

    /** `ContactStorageLogic.toModContactOplog(y3)` → `tn4` proto 字节（oplog cmd 2 的 payload）。 */
    private val methodBuildModContactOplog by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings(
                "MicroMsg.ContactStorageLogic",
                "oplog modContact user:%s remark:%s BitVal:%d BitValue2:%s isInConvBox:%s isTop:%s isMute:%s"
            )
        }
    }

    /** 缓存的 ContactStorage 实例（从 storageFeatureService 取）。DexKit 锚定失败时返回 null。 */
    private val contactStorage: Any? by lazy {
        if (classContactStorage.isPlaceholder) {
            WeLogger.w(TAG, "contactStorage: classContactStorage anchor failed, scheme B disabled")
            null
        } else {
            runCatching {
                WeServiceApi.storageFeatureService.reflekt()
                    .firstMethod { returnType = classContactStorage.clazz }
                    .invoke()
            }.getOrNull()
        }
    }

    /**
     * 通过微信自身 modContact oplog 把 [username] 的备注改成 [remark]。
     *
     * @return `true` 表示 oplog 已成功构造并发出（微信本地会重算拼音并刷新列表）；
     *         `false` 表示本机 DexKit 锚定失败或调用异常，调用方应回退到方案 A。
     */
    fun setRemarkViaModContact(username: String, remark: String): Boolean {
        return runCatching {
            val storage = contactStorage
                ?: return@runCatching run {
                    WeLogger.w(TAG, "setRemarkViaModContact: ContactStorage unavailable")
                    false
                }
            // 1. 取原生联系人对象 y3：ContactStorage 上「参数为单一 String」的 get 方法即按 username 取联系人。
            val getMethod = storage.reflekt().firstMethod { parameters(String::class.java) }
            val contact = getMethod.invoke(storage, username)
                ?: return@runCatching run {
                    WeLogger.w(TAG, "setRemarkViaModContact: contact null for $username")
                    false
                }
            // 2. 设 conRemark（field_conRemark 是未被混淆的真实列名）
            contact.reflekt().setField("field_conRemark", remark)
            // 3. 调 toModContactOplog 得 tn4 字节（锚点失败时跳过，回退方案 A）
            if (methodBuildModContactOplog.isPlaceholder) {
                return@runCatching run {
                    WeLogger.w(TAG, "setRemarkViaModContact: methodBuildModContactOplog anchor failed")
                    false
                }
            }
            val tn4 = methodBuildModContactOplog.method.invoke(null, contact) as? ByteArray
                ?: return@runCatching run {
                    WeLogger.w(TAG, "setRemarkViaModContact: toModContactOplog returned null")
                    false
                }
            // 4. 包成 oplog cmd 2 发出
            val reqBytes = OpLog.encodeSingleRaw(OpLog.CMD_MOD_CONTACT, tn4)
            WePacketHelper.sendCgi(
                "/cgi-bin/micromsg-bin/oplog", 681, 0, 0, reqBytes
            ) { onSuccess { _ -> WeLogger.i(TAG, "setRemarkViaModContact ok: $username") } }
            true
        }.getOrElse { e ->
            WeLogger.e(TAG, "setRemarkViaModContact failed for $username", e)
            false
        }
    }
}
