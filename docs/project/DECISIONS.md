# Decision log

The eight ADRs in the approved design remain authoritative: native offline tools; Compose/Sora; C/C++ shared-library launcher; OpenJDK 21; embedded CPython; LSP/DAP; internal workspace with SAF; complete ARM64 APK.

## D001 — 2026-10-12 — development ABI

Production stays ARM64-only. A separately selected x86_64 development build may be used with the locally installed emulator, to test the same app/process/protocol paths. Emulator results never substitute for ARM64 or 16KiB device acceptance.

## D002 — 2026-10-12 — validation boundary

No physical device is currently attached. Continue all feasible implementation and host/emulator verification. Record unexecuted target-device checks explicitly instead of weakening the P0 gate or describing a build-only artifact as fully validated.

Update: the user subsequently connected/authorized a Xiaomi Mi 9 SE. ARM64 Android15/4KiB phone validation is now available and passed; 16KiB execution and other OS/vendor environments remain pending.

## D003 — 2026-10-12 — Android JVM logical layout

The Android OpenJDK port derives its resource home through dladdr and skips the upstream alternative-JVM JAVA_HOME fallback. The packaged runner adapts only libjvm's dladdr filename to the logical installed-resource alias. Executable pages still map from the APK native directory; JVM/JDI run in independent processes. Phone run and private-FD debugging verify this path.

## D004 — 2026-10-12 — debugger console

Upstream lldb-dap did not reliably preserve the proposed stdio FD paths. A dedicated native-debug launcher mode opens private per-session FIFOs, confirms actual connection before input, and strips its control arguments before calling user main. DAP and debug control stay separate. Real stdin/EOF and all three step operations passed on the phone.

## D005 — 2026-10-12 — public repository and app updates

The user explicitly requests a public GitHub repository and online app updates. This supersedes the previous no-INTERNET constraint for the updater. GodBook/mobile-code will host source and stable releases. A small HTTPS update.json provides versionCode, exact versioned APK URL, ABI/minSdk, size, SHA256 and notes. Startup checking is optional and limited to once daily; downloading and installing require user actions. Android DownloadManager handles durable background transfers, then a private FileProvider hands a size/hash/package/version/ABI/signature-verified APK to the system installer. Existing signing identity is retained so installed projects survive an in-place upgrade. No GitHub credential is embedded in the application.
