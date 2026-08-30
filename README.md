# Lenix

Lenix is an Android environment that installs and runs a Linux userspace inside a
single APK using **PRoot** (no root required, no Termux, no external VNC app). The
first release is a **Lenix Runtime** on top of PRoot — not a full Android VM.

> v0.1 scope: **Debian + ARM64 only**. The APK builds in CI, installs on Android,
> and shows a VM manager UI with an explicit INSTALL → READY → START flow.

## Status

**Phase 1 — Android Project Foundation**

- Jetpack Compose UI with a home / settings / instance / terminal / desktop / distro
  screen scaffold
- Gradle 8.4 + AGP 8.2 + Kotlin 1.9
- GitHub Actions APK build, lint, and release workflows
- Explicit VM state machine (`NOT_INSTALLED → DOWNLOADING → VERIFYING → EXTRACTING →
  INSTALLING → READY → STARTING → RUNNING → STOPPING`)
- RootFS manifest parser (JSON schema + field validation)
- RootFS checksum verifier interface
- Local fake installer for UI testing
- Unit tests for package `vm` and `installer`

The actual PRoot engine, RootFS downloader, PTY, and VNC viewer are the next phases.

## Runtime model

```
Lenix Runtime (v0.1+)
    Android
      → Lenix Native Engine
      → PRoot
      → Linux RootFS

Future Lenix VM
    Android
      → Virtualization Backend
      → Linux Kernel
      → Virtual Disk
```

## Repository layout

```
lenix/
├── app/                     # Android app (Jetpack Compose)
├── gradle/                  # Gradle wrapper + shared config
├── gradlew, gradlew.bat
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── .github/workflows/       # build, lint, release
├── native/                  # native engine tree (Phase 1+)
├── scripts/                 # build / lint / smoke helpers
├── docs/                    # architecture + decisions
├── LICENSE
├── .gitignore
└── README.md
```

## Quick start

### Prerequisites

- JDK 17
- Android SDK with API 34

### Build locally

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

### Build via GitHub Actions

Push to `main` or any `arena/**` branch. The **Build Lenix APK** workflow runs unit
tests, assembles the debug APK, and uploads the artifact.

### Smoke test on a device

```bash
ANDROID_HOME=~/Android/Sdk ./scripts/smoke.sh
```

## Product scope (v0.1)

| Item | v0.1 |
|---|---|
| Distro | Debian |
| Architecture | arm64-v8a (`aarch64`) |
| Desktop | Openbox |
| Root access | Not required |
| RootFS install | Download → verify → extract → run |
| First user-visible milestone | APK from CI installs and shows the VM manager UI |

Later releases add Ubuntu, Alpine, LXQt/XFCE, multi-instance, and a built-in VNC
viewer.

## Architecture

The app is organized to avoid a monolithic `MainActivity`:

```
app/src/main/java/com/lenix/
├── ui/                 # Compose screens + navigation
│   ├── App.kt
│   ├── MainActivity.kt
│   ├── HomeViewModel.kt
│   └── screens/
│       ├── HomeScreen.kt
│       ├── InstanceScreen.kt
│       ├── InstallScreen.kt
│       ├── TerminalScreen.kt
│       ├── DesktopScreen.kt
│       └── SettingsScreen.kt
├── vm/                 # state machine, VmManager, instance/process abstractions
├── installer/          # RootFS manifest, verifier, catalog, installer
├── data/               # local persistence (Room / preferences, next phase)
├── domain/             # models and usecases (next phase)
└── native/             # NativeBridge (next phase)
```

### State machine

Linux status is never tracked with random booleans. `VmState` is one enum and
`VmStateMachine` rejects illegal transitions:

```
NOT_INSTALLED → DOWNLOADING → VERIFYING → EXTRACTING → INSTALLING → READY
  READY → STARTING → RUNNING → STOPPING → READY
  any → ERROR
```

### Error categories

```
NETWORK_ERROR, INSUFFICIENT_STORAGE, DOWNLOAD_CORRUPTED, CHECKSUM_FAILED,
ROOTFS_EXTRACTION_FAILED, UNSUPPORTED_ARCHITECTURE, NATIVE_ENGINE_FAILED,
PROCESS_CRASHED, VNC_CONNECTION_FAILED
```

## Device requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| Android | 10 (API 29) | 12+ |
| RAM | 2 GB | 4 GB+ |
| Storage | 2 GB free | 8 GB+ |
| Architecture | arm64-v8a | arm64-v8a |

`armeabi-v7a`, `x86`, and `x86_64` are explicitly rejected at first.

## Documents

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Product goals, app architecture, native engine, security |
| [`docs/BUILDING.md`](docs/BUILDING.md) | Build instructions |
| [`docs/DECISIONS.md`](docs/DECISIONS.md) | Architecture decision record |
| [`docs/DEVICE_COMPATIBILITY.md`](docs/DEVICE_COMPATIBILITY.md) | Device support matrix |
| [`docs/NATIVE_BINARIES.md`](docs/NATIVE_BINARIES.md) | Native binary inventory |
| [`docs/ROOTFS_SYSTEM.md`](docs/ROOTFS_SYSTEM.md) | RootFS distribution and install pipeline |

## Roadmap

- [x] **Phase 0** — design docs and repo structure
- [x] **Phase 1** — Android project foundation, Compose UI, state machine, manifest parser
- [ ] **Phase 2** — instance manager and local persistence
- [ ] **Phase 3** — resumable RootFS downloader
- [ ] **Phase 4** — checksum + signature verification
- [ ] **Phase 5** — RootFS extraction
- [ ] **Phase 6** — native engine (PRoot) + terminal
- [ ] **Phase 7** — Openbox desktop + built-in VNC viewer

## License

Apache-2.0 for Lenix app code (see [`LICENSE`](LICENSE)). PRoot, BusyBox, TigerVNC,
and other GPL components are executed as separate processes and vendored separately
for license compliance; native asset licensing and source-offer metadata are tracked
in `docs/NATIVE_BINARIES.md`.
