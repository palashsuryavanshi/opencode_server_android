package com.palash.opencodeserver.backend.runtime

import android.os.Build
import com.palash.opencodeserver.backend.core.DeviceArchitecture

/**
 * Spec 2/12 - architecture and libc detection.
 *
 * This runs before any binary is downloaded. Nothing in the install path is allowed to fetch a
 * payload until [DeviceArchitecture.runtimeAbi] and [DeviceArchitecture.libcKind] are known, which
 * is what prevents "downloaded an x86_64 binary onto an ARM64 phone" (spec 2).
 */
class ArchitectureDetector {

    private val notes = mutableListOf<String>()

    fun detect(): DeviceArchitecture {
        notes.clear()

        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val primaryAbi = supportedAbis.firstOrNull() ?: Build.CPU_ABI.orEmpty()
        val is64Bit = Build.VERSION.SDK_INT >= 23 && primaryAbi.contains("64")
        val libc = detectLibc(primaryAbi)

        if (supportedAbis.size > 1) {
            notes += "Device advertises ${supportedAbis.size} ABIs; ${primaryAbi.ifEmpty { "unknown" }} selected."
        }
        notes += "Android SDK ${Build.VERSION.SDK_INT} (release ${Build.VERSION.RELEASE})."

        return DeviceArchitecture(
            androidAbi = primaryAbi,
            supportedAbis = supportedAbis,
            is64Bit = is64Bit,
            runtimeAbi = primaryAbi,
            libcKind = libc,
            notes = notes.toList()
        )
    }

    /**
     * Android always links against Bionic, but a PRoot userland can present glibc or musl inside the
     * sandbox. The distinction matters because an OpenCode binary built for glibc will not run
     * against Bionic and vice versa.
     */
    private fun detectLibc(primaryAbi: String): DeviceArchitecture.LibcKind =
        DeviceArchitecture.LibcKind.BIONIC

    /** Spec 12 - the platform string we hand to installers and diagnostics. */
    fun platformDescription(arch: DeviceArchitecture = detect()): String =
        "Android ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT} / ${arch.displayName} / ${arch.libcKind.name.lowercase()}"
}