# Current checkpoint

Updated: 2026-10-12, Asia/Shanghai.

## Outcome

The four-language IDE 0.1.0 baseline below passed 20 device/UI integration tests and was delivered locally. Public repository https://github.com/GodBook/mobile-code now contains the source. Releases 0.2.0 and 0.2.1 are published, with every uploaded asset size/SHA256 verified against GitHub's digest before publication. Latest/update.json fetched without authentication identifies 0.2.1. Six unit tests and two phone security tests passed. The phone has 0.2.0 installed; the final real download/install of 0.2.1 is still being investigated because Android Java HTTPS requests time out even though phone-system curl reaches both GitHub and its API. Do not claim end-to-end update acceptance yet. Broader compatibility limits below still apply.

Current increment: 0.2.0 / versionCode 3 is installed; 0.2.1 / versionCode 4 is latest. APK 0.2.1: 429,738,092 bytes, SHA256 9d7187b05b64ef1f6342b2a20340953cd3a9d5fae4920afddcf7ce6d10087bc2. Tag points to d3f4012; its five assets include the APK, app source ZIP, 16-recipe/23-input corresponding source ZIP, update.json and SHA256SUMS. INTERNET and REQUEST_INSTALL_PACKAGES are explicitly authorized; editing/build/run/debug continue locally.

Phone update diagnostic: the phone resolves/reaches GitHub, but Mobile Code UID 10272 had a LineageOS REJECT_ALL (262144) network policy. Manifest INTERNET is granted. Under the user's online-update authorization, only that UID's REJECT_ALL bit was removed via the verified INetworkPolicyManager.removeUidPolicy API; readback is policy 0. No root was used. The PIN lock remains, and deep Doze interrupted timing checks in the 0.2.0 regression (partial output observation, preset-input timeout and >2s stop). Preserve those failed logs; do not count this run as passed. Wake/unlock is needed for repeat foreground acceptance.

Update: the same Mi 9 SE reconnected under a different wireless IP and is now unlocked. Find the active grus device with adb devices; do not target old offline entries. The 0.2.0 full regression had 17/22 pass under PIN/Doze. Repeating its four timing/output failures while keeping the phone awake passed 4/4 in 38.471s, so 21 distinct cases now have pass evidence. Editor UI acceptance remains to rerun while unlocked. Reports: verification/local/update-0.2.0-regression.json (failed run) and update-0.2.0-timing-retest.json (passed retest).

Network follow-up: explicit NetworkDiagnosticsTest passed on the phone, with the Java GitHub redirect chain and API each returning 200. Global proxy settings are null. Forced-refresh client checks were unreliable. The pending 0.2.2 client follows normal response cache policy and retries one transient I/O failure while retaining the 60s bound, HTTPS allowlist and certificate checks. Its local versionCode4/0.2.2-preview bridge is installed; PublishedUpdateTest successfully fetched the public versionCode4 manifest in ~3s (.downloads/github-fixed-client-check.log, github-fixed-client-logcat.txt). Do not replace the public 0.2.0/0.2.1 APKs or tags.

0.2.2 final APK is prepared: artifacts/update-publication/MobileCode-0.2.2-arm64-v8a.apk, 429,738,676 bytes, SHA256 61acbfc1d73789876d1e218733244f45a1921eb45e047656abb92e8d8a8613d3. Six unit tests, build and 484-file audit pass. The corresponding source archive is prepared. Next: commit/push reviewed 0.2.2 source, prepare_release.py, publish its five verified release assets, run PublishedUpdateTest on the versionCode4 bridge to really download versionCode5, then use the normal system install flow and verify UpgradeDataTest/foreground regression with the final test APK.

The versionCode4 full regression took 829.239s under renewed PIN/Doze and passed 19/22; failures were stop timing, timeout output and editor Compose UI absence. It is not a passing run. The user was asked to unlock again. A bounded activity helper (session 84612) taps the harmless status-bar corner while testing to prevent renewed inactivity lock; stop it by creating .downloads/ota-v022-device-activity.finished (or wait its 20-minute bound). It never enters a PIN or changes lock configuration. Current build session 28033 compiles the added UpgradeDataTest marker fixture for versionCode4 and records pre-upgrade project/settings data. Check its result before replacing the test APK. Rebuild only the final versionCode5 Android test APK afterward; keep the already audited final APK unchanged.

## Workspace and environment

