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
