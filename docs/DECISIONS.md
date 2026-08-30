# DECISIONS — ADR log

Every decision that shapes the architecture, recorded in one place. Status:
`accepted` / `proposed` / `open`. Revisit entries via a new ADR, never by editing an
old one (append-only).

---

## ADR-001 — Isolation engine: PRoot userspace (accepted)

**Context:** No root; Android app sandbox; must run glibc rootfs.
**Decision:** Use a PRoot-style ptrace userspace interposer as the v1 engine (Lite
mode). QEMU full virtualization is a separate, later backend (Full mode) only where
root/KVM is available.
**Consequences:** No kernel-level isolation; guest processes share Android's kernel
and network; `/proc` is partially emulated; correct for the "feels like a VM" goal
without root.

## ADR-002 — Which proot: C `proot` vs `proot-rs` (open → decide in Phase 1)

**Context:** Termux is migrating to proot-rs; C proot is battle-tested.
**Decision:** Benchmark both on a stock Pixel and a One UI device; ship whichever
wins on stability/speed, record it per instance in `config.json`. Both are scheduled
for Phase 1.

## ADR-003 — Desktop server: TigerVNC `Xvnc` in guest, RFB client in app (accepted)

**Context:** Must display a real X11 desktop inside the app with no external viewer.
**Decision:** Run `Xvnc` (X server + RFB server) inside the guest on loopback with a
per-boot password; render RFB with a bundled pure-Kotlin 3.8 client to a
Surface/Compose canvas. No hardware-accelerated X11 (software rendering); performance
comes from Tight/JPEG and resolution limits, not guest GPU.

## ADR-004 — RFB client: pure Kotlin, Apache-2.0 (accepted)

**Context:** Tight decoder can use zlib (JDK `Inflater`) and JPEG (`BitmapFactory`);
a JNI libjpeg-turbo path is possible.
**Decision:** Pure-Kotlin client; JNI decoder is a benchmarked fallback only. Keeps
the APK's compiled code licensed Apache-2.0 and avoids GPL linkage questions.

## ADR-005 — RootFS distribution: layers + signed manifest (accepted)

**Context:** Multiple distros, archs, and DE flavors; flaky mobile networks; must be
verifiable.
**Decision:** `base` + `desktop` tar.zst layers, content-addressed by sha256, with an
Ed25519-signed `manifest.json`; immutable releases; resumable downloads; atomic
install; content-addressed layer cache shared by instances.

## ADR-006 — Guest user: faked root, real app uid (accepted)

**Context:** Android's uid model, PRoot's `-0`.
**Decision:** Guest runs as the app uid; PRoot's `-0` only emulates UID 0 inside the
emulated filesystem. No setuid binaries, no capabilities, no host filesystem escape.

## ADR-007 — Storage: app-private internal storage only (accepted)

**Context:** FUSE `/sdcard` is slow; file manager visibility is a nice-to-have.
**Decision:** Instances in `filesDir/instances/<id>/`; SAF only for user-initiated
backup export/import and (later) optional file sharing. Internal storage path is
device-PRIVATE-by-design; the app shows "Open storage" intents for browsing.

## ADR-008 — Desktop flavor default: Openbox for v1 (accepted)

**Context:** Phones have 3–8 GB RAM; GNOME/KDE are out.
**Decision:** Ship three flavors (openbox, lxqt, xfce). Default = openbox (lowest
RAM, fastest cold boot). XFCE offered on the installer screen for users who want
more polish; they are three `desktop` layers over the same `base`.

## ADR-009 — Terminal: pty via JNI `openpty(3)` (accepted)

**Context:** Phase 1 MVP is a shell; Android lacks `/dev/ptmx` guarantees on some
devices.
**Decision:** Use `openpty(3)` through `libpvmnative.so`; render with an
Apache-2.0 terminal widget (jackpal `terminal-emulator`) or custom Compose renderer.
Fallback: `socketpair` + line-mode shell if `/dev/ptmx` is unavailable.

## ADR-010 — Update model: new instances, not in-place upgrades (accepted)

**Context:** Upgrading a rootfs in place risks breaking a running environment and is
hard to make atomic on Android.
**Decision:** v1 installs are versioned per instance; new versions create new
instances. In-place upgrade + data migration is a Phase 4 feature.

