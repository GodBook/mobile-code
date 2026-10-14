# Task state

## GitHub / Release follow-up (user authorized, 2026-10-14)

0.3.1/code8 [published/latest](https://github.com/GodBook/mobile-code/releases/tag/v0.3.1) at2026-10-14T13:50:30Z, ID412770874. Source tag/archive e626c144a7999362ef96d4e52fc4a88211d0a5d4; normal non-force atomic push preserved history. Actual signed release APK SHA2563983d693f654504fbeb6f3e77154bf2b233282ec545c0790e52a80abf5887604:27 release units,24 real-phone core cases(246.690s),484 native files/1460 audits and original-data checks before/after install/tests passed. All five assets match GitHub sizes/digests; anonymous latest manifest is exactly code8 and APK HEAD isHTTP200/exact length;0.3.0 assets/tag preserved. [Acceptance](../../verification/releases/v0.3.1/acceptance.json) and [publication](../../verification/releases/v0.3.1/publication.json) record details. No requested publication work remains. Phone is PIN-locked, so shipping UI/performance suite was not repeated; prior p0 full43 remains separate. New phone OTA and broad device matrices remain unverified.

## Four reliability fixes (user authorized, 2026-10-14)

| ID | Deliverable | State | Verification |
|---|---|---|---|
| FIX-01 | Stable session/snapshot identity and failed-build history | Implemented; host/phone scope passed |3 units and real run/failed-build log association after repository reload; old unlinked snapshots accessible; schema unchanged |
| FIX-02 | Project/document/snapshot diagnostic provenance | Implemented; host/phone scope passed |5 units and real changed-source/cross-project/snapshot-preview cases; off-UI filtering; unversioned live results remain in Problems |
| FIX-03 | One overall build deadline including output draining | Implemented; host/phone scope passed |4 units and actual descendant-held pipe timeout/termination; shared120s budget and truthful BUILD_TIMEOUT/Stop causes |
| FIX-04 | Complete offline component verification/repair | Implemented; host/phone scope passed |9 units and real corrupt/missing-resource repair followed by four-language execution; rollback and interrupted-swap/stage recovery |

Earlier unpublished non-debuggable ARM64 p0 APK SHA25655569099c077f38a03244fd0b73e45012dd3a9f17ec43eac00d5497f9a1e0e9a:27 units,4 targeted phone cases,43 full regression cases and1460 APK audit checks passed. At that checkpoint original APK/test package was restored and data preservation passed. The later user-requested0.3.1 publication is recorded above; details and device-matrix limits are in [STATE.md](STATE.md). The initial3/4 targeted run is retained and excluded from accepted counts.

## Approved UX implementation and release (2026-10-13)

User approved the complete [upgrade plan](../UX_UPGRADE_PLAN.md), connected-phone validation and GitHub/Release publication. Core implementation is complete in0.3.0/code7. Exact shipping43/43 phone regression passed; [acceptance](../../verification/releases/v0.3.0/acceptance.json) records scope/limits. “Phone scope passed” is not blanket fault/device-matrix acceptance.

| ID | Deliverable | State | Actual evidence / open scope |
|---|---|---|---|
| UX-01 | Per-file save, retry, dirty-buffer export and explicit recovery | Shipping phone scope passed | Immediate-flush fault/export/retry, automatic draft and real force-stop recovery; original source preserved |
| UX-02 | Acknowledged stdin drafts and EOF capability | Shipping phone scope passed | Four-language core input plus UI acknowledgment/error/draft/EOF, including360dp/font1.5/IME |
| UX-03 | Invalid-project recovery and cancellable imports | Shipping phone scope passed | Broken config raw export, mixed-language/src preservation, cancellation and original hashes |
| UX-04 | Component readiness and direct retry/space feedback | Implemented; partial phone scope | Normal preparation verified; corrupt-resource/full-disk/restart fault matrix open |
| UX-05 | Storage inventory, safe cleanup and generated-file/history access | Shipping phone scope passed |100 real runs;356 existing sessions/current snapshot/source protected; external SAF UI open |
| UX-06 | Compact/searchable/pinned/recent home | Shipping phone scope passed | Chinese search/filter/pin/tree;360dp/font1.3 and1.5 checked |
| UX-07 | Folder tree, atomic moves, project operations and recycle restore | Shipping phone scope passed | Conflict rejection, Unicode paths, entry/breakpoint migration, copy/rename and source hashes |
| UX-08 | Quick open, inline search/replace, line/project search | Shipping phone scope passed | Actual Unicode buffer/search/navigation; broader search edge matrix remains open |
| UX-09 | Tabs, per-file locations, watches and workspace restoration | Shipping phone scope passed | Actual cursor after recreation, tabs/watches restoration and process recovery |
| UX-10 | Focus/responsive UI, accessibility labels, shortcuts and settings | Shipping phone scope passed; matrix partial | Default/font130/font150 portrait/landscape/IME; TalkBack/physical keyboard/split-screen open |
| UX-11 | Diagnostic provenance, bounded logs, argv/configuration and history | Shipping core phone scope passed | Real fault/12MiB flood/10MiB log limit, snapshots/input/argv; external export UI open |
| UX-12 | Breakpoint explanations and fresh variable/watch UI | Shipping core phone scope passed | Four-language real debug/step/locals/watch; broader rapid-transition matrix open |
| UX-13 | Real definition/references/outline and service retry | Shipping phone scope passed | Four-language real cross-file navigation; overload/scope/external-source matrix open; formatting/semantic rename assessed/deferred |
| UX-14 | Updater clarity and measured complete-package reduction | Implemented; online flow unverified |6.78% measured reduction/native audit/security checks; phone had no network, real new OTA unverified |
| UX-R | Signed production APK, phone verification and GitHub Release | Published and verified | [v0.3.0](https://github.com/GodBook/mobile-code/releases/tag/v0.3.0); exact APK43/43,6 units/1460 audits; five sizes/digests, anonymous manifest7/APK HTTP200 and old assets/tag preservation verified |

## Appearance and performance increment (2026-10-12)

| ID | Deliverable | State | Acceptance / evidence |
|---|---|---|---|
| O1 | Reference-style syntax colors and explicit light/dark themes | Phone acceptance passed | Four grammar roles, Unicode/incomplete edits, actual theme choices, undo/recreation and real screenshots; final29/29 regression |
| O2 | Memory and interaction improvements | Phone acceptance passed; broad matrix pending | Visible1MiB open431.855ms/frame-callback P9530.795ms/PSS176101KiB; lexical fallback80ms;12MiB visible console; closed editors and semantic recovery |
| O3 | Tested GitHub source and 0.2.3 Release | Published and verified, 2026-10-13 | https://github.com/GodBook/mobile-code/releases/tag/v0.2.3; all5 assets match sizes/SHA256, anonymous latest manifest is6, phone current-version check1/1 in3.293s; original signature and data preserved |

## Previous accepted baseline

| ID | Deliverable | State | Acceptance / evidence |
|---|---|---|---|
| P0.1 | Android build and offline toolchain preparation | Local acceptance passed | Full ARM64 release, locked dependencies, unique native names, hashes and 484 ELF audits |
| P0.2 | Independent C/C++/Python/JVM workers | Phone acceptance passed | Four actual languages, input/argv/EOF, stop and crash containment |
| P0.3 | Python, Java and native debugger adapters | Phone acceptance passed | Four real stdin/breakpoint/step-in/over/out/stack/locals/watch flows |
| P1 | Workspace, templates, autosave, editor/settings | Core phone acceptance passed | Chinese composing/emoji, 30 rapid resets, undo/fold/save/run, source/ZIP round trip |
| P2 | Semantic language services, completion and debug UI | Phone acceptance passed | clangd/Jedi/JDT cross-file symbols and actual DAP integration |
| P3 | Resource/fault/Android compatibility | Local fault suite passed; matrix pending | 20/20 overall; timeout, blocked input, flood, SIGSEGV, EOF and stop; broader devices pending |
| P4 | APK, source and documentation | Local and GitHub delivery complete; broad compatibility pending | v0.2.3 Release includes signed APK, application/corresponding sources, update manifest and hashes;29/29 final phone acceptance passed |

Available phone: Xiaomi Mi 9 SE, Android15 ARM64, 4KiB. Formal P0/P4 broad compatibility acceptance remains open for 16KiB live execution and the Android10–17/vendor matrix. Local APK delivery must state these limits.

## GitHub / online update increment (2026-10-12)

| ID | Deliverable | State | Acceptance / evidence |
|---|---|---|---|
| U1 | GitHub release updater and settings | Accepted on phone | HTTPS check, system download, APK verification and normal installer; 6/6 unit tests and 2/2 security cases in final regression |
| U2 | Updated release and connected-phone validation | Accepted on phone | Actual 429,738,676-byte GitHub download, system upgrade from bridge4 to public5, project/settings preservation, 22/22 final regression and post-upgrade latest check |
| U3 | Public GodBook/mobile-code repository and release | Published | https://github.com/GodBook/mobile-code/releases/tag/v0.2.2; all five assets verified by size/SHA256; application and corresponding source archives included |

Historical evidence: [0.2.2 acceptance report](../../verification/releases/v0.2.2/acceptance.json), adjacent raw instrumentation logs, and [verification notes](../VERIFICATION.md). The phone now uses0.3.1/versionCode8; see[0.3.1 acceptance](../../verification/releases/v0.3.1/acceptance.json). The earlier temporary screen-activity helper remains stopped; failed locked/Doze and intermediate runs are retained and excluded from passing counts.
