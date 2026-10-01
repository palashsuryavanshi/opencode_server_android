package com.palash.opencodeserver.backend.runtime

import com.palash.opencodeserver.backend.core.DeviceArchitecture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec 2/12 - architecture mapping.
 *
 * This is the guard against the failure the spec calls out explicitly: downloading a binary that
 * cannot run. An ABI with no mapping must return null, never a guess.
 */
class ArchitectureMappingTest {

    private val resolver = com.palash.opencodeserver.backend.userland.ProvisionedUserlandProvider.TargetTripleResolver()

    private fun arch(abi: String) = DeviceArchitecture(
        androidAbi = abi,
        supportedAbis = listOf(abi),
        is64Bit = abi.contains("64"),
        runtimeAbi = abi,
        libcKind = DeviceArchitecture.LibcKind.BIONIC
    )

    @Test
    fun arm64MapsToAarch64Triple() {
        assertEquals("aarch64-linux-android", resolver.resolve(arch("arm64-v8a")))
    }

    @Test
    fun x86_64MapsToItsOwnTriple() {
        assertEquals("x86_64-linux-android", resolver.resolve(arch("x86_64")))
    }

    @Test
    fun armeabiMapsToArmv7() {
        assertEquals("armv7a-linux-androideabi", resolver.resolve(arch("armeabi-v7a")))
    }

    @Test
    fun x86MapsToI686() {
        assertEquals("i686-linux-android", resolver.resolve(arch("x86")))
    }

    @Test
    fun unknownAbiReturnsNullRatherThanGuessing() {
        // This is the important case: an unmapped ABI must not produce a plausible-looking triple.
        assertNull(resolver.resolve(arch("riscv64")))
        assertNull(resolver.resolve(arch("mips64")))
        assertNull(resolver.resolve(arch("")))
    }

    @Test
    fun explanationNamesTheAbiAndTheSupportedTargets() {
        val explanation = resolver.explain(arch("riscv64"))

        assertTrue(explanation.contains("riscv64"))
        assertTrue(explanation.contains("aarch64-linux-android"))
        assertTrue(explanation.contains("not currently available"))
    }

    @Test
    fun supportedTargetsAreThe64BitArchitectures() {
        // 32-bit targets are recognised but not listed as supported, matching OpenCodeManager.
        assertEquals(listOf("aarch64-linux-android", "x86_64-linux-android"), resolver.supportedTargets())
    }

    @Test
    fun displayNamesAreHumanReadable() {
        assertEquals("ARM64", arch("arm64-v8a").displayName)
        assertEquals("x86_64", arch("x86_64").displayName)
        assertEquals("ARM32", arch("armeabi-v7a").displayName)
        assertEquals("x86", arch("x86").displayName)
    }

    @Test
    fun allFourSpecArchitecturesAreRecognisedAsTargets() {
        // Spec 2 lists ARM64, ARM32, x86_64 and x86; all must be classified as supported targets even
        // though only the 64-bit ones have OpenCode builds.
        listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86").forEach { abi ->
            assertTrue("$abi should be a recognised target", arch(abi).isSupportedTarget)
        }
    }

    @Test
    fun unknownArchitectureIsNotASupportedTarget() {
        assertFalse(arch("riscv64").isSupportedTarget)
    }

    @Test
    fun sixtyFourBitFlagIsDerivedFromAbi() {
        assertTrue(arch("arm64-v8a").is64Bit)
        assertFalse(arch("armeabi-v7a").is64Bit)
    }

    @Test
    fun libcIsAlwaysBionicOnAndroid() {
        // A PRoot userland may present glibc internally, but the platform itself is Bionic, and that
        // is what determines whether a host binary can be loaded.
        assertEquals(DeviceArchitecture.LibcKind.BIONIC, arch("arm64-v8a").libcKind)
    }
}