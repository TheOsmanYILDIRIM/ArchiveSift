package com.archivesift.core

import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val MAX_ENTRIES = 50_000
const val MAX_BYTES = 2L * 1024 * 1024 * 1024
const val MAX_FILE_BYTES = 512L * 1024 * 1024
const val MAX_INPUT_COPY = 512L * 1024 * 1024
const val MAX_DEPTH = 64

data class Entry(val path: String, val size: Long?, val crc: Long? = null, val open: () -> InputStream)
class Source(val files: List<Entry>, private val cleanup: () -> Unit = {}) : Closeable {
    override fun close() = cleanup()
}

fun safePath(raw: String): String {
    val p = raw.replace('\\', '/')
    val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
    require(!p.startsWith('/') && parts.isNotEmpty() && parts.size <= MAX_DEPTH && p.length <= 4096 &&
        parts.none { it.trimEnd(' ', '.').isEmpty() || ':' in it || it.any { c -> c.isISOControl() } }) { "Unsafe or unsupported path: ${raw.take(160)}" }
    return parts.joinToString("/")
}

class PathIndex {
    private val explicit = HashSet<String>()
    private val dirs = HashSet<String>()
    private val files = HashSet<String>()
    fun add(path: String, directory: Boolean) {
        require(explicit.add(path) && path !in files && (directory || path !in dirs)) { "Ambiguous path: $path" }
        require(explicit.size <= MAX_ENTRIES) { "Entry limit exceeded." }
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            require(parent !in files) { "File is also a directory: $parent" }
            if (!dirs.add(parent)) break
            parent = parent.substringBeforeLast('/', "")
        }
        if (directory) dirs.add(path) else files.add(path)
        require(dirs.size + files.size <= MAX_ENTRIES) { "Path limit exceeded." }
    }
}

data class FolderNode<T>(val name: String, val key: T, val directory: Boolean, val size: Long?)
fun <T> readFolder(root: T, children: (T) -> Sequence<FolderNode<T>>, open: (T) -> InputStream,
                   check: () -> Unit = {}, progress: (Int) -> Unit = {}): Source {
    val paths = PathIndex(); val entries = mutableListOf<Entry>(); val seen = hashSetOf(root)
    var count = 0
    fun walk(parent: T, prefix: String) {
        for (child in children(parent)) {
            check(); require(++count <= MAX_ENTRIES) { "Folder entry limit exceeded." }
            require('/' !in child.name && '\\' !in child.name && child.name !in listOf("", ".", "..")) { "Unsafe folder name." }
            val path = safePath("$prefix${child.name}"); paths.add(path, child.directory)
            if (count % 128 == 0) progress(count)
            if (child.directory) {
                require(seen.add(child.key)) { "Folder provider returned a directory more than once." }
                walk(child.key, "$path/")
            } else entries += Entry(path, child.size) { open(child.key) }
        }
    }
    check(); walk(root, ""); return Source(entries.sortedBy { it.path })
}

fun readLocalFolder(root: Path, check: () -> Unit = {}): Source = readFolder(root, { parent ->
    Files.newDirectoryStream(parent).use { dir ->
        dir.asSequence().filter { !Files.isSymbolicLink(it) }.take(MAX_ENTRIES + 1).map {
            val folder = Files.isDirectory(it)
            FolderNode(it.fileName.toString(), it, folder, if (folder) null else Files.size(it))
        }.toList().asSequence()
    }
}, { Files.newInputStream(it) }, check)

class ByteBudget(private val limit: Long) {
    var total = 0L; private set
    fun spend(n: Int) {
        if (n > limit - total) throw IOException("Total output limit exceeded.")
        total += n
    }
}

fun copyBounded(input: InputStream, output: OutputStream, budget: ByteBudget, limit: Long,
                check: () -> Unit = {}, progress: (Long) -> Unit = {}, crc: CRC32? = null): Long {
    val buffer = ByteArray(64 * 1024); var bytes = 0L
    while (true) {
        check(); val n = input.read(buffer); check()
        if (n < 0) break
        if (n == 0) throw IOException("Source stream made no progress.")
        if (n > limit - bytes) throw IOException("File size limit exceeded.")
        budget.spend(n); output.write(buffer, 0, n); crc?.update(buffer, 0, n); bytes += n; progress(budget.total)
    }
    return bytes
}

fun writeEntry(entry: Entry, out: OutputStream, budget: ByteBudget, check: () -> Unit = {}, progress: (Long) -> Unit = {}) {
    check(); val crc = entry.crc?.let { CRC32() }
    val n = entry.open().use { copyBounded(it, out, budget, MAX_FILE_BYTES, check, progress, crc) }
    if (crc != null && (crc.value != entry.crc || n != entry.size)) throw IOException("Corrupt ZIP entry: ${entry.path}")
}

fun writeZip(entries: List<Entry>, out: OutputStream, check: () -> Unit = {}, progress: (Long) -> Unit = {}, limit: Long = MAX_BYTES) {
    val budget = ByteBudget(limit); val paths = PathIndex()
    ZipOutputStream(out).use { zip ->
        entries.forEach { entry ->
            check(); require(safePath(entry.path) == entry.path); paths.add(entry.path, false)
            zip.putNextEntry(ZipEntry(entry.path)); writeEntry(entry, zip, budget, check, progress); zip.closeEntry()
        }
        check()
    }
}

fun writeFileList(entries: List<Entry>, out: OutputStream, check: () -> Unit = {}) {
    out.bufferedWriter(Charsets.UTF_8).use { w ->
        w.write("relative_path,bytes\r\n")
        entries.forEach { check(); w.write("\"${it.path.replace("\"", "\"\"")}\",${it.size ?: ""}\r\n") }
    }
}
