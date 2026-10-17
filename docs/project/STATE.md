# Current checkpoint

Updated: 2026-10-17, Asia/Shanghai.

## README review — 2026-10-17

Reviewed the public project README against the existing specification, build configuration and release metadata. Simplified repeated release/testing narratives, retained the offline-runtime and device-compatibility limits, and kept detailed procedures linked from the README. Documentation checks cover Markdown structure, local link targets and the documented release version; no new application build, device test, performance measurement or OTA verification is claimed. Existing acceptance evidence and open matrices below remain unchanged.

## Completed publication — 0.3.1

User explicitly requested GitHub and Release updates after the four reliability fixes. Default version is0.3.1/code8. Actual release and matching Android-test assembly/lint succeeded in8m46s;27/27 release units passed. Shipping APK artifacts/update-publication/v0.3.1/MobileCode-0.3.1-arm64-v8a.apk is400671849bytes/SHA2563983d693f654504fbeb6f3e77154bf2b233282ec545c0790e52a80abf5887604. Original certificate b579d64d…/ARM64-only/nondebuggable/min29/compile-target37 preserved;484 native files/1460 audit checks passed. Actual installed base.apk hash matches.

Shipping release-config core24/24 passed in246.690s: OfflineIdeTest18, UpdateIntegrationTest2 and ReliabilityFixTest4; real four-language run/debug/semantic completion, resource/process/input faults and all four fixes. Original data passed1/1 before install(3.438s), after USB install-r(3.157s) and after tests(1.205s). Phone now retains0.3.1/code8 with matching test package; both force-stopped, no execution service/process remains. Settings remain font1.0/physical density440/no override/stay-awake0. No PIN or network configuration change.

Phone currently has a secure PIN keyguard. Shipping screen-interaction/UX/performance classes were not repeated after the version bump; prior corrected p0 artifact has separate4+43 evidence, retained in verification/releases/v0.3.1/pre-release-*. These must not be combined into47 passing cases on the shipping APK. No new physical-IME/performance/OTA or broad device-matrix claim. Corresponding-source archive is156785258bytes/SHA256a728b4e2dd095d9d8866f2f72e78f1d65044419835d18e0e4a6fa16e9b61d9c0,16 recipes/23 materials. Acceptance entry:verification/releases/v0.3.1/acceptance.json.

