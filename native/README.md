# Lenix Native

Native engine source tree (PRoot, BusyBox, PTY helper, extractor) will live here.

Phase 1 intentionally does not vendor native code — the app UI and state machine
must build green before the native tree grows. Follow `scripts/` helpers and the
phase breakdown in `docs/DECISIONS.md`:
- Phase 1: PRoot
- Phase 2: Terminal (PTY)
- Phase 3: VNC
- Phase 4: Desktop environment
