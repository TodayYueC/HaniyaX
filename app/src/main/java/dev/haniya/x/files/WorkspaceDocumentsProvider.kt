package dev.haniya.x.files

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import dev.haniya.x.R
import dev.haniya.x.core.Paths
import java.io.File
import java.io.FileNotFoundException

/**
 * Exposes the workspace home (/root) to the system file manager and to other apps' file pickers as "HaniyaX".
 * Document ids are guest paths ("/root/projects/web/index.html"); only paths under /root are served.
 */
class WorkspaceDocumentsProvider : DocumentsProvider() {

    private val rootGuest = Paths.GUEST_HOME

    override fun onCreate(): Boolean = true

    private fun ensureInit() {
        // The provider may be created before Application.onCreate finished wiring Paths in rare cases.
        runCatching { Paths.files }.onFailure { context?.let { Paths.init(it) } }
    }

    private fun checkId(id: String): String {
        val g = GuestPaths.normalize(id)
        if (g != rootGuest && !g.startsWith("$rootGuest/")) throw FileNotFoundException("outside workspace: $id")
        return g
    }

    private fun fileOf(id: String): File { ensureInit(); return Paths.host(checkId(id)) }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        ensureInit()
        val c = MatrixCursor(projection ?: ROOT_COLUMNS)
        val home = Paths.host(rootGuest)
        if (!home.isDirectory) return c // workspace not created yet
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "workspace")
            add(Root.COLUMN_DOCUMENT_ID, rootGuest)
            add(Root.COLUMN_TITLE, "HaniyaX")
            add(Root.COLUMN_SUMMARY, "工作区 ~")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_LOCAL_ONLY)
            add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(Root.COLUMN_MIME_TYPES, "*/*")
            add(Root.COLUMN_AVAILABLE_BYTES, home.freeSpace)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        addRow(c, checkId(documentId), fileOf(documentId))
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: DOC_COLUMNS)
        val parent = checkId(parentDocumentId)
        fileOf(parent).listFiles()?.sortedBy { it.name.lowercase() }?.forEach { f -> addRow(c, GuestPaths.child(parent, f.name), f) }
        return c
    }

    private fun addRow(c: MatrixCursor, id: String, f: File) {
        val dir = f.isDirectory
        var flags = 0
        if (f.canWrite()) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            flags = if (dir) flags or Document.FLAG_DIR_SUPPORTS_CREATE else flags or Document.FLAG_SUPPORTS_WRITE
        }
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, id)
            add(Document.COLUMN_DISPLAY_NAME, if (id == rootGuest) "HaniyaX" else f.name)
            add(Document.COLUMN_MIME_TYPE, if (dir) Document.MIME_TYPE_DIR else FileStore.mimeOf(f.name))
            add(Document.COLUMN_SIZE, if (dir) null else f.length())
            add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = fileOf(documentId)
        if (f.isDirectory) throw FileNotFoundException("is a directory")
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = checkId(parentDocumentId)
        GuestPaths.validName(displayName)?.let { throw FileNotFoundException(it) }
        var id = GuestPaths.child(parent, displayName)
        var n = 1
        while (fileOf(id).exists()) id = GuestPaths.child(parent, "$displayName ($n)").also { n++ }
        val f = fileOf(id)
        val ok = if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else f.createNewFile()
        if (!ok) throw FileNotFoundException("无法创建 $displayName")
        return id
    }

    override fun deleteDocument(documentId: String) {
        FileStore.delete(checkId(documentId))
    }

    override fun renameDocument(documentId: String, displayName: String): String = FileStore.rename(checkId(documentId), displayName)

    override fun getDocumentType(documentId: String): String {
        val f = fileOf(documentId)
        return if (f.isDirectory) Document.MIME_TYPE_DIR else FileStore.mimeOf(f.name)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val p = GuestPaths.normalize(parentDocumentId)
        val d = GuestPaths.normalize(documentId)
        return d.startsWith("$p/")
    }

    companion object {
        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS,
            Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES,
        )
        private val DOC_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
