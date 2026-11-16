package com.archivesift.core

import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

private const val CP437 = "\u00c7\u00fc\u00e9\u00e2\u00e4\u00e0\u00e5\u00e7\u00ea\u00eb\u00e8\u00ef\u00ee\u00ec\u00c4\u00c5\u00c9\u00e6\u00c6\u00f4\u00f6\u00f2\u00fb\u00f9\u00ff\u00d6\u00dc\u00a2\u00a3\u00a5\u20a7\u0192\u00e1\u00ed\u00f3\u00fa\u00f1\u00d1\u00aa\u00ba\u00bf\u2310\u00ac\u00bd\u00bc\u00a1\u00ab\u00bb\u2591\u2592\u2593\u2502\u2524\u2561\u2562\u2556\u2555\u2563\u2551\u2557\u255d\u255c\u255b\u2510\u2514\u2534\u252c\u251c\u2500\u253c\u255e\u255f\u255a\u2554\u2569\u2566\u2560\u2550\u256c\u2567\u2568\u2564\u2565\u2559\u2558\u2552\u2553\u256b\u256a\u2518\u250c\u2588\u2584\u258c\u2590\u2580\u03b1\u00df\u0393\u03c0\u03a3\u03c3\u00b5\u03c4\u03a6\u0398\u03a9\u03b4\u221e\u03c6\u03b5\u2229\u2261\u00b1\u2265\u2264\u2320\u2321\u00f7\u2248\u00b0\u2219\u00b7\u221a\u207f\u00b2\u25a0\u00a0"

private fun ByteBuffer.u16(i: Int) = getShort(i).toInt() and 0xffff
private fun ByteBuffer.u32(i: Int) = getInt(i).toLong() and 0xffffffffL
private fun demand(valid: Boolean, message: String = "Corrupt or invalid ZIP structure.") { if (!valid) throw IOException(message) }
private fun SeekableByteChannel.at(offset: Long, length: Int): ByteBuffer {
    demand(offset >= 0 && offset <= size() - length)
    val b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
    synchronized(this) {
        position(offset)
        while (b.hasRemaining()) demand(read(b) > 0, "Truncated ZIP.")
    }
    b.flip(); return b
}

