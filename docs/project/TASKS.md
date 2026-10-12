# Task state

| ID | Deliverable | State | Acceptance / evidence |
|---|---|---|---|
| P0.1 | Android build and offline toolchain preparation | Local acceptance passed | Full ARM64 release, locked dependencies, unique native names, hashes and 484 ELF audits |
| P0.2 | Independent C/C++/Python/JVM workers | Phone acceptance passed | Four actual languages, input/argv/EOF, stop and crash containment |
| P0.3 | Python, Java and native debugger adapters | Phone acceptance passed | Four real stdin/breakpoint/step-in/over/out/stack/locals/watch flows |
| P1 | Workspace, templates, autosave, editor/settings | Core phone acceptance passed | Chinese composing/emoji, 30 rapid resets, undo/fold/save/run, source/ZIP round trip |
| P2 | Semantic language services, completion and debug UI | Phone acceptance passed | clangd/Jedi/JDT cross-file symbols and actual DAP integration |
| P3 | Resource/fault/Android compatibility | Local fault suite passed; matrix pending | 20/20 overall; timeout, blocked input, flood, SIGSEGV, EOF and stop; broader devices pending |
| P4 | APK, source and documentation | Local and GitHub delivery complete; broad compatibility pending | v0.2.2 Release includes signed APK, application/corresponding sources, update manifest and hashes; final phone acceptance passed |

Available phone: Xiaomi Mi 9 SE, Android15 ARM64, 4KiB. Formal P0/P4 broad compatibility acceptance remains open for 16KiB live execution and the Android10–17/vendor matrix. Local APK delivery must state these limits.

## GitHub / online update increment (2026-10-12)

| ID | Deliverable | State | Acceptance / evidence |
|---|---|---|---|
| U1 | GitHub release updater and settings | Accepted on phone | HTTPS check, system download, APK verification and normal installer; 6/6 unit tests and 2/2 security cases in final regression |
| U2 | Updated release and connected-phone validation | Accepted on phone | Actual 429,738,676-byte GitHub download, system upgrade from bridge4 to public5, project/settings preservation, 22/22 final regression and post-upgrade latest check |
| U3 | Public GodBook/mobile-code repository and release | Published | https://github.com/GodBook/mobile-code/releases/tag/v0.2.2; all five assets verified by size/SHA256; application and corresponding source archives included |

Evidence: [0.2.2 acceptance report](../../verification/releases/v0.2.2/acceptance.json), adjacent raw instrumentation logs, and [verification notes](../VERIFICATION.md). Phone remains on 0.2.2/versionCode5; temporary screen-activity helper stopped. Earlier failed locked/Doze logs are retained locally and excluded from passing counts.
