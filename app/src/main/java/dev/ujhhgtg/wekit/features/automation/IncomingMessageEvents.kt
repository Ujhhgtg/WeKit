package dev.ujhhgtg.wekit.features.automation

import android.content.ContentValues
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One `message` insert listener shared by the automation features: each insert is converted to a
 * [MessageInfo] once and handed to every subscriber. Subscribers run on WeChat's DB thread and must
 * hand real work off to their own thread.
 */
object IncomingMessageEvents : WeDatabaseListenerApi.IInsertListener {
    private val listeners = CopyOnWriteArrayList<(MessageInfo) -> Unit>()

    @Synchronized
    fun subscribe(listener: (MessageInfo) -> Unit) {
        if (listeners.isEmpty()) WeDatabaseListenerApi.addListener(this)
        listeners += listener
    }

    @Synchronized
    fun unsubscribe(listener: (MessageInfo) -> Unit) {
        listeners -= listener
        if (listeners.isEmpty()) WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        val message = MessageInfo.fromContentValues(values)
        listeners.forEach { it(message) }
    }
}
