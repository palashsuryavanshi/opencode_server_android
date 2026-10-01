package com.palash.opencodeserver.backend.userland

import com.palash.opencodeserver.backend.core.BackendResult
import com.palash.opencodeserver.backend.core.DeviceArchitecture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The installer behind `ProvisionedUserlandProvider`: ABI mapping, traversal guard, manifest
 * verification and extraction — all with fake assets so no device or APK is needed.
 */
class UserlandInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun arch(abi: String) = DeviceArchitecture(
        androidAbi = abi,
        supportedAbis = listOf(abi),
        is64Bit = abi.contains("64"),
        runtimeAbi = abi,
        libcKind = DeviceArchitecture.LibcKind.BIONIC
    )

    private fun installer(userlandDir: File, assets: Map<String, ByteArray>): UserlandInstaller {
        val source = object : UserlandInstaller.AssetSource {
            override fun open(assetPath: String): InputStream? =
                assets[assetPath]?.let { ByteArrayInputStream(it) }
        }
        return UserlandInstaller(userlandDir, log = null, assets = source)
    }

    // ---- ABI mapping (spec 2: never guess) ---------------------------------------------------

    @Test
    fun abiMapsToPayloadArchitecture() {
        val probe = installer(temp.root, emptyMap())
        assertEquals("aarch64", probe.architectureFor("arm64-v8a"))
        assertEquals("arm", probe.architectureFor("armeabi-v7a"))
        assertEquals("x86_64", probe.architectureFor("x86_64"))
        assertEquals("i686", probe.architectureFor("x86"))
    }

    @Test
    fun unknownAbiMapsToNull() {
        val probe = installer(temp.root, emptyMap())
        assertNull(probe.architectureFor("riscv64"))
        assertNull(probe.architectureFor(""))
    }

    @Test
    fun architectureMapsToInstallTriple() {
        assertEquals("aarch64-linux-android", UserlandInstaller.tripleForArchitecture("aarch64"))
        assertEquals("armv7a-linux-androideabi", UserlandInstaller.tripleForArchitecture("arm"))
        assertEquals("x86_64-linux-android", UserlandInstaller.tripleForArchitecture("x86_64"))
        assertEquals("i686-linux-android", UserlandInstaller.tripleForArchitecture("i686"))
        assertNull(UserlandInstaller.tripleForArchitecture("riscv"))
    }

    @Test
    fun unmappedAbiFailsInstallWithoutTouchingDisk() = runBlocking {
        val root = temp.newFolder("userland")
        val result = installer(root, emptyMap()).install(arch("riscv64"))
        assertTrue(result is BackendResult.Failure)
        assertFalse(File(root, "riscv64-linux-android").exists())
    }

    // ---- traversal guard (spec 83) ----------------------------------------------------------------

    @Test
    fun safeRelativeAcceptsNormalPaths() {
        assertEquals("bin/bash", UserlandInstaller.safeRelative("bin/bash"))
        assertEquals("bin/bash", UserlandInstaller.safeRelative("./bin/bash"))
        assertEquals("usr/lib/libfoo.so", UserlandInstaller.safeRelative("usr/lib/libfoo.so"))
    }

    @Test
    fun safeRelativeRejectsEscapes() {
        assertNull(UserlandInstaller.safeRelative("../../etc/passwd"))
        assertNull(UserlandInstaller.safeRelative("bin/../../etc/passwd"))
        assertNull(UserlandInstaller.safeRelative("/absolute/path"))
        assertNull(UserlandInstaller.safeRelative("C:/windows/path"))
        assertNull(UserlandInstaller.safeRelative("bin\\windows"))
        assertNull(UserlandInstaller.safeRelative(".."))
        assertNull(UserlandInstaller.safeRelative(""))
    }

    @Test
    fun traversalEntriesAreSkippedNotWritten() = runBlocking {
        val root = temp.newFolder("userland")
        val gzip = gzipTar(
            file("bin/bash", "#!/bin/bash".toByteArray()),
            file("../../evil.txt", "escape".toByteArray()),
            file("/absolute.txt", "escape".toByteArray())
        )
        val assets = mapOf(
            "userland/aarch64.tar.gz" to gzip,
            "userland/aarch64.manifest" to manifestFor(gzip)
        )
        val result = installer(root, assets).install(arch("arm64-v8a"))
        assertTrue(result is BackendResult.Success)
        assertTrue(File(root, "aarch64-linux-android/bin/bash").isFile)
        assertFalse(File(temp.root, "evil.txt").exists())
        assertFalse(File(root, "absolute.txt").exists())
    }

    // ---- happy path --------------------------------------------------------------------------

    @Test
    fun installsPayloadAndRecreatesShell() = runBlocking {
        val root = temp.newFolder("userland")
        val gzip = gzipTar(
            file("bin/bash", "#!/bin/bash".toByteArray()),
            symlink("bin/sh", "bash"),
            file("bin/git", "git".toByteArray())
        )
        val assets = mapOf(
            "userland/aarch64.tar.gz" to gzip,
            "userland/aarch64.manifest" to manifestFor(gzip)
        )
        val inst = installer(root, assets)
        val result = inst.install(arch("arm64-v8a"))
        assertTrue(result is BackendResult.Success)
        val report = (result as BackendResult.Success).value
        assertEquals("aarch64-linux-android", report.triple)
        assertTrue(report.files >= 2)
        assertTrue(File(root, "aarch64-linux-android/bin/bash").isFile)
        assertTrue(File(root, "aarch64-linux-android/bin/bash").canExecute())
        if (supportsSymlinks()) {
            assertEquals("bash", java.nio.file.Files.readSymbolicLink(File(root, "aarch64-linux-android/bin/sh").toPath()).toString())
            assertTrue(report.symlinks >= 1)
        }
    }

    @Test
    fun secondInstallIsAlreadyInstalled() = runBlocking {
        val root = temp.newFolder("userland")
        val gzip = gzipTar(file("bin/bash", "sh".toByteArray()))
        val assets = mapOf(
            "userland/aarch64.tar.gz" to gzip,
            "userland/aarch64.manifest" to manifestFor(gzip)
        )
        val inst = installer(root, assets)
        assertTrue(inst.install(arch("arm64-v8a")) is BackendResult.Success)
        val second = inst.install(arch("arm64-v8a"))
        assertTrue(second is BackendResult.Success)
        assertTrue((second as BackendResult.Success).value.alreadyInstalled)
    }

    // ---- integrity (spec 48) ---------------------------------------------------------------------

    @Test
    fun shaMismatchRefusesInstall() = runBlocking {
        val root = temp.newFolder("userland")
        val gzip = gzipTar(file("bin/bash", "sh".toByteArray()))
        val badManifest = "archive=aarch64.tar.gz\narchiveSha256=0000000000000000000000000000000000000000000000000000000000000000\n"
            .toByteArray()
        val result = installer(
            root,
            mapOf("userland/aarch64.tar.gz" to gzip, "userland/aarch64.manifest" to badManifest)
        ).install(arch("arm64-v8a"))
        assertTrue(result is BackendResult.Failure)
        assertFalse(File(root, "aarch64-linux-android/bin/bash").exists())
    }

    @Test
    fun missingAssetFailsWithArchitectureError() = runBlocking {
        val root = temp.newFolder("userland")
        val result = installer(root, emptyMap()).install(arch("arm64-v8a"))
        assertTrue(result is BackendResult.Failure)
        val error = (result as BackendResult.Failure).error
        assertTrue(error is com.palash.opencodeserver.backend.core.BackendError.ArchitectureUnsupported)
    }

    @Test
    fun payloadWithoutShellFails() = runBlocking {
        val root = temp.newFolder("userland")
        val gzip = gzipTar(file("bin/notash", "x".toByteArray()))
        val assets = mapOf(
            "userland/aarch64.tar.gz" to gzip,
            "userland/aarch64.manifest" to manifestFor(gzip)
        )
        val result = installer(root, assets).install(arch("arm64-v8a"))
        assertTrue(result is BackendResult.Failure)
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private fun manifestFor(gzip: ByteArray): ByteArray {
        val sha = UserlandArchive.sha256Hex(ByteArrayInputStream(gzip))
        return "architecture=aarch64\nabi=arm64-v8a\narchive=aarch64.tar.gz\narchiveSha256=$sha\n"
            .toByteArray(Charsets.UTF_8)
    }

    private sealed interface Spec
    private data class Reg(val name: String, val content: ByteArray) : Spec
    private data class Link(val name: String, val target: String) : Spec

    private fun file(name: String, content: ByteArray) = Reg(name, content)
    private fun symlink(name: String, target: String) = Link(name, target)

    private fun gzipTar(vararg specs: Spec): ByteArray {
        val tar = ByteArrayOutputStream()
        specs.forEach { spec ->
            when (spec) {
                is Reg -> {
                    tar.write(header(spec.name, spec.content.size.toLong(), 0b110_100_100, '0', ""))
                    tar.write(spec.content)
                    val remainder = spec.content.size % 512
                    if (remainder != 0) tar.write(ByteArray(512 - remainder))
                }
                is Link -> tar.write(header(spec.name, 0, 0b111_111_111, '2', spec.target))
            }
        }
        tar.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(tar.toByteArray()) }
        return gz.toByteArray()
    }

    private fun header(name: String, size: Long, mode: Int, flag: Char, link: String): ByteArray {
        val h = ByteArray(512)
        fun put(value: String, offset: Int, length: Int) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            System.arraycopy(bytes, 0, h, offset, minOf(bytes.size, length - 1))
        }
        fun octal(value: Long, offset: Int, length: Int) = put(java.lang.Long.toOctalString(value), offset, length)
        put(name, 0, 100)
        octal(mode.toLong(), 100, 8)
        octal(size, 124, 12)
        h[156] = flag.code.toByte()
        put(link, 157, 100)
        put("ustar", 257, 6)
        put("00", 263, 2)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0
        h.forEach { sum += it.toInt() and 0xFF }
        put(sum.toString(8).padStart(6, '0') + "\u0000 ", 148, 8)
        return h
    }

    private fun supportsSymlinks(): Boolean = runCatching {
        val probe = File.createTempFile("opencode-symlink-probe", ".tmp")
        val link = File(probe.parentFile, probe.name + "-link")
        probe.delete()
        return try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), probe.toPath())
            link.delete()
            true
        } catch (e: Exception) {
            false
        }
    }.getOrDefault(false)
}
