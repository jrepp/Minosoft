<!-- Copyright (C) 2026 Jacob Repp -->

# In-game audio menu evidence — 2026-07-22

## Decision

Minosoft owns audio presentation and exposes it directly from the pause menu:

```text
Pause → Audio
  → master enable and a 10%-step volume slider
  → live engine status and test output
  → Advanced Audio
      → server, GUI, and button sound policies
      → next-start engine policy
      → stop all and reset
```

The controls mutate the existing trajectory-owned `AudioProfile`; they do not
introduce a second settings model. `AudioPlayer` already observes master enable
and volume changes, so those controls apply to the live OpenAL engine. Engine
startup maps to `skipLoading` and is explicitly labelled as restart-required
because the player is constructed during rendering startup.

The status text reads `AudioPlayer.initialized`, rather than inferring readiness
from the profile. Test output uses the normal `AudioPlayer.play` path and the
registered `minecraft:entity.experience_orb.pickup` sound event.

Sound indices are layered separately from sound assets. `SoundManager` reads
every available `sounds.json` from low to high resource-pack priority.
`SoundIndexMerger` preserves unrelated lower-layer events, appends event sounds
by default, and honors an event's explicit `replace` flag. A partial resource
pack therefore cannot hide vanilla UI, gameplay, or test events.

OpenAL master volume uses listener `AL_GAIN`. `AL_MAX_GAIN` is a source property
and cannot implement global listener volume.

## Implementation

- `PauseMenu` opens the new `AudioMenu`.
- `AudioMenu` owns the compact primary controls, live engine status, and test
  output.
- `AudioAdvancedMenu` owns sound-family policies, startup policy, stop-all, and
  reset.
- `AudioControls` centralizes bounded 10% volume conversion and engine-state
  classification.
- `SoundIndexMerger` composes base and resource-pack event definitions before
  `SoundManager` constructs its runtime event table.
- `AudioPlayer.appliedMasterVolume` records the value read back after applying
  listener `AL_GAIN`; client debug state reports configured/applied volume and
  source counts without calling OpenAL from the debug thread.
- Audio debug state distinguishes requested, resolved, and unresolved sound
  counts, distance and buffer rejection, and actual source starts. It retains
  the last started sound and world position. Requested/resolved telemetry alone
  does not prove playback.
- The audio thread refreshes listener position/orientation from the live camera
  before attenuation. This covers the startup race where the camera's first
  change event can precede audio-listener registration.
- `en_us.lang` contains the full integrated-language surface.

The first live layout placed every control on one page and clipped at the
current GUI scale. Framebuffer evidence led to the primary/advanced split; both
final pages fit in a 1800×1000 capture.

## Automated and live evidence

- `AudioControlsTest` passed four focused cases covering stable steps, clamping,
  imported-value rounding, and all engine states. `SoundIndexMergerTest` passed
  three cases covering unrelated-event preservation, append, and replace.
- Full Java 17 acceptance passed: 1,441 unit tests and 2,001 integration tests
  with zero failures (116 integration skips).
- Parent session `2026-07-23T02:49:18.018447Z-77297` rejected two invalid
  compile candidates while preserving the prior client, then activated the
  corrected changes through generation 6.
- Generation 6 (PID 44614) reported `Engine: Ready`; its logs recorded
  `Loading OpenAL...` followed by `OpenAL loaded!`.
- The first hook-only check was insufficient: it proved dispatch but not event
  resolution. User playback found that Enhanced Audio's partial `sounds.json`
  shadowed the vanilla index, leaving the test and button events unresolved.
- Generation 7 (PID 53418) loaded the merged index. Test output then produced
  two sound-hook calls (button plus chime), and the debug HUD reported 26
  allocated OpenAL sources rather than `S disabled`.
- Generation 8 (PID 57753) applied the corrected listener gain. Client debug
  state showed configured and applied master volume changing together. The
  final live check used the menu to restore 100% volume and read back
  `configuredMasterVolume: 1.0` and `appliedMasterVolume: 1.0`.
- Earlier foliage checks advanced only Fabric-hook and resolution counters.
  Those checks did not prove that OpenAL started a source and are not playback
  acceptance evidence.
- After the following supervised graphics-fix reload, generation 10 reported
  one requested and one resolved ambient sound, zero unresolved sounds, and
  matching last-requested/last-resolved
  `minecraft:entity.zombie.ambient`. Master volume remained configured and
  applied at 1.0.
- Debug framebuffer captures at 1800×1000 verified the final primary and
  advanced layouts visually.
- The original quieter/louder buttons were subsequently replaced by the shared
  discrete slider and reaccepted in
  [stepped-slider evidence](2026-07-23-stepped-slider.md).
- After listener synchronization and source-start telemetry, a targeted stone
  harvest removed the block and reported
  `lastStartedSound=minecraft:block.stone.break`,
  `lastStartedPosition=(0.5,96.5,-23.5)`, listener
  `(0.7488369,97.53,-21.7)`, one allocated source, zero missing buffers, and no
  new distance rejection. This proves the sound originated at the destroyed
  block rather than at the listener or as a 2D sound.
- Mining progress now uses the block sound group's positional `hit` event with
  monotonic 200 ms spacing. A short non-destructive live strike started
  `minecraft:block.stone.hit` at block center, and remote break-animation
  updates share that path while excluding the local player ID.
- `./play.sh status --json` reported parent PID 77297, server PID 77325 ready,
  and generation-22 client PID 94056 active after final validation.
- The subsequent mining-progress/arm trajectory activated generation 28, PID
  6733, under the same parent and ready server. Its live startup/acceptance log
  window contained no OpenGL, fatal, or exception entry.

## Boundary and next work

This menu controls the existing audio engine; it does not yet expose per-sound
category volume sliders, device selection, or a live OpenAL teardown/restart.
Those require explicit engine lifecycle ownership before they should be added
as menu actions.
