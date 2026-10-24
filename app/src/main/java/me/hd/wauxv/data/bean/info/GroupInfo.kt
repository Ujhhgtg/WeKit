package me.hd.wauxv.data.bean.info

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.features.api.core.models.WeGroup

@Keep
data class GroupInfo(
    var roomId: String = "",
    var remark: String = "",
    var name: String = "",
    var groupData: GroupData = GroupData(),
) {
    constructor(
        group: WeGroup,
    ) : this(
        roomId = group.wxId,
        remark = "",
        name = group.nickname,
        groupData = GroupData(group.wxId),
    )
}