/** Reads only the central directory. File streams independently validate local headers on demand. */
fun readArchive(channel: SeekableByteChannel, check: () -> Unit = {}, progress: (Int) -> Unit = {}): Source {
    try {
        check(); val size = channel.size(); val tailSize = minOf(size, 65_557L).toInt()
        val tail = channel.at(size - tailSize, tailSize)
        val end = (tailSize - 22 downTo 0).firstOrNull {
            tail.u32(it) == 0x06054b50L && it + 22 + tail.u16(it + 20) == tailSize
        } ?: throw IOException("Not a supported ZIP or missing end record (corrupt or multipart ZIP).")
        var centralEnd = size - tailSize + end
        demand(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0 && tail.u16(end + 8) == tail.u16(end + 10), "Multipart ZIPs are unsupported.")
        var count = tail.u16(end + 10).toLong(); var length = tail.u32(end + 12); var start = tail.u32(end + 16)
        if (centralEnd >= 20 && channel.at(centralEnd - 20, 4).u32(0) == 0x07064b50L) {
            val locator = channel.at(centralEnd - 20, 20)
            demand(locator.u32(4) == 0L && locator.u32(16) == 1L, "Multipart ZIPs are unsupported.")
            val offset = locator.getLong(8); val z = channel.at(offset, 56)
            demand(z.u32(0) == 0x06064b50L && z.getLong(4) >= 44 && offset <= centralEnd - 32 && z.getLong(4) == centralEnd - 32 - offset)
            demand(z.u32(16) == 0L && z.u32(20) == 0L && z.getLong(24) == z.getLong(32), "Multipart ZIPs are unsupported.")
            count = z.getLong(32); length = z.getLong(40); start = z.getLong(48); centralEnd = offset
        }
        demand(count in 0..MAX_ENTRIES.toLong(), "ZIP entry limit exceeded.")
        demand(length in 0..(64L * 1024 * 1024), "ZIP metadata limit exceeded.")
        demand(start >= 0 && start <= centralEnd && length == centralEnd - start, "Corrupt, encrypted, or unsupported ZIP directory.")
        val entries = ArrayList<Entry>(); val paths = PathIndex(); var cursor = start
        repeat(count.toInt()) { i ->
            check(); if (i % 128 == 0) progress(i)
            demand(cursor <= centralEnd - 46)
            val h = channel.at(cursor, 46); demand(h.u32(0) == 0x02014b50L)
            val flags = h.u16(8); val method = h.u16(10)
            demand(flags and 0x2041 == 0, "Encrypted ZIPs are unsupported.")
            demand(method == 0 || method == 8, "Unsupported ZIP compression method: $method.")
            val nameLength = h.u16(28); val extraLength = h.u16(30); val comment = h.u16(32)
            demand(nameLength in 1..16_384 && cursor + 46 + nameLength + extraLength + comment <= centralEnd)
            val name = channel.at(cursor + 46, nameLength).array()
            val raw = if (flags and 0x800 != 0) Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(name)).toString() else buildString {
                name.forEach { byte -> val c = byte.toInt() and 255; append(if (c < 128) c.toChar() else CP437[c - 128]) }
            }
            var unpacked = h.u32(24); var compressed = h.u32(20); var local = h.u32(42); var disk = h.u16(34)
            if (unpacked == 0xffffffffL || compressed == 0xffffffffL || local == 0xffffffffL || disk == 0xffff) {
                val extras = channel.at(cursor + 46 + nameLength, extraLength); var p = 0; var found = false
                while (p + 4 <= extraLength) {
                    val n = extras.u16(p + 2); demand(p + 4 + n <= extraLength)
                    if (extras.u16(p) == 1) {
                        var j = p + 4; val last = j + n
                        fun next(): Long { demand(j + 8 <= last); return extras.getLong(j).also { j += 8 } }
                        if (unpacked == 0xffffffffL) unpacked = next()
                        if (compressed == 0xffffffffL) compressed = next()
                        if (local == 0xffffffffL) local = next()
                        if (disk == 0xffff) { demand(j + 4 <= last); demand(extras.u32(j) == 0L, "Multipart ZIPs are unsupported."); disk = 0 }
                        found = true; break
                    }
                    p += 4 + n
                }
                demand(found)
            }
            demand(disk == 0, "Multipart ZIPs are unsupported.")
            demand(unpacked >= 0 && compressed >= 0 && local in 0..(start - 30) && compressed <= start - local)
            demand(method != 0 || unpacked == compressed)
            val path = safePath(raw); val directory = raw.endsWith('/') || raw.endsWith('\\')
            paths.add(path, directory)
            if (!directory) entries += Entry(path, unpacked, h.u32(16)) {
                val header = channel.at(local, 30)
                demand(header.u32(0) == 0x04034b50L && header.u16(6) == flags && header.u16(8) == method && header.u16(26) == nameLength)
                val data = local + 30 + nameLength + header.u16(28)
                demand(data <= start && compressed <= start - data && channel.at(local + 30, nameLength).array().contentEquals(name))
                val slice = Slice(channel, data, compressed)
                if (method == 0) slice else {
                    val inflater = Inflater(true)
                    object : InflaterInputStream(slice, inflater, 32 * 1024) {
                        override fun close() { try { super.close() } finally { inflater.end() } }
                    }
                }
            }
            cursor += 46 + nameLength + extraLength + comment
        }
        demand(cursor == centralEnd)
        return Source(entries.sortedBy { it.path }) { channel.close() }
    } catch (t: Throwable) { runCatching { channel.close() }; throw t }
}

private class Slice(private val channel: SeekableByteChannel, private var offset: Long, private var left: Long) : InputStream() {
    override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (left == 0L) return -1
        val n = synchronized(channel) {
            channel.position(offset); channel.read(ByteBuffer.wrap(b, off, minOf(len.toLong(), left).toInt()))
        }
        demand(n > 0, "Truncated ZIP data."); offset += n; left -= n; return n
    }
}
