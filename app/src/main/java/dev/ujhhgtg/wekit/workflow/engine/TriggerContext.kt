package dev.ujhhgtg.wekit.workflow.engine

/**
 * Variables injected into the workflow execution context by the triggering event.
 *
 * Accessed in nodes via [dev.ujhhgtg.wekit.workflow.model.WorkflowValue.TriggerInput].
 *
 * ## Available fields per trigger type
 *
 * | Trigger | Fields |
 * |---|---|
 * | [dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger.NewMessage] | `sender`, `talker`, `content`, `msgType`, `msgSvrId`, `isGroup` |
 * | [dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger.NewMoment] | `sender`, `content`, `snsId`, `type` |
 * | [dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger.NewTransfer] | `sender`, `amount`, `currency`, `note`, `msgSvrId` |
 * | [dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger.NewRedPacket] | `sender`, `groupId`, `senderName`, `msgSvrId` |
 * | [dev.ujhhgtg.wekit.workflow.model.WorkflowTrigger.Schedule] | `firedAt` |
 */
data class TriggerContext(
    /** Map of field name → string value, as described above. */
    val fields: Map<String, String>,
) {
    companion object {
        fun newMessage(
            sender: String,
            talker: String,
            content: String,
            msgType: Int,
            msgSvrId: Long,
        ) = TriggerContext(
            mapOf(
                "sender" to sender,
                "talker" to talker,
                "content" to content,
                "msgType" to msgType.toString(),
                "msgSvrId" to msgSvrId.toString(),
                "isGroup" to (talker.endsWith("@chatroom")).toString(),
            )
        )

        fun newMoment(
            sender: String,
            content: String,
            snsId: String,
            type: Int,
        ) = TriggerContext(
            mapOf(
                "sender" to sender,
                "content" to content,
                "snsId" to snsId,
                "type" to type.toString(),
            )
        )

        fun newTransfer(
            sender: String,
            amount: String,
            currency: String,
            note: String,
            msgSvrId: Long,
        ) = TriggerContext(
            mapOf(
                "sender" to sender,
                "amount" to amount,
                "currency" to currency,
                "note" to note,
                "msgSvrId" to msgSvrId.toString(),
            )
        )

        fun newRedPacket(
            sender: String,
            groupId: String,
            senderName: String,
            msgSvrId: Long,
        ) = TriggerContext(
            mapOf(
                "sender" to sender,
                "groupId" to groupId,
                "senderName" to senderName,
                "msgSvrId" to msgSvrId.toString(),
            )
        )

        fun schedule(firedAt: String) = TriggerContext(mapOf("firedAt" to firedAt))
    }
}
