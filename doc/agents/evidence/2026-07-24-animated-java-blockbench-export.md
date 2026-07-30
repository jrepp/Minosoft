<!-- Copyright (C) 2026 Jacob Repp -->

# Animated Java Blockbench export evidence

## Outcome

Animated Java's real Blockbench exporter can produce a durable Minosoft
integration fixture. On 2026-07-24, an isolated Blockbench 5.1.4 process loaded
the official Animated Java 1.10.2 release without compatibility shims, opened
the upstream 1.20.4 minimal armor-stand blueprint, and completed
`AnimatedJava.exportProject({forceSave: false, debugMode: false})` without a
plugin or export error.

This verifies the authoring/export boundary. The checked-in fixture now also
passes Minosoft's headless lifecycle gate and a real-OpenGL default-pose,
four-generation summon/reload/remove loop described below. Checked visual
references, a settled walk frame, remote-server behavior, and broader
blueprints remain separate gates.

## Pinned inputs

| Input | Identity |
| --- | --- |
| Blockbench | `5.1.4`, `Blockbench_arm64_5.1.4.zip`, SHA-256 `f77037d42226e281caf88e2b989b281b97e84441a067c48d31e86cf1b2d95f10` |
| Animated Java | `1.10.2`, official `animated_java.js`, SHA-256 `81aadc4def796d97dab6642ad05b564b470ecadcaf455c8cc5826c9e24759672` |
| Animated Java source | tag `v1.10.2`, commit `a5fc548d2a53cc0887fa070db33ccfcef1cd3541` |
| Blueprint | `test-packs/1.20.4/blueprints/armor_stand_minimal_1.20.4.ajblueprint`, SHA-256 `d5802d0c275f27cfab33c09914b5dbdc1477ecbe35e49fba6f49e4ee7079a03e` |
| Target | Minecraft Java Edition `1.20.4` |

Blockbench 5.1.5 was not substituted for 5.1.4: the pinned Animated Java
release expects Blockbench's 5.1.4 plugin namespace. The plugin artifact must
retain the filename `animated_java.js`, because Blockbench derives the plugin
identifier used by Animated Java's own patch dependencies from that filename.

## Repeatable export protocol

1. Start the exact Blockbench 5.1.4 application with a clean, isolated user-data
   directory. Disable automatic updates in that profile and relaunch it before
   loading the fixture.
2. Load the official `animated_java.js` release artifact as a local Blockbench
   plugin. Do not use a locally rebuilt bundle as equivalent evidence unless
   its changed identity is recorded separately.
3. Open the pinned upstream `.ajblueprint`.
4. In Animated Java's project settings, select two empty, absolute output
   directories: one resource-pack root and one data-pack root.
5. Invoke the normal export action. The automated probe used the same public
   plugin entry point as the UI:

   ```javascript
   await AnimatedJava.exportProject({forceSave: false, debugMode: false})
   ```

6. Require a completed export with no Blockbench/plugin errors. Preserve the
   two output roots exactly; do not normalize, prune, or hand-edit generated
   files.
7. Record the input hashes, output counts, and deterministic output-manifest
   hash in `fixture.json`.

The successful probe controlled the isolated Electron application through its
Chromium debugging endpoint. That is a test harness detail, not a runtime
Minosoft dependency.

## Captured output

The unmodified output is checked in at
`src/integration-test/resources/content_fidelity/animated_java-1.10.2-export/`.
It contains:

- 13 resource-pack files, including seven generated cuboid models, the source
  texture, the item override, atlas metadata, and pack metadata;
- 109 data-pack files, including 102 generated `.mcfunction` files;
- a deterministic manifest SHA-256 of
  `d4ae9d007aeb6c736255de8738e5c63c54d6e2b9a0c0f6fbb3dfb5c2d5192c11`.

