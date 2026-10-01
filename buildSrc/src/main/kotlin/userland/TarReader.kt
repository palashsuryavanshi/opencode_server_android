package userland

import java.io.File
import java.io.InputStream

/**
 * A minimal `ustar` reader, sufficient for unpacking a Linux userland.
 *
 * Supports regular files, directories and symlinks. Hard links are recorded but not recreated:
 * a userland rarely needs them, and silently materialising one as a copy would be wrong.
 *
 * Written rather than depended upon because the alternatives are worse: a host `tar` binary does not
 * exist consistently, and Apache Commons Compress would add a dependency to the build logic for one
 * very simple format.
 */
class TarReader(input: InputStream) : AutoCloseable {

    private companion object {
        const val BLOCK_SIZE = 512
        const val FILE_ENTRY = '0'
        const val SYMLINK_ENTRY = '2'
        const val DIRECTORY_ENTRY = '5'
    }

    private val stream = input

    class Entry(
        val name: String,
        val type: Char,
        val size: Long,
        val mode: Int,
        val linkTarget: String
    ) {
        val isDirectory: Boolean get() = type == DIRECTORY_ENTRY
        val isSymlink: Boolean get() = type == SYMLINK_ENTRY
    }

    override fun close() = stream.close()

    /** Reads the next entry header, or null at end of archive. */
    private fun readHeader(): Entry? {
        val header = ByteArray(BLOCK_SIZE)
        if (readFully(header).not()) return null

        // Two consecutive zero blocks mark the end of the archive.
        if (header.all { it == 0.toByte() }) return null

        fun field(start: Int, length: Int): String {
            val raw = String(header, start, length, Charsets.ISO_8859_1)
            return raw.trimEnd('\u0000', ' ')
        }

        val name = field(0, 100)
        val mode = field(100, 8).toIntOrNull(8) ?: 0
        val size = field(124, 12).toLongOrNull(8) ?: 0L
        val typeFlag = header[156].toInt().toChar()
        val linkTarget = field(157, 100)
        val prefix = field(345, 155)

        // ustar splits long paths into a 155-byte prefix and a 100-byte name.
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

        return Entry(fullName, typeFlag, size, mode, linkTarget)
    }

    /**
     * Skip [count] bytes of tar's block padding.
     *
     * Only the padding is ever skipped here: the entry's own payload is consumed through the bounded
     * stream. Skipping the padded size *as well* would advance twice as far and land in the middle of
     * the next entry, which is exactly the kind of desync that makes a tar reader emit file contents
     * as if they were header names.
     */
    private fun skipBytes(count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val wanted = minOf(remaining.toInt(), scratch.size)
            val read = stream.read(scratch, 0, wanted)
            if (read <= 0) return
            remaining -= read
        }
    }

    /**
     * Opens each entry and hands it to [consumer].
     *
     * The consumer receives a stream bounded to the entry, so it cannot read into the next header.
     * Whatever it leaves unread is drained, and the trailing padding up to the next 512-byte boundary
     * is then skipped, which is what keeps the reader aligned.
     */
    fun forEachEntry(consumer: (Entry, InputStream) -> Unit) {
        while (true) {
            val entry = readHeader() ?: return
            val payload = BoundedInputStream(stream, entry.size)
            try {
                consumer(entry, payload)
            } finally {
                payload.drain()
            }
            // tar rounds every entry up to a whole 512-byte block; the payload itself was already
            // consumed, so only the remainder is left to step over.
            val padded = ((entry.size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
            skipBytes(padded - entry.size)
        }
    }

    /** A read-only view limited to [limit] bytes, so a consumer cannot overrun the entry. */
    class BoundedInputStream(
        private val delegate: InputStream,
        private val limit: Long
    ) : InputStream() {
        private var consumed = 0L

        override fun read(): Int {
            if (consumed >= limit) return -1
            val value = delegate.read()
            if (value >= 0) consumed++
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (consumed >= limit) return -1
            val allowed = minOf(len.toLong(), limit - consumed).toInt()
            val read = delegate.read(b, off, allowed)
            if (read > 0) consumed += read
            return read
        }

        override fun available(): Int = minOf(delegate.available().toLong(), limit - consumed).toInt()

        /** Consumes whatever the caller left, so the next header lands on a block boundary. */
        fun drain() {
            val scratch = ByteArray(8192)
            while (consumed < limit) {
                val read = read(scratch, 0, minOf(scratch.size.toLong(), limit - consumed).toInt())
                if (read <= 0) break
            }
        }
    }

    private fun readFully(buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }
}