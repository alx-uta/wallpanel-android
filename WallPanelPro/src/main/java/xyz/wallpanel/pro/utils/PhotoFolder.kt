/*
 * Copyright (c) 2026 WallPanel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package xyz.wallpanel.pro.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import timber.log.Timber

/**
 * The photos of the folder picked for the wallpaper screensaver, a tree the storage access
 * framework granted. Only the folder's own photos count, not those of folders inside it.
 */
object PhotoFolder {

    private const val IMAGE_TYPE_PREFIX = "image/"

    /** The photos in the folder, none if it is gone or access to it was lost. Does disk work. */
    fun list(context: Context, folder: String): List<Uri> {
        val photos = ArrayList<Uri>()
        try {
            val tree = Uri.parse(folder)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (isPhoto(cursor.getString(1))) {
                        photos.add(DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)))
                    }
                }
            }
        } catch (e: Exception) {
            // A provider can throw anything across the binder, and a folder that went away
            // must not take the screensaver with it
            Timber.e(e, "Could not list the photo folder")
            return emptyList()
        }
        return photos
    }

    fun isPhoto(mimeType: String?): Boolean = mimeType?.startsWith(IMAGE_TYPE_PREFIX) == true

    /**
     * The folder as a person would write it, from its tree document id such as
     * `primary:Pictures/Family`. The top of a storage volume has no path, so shows the volume.
     */
    fun displayName(treeDocumentId: String): String {
        val path = treeDocumentId.substringAfter(':', treeDocumentId)
        return path.ifEmpty { treeDocumentId.substringBefore(':') }
    }
}

/**
 * Goes through photos in a random order, each once a pass, so a small folder does not repeat
 * one photo while another waits. [next] gives null at the end of a pass, for the caller to
 * list the folder again and pick up what changed.
 */
class PhotoRotation<T>(private val shuffle: (MutableList<T>) -> Unit = { it.shuffle() }) {

    private val queue = ArrayDeque<T>()
    private var last: T? = null

    val isEmpty: Boolean
        get() = queue.isEmpty()

    fun start(photos: List<T>) {
        val order = photos.toMutableList()
        shuffle(order)
        // The new pass does not open with the photo on screen
        if (order.size > 1 && order.first() == last) {
            order.add(order.removeAt(0))
        }
        queue.clear()
        queue.addAll(order)
    }

    fun next(): T? {
        val photo = queue.removeFirstOrNull() ?: return null
        last = photo
        return photo
    }
}
