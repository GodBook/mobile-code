# Mobile Code

Android local/offline IDE. Read `docs/project/SPEC.md`, then `STATE.md` and `TASKS.md` in that directory before continuing work.

- User-approved requirements are authoritative. Do not silently substitute cloud execution, simulated debugging, or a keyword-only completion engine for the planned capabilities.
- Keep release ARM64-only, minSdk 29, target/compileSdk 37; development emulators may use a separate x86_64 build.
- All runtime programs and resources must be packaged offline. INTERNET and REQUEST_INSTALL_PACKAGES are authorized by the user's 2026-10-12 GitHub/online-update request; only the updater needs network access. No broad storage permissions.
- Preserve projects on save/build failures. Run immutable source snapshots in separate processes.
- Update STATE.md with actual checks and unresolved limitations. A successful APK build does not prove device/runtime compatibility.
- Use apply_patch for source edits. Do not put credentials or signing keys in source control.

Build and verification instructions live in README.md. Native/runtime preparation lives in tools/.