The manifest is the SHA-256 of sorted lines in the form
`<file-sha256>  <relative-path>\n` for every regular file below `resources/`
and `datapacks/`. `fixture.json` is provenance and is deliberately excluded
from that manifest.

## Minosoft acceptance boundary

The reusable producer-versus-consumer classification and recapture rules are
specified in the
[Blockbench producer-integration protocol](../acceptance/blockbench.md).

`AnimatedJavaExportFixtureTest.runs unmodified 1_10_2 Blockbench export`
protects the next boundary. It:

- verifies the complete generated-output manifest before loading it;
- mounts the generated resource and data namespaces separately;
- resolves all seven generated models and their texture;
- runs the exporter's load and summon path with its macro argument;
- checks the item-display root, seven passenger nodes, and custom-model-data
  values `2..8`;
- starts the generated walk animation and requires frame advancement;
- runs the generated removal function and requires owned entities to disappear.

The first run exposed three real differences between the reduced fixture and
the generated output. Minosoft now:

- accepts the trailing collection commas emitted in the exporter's summon
  SNBT;
- creates a missing list target for `data modify ... append`;
- treats missing data sources and incomplete function-macro compounds as
  command failure, including rejecting a macro function atomically before any
  of its commands execute.

After those source-native fixes, the complete focused gate passed on Java
17.0.19. Both the reduced lifecycle fixture and the unmodified exporter fixture
passed. This verifies headless asset discovery and the generated
load/summon/walk/remove command lifecycle.

The same unmodified fixture also passes eight consecutive content-generation
replacements, a deliberately rejected load generation, and recovery. Across
those swaps:

- the summoned root and all seven passengers retain object identity, entity
  ID, UUID, and command-visible hierarchy;
- the generated walk function continues advancing after each runtime swap;
- a rejected candidate rolls command/entity state back and leaves the
  last-known-good runtime generation active;
- every retired CPU content generation closes exactly once after its runtime
  lease moves, while the active generation remains owned until store shutdown.

This is specifically the headless transactional-runtime and CPU-generation
cleanup evidence. It does not provide the later real-OpenGL evidence, a checked
rendered reference, or remote-server proof.

The fixture's `custom_model_data` path now runs on the separately audited
Minecraft 1.20.4 legacy item-predicate selector. That selector also has
stack/live-world coverage for every registered 1.20.4 provider beyond this
fixture, including live world/display-item model replacement. The exported
armor stand itself proves only its custom-model-data branch; it does not prove
later component dispatch or rendered parity. Remote active-hand timing is
covered separately by 1.19.4 and 1.20.4 entity fixtures. See
the [item-model predicate evidence](2026-07-24-item-model-predicates.md).

The display renderer boundary is also separately audited against mapped
Minecraft 1.20.4 behavior. Shared item/block/text view-range rejection,
width/height visibility bounds, transform and absolute shadow interpolation,
text background/opacity interpolation, negative interpolation-start deltas,
and capped teleport pose interpolation have focused tests. The registry-backed
display fixture and this exact export remain green together. Glowing/team
outlines now have headless semantic/geometry tests and live-GL shader,
framebuffer, render-graph, and reload evidence. The exact non-glowing export
also has platform-qualified default/walk references; glowing outline pixels
and rejected-reload real-GPU recovery remain gates. See the
[display-entity semantics evidence](2026-07-24-animated-java-display-semantics.md).

Run the focused gate with Java 17:

```sh
./gradlew integrationTest \
  --tests de.bixilon.minosoft.local.datapack.AnimatedJavaExportFixtureTest
```

Do not weaken or rewrite the generated fixture if this test regresses. A
failure identifies a real importer/runtime gap: implement the bounded command,
entity, item-model, or render behavior, then rerun the same fixture.

## Real-OpenGL automation capability

The client debug channel now provides the two render-queue operations needed
by a real-GPU Blockbench loop:

