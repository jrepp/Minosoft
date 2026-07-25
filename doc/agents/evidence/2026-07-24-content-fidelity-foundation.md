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
| EMF / CEM | Partial | Neutral IR, JEM/JPM, entity/version aliases applied during binding, native aliases for the five shipped player/zombie/cow/pig/sheep rigs, the EMF 3.0.17 public expression input-name catalog, the audited numeric/boolean method surface, raw `nbt(key,query)` predicates, retained shadow/leash outputs, a partial live-value surface, bounded per-instance expressions, targeted native-part replacement, isolated attach roots, discovery, generation-leased stable texture slots, and transactional model reload exist. Broader entity aliases, exact live semantics for every catalogued input, exact absolute part-property and complex attachment semantics, shadow/leash renderer consumption, exact diagnostics, fallback, and visual fixtures remain. |
| ETF | Partial | Property parsing, deterministic weighted variants, expanded entity/environment plus bounded entity/client-player/vehicle NBT context, NBT existence/inversion/range/wildcard-path/pattern queries, negated string sets, semantic Minecraft-version ranges, calendar/world/client values, biome tags, pack-scoped active-mod IDs, equipment keywords and item IDs, general mob variants, panda genes, llama inventory strength, horse jump and movement attributes, percent health, ordinary-entity spawner state, and predicate-gated vertical block identifiers exist. The pinned bytecode establishes that `blocks` and misleadingly named `blockSpawned` both inspect the current and immediately lower block; both now retain ETF's colon-separated state-subset grammar. Solid vertical probes use the `solid_render`-derived full-opacity flag matching the pinned `isOpaqueFullCube` call. Regional difficulty follows the vanilla 1.20.4 client formula with the client-side zero inhabited-time fallback, and the generation-owned cache carries prior rule/suffix selection into dependent feature predicates. Configured emissive suffixes, blink/blink2 timing, generation-owned caches, independently selected skeletal body/feature materials, and full-bright emissive passes exist. ETF 7.0.13 control pixels on 64×64 player skins derive blink/blink2, matching emissives, coat styles/lengths, moved-coat base edits, coat emissives, fat-coat inflation, leggings suppression, forced lower-skin opacity, legacy and controller-selected villager noses, five textured nose layouts, removal edits, nose emissives, marker-selected animated glint masks, and profile-controlled ETF-only base transparency. Source-keyed dynamic textures feed world rendering and the shared first-person base/blink state. Content and native entity models join transactional catalog/material replacement; dead content-owned texture holes are reused and trailing layers compact without renumbering live meshes. Broader non-skeletal and block-entity feature bindings, configuration, real-GL validation, and visual fixtures remain. |
| GeckoLib data | Partial | Geo/animation parsing, hierarchy, cubes, pivots, transforms, box/per-face UV, loop/channel metadata including retained custom loop names, sound/particle/custom-instruction keyframe events, discovery, CPU geometry binding, deterministic interpolation, the pinned 4.4.4 built-in easing catalog and first easing argument, owner-scoped custom easing/loop registration, source-native predicate/layer controllers, and retained-model transactional reload exist. Stable content identities route entities, block entities, items, and armor to retained geometry. Generic object animatables resolve their exact snapshot content into headless typed managers. Generation-baked, owner-scoped opaque/translucent/additive texture passes evaluate entity-state predicates and restore render state. Matching named controller layers migrate clip/raw-queue time, transition/trigger state, and fired-event position across retained model replacement. Events dispatch after finalized bone transforms to native positional audio, registered particle factories with locator support, and owner-scoped custom listeners. Content-generation leases protect stable texture coordinates while holes compact. GUI item views, exact armor fitting, dependent-mod validation, real-GL validation, and visual fixtures remain. |
| GeckoLib dependent mods | Partial | A source-native controller/cache/event facade covers predicates, transitions, concurrent replace/add layers, state-driven speed/easing overrides, triggerable animations, typed sound/particle/custom handlers that preserve locator and `pre_effect_script` data, owned listener/easing/loop/render-layer registration, explicit content-identity entity routing, compatible controller-state migration, lifecycle ownership, and bounded `RawAnimation` play/once/hold/loop/wait/repeat queues with delta carry. The generic-object manager layer adds typed data tickets, first-tick/update time, per-ID triggers, compatible snapshots, shared instanced ownership, and a bounded singleton LRU with deterministic close. Finished raw identity, explicit reset, current-stage queries, and trigger-driven base queue reload follow the audited 4.4.4 controller contract. It still needs real dependent-mod validation; classes compiled against GeckoLib and Mojang binary types do not link, so exact native adapters or a separate binary bridge are required. |
| Animated Java | Partial | Resource/data packs mount into a shared session content generation; a generation-leased local runtime covers bounded functions/tags/storage and entity macros/scheduling, scoreboards/storage/SNBT, selectors, exporter-used score comparisons and entity-data predicates/sources, target/attacker relationships, display/interaction summon, entity mutation/lifecycle, and transactional failed-load rollback. Local attack/interact packets dispatch the pinned exporter's reward functions as the player. An upstream-shaped fixture proves entity macro callback dispatch and exact signed four-word UUID-to-string conversion against fingerprinted Animated Java 1.10.2 compiler templates. The reduced lifecycle fixture remains separate. An unmodified compiled exporter fixture, any additional command surface it exposes, and server/client plus repeated GPU-reload acceptance remain. |

Every adapter must also satisfy artifact identity, generation ownership,
headless behavior, failure isolation, last-known-good reload, GPU disposal, and
multi-version fixture gates from the graphics and mod-workflow maps.

One headless CEM/ETF/Gecko fixture now passes for both 1.19.4 and 1.20.4,
including version-specific aliases. It establishes the first multi-version
data/runtime gate; it does not discharge live rendering, visual comparison, or
GPU reload requirements.

## Ordered continuation

1. Extend EMF entity/version aliases beyond the five shipped skeletal rigs,
   finish exact live-input and absolute part-property semantics, consume
   shadow/leash outputs, and add complex-attachment and visual fixtures.
2. Complete broader non-skeletal and block-entity ETF feature bindings, then
   finish configuration plus live visual fixtures on the existing
   generation-owned material path.
3. Validate the source-native GeckoLib
   entity/block-entity/item/armor controller/cache/event/easing/loop and
   render-layer paths against dependent mods. Add GUI item views and exact
   armor fitting while keeping GeckoLib/Mojang binary
   compatibility as a separate, explicit rung.
4. Run an unmodified compiled Animated Java 1.10.2 export and close any
   additional command surface it exposes. The fingerprinted compiler-template
   audit plus upstream-shaped interaction and UUID tests are stronger
   intermediate gates, but are not a full exporter acceptance fixture.
5. Prove the stable-slot compactor and handle replacement under repeated
   real-OpenGL reload/unload before claiming runtime-complete support.
6. Expand the multi-version fixture matrix and add rendered reference captures,
   live remote-server acceptance, and failure/reload diagnostics before moving
   any partial capability to fully supported.

Implementation progress and the revised continuation order are recorded in
[native adapter evidence](2026-07-24-content-fidelity-native-adapters.md).

The machine-readable continuation remains
`modpacks/content-fidelity/ladder.tsv`.