## ADR-011 — Distribution channel (proposed)

**Context:** Play policy for FGS "specialUse" + user-installable environments.
**Decision (proposed):** v1 ships as signed sideload APK via GitHub Releases;
Play Store submission investigated in parallel (specialUse FGS declaration +
disclosure). RootFS layers hosted on GitHub Releases (Range-supported) for v1.

## ADR-012 — Threading model (accepted)

**Context:** Long-running guest, foreground service, progress UI.
**Decision:** Single install-task executor, cancellable jobs, state persisted before
every transition; guest owned by `GuestProcess` (process group, SIGTERM→SIGKILL);
RFB reader thread + SurfaceView renderer; input from UI thread.

## ADR-013 — Jetifier disabled (accepted)

**Context:** `android.enableJetifier=true` came with the project template, but every
dependency Lenix uses is already AndroidX/Jetpack, Kotlin or plain Java, so nothing
needs the legacy support-library rewrite. Jetifier still ran a bytecode pass over
every jar in the graph, and that pass was not merely wasted: Jetifier's bundled ASM
cannot read class file major version 65 (Java 21), and jackson-core 2.16+ ships Java
21 classes under `META-INF/versions/21` (shaded FastDoubleParser; the jar is
`Multi-Release: true`). With Jetifier on, resolution of `debugCompileClasspath` died
in `JetifyTransform` — `IllegalArgumentException: Unsupported class file major
version 65` — before Kotlin was ever compiled.
**Decision:** Set `android.enableJetifier=false` and keep it off. If a future
dependency still needs jetification, upgrade or replace that dependency rather than
flipping the flag back.
**Consequences:** Configuration and dexing get faster (one less whole-graph bytecode
transform), and we drop a flag that AGP 9 removes outright. Multi-release jars such
as jackson-core now reach D8 untouched and Android resolves their base (Java 8)
entries, which is the only variant the runtime would ever load.

## ADR-014 — Instance persistence: per-instance `config.json`, no Room (accepted)

**Context:** Phase 2 needs instances to survive app restarts. `ARCHITECTURE.md` §7.3
already specifies a `config.json` instance record per instance directory, and ADR-012
requires state to be persisted on every transition. The obvious "just add Room"
path would pull in the Room runtime, KSP, and a schema-migration story for what is
currently a handful of tiny records with no relational queries.

**Decision:** Persist each `VmInstance` as
`filesDir/instances/<id>/config.json` via `data.JsonInstanceStore` (Jackson, the JSON
stack already shipped for manifests). Records are written atomically (temp file +
`Files.move(ATOMIC_MOVE)`), carry a `schemaVersion` gate (newer schema ⇒ record
skipped, never mangled), and are skipped — not deleted — when corrupt or when the id
does not match its directory. On manager construction, persisted transient states are
normalized for process death (`RUNNING/STARTING/STOPPING → READY`,
`DOWNLOADING/VERIFYING/EXTRACTING/INSTALLING → ERROR + INSTALL_INTERRUPTED`), and any
normalization is re-persisted. The last-selected instance is remembered in a one-line
`selected_instance` file. Room stays out until a real query need appears (e.g. install
event history).

**Consequences:** The store is plain-JVM unit-testable (constructor takes a `File`
root, not a `Context`); no new Gradle dependencies; the installer's own provenance
snapshot was renamed to `rootfs.json` so `config.json` unambiguously means the
instance record from §7.3. The per-step `state.json` staging detail from
`ROOTFS_SYSTEM.md` remains a Phase 3 concern layered on top of this record.

## ADR-015 — Resumable downloader: OkHttp + `.part`/`.etag` sidecars + content-addressed cache (accepted)

**Context:** Phase 3 needs the download step of `ROOTFS_SYSTEM.md` §2 to work on
flaky mobile networks and to survive process death. `ARCHITECTURE.md` already pins
"OkHttp on its own pool; `ResumableDownloader` writes to `cache/<sha>.part`".

