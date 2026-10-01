plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    // Pure-Java XZ decoder. Termux packages ship `data.tar.xz`, and using a library rather than a
    // host `xz`/`tar` binary keeps the bootstrap task working identically on Windows, macOS and Linux
    // without depending on what happens to be installed.
    implementation("org.tukaani:xz:1.9")
}

// No explicit toolchain: buildSrc deliberately compiles against whichever JVM is running the build
// (Android Studio's bundled JBR in practice). Pinning a version here would force every contributor
// to have that exact JDK installed, since buildSrc does not inherit the root project's toolchain
// resolver.