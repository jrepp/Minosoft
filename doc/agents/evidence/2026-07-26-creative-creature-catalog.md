<!-- Copyright (C) 2026 Jacob Repp -->

# Creative creature catalog — 2026-07-26

## Scope

Creative player inventories have proportional `Items`, `Creatures`, and
`Recipes` workspaces. The creature tab searches the active entity registry,
previews real baked skeletal content with retained clips, and requests an
authoritative server spawn.

## Stable behavior

- `CreativeInventoryCatalogElement` owns tab selection, focus, input routing,
  and cleanup. `CreativeInventoryLayout` expands the catalog to at most 42% of
  viewport width and 72% of height (bounded at `340×330`) and centers it with
  the fixed vanilla slot panel. Compact windows retain `170×166`; vanilla slot
  coordinates are never stretched. It is attached only in Creative mode.
- JEI's owned recipe viewer is a `Recipes` tab in Creative mode and remains a
  standalone container extension in Survival. Recipe browsing no longer
  competes with creature/item content in one panel.
- `CreativeCreatureCatalogElement` derives entries from the session entity
  registry and keeps only types for which `SkeletalLoader.previewModel` resolves
  an actual baked entity route with geometry. It does not keep a second
  version-specific creature list and does not advertise abstract entities,
  projectiles, or the unrelated diagnostic living fallback.
- Search matches localized label, path, or namespaced identifier and reuses the
  bounded `CreativeCatalogPager` behavior.
- The model bake retains raw emitted cuboids plus bone IDs, UVs, materials, and
  source textures from the production skeletal mesh. A lightweight
  `SkeletalPreviewPlayer` evaluates the baked clip map at 20 Hz and applies the
  same transform hierarchy on the CPU. The selector below the render window
  can return to neutral pose or play any retained clip. Because it is an
  inspection control, selected clips receive a preview-local loop override;
  production one-shot/hold semantics remain unchanged. Preview expression
  contexts advance both `query.anim_time` and `q.anim_time` aliases plus bounded
  movement/head defaults, and normalized unique bone names bridge
  snake_case/camelCase authoring differences.
- The preview completes an idle orbit every 20 seconds. Primary-button
  horizontal dragging adjusts yaw and pauses the idle orbit until release.
  `Show default rotation` returns to authored yaw zero and holds that view;
  dragging exits the hold and resumes the idle orbit after release. Projection
  remains CPU/painter sorted and owns no world entity, framebuffer, or GPU mesh.
- Preview texture resolution follows the baked generation's independent ETF
  material layers and GeckoLib dependent-mod selector. Selectors receive the
  exact entity identifier, while unavailable runtime state uses declared
  defaults. Transactional content reload replaces the baked model reference and
  invalidates the GUI cache on the next tick. Changing between entity types
  invalidates the preview even when they intentionally share one geometry
  object; Naturalist canary/cardinal/bluejay therefore retain distinct exact
  textures.
- Gecko/Bedrock bone and animation rotations are converted to the renderer's
  right-handed convention. Joint matrices use column-vector order
  `T(pivot) · R · S · T(-pivot)`. The previous inverse order rotated geometry
  around the wrong point and was the common cause behind detached bird tails,
  backward feet, scattered boar limbs, butterfly parts, and caterpillar
  antennae.
- Gecko box UV binding maps Bedrock's authored negative-Z front face to north
  and applies mirrored U coordinates. This fixes the common reversed eyes,
  knees, body, and leg textures across ducks, elephants, giraffes, hippos, and
  Naturalist birds. Zero-thickness wing/fin/tail planes within a quarter source
  pixel snap to their hinge and overlap it by `0.05` source pixels while UV
  dimensions remain unchanged.
- The exact pinned Naturalist adapter applies fail-closed, in-memory corrections
  for its bird toe planes, boar ridge face UVs, catfish whisker height, and
  caterpillar/firefly antenna attachment. The third-party JAR is not rewritten.
- Naturalist's zebra is a native `HorseEntityModel` renderer, not the ostrich
  Gecko mesh that happens to be the only otherwise-unrouted geometry in the
  pinned artifact. The adapter now emits the exact adult default horse cuboids
  against Naturalist's 64×64 zebra atlas, omits conditional chest/saddle parts,
  retains the shipped idle/walk control-bone names, and excludes the two-legged
  winged ostrich asset.
- Sheep's native body joint is aligned with its legs. Unsheared sheep add a
  separately retained, dye-tinted wool feature whose body/head/leg cubes use
  vanilla inflation (`1.75`, `0.6`, `0.5`) to avoid coplanar z-fighting.
  Sheared state disables that feature. The catalog exposes `Sheep (Woolly)` and
  `Sheep (Sheared)` and appends only fixed `{Sheared:0b}`/`{Sheared:1b}` summon
  state.
- `Spawn` is enabled only when the server-provided command tree grants a direct
  `/summon` command. A click sends that command through the normal version-aware
  chat/command transport at an absolute position three horizontal blocks ahead
  of the player. There is no local entity mutation or permission bypass.

## Validation

- `CreatureSpawnCommandTest` covers placement, invalid distances, command
  authority, and fixed sheep variant NBT.
- `CreativeInventoryLayoutTest`, `CreaturePreviewRotationStateTest`, and
  `SkeletalPreviewPlayerTest` cover proportional bounds, orbit/drag
  normalization, clip advancement, and neutral reset.
- `TransformInstancePivotTest` proves a rotated joint leaves its pivot
  invariant. `SkeletalModelBinderTest` covers Gecko rotation conversion,
  front/back and mirrored box UVs, hierarchy binding, and plane hinge
  snap/overlap.
- `NaturalistCompatibilityAdapterTest` proves exact bluejay, canary, and
  cardinal texture selection despite their shared bird geometry plus the
  bounded pinned-geometry corrections. It also parses the zebra native bridge,
  requires four leg cubes and no wing bones, and attaches every control-bone
  name used by both shipped zebra clips.
- `SkeletalLoaderTest` covers non-empty retained preview geometry for native and
  Gecko-adapted models, exact Gecko entity routing, and generation-baked preview
  texture selection.
- `./gradlew compileKotlin`
- `./gradlew :test --tests
  de.bixilon.minosoft.gui.rendering.gui.gui.screen.container.inventory.CreatureSpawnCommandTest`
- `./gradlew :integrationTest --tests
  de.bixilon.minosoft.gui.rendering.models.loader.SkeletalLoaderTest`

## Live acceptance

The supervised `fabric-stack` run retained one ready server while hot-reloading
the client. Fullscreen capture proved the proportional item workspace and
separate tab headers. Creature captures before the central matrix correction
provided the disconnected boar/bird reference; the mathematical pivot
regression and focused adapter tests now guard the common defect independently
of a particular camera angle. The final zebra rotation correction activated as
client generation 8 while retaining parent PID `38738` and server PID `38774`;
the new client PID `78359` joined and reached render readiness without a
content parse/bake failure. This is topology/runtime evidence; a checked
zebra pixel reference remains open. A remaining interaction gate should exercise
tab/row/drag/animation/spawn through semantic GUI input once the debug plane
owns GUI coordinate conversion; current raw mouse injection deliberately does
not.

The catalog does not claim arbitrary user-supplied entity NBT or geometry for
registry types without a routed skeletal model.
