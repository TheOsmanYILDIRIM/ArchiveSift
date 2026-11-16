package com.archivesift.core

import java.io.*
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object CoreChecks {
    @JvmStatic fun main(args: Array<String>) {
        paths(); unsafeArchives(); corruptAndUnsupported(); actualByteLimitsAndCancellation(); fileListDoesNotOpenContents()
        folderPlanning(); entryCountLimit(); inputParity { it.endsWith(".txt") && !it.startsWith("ignored/") }
        println("8 standalone JVM checks passed (parity uses a plain predicate; RE2/J tests not run here).")
    }
    fun paths() {
        check(safePath("章\\./one//café.txt") == "章/one/café.txt")
        for (bad in listOf("", "/home/a", "\\\\server\\share", "C:drive", "foo/D:file", "a/../x", "..\\x", "a/\u0000b")) {
            fails<IllegalArgumentException> { safePath(bad) }
        }
        PathIndex().apply { add("a/b", false); add("a", true); fails<IllegalArgumentException> { add("a/b", false) } }
        fails<IllegalArgumentException> { PathIndex().apply { add("a", false); add("a/b", false) } }
        fails<IllegalArgumentException> { PathIndex().apply { add("a/b", false); add("a", false) } }
    }
    fun unsafeArchives() {
        listOf(listOf("../a"), listOf("/a"), listOf("C:a"), listOf("d/a", "d\\./a"), listOf("d", "d/a")).forEach { paths ->
            withArchive(zipOf(paths.associateWith { byteArrayOf(1) })) { file ->
                fails<IllegalArgumentException> { readArchive(Files.newByteChannel(file)) }
            }
        }
    }
    fun corruptAndUnsupported() {
        val valid = zipOf(mapOf("file" to byteArrayOf(1, 2, 3)))
        withArchive(valid.copyOf(10)) { fails<IOException> { readArchive(Files.newByteChannel(it)) } }
        val central = valid.indices.first { i -> valid.copyOfRange(i, minOf(i + 4, valid.size)).contentEquals(byteArrayOf(0x50, 0x4b, 1, 2)) }
        for ((position, flag) in listOf(central + 8 to 1, central + 34 to 1, valid.size - 18 to 1)) {
            val bad = valid.copyOf(); bad[position] = (bad[position].toInt() or flag).toByte()
            withArchive(bad) { fails<IOException> { readArchive(Files.newByteChannel(it)) } }
        }
        val badCrc = valid.copyOf(); badCrc[central + 16] = (badCrc[central + 16].toInt() xor 1).toByte()
        withArchive(badCrc) { file -> readArchive(Files.newByteChannel(file)).use { s ->
            fails<IOException> { writeZip(s.files, ByteArrayOutputStream()) }
        } }
    }
    fun actualByteLimitsAndCancellation() {
        val bytes = ByteArray(192 * 1024) { 17 }
        val unknown = Entry("large", 1) { ByteArrayInputStream(bytes) }
        fails<IOException> { writeZip(listOf(unknown), ByteArrayOutputStream(), limit = 64 * 1024) }
        fails<IOException> { copyBounded(ByteArrayInputStream(bytes), ByteArrayOutputStream(), ByteBudget(MAX_BYTES), 15) }
        withArchive(zipOf(mapOf("compressed.bin" to bytes))) { file -> readArchive(Files.newByteChannel(file)).use { source ->
            fails<IOException> { writeZip(source.files, ByteArrayOutputStream(), limit = 1024) }
        } }
        var progress = 0L; var closed = false
        val tracked = Entry("large", null) { object : ByteArrayInputStream(bytes) { override fun close() { closed = true } } }
        fails<CancellationException> { writeZip(listOf(tracked), ByteArrayOutputStream(),
            check = { if (progress > 0L) throw CancellationException() }, progress = { progress = it }) }
        check(closed && progress == 65536L)
    }
    fun fileListDoesNotOpenContents() {
        val out = ByteArrayOutputStream()
        writeFileList(listOf(Entry("层/one,\"two\"", 42) { error("Must not open") }, Entry("unknown", null) { error("Must not open") }), out)
        check(out.toString("UTF-8") == "relative_path,bytes\r\n\"层/one,\"\"two\"\"\",42\r\n\"unknown\",\r\n")
    }
    fun folderPlanning() {
        val entries = listOf("blocked/x.txt", "kept/same.txt", "kept/okay.txt", "fresh/one.txt", "fresh/two.txt")
            .map { Entry(it, 0) { error("Preview must not open source bytes") } }
        val docs = mapOf("root" to listOf(TargetDoc("1", "blocked", false), TargetDoc("2", "kept", true)),
            "2" to listOf(TargetDoc("3", "SAME.txt", false), TargetDoc("4", "same (2).txt", false)))
        val lookup = { id: String -> docs.getValue(id) }
        val skip = planFolderTargets("root", entries, Conflicts.Skip, lookup)
        check(skip.moves.map { it.target } == listOf(null, null, "kept/okay.txt", "fresh/one.txt", "fresh/two.txt"))
        check(skip.moves.take(2).map { it.reason } == listOf("Folder name conflict", "File name conflict"))
        val rename = planFolderTargets("root", entries, Conflicts.Rename, lookup)
        check(rename.moves.map { it.target } == listOf("blocked (2)/x.txt", "kept/same (3).txt", "kept/okay.txt", "fresh/one.txt", "fresh/two.txt"))
        check(rename.directories.getValue("kept").docId == "2" && rename.directories.getValue("blocked (2)").docId == null)
    }
    fun entryCountLimit() {
        val header = zipOf(emptyMap()); val count = MAX_ENTRIES + 1
        for (i in listOf(8, 10)) { header[i] = count.toByte(); header[i + 1] = (count shr 8).toByte() }
        withArchive(header) { file ->
            val channel = Files.newByteChannel(file)
            fails<IOException> { readArchive(channel) }
            check(!channel.isOpen)
        }
        withArchive(zipOf(mapOf("a" to byteArrayOf(1)))) { file ->
            val channel = Files.newByteChannel(file)
            fails<CancellationException> { readArchive(channel, { throw CancellationException() }) }
            check(!channel.isOpen)
        }
    }
    fun inputParity(match: (String) -> Boolean) {
        val payload = linkedMapOf("a/same.txt" to byteArrayOf(1, 2), "b/same.txt" to byteArrayOf(3), "章/café.txt" to "こんにちは".toByteArray(),
            "a/skip.bin" to byteArrayOf(4), "ignored/no.txt" to byteArrayOf(5))
        val expected = listOf("a/same.txt", "b/same.txt", "章/café.txt")
        val root = Files.createTempDirectory("archivesift-parity")
        try {
            payload.forEach { (path, data) -> val file = root.resolve(path); Files.createDirectories(file.parent); Files.write(file, data) }
            withArchive(zipOf(payload)) { zip ->
                val exports = listOf(readArchive(Files.newByteChannel(zip)), readLocalFolder(root)).map { src -> src.use { source ->
                    val selected = source.files.filter { match(it.path) }
                    check(selected.map { it.path } == expected)
                    val out = ByteArrayOutputStream(); writeZip(selected, out)
                    val actual = linkedMapOf<String, ByteArray>()
                    ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { stream ->
                        while (true) { val e = stream.nextEntry ?: break; check(actual.put(e.name, stream.readBytes()) == null) }
                    }
                    check(actual.keys.toList() == expected)
                    actual.forEach { (path, bytes) -> check(bytes.contentEquals(payload.getValue(path))) }
                    actual
                } }
                expected.forEach { check(exports[0].getValue(it).contentEquals(exports[1].getValue(it))) }
            }
        } finally { root.toFile().deleteRecursively() }
    }
    private inline fun <reified T : Throwable> fails(block: () -> Unit) {
        try { block() } catch (e: Throwable) { if (e is T) return; throw e }
        error("Expected ${T::class.simpleName}")
    }
    private inline fun withArchive(bytes: ByteArray, block: (java.nio.file.Path) -> Unit) {
        val file = Files.createTempFile("archivesift-test", ".zip")
        try { Files.write(file, bytes); block(file) } finally { Files.deleteIfExists(file) }
    }
    private fun zipOf(payload: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> payload.forEach { (p, data) -> z.putNextEntry(ZipEntry(p)); z.write(data); z.closeEntry() } }
        return out.toByteArray()
    }
}
