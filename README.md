# lenix — PocketVM

A single Android APK that installs and runs a complete Linux userspace (PRoot-based,
no root, no Termux, no external VNC app) and renders the Linux desktop **inside the app**.

> Install Ubuntu/Debian/Alpine → press **Start** → a Linux desktop opens in the app.

## Status

**Phase 0 — Design (current).** The repository is a design-stage skeleton. The
architecture, native binary inventory, and RootFS distribution format are specified
in the documents below; no implementation code exists yet.

## Documents

| Document | Contents |
|---|---|
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Product goals, full project structure, Android app architecture, native engine, instance lifecycle, launcher pipeline, VNC subsystem, touch controls, security, build/CI, roadmap, risks |
| [`docs/NATIVE_BINARIES.md`](docs/NATIVE_BINARIES.md) | Exact inventory of every native binary/module: purpose, source, license, ABI, where it lives (APK vs rootfs layer), and build notes |
| [`docs/ROOTFS_SYSTEM.md`](docs/ROOTFS_SYSTEM.md) | RootFS distribution format: layer layout, manifest schema, signatures, download/resume, extraction, atomic install, backups, and the CI pipeline that builds the images |

## Design highlights

- **No root required** — `proot`-style ptrace userspace isolation (host binaries run on
  bionic; the guest rootfs runs its own glibc/musl).
- **One APK** — hosts the native engine (proot, tini, busybox, libarchive/zstd extractor)
  and a pure-Kotlin RFB (VNC) client. Nothing else to install.
- **Desktop = TigerVNC `Xvnc` inside the guest**, bound to `127.0.0.1` only, with a
  per-instance generated password; the app renders the RFB stream to a Surface/Compose canvas.
- **Layered, signed RootFS images** — `base` + `desktop` layers, `sha256` + Ed25519
  signatures, resumable downloads, atomic installs, per-instance COW-free clones.
- **Two modes** — PocketVM Lite (PRoot, shippable now) and PocketVM Full (real
  virtualization, requires root/KVM, later).

## License strategy

Host-side app code is Apache-2.0, using BSD/MIT dependencies only. GPL components
(proot, busybox, TigerVNC, the desktop environment) are **executed as separate
processes**, never linked into the APK, and their source is vendored for compliance.

## Roadmap (from `docs/ARCHITECTURE.md` §13)

- **Phase 0** — scaffold repo, CI skeleton, native dep vendoring
- **Phase 1** — MVP: install one distro, launch shell, built-in terminal
- **Phase 2** — desktop: Xvnc + openbox/XFCE + built-in RFB viewer
- **Phase 3** — touch/mouse toolbar, clipboard, sizing, landscape
- **Phase 4** — instance manager: clone/backup/restore/storage
- **Phase 5** — multi-instance, snapshots, FGS runtime, file sharing, optional Full-VM backend
