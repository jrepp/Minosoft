<!-- Copyright (C) 2026 Jacob Repp -->

# Camera-motion noise measurement

## Outcome

Camera-motion noise is now measurable independently of ordinary cloud, foliage,
weather, and world-time animation. The repository CLI performs a bounded yaw
turn, returns to the exact sampled player pose, captures recovery checkpoints,
and compares them with stationary captures separated by the same actual number
of render frames.

The metric does not automatically claim a shader root cause. It answers the
narrower question needed for graphics work: how much same-pose image residual
was introduced by camera motion, how quickly did it converge, and how much
would the scene have changed over the same time without moving?

## Command

```sh
./play.sh debug visual motion-noise \
  --yaw-delta 5 \
  --samples 2 \
  --recovery-frames 0,4,16,32 \
  --settle-frames 32 \
  --away-frames 4 \
  --region 0,0,3456,850 \
  --output .run/motion-noise/2026-07-28-cloud-layer \
  --trajectory diverse-small-biomes-2026-07-28 \
  --json
```

The output directory contains `report.json` plus first-sample reference,
motion, and stationary-control crops. Raw runtime artifacts remain under
`.run/` and are not source evidence.

## Metric contract

For every checkpoint, the report includes:

- changed-pixel ratio above the configured per-channel threshold;
- mean absolute RGB and luminance error, RMS luminance error, and p95
  luminance error;
- a low-gradient baseline mask and its changed-pixel ratio, used as the
  speckle-oriented measure;
- motion-minus-control excess and motion/control ratios;
- requested recovery frames, actual recovery frames, and the total actual
  frame delta used for the stationary control.

The stationary control begins at the same pose after an independent settle
period. Its capture delay matches the motion pair's actual render-frame delay,
including the turn-away/return interval. This prevents authored animation from
being mistaken for a movement-only residual.

PNG capture itself can advance several render frames. Therefore acceptance must
use `actualRecoveryFrames`, not assume that a requested checkpoint was captured
without readback overhead.

## Representative cadence

The normal profile intentionally throttles an unfocused client. A terminal-owned
probe would otherwise run near 8 FPS and misrepresent interactive camera motion.
The command acquires `visual.background-throttle` with compare-and-set semantics,
sets only the non-persistent override to `disabled`, and restores `disabled` to
`default` only if it still owns that state. It records initial/final FPS and
median frame timing and marks the result non-representative below 20 FPS or
above a 50 ms median.

The accepted run reported:

- minimum observed FPS: `52.736`;
- maximum median frame time: `18.605 ms`;
- throttle transition: `default → disabled → default`;
- pose conflict: `false`;
- client/server remained ready after the run.

## Live result

The 3456×850 upper-frame region covered the visible Complementary cloud layer.
Two samples produced these aggregate ratios:

| Requested checkpoint | Motion/control luma | Motion/control flat speckle | Excess luma error |
| --- | ---: | ---: | ---: |
| 0 frames | 5.768× | 43.561× | 1.5900 |
| 4 frames | 1.275× | 0.749× | 0.1080 |
| 16 frames | 1.206× | 0.929× | 0.1140 |
| 32 frames | 1.067× | 1.146× | 0.0520 |

The requested four-frame capture landed at seven and eight actual recovery
frames because earlier PNG readback occupied render time. The 16- and 32-frame
captures landed exactly on their requested recovery values.

This is direct evidence of a large motion-triggered residual on the first
returned frame and substantial temporal convergence afterward. It does not show
that the immediate noise is acceptable; it provides the baseline needed to
compare TAA, cloud sampling, foliage animation, and shader-history changes
without relying on subjective screenshots.

## Implementation and validation

Implementation:

- `util/play/MotionNoiseAnalyzer.java`
- `util/play/Play.java`
- `RenderContext.backgroundThrottleOverride`
- `RenderLoop` background limiter selection
- `ClientDebugChannel.configureBackgroundThrottle`

Validation:

- `./gradlew :play-util:test`
- `./gradlew compileKotlin`
- live managed client/server command above
- exact pose restoration with `poseConflict=false`
- compare-and-set throttle restoration to `default`
- `./play.sh status --json` reported parent, server, and client ready afterward
