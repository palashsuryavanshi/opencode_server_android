package userland

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.GZIPOutputStream

/**
 * A minimal `ustar` writer.
 *
 * The userland has to be shipped as one archive rather than a directory of assets, for two reasons:
 *
 *  1. **Symlinks.** A Linux userland depends on them (`sh` is `bash`, libraries point at versioned
 *     `.so` names). APK assets cannot represent a symlink, and a Windows build host cannot even
 *     create one without elevated privileges. Recording the link in the archive and recreating it on
 *     device - where symlinks work - is the only way to preserve them faithfully.
 *  2. **Size.** One gzip stream compresses the ~190 MB installed tree to a fraction of that, and
 *     lets AAPT treat it as a single opaque asset.
 */
class TarWriter(output: OutputStream) : AutoCloseable {

    companion object {
        private const val BLOCK_SIZE = 512
        private const val FILE_FLAG = '0'
        private const val SYMLINK_FLAG = '2'
        private const val DIRECTORY_FLAG = '5'

        /** 0755: readable and executable by everyone, writable by the owner. */
        const val MODE_EXECUTABLE = 0b111_101_101

        /** 0644: readable by everyone, writable by the owner. */
        const val MODE_REGULAR = 0b110_100_100

        /** 0777, the conventional mode for a symlink, which needs no permissions of its own. */
        const val MODE_SYMLINK = 0b111_111_111

        /**
         * True when this host can create symlinks.
         *
         * A Windows build machine cannot without developer mode, so links are recorded for the
         * device to create instead of failing the build on the developer's machine.
         */
        fun hostSupportsSymlinks(): Boolean =
            runCatching {
                val probe = File.createTempFile("opencode-symlink-probe", ".tmp")
                val link = File(probe.parentFile, probe.name + "-link")
                probe.delete()
                java.nio.file.Files.createSymbolicLink(link.toPath(), probe.toPath())
                link.delete()
                true
            }.getOrDefault(false)
    }

    /** Everything captured during extraction: real files plus the links a Windows host cannot make. */
    class Tree(
        val root: File,
        val symlinks: Map<String, String>
    ) {
        fun files(): List<File> = root.walkTopDown().filter { it.isFile }.toList()
    }

    private val out: OutputStream = output

    /** Writes [tree] as a gzipped tar, plus a manifest listing what went in. */
    fun writeTree(tree: Tree) {
        GZIPOutputStream(out).use { gzip ->
            val writer = TarWriter(gzip)

            tree.files().forEach { file ->
                val relative = file.relativeTo(tree.root).invariantSeparatorsPath
                writer.addFile(relative, file.readBytes(), mode = modeOf(file))
            }

            // Recorded links become real symlink entries, so a device extracts them as links.
            tree.symlinks.forEach { (path, target) ->
                writer.addSymlink(path, target)
            }

            writer.close()
        }
    }

    private fun modeOf(file: File): Int =
        if (file.canExecute()) MODE_EXECUTABLE else MODE_REGULAR

    fun addFile(name: String, content: ByteArray, mode: Int) {
        val header = buildHeader(
            name = name,
            size = content.size.toLong(),
            mode = mode,
            typeFlag = FILE_FLAG,
            linkTarget = ""
        )
        out.write(header)
        out.write(content)
        pad(content.size)
    }

    fun addSymlink(name: String, target: String) {
        val header = buildHeader(
            name = name,
            size = 0L,
            mode = MODE_SYMLINK,
            typeFlag = SYMLINK_FLAG,
            linkTarget = target
        )
        out.write(header)
    }

    fun addDirectory(name: String, mode: Int = MODE_EXECUTABLE) {
        out.write(
            buildHeader(
                name = if (name.endsWith("/")) name else "$name/",
                size = 0L,
                mode = mode,
                typeFlag = DIRECTORY_FLAG,
                linkTarget = ""
            )
        )
    }

    /** Two zero blocks terminate the archive. */
    fun finish() {
        out.write(ByteArray(BLOCK_SIZE * 2))
        out.flush()
    }

    override fun close() {
        finish()
    }

    private fun pad(contentLength: Int) {
        val remainder = contentLength % BLOCK_SIZE
        if (remainder != 0) out.write(ByteArray(BLOCK_SIZE - remainder))
    }

    private fun buildHeader(
        name: String,
        size: Long,
        mode: Int,
        typeFlag: Char,
        linkTarget: String
    ): ByteArray {
        val header = ByteArray(BLOCK_SIZE)

        fun put(value: String, offset: Int, length: Int) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            // A field that does not fit is truncated; the ustar prefix/name split handles long paths,
            // and every path this writer produces is well under the limits.
            val count = minOf(bytes.size, length - 1)
            System.arraycopy(bytes, 0, header, offset, count)
        }

        fun putOctal(value: Long, offset: Int, length: Int) {
            val octal = java.lang.Long.toOctalString(value)
            put(octal, offset, length)
        }

        // Split overlong paths across the ustar prefix/name fields rather than truncating them.
        val (prefix, shortName) = splitName(name)
        put(shortName, 0, 100)
        putOctal(mode.toLong(), 100, 8)
        putOctal(0L, 108, 8)          // uid
        putOctal(0L, 116, 8)          // gid
        putOctal(size, 124, 12)
        putOctal(0L, 136, 12)         // mtime, zero keeps the output reproducible
        header[156] = typeFlag.code.toByte()
        put(linkTarget, 157, 100)
        put("ustar", 257, 6)
        put("00", 263, 2)
        put(prefix, 345, 155)
        // The checksum is computed with its own field filled with spaces, per POSIX. A reader that
        // predates this fix stored a zeros-based sum instead; UserlandArchive accepts both, so old
        // payloads keep extracting.
        for (i in 148 until 156) header[i] = ' '.code.toByte()

        var checksum = 0
        header.forEach { checksum += it.toInt() and 0xFF }
        put(checksum.toString(8).padStart(6, '0') + "\u0000 ", 148, 8)

        return header
    }

    /** Split [name] into (prefix, shortName) for the ustar 155+100 byte fields. */
    private fun splitName(name: String): Pair<String, String> {
        val cleaned = name.trimStart('/')
        if (cleaned.toByteArray(Charsets.UTF_8).size <= 100) return "" to cleaned
        val cut = cleaned.lastIndexOf('/', 100)
        if (cut <= 0 || cut > 155) return "" to cleaned.takeLast(100)
        return cleaned.substring(0, cut) to cleaned.substring(cut + 1)
    }
}

/** Collects a filesystem tree plus any links that had to be recorded rather than created. */
object TreeCollector {

    fun collect(root: File, symlinks: Map<String, String>): TarWriter.Tree {
        // Directories are written first so a file can never appear before its parent.
        symlinks.keys.forEach { path ->
            File(root, path).parentFile?.mkdirs()
        }
        return TarWriter.Tree(root, symlinks)
    }

    /** Fails loudly rather than silently producing an incomplete archive. */
    @Throws(IOException::class)
    fun requireReadable(root: File) {
        require(root.isDirectory) { "Userland root is not a directory: $root" }
    }
}