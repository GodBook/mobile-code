# Mobile Code v1 — authoritative requirements

Source: user-approved design, 2026-10-12 (Asia/Shanghai).

## Product

A native Android IDE for C17, C++17, CPython 3.13 and Java 17 source/API compatibility, with an Android OpenJDK 21 runtime. Android 10+ (minSdk 29), ARM64 production APK, compile/targetSdk 37. All four languages, standard libraries, compilers, debuggers and semantic completion engines are bundled in one APK and work offline. No root, ADB, Termux installation, cloud execution or broad filesystem permission is required by the user.

## Authorized addition: public GitHub and online updates

User request, 2026-10-12: create a public GitHub repository and implement online software updates. Use the authenticated GodBook account and a new mobile-code repository. This authorizes publishing application source and release materials, and adding INTERNET and REQUEST_INSTALL_PACKAGES. Core editing/build/run/debug remain local and offline.

- Stable releases provide a versioned APK and update.json. Version comparison uses monotonically increasing Android versionCode.
- Settings provide manual checking and a switch for startup checks, limited to once per day. Checking never automatically downloads or installs an APK.
- Show release notes, file size, download progress, cancellation/retry and meaningful network errors. Android DownloadManager persists downloads across process death.
- Require HTTPS, the configured GitHub repository, bounded metadata, APK SHA256/size, application ID, compatible SDK/ABI, newer version and the same signing certificate. Reject invalid or downgraded packages.
- Save edits before installation and block installation during an active execution/debug session. Android's permission and installer UI obtain the user's installation confirmation.
- Keep keys, credentials, generated toolchains and local device logs out of Git. Preserve the installed app's signing identity for in-place upgrades.

## Required behavior

- Project home, single/multiple-file templates, file tree/tabs, internal projects, SAF import/export.
- Sora editor, dark/light themes, line numbers, incremental syntax highlighting, folding, bracket pairing, indentation, search/replace, undo/redo, Chinese IME, keyboard accessory row.
- Semantic/project completion and signature help: clangd (C/C++), Jedi (Python), JDT LS (Java). Immediate lexical/snippet fallback while services initialize; versioned responses must not overwrite newer edits.
- Save after 500ms idle and before switching/background/build. Atomic replacement; failed writes keep dirty buffers. Room metadata, DataStore settings. Files are the source of truth.
- Immutable build/run snapshots. C/C++ shared libraries loaded by a packaged native launcher. Python embedded in its own native process. Java compiled with --release 17 -g and run in an independent JVM.
- Interactive/preloaded stdin, explicit EOF, separate stdout/stderr/build logs, diagnostics with source navigation, argv arrays, exit/time/termination status.
- Real breakpoints, continue, step in/over/out, pause, stack, locals, simple read-only watches, restart and stop in all languages. Python v1 debugging focuses on the main thread. Debugged source is read-only.
- LSP/DAP stdio, dedicated output pipes, private Unix-FD native/JDWP debugging. Release application debuggable=false. No TCP listener is needed.
- One execution session. Foreground service with stop notification; Android 14+ specialUse. Finite language-server restarts and truthful state/events.
- Defaults: 120s build, 60s run (configurable 1–600s including stdin wait), Java heap 256MiB, worker RSS initial ceiling 512MiB, console 10MiB, output files 100MiB. Paused debugging does not consume execution timeout.
- 4KiB/16KiB native ELF compatibility, read-only loaded code, Android 10–17 and low-memory/background behavior coverage.

## Deferred

Full CMake/Gradle/Maven/pip dependency management, Android/GUI builds, conditions/data breakpoints, arbitrary evaluation or variable mutation, Python child-thread/async-specific debugging, Git/cloud/remote development, 32-bit release devices.

## Architecture and acceptance

Compose/Sora UI → Workspace / LanguageService / SessionSupervisor. Tools are packaged Android/Bionic executables/shared libraries plus offline assets. Session workers are independent processes under the app UID; this is crash/resource isolation, not a hostile-code sandbox.

P0 must demonstrate real run/breakpoint/step/locals for all languages in a debuggable=false ARM64 build and 16KiB compatibility. P1 supplies editor/projects; P2 completion/debug UI; P3 fault/compatibility validation; P4 signed APK and reproducible build/license materials. Missing device evidence is recorded as unverified, never passed.

Performance targets on ARM64/6GiB: key-to-render P95≤50ms, warm completion≤300ms C/C++/Python and ≤500ms Java, 1MiB open≤1.5s, hello run≤5s C/C++/Python or ≤8s Java, stop≤2s.
