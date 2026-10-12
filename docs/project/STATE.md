# Current checkpoint

Updated: 2026-10-12, Asia/Shanghai.

## Current outcome

Active increment: user-requested reference-image syntax colors, explicit light/dark themes, memory/input improvements and a tested GitHub release. Worktree started clean on main at 4f4f309. Version 0.2.3 / versionCode6 is in preparation; it is not yet published or device-accepted.

Implemented: AST token roles and rainbow brackets, complete Sora palettes, explicit theme choices, closed-tab/snapshot editor disposal, linear token-offset conversion, coalesced and serialized LSP changes, position-only settings writes, language-service recovery and event-driven console publication. First appearance run passed data retention, theme/undo/recreation, semantic recovery and four-grammar roles; rapid tab closure exposed a Material3 indicator index crash, now fixed and awaiting retest. Six unit tests and an interim 484-file APK audit passed; final artifact verification is pending. Before-update fixture: .downloads/visual-0.2.3-data-before.log. The old 0.2.2 1MiB typing benchmark was killed by Android LOW_MEMORY (3.7GB RSS reported in exit-info), with queued JSON writes and GC pressure; its failure is retained. A 64KiB baseline passed in 9.758s, P95 callback latency23.119ms and app PSS102044KiB; verification/local/visual-0.2.2-performance-64k.json. Final new-version performance has not yet been measured. First build invocation used a nonexistent release unit-test task; corrected to testP0UnitTest.

The published baseline below remains available until the new artifact passes verification.

Public repository: https://github.com/GodBook/mobile-code . Release https://github.com/GodBook/mobile-code/releases/tag/v0.2.2 is published and latest. All five assets were checked against their local sizes and GitHub SHA256 digests before publication. Anonymous latest/download/update.json returns versionCode5 and the expected APK hash.

The connected Xiaomi Mi 9 SE completed a real online upgrade from the local versionCode4 / 0.2.2-preview bridge to the published versionCode5 / 0.2.2 APK. PublishedUpdateTest fetched the public manifest, downloaded the actual 429,738,676-byte APK through Android DownloadManager and passed APK verification in 69.747s. Installation used the application's install-update action, Android's source permission screen and normal package-installer confirmation. The installed base.apk SHA256 matches the published APK exactly. No adb install replaced the application during this upgrade.

UpgradeDataTest.verifyAfterSystemUpdate passed 1/1 in 0.476s: all recorded project IDs, representative Chinese/emoji source hash, breakpoint and editor settings survived. The final APK passed the complete 22-case foreground regression in 119.706s. A further real GitHub check passed in 3.29s with installed=5/offered=5 and CURRENT state. The separate test APK was built for this final application version. Published production assets remain unchanged.

## Frozen release artifact

- Version 0.2.2 / versionCode5; source tag v0.2.2 points to 3dfa390.
- APK: artifacts/update-publication/MobileCode-0.2.2-arm64-v8a.apk, 429,738,676 bytes.
- APK SHA256: 61acbfc1d73789876d1e218733244f45a1921eb45e047656abb92e8d8a8613d3.
- Certificate SHA256: b579d64db9b1ca5ab84084823dfcae5c625de4bcd6dd38fe4bdcfc1df5fa370a. Original signing identity retained; private backup is outside the repository.
- ARM64 only, minSdk29, compile/targetSdk37, application debuggable=false.
- Six unit tests and 484/484 native-file checks passed; verification/local/update-0.2.2-apk-audit.json.
- Release includes APK, application source ZIP, corresponding-source ZIP, update.json and SHA256SUMS.txt. Corresponding materials cover 16 recipes and 23 upstream inputs/patches; docs/SOURCE_MATERIALS.md.
- Historical 0.2.0/0.2.1 releases and tags are preserved.

## Device evidence and handoff

