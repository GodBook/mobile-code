# Current checkpoint

Updated: 2026-10-13, Asia/Shanghai.

## Current outcome

0.2.3 / versionCode6 is published and latest: https://github.com/GodBook/mobile-code/releases/tag/v0.2.3 . All five uploaded assets were verified against local sizes and SHA256 before publication on2026-10-13T04:31:35Z. Anonymous latest/download/update.json returns versionCode6 and the frozen APK hash. The phone's actual GitHub check passed1/1 in3.293s with installed=6/offered=6 and CURRENT state. This increment is complete; no requested application or release work remains.

Application implementation:21c7434. Instrumentation stress coverage:324e9a9. Source tag v0.2.3 points to91de636. Workspace:D:\CHATGPT\mobile code, branch main; remote https://github.com/GodBook/mobile-code.

## Frozen artifact and checks

- APK:app/build/outputs/apk/release/app-release.apk,429758440bytes.
- SHA256:6cbb4bab39b7f0bb31a0d35d3ace8002a8830f7c4b8a366d22f24da1794de67a. Installed base.apk hash is identical.
- Certificate SHA256:b579d64db9b1ca5ab84084823dfcae5c625de4bcd6dd38fe4bdcfc1df5fa370a. Original signing identity retained; private backup remains outside Git.
- ARM64-only, minSdk29, compile/targetSdk37, debuggable=false. Six unit tests and all1460 audit checks across484 native files passed.
- Production APK foreground regression:29/29 passed in126.836s; .downloads/visual-0.2.3-regression-shipping.log and verification/local/visual-0.2.3-regression-shipping.json.
- Covers four real local run/debug/semantic-completion engines, Chinese IME/emoji, save/undo/fold, theme/undo/recreation, closed tabs/snapshots, memory recovery, output/resource faults, updater security and both large-file stress cases.
- Final data preservation:1/1 in0.614s, .downloads/visual-0.2.3-data-after-shipping.log. Original project IDs, Chinese/emoji source hash, breakpoints and appearance settings survived the adb install -r upgrade. No6 OTA installer flow is claimed.
- 1MiB editor, visible1080x939px viewport:open431.855ms;80 insertions, frame-callback P95 30.795ms; post-close app PSS176101KiB.
- 1MiB lexical fallback with semantic service unavailable:80ms, expected identifier returned, PSS183591KiB. Visible12MiB console flood:frame-callback4.623ms/PSS150050KiB. All are instrumented app-only samples, not a full physical-IME or long-running performance matrix.

## Changes and failure evidence

AST syntax roles/rainbow brackets and complete light/dark palettes follow the user's reference. Settings have explicit theme choices; cursor saves cannot overwrite appearance settings. Closed editor/snapshot resources are released. Token mapping is linear; console publication is event-driven; language changes are coalesced/serialized and recover after memory release.

Startup restoration now completes before new project/home actions. The phone rotated into landscape during validation; a fixed-height console had reduced the editor to zero height. Responsive rows and an independently expanded compact console preserve the editing viewport. Actual landscape Workbench checks passed3/3; final full regression ran in portrait.

The old0.2.2 and intermediate visible1MiB probes hit LOW_MEMORY at3.7/3.9GB RSS. Kotlin Regex.findAll rebuilt Android native matchers per token; fallback now reuses one Matcher, checks cancellation/version, and skips comments while asynchronous styles initialize. Earlier26/27,27/28, asleep and zero-height runs remain local and are excluded from passing counts. They must not be cited as successful acceptance.

## Publication and handoff

Release ID410733833 includes the APK, application source ZIP, corresponding-source ZIP (16 recipes/23 materials), update.json and SHA256SUMS.txt. Evidence is in verification/releases/v0.2.3/acceptance.json, publication fields and adjacent raw test/measurement files. Versioned delivery is preserved in artifacts/update-publication. Do not rebuild or replace these published assets under the same version/tag.

Native Git HTTPS hit TLS failures. GitHub's Git data API uploaded and verified48 blobs/eight original commit identities, then advanced main without force and created the identical annotated tag. Original history was preserved. The ignored .downloads/publish-git-v023.py can synchronize later documentation with --main-only and MC_PUBLICATION_BASE set to the current remote head; inspect remote state first.

The first post-publication phone check timed out; its local .downloads/visual-0.2.3-published-check.log is retained and excluded from passing counts. The retry .downloads/visual-0.2.3-published-check-retry.log passed and is copied to the public published-check evidence. No phone network policy or PIN configuration was changed. No new0.2.3 OTA download/installer flow is claimed; this phone was upgraded using adb install -r and separately checked against the public update endpoint.

## Environment and limits

JDK17:D:\dev\jdk-17; SDK:D:\dev\android-sdk; NDK28.2.13676358; CMake3.22.1; Gradle9.4.1. WSL Ubuntu-24.04 uses /opt/mobilecode-work. Find the active Mi9SE with adb devices; wireless addresses change. Wake the screen before instrumentation; tests keep it on. No PIN or lock configuration changes are authorized.

Device:Xiaomi Mi9SE, LineageOS Android15/API35, ARM64,4096-byte pages, about6GB RAM. Actual16KiB execution, Android10–17/vendor coverage, physical key-to-render P95, sustained/background/low-memory matrix and external SAF provider UI matrix remain open. Formal broad P0/P4 acceptance is still pending.

All compiler/runtime/debug/completion resources remain bundled offline. INTERNET and REQUEST_INSTALL_PACKAGES are authorized for the updater only; no broad storage permission. Keep keys, device logs, caches, generated toolchains and projects out of Git.

Previous published baseline:v0.2.2/versionCode5, full22-case acceptance and genuine GitHub/system-installer OTA evidence are preserved in docs/VERIFICATION.md and verification/releases/v0.2.2. Historical tags/releases and user projects remain intact.
