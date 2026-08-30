plugins {
    id("com.android.application")
    // Required with AGP 9 built-in Kotlin: AGP does not wire the Compose
    // compiler itself. Version 2.2.10 matches the Kotlin compiler embedded in
    // AGP 9.3.2 (see its POM: kotlin-gradle-plugin 2.2.10).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "com.lenix"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lenix"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Engine binaries (proot, loader, tini, libtalloc…) ship in
        // src/main/resources/lib/<abi>/ so the APK contains them under lib/<abi>/ and the
        // package manager EXTRACTS them to /data/app/.../lib/<abi>/ — the only
        // app-reachable location where SELinux allows execve() on Android 10+ (ADR-021).
        // (AGP only packages *.so from jniLibs; resources/lib/<abi>/ is the documented
        // route for arbitrary executables — same trick as wrap.sh.)
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        disable += setOf(
            "NotificationPermission",
            "ForegroundServiceType",
            "StartForegroundMissingType",
            "NewApi",
            "UnusedAttribute",
            "IconLauncherShape",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.10.0")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // RootFS manifest parsing (JSON)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")

    // Resumable RootFS downloads (HTTP Range resume, ETag/If-Range validation).
    // ARCHITECTURE.md pins OkHttp for the downloader; see docs/DECISIONS.md ADR-015.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // RootFS manifest signatures (Phase 4) need no dependency at all: Ed25519 ships with
    // the platform's JCA from API 29, which is also minSdk here (see ADR-017).
    //
    // Streaming RootFS extraction (Phase 5) is pure Java, so it works before the native
    // engine lands: Commons Compress reads the tar stream (ustar/pax/GNU long names,
    // sparse entries) and XZ for Java decodes the .tar.xz layers that proot-distro and
    // our own builder publish. Both are dependency-light, license-clean (Apache-2.0 and
    // 0BSD) and carry no native code; see ADR-018.
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    // Local unit tests
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Guard against the two ways the engine payload silently disappears from an APK.
 *
 * 1. The payload dir is empty (nobody ran `scripts/fetch-engine.sh`), so the app builds
 *    fine and then fails on-device with NATIVE_ENGINE_FAILED.
 * 2. A file is there but not named `lib*.so`. Packaging it under `lib/<abi>/` is not
 *    enough: for a non-debuggable package Android's installer only extracts entries
 *    whose base name starts with `lib` and ends with `.so`
 *    (frameworks/base, libs/androidfw/ApkParsing.cpp, ValidLibraryPathLastSlash()), so
 *    a `proot` / `loader` / `libtalloc.so.2` payload never reaches nativeLibraryDir.
 *    Debug builds relax that filter, which is exactly why this bug survives testing.
 *
 * Release builds fail hard; debug builds only warn, so contributors can still work on
 * UI without fetching GPL binaries.
 */
val engineAbis = listOf("arm64-v8a")

fun engineProblems(): List<String> = engineAbis.flatMap { abi ->
    val dir = file("src/main/resources/lib/$abi")
    val files = dir.listFiles()?.filter { it.isFile && it.name != ".gitkeep" }.orEmpty()
    when {
        files.isEmpty() -> listOf(
            "No PRoot engine payload in src/main/resources/lib/$abi/ — " +
                "run ./scripts/fetch-engine.sh $abi"
        )
        else -> files.map { it.name }
            .filterNot { it.startsWith("lib") && it.endsWith(".so") }
            .map {
                "src/main/resources/lib/$abi/$it is not named lib*.so, so Android will " +
                    "not extract it from the APK — re-run ./scripts/fetch-engine.sh $abi"
            }
    }
}

val verifyEnginePayload by tasks.registering {
    group = "verification"
    description = "Fails the build when the PRoot engine payload is missing or misnamed."
    // Resolved at configuration time so the task carries no Project reference into the
    // execution phase (configuration-cache safe).
    val problems = provider { engineProblems() }
    doLast {
        val found = problems.get()
        if (found.isNotEmpty()) {
            throw org.gradle.api.GradleException(
                "Engine payload check failed:\n  - " + found.joinToString("\n  - ") +
                    "\nThe APK would install without a runnable engine and fail at START " +
                    "with NATIVE_ENGINE_FAILED (see docs/DECISIONS.md ADR-022)."
            )
        }
    }
}

val warnEnginePayload by tasks.registering {
    description = "Warns (without failing) when a debug build has no usable engine payload."
    val problems = provider { engineProblems() }
    doLast { problems.get().forEach { logger.warn("WARNING: $it") } }
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(verifyEnginePayload) }

tasks.matching { it.name == "assembleDebug" }
    .configureEach { dependsOn(warnEnginePayload) }

/**
 * CI reads the console, not the HTML report, so a failing test has to explain itself there:
 * full exception messages and stack traces (one summarized line is not a diagnosis), plus
 * anything a test prints on purpose.
 */
tasks.withType<Test>().configureEach {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events(org.gradle.api.tasks.testing.logging.TestLogEvent.FAILED)
        showStandardStreams = true
    }
}
