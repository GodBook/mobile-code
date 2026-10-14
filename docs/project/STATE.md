# Current checkpoint

Updated: 2026-10-14, Asia/Shanghai.

## Active objective — publication

User authorized implementation of docs/UX_UPGRADE_PLAN.md, computer-connected phone tests and GitHub/Release publication. Implementation and phone validation are complete within the recorded scope; publish0.3.0/versionCode7 next. Main implementation commit:0a38049efbdf4472c3d817a2cf2ce922fda59201, based on3a0846880bb1350a6bb39717e104a5d441cd07c0. App source/APK stayed unchanged while test fixtures/gestures were corrected. Working tree contains final tests, docs and curated evidence not yet committed. Remote main/latest are still the previous baseline; no0.3.0 tag or Release has been created.

## Frozen production artifact

- artifacts/update-publication/MobileCode-0.3.0-arm64-v8a.apk:400641853bytes (382.1MiB), SHA256e319f2360aa471abd294646735af98206a89b69c2cdd4d14ec201856116d0cdd.
- Actual installed base.apk has the identical hash; phone version0.3.0/code7.
- Original certificate SHA256b579d64db9b1ca5ab84084823dfcae5c625de4bcd6dd38fe4bdcfc1df5fa370a; ARM64-only, min29, compile/target37, debuggable=false.
- Default R8 UI dependency shrinking adopted without obfuscation/optimization; complete offline engines remain. Native/resource audit484 files/1460 checks passed; units6/6 passed.
- Compared with0.2.3:29,116,587bytes/27.8MiB/6.78% smaller. Report:verification/releases/v0.3.0/apk-size.json.
- Corresponding-source ZIP156785258bytes, SHA256ea24e972cb89562cbd014cd4a7c42f4ff38c88790cc4c4cb0294d31bb99a1e3a,16 recipes/23 materials.

## Accepted phone evidence

All curated evidence is in verification/releases/v0.3.0; acceptance.json is the entry point.

- Exact shipping APK full43/43 passed in418.473s (regression.json/.log), with real four-language run/debug/completion/navigation and new UX cases.
- Original data after USB install-r:1/1 in0.707s; after all tests:1/1 in0.828s. Original project IDs, representative Chinese/emoji source hash, breakpoints and appearance settings preserved.
- Actual process-death recovery: two separate methods, host am force-stop between them; automatic draft persisted, disk original unchanged before explicit recovery, fixture and marker repaired/removed. No failed-save fixture remains.
-360dp/font1.3 layout2/2 in14.111s;360dp/font1.5 layout+input3/3 in24.136s. Actual landscape geometry and full panels checked.
-100 actual Python runs/safe cleanup in179.989s;356 existing sessions/current snapshot/source hash protected.
- Final1MiB open627.836ms;80 insertions to frame-callback P9561.684ms; app-only PSS161230KiB after closing4 editors. Earlier shipping samples P9561.917 and45.685ms retained. Stable50ms and full physical-IME targets are not established.
-1MiB lexical fallback111ms/PSS175551KiB;12MiB output frame callback8.707ms/PSS131441KiB,10MiB saved-log limit.
- Source test APK used for final43:.downloads/ux-0.3.0-ime-stable-tests.apk. Shipping APK was not rebuilt between these attempts.

First shipping full42/43 retained the injected1s timeout budget for an ordinary recovery program; fixture now restores its original budget after the unchanged timeout assertion. Second42/43 had an input-test timeout after physical clicking during IME movement; added actual stable-target/enabled/display checks and diagnostics, then full43 passed. Exact original input cause was not independently established. Firstfont1502/3 missed an offscreen chip; test now scrolls before clicking. Raw failed runs and reports are public alongside final evidence, excluded from accepted counts. Earlier bridge shrink/comparator/layout failures stay local and documented in source history.

## Device state and online limitation

USB1944b301, Xiaomi Mi9SE, LineageOS Android15/API35, ARM64,4096-byte pages, about6GiB RAM. Final app force-stopped after acceptance; no active execution/debug session. Restored:stay_on_while_plugged_in=0, font_scale=1.0, physical density440/no override. Wi-Fi remains enabled as originally found; no PIN/lock or per-app network restriction changed.

Phone has no active default network; saved networks are absent from scan. Prepublication GitHub check failed truthfully. No new phone GitHub download/system-installer/current-version acceptance is claimed. This release used USB adb install-r, distinct from historical0.2.2 real OTA. Host-network inspection was read-only and no hotspot/configuration change was made.

## Next actions

1. Validate final records/links/diff; commit final tests/docs/evidence.
2. Preserve old generic local publication metadata, run tools/prepare_release.py on the frozen versioned APK, with original signing identity and all source tracked. Generate the other four assets; do not rebuild the APK.
3. Normal non-force push main, create/push new annotated v0.3.0 source tag, create GitHub draft with five assets, verify all sizes/GitHub digests, publish as latest.
4. Verify anonymous latest manifest and public APK availability; write actual release ID/source-tag commit/asset hashes into acceptance and publication records, update docs/STATE/TASKS, push the documentation commit.
5. Open update notes and deliver Release link plus actual checks/material limits. No further user confirmation is needed; publication was explicitly authorized.

## Historical baseline and open matrix

Last published:[v0.2.3](https://github.com/GodBook/mobile-code/releases/tag/v0.2.3), code6/APK429758440bytes, SHA2566cbb4bab39b7f0bb31a0d35d3ace8002a8830f7c4b8a366d22f24da1794de67a. Five assets,29/29 final phone cases,6 units and1460 audits passed; source tag91de636, remote main3a084688. Historical tags/assets remain intact. See docs/VERIFICATION.md and verification/releases/v0.2.3/acceptance.json.

Actual16KiB execution, full Android10–17/vendor matrix, stable50ms/physical IME latency, sustained/background/low-memory, external SAF, TalkBack/physical keyboard/split-screen, broader component faults/navigation/debug transitions and new phone OTA remain open. Formal broad P0/P4 acceptance is pending. No cloud execution, keyword-only semantic substitution, broad storage access or new dependency-management/GUI capabilities were added.

Build:JDK17 D:\dev\jdk-17, SDK D:\dev\android-sdk, apksigner36.1.0. Quote dotted PowerShell Gradle flags; in-process Kotlin works. WSL Ubuntu-24.04 lacks python3.13; host AST check used Python3.14, actual CPython3.13 was verified on phone. Keys/caches/toolchains/device logs/private projects stay outside Git.
