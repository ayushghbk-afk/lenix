# Lenix Native

Host-side engine (PRoot, BusyBox, PTY helper, extractor). GPL binaries are
**executed**, never linked (ADR-001). `libpvmnative.so` is Apache-2.0 JNI for
`openpty` and process-group kill.

## Layout

```
native/
├── CMakeLists.txt      # libpvmnative (pty + launcher)
├── pty/pty.c
├── launcher/launcher.c
└── README.md
```

## Engine binaries (proot / loader / tini / busybox / libtalloc…)

Drop arm64-v8a builds into `app/src/main/resources/lib/arm64-v8a/` (not `jniLibs/`:
Android Studio only packages `*.so` from there; `resources/lib/<abi>/` is the
documented wrap.sh-style route into the APK's `lib/<abi>/`) and `scripts/fetch-engine.sh`
can fetch+bundle the official Termux PRoot package for you:

```bash
./scripts/fetch-engine.sh
```

The engine is **not** copied to `filesDir/native/` at runtime: Android 10+ SELinux
denies `execve` of app-data files (`app_data_file` lacks `execute_no_trans`), so a
filesDir copy fails with `error=13, Permission denied` even at mode 0700. Shipping the
binaries as native library payloads (`resources/lib/<abi>/` → `/data/app/<pkg>/lib/<abi>/`,
SELinux `apk_data_file` with `x_file_perms`) keeps exec legal; PRoot's static `loader`
must ship there too because `$PROOT_LOADER` is pinned to it. See docs/DECISIONS.md
ADR-021.

Required payload for `arm64-v8a` (next to any libs you already ship):

```
app/src/main/resources/lib/arm64-v8a/
├── proot                  # bionic-linked PRoot (termux/proot fork)
├── loader                 # PRoot static runtime loader
├── libtalloc.so.2         # PRoot bionic dependency
├── libandroid-shmem.so    # PRoot bionic dependency
├── tini                   # optional, static (desktop mode)
└── busybox                # optional, bionic
```

Until those files exist, START fails as `NATIVE_ENGINE_FAILED` with an actionable
message instead of pretending a guest is running.

Phase 6: PRoot argv + PTY/pipe terminal + JNI stubs.
Phase 7: Openbox + Xvnc launch line + loopback RFB client (Raw encoding).
