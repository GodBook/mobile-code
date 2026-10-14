# Current checkpoint

Updated: 2026-10-14, Asia/Shanghai.

## Completed objective — 0.3.0

User-authorized UX implementation, connected-phone tests and GitHub/Release publication are delivered within the recorded scope. [v0.3.0](https://github.com/GodBook/mobile-code/releases/tag/v0.3.0) is published/latest, release ID412390733, published2026-10-14T06:19:56Z. App implementation0a38049efbdf4472c3d817a2cf2ce922fda59201; immutable source tag/archive73c9ca050463d59991c0d8496b7aa5bf2fd3436f. Main contains subsequent publication records. App APK remained unchanged while test fixtures/gestures were corrected. No active implementation task remains; unverified matrices below are explicit limits.

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

## Publication result

1. Normal non-force atomic push preserved history and created v0.3.0 at73c9ca0. prepare_release.py validated actual APK signature/SDK/ABI/nondebuggable and archived178 tracked source files.
2. All five assets uploaded and GitHub size/SHA256 verified before publication. App source ZIP1870400bytes/SHA256d87d6d3f731f872cbf055383318ea5113155805e9ab630de261f6a1f70fe2cd0; corresponding ZIP156785258bytes; update.json3954bytes/SHA2568953f2954c3d292e1e4805bd3be14d689f31958f08e7ad1ef615f713112da761; SHA256SUMS382bytes/SHA256139be78ab8417fbaf49e2e887972e46950bb202520ad4b44483a686c71e2bfc8. APK/corresponding hashes above.
3. Anonymous latest/download/update.json GET returns exact frozen metadata:code7/0.3.0/400641853bytes/e319f236…; public APK HEAD returnsHTTP200 and exact length. A new full anonymous APK download and phone OTA were not performed. Release API confirms latest412390733.
4. All five0.2.3 asset sizes/digests and source tag91de636 remain unchanged. Old generic local publication metadata preserved in artifacts/update-publication/v0.2.3. Do not rebuild/replace published0.3.0 assets or reuse its tag.
5. Final acceptance/publication/published-update records are in verification/releases/v0.3.0. Source ZIP is the prepublication73c9ca0 snapshot; postpublication evidence is supplied on main without replacing frozen archives.

## Historical baseline and open matrix

Previous baseline:[v0.2.3](https://github.com/GodBook/mobile-code/releases/tag/v0.2.3), code6/APK429758440bytes, SHA2566cbb4bab39b7f0bb31a0d35d3ace8002a8830f7c4b8a366d22f24da1794de67a. Five assets,29/29 final phone cases,6 units and1460 audits passed; source tag91de636, pre-upgrade main3a084688. Historical tags/assets remain intact. See docs/VERIFICATION.md and verification/releases/v0.2.3/acceptance.json.

Actual16KiB execution, full Android10–17/vendor matrix, stable50ms/physical IME latency, sustained/background/low-memory, external SAF, TalkBack/physical keyboard/split-screen, broader component faults/navigation/debug transitions and new phone OTA remain open. Formal broad P0/P4 acceptance is pending. No cloud execution, keyword-only semantic substitution, broad storage access or new dependency-management/GUI capabilities were added.

Build:JDK17 D:\dev\jdk-17, SDK D:\dev\android-sdk, apksigner36.1.0. Quote dotted PowerShell Gradle flags; in-process Kotlin works. WSL Ubuntu-24.04 lacks python3.13; host AST check used Python3.14, actual CPython3.13 was verified on phone. Keys/caches/toolchains/device logs/private projects stay outside Git.
