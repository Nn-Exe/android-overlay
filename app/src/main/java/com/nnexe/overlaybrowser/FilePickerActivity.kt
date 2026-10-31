package com.nnexe.overlaybrowser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract

/**
 * Invisible trampoline Activity that opens Android's system DocumentsUI picker (for files or folders,
 * supporting both internal storage and external SD cards) and hands the chosen Uri list back to the
 * overlay browser's WebView.
 */
class FilePickerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return

        val pickFolder = intent.getBooleanExtra(EXTRA_FOLDER, false)
        val allowMultiple = intent.getBooleanExtra(EXTRA_MULTIPLE, false)
        val acceptTypes = intent.getStringArrayExtra(EXTRA_MIME_TYPES)

        val pickerIntent = if (pickFolder) {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
                putExtra("android.provider.extra.SHOW_ADVANCED", true)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                putExtra("android.provider.extra.SHOW_ADVANCED", true)
                val validMimeTypes = acceptTypes?.filter { it.contains('/') }?.toTypedArray()
                if (!validMimeTypes.isNullOrEmpty()) {
                    if (validMimeTypes.size == 1) {
                        type = validMimeTypes[0]
                    } else {
                        putExtra(Intent.EXTRA_MIME_TYPES, validMimeTypes)
                    }
                }
            }
        }

        try {
            startActivityForResult(pickerIntent, if (pickFolder) REQ_FOLDER else REQ_FILE)
        } catch (_: Exception) {
            // Fallback to ACTION_GET_CONTENT if ACTION_OPEN_DOCUMENT is unavailable
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
                }
                startActivityForResult(fallback, REQ_FILE)
            } catch (_: Exception) {
                deliverResult(null)
                finishAndRemoveTask()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            deliverResult(null)
            finishAndRemoveTask()
            return
        }

        if (requestCode == REQ_FOLDER) {
            val treeUri = data.data
            if (treeUri == null) {
                deliverResult(null)
                finishAndRemoveTask()
                return
            }
            try {
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}

            Thread {
                val files = collectTreeFiles(treeUri, maxFiles = 500)
                val result = if (files.isNotEmpty()) files.toTypedArray() else arrayOf(treeUri)
                runOnUiThread {
                    deliverResult(result)
                    finishAndRemoveTask()
                }
            }.start()
            return
        }

        val uris = mutableListOf<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uri ->
                    takeReadPermission(uri)
                    uris.add(uri)
                }
            }
        } else {
            data.data?.let { uri ->
                takeReadPermission(uri)
                uris.add(uri)
            }
        }
        deliverResult(if (uris.isNotEmpty()) uris.toTypedArray() else null)
        finishAndRemoveTask()
    }

    private fun takeReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {}
    }

    private fun collectTreeFiles(treeUri: Uri, maxFiles: Int): List<Uri> {
        val out = mutableListOf<Uri>()
        val rootDocId = try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            return out
        }
        val queue = ArrayDeque<String>()
        queue.add(rootDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        while (queue.isNotEmpty() && out.size < maxFiles) {
            val parentDocId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            try {
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (cursor.moveToNext() && out.size < maxFiles) {
                        val docId = if (idCol >= 0) cursor.getString(idCol) else continue
                        val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            queue.add(docId)
                        } else {
                            out.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        return out
    }

    companion object {
        private const val EXTRA_FOLDER = "folder"
        private const val EXTRA_MULTIPLE = "multiple"
        private const val EXTRA_MIME_TYPES = "mimes"
        private const val REQ_FILE = 100
        private const val REQ_FOLDER = 101

        private var pendingCallback: ((Array<Uri>?) -> Unit)? = null

        fun launch(
            context: Context,
            pickFolder: Boolean,
            allowMultiple: Boolean,
            acceptTypes: Array<String>?,
            callback: (Array<Uri>?) -> Unit,
        ) {
            pendingCallback?.invoke(null)
            pendingCallback = callback
            val intent = Intent(context, FilePickerActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_FOLDER, pickFolder)
                putExtra(EXTRA_MULTIPLE, allowMultiple)
                putExtra(EXTRA_MIME_TYPES, acceptTypes)
            }
            context.startActivity(intent)
        }

        private fun deliverResult(uris: Array<Uri>?) {
            val cb = pendingCallback
            pendingCallback = null
            cb?.invoke(uris)
        }
    }
}

