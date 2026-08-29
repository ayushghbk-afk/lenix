# Lenix — Pocket Linux Environment

A single Android APK that installs and runs a complete Linux userspace using PRoot
(no root required, no Termux, no external VNC app) and renders the Linux desktop
**inside the app**.

> Install Debian/Alpine → press **Start** → a Linux desktop opens in the app.

## Status

**Phase 1 — Android Project Foundation**

The Android project structure is now in place with:
- Jetpack Compose UI
- Gradle build system
- GitHub Actions CI/CD pipeline
- Basic instance data models

The Linux runtime engine is next on the roadmap.

## Quick Start

### Build Locally

```bash
# Requires Android SDK and Java 17
./gradlew assembleDebug

# APK will be at: app/build/outputs/apk/debug/app-debug.apk
```

### Build via GitHub Actions

1. Push to `main` or any branch under `arena/**`
2. Go to **Actions** → **Build Lenix APK** → **Run workflow**
3. Download the artifact from the completed run

## Features (Planned)

- [ ] **No root required** — PRoot-based userspace isolation
- [ ] **Lightweight first** — Start with Openbox, then optional LXQt/XFCE
- [ ] **Built-in VNC viewer** — No external app needed
- [ ] **On-demand RootFS** — Small APK, download distros after install
- [ ] **Instance management** — Create, clone, backup, restore

## Architecture

```
Lenix APK
    │
    ├── Android UI (Jetpack Compose)
    │
    ├── Instance Manager
    │       └── VmInstance data model
    │
    ├── RootFS Installer
    │       ├── Manifest download
    │       ├── RootFS download
    │       └── Checksum verification
    │
    └── Linux Engine
            ├── PRoot (userspace chroot)
            ├── BusyBox (shell utilities)
            └── TigerVNC (display server)
```

## Device Requirements

| Requirement | Minimum | Recommended |
|-------------|---------|-------------|
| Android | 10 (API 29) | 12+ |
| RAM | 2 GB | 4 GB+ |
| Storage | 2 GB free | 8 GB+ |
| Architecture | ARM64 | ARM64 |

- **No root required**
- **No Termux required**
- **No external VNC app required**

## Documents

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Product goals, app architecture, native engine, security |
| [`docs/NATIVE_BINARIES.md`](docs/NATIVE_BINARIES.md) | Native binary inventory and build notes |
| [`docs/ROOTFS_SYSTEM.md`](docs/ROOTFS_SYSTEM.md) | RootFS distribution format and CI pipeline |
| [`docs/BUILDING.md`](docs/BUILDING.md) | How to build the project |

## Roadmap

- [x] **Phase 0** — Design docs and repo structure
- [x] **Phase 1** — Android project foundation (current)
- [ ] **Phase 2** — Instance manager (VmManager, StorageManager)
- [ ] **Phase 3** — RootFS installer (download, verify, extract)
- [ ] **Phase 4** — Linux engine integration (PRoot, BusyBox)
- [ ] **Phase 5** — Built-in VNC viewer
- [ ] **Phase 6** — Openbox desktop (lightweight first!)
- [ ] **Future** — LXQt/XFCE, snapshots, multi-instance

## License

Apache-2.0 for app code. GPL components (PRoot, BusyBox, TigerVNC) are executed as
separate processes and vendored separately for license compliance.
