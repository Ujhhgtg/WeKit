package dev.ujhhgtg.wekit.utils

import android.database.Cursor
import android.view.View
import android.view.ViewGroup

fun debugCursor(cursor: Cursor?) {
    if (cursor == null) {
        WeLogger.d("debugCursor", "Cursor is null")
        return
    }

    WeLogger.d("debugCursor", "Rows: ${cursor.count} | Columns: ${cursor.columnCount}")

    // Save current position to restore it later
    val initialPosition = cursor.position

    if (cursor.moveToFirst()) {
        do {
            val rowString = StringBuilder()
            for (i in 0 until cursor.columnCount) {
                val columnName = cursor.getColumnName(i)
                val value =
                    try {
                        cursor.getString(i) ?: "NULL"
                    } catch (_: Exception) {
                        "BLOB/Internal Error"
                    }
                rowString.append("[$columnName: $value] ")
            }
            WeLogger.d("debugCursor", "Row ${cursor.position}: $rowString")
        } while (cursor.moveToNext())
    } else {
        WeLogger.d("debugCursor", "Cursor is empty")
    }

    // Restore the cursor to its original position
    cursor.moveToPosition(initialPosition)
}

fun debugViewTree(view: View, connector: String = "", indent: String = "") {
    val idStr =
        if (view.id != View.NO_ID) {
            runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("UNKNOWN_ID")
        } else "NO_ID"
    WeLogger.d(
        "debugViewTree",
        "$indent$connector${view.javaClass.name} [ID: $idStr / ${view.id}] [TAG: ${view.tag?.javaClass?.name ?: "null"}]",
    )
    if (view is ViewGroup) {
        val children = (0 until view.childCount).mapNotNull { view.getChildAt(it) }
        children.forEachIndexed { i, child ->
            val isLast = i == children.lastIndex
            val childConnector = if (isLast) "└─ " else "├─ "
            val childIndent =
                indent +
                    if (connector.isEmpty()) "" else if (connector.startsWith("└")) "   " else "│  "
            debugViewTree(child, childConnector, childIndent)
        }
    }
}

fun logStackTrace() {
    Thread.currentThread()
        .stackTrace
        .drop(2) // drop getStackTrace() and logStackTrace() itself
        .joinToString("\n") {
            "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
        }
        .let { WeLogger.d("logStackTrace", "\n$it") }
}
