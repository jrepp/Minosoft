<!-- Copyright (C) 2026 Jacob Repp -->

# Double-tap sprint evidence

Date: 2026-07-23

## Input and simulation contract

`CameraInput` registers `W` twice: the existing held forward binding and a
`KeyActions.DOUBLE_PRESS` sprint binding. The shared detector accepts presses at
most 300 ms apart and ignores a repeated toggle for 500 ms. Each toggle edge is
published as `MovementInputActions.startSprint`, not as a render-frame-only
`PlayerMovementInput.sprint` value.

`PersonView` accumulates that action until the 20 Hz local-player physics cycle
consumes it. `LocalPlayerPhysics` accepts either held Control or the accumulated
action when starting sprint, clears the action after evaluating it once, and
then preserves the existing sprint rules: forward movement must continue and
hunger, collision, item-use, sleeping, water, and blindness restrictions still
apply.

The explicit action mailbox is required because render input can update more
often than simulation. An initial implementation emitted a one-frame movement
flag; live acceptance showed that a later render frame could overwrite it
before physics observed it.

The double-press state also now uses a nullable `lastChange`. The previous
`TimeUtil.NULL` value was actually library-load time and suppressed valid double
presses during the first 500 ms of a process.

## Automated acceptance

- `KeyBindingChangeTriggerTest` proves one event per toggle edge.
- The existing `DoublePress` integration group proves single press, accepted
  double press, repeat toggle, timeout, and debounce behavior.
- `SprintIT.sprintRequestOnlyNeedsOneTickWhileForwardRemainsHeld` proves that
  physics consumes `startSprint` once and remains sprinting while forward stays
  held.
- The complete `:integrationTest` suite passed after the mailbox correction.

## Live acceptance

Trajectory `iris-jei-acceptance-2026-07-23` hot-reloaded the input changes while
the parent and Fabric server stayed stable. The old client endpoint disappeared
and the replacement retained both `input.inject` and `state.sample` in
`core.capabilities`.

After returning from the pause screen, one normal-path batch injected
`W press -> W release -> W press`. The next state sample reported:

```json
{"gamemode":"creative","sprinting":true}
```

Releasing `W` through the same input path produced:

```json
{"gamemode":"creative","sprinting":false}
```

`ClientDebugChannel` now includes the model-owned `sprinting` value in its
immutable player sample so this behavior can be accepted without inferring it
from position or FOV.
