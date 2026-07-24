<!-- Copyright (C) 2026 Jacob Repp -->

# Content-fidelity foundation evidence

## Decision

OptiFine cannot be activated as an ordinary Minosoft mod. Its runtime patches
and links against Mojang/Forge implementation classes that are not Minosoft's
host API, and it is not a Fabric artifact that can coexist with the pinned
EMF/ETF trajectory.

The supported direction is therefore native implementation of selected content
formats and runtime semantics, exposed through exact compatibility adapters:

- OptiFine CEM (`.jem`/`.jpm`) and expression semantics through an EMF-shaped
  adapter;
- OptiFine entity texture properties, variants, and emissive passes through an
  ETF-shaped adapter;
- GeckoLib geometry/animation data through a native intermediate model, with a
  separate gate for dependent-mod controller API behavior;
- Animated Java resource-pack and datapack output as an import/runtime
  workflow, not as a client mod.

This is deliberately narrower than claiming the complete OptiFine client.
Shaders, connected textures, custom skies, CIT, and other OptiFine feature
families need their own scoped trajectories if requested.

## Implemented foundation

The first source-native slice removes three vanilla fidelity gaps shared by
future adapters:

1. `ModelFace` parses `cullface`. Baking rotates it with the blockstate and
   stores it independently from boundary `FaceProperties`.
2. `BakedModel` consults an adjacent block only when the author declared a
   `cullface`. Boundary properties remain available for neighbor occlusion,
   light selection, and AO; they no longer silently imply that the face itself
   may be culled.
3. The inherited model `ambientocclusion` value is retained on each
   `BakedFace`. This preserves the source model's choice when multipart models
   combine faces from different models.
4. `AnimationProperties` honors explicit `width` and `height`, computes a
   row-major frame grid, defaults object-frame `time` to `frametime`, and
   rejects non-positive or non-divisible frame geometry before upload.
   `SpriteAnimator` copies each frame from its computed two-dimensional origin.

The cull direction is intentionally not reused as the lighting direction.
Lighting follows the actual baked face normal; culling follows the declared
neighbor direction.

## Automated evidence

The focused Java 17 integration run passed:

```text
JAVA_HOME=/tmp/minosoft-temurin17/Contents/Home \
PATH=/tmp/minosoft-temurin17/Contents/Home/bin:$PATH \
./gradlew integrationTest -x :debug-core:test \
  --tests de.bixilon.minosoft.gui.rendering.models.BlockModelTest \
  --tests de.bixilon.minosoft.gui.rendering.models.baked.ContentFidelityBakeTest \
  --tests de.bixilon.minosoft.gui.rendering.textures.properties.AnimationPropertiesTest
```

Covered behavior:

- vanilla cube faces retain all parsed cull directions;
- boundary geometry without `cullface` remains explicitly uncullable;
- a north cull direction rotated 90 degrees around Y becomes east;
- `ambientocclusion: false` survives model baking;
- a 32×16 image divides into four 16×8 frames in row-major order;
- an object frame without `time` inherits a four-tick default.

No live visual or reload acceptance is claimed for this CPU-side slice.

## What “fully supported” requires

| Surface | Current state | Gates before a full claim |
| --- | --- | --- |
| OptiFine runtime | Out of scope | Do not stage or claim the upstream client. Name and test each native format family separately. |
| EMF / CEM | Partial | Neutral IR, JEM/JPM, version/entity aliases applied during binding, bounded per-instance expressions, targeted native-part replacement, isolated attach roots, discovery, and initial animal/humanoid skeletal binding exist. Complete alias/variable catalogs, exact part-property and complex attachment semantics, live replacement, fallback, and visual fixtures remain. |
| ETF | Partial | Property parsing, deterministic weighted variants, expanded entity/environment plus bounded nested-NBT context, discovered variant/emissive/blink materials, a generation-owned cache, and skeletal-entity base/emissive passes with state restoration exist. Complete predicate parity, player and feature-layer textures, live reload, and visuals remain. |
| GeckoLib data | Partial | Geo/animation parsing, hierarchy, cubes, pivots, transforms, box/per-face UV, loop/channel metadata, discovery, CPU geometry binding, deterministic interpolation/easing, and source-native predicate/layer controllers plus animatable caches exist. Render layers, events, automatic routing, and complete easing remain. |
| GeckoLib dependent mods | Partial | A source-native controller/cache facade now covers predicates, transitions, concurrent replace/add layers, and lifecycle ownership. It needs dependent-mod validation and render/events integration; classes compiled against GeckoLib and Mojang binary types still do not link, so exact native adapters or a separate binary bridge are required. |
| Animated Java | Partial | Resource/data packs mount into a shared session content generation; a generation-leased local runtime covers bounded functions/tags/macros/scheduling, scoreboards/storage/SNBT, selectors, an exporter-oriented execute subset, display/interaction summon, entity mutation/lifecycle, transactional failed-load rollback, and a reduced fixture pinned to exporter 1.10.2. Complete execute/data-manager semantics, interaction callbacks, an unmodified exporter fixture, and server/client plus repeated GPU-reload acceptance remain. |

Every adapter must also satisfy artifact identity, generation ownership,
headless behavior, failure isolation, last-known-good reload, GPU disposal, and
multi-version fixture gates from the graphics and mod-workflow maps.

One headless CEM/ETF/Gecko fixture now passes for both 1.19.4 and 1.20.4,
including version-specific aliases. It establishes the first multi-version
data/runtime gate; it does not discharge live rendering, visual comparison, or
GPU reload requirements.

## Ordered continuation

1. Introduce immutable, renderer-independent skeletal DTOs and parser
   diagnostics with no OpenGL dependency.
2. Convert the existing Minosoft `.smodel` loader to that intermediate
   representation first, preserving behavior with fixtures.
3. Add CEM JEM/JPM ingestion and versioned entity-part binding; then add the
   bounded expression evaluator.
4. Complete ETF selection/material passes on the same generation-owned binding,
   including feature/player textures and live fixture coverage.
5. Complete GeckoLib render layers/events and automatic routing. Validate the
   source-native controller/cache facade against dependent mods while keeping
   GeckoLib/Mojang binary compatibility as a separate, explicit rung.
6. Complete the remaining Animated Java execute/data-manager/UUID and
   interaction-callback surface, then accept an unmodified pinned exporter
   fixture. The reduced 1.10.2 lifecycle fixture is only an intermediate gate.
7. Extend the implemented CPU/content and failed-load transaction boundaries
   through render-thread bake/apply and prove repeated GPU cleanup before
   claiming runtime-complete support.

Implementation progress and the revised continuation order are recorded in
[native adapter evidence](2026-07-24-content-fidelity-native-adapters.md).

The machine-readable continuation remains
`modpacks/content-fidelity/ladder.tsv`.
