package dev.ujhhgtg.wekit.features.items.chat.panel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import dev.ujhhgtg.wekit.activity.TransparentActivity

data class PickedPanelFile(
    val name: String,
    val uri: Uri,
)

fun pickPanelFile(
    context: Context,
    mimeTypes: Array<String>,
    onSelected: (name: String, uri: Uri, activity: FragmentActivity) -> Unit,
) {
    TransparentActivity.launch(context) {
        val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            val displayName = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "imported_file"
            onSelected(displayName, uri, this)
        }
        launcher.launch(mimeTypes)
    }
}

fun pickPanelFiles(
    context: Context,
    mimeTypes: Array<String>,
    onSelected: (files: List<PickedPanelFile>, activity: FragmentActivity) -> Unit,
) {
    TransparentActivity.launch(context) {
        val launcher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isEmpty()) {
                finish()
                return@registerForActivityResult
            }
            onSelected(
                uris.map { uri -> PickedPanelFile(contentResolver.displayName(uri), uri) },
                this,
            )
        }
        launcher.launch(mimeTypes)
    }
}

fun pickPanelDirectory(
    context: Context,
    onSelected: (treeUri: Uri, activity: FragmentActivity) -> Unit,
) {
    TransparentActivity.launch(context) {
        val launcher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            onSelected(uri, this)
        }
        launcher.launch(null)
    }
}

fun listPanelTreeFiles(
    resolver: ContentResolver,
    treeUri: Uri,
): List<PickedPanelFile> {
    val result = mutableListOf<PickedPanelFile>()
    val visited = mutableSetOf<String>()

    fun visit(documentId: String) {
        if (!visited.add(documentId)) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val typeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Triple(
                            cursor.getString(idIndex),
                            cursor.getString(nameIndex) ?: "imported_file",
                            cursor.getString(typeIndex),
                        ),
                    )
                }
            }
        }.orEmpty()
        children.forEach { (childId, name, mimeType) ->
            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                visit(childId)
            } else {
                result += PickedPanelFile(
                    name = name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                )
            }
        }
    }

    visit(DocumentsContract.getTreeDocumentId(treeUri))
    return result
}

private fun ContentResolver.displayName(uri: Uri): String = query(
    uri,
    arrayOf(OpenableColumns.DISPLAY_NAME),
    null,
    null,
    null,
)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getString(0) else null
} ?: "imported_file"
