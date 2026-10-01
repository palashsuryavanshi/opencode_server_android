package com.palash.opencodeserver.backend.userland

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * App-side reader for the `assets/userland/<arch>.tar.gz` payload.
 *
 * Pure JVM (no Android APIs) so it is unit-testable without a device. It mirrors
 * `buildSrc/.../userland/TarReader.kt` entry for entry: regular files, directories and symlinks.
 * Anything else (hard links, devices, GNU long-name extensions) is reported as [EntryType.OTHER]
 * and skipped by the installer.
 *
 * Deliberately tolerant: payloads written before the writer fix carry a zeros-based header
 * checksum and a truncated ustar magic. Neither is validated here; the SHA-256 manifest check in
 * [UserlandInstaller] is the integrity gate, and per-entry traversal checks are the safety gate.
 */
object UserlandArchive {

    enum class EntryType { FILE, DIRECTORY, SYMLINK, OTHER }

    class Entry(
        val name: String,
        val type: EntryType,
        val size: Long,
        val mode: Int,
        val linkTarget: String
    ) {
        val isDirectory: Boolean get() = type == EntryType.DIRECTORY
        val isSymlink: Boolean get() = type == EntryType.SYMLINK
        val isFile: Boolean get() = type == EntryType.FILE
    }

    private const val BLOCK_SIZE = 512

    /**
     * Streams every entry in [input] (a raw tar stream; wrap in GZIPInputStream for `.tar.gz`).
     * The [consumer] receives a stream bounded to the entry and must not close it.
     */
    fun forEachEntry(input: InputStream, consumer: (Entry, InputStream) -> Unit) {
        while (true) {
            val entry = readHeader(input) ?: return
            val payload = BoundedInputStream(input, entry.size)
            try {
                consumer(entry, payload)
            } finally {
                payload.drain()
            }
            val padded = ((entry.size + BLOCK_SIZE - 1) / BLOCK_SIZE) * BLOCK_SIZE
            skipFully(input, padded - entry.size)
        }
    }

    /** Convenience overload that buffers small entries; large payloads should use [forEachEntry]. */
    fun readAll(input: InputStream): List<Pair<Entry, ByteArray>> {
        val out = mutableListOf<Pair<Entry, ByteArray>>()
        forEachEntry(input) { entry, payload ->
            val bytes = if (entry.isFile) payload.readBytes() else ByteArray(0)
            out += entry to bytes
        }
        return out
    }

    private fun readHeader(input: InputStream): Entry? {
        val header = ByteArray(BLOCK_SIZE)
        if (!readFully(input, header)) return null
        if (header.all { it == 0.toByte() }) return null

        fun field(start: Int, length: Int): String {
            val raw = String(header, start, length, Charsets.ISO_8859_1)
            return raw.trimEnd('\u0000', ' ')
        }

        val name = field(0, 100)
        val mode = field(100, 8).trim().toIntOrNull(8) ?: 0
        val size = field(124, 12).trim().toIntOrNull(8)?.toLong()
            ?: field(124, 12).trim().toLongOrNull(8) ?: 0L
        val typeFlag = header[156].toInt().toChar()
        val linkTarget = field(157, 100)
        val prefix = field(345, 155)
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

        val type = when (typeFlag) {
            '0', '\u0000' -> EntryType.FILE
            '5' -> EntryType.DIRECTORY
            '2' -> EntryType.SYMLINK
            else -> EntryType.OTHER
        }
        return Entry(fullName, type, size.coerceAtLeast(0), mode, linkTarget)
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val scratch = ByteArray(8192)
        while (remaining > 0) {
            val wanted = minOf(remaining, scratch.size.toLong()).toInt()
            val read = input.read(scratch, 0, wanted)
            if (read <= 0) return
            remaining -= read
        }
    }

    /** A read-only view limited to [limit] bytes so a consumer cannot overrun the entry. */
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

        fun drain() {
            val scratch = ByteArray(8192)
            while (consumed < limit) {
                val read = read(scratch, 0, minOf(scratch.size.toLong(), limit - consumed).toInt())
                if (read <= 0) break
            }
        }

        fun readBytesFully(): ByteArray {
            val out = ByteArrayOutputStream(limit.coerceAtMost(8 * 1024 * 1024).toInt())
            val scratch = ByteArray(8192)
            while (true) {
                val read = read(scratch, 0, scratch.size)
                if (read <= 0) break
                out.write(scratch, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** Parse a `<arch>.manifest` file (plain `key=value` lines) into a map. */
    fun parseManifest(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

    /** SHA-256 of a stream, hex-encoded. The stream is fully consumed but not closed. */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }
}
