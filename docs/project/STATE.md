# Current checkpoint

Updated: 2026-10-14, Asia/Shanghai.

## Active objective

User authorized all of docs/UX_UPGRADE_PLAN.md, connected-phone validation and GitHub/Release publication. Target: 0.3.0/versionCode7, original signing identity. Work in D:\CHATGPT\mobile code, main based on3a0846880bb1350a6bb39717e104a5d441cd07c0. No new commit/push/tag/Release yet. Remote main still matches this base; latest release remains v0.2.3. Do not replace historical assets.

## Current implementation and verification

UX-01–UX-14 core implementation exists: per-file save/recovery, acknowledged stdin/EOF, invalid-project recovery/import cancellation, component readiness, storage/history, home/tree/file operations, inline/project search, workspace restoration, responsive/focus/accessibility settings, diagnostics/log/configuration tools, breakpoint/variable freshness, four-language real navigation and updater feedback. Formatting/semantic rename remain assessment/deferred items. See release notes and TASKS for scope.

- Reviewed unshrunk bridge targeted6/6 passed in68.18s; font150 UI2/2 passed in12.946s, font restored to1.0. Logs: .downloads/ux-0.3.0-targeted-fourth.log and ux-0.3.0-font150.log.
-100 actual Python runs plus safe test-only cleanup passed in the intermediate run;244 pre-existing sessions and representative source hash preserved. .downloads/ux-0.3.0-storage-100-first.json.
- Shrink v2 bridge (0.3.0/code6, not publishable):400639685bytes, SHA256f6cf5309f9efbe7cad36bfc43883a4d454eb01caf04d1de29ef9a6cd5cc13ad6, original certificate; native484 files/audit passed, native/offline ZIP retained. .downloads/ux-0.3.0-shrunk-v2-{bridge.apk,size.json,audit.json}.
- v2 data preservation1/1 in0.656s. Full43 completed37 passed/6 failed in521.578s, excluded from full acceptance. Phone was Dozing after non-UI core tests, causing5 UI failures. The remaining failure exposed cursor restoration on view recreation without a new navigation request; fixed EditorHost initial-location restoration and strengthened the test to check the actual cursor. Woke/dismissed nonsecure keyguard and temporarily set svc power stayon usb; original stay_on_while_plugged_in=0 must be restored at completion. No PIN/lock configuration changed.
- Narrow Future API keep rules fixed the first shrink attempt's missing ListenableFuture bridge. After the latest affected14 cases passed, R8 default was adopted as true; app/native/Gson/JNI/editor names remain preserved and no optimization/obfuscation is enabled.
- Code review additionally corrected immediate flush failure so it writes the newest recovery buffer even when the delayed draft was cancelled. Existing failure test now asserts the automatic draft, and process-recovery test no longer manually seeds a draft.
- Current app bridge6281ed00a9a7b423ad75ed169117666aaa25ec5467f6fba36af09b236fb8d152: .downloads/ux-0.3.0-restored-bridge.apk. Build57352 passed in6m53s. Latest affected14/14 passed in68.614s, including actual cursor, automatic failed-save draft, themes, editor release/performance, proper landscape geometry and original data. Curated evidence: verification/releases/v0.3.0/bridge-targeted.json and .log. App source is stable.
- Awake v2 UI retest4/5 passed in34.323s; theme test failed because it tapped an offscreen settings menu item without scrolling. Test helper now performs the actual scroll. .downloads/ux-0.3.0-shrunk-v2-awake-ui.log. Other4 include visible output/editor workbench, editor release and1MiB performance. Preserve all failed logs/hash; never summarize them as43/43.

Earlier comparator, Sora readiness, Java indexing timeout, src ZIP unwrapping, stdin compact layout and delegated-state/subcomposition failures were corrected. Their failed .downloads runs are retained and excluded from acceptance. Unit updater tests6/6 passed before these latest changes; rerun on production build.

