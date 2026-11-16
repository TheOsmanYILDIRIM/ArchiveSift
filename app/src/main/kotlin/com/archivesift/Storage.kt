package com.archivesift

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as D
import com.archivesift.core.*
import java.io.*
import java.nio.file.Files

private fun ContentResolver.input(uri: Uri) = openInputStream(uri) ?: throw IOException("Provider could not open the source.")
fun ContentResolver.output(uri: Uri) = openOutputStream(uri, "wt") ?: throw IOException("Provider could not open the destination.")

fun openZip(resolver: ContentResolver, uri: Uri, cache: File, check: () -> Unit, update: (String) -> Unit): Source {
    val direct = try {
        resolver.openFileDescriptor(uri, "r")?.let { ParcelFileDescriptor.AutoCloseInputStream(it).channel }
    } catch (_: FileNotFoundException) { null }
    if (direct != null) {
        val seekable = runCatching { direct.position(0); direct.size() }.isSuccess
        if (seekable) return readArchive(direct, check) { update("Indexing ZIP · $it entries") }
        direct.close()
    }
    val tmp = File.createTempFile("archive-", ".zip", cache)
    try {
        resolver.input(uri).use { input -> tmp.outputStream().use { out ->
            copyBounded(input, out, ByteBudget(MAX_INPUT_COPY), MAX_INPUT_COPY, check,
                { update("Caching ZIP · ${it / 1024} KiB") })
        } }
        val source = readArchive(Files.newByteChannel(tmp.toPath()), check) { update("Indexing ZIP · $it entries") }
        return Source(source.files) { try { source.close() } finally { tmp.delete() } }
    } catch (t: Throwable) { tmp.delete(); throw t }
}

private data class Doc(val id: String, val name: String, val mime: String, val size: Long?) {
    val folder get() = mime == D.Document.MIME_TYPE_DIR
}
private class Tree(private val r: ContentResolver, val root: Uri) {
    val rootId: String = D.getTreeDocumentId(root)
    fun uri(id: String): Uri = D.buildDocumentUriUsingTree(root, id)
    fun children(id: String, check: () -> Unit): List<Doc> {
        val query = D.buildChildDocumentsUriUsingTree(root, id)
        val projection = arrayOf(D.Document.COLUMN_DOCUMENT_ID, D.Document.COLUMN_DISPLAY_NAME, D.Document.COLUMN_MIME_TYPE, D.Document.COLUMN_SIZE)
        return r.query(query, projection, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    check(); if (size >= MAX_ENTRIES) throw IOException("Provider directory entry limit exceeded.")
                    add(Doc(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 }))
                }
            }
        } ?: throw IOException("Provider could not list a directory.")
    }
    fun create(parent: String, mime: String, name: String): Uri = D.createDocument(r, uri(parent), mime, name)
        ?: throw IOException("Provider could not create $name.")
    fun name(uri: Uri): String? = r.query(uri, arrayOf(D.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    fun remove(uri: Uri) = D.deleteDocument(r, uri)
    fun output(uri: Uri) = r.output(uri)
}

fun openFolder(r: ContentResolver, root: Uri, check: () -> Unit, update: (String) -> Unit): Source {
    val tree = Tree(r, root)
    return readFolder(tree.rootId, { id ->
        tree.children(id, check).asSequence().map { FolderNode(it.name, it.id, it.folder, it.size) }
    }, { r.input(tree.uri(it)) }, check) { update("Indexing folder · $it entries") }
}

data class FolderPreview(val root: Uri, val entries: List<Entry>, val policy: Conflicts, val moves: List<FolderMove>)
private data class Created(val uri: Uri, val path: String, val directory: Boolean)

private fun mapFolder(tree: Tree, entries: List<Entry>, policy: Conflicts, check: () -> Unit, update: (String) -> Unit) =
    planFolderTargets(tree.rootId, entries, policy, { id ->
        tree.children(id, check).map { TargetDoc(it.id, it.name, it.folder) }
    }, check, update)

fun previewFolder(r: ContentResolver, uri: Uri, entries: List<Entry>, policy: Conflicts,
                  check: () -> Unit, update: (String) -> Unit): FolderPreview =
    FolderPreview(uri, entries, policy, mapFolder(Tree(r, uri), entries, policy, check, update).moves)

fun exportFolder(r: ContentResolver, preview: FolderPreview, check: () -> Unit, update: (String) -> Unit): String {
    val tree = Tree(r, preview.root)
    val (moves, directories, knownIds) = mapFolder(tree, preview.entries, preview.policy, check, update)
    val ids = knownIds.toMutableSet()
    require(moves == preview.moves) { "The destination changed. Preview the export again before writing." }
    val made = mutableListOf<Created>()
    val refs = mutableMapOf("" to tree.rootId)
    fun create(parent: String, path: String, folder: Boolean): Uri {
        check()
        val name = path.substringAfterLast('/')
        val madeUri = tree.create(parent, if (folder) D.Document.MIME_TYPE_DIR else "application/octet-stream", name)
        require(ids.add(D.getDocumentId(madeUri))) { "Provider returned an existing item for $path. It was not written or removed." }
        made += Created(madeUri, path, folder)
        val actual = tree.name(madeUri) ?: throw IOException("Provider did not report the created name: $path.")
        if (actual != name) throw IOException("Provider changed the name of $path to $actual.")
        return madeUri
    }
    fun directory(path: String): String = refs.getOrPut(path) {
        val spec = directories.getValue(path)
        spec.docId ?: D.getDocumentId(create(directory(path.substringBeforeLast('/', "")), path, true))
    }
    var written = 0
    val budget = ByteBudget(MAX_BYTES)
    try {
        moves.forEach { move ->
            check()
            val target = move.target ?: return@forEach
            val output = create(directory(target.substringBeforeLast('/', "")), target, false)
            tree.output(output).use { out ->
                writeEntry(move.entry, out, budget, check) { update("Exporting folder · $written files · ${it / 1024} KiB") }
            }
            written++
        }
        check(); val renamed = moves.count { it.target != null && it.target != it.entry.path }
        return "Exported $written files; renamed $renamed; skipped ${moves.size - written}."
    } catch (e: Exception) {
        val remaining = made.asReversed().filter { item ->
            !runCatching {
                (!item.directory || tree.children(D.getDocumentId(item.uri)) {}.isEmpty()) && tree.remove(item.uri)
            }.getOrDefault(false)
        }
        if (remaining.isNotEmpty()) throw IOException("${e.message ?: "Export stopped."} Provider left ${remaining.size} item(s): ${remaining.take(4).joinToString { it.path.take(90) }}.", e)
        throw e
    }
}