- `render.reload-content` runs the same
  `FabricResourceReloadEvents.CONTENT_FIDELITY` transaction and
  `SkeletalLoader.reloadContentFidelity` apply path as the production
  `reload content` command, then returns the published generation;
- `render.substrate` reports typed created/deleted/live OpenGL names for
  buffers, vertex arrays, textures, renderbuffers, framebuffers, shader
  objects, programs, and queries.

On 2026-07-24 an isolated Java 17 local-world launch of the `content-fidelity`
pack exposed both operations in `core.capabilities`. A direct reload published
content generation 2, and a supervised source change activated client
generation 2 with exactly one new endpoint. The operation remained callable
after replacement, and stopping the supervisor removed the endpoint.

On 2026-07-25 a supervised void-world launch mounted the exact export through
the managed `content-fidelity` pack. `Play` validated its output-manifest hash
and file counts, staged the two roots transactionally in the out-of-source
modpack store, and mounted them through the normal session asset/data-pack
managers. The bounded local-only `content.execute-local` operation then invoked
`aj:armor_stand_minimal/summon` through the production
`SessionDataPackRuntime`, positioned the normal player camera, and produced the
root plus seven item-display bones with custom-model-data values `2..8`.

The first repeated captures exposed apparent joint z-fighting: four pixel
variants alternated across the head/shoulder, torso/waist, and leg/base
intersections. The export did not contain duplicate body parts.
`EntitiesRenderer` collected independent display renderers in parallel, while
the final drawable comparator returned equality for equal feature class and
distance. With `LESS_OR_EQUAL`, collection order selected the visible
coplanar fragment. A stable entity-identity tie-breaker in
`FeatureDrawable`/`EntityDrawer` removed that nondeterminism. Ten successive
500×500 default-pose crops then had zero differing pixels.

Four consecutive production content reloads advanced through generations 2–5.
Each subsequent summon executed seven generated commands, and removal returned
to the player-only state. This uncovered and fixed a second lifetime bug:
retiring the initial content-generation cleanup also unloaded the session asset
and data-pack managers. `SessionAssetsCandidate` now transfers those managers
to session ownership at commit; content snapshots retire independently, and
session disconnect/error cleanup unloads the managers explicitly. The
default-pose capture after four reloads still had zero differing pixels.

The fixture held at 311 live GPU objects (176 buffers and 88 vertex arrays)
while summoned. Removal returned to the warmed baseline family
(`269/148/74`, with transient HUD/chunk values up to `272/150/75`) without
cumulative growth across the four generations. The outline texture,
framebuffer, renderbuffer, shader, and program counts were also stable. The
latest run emitted no new resource-leak warning, stopped cleanly, and removed
its endpoint.

The platform-qualified checked scenario now clears transient GUI overlays,
disables the HUD, requires a `3456x1910` physical framebuffer, and crops
`[1100,900,550,550]`. The default pose and static `walk` frame 10 both match
checked references with zero tolerance. The walk frame is compared a second
time after production content reload. The scenario passed twice on the
documented macOS/Apple M4 Max target, including after deliberately opening the
pause menu before the run.

Allocation-stack diagnostics also found that previously reported buffer
finalizers came from replaced `CloudArray` meshes, not the Animated Java item
features. Cloud grids now unload only their outgoing edge, every array releases
its mesh, retired layers drain once, and renderer shutdown releases remaining
layers. The cleanup scenario removes the generated hierarchy, moves the camera
512 blocks and back to replace all cloud arrays, and verifies only the player
remains. Two forced collections then emitted no new finalizer or double-unload
warning.

The separate `animated-java-rejected-reload.json` lane exercises two bounded
failure points in the production transaction. Rejection after upload and
rejection after texture/model publication each allocated then retired 16
buffers and eight vertex arrays, with zero live delta for every tracked OpenGL
type. Both retained content generation 1 and the exact walk-frame pixels.
Mounted remove/summon functions remained callable, and the following accepted
reload published generation 3 (generation 2 was the rejected publication
candidate).