[v0.3.1](https://github.com/GodBook/mobile-code/releases/tag/v0.3.1) published/latest at2026-10-14T13:50:30Z, Release ID412770874. Normal atomic non-force push advanced main and created the immutable source tag at e626c144a7999362ef96d4e52fc4a88211d0a5d4; application source archive covers215 tracked files and matches the clean reviewed checkout. All five draft asset sizes/GitHub SHA256 matched local before publication. Application source ZIP1956159bytes/SHA256827f8d4f1b121725d1764aa1ead7f9154e12538cd4cc71196e8072f1382c0dc0; update.json2432bytes/SHA25620acb703cec7ae044e1e33003f75041f7a36398923c715ce2bf0235c3e32ad5f; SHA256SUMS382bytes/SHA256224e2028a9038842bacc4648c2cbb36cf275574167ea4792c5b099aaf14f369f. APK/corresponding hashes above.

Anonymous latest/download/update.json GET exactly matches the prepared file and identifies code8/0.3.1/400671849bytes/3983d693…; public APK HEAD returnsHTTP200/exact length. Release API confirms latest412770874. No new full anonymous APK download or phone OTA was performed. All five0.3.0 asset sizes/digests and annotated tag/peeled commit remain unchanged. Old generic corresponding-source metadata preserved locally under artifacts/update-publication/v0.3.0; new assets use a separatev0.3.1 directory. Postpublication acceptance/publication/published-update records are added on main without changing the frozen source ZIP/tag or assets. Requested GitHub/Release update is complete within this evidence scope.

## Completed objective — 0.3.0

User-authorized UX implementation, connected-phone tests and GitHub/Release publication are delivered within the recorded scope. [v0.3.0](https://github.com/GodBook/mobile-code/releases/tag/v0.3.0) is published, marked latest at its publication, release ID412390733, published2026-10-14T06:19:56Z. App implementation0a38049efbdf4472c3d817a2cf2ce922fda59201; immutable source tag/archive73c9ca050463d59991c0d8496b7aa5bf2fd3436f. Main contains subsequent publication records. App APK remained unchanged while test fixtures/gestures were corrected. The following0.3.0 artifact/device statements describe its historical acceptance; current0.3.1 delivery is recorded above. Unverified matrices below are explicit limits.

## Frozen production artifact

- artifacts/update-publication/MobileCode-0.3.0-arm64-v8a.apk:400641853bytes (382.1MiB), SHA256e319f2360aa471abd294646735af98206a89b69c2cdd4d14ec201856116d0cdd.
- At0.3.0 acceptance, installed base.apk had the identical hash and phone version0.3.0/code7.
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

## Follow-up source review — 2026-10-14

Scope: requirements, current Kotlin/Java/native implementation, existing tests and frozen 0.3.0 evidence. This records the earlier review; the first four findings are resolved in the following checkpoint. Debug concurrency, performance and engineering recommendations remain follow-up work.

- History association: `SessionSupervisor.start` stores `HistoryEntity.id=requestId`, while `WorkspaceService.snapshot` generates a separate UUID. `WorkspaceService.history` looks for files under the history ID, so ordinary history entries cannot locate their actual console log/work directory. Persist an explicit snapshot association or use one session ID; preserve existing directories during migration. The snapshot getter also becomes available only after a successful build, limiting failed-build log export/provenance.
- Diagnostic provenance: `IdeViewModel` combines live diagnostics with the retained previous session; `EditorHost` matches paths only by suffix and accepts unversioned build diagnostics. Errors can therefore be drawn on changed source or another project's same relative filename. Use canonical document/project identity and snapshot hashes/versions; failed-build diagnostics also need an explicit origin rather than inference from the successful artifact.
- Build deadline: `BuildService.runProcess` applies 120 seconds separately to each subprocess, then joins output readers outside that timeout. Multi-file compilation has no overall 120-second build deadline. Bound the whole build and output draining, with truthful timeout status and process cleanup.
- Component repair: the settings retry calls ordinary `ToolchainService.prepare`; existing `.ready`/`.native-verified` markers skip resource extraction/native hashing. Deleted or corrupted extracted resources can remain undetected. Add an explicit offline verification/repair path using staged extraction and validated replacement.
- Debug concurrency needs a targeted real-adapter regression: `refreshStack`/`selectFrame` accept replies based on `PAUSED` alone, unlike variable expansion's session/stop/frame identity checks. A reply from an earlier pause or frame selection can overwrite a newer pause. Capture and recheck session, connection, stop sequence and selection generation before publishing results. This race was identified in source, not reproduced on the phone during this review.
- Performance candidates: each content event materializes the whole editor text; native parsing still compares full strings and collects the full tree; folding/bracket/line mappings are rescanned. Language-service activation is called from `viewModelScope` and performs filesystem writes/sync/configuration copies before suspension; navigation also reads files on that caller. Profile and move disk work off the UI thread, then incrementally process edits. Publish lexical/snippet results promptly while real semantic requests initialize or wait, retaining version checks.
- Packaging/engineering: the frozen size report attributes 95.07% of the APK to native libraries plus offline resources and 4.67% to DEX. Further size work needs a measured runtime-dependency/resource audit with complete offline capability/license preservation. The six host unit cases cover update metadata; the 100-run test covers safe cleanup but not history-to-file association. Add targeted coverage for the issues above and an automated verification entry point; gradually separate document persistence, navigation and debug presentation responsibilities.

Actual check: `:app:testP0UnitTest --offline --console=plain -Pkotlin.compiler.execution.strategy=in-process` succeeded (6 tests, 0 failures/errors/skips; XML timestamp 2026-10-14T10:15:44.250Z). An initial `:app:testReleaseUnitTest` invocation was unavailable under the default p0 test configuration; task discovery identified the supported p0 target. No APK assembly, native audit, new performance measurement, phone regression or OTA test was performed. The existing 43/43 result and 61.917/45.685/61.684ms P95 samples remain historical evidence; all open device/compatibility matrices above remain unresolved.

## Four reliability fixes — completed and verified, 2026-10-14

User authorized implementing the first four review findings. New snapshots use the session operation ID; old unlinked snapshots remain separately exportable with unknown run status. Failed builds retain their snapshot. Build/live diagnostics carry project/source provenance; matching hashes and document identity gate editor marks off the UI thread. Unversioned live diagnostics remain in Problems. The whole build, including output draining, shares a 120s deadline. Manual offline repair verifies native hashes and the packaged archive, stages a complete replacement, and supports rollback, interrupted-swap recovery and abandoned-stage cleanup. Database schema and original source directories are preserved.

Checks: 27/27 host unit tests (3 history,5 diagnostic provenance,4 deadline,9 offline resource/rollback/recovery,6 updater); non-debuggable ARM64 p0/app-test assembly and lint passed in an independent Gradle process; 484 ELF files/1460 APK audit checks passed. Initial source compilation had a constructor-call mismatch, corrected. The next build encountered an internal lint AsyncExecutionService error; independent no-daemon/no-parallel rebuilds passed. Frozen published 0.3.0 files/tags remain unchanged.

First verification APK: 400671793 bytes/SHA256 f9e0329e35b2cafeee4846e76881df8f365815348311271f394b2e37f07c5fff. Its targeted phone tests passed3/4 in77.346s; the actual descendant-held output-pipe case exposed InterruptedIOException masking timeout cancellation. The production reader was corrected to preserve cancellation; the original assertion was retained. This failed run is excluded from accepted counts and retained at verification/local/reliability-2026-10-14/first-targeted.log/.json, with its APK at artifacts/reliability-fixes/first-attempt-arm64-v8a.apk.

Final corrected verification APK: artifacts/reliability-fixes/MobileCode-reliability-arm64-v8a.apk,400671857 bytes/SHA256 55569099c077f38a03244fd0b73e45012dd3a9f17ec43eac00d5497f9a1e0e9a. The actual installed base.apk matched. Original certificate b579d64d… and non-debuggable ARM64/min29/compile-target37 preserved. All4/4 targeted phone cases passed in54.726s, including actual descendant termination and the truthful BuildTimeoutException. Full43/43 regression passed in438.864s on the same APK, with real four-language run/debug/completion/navigation, save recovery, input/EOF and editor/storage cases. Evidence: verification/local/reliability-2026-10-14/targeted.json,regression.json,apk-audit.json and adjacent logs/unit XMLs. This is an unpublished p0 verification artifact with version0.3.0/code7; published assets were not replaced.

Data preservation passed1/1 before installation,1/1 after the first verification install,1/1 after final tests (0.959s), and1/1 after restoring the original production/test APKs (2.261s): original project IDs, representative Chinese/emoji source, breakpoints and appearance settings retained. Restored phone base.apk hash is e319f236… (the frozen production APK); original test APK backup SHA256 cea384cb50c2fbecefa26db3502f6898548eaf8ba5d9cd1384e211aa3cb34a65. Both packages are force-stopped; no execution service remains. Font scale1.0, physical density440/no override, plugged-in stay-awake0. Original projects/history were protected; new acceptance fixtures remain available.

Measured in this regression:100 real runs in184.285s with394 existing sessions protected;1MiB open369.054ms, frame-callback P9561.574ms, app-only PSS158011KiB; lexical fallback204ms;12MiB console frame callback9.021ms. These are individual instrumented samples, not stable50ms or physical-IME acceptance. Normal startup uses quick readiness checks; explicit repair rebuilds the complete offline resources. An invalid installed native library is rejected with a request to reinstall the complete APK, because the installed native directory is immutable. Actual16KiB execution, Android10–17/vendor/background/low-memory/SAF/physical-IME matrices and new OTA remain unverified. No new release/publication is performed. No work remains for the authorized four fixes.
