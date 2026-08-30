# Building Lenix

## Prerequisites

- **Java Development Kit (JDK) 17** or higher
- **Android SDK** with API level 34
- **Gradle 8.4** (included via wrapper)

## Build Commands

### Debug Build

```bash
./gradlew assembleDebug
```

The APK will be at: `app/build/outputs/apk/debug/app-debug.apk`

### Release Build

```bash
./gradlew assembleRelease
```

The APK will be at: `app/build/outputs/apk/release/app-release-unsigned.apk`

### Clean Build

```bash
./gradlew clean
```

### Build with Dependencies Report

```bash
./gradlew dependencies --configuration debugRuntimeClasspath
```

## CI/CD Build

GitHub Actions workflows:

- `.github/workflows/build-apk.yml` — builds the debug APK and uploads the artifact
- `.github/workflows/lint.yml` — runs `lintDebug` and unit tests
- `.github/workflows/release.yml` — builds release APK on a `v*` tag and publishes it

Build triggers:

1. Push to `main` branch
2. Push to any `arena/**` branch
3. Manual workflow dispatch

Scripts:

```bash
./scripts/build.sh   # assembleDebug
./scripts/lint.sh    # lintDebug + testDebugUnitTest
ANDROID_HOME=$HOME/Android/Sdk ./scripts/smoke.sh  # install and launch on a connected device
```

## Android SDK Setup

If you don't have the Android SDK installed:

```bash
# Download Android command line tools
mkdir -p ~/android-sdk/cmdline-tools
cd ~/android-sdk/cmdline-tools
curl -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip tools.zip
mv cmdline-tools latest

# Accept licenses and install SDK components
export ANDROID_HOME=~/android-sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
yes | sdkmanager --licenses
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

## Troubleshooting

### OutOfMemoryError

Increase JVM heap size in `gradle.properties`:
```
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
```

### Gradle Sync Fails

Try:
```bash
rm -rf ~/.gradle/caches
./gradlew --stop
./gradlew assembleDebug
```

### Missing NDK

NDK is not required for Stage 1. Native code integration comes in Phase 4.