- Locate the active lineage_grus / Mi 9 SE with adb devices; wireless addresses change. Ignore offline entries and the unrelated x86_64 emulator.
- Before/after upgrade fixture: .downloads/update-data-before.log and update-0.2.2-data-after.log. Do not delete the private update-acceptance.json or test projects.
- Real download: .downloads/update-0.2.2-real-download.log. Installed hash: .downloads/update-0.2.2-installed-sha256.txt. Update/installer screenshots remain in .downloads.
- Public report and five raw instrumentation logs: verification/releases/v0.2.2/acceptance.json and adjacent *.log files. Local reports generated with tools/summarize_android_tests.py: verification/local/update-0.2.2-{final-regression,data-after,real-download}.json.
- Final regression: .downloads/update-0.2.2-final-regression.log; 22/22, classes OfflineIdeTest,WorkbenchTest,UpdateIntegrationTest. Includes four-language run/debug/project completion, editor, resource faults and update security.
- Bridge foreground retest passed 3/3 after unlocking: .downloads/update-unlocked-retest.log. Earlier locked runs passed only 17/22 (0.2.0) and 19/22 (bridge); retain failures and do not call those runs passed.
- Temporary screen-activity helper was stopped through .downloads/ota-v022-unlocked-activity.finished; its session confirmed termination. It never entered a PIN or changed lock configuration.
- Process-list inspection after the regression showed no run/debug workers. Application reopened for the user. Projects, acceptance fixtures and signing materials are preserved.
- Authorized repository/update increment is complete. Broader device/performance work requires the environments listed below; do not repeat already passing acceptance without a relevant change or failure.

## Environment and authorization

- Workspace: D:\CHATGPT\mobile code; JDK17 D:\dev\jdk-17; SDK D:\dev\android-sdk; NDK28.2.13676358; CMake3.22.1; Gradle9.4.1 in .toolchains/.
- WSL Ubuntu-24.04: /opt/mobilecode-work points here. Runtime builds/package extraction: /root/.cache/mobilecode/.
- User explicitly authorized this connected phone, public GitHub repository/releases and online updates. INTERNET and REQUEST_INSTALL_PACKAGES are authorized additions; core editing/build/run/debug remain local/offline.
- Phone: Xiaomi Mi 9 SE, LineageOS Android15/API35, ARM64, 4096-byte pages, approximately 6GB RAM. Only Mobile Code's app-specific REJECT_ALL policy was removed under the update authorization; readback policy0. No root.
- Keys, tokens, device logs and generated toolchains are excluded from Git. Public visibility does not assign a new general license to application-owned source.

## Previous accepted IDE baseline

0.1.0 passed 20/20 phone/UI tests in 117.815s and 5/5 host tests. APK SHA256: 89253f5cf325560c336ef9a5b25a04e35a53dd3cc8d6bdc57d6910bab576259e. Historical reports: verification/local/phone-runtime.json, phone-acceptance.log, host-runtime.json and apk-audit.json. Local delivery is preserved in artifacts/; docs/VERIFICATION.md.

Coverage: four real local languages; stdin/argv/EOF; source breakpoints, step-in/over/out, stacks, locals and safe watches; cross-file clangd/Jedi/JDT completion; Chinese IME/emoji; rapid edits, undo/fold/autosave/click-run; immutable snapshots, ZIP/source import/export, traversal rejection, timeouts, crash isolation and output limits.

Runtime decisions: C/C++ PIC modules load in the installed launcher; native debugging uses console FIFOs and real readiness markers. CPython3.13 and LLDB Python3.14 are isolated. Java uses the narrow libjvm layout adapter and private-FD transport; continued events precede resume. Canonical paths handle /data/data versus /data/user/0. Editor parse/fold results are generation-checked. Cancellation kills workers before joining pipe readers.

## Explicit limits

Actual 16KiB device execution, Android10–17/vendor coverage, P95 performance and long-running background/low-memory behavior remain unverified. This phone has 4KiB pages. Tests keep Wi-Fi for wireless ADB; offline-regression classes do not require updater traffic. SAF stream round trips are covered; third-party provider UI matrices remain pending.

Formal broad P0/P4 compatibility acceptance remains open for those environments. The APK retains the original development certificate for compatible updates. Root, reboot, PIN bypass and unrelated system changes remain outside scope.
