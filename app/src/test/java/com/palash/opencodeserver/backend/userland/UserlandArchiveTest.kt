package com.palash.opencodeserver.backend.userland

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * App-side tar reader: synthetic round-trip plus the real generated payload when present.
 *
 * The reader is deliberately tolerant (no checksum enforcement), so the round-trip writer below
 * emits the fixed format and the real-payload test proves the legacy payload still parses.
 */
class UserlandArchiveTest {

    // ---- synthetic round-trip ----------------------------------------------------------

    @Test
    fun roundTripFilesDirsAndSymlinks() {
        val tar = tarBytes(
            dir("bin/"),
            file("bin/bash", "#!/bin/bash".toByteArray(), 0b111_101_101),
            file("bin/tool", "x".toByteArray(), 0b110_100_100),
            symlink("bin/sh", "bash")
        )
        val entries = UserlandArchive.readAll(ByteArrayInputStream(tar))
        val byName = entries.associate { it.first.name to it }

        assertEquals(4, entries.size)
        assertEquals(UserlandArchive.EntryType.DIRECTORY, byName.getValue("bin/").first.type)
        assertEquals("#!/bin/bash", String(byName.getValue("bin/bash").second))
        assertEquals(0b111_101_101, byName.getValue("bin/bash").first.mode)
        assertEquals(UserlandArchive.EntryType.SYMLINK, byName.getValue("bin/sh").first.type)
        assertEquals("bash", byName.getValue("bin/sh").first.linkTarget)
    }

    @Test
    fun streamingConsumerSeesBoundedPayloads() {
        val big = ByteArray(3000) { (it % 251).toByte() }
        val tar = tarBytes(file("a.bin", big, 0b110_100_100), file("b.txt", "hi".toByteArray(), 0b110_100_100))
        val seen = mutableListOf<String>()
        UserlandArchive.forEachEntry(ByteArrayInputStream(tar)) { entry, payload ->
            seen += "${entry.name}:${payload.readBytes().size}"
        }
        assertEquals(listOf("a.bin:3000", "b.txt:2"), seen)
    }

    @Test
    fun ustarPrefixSplitNamesRejoined() {
        val longDir = "d".repeat(60)
        val name = "$longDir/${"f".repeat(60)}"
        val tar = tarBytes(file(name, "v".toByteArray(), 0b110_100_100))
        val entries = UserlandArchive.readAll(ByteArrayInputStream(tar))
        assertEquals(1, entries.size)
        assertEquals(name, entries.single().first.name)
    }

    @Test
    fun manifestParsesKeyValuePairs() {
        val map = UserlandArchive.parseManifest("architecture=aarch64\narchiveSha256=abc123\n# comment\n\nfiles=5\n")
        assertEquals("aarch64", map["architecture"])
        assertEquals("abc123", map["archiveSha256"])
        assertEquals("5", map["files"])
    }

    // ---- real generated payload ----------------------------------------------------------

    @Test
    fun realPayloadContainsExpectedBinaries() {
        val payload = findPayload()
        Assume.assumeTrue("generated userland payload absent; skipping", payload != null)
        val names = mutableSetOf<String>()
        val symlinkTargets = mutableMapOf<String, String>()
        payload!!.inputStream().use { raw ->
            GZIPInputStream(raw).use { gzip ->
                UserlandArchive.forEachEntry(gzip) { entry, _ ->
                    names += entry.name.removePrefix("./")
                    if (entry.isSymlink) symlinkTargets[entry.name.removePrefix("./")] = entry.linkTarget
                }
            }
        }
        assertTrue("bin/bash missing", names.contains("bin/bash"))
        for (binary in listOf("bin/git", "bin/node", "bin/npm", "bin/curl")) {
            assertTrue("$binary missing from payload", names.contains(binary))
        }
        assertTrue("payload records no symlinks", symlinkTargets.isNotEmpty())
        assertTrue(
            "symlink targets lost",
            symlinkTargets.values.any { it.isNotBlank() }
        )
    }

    private fun findPayload(): File? {
        // Unit-test working dir is the `app/` module directory.
        val candidates = listOf(
            File("build/generated/userlandAssets/userland/aarch64.tar.gz"),
            File("app/build/generated/userlandAssets/userland/aarch64.tar.gz")
        )
        return candidates.firstOrNull { it.isFile }
    }

    // ---- minimal fixed-format writer (test-local) ------------------------------------------

    private sealed interface Spec
    private data class Dir(val name: String) : Spec
    private data class Reg(val name: String, val content: ByteArray, val mode: Int) : Spec
    private data class Link(val name: String, val target: String) : Spec

    private fun dir(name: String) = Dir(name)
    private fun file(name: String, content: ByteArray, mode: Int) = Reg(name, content, mode)
    private fun symlink(name: String, target: String) = Link(name, target)

    private fun tarBytes(vararg specs: Spec): ByteArray {
        val out = ByteArrayOutputStream()
        specs.forEach { spec ->
            when (spec) {
                is Dir -> out.write(header(spec.name, 0, 0b111_101_101, '5', ""))
                is Reg -> {
                    out.write(header(spec.name, spec.content.size.toLong(), spec.mode, '0', ""))
                    out.write(spec.content)
                    pad(out, spec.content.size)
                }
                is Link -> out.write(header(spec.name, 0, 0b111_111_111, '2', spec.target))
            }
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun header(name: String, size: Long, mode: Int, flag: Char, link: String): ByteArray {
        val h = ByteArray(512)
        fun put(value: String, offset: Int, length: Int) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            System.arraycopy(bytes, 0, h, offset, minOf(bytes.size, length - 1))
        }
        fun octal(value: Long, offset: Int, length: Int) = put(java.lang.Long.toOctalString(value), offset, length)
        val (prefix, short) = if (name.toByteArray().size <= 100) "" to name else {
            val cut = name.lastIndexOf('/', 100)
            name.substring(0, cut) to name.substring(cut + 1)
        }
        put(short, 0, 100)
        octal(mode.toLong(), 100, 8)
        octal(size, 124, 12)
        h[156] = flag.code.toByte()
        put(link, 157, 100)
        put("ustar", 257, 6)
        put("00", 263, 2)
        put(prefix, 345, 155)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0
        h.forEach { sum += it.toInt() and 0xFF }
        put(sum.toString(8).padStart(6, '0') + "\u0000 ", 148, 8)
        return h
    }

    private fun pad(out: ByteArrayOutputStream, size: Int) {
        val remainder = size % 512
        if (remainder != 0) out.write(ByteArray(512 - remainder))
    }
}
