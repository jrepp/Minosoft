<!-- Copyright (C) 2026 Jacob Repp -->

# Stepped-slider evidence

## Decision and scope

Bounded numeric settings use one reusable `SteppedSliderElement` instead of a
value label plus separate decrement/increment buttons. The element keeps an
integer step as its source of truth and supports:

- click and drag selection rounded to the nearest step;
- Left/Right and mouse-wheel single-step changes;
- Home/End minimum and maximum selection;
- immediate label and callback updates;
- silent synchronization when a menu is reopened or reset.

`SteppedSliderSteps` owns the rendering-independent position/step mapping. The
menu remains responsible for translating a step to its profile value.

The current bounded step-control inventory is fully migrated:

| Menu | Setting | Range |
| --- | --- | --- |
| Lighting | Brightness | 0–100% in 10% steps |
| Lighting | Player light | Off–30% in 5% steps |
| Audio | Master volume | 0–100% in 10% steps |

Pagination, toggles, and one-shot actions remain buttons because they do not
select a bounded numeric value.

## Automated acceptance

The Java 17 focused gate passed 15 cases across `SteppedSliderStepsTest`,
`LightingControlsTest`, and `AudioControlsTest`. Coverage includes nearest-step
selection, endpoint clamping, handle travel, narrow/single-value stability, and
reversible profile conversions.

The broad unit gate passed all 1,461 tests after removing the host shell's
`NO_COLOR=1`; the unrelated `LogArgumentTest` intentionally derives its default
from that environment variable.

## Live visual and interaction acceptance

The supervised client activated generation 23 (PID 15291) while the ready
server remained PID 35639. Debug-pipe framebuffer captures at 1800×1000 verified
that Lighting and Audio use the same button atlas surface, dimensions, centered
label, track/fill/handle geometry, hover outline, and menu spacing.

- Brightness dragged from 10% to 70%; Right Arrow selected 80%; the test then
  restored 10%.
- Master volume dragged from 100% to 50%; End restored 100%.
- Both changes updated the rendered label and world/audio presentation in the
  same frame sequence.
- The five-mod Fabric stack remained active and the client remained in
  `playing` state.
- No fatal, `GL_INVALID`, shader, or OpenGL error was emitted in the generation
  23 acceptance window. Longstanding deferred `Buffer has not been unloaded`
  finalizer warnings also appeared in earlier generations and remain a separate
  GUI/graphics lifecycle backlog item.
