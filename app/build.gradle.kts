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

// Plain (abi -> dir) pairs resolved during configuration: the checks below run at
// execution time and must not touch `Project` (configuration-cache safe).
val enginePayloadDirs: List<Pair<String, File>> =
    engineAbis.map { abi -> abi to file("src/main/resources/lib/$abi") }

// Pure lambda (no script/Project capture) so the providers below stay
// configuration-cache safe.
val engineProblemsOf: (List<Pair<String, File>>) -> List<String> = { dirs ->
    dirs.flatMap { (abi, dir) ->
        val files = dir.listFiles()?.filter { it.isFile && it.name != ".gitkeep" }.orEmpty()
        if (files.isEmpty()) {
            listOf(
                "No PRoot engine payload in app/src/main/resources/lib/$abi/ — " +
                    "run ./scripts/fetch-engine.sh $abi"
            )
        } else {
            files.map { it.name }
                .filterNot { it.startsWith("lib") && it.endsWith(".so") }
                .map {
                    "app/src/main/resources/lib/$abi/$it is not named lib*.so, so Android " +
                        "will not extract it from the APK — re-run ./scripts/fetch-engine.sh $abi"
                }
        }
    }
}

/**
 * Fetches the engine payload as part of the build.
 *
 * The binaries are GPL and intentionally untracked, so *something* has to run
 * `scripts/fetch-engine.sh` or the APK ships an empty `lib/<abi>/`. Doing it here rather
 * than only in a CI workflow means a plain `./gradlew assembleDebug` on a fresh clone
 * also produces a working APK. Set `-PskipEngineFetch=true` (or `SKIP_ENGINE_FETCH=1`)
 * to build the UI without pulling binaries; the payload check then only warns.
 */
val fetchEnginePayload by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Downloads the PRoot engine payload into src/main/resources/lib/<abi>/."

    val abi = engineAbis.first()
    val script = rootProject.file("scripts/fetch-engine.sh")
    val payloadDir = file("src/main/resources/lib/$abi")

    // Skip entirely once a correctly named payload exists, so rebuilds need no network.
    // Deliberately `onlyIf` rather than `outputs.dir(payloadDir)`: the payload lives in
    // the source tree, and declaring it as a task output invites Gradle's stale-output
    // cleanup to delete files there.
    onlyIf {
        payloadDir.listFiles()
            ?.none { it.isFile && it.name.startsWith("lib") && it.name.endsWith(".so") } ?: true
    }

    commandLine("bash", script.absolutePath, abi)
    // A missing engine must not look like a green build, but an offline dev should still
    // get a usable error from verifyEnginePayload rather than a stack trace here.
    isIgnoreExitValue = true
    doLast {
        val result = executionResult.get()
        if (result.exitValue != 0) {
            logger.warn(
                "WARNING: scripts/fetch-engine.sh failed (exit ${result.exitValue}). " +
                    "The APK will have no PRoot engine unless you add it manually."
            )
        }
    }
}

val skipEngineFetch = providers.gradleProperty("skipEngineFetch").orNull == "true" ||
    providers.environmentVariable("SKIP_ENGINE_FETCH").orNull == "1"

val verifyEnginePayload by tasks.registering {
    group = "verification"
    description = "Fails the build when the PRoot engine payload is missing or misnamed."
    if (!skipEngineFetch) dependsOn(fetchEnginePayload)
    // Read through a provider so the task holds no Project reference at execution time
    // (configuration-cache safe).
    val dirs = enginePayloadDirs
    val check = engineProblemsOf
    val problems = provider { check(dirs) }
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
    if (!skipEngineFetch) dependsOn(fetchEnginePayload)
    val dirs = enginePayloadDirs
    val check = engineProblemsOf
    val problems = provider { check(dirs) }
    doLast { problems.get().forEach { logger.warn("WARNING: $it") } }
}

// The payload must exist before anything *reads* src/main/resources, not merely before
// `assembleDebug` finishes: assemble is a lifecycle task and Gradle is free to run the
// packaging tasks before any other dependency of it. Hooking the java-resource merge
// and packaging tasks directly is what actually gets the engine into the APK.
if (!skipEngineFetch) {
    tasks.matching { task ->
        task.name.contains("JavaRes") || task.name.matches(Regex("package(Debug|Release)"))
    }.configureEach { dependsOn(fetchEnginePayload) }
}

// Release must never ship without an engine; debug only warns so UI work stays possible
// offline (and debug builds do extract the historic unprefixed names anyway).
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }
    .configureEach { dependsOn(verifyEnginePayload) }

tasks.matching { it.name == "assembleDebug" }
    .configureEach { dependsOn(warnEnginePayload) }

/**
 * Verifies the *built APK*, not the source tree.
 *
 * The source-tree checks above can pass while the APK still ends up without a usable
 * engine (a packaging rule drops `resources/lib/`, a file gets renamed, AGP changes
 * behaviour). This opens the real archive and asserts `lib/<abi>/` contains an entry
 * Android will actually extract — the property the whole fix depends on.
 *
 * Failures are also echoed as `::error::` workflow commands so the reason shows up in
 * the run's annotations, not just in the (often unreachable) raw log.
 */
val verifyApkEngine by tasks.registering {
    group = "verification"
    description = "Asserts the built APK carries an extractable engine under lib/<abi>/."

    val abi: String = engineAbis.first()
    val apkDirFile: File = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
    val sourcePayload: File = file("src/main/resources/lib/$abi")
    val requiredEntry = "lib/" + abi + "/libproot.so"

    doLast {
        val staged: List<String> = (sourcePayload.list() ?: arrayOf<String>()).sorted()
        logger.lifecycle("Engine payload staged in source tree: " + staged.joinToString())

        val apks: List<File> = (apkDirFile.listFiles() ?: arrayOf<File>())
            .filter { f -> f.name.endsWith(".apk") }
        if (apks.isEmpty()) {
            throw org.gradle.api.GradleException("No debug APK found in " + apkDirFile.path)
        }

        for (apk in apks) {
            val names = ArrayList<String>()
            val zip = java.util.zip.ZipFile(apk)
            try {
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entryName = entries.nextElement().name
                    if (entryName.startsWith("lib/" + abi + "/")) {
                        names.add(entryName.substringAfterLast("/"))
                    }
                }
            } finally {
                zip.close()
            }

            val problem: String? = when {
                !names.contains("libproot.so") ->
                    apk.name + " has no " + requiredEntry + " - the app would fail at START " +
                        "with NATIVE_ENGINE_FAILED. lib/" + abi + "/ contains: " +
                        names.joinToString() + " | staged in source tree: " + staged.joinToString()
                names.any { n -> !(n.startsWith("lib") && n.endsWith(".so")) } ->
                    apk.name + " packages entries under lib/" + abi + "/ that Android will not " +
                        "extract on a release build (ADR-022): " +
                        names.filter { n -> !(n.startsWith("lib") && n.endsWith(".so")) }
                            .joinToString()
                else -> null
            }

            if (problem != null) {
                logger.error("::error::" + problem)
                throw org.gradle.api.GradleException(problem)
            }
            logger.lifecycle("Engine payload verified in " + apk.name + ": " + names.sorted().joinToString())
        }
    }
}

tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(verifyApkEngine) }

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
