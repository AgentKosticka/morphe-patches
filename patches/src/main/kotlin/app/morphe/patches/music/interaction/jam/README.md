# Jam patch boundary

`JamPatch.kt` wires dependencies, resources and preferences. `Fingerprints.kt`
contains semantic entry points: queue diagnostics, platform calls, and native
view resource names. `JamAbi.kt` and `JamUiAbi.kt` resolve native relationships
from those matches. They must fail on missing or ambiguous targets; do not add
obfuscated class/method names or select an arbitrary candidate to make a version
patch successfully.

The installers add casts, field accessors, native calls and interception points.
`JamBridge.kt` contains their shared method emitter. Use the project's
`BytecodeUtils` instruction matching and reverse-index helpers for insertion;
keep queue policy and UI behavior in `extensions/music/.../jam`.

In particular:

- Host playback uses `MediaController.TransportControls.skipToQueueItem` with
  the native persistent item ID. The queue mutation notifier is **not** a play
  operation. Never substitute an enqueue endpoint on playback failure.
- `JamClock.playHost` confirms the selected ID and playing state off the native
  queue executor. Paused selection is resumed only once the requested ID is active.
- Player metadata is handled by `JamMetadata`, which overrides the actual
  full-player and mini-player `setText` calls. The presenter is found through
  `player_page`, `mini_player_title` and `mini_player_subtitle` resources. All
  caching, text selection and restoration run in Java on the UI thread; no
  timer races or custom text-register dataflow analysis are needed.
- Queue lane selection, bounds checking and reorder predecessor calculation
  are extension interface defaults. Smali only exposes native primitives.

The native ABI resolver is still substantial because Jam mirrors YTM's native
observable queue, item models, gestures and player components. Removing literal
obfuscation names does not prove compatibility with uninspected versions. The
patch remains opt-in and limited to 9.15.51 until additional APKs and user testing
provide evidence for broader support.
