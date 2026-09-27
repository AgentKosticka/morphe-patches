# Jam compatibility and player-controls validation

## Scope

PR #3014 now includes the compatibility and player-control changes from
`AgentKosticka/Jam-Patches` commits `81820e6` and `d75c61a`.
The source and test files were compared with that tested implementation before
publication; only the new Jam string was merged into the shared string resources.
Custom-source release metadata and vendored dependency changes were not imported.

The patch declares `versionCheckPatch` and accepts exactly 9.15.51, 9.35.54,
9.36.50 and 9.37.54. Both Jam bytecode and resource execution warn and return
before making Jam changes on any other version. Shared dependency patches retain
their normal behavior. The three newer targets remain experimental.

The compact player's previous/next and both layouts' play/pause controls route
participant commands to the host. Both icons use the native renderer with host
clock state. Explicit pause/resume preserves the current item and validates its
identity. Repatch both host and participant; the Companion needs no update.

## Evidence

| Version (ARM64) | Patch, SDK DEX verification and APK build | Device tests |
| --- | --- | --- |
| 9.15.51 baseline | Passed in Jam-Patches | User reported passed, 2026-09-26 |
| 9.35.54 experimental | Passed in Jam-Patches | Pending |
| 9.36.50 experimental | Passed in Jam-Patches | Pending |
| 9.37.54 experimental | Passed in Jam-Patches | User reported passed, 2026-09-26 |

The device-test report covers the preceding player-control fixes. No new device
execution was performed during the PR port, and no broader device matrix is inferred.

The validated patch selection includes GmsCore support, Hide ads, Jam, Lyrics,
background playback and Miniplayer previous and next buttons. Seven regression
tests passed on 9.15.51 and 9.37.54; the final exact-version guard brought the suite
to eight passing tests on 9.37.54. The guarded 9.37.54 APK and Android patch bundle
also built successfully.

The standalone PR checkout's build attempt remains blocked by the previously
observed shared YouTube/dependency API mismatch: 12 Java errors involving
`CharSequence`/`String` and missing `Utils.indexOf`/`Utils.contains`. It did not
produce a fresh PR-checkout build. The successful compile evidence above comes
from the matching Jam-Patches source and its pinned dependencies. That environment
also contains fingerprint-cache lifecycle fixes in its vendored patcher, which
belong to the patcher dependency and are not part of this patches PR.

Local traces are retained in the workspace's `analysis` directory:

- `jam-controls-<version>-miniplayer.log`: four successful APK validations.
- `jam-controls-version-guard.log`: final guarded tests and APK validation.
- `jam-controls-final-catalog.log`: final catalog and Android bundle build.
- `pr3014-controls-build.log`: standalone PR-checkout build limitation.

Bulk/playlist/offline enqueue, exhaustive Doze/network stress, separate radio-loss
failover and physical camera validation remain outside the reported acceptance.

## Session lifecycle update â€” 26 September 2026

The Jam runtime and decoder regression test from Jam-Patches `d79890b` are
ported here without custom-source release metadata or dependency changes.
Joining pauses participant audio, leaving keeps it paused, Wi-Fi-off entry is
allowed, BLE has a connected status, and unsupported queue commands use the
translatable **Unsupported during Jam** message. Binding and session replies
update the player promptly, with stale lifecycle replies discarded.

Validation in the matching Jam-Patches checkout passed 46 bridge checks,
12 timeline checks, nine patch/decoder tests with real APK fixtures, and local
9.15.51/9.37.54 patch application and SDK DEX verification. The final 9.37.54
isolated unsigned APK was constructed successfully. These results do not claim
a standalone build of this PR checkout or manual acceptance of the new YTM UI.

The matching companion lifecycle update is local Jam Layer commit `6a8a828`.
Its 30 unit tests and companion-only device lifecycle/LAN/Aware/BLE tests passed
on the two test devices. The updated companion is installed there; its source
changes are not part of this patches PR. Native YTM playback and rejection UI
remain subject to the [manual test checklist](session-lifecycle-testing.md).

For this port, the five changed runtime files were compared with `d79890b` and
match apart from line endings. The PR checkout's 46 bridge and 12 timeline checks
passed again. A fresh `:patches:buildAndroid`/decoder-test attempt still stops at
the same 12 shared YouTube/dependency Java errors described above, before the
decoder Gradle test can run. See local `analysis/jam-pr-session-build.log`.


## Review fixes and device retest — 27 September 2026

Ported the tested Jam-Patches changes through `8b2befa`, including zappybiby's
13 review comments, contextual song links, host cover artwork and restoration on
exit. Runtime/patch/test files match that commit byte-for-byte. Release metadata,
vendored dependencies and subsequent uncommitted artwork work were not imported.

Nine tests passed on both 9.15.51 and 9.37.54 in Jam-Patches, along with SDK DEX
verification and APK assembly; the final artwork-cache adjustment was rebuilt and
APK-verified on 9.37.54. This port does not claim a fresh standalone PR build;
the dependency mismatch recorded above remains a limitation of that environment.

The author reports the device checks passed with one deferred exception: rapid
skips can leave participant artwork fully black until changing tracks and returning.
That issue is still open. See [review fixes and retest details](pr3014-review-fixes.md).
