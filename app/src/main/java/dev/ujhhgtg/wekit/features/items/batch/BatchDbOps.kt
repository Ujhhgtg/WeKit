package dev.ujhhgtg.wekit.features.items.batch

import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi

/**
 * SQLite limits the number of host parameters per statement (SQLITE_MAX_VARIABLE_NUMBER,
 * historically 999). Chunk deletes so large selections don't blow past it.
 */
private const val SQL_VARIABLE_CHUNK = 500

/**
 * Delete the `message` rows for the given talkers (i.e. wipe the chat history). Returns the number
 * of rows deleted. Runs on the caller's thread.
 *
 * Note: the `rconversation` row is removed separately via WeChat's native cache-aware delete
 * ([dev.ujhhgtg.wekit.features.api.core.WeConversationApi.deleteConversation]) so the list refreshes
 * live; message rows aren't cached in the conversation-list adapter, so a direct delete is fine.
 */
fun deleteMessageRows(wxIds: Collection<String>, onError: (Throwable) -> Unit = {}): Int =
    deleteByColumn("message", "talker", wxIds, onError)

/**
 * Chunked `DELETE FROM [table] WHERE [column] IN (...)`. Each chunk is a separate statement so the
 * IN-list never exceeds SQLite's host-parameter limit. Returns the total rows deleted across chunks.
 */
fun deleteByColumn(
    table: String,
    column: String,
    wxIds: Collection<String>,
    onError: (Throwable) -> Unit = {}
): Int {
    var total = 0
    for (chunk in wxIds.chunked(SQL_VARIABLE_CHUNK)) {
        if (chunk.isEmpty()) continue
        try {
            val placeholders = chunk.joinToString(",") { "?" }
            total += WeDatabaseApi.delete(
                table,
                "$column IN ($placeholders)",
                chunk.toTypedArray()
            )
        } catch (e: Throwable) {
            onError(e)
        }
    }
    return total
}