The current combined `content-fidelity-completion` trajectory revalidated all
four live lanes against the final mounted fixture set:

```text
animated-java-render-reference-2026-07-26T22-57-34-491563Z-58113
animated-java-rejected-reload-2026-07-26T22-58-01-312756Z-58373
animated-java-render-cleanup-2026-07-26T22-58-39-584328Z-58733
emf-etf-zombie-render-reference-2026-07-26T22-58-25-400271Z-58608
```

Because both managed fixtures were mounted, each rejected candidate in this
combined run retired the complete 33-object content set: 21 buffers, 11 vertex
arrays, and one texture, with zero live delta. An initial reference attempt
correctly failed when macOS focus loss reopened a pause/settings screen after
the first capture. Multi-capture scenarios now invoke
`visual.prepare-reference` immediately before every checked screenshot; the
unchanged baselines then passed at zero tolerance. Clean shutdown removed the
parent/client PIDs and all debug endpoints.

This proves exact-pack mounting, production local command execution,
platform-qualified default/walk rendering, deterministic joint ordering,
positive and rejected reload/recovery, entity removal, and bounded GPU
cleanup. It does not prove another driver, glowing outline pixels, or the
remote-server boundary.

The reusable live commands are:

```sh
./play.sh dev client --local-world --world-generator void \
  --modpack content-fidelity \
  --trajectory animated-java-render-reference

./play.sh debug request content.execute-local \
  '{"function":"aj:armor_stand_minimal/summon","arguments":{"args":"{}"},"origin":{"x":0.5,"y":20.0,"z":0.5,"yaw":0.0,"pitch":0.0},"camera":{"x":0.5,"y":19.5,"z":3.5,"yaw":180.0,"pitch":0.0}}' \
  --role client --trajectory animated-java-render-reference --json

./play.sh debug request render.reload-content \
  --role client --trajectory animated-java-render-reference --json

./play.sh debug request render.substrate \
  --role client --trajectory animated-java-render-reference --json
```

See [OpenGL resource-accounting evidence](2026-07-24-opengl-resource-accounting.md)
for lifecycle ownership and the remaining baseline protocol.

## Rendered-reference acceptance

The current dummy renderer is not a rendered-reference oracle:

- `DummyRenderSystem.readPixels` is unimplemented;
- `DummyVertexBuffer` discards uploaded data and reports zero vertices;
- the render-graph canary proves pass ordering with procedural callbacks, not
  pixels from the Animated Java models.

Therefore a baked-model hash or headless graph trace must not be promoted as
visual acceptance. `animated-java-render-reference.json` and
`animated-java-rejected-reload.json` are the accepted real-OpenGL lanes. Each
summons at a fixed origin, settles display interpolation, compares checked
walk crops, reloads through `render.reload-content`, compares again, samples
typed resource accounting, and removes the hierarchy; the reference lane also
checks all seven custom-model-data passengers through `client.entities`. The
rejection lane additionally proves rollback
after candidate upload and after lookup publication before accepted recovery.

The checked files are deliberately named for their macOS Retina framebuffer.
They prove `ItemDisplayEntityRenderer` and `ItemFeature` through the normal
entity/render pipeline on that target; they are not a portable raster oracle.
Other platforms should add independently named references rather than weaken
the zero-tolerance baseline.

## Trajectory

1. Repeat the same accepted/rejected/recovery lane on another real driver.
2. Validate the same exported packs through a remote 1.20.4 server boundary.
3. Capture explicit glowing override/team-color cases and require the final
   typed resource counts to return to a quiescent pre-fixture baseline.
4. Add another upstream blueprint only when it expands the asset or command
   surface; keep each producer and output manifest independently pinned.

Passing these gates supports Animated Java 1.10.2 exported content. It does not
turn Animated Java into a Minosoft runtime mod and does not imply compatibility
with arbitrary exporter versions.
