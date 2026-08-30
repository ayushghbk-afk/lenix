# Lenix

Lenix is an Android environment that installs and runs a Linux userspace inside a
single APK using **PRoot** (no root required, no Termux, no external VNC app). The
first release is a **Lenix Runtime** on top of PRoot — not a full Android VM.

> v0.1 scope: **Debian + ARM64 only**. The APK builds in CI, installs on Android,
> and shows a VM manager UI with an explicit INSTALL → READY → START flow.

## Status

**Phase 3 — Resumable RootFS downloader (and the settings fix)**

- Real install pipeline: bundled manifest → download → verify → stage → commit,
  driven by `data.download.ResumableDownloader` (ADR-015)
- HTTP `Range` resume from byte-exact `.part` files, with ETag/`If-Range`
  validation so changed upstream content restarts instead of corrupting
- Content-addressed layer cache at `filesDir/cache/layers/<sha256>.layer`,
  shared across instances and retries: a layer is downloaded at most once
- Retry with exponential backoff for transient failures (I/O, short reads,
  408/429/5xx); checksum gate before anything is trusted; 416 and
  range-ignoring servers handled by clean restart
- Interrupted installs really resume: process death or cancel leaves the
  `.part` + per-instance `state.json` checkpoint behind, and RESUME INSTALL
  continues where it stopped (the Home screen shows the interrupted percentage)
- Real Debian bookworm arm64 layer published to GitHub Releases (Range-supported)
  by the `rootfs.yml` workflow; the APK pins its manifest
  (`assets/rootfs/debian-bookworm-aarch64.json`, sha256-verified on download)
- Fixed: settings now actually save — `filesDir/settings.json` via
  `JsonSettingsStore` (ADR-016), with the storage-care toggle gating a real
  free-space precheck before installs
- Unit tests for the downloader (MockWebServer: resume, ETag, 416, retry,
  cancellation), the full installer pipeline, and the new stores

**Phase 2 — Instance manager and local persistence**

- Multi-instance manager: create / rename / delete instances with slug ids, unique
  names, and a v0.1 instance cap (4)
- Per-instance `config.json` records under `filesDir/instances/<id>/`, written
  atomically (temp file + rename) and reloaded on every app start (ADR-014)
- Every state transition is persisted (ADR-012); instances survive app restarts
- Crash recovery: transient states never survive a process restart — a running guest
  becomes `READY` again, an interrupted install becomes `ERROR` +
  `INSTALL_INTERRUPTED` (retryable; since Phase 3 the retry resumes the download)
- Selected instance is remembered across restarts (`selected_instance` file)
- Functional Instance Manager screen: per-row state, on-disk size, rename, delete,
  create dialog driven by the distro catalog
- Unit tests for the store, selection store, and manager persistence/recovery

**Phase 1 — Android Project Foundation**

- Jetpack Compose UI with a home / settings / instance / terminal / desktop / distro
  screen scaffold
- Gradle 9.5 + AGP 9.3 (built-in Kotlin)
- GitHub Actions APK build, lint, and release workflows
- Explicit VM state machine (`NOT_INSTALLED → DOWNLOADING → VERIFYING → EXTRACTING →
  INSTALLING → READY → STARTING → RUNNING → STOPPING`)
- RootFS manifest parser (JSON schema + field validation)
- RootFS checksum verifier interface
- Local fake installer for UI testing
- Unit tests for package `vm` and `installer`

The actual PRoot engine, PTY, and VNC viewer are the next phases (extraction of
the downloaded layers is Phase 5; until then a verified layer is staged into the
instance directory).

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
- Android SDK with API 37

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
├── data/               # local persistence: config.json instance store, selection,
│   ├── SettingsStore.kt      # settings.json (the settings-savings fix)
│   ├── InstallStateStore.kt  # per-instance state.json install checkpoints
│   └── download/             # ResumableDownloader + content-addressed LayerCache
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
- [x] **Phase 2** — instance manager and local persistence
- [x] **Phase 3** — resumable RootFS downloader
- [ ] **Phase 4** — checksum + signature verification
- [ ] **Phase 5** — RootFS extraction
- [ ] **Phase 6** — native engine (PRoot) + terminal
- [ ] **Phase 7** — Openbox desktop + built-in VNC viewer

## License

Apache-2.0 for Lenix app code (see [`LICENSE`](LICENSE)). PRoot, BusyBox, TigerVNC,
and other GPL components are executed as separate processes and vendored separately
for license compliance; native asset licensing and source-offer metadata are tracked
in `docs/NATIVE_BINARIES.md`.
