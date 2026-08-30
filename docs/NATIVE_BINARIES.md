# NATIVE BINARIES — Exact Inventory & Build Notes

Every native artifact PocketVM needs, grouped by **where it lives**:

- **HOST** — bundled in the APK, run by the app (bionic-linked or musl-static).
- **GUEST** — installed into the rootfs layers from distro packages or our builder.
- **TOOL** — build-time only (CI/Docker), never shipped to the device.

---

## 1. Host-side binaries (in APK → `filesDir/native/<abi>/`)

ABIs: **arm64-v8a** (primary), **x86_64** (emulator/ChromeOS). armeabi-v7a is out of
scope for v1 (PRoot on 32-bit is a maintenance burden); revisit for cheap tablets.

| # | Binary | Purpose | Source | License | Build notes |
|---|---|---|---|---|---|
| H1 | `proot` (or `proot-rs`) | Syscall interposer: emulates `/`, root, bind mounts. **The engine.** | `termux/proot` (C) or `proot-rs/proot-rs` (Rust) | GPL-2.0+ | C version: cross-compile bionic, `-DUSER_LAND`, `-DHAVE_ANDROID`, `-static`-ish; Rust version: `cargo-ndk`, target `aarch64-linux-android` / `x86_64-linux-android`. Shipping both in v1; instance records which one ran. |
| H2 | `tini` | PID 1 in guest: reaps zombies, forwards signals | `krallin/tini` | MIT | `musl` static build; tiny patch for Android bionic (`TCGETS` path) if needed |
| H3 | `busybox` | Host-side bootstrap (install scripts, early shell, `tar`/`dd` fallback) | `termux/busybox` | GPL-2.0+ | Termux config (no applets that conflict with host); **not** static-glibc — bionic-linked |
| H4 | `libpvmnative.so` | JNI: `extract()` (libarchive+zstd), `openpty(3)`, proc-group helpers | our code (`native/extractor`, `native/pty`, `native/launcher`) | Apache-2.0 (BSD deps) | CMake + NDK r27; links **static** libarchive + zstd |
| H5 | `pvmextract` (static, optional) | Standalone extractor for debugging / offline install fallback | our code | Apache-2.0 | musl static, `-static`; kept as belt-and-suspenders; H4 is the primary path |
| H6 | `qemu-<arch>-static` | Cross-arch guest only (e.g. arm64 rootfs on x86_64 host; i386 guest on arm64) | qemu project | GPL-2.0+ | Only in the `cross-arch` APK variant; ships `qemu-aarch64`, `qemu-arm`, `qemu-i386`, `qemu-x86_64` as needed |
| H7 | `toybox`-like `getprop`/`setprop` shim (optional) | Pass Android props (sdk, arch) into guest env | N/A (script) | — | v1 reads props from Java; H7 only if guest scripts need them |

**Never bundled** (by design): `Xvnc`, `openbox`, desktop components, `apt`, guest
shells — these come from the distro repositories inside the image layers.

---

## 2. Guest-side packages (installed into `base`/`desktop` layers)

These come from the distro's own repositories in `rootfs.yml`; the app knows only the
**layer manifest**, not individual packages.

### 2.1 `base` layer (core userspace, all flavors)

| Package group | Purpose |
|---|---|
| `libc6`/`musl`, `bash`, `coreutils`, `findutils`, `grep`, `sed`, `awk`, `tar`, `xz`, `zstd` | shell, fs, archives |
| `apt` + `ca-certificates` (Debian/Ubuntu), `apk-tools` (Alpine) | guest package management (the "install anything" promise) |
| `openssh-client/server` (optional feature flag), `locales`, `tzdata`, `man-db` | usability |
| `procps`, `psmisc`, `util-linux`, `nano`/`vim-tiny` | admin basics |
| `dash`-as-`/bin/sh`? No — keep distro default | — |

### 2.2 `desktop` layer (one per DE flavor)

| Package | Purpose | Notes |
|---|---|---|
| `tigervnc-standalone-server` (Ubuntu/Debian; Alpine: `tigervnc`) | **Xvnc** — X server + RFB server in one | `-localhost -rfbauth`; fixed version pinning per image |
| `openbox` | WM (default v1: lowest RAM) | also LXQt's WM |
| `lxqt` / `xfce4` (+ `xfce4-terminal`, `xfce4-session`, `xfce4-panel`) | alternate DEs | selected per layer flavor |
| `xterm` | guaranteed terminal | always present |
| `dbus`, `dbus-x11`, `xauth`, `x11-utils`, `xinit` | session plumbing | `dbus-run-session` or `dbus-launch` |
| `fonts-dejavu` / `fonts-noto` | text rendering | mandatory; default Debian has almost none |
| `pulseaudio` (or `pipewire-pulse`) | guest audio (Phase 5, optional) | runs in guest, binds loopback; app plays PCM via a tiny native bridge *later* |
| `xclip` | clipboard bridge (Phase 3) | app ↔ X selection |
| `openssl`, `ca-certificates` | TLS in guest (apt, curl) | in both layers |
| `/usr/local/bin/pvm-entry`, `pvm-clipboard`, `pvm-xrandr`, `pvm-bootstrap` | **our scripts**, baked into the layer | idempotent; part of the image, versioned in `dist/rootfs/builder/scripts/` |

Typical disk cost (uncompressed guest fs):
Alpine+openbox **~0.9 GB**, Debian+openbox **~1.6 GB**, Debian+XFCE **~2.4 GB**,
Ubuntu+XFCE **~3.0 GB** (real measured numbers beat estimates: the "1.2 GB Ubuntu"
figure in the product brief assumes a minimal cloud image, not a desktop).

---

## 3. Build-time tooling (never shipped)

| Tool | Use |
|---|---|
| `mmdebstrap` / `debootstrap` | Debian/Ubuntu base layers (no root needed; deterministic with `SOURCE_DATE_EPOCH`) |
| `apk` + `alpine-minirootfs` | Alpine base layer |
| `qemu-user-static` + binfmt | building **opposite arch** in Docker (avoided when using GitHub arm64 runners) |
| `minisign` (ed25519) | sign the manifest's canonical payload (`minisign -S -l -m`); public keys live in `app/src/main/assets/rootfs/keys/*.pub` — see `docs/BUILDING.md` and `scripts/` |
| `zstd` CLI | layer compression, `--long=27`, `-T0` |
| NDK r27 + CMake + `cargo-ndk` | host binaries |
| `docker` | hermetic, reproducible layer builds |

---

## 4. Layer artifact naming

```
{distro}-{codename}-{arch}-{layer}-v{N}.tar.zst
example: ubuntu-noble-aarch64-desktop-openbox-v1.tar.zst
```

Each layer ships with:
- `*.sha256` — checksum
- `manifest.json` (per distro, signed) — see `ROOTFS_SYSTEM.md` §3
- `*.buildinfo` — builder commit, packages+versions used (`dpkg -l` dump), timestamps

## 5. Vendor pinning policy

`tools/fetch-deps.sh` clones each `third_party` project at an **exact commit** recorded
in `native/third_party/VERSIONS.lock`. CI fails if the lockfile and tree diverge.
GPL components keep a `SOURCE.md` (upstream URL + exact commit + local patches), which
satisfies source-offer requirements and makes release compliance one command:
`tools/release.sh --check-licenses`.
