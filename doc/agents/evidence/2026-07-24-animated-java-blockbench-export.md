<!-- Copyright (C) 2026 Jacob Repp -->

# Animated Java Blockbench export evidence

## Outcome

Animated Java's real Blockbench exporter can produce a durable Minosoft
integration fixture. On 2026-07-24, an isolated Blockbench 5.1.4 process loaded
the official Animated Java 1.10.2 release without compatibility shims, opened
the upstream 1.20.4 minimal armor-stand blueprint, and completed
`AnimatedJava.exportProject({forceSave: false, debugMode: false})` without a
plugin or export error.

This verifies the authoring/export boundary. It does not by itself verify that
Minosoft executes or renders every generated command and asset correctly.

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

This is headless transactional-runtime and CPU-generation cleanup evidence. It
does not provide a rendered reference, remote-server proof, or real-OpenGL
resource accounting.

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
display fixture and this exact export remain green together. Glow metadata is
retained but entity outlines, rendered-reference comparison, and real-GPU
accounting remain gates. See the
[display-entity semantics evidence](2026-07-24-animated-java-display-semantics.md).

Run the focused gate with Java 17:

```sh
./gradlew integrationTest \
  --tests de.bixilon.minosoft.local.datapack.AnimatedJavaExportFixtureTest
```

Do not weaken or rewrite the generated fixture if this test regresses. A
failure identifies a real importer/runtime gap: implement the bounded command,
entity, item-model, or render behavior, then rerun the same fixture.

## Rendered-reference acceptance

The current dummy renderer is not a rendered-reference oracle:

- `DummyRenderSystem.readPixels` is unimplemented;
- `DummyVertexBuffer` discards uploaded data and reports zero vertices;
- the render-graph canary proves pass ordering with procedural callbacks, not
  pixels from the Animated Java models.

Therefore a baked-model hash or headless graph trace must not be promoted as
visual acceptance. The next rendered gate must use Minosoft's real OpenGL path
and the exact pinned resource/data roots:

1. summon one armor stand at a fixed world origin;
2. use a fixed viewport, camera transform, world time, packed light, and
   background;
3. capture the initialized default pose and one named walk frame after its
   display interpolation settles;
4. require all seven custom-model-data nodes to contribute visible pixels and
   compare the captures with checked-in references using an explicit
   pixel/perceptual tolerance;
5. repeat content reload and entity removal while recording created, deleted,
   and live texture/buffer counts;
6. require the final live count to return to the pre-fixture baseline and save
   the failing frame plus resource counters on mismatch.

This gate must exercise `ItemDisplayEntityRenderer` and `ItemFeature` through
the normal entity/render pipeline. Directly rasterizing the JSON in a test-only
renderer would not prove Minosoft integration.

## Trajectory

1. Add a real-OpenGL rendered reference capture for the default pose and a
   settled known walk frame, with repeated reload/unload accounting for
   generated model textures and display meshes.
2. Validate the same exported packs through a remote 1.20.4 server boundary.
3. Add the entity glowing/team-outline pass required by
   `glow_color_override`.
4. Add another upstream blueprint only when it expands the asset or command
   surface; keep each producer and output manifest independently pinned.

Passing these gates supports Animated Java 1.10.2 exported content. It does not
turn Animated Java into a Minosoft runtime mod and does not imply compatibility
with arbitrary exporter versions.