- Workspace: D:\CHATGPT\mobile code; initially empty, no Git repository.
- JDK17 D:\dev\jdk-17; Android SDK37.0 D:\dev\android-sdk; NDK28.2.13676358; CMake3.22.1; Gradle9.4.1 in .toolchains/.
- WSL Ubuntu-24.04: /opt/mobilecode-work points here. Persistent CPython builds and package extraction are in /root/.cache/mobilecode/.
- User explicitly authorized testing the connected Xiaomi Mi 9 SE: LineageOS Android15/API35, ARM64, 4096-byte pages, ~6GB RAM. Query adb devices to find its current wireless serial; ignore unrelated offline entries. emulator-5580 is development x86_64.

## Verified artifact

- app/build/outputs/apk/release/app-release.apk: 429,662,908 bytes.
- SHA256: 89253f5cf325560c336ef9a5b25a04e35a53dd3cc8d6bdc57d6910bab576259e.
- Installed phone APK SHA256 matches. minSdk29/target37, ARM64-only, no INTERNET or broad storage access; app debuggable=false.
- Signed using the local Android development certificate. This is a locally installable prototype, not a public release signed with an owner-managed production key.

## Evidence

- verification/local/phone-runtime.json and phone-acceptance.log: 20/20 passed in 117.815s, against the exact APK SHA256 above.
- Device coverage: four real languages run; stdin during debugging; breakpoint, step-in/over/out, stack, locals, safe watches; cross-file clangd/Jedi/JDT completions; paused execution budget; Chinese composing/emoji; 30 rapid text resets; undo/redo/fold/autosave/click-run; immutable snapshots, source/ZIP export and import; traversal rejection; timeout, blocked preset input, native SIGSEGV, 12MiB stdout/10MiB log cap, EOF/idempotence and <2s stop.
- verification/local/host-runtime.json: 5/5 passed, including native FIFO console/Unicode argv and actual private-FD Java/Python debug integration.
- verification/local/apk-audit.json: all 484 packaged native files passed ABI, 16KiB LOAD alignment and expected SHA256 checks; native names are unique; offline archive hash and required assets passed.
- verification/local/apk-signing.txt, apk-manifest*.txt, installed-apk-sha256.txt: signing, permissions and installed-version evidence.
- No run/debug workers remained in the phone process list after acceptance. Test projects and session data were preserved.

## Important implementation decisions

- C/C++ compile PIC shared modules and call standard main through the installed native launcher. Debug console FIFOs are opened directly by native-debug; readiness is confirmed by a real marker before sending input. Control arguments are stripped before user argv.
- Android OpenJDK uses the runner's narrow libjvm dladdr layout adapter. Java continued events are emitted before VM resume to avoid overwriting an immediate step stop.
- CPython3.13 stdout/stderr explicitly use console pipes; LLDB's Python3.14 is isolated. Same-named libpython3.so stable-ABI shims get unique installed names and context-correct NEEDED mappings.
- Canonical project paths handle /data/data versus /data/user/0. Syntax results/folds/brackets are generation/line checked on the UI thread; released editors reject navigation.
- Cancellation marks the session and kills workers before joining blocked readers. Reused foreground services acknowledge every startForegroundService request and use start IDs when stopping.
- Runtime package versions are frozen under tools/locks; Gradle dependency locks, Gradle/CPython/JDT archive SHA256, notices and component license assets are saved.

## Local delivery

- artifacts/MobileCode-0.1.0-arm64-v8a.apk is the verified, installed release APK identified above.
- artifacts/MobileCode-0.1.0-source.zip contains application sources, build files and locks, toolchain preparation scripts, design/build documents and verification evidence. Generated toolchains, caches and signing keys are excluded.
- artifacts/SHA256SUMS.txt and artifacts/delivery.json record file hashes, the tested device and validation limits. tools/package_delivery.py checks that the source APK matches the passing phone and APK-audit reports before packaging.
- These final project records are included in the source ZIP. No runtime source changed after phone acceptance. Do not rerun or broaden completed checks unless source changes or a concrete failure warrants it.

The next development work, if requested, is the unverified compatibility/performance matrix and public-distribution materials below, not a repeat of the completed local handoff.

## Explicit limits

Actual 16KiB execution, the Android10-17/vendor matrix, P95 performance and long-running background/low-memory behavior are not verified. Phone tests retained Wi-Fi for wireless ADB; the APK has no networking permission. SAF stream behavior is verified; third-party provider UI matrices remain pending.

Formal broad P0/P4 compatibility acceptance stays open for these environments. The user's new GitHub request authorizes public source/release publication and online updates. Corresponding GPL/LGPL source materials are now collected separately; see docs/SOURCE_MATERIALS.md. Existing signing identity must be retained for upgrades. Root, reboot and unrelated system-setting changes remain outside scope.

Failed exploratory logs remain in .downloads; authoritative final evidence is in verification/local. Runtime source is frozen at the verified version.
