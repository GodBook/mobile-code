# Task state

| ID | Deliverable | State | Acceptance / evidence |
|---|---|---|---|
| P0.1 | Android build and offline toolchain preparation | Local acceptance passed | Full ARM64 release, locked dependencies, unique native names, hashes and 484 ELF audits |
| P0.2 | Independent C/C++/Python/JVM workers | Phone acceptance passed | Four actual languages, input/argv/EOF, stop and crash containment |
| P0.3 | Python, Java and native debugger adapters | Phone acceptance passed | Four real stdin/breakpoint/step-in/over/out/stack/locals/watch flows |
| P1 | Workspace, templates, autosave, editor/settings | Core phone acceptance passed | Chinese composing/emoji, 30 rapid resets, undo/fold/save/run, source/ZIP round trip |
| P2 | Semantic language services, completion and debug UI | Phone acceptance passed | clangd/Jedi/JDT cross-file symbols and actual DAP integration |
| P3 | Resource/fault/Android compatibility | Local fault suite passed; matrix pending | 20/20 overall; timeout, blocked input, flood, SIGSEGV, EOF and stop; broader devices pending |
| P4 | Local APK, source and documentation | Local delivery complete; public release pending | Verified APK, application source ZIP, SHA256SUMS and delivery.json in artifacts/; APK installed/tested/audited; public release materials remain separate |

Available phone: Xiaomi Mi 9 SE, Android15 ARM64, 4KiB. Formal P0/P4 broad compatibility acceptance remains open for 16KiB live execution and the Android10–17/vendor matrix. Local APK delivery must state these limits.

## GitHub / online update increment (2026-10-12)

| ID | Deliverable | State | Acceptance / evidence |
|---|---|---|---|
| U1 | GitHub release updater and settings | Implemented | Real HTTPS manifest, persistent download, hash/package/version/signature validation, system installer; 6 unit + 2 device security checks passed |
| U2 | Updated release and connected-phone validation | In progress | Improved 0.2.2 bridge installed; real manifest check passes. PIN/Doze prevents a clean full foreground regression; actual APK download/install and data-preservation checks next |
| U3 | Public GodBook/mobile-code repository and release | 0.2.0/0.2.1 published; 0.2.2 prepared | Sanitized source pushed; five uploaded assets per release verified by GitHub digest; 0.2.2 APK/source materials built and audited, awaiting publication |