RecoveryProcessTest passed both separate steps on the current bridge:record1/1 in3.083s, host am force-stop, fresh-process verification1/1 in3.411s. It used the application's automatic draft, verified the original source hash before explicit recovery, applied the draft and repaired/removed its marker and backup. Curated bridge-recovery-{record,verify}.{log,json}. No failed-save fixture is outstanding. The earlier JUnit return-type initialization failure ran no fixture and is excluded. Original upgrade-acceptance marker remains intact.

Intermediate restored-bridge1MiB sample:open822.143ms/frame-callback P9560.359ms/app PSS143841KiB. This sampled P95 is above50ms and does not establish the physical-IME target; report it truthfully and collect the final shipping sample. Prior v2 awake sample414.703ms/P9573.862ms. Do not claim typing latency improved from these samples.

## Next steps

1. Latest bridge affected14/14 and true process-recovery2/2 are complete. Preserve source and curated evidence in an implementation commit; no further implementation changes planned.
2. Build/freeze production0.3.0/code7, units/signature/SDK/ABI/audit, using adopted default shrink configuration.
3. Complete release materials and source/evidence commit. Previously passing native/new bridge cases remain intermediate evidence; run the complete43 on exact shipping7 after OTA.
4. Prepare five immutable assets, source tag and GitHub Release; verify uploaded size/hash and anonymous latest manifest.
5. Use code6 bridge for real public GitHub download and Android installer upgrade to7. Verify data and exact shipping APK hash, run final regression/current-version check.
6. Publish final evidence/docs commit, restore temporary device settings, leave no active workers. Material platform limits stay explicit.

## Release materials

Corresponding sources prepared: artifacts/update-publication/MobileCode-0.3.0-corresponding-sources.zip,156785258bytes, SHA256ea24e972cb89562cbd014cd4a7c42f4ff38c88790cc4c4cb0294d31bb99a1e3a,16 recipes/23 materials. No native/upstream dependency changes.

Signing certificate SHA256:b579d64db9b1ca5ab84084823dfcae5c625de4bcd6dd38fe4bdcfc1df5fa370a. Keys stay outside Git. Native Git HTTPS and gh currently work. Release protocol: docs/UPDATES.md; prepare_release.py derives manifest from actual APK and tracked source list.

## Last published baseline

[v0.2.3](https://github.com/GodBook/mobile-code/releases/tag/v0.2.3), code6, APK429758440bytes, SHA2566cbb4bab39b7f0bb31a0d35d3ace8002a8830f7c4b8a366d22f24da1794de67a. All five assets verified; final29/29 phone regression,6 units and484 native/1460 audit checks passed. Source tag91de636; remote main later3a084688. Full evidence: verification/releases/v0.2.3/acceptance.json and docs/VERIFICATION.md. v0.2.2 has genuine prior GitHub/system-installer OTA evidence;0.2.3 used adb install-r and public current-version checking. Historical versions remain intact.

## Environment and limits

USB serial1944b301: Xiaomi Mi9SE, LineageOS Android15/API35, ARM64,4096-byte pages, about6GiB RAM. Old wireless10.102.147.96:5555 is offline. ADB D:\dev\android-sdk\platform-tools\adb.exe; SDK D:\dev\android-sdk; JDK17 D:\dev\jdk-17; apksigner build-tools36.1.0. Quote dotted PowerShell Gradle flags and use in-process Kotlin compilation.

WSL Ubuntu-24.04 lacks python3.13; only host AST syntax was checked with Python3.14, actual CPython3.13 is tested on phone. Do not claim fresh host-runtime acceptance.

Actual16KiB execution, full Android10–17/vendor matrix, physical IME-to-display P95, sustained/background/low-memory matrix and external SAF-provider UI matrix remain unverified. Formal broad P0/P4 acceptance remains open. ARM64/minSdk29/compile-target37/debuggable=false, complete offline resources, immutable snapshots and project preservation remain mandatory. Network only for updater; no broad storage permission. Keep generated toolchains/caches/private projects/device logs/signing secrets outside Git.
