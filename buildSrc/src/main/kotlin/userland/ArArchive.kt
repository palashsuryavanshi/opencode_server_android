package userland

import java.io.File

/**
 * A minimal `ar` reader.
 *
 * Debian packages are `ar` archives. The archive opens with an 8-byte global magic, and each member
 * is then preceded by a fixed 60-byte header:
 *
 * ```
 * global   0..8    "!<arch>\n"
 * --- per-member header, relative to its own start ---
 * name     0..16   ASCII, space padded (GNU deb appends a trailing "/")
 * mtime   16..28
 * uid     28..34
 * gid     34..40
 * mode    40..48
 * size    48..58   ASCII decimal
 * magic   58..60   "`\n"
 * ```
 *
 * Members are aligned to an even offset. That is the whole format, so parsing it here is cheaper
 * and more predictable than shelling out to a host `ar`, which does not exist on Windows.
 */
object ArArchive {

    private const val MAGIC = "!<arch>\n"
    private const val HEADER_SIZE = 60
    private const val FILE_MAGIC = "`\n"

    class Entry(val name: String, val size: Long, val offset: Long)

    /** Every member in the archive, in order. */
    fun entries(file: File): List<Entry> {
        val bytes = file.readBytes()
        require(bytes.size > MAGIC.length && String(bytes, 0, MAGIC.length, Charsets.US_ASCII) == MAGIC) {
            "${file.name} is not an ar archive"
        }

        val result = mutableListOf<Entry>()
        var offset = MAGIC.length.toLong()

        while (offset + HEADER_SIZE <= bytes.size) {
            val header = String(bytes, offset.toInt(), HEADER_SIZE, Charsets.ISO_8859_1)

            // The field magic must be exactly "`\n"; anything else means the archive is corrupt or
            // has a format this reader does not understand, and continuing would produce garbage.
            require(header.substring(58, 60) == FILE_MAGIC) {
                "${file.name} has a malformed ar header at offset $offset"
            }

            val name = header.substring(0, 16).trim().trimEnd('/')
            val size = header.substring(48, 58).trim().toLongOrNull()
                ?: error("${file.name} has an unreadable size field at offset $offset")

            val dataOffset = offset + HEADER_SIZE
            result += Entry(name, size, dataOffset)

            // ar pads every member to an even offset.
            offset = dataOffset + size + (size and 1L)
        }

        return result
    }

    /** Extracts the member whose name starts with [prefix], e.g. "data.tar". */
    fun extractMember(file: File, prefix: String, destination: File) {
        val entry = entries(file).firstOrNull { it.name.startsWith(prefix) }
            ?: error("${file.name} has no member starting with '$prefix'")

        file.inputStream().use { input ->
            input.skip(entry.offset)
            val bytes = ByteArray(entry.size.toInt())
            input.read(bytes)
            destination.writeBytes(bytes)
        }
    }

    /** The `data.tar.*` payload of a Debian package, saved next to [workDir]. */
    fun extractDataTarball(debFile: File, workDir: File): File {
        val dataMember = entries(debFile).firstOrNull { it.name.startsWith("data.tar") }
            ?: error("${debFile.name} has no data.tar member")

        val target = File(workDir, dataMember.name)
        extractMember(debFile, "data.tar", target)
        return target
    }
}