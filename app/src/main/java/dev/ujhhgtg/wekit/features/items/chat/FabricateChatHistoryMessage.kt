package dev.ujhhgtg.wekit.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Person_search
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.IWeContact
import dev.ujhhgtg.wekit.features.api.core.models.WeContact
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.SingleContactSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.WeDateTimeField
import dev.ujhhgtg.wekit.ui.content.WeDateTimeMode
import dev.ujhhgtg.wekit.ui.content.formatDateTime
import dev.ujhhgtg.wekit.ui.content.parseDateTime
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.readTextFromClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.XmlJsonParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.random.Random

object FabricateChatHistoryMessage : ClickableFeature() {

    override val technicalId = "伪造聊天记录消息"
    override val nameRes = R.string.feature_fabricate_chat_history_message_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_fabricate_chat_history_message_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        val contacts = WeDatabaseApi.getContacts()

        showComposeDialog(context) {
            ChatRecordXmlGeneratorDialog(
                contacts = contacts,
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun ChatRecordXmlGeneratorDialog(
    contacts: List<WeContact>,
    onDismiss: () -> Unit
) {
    val defaultTitle = stringResource(R.string.chat_fabricate_record_default_title)
    val defaultDescription = stringResource(R.string.chat_fabricate_record_default_description)
    var outerTitle by remember { mutableStateOf(defaultTitle) }
    var outerDesc by remember { mutableStateOf(defaultDescription) }

    val rows = remember {
        mutableStateListOf(
            MessageRowState(initialTimeMillis = System.currentTimeMillis())
        )
    }

    val context = LocalContext.current
    val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)
    val contactsByWxId = remember(contacts) { contacts.associateBy { it.wxId } }

    val canGenerate by remember(rows, contacts) {
        derivedStateOf {
            rows.isNotEmpty() &&
                    rows.all { it.senderWxId != null && it.senderWxId!!.isNotBlank() && it.text.isNotBlank() && !it.isTimeError } &&
                    contactsByWxId.isNotEmpty()
        }
    }

    AlertDialogContent(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(),
        title = { Text(stringResource(R.string.chat_fabricate_record_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = outerTitle,
                    onValueChange = { outerTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.chat_fabricate_record_title_field)) },
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = outerDesc,
                        onValueChange = { outerDesc = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.chat_fabricate_record_description_field)) }
                    )

                    Spacer(Modifier.width(8.dp))

                    TextButton(
                        onClick = {
                            outerDesc = buildString {
                                val takeCount = minOf(rows.size, 5)
                                for (i in 0 until takeCount) {
                                    val row = rows[i]
                                    val nickname = contactsByWxId[row.senderWxId]?.nickname
                                        ?: localizedContext.getString(R.string.error_unknown)
                                    append("$nickname: ${row.text}")
                                    if (i < takeCount - 1) {
                                        append("\n")
                                    }
                                }
                                if (rows.size > 5) {
                                    append("...")
                                }
                            }
                        }
                    ) {
                        Text(stringResource(R.string.chat_fabricate_record_generate))
                    }
                }

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        val clipboardText = readTextFromClipboard(context)
                        if (clipboardText.isNullOrBlank()) {
                            showToast(
                                context,
                                localizedContext.getString(R.string.chat_fabricate_record_clipboard_empty),
                            )
                            return@Button
                        }
                        runCatching {
                            val outerJson = XmlJsonParser.toJsonObject(clipboardText)
                            val appmsg = outerJson["msg"]?.jsonObject?.get("appmsg")?.jsonObject
                                ?: error(localizedContext.getString(R.string.chat_fabricate_record_missing_appmsg))
                            val parsedTitle = appmsg["title"]?.jsonPrimitive?.contentOrNull
                            val parsedDesc = appmsg["des"]?.jsonPrimitive?.contentOrNull
                            val recordItemCdata = appmsg["recorditem"]!!.jsonPrimitive.content
                            if (recordItemCdata.isBlank()) {
                                error(localizedContext.getString(R.string.chat_fabricate_record_empty_recorditem))
                            }
                            val innerJson = XmlJsonParser.toJsonObject(recordItemCdata)
                            val recordInfo = innerJson["recordinfo"]?.jsonObject
                                ?: error(localizedContext.getString(R.string.chat_fabricate_record_missing_recordinfo))
                            val datalist = recordInfo["datalist"]?.jsonObject
                                ?: error(localizedContext.getString(R.string.chat_fabricate_record_missing_datalist))
                            val dataItems: List<JsonObject> = when (val elems = datalist["dataitem"]) {
                                is JsonArray -> elems.map { it.jsonObject }
                                is JsonObject -> listOf(elems)
                                else -> emptyList()
                            }
                            if (dataItems.isEmpty()) {
                                error(localizedContext.getString(R.string.chat_fabricate_record_missing_items))
                            }

                            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            val newRows = dataItems.mapNotNull { item ->
                                val text = item["datadesc"]?.jsonPrimitive?.contentOrNull?.trim()
                                    ?: return@mapNotNull null
                                val sourceName = item["sourcename"]?.jsonPrimitive?.contentOrNull
                                    ?: return@mapNotNull null
                                val contact = contacts.firstOrNull { it.nickname == sourceName }

                                // Attempt to extract and parse the existing source time
                                val sourceTimeStr = item["sourcetime"]?.jsonPrimitive?.contentOrNull?.replace("&#x20;", " ")
                                val parsedTimeMillis = sourceTimeStr?.let {
                                    runCatching { timeFormat.parse(it)?.time }.getOrNull()
                                } ?: System.currentTimeMillis()

                                MessageRowState(
                                    senderWxId = contact?.wxId,
                                    text = text,
                                    initialTimeMillis = parsedTimeMillis
                                )
                            }
                            if (newRows.isEmpty()) {
                                error(localizedContext.getString(R.string.chat_fabricate_record_no_valid_messages))
                            }
                            if (parsedTitle != null) outerTitle = parsedTitle
                            if (parsedDesc != null) outerDesc = parsedDesc
                            rows.clear()
                            rows.addAll(newRows)
                            showToast(
                                context,
                                localizedContext.resources.getQuantityString(
                                    R.plurals.chat_fabricate_record_loaded_count,
                                    newRows.size,
                                    newRows.size,
                                ),
                            )
                        }.onFailure {
                            showToast(
                                context,
                                localizedContext.getString(
                                    R.string.chat_fabricate_record_parse_failed,
                                    it.message ?: localizedContext.getString(R.string.error_unknown),
                                ),
                            )
                            WeLogger.e("FabricateChatHistoryMessage", "failed to parse messages from clipboard", it)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.chat_fabricate_record_load_clipboard))
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.chat_fabricate_record_message_list),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            // Automatically offset new items progressively by 2 seconds
                            val nextTime = if (rows.isNotEmpty()) {
                                (rows.last().parsedTimeMillis ?: System.currentTimeMillis()) + 2000L
                            } else {
                                System.currentTimeMillis()
                            }
                            rows.add(MessageRowState(initialTimeMillis = nextTime))
                        }
                    ) {
                        Icon(
                            MaterialSymbols.Outlined.Add,
                            contentDescription = stringResource(R.string.chat_fabricate_record_add_message),
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                rows.forEachIndexed { index, row ->
                    MessageRowEditor(
                        row = row,
                        contactsByWxId = contactsByWxId,
                        onPickSender = {
                            showComposeDialog(context) {
                                SingleContactSelector(
                                    title = localizedContext.getString(R.string.chat_fabricate_record_select_sender),
                                    contacts = contacts,
                                    initialSelectedWxId = row.senderWxId,
                                    onDismiss = this.onDismiss,
                                    onConfirm = { wxId ->
                                        row.senderWxId = wxId
                                        this.onDismiss()
                                    }
                                )
                            }
                        },
                        onRemove = {
                            if (rows.size > 1) {
                                rows.removeAt(index)
                            } else {
                                showToast(
                                    context,
                                    localizedContext.getString(R.string.chat_fabricate_record_cannot_delete_last),
                                )
                            }
                        }
                    )

                    Spacer(Modifier.height(8.dp))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
        confirmButton = {
            Button(
                enabled = canGenerate,
                onClick = {
                    val xml = buildWeChatRecordXml(
                        outerTitle = outerTitle,
                        outerDesc = outerDesc,
                        rows = rows.map { it.toSnapshot() },
                        contacts = contacts
                    )

                    copyToClipboard(context, xml)
                    showToast(context, localizedContext.getString(R.string.chat_message_details_copied))
                }
            ) { Text(stringResource(R.string.chat_fabricate_record_copy)) }
            Button(
                enabled = canGenerate,
                onClick = {
                    val xml = buildWeChatRecordXml(
                        outerTitle = outerTitle,
                        outerDesc = outerDesc,
                        rows = rows.map { it.toSnapshot() },
                        contacts = contacts
                    )
                    showComposeDialog(context) {
                        SingleContactSelector(
                            title = localizedContext.getString(R.string.chat_fabricate_record_select_target),
                            contacts = contacts,
                            initialSelectedWxId = null,
                            onDismiss = this.onDismiss,
                            onConfirm = { wxId ->
                                WeMessageApi.sendXmlAppMsg(wxId, xml)
                                showToast(context, localizedContext.getString(R.string.chat_fabricate_record_sent))
                                this.onDismiss()
                            }
                        )
                    }
                }
            ) { Text(stringResource(R.string.chat_fabricate_record_send)) }
        }
    )
}

@Composable
private fun MessageRowEditor(
    row: MessageRowState,
    contactsByWxId: Map<String, IWeContact>,
    onPickSender: () -> Unit,
    onRemove: () -> Unit
) {
    val selectedContact = remember(row.senderWxId, contactsByWxId) {
        row.senderWxId?.let { contactsByWxId[it] }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = selectedContact?.displayName
                            ?: stringResource(R.string.chat_fabricate_record_choose_sender),
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (selectedContact != null) {
                        Text(
                            text = selectedContact.wxId,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = onPickSender) {
                    Icon(
                        MaterialSymbols.Outlined.Person_search,
                        contentDescription = stringResource(R.string.chat_fabricate_record_select_sender),
                    )
                }

                IconButton(
                    onClick = onRemove,
                    enabled = true
                ) {
                    Icon(
                        MaterialSymbols.Outlined.Delete,
                        contentDescription = stringResource(R.string.chat_fabricate_record_remove_message),
                    )
                }
            }

            OutlinedTextField(
                value = row.text,
                onValueChange = { row.text = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.chat_fabricate_record_message_content)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default)
            )

            Spacer(Modifier.height(8.dp))

            WeDateTimeField(
                value = row.timeText,
                onValueChange = { row.timeText = it },
                label = stringResource(R.string.chat_fabricate_record_send_time),
                mode = WeDateTimeMode.DATE_TIME,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Stable
private class MessageRowState(
    senderWxId: String? = null,
    text: String = "",
    initialTimeMillis: Long = System.currentTimeMillis()
) {
    var senderWxId by mutableStateOf(senderWxId)
    var text by mutableStateOf(text)

    var timeText: String by mutableStateOf(formatDateTime(initialTimeMillis))

    val parsedTimeMillis: Long? by derivedStateOf { parseDateTime(timeText) }

    val isTimeError: Boolean by derivedStateOf {
        parsedTimeMillis == null
    }

    fun toSnapshot() = MessageRowSnapshot(
        senderWxId = senderWxId,
        text = text,
        timestampMillis = parsedTimeMillis ?: System.currentTimeMillis()
    )
}

private data class MessageRowSnapshot(
    val senderWxId: String?,
    val text: String,
    val timestampMillis: Long
)

// ------------------------------------------------------------
// XML generation
// ------------------------------------------------------------

private fun buildWeChatRecordXml(
    outerTitle: String,
    outerDesc: String,
    rows: List<MessageRowSnapshot>,
    contacts: List<WeContact>
): String {
    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    val innerTitle = xmlEscape(outerTitle)
    val innerDesc = xmlEscape(outerDesc)

    return buildString {
        appendLine("<msg>")
        appendLine("    <appmsg>")
        appendLine("        <title>${xmlEscape(outerTitle)}</title>")
        appendLine("        <des>${xmlEscapeNewLines(outerDesc)}</des>")
        appendLine("        <action>view</action>")
        appendLine("        <type>19</type>")
        appendLine("        <url>")
        appendLine("            https://support.weixin.qq.com/cgi-bin/mmsupport-bin/readtemplate?t=page/favorite_record__w_unsupport&amp;from=singlemessage&amp;isappinstalled=0</url>")
        appendLine("        <recorditem><![CDATA[<recordinfo><title>$innerTitle</title><desc>$innerDesc</desc><datalist count=\"${rows.size}\">")

        rows.forEachIndexed { index, row ->
            val contact = row.senderWxId?.let { wxId -> contacts.firstOrNull { it.wxId == wxId } }
            if (contact != null) {
                val sourceName = contact.nickname
                val sourceHeadUrl = contact.avatarUrl

                val sourcetime = timeFormat.format(Date(row.timestampMillis)).replace(" ", "&#x20;")
                val srcMsgCreateTime = row.timestampMillis / 1000L
                val fromNewMsgId = generateFakeMsgId(index)

                append("<dataitem datatype=\"1\" dataid=\"\">")
                append("<datadesc>${xmlEscape(row.text)}</datadesc>")
                append("<sourcename>${xmlEscape(sourceName)}</sourcename>")
                append("<sourceheadurl>${xmlEscape(sourceHeadUrl)}</sourceheadurl>")
                append("<sourcetime>$sourcetime</sourcetime>")
                append("<srcMsgCreateTime>$srcMsgCreateTime</srcMsgCreateTime>")
                append("<fromnewmsgid>$fromNewMsgId</fromnewmsgid>")
                append("<dataitemsource><hashusername></hashusername></dataitemsource>")
                append("</dataitem>")
            }
        }

        appendLine("</datalist></recordinfo>]]></recorditem>")
        appendLine("    </appmsg>")
        appendLine("    <extcommoninfo>")
        appendLine("        <media_expire_at>2000000000</media_expire_at>")
        appendLine("    </extcommoninfo>")
        appendLine("</msg>")
    }
}

private fun generateFakeMsgId(index: Int): Long {
    val a = System.currentTimeMillis()
    val b = Random.nextLong(1_000_000_000L, 9_999_999_999L)
    return abs(a + b + index)
}

private fun xmlEscape(text: String): String {
    return buildString(text.length) {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}

private fun xmlEscapeNewLines(text: String): String {
    return xmlEscape(text).replace("\n", "&#x0A;").replace(" ", "&#x20;")
}
