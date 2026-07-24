<!-- Copyright (C) 2026 Jacob Repp -->

# Player-light evidence

## Goal and contract

Normal sky and block lighting can legitimately approach black at night, during
weather, or underground. The renderer now applies a deliberately faint local
light centered on the player so nearby geometry remains readable without
turning the scene into fullbright.

The stable contract is:

- peak contribution is 15% by default;
- contribution falls quadratically to zero over six blocks;
- existing block/sky light wins whenever it is brighter;
- intensity is persisted in the rendering profile and can be adjusted from Off
  through 30% in five-percent steps with the shared discrete slider;
- the contribution is evaluated per fragment for terrain, breaking overlays,
  and lightmapped entities, avoiding triangle-shaped interpolation seams;
- particles use the same player position, intensity, and radius.

The world lightmap remains authoritative. This is a presentation-only minimum
around the player and does not mutate server light, chunk light data, spawning,
or simulation.

## Automated acceptance

`LightingControlsTest` verifies bounded five-percent menu steps.
`PlayerLightFalloffTest` verifies the 15% black-surface floor at the player,
quadratic midpoint contribution, zero at the six-block boundary, and the Off
state.

The focused Java 17 gate passed seven tests. The broad unit suite passed. The
broad integration suite executed 2018 tests with all lighting/rendering tests
passing, but the unrelated timing-sensitive `KeyHandlerTest.tick twice` observed
one callback under the loaded suite. Its isolated rerun passed all four cases;
the same suite-level timing failure repeated and remains outside this graphics
change.

## Live acceptance

The supervised client hot-reloaded the implementation through generations
17–19 while the server process remained stable.

- Generation 17 established the live 15% night/rain behavior and exposed
  vertex-interpolation seams at point-blank range.
- Generation 18 moved terrain, breaking, and skeletal evaluation to the
  fragment stage. A debug capture at 1800×1000 showed smooth nearby geometry
  without triangle seams, and the complete Lighting menu fit the viewport.
- Debug input changed the live value from 15% to 10% and back to 15%; the
  original step buttons and their labels updated immediately. They were
  subsequently replaced by the shared slider and reaccepted in
  [stepped-slider evidence](2026-07-23-stepped-slider.md).
- Generation 19 moved the radius to a shared uniform backed by the tested
  six-block contract. The client reported `playing`, the five-mod Fabric stack
  remained active, and a final framebuffer capture showed normal world
  rendering.
- No fatal, shader-compilation, OpenGL, or `GL_INVALID` diagnostics appeared
  from the generation-19 log boundary.
