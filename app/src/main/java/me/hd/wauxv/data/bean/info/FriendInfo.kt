package me.hd.wauxv.data.bean.info

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.features.api.core.models.WeContact

@Keep
data class FriendInfo(
    var wxid: String = "",
    var alias: String = "",
    var remark: String = "",
    var nickname: String = "",
    var type: Int = 0,
    var sourceExtInfo: String = "",
    var createTime: Long = 0,
) {
    constructor(
        contact: WeContact,
    ) : this(
        wxid = contact.wxId,
        alias = contact.customWxId,
        remark = contact.remarkName,
        nickname = contact.nickname,
        type = contact.type,
        sourceExtInfo = "",
        createTime = 0L,
    )
}