**Decision:** `data.download.ResumableDownloader` (OkHttp 4, no whole-call timeout)
streams each layer into `filesDir/cache/layers/<sha256>.layer.part` and renames it
to `<sha256>.layer` only after the full `sha256` matches the manifest. A partial is
resumed with `Range: bytes=<n>-`; the ETag captured beside the `.part` is replayed
as `If-Range`, so a changed upstream restarts from zero instead of concatenating
mismatched bytes (the completion checksum is the second gate). Servers that ignore
Range (plain 200), reject it (416), or resume at a wrong offset make the attempt
restart cleanly; transient failures (I/O, short reads, 408/429/5xx) retry with
exponential backoff (max 3 attempts) always resuming from disk. Coroutine
cancellation is not an error: it leaves the `.part` in place — that is the resume
point for the next install — and always surfaces as `CancellationException`, never
a wrapped `VmError`. Explicit user cancel deletes in-flight `.part`s but keeps
completed layers (immutable, content-addressed, shared across instances).

The cache key is the bare sha256 with a fixed `.layer` suffix (not
`<sha>.tar.zst`): the digest is validated before it is used as a filename, and one
canonical name per digest means two URLs serving the same content share one cache
entry. Per-instance progress lives in `instances/<id>/state.json`
(`data.JsonInstallStateStore`, the checkpoint `ROOTFS_SYSTEM.md` §2 assigns to
Phase 3), refreshed at phase changes and at least every 5 s, cleared on commit.

v0.1 layer sourcing: no Lenix-owned layer host exists yet, so the bundled manifest
(`assets/rootfs/debian-bookworm-aarch64.json`) pins a real Debian bookworm arm64
rootfs published on GitHub Releases by `termux/proot-distro`
(`debian-bookworm-aarch64-pd-v4.7.0.tar.xz`, ~43 MB). Its sha256 is pinned in the
manifest — the same digest upstream verifies on every install — and the app
re-verifies it over the downloaded bytes before anything is staged, so a swapped
or corrupted asset can never pass. The layer is *downloaded at runtime*, not
vendored into the APK (the guest is Debian's own licensed content plus proot-distro
setup bits; attribution lives in `docs/ROOTFS_SYSTEM.md` §7). The manifest
signature field is a documented `unsigned:phase-4` placeholder until Ed25519
verification lands. `uncompressedBytes` for the borrowed layer is a conservative
estimate used only for the storage precheck. Real streaming extraction is Phase 5 —
until then verified layers are staged into the instance directory.

**Consequences:** A completed layer is downloaded at most once per device, an
interrupted install resumes at the exact byte it stopped, and tampered or truncated
transfers can never reach extraction. The installer is file-based (no `Context`)
and therefore fully JVM-testable against MockWebServer; OkHttp is the first new
runtime dependency since Jackson. Building and hosting Lenix's own layers (the
`rootfs.yml` builder — docker-export `debian:bookworm-slim`, xz, publish to this
repo's Releases, regenerate the manifest) is the next step once workflow-file
permissions allow it.

## ADR-016 — App settings: one `settings.json`, owned by the root view model (accepted)

**Context:** The Settings screen kept its three toggles in
`remember { mutableStateOf(...) }` — local UI state — so every value was lost on
navigation or process death ("settings not saving"). The obvious fixes are
`SharedPreferences`/DataStore, but the settings are three booleans with no
cross-process access and no observed types.

**Decision:** Persist a versioned `SettingsRecord` to `filesDir/settings.json` via
`data.JsonSettingsStore` — the same wire-format rules as the other JSON stores
(atomic temp-file + rename, unknown fields ignored, corrupt/newer-schema loads as
documented defaults, plain `File` constructor for JVM tests). One owner:
`HomeViewModel` loads the settings at construction, updates its state flow
optimistically, and serializes disk writes on the manager dispatcher. Screens stay
stateless and receive `(LenixSettings, onUpdate)` — the same
single-source-of-truth pattern as the instance list. The storage-care toggle is
wired to a real free-space precheck before installs (`StatFs`; required ≈ Σ
compressed + 1.2 × Σ uncompressed, `ROOTFS_SYSTEM.md` §4); the background-runtime
and desktop auto-start toggles are consumed by the Phase 6/7 features they name.

**Consequences:** Settings survive restarts by construction and are unit-testable
without Android. DataStore/SharedPreferences stay out until a real need appears
(observed types, cross-process access); migrating a 3-field JSON record then is
trivial.

---

*Open items from `ARCHITECTURE.md` §14 are tracked here as they resolve.*
