# Lenix Native

Native engine source tree (PRoot, BusyBox, PTY helper, extractor) will live here.

No native code is vendored yet, and Phases 0–5 never needed any: the installer, its
signature check (`ed25519` via the JDK) and RootFS extraction (`commons-compress` +
`org.tukaani:xz`) all run in the JVM — see ADR-017/ADR-018. The tree grows with the
phases in `README.md` that do need it:

- Phase 6 — `launcher/` (PRoot), `pty/` (PTY bridge), `extractor/` (libarchive + zstd, the
  fast path and the only reader for `tar.zst` layers), behind `NativeBridge`
- Phase 7 — desktop/VNC plumbing, if any turns out to need native helpers

`docs/NATIVE_BINARIES.md` tracks each binary, its provenance and license; until a `.so` is
built, the app degrades with `NATIVE_ENGINE_FAILED` / `UNSUPPORTED_COMPRESSION` rather than
pretending to work.
