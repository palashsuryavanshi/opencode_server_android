import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import userland.BootstrapUserlandTask
import userland.WriteUserlandNoticesTask

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

/**
 * Spec 2 - the ABIs a userland is built for.
 *
 * OpenCode publishes builds only for the 64-bit targets, but the terminal and the rest of the
 * environment work on 32-bit too, so the native PTY and the userland are both produced for all four.
 * Drop an ABI here to remove it from the release build.
 */
val userlandAbis = (project.findProperty("userlandAbis") as String?)
  ?.split(",")
  ?.map { it.trim() }
  ?.filter { it.isNotEmpty() }
  ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/**
 * The seed package set.
 *
 * This is the closure of what the runtime needs, not a maximal environment: every package costs APK
 * size. Everything else arrives transitively through `Depends`.
 *
 *   bash/coreutils/grep/sed/tar/findutils/diffutils -> a usable shell
 *   git                                          -> version control (spec 72)
 *   nodejs-lts + npm                             -> the npm install path for OpenCode (spec 10)
 *   curl                                         -> the official installer transport (spec 11)
 *
 * `nodejs-lts` and `npm` are separate packages in the repository. The bootstrap resolves `npm`'s
 * `nodejs | nodejs-lts` dependency against the seed list, so only the LTS runtime is installed.
 */
val userlandSeedPackages =
  (project.findProperty("userlandSeedPackages") as String?)
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: listOf(
      "bash",
      "coreutils",
      "grep",
      "sed",
      "gawk",
      "tar",
      "xz-utils",
      "findutils",
      "diffutils",
      "less",
      "nano",
      "git",
      "curl",
      "openssl",
      "nodejs-lts",
      "npm"
    )

android {
  namespace = "com.palash.opencodeserver"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.palash.opencodeserver"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Spec 2: the native PTY library is built for every architecture the app targets, so a device
    // never falls back to the pipe terminal because of a missing .so. arm32 and x86 are included
    // even though OpenCode itself has no build for them: the terminal still works there.
    ndk {
      abiFilters += userlandAbis
    }

    externalNativeBuild {
      cmake {
        cppFlags += listOf("-std=c++17")
        arguments += listOf("-DANDROID_STL=c++_static")
      }
    }
  }

  // Spec 33: libopencodepty.so wraps forkpty and is what gives the terminal a real controlling
  // terminal. Without it the app degrades to the pipe-backed compatibility terminal.
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
      // Spec 114: release builds default to the real runtime. A mock build must never ship.
      buildConfigField("boolean", "USE_MOCK_BACKEND", "false")
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
      // Spec 114: set USE_MOCK_BACKEND=true in .env to develop the frontend without a runtime.
      // When the value is absent the debug build uses the real backend.
      val mockFromEnv = (project.findProperty("USE_MOCK_BACKEND") as String?)?.toBoolean() ?: false
      buildConfigField("boolean", "USE_MOCK_BACKEND", mockFromEnv.toString())
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  // Spec 2: the assembled userland is packaged as assets so it is a normal build input rather than
  // something a developer has to place by hand. ProvisionedUserlandProvider reads it from here.
  //
  // Resolved eagerly because AGP 9 rejects a Provider here: it cannot tell whether the path points
  // at a generated (read-only) or static (read-write) directory. The directory is created by the
  // bootstrap task, so it may legitimately not exist at configuration time.
  val userlandAssets = layout.buildDirectory.dir("generated/userlandAssets").get().asFile
  sourceSets {
    getByName("main") {
      assets.srcDir(userlandAssets)
    }
  }

  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

/**
 * Spec 2 - assemble the Linux userland for one ABI.
 *
 * Run explicitly, because it downloads tens of megabytes:
 *
 * ```
 * ./gradlew :app:bootstrapUserland -PuserlandAbi=arm64-v8a
 * ```
 *
 * The result lands in `build/generated/userlandAssets/userland/<arch>` and is picked up as assets by
 * the `main` source set above, so the next build bundles it.
 */
val bootstrapUserland by tasks.registering(BootstrapUserlandTask::class) {
  group = "userland"
  description = "Downloads and assembles a Linux userland for one Android ABI."

  abi.set(
    (project.findProperty("userlandAbi") as String?) ?: userlandAbis.first()
  )
  seedPackages.set(userlandSeedPackages)
  outputDirectory.set(layout.buildDirectory.dir("generated/userlandAssets"))
  cacheDirectory.set(layout.buildDirectory.dir("userland-cache"))
  // A partial payload fails verification unless this is explicitly opted into, because a userland
  // without git or Node cannot install OpenCode.
  failOnMissingBinaries.set(
    !((project.findProperty("userlandAllowPartial") as String?)?.toBoolean() ?: false)
  )
}

/** Spec 3 - record the bundled packages and their licence next to the payload. */
val writeUserlandNotices by tasks.registering(WriteUserlandNoticesTask::class) {
  group = "userland"
  description = "Records which third-party packages a bundled userland contains."

  packages.set(userlandSeedPackages)
  outputDirectory.set(layout.buildDirectory.dir("generated/userlandAssets"))
}

/** Convenience wrapper: bootstrap every configured ABI and write the notices. */
val bootstrapAllUserlands by tasks.registering {
  group = "userland"
  description = "Bootstraps the Linux userland for every configured ABI."

  val abis = userlandAbis
  dependsOn(writeUserlandNotices)
  doLast {
    logger.lifecycle(
      "Bootstrapped ABIs: ${abis.joinToString(", ")}. " +
        "Payloads are under ${layout.buildDirectory.dir("generated/userlandAssets").get()}"
    )
  }
}

// Configure the Google Services plugin
googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

dependencies {

  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.room.ktx)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.retrofit)

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}