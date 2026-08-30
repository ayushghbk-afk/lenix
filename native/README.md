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

`proot` / `tini` / `busybox` are not vendored in this tree yet. Drop arm64-v8a
builds into `app/src/main/assets/native/arm64-v8a/` and [NativeSetup] copies
them to `filesDir/native/<abi>/` on first launch. Until those files exist, START
fails as `NATIVE_ENGINE_FAILED` instead of pretending a guest is running.

Phase 6: PRoot argv + PTY/pipe terminal + JNI stubs.
Phase 7: Openbox + Xvnc launch line + loopback RFB client (Raw encoding).
