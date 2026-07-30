<!-- Copyright (C) 2026 Jacob Repp -->

# Animated Java display-entity semantics evidence

## Outcome

Minosoft now has a valid headless integration gate for the display-entity
surface exercised by the pinned Animated Java 1.10.2 Blockbench export. The
exact, unmodified armor-stand export still passes its manifest/model discovery,
load, summon, walk, removal, repeated replacement, rollback, stable-identity,
and CPU-generation cleanup checks after the display renderer changes described
below.

The headless portion is runtime and renderer-state evidence. A separate
platform-qualified real-OpenGL scenario now mounts the exact exported fixture
and compares checked default and static walk-frame pixels; it does not imply
pixel identity on other drivers or platforms.

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

Entity flag bit `0x40` enables the vanilla glowing effect. Display
`glow_color_override` selects the outline color but does not enable glowing.
Without an override, a scoreboard/player team color is used, then white as the
fallback. Bit `0x20` remains invisibility and is independent of glowing.

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

`EntityOutlineRenderer` now owns the canonical
`minosoft:scene/entity-outlines` world-overlay pass. It draws eligible entity
geometry into a texture-only color mask without depth testing, then composites
a one-physical-pixel external edge into the main framebuffer. Mask
framebuffers resize transactionally and their texture uses clamped sampling.
Block, item, text-display, EMF/ETF skeletal, Gecko skeletal/layer, Gecko armor,
and emissive geometry contribute to the mask. Names, hitboxes, shadows, and
leashes deliberately do not.

Normal and outline eligibility are collected independently. Occluded glowing
geometry can therefore contribute an outline, while invisible glowing geometry
does not leak into the normal entity layers. The color-only
`glow_color_override` on the exact upstream fixture remains dormant because
that export does not set the glowing flag.

## Automated evidence

Run with Java 17:

```sh
./gradlew :test \
  --tests de.bixilon.minosoft.data.entities.entities.display.DisplayTransformationTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.effect.EntityRenderEffectsTest \
  -x :debug-core:test

./gradlew integrationTest \
  --tests de.bixilon.minosoft.data.entities.entities.EntityTest \
  --tests de.bixilon.minosoft.local.datapack.LocalDisplayEntityFactoryTest \
  --tests de.bixilon.minosoft.local.datapack.AnimatedJavaExportFixtureTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.draw.EntityDrawerTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineFeatureTest \
  --tests de.bixilon.minosoft.gui.rendering.entities.outline.EntityOutlineRendererTest \
  -x :debug-core:test
```

On 2026-07-25, both commands passed on Java 17.0.19. The focused unit gate
verifies delayed and negative-start transform interpolation, shadow
interpolation, per-channel text interpolation, capped pose interpolation,
shortest-path yaw, and owner-safe absolute shadow publication.

The registry-backed integration gate verifies a 1.20.4 item-display root with
retained `view_range`, width/height AABB, shadow state, teleport duration, glow
metadata, transformation, light, item predicate, and text-display passenger
state. The exact Blockbench-export gate verifies that these renderer-state
changes do not regress the unmodified exporter lifecycle or transactional CPU
generation ownership. It also proves that an override-bearing unmodified
export remains non-glowing, while the registry fixture proves explicit
`Glowing:1b`, override color, scoreboard-team color, invisible/occluded
selection, outline prepare/draw, mask/composite lifecycle, and cleanup.

## Live OpenGL evidence

On 2026-07-25 a supervised Java 17 local-world launch of the
`content-fidelity` pack initialized the composite shader, the mask texture and
framebuffer, and the registered outline pass. The first probe found that the
flashing block shader inherits the block outline uniform; adding the matching
solid-mask branch made the next clean launch ready without shader, uniform, or
framebuffer errors.

`render.substrate` reported the outline pass as
`minosoft:scene/entity-outlines`, `world_overlay`, order `4000`, in a
21-pass graph. After three consecutive `render.reload-content` operations,
content generations advanced through 2, 3, and 4 while the relevant resource
classes remained stable: textures `13`, renderbuffers `1`, framebuffers `3`,
programs `30`, shader objects `0`, and queries `134`. Buffer and vertex-array
counts changed by normal live-world mesh activity. A supervised stop removed
the client endpoint.

The exact fixture now also has checked 550x550 macOS/Apple M4 Max references
for its default pose and static walk frame 10. Both compare with zero tolerance,
and the walk reference remains exact after a production content reload. The
scenario checks all seven item-display custom-model-data nodes before capture,
clears transient GUI/HUD state, removes the hierarchy, and verifies typed
buffer/vertex-array accounting fields.

The cleanup diagnostic exposed an unrelated cloud-grid buffer leak through
allocation stacks. After explicit `CloudArray` mesh disposal and corrected
grid/layer ownership, entity removal plus a 512-block camera jump and two
forced collections emitted no new GPU finalizer warning. This proves the
fixture's platform-qualified pixels and positive cleanup path. The separate
two-checkpoint rejection lane now also preserves exact walk pixels, mounted
functions, and zero live GPU delta through upload rejection, publication
rollback, and accepted recovery. It does not prove the outline's pixels, a
glowing instance of the exact exported model, or a universal cross-driver
quiescent full-resource baseline.

## Remaining acceptance

The next Animated Java display gates are:

1. capture a glowing override and scoreboard-team case through real OpenGL and
   compare their one-pixel external outlines with checked-in references;
2. repeat the typed rejected-candidate accounting and a quiescent fixed
   baseline on another driver;
3. run the same exported resource/data packs through a remote 1.20.4 server;
4. add another upstream Blockbench blueprint only when it expands the command
   or asset surface.

Passing the current tests moves display metadata and interpolation from
unimplemented to headless-verified and moves glowing outlines to
headless-verified plus live-GL lifecycle-verified. The exact Animated Java
default/walk positive and rejected/recovery loops are platform-qualified;
Animated Java remains partial until outline pixels, another driver, and
remote-server gates pass.

The accounting substrate and transactional framebuffer/stencil allocation are
documented in [OpenGL resource-accounting evidence](2026-07-24-opengl-resource-accounting.md).
