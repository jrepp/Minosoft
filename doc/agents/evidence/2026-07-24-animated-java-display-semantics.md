<!-- Copyright (C) 2026 Jacob Repp -->

# Animated Java display-entity semantics evidence

## Outcome

Minosoft now has a valid headless integration gate for the display-entity
surface exercised by the pinned Animated Java 1.10.2 Blockbench export. The
exact, unmodified armor-stand export still passes its manifest/model discovery,
load, summon, walk, removal, repeated replacement, rollback, stable-identity,
and CPU-generation cleanup checks after the display renderer changes described
below.

This is runtime and renderer-state evidence. It is not pixel evidence: the
integration environment uses the dummy render system and cannot prove the
contents of a real OpenGL framebuffer.

## Reference boundary

The behavior was compared with the mapped Minecraft 1.20.4 display entity and
display renderer classes used by the repository's pinned compatibility
baseline. The relevant reference semantics are:

- `view_range` rejects displays beyond `64 * view_range`, independently of
  item, block, or text subtype;
- nonzero `width` and `height` define a visibility box centered horizontally
  on the entity and extending upward from its origin; zero on either axis
  disables the frustum-box test;
- transformation, shadow radius, and shadow strength share the display
  interpolation clock;
- text background ARGB channels and text opacity share that clock;
- a negative interpolation start delta begins a transition partway through
  rather than being clamped to zero;
- display pose interpolation uses `teleport_duration`, whose valid vanilla
  range is `0..59` ticks, and yaw follows the shortest angular path.

The `glow_color_override` value is retained but is not rendered. Minosoft does
not yet have the entity team/glowing outline pass required to consume it.

## Implemented boundary

`DisplayEntity` now derives finite nonnegative dimensions and the matching
visibility AABB from display metadata. Its shared range predicate is consumed
by `DisplayEntityRenderer`, so item, block, text, name, hitbox, and shadow
features receive one consistent visibility decision.

`DisplayEntityRenderer` now owns three independent retained interpolation
states:

1. pose position/rotation, driven by `teleport_duration`;
2. transformation, driven by display start/duration metadata;
3. absolute shadow radius/strength, driven by the same display clock.

Display shadows reuse the native terrain projection feature. Their absolute
radius and strength are published through identity-scoped render effects, so
retirement of an old renderer cannot clear a replacement's values. Distance
fade and renderer cleanup remain on the existing native path.

`TextDisplayFeature` now interpolates background color and opacity before
rebuilding its mesh. Text wrapping, alignment, default background, shadow,
see-through depth state, and packed-light override continue to use their
existing paths.

## Automated evidence

Run with Java 17:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.data.entities.entities.display.DisplayTransformationTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.effect.EntityRenderEffectsTest \
  -x :debug-core:test

./gradlew integrationTest \
  --tests de.bixilon.minosoft.local.datapack.LocalDisplayEntityFactoryTest \
  --tests de.bixilon.minosoft.local.datapack.AnimatedJavaExportFixtureTest \
  -x :debug-core:test
```

On 2026-07-24, both commands passed on Java 17.0.19. The focused unit gate
verifies delayed and negative-start transform interpolation, shadow
interpolation, per-channel text interpolation, capped pose interpolation,
shortest-path yaw, and owner-safe absolute shadow publication.

The registry-backed integration gate verifies a 1.20.4 item-display root with
retained `view_range`, width/height AABB, shadow state, teleport duration, glow
metadata, transformation, light, item predicate, and text-display passenger
state. The exact Blockbench-export gate verifies that these renderer-state
changes do not regress the unmodified exporter lifecycle or transactional CPU
generation ownership.

## Remaining acceptance

The next Animated Java display gates are:

1. render the exact pinned export through real OpenGL and compare a fixed
   default pose and settled walk frame with checked-in references;
2. require all seven custom-model-data display nodes to contribute pixels;
3. use the new typed `render.substrate.gpuResources` counters to prove buffers,
   textures, programs, framebuffers, and attachments return to a fixed baseline
   across repeated content reload and entity removal;
4. implement and separately validate glowing/team outlines before claiming
   `glow_color_override`;
5. run the same exported resource/data packs through a remote 1.20.4 server;
6. add another upstream Blockbench blueprint only when it expands the command
   or asset surface.

Passing the current tests moves display metadata and interpolation from
unimplemented to headless-verified. Animated Java remains partial until the
real-render, GPU-lifecycle, and remote-server gates pass.

The accounting substrate and transactional framebuffer/stencil allocation are
documented in [OpenGL resource-accounting evidence](2026-07-24-opengl-resource-accounting.md).
