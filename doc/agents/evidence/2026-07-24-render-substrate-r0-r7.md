<!-- Copyright (C) 2026 Jacob Repp -->

# Render-substrate R0–R7 implementation checkpoint

## Scope and conclusion

This record evaluates the current implementation against the completion
function in the [render-substrate target](../backlog/render-substrate.md). It
records substantial R0–R7 implementation work, but it does **not** accept the
trajectory as complete.

Accepted predicates at this checkpoint are `SINGLE_PIPELINE`, `HEADLESS_SAFE`,
`MULTI_VERSION_SAFE`, and `LEGACY_REMOVED`. The remaining predicates are
partial or failed for the reasons in the final table. In particular:

- the Sodium-labelled backend delegates Minosoft's existing chunk core instead
  of owning pinned Sodium scheduling, meshing, upload, visibility, batching,
  and submission algorithms;
- the Iris path executes a project-owned reference pack that uses Minosoft
  attributes/includes, not a separately pinned real-world Iris/OptiFine pack;
- the R0 run did not record percentile baselines, and the measured
  Sodium-labelled p95 submission sample was slower than the later built-in
  sample; and
- the live base scene did not exercise every entity, block-entity, particle,
  and weather visual gate.

SM0–SM6 are reported complete and their useful artifacts have been absorbed
into the canonical implementation and evidence below. The duplicate support
graph and synthetic matrix were removed; support milestones do not substitute
for an R1–R7 predicate.

## Implemented substrate

The production frame now executes one immutable `RenderGraphGeneration` from
`RendererPipeline`. Stable pass IDs and explicit semantics cover sky/clouds,
opaque/cutout/emissive/translucent terrain, entities, block entities,
particles, overlays, arm/HUD, and final composite. The deleted
`WorldRendererPipeline` is not retained as a fallback.

`TerrainBackendRegistry` selects exactly one built-in or adapted backend,
rejects duplicate `(view, material)` submissions, and pins one generation lease
from asynchronous preparation through frame completion. Its bounded telemetry
reports preparation/submission median and p95 timings.

`ShaderPipelineRegistry` selects the built-in or Iris pipeline transactionally.
`IrisShaderPackPlanner` performs bounded directory/ZIP discovery, include
resolution, program/view/resource planning, semantic negotiation, and stable
fingerprinting. `IrisWorldShaderPipeline` prepares terrain, shadow, and
composite programs plus a typed shadow target before publication. The main
composite keeps its generation lease through the draw. Failed OpenGL program
reload preserves the active program; unloading a selected shader first clears
the render-system selection.

The typed main target replaced the former single-world-framebuffer compositor.
`WorldFramebuffer`, `WorldPostProcessors`, their tests, the compatibility
presentation fragment, and the old flat world pipeline are removed.

## R0 baseline

The original deterministic base command was:

```text
MINOSOFT_JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
./play.sh start client --local-world --world-generator flat \
  --world-seed 6072333650475958863 \
  --trajectory render-substrate-r0-base2-2026-07-24
./play.sh wait client.render-ready \
  --trajectory render-substrate-r0-base2-2026-07-24 --timeout 180
./play.sh debug status --role client \
  --trajectory render-substrate-r0-base2-2026-07-24
./play.sh debug visual capture /tmp/minosoft-render-substrate-r0-base2.png \
  --role client --trajectory render-substrate-r0-base2-2026-07-24 --json
./play.sh stop
./play.sh status --json
```

At frame 741 it reported 60.0287 FPS, 16,660,651 average frame nanoseconds,
and 881,847 average draw nanoseconds. The 3456×1910 capture contained 664,998
bytes. Cleanup reported no remaining parent, client, or server process.

This is a visual/average-time anchor, not an accepted percentile baseline:
median and p95 frame, preparation, and submission values were not instrumented
in R0.

## Automated verification

Focused Java 17 checks:

```text
./gradlew test -x :debug-core:test \
  --tests 'de.bixilon.minosoft.gui.rendering.graph.*' \
  --tests 'de.bixilon.minosoft.gui.rendering.terrain.*' \
  --tests 'de.bixilon.minosoft.gui.rendering.shader.pipeline.*' \
  --tests 'de.bixilon.minosoft.gui.rendering.stats.RenderTimingWindowTest' \
  --tests 'de.bixilon.minosoft.modding.loader.fabric.FabricPackPreflightTest'
./gradlew integrationTest \
  --tests 'de.bixilon.minosoft.gui.rendering.renderer.renderer.pipeline.RendererPipelineTest'
```

The focused selection passed 32 graph/resource/shader/terrain/timing tests, the
Fabric adapter tests, and 10 pipeline integration tests. It covers graph
ordering and rejection, candidate preservation, bounded timing, semantic
negotiation, generation retirement, duplicate terrain rejection, and a backend
swap during an active frame.

The broad gate also passed:

```text
./gradlew test integrationTest assemble
```

Results were 1,787 main unit tests, 2,077 integration tests, and 9
`debug-core` tests with zero failures/errors. The integration suite exercised
the repository's legacy and Pixlyzer registry fixtures through Minecraft
1.20.4; the substrate contracts contain normalized `BlockState` identities
rather than protocol palette indices.

## Real-OpenGL matrix

The post-cutover command used the exact Fabric-stack artifacts, flat seed
`6072333650475958863`, and the project reference pack:

```text
MINOSOFT_JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
MINOSOFT_SHADER_PACK=$PWD/src/integration-test/resources/render_substrate/iris-reference \
./play.sh start client --modpack fabric-stack --local-world \
  --world-generator flat --world-seed 6072333650475958863 \
  --trajectory render-substrate-r7-final-2026-07-24
./play.sh debug request render.substrate '{}' --role client \
  --trajectory render-substrate-r7-final-2026-07-24 --json
```

The combined profile built graph generation 16 with 21 passes. The Iris owner
owned the shadow and composite passes; the Sodium-labelled owner owned every
terrain pass; no duplicate submission was accepted. Disabling Iris and Sodium
produced generation 18 with the built-in shader and terrain owners and 20
passes. Re-enabling Sodium produced generation 19 without changing the frame
architecture. All observed shader/terrain generation stores reported zero
active leases and zero retired generations awaiting leases at the debug
boundary.

Post-audit captures:

| Profile | Capture | Bytes | Visual result |
| --- | --- | ---: | --- |
| Combined | `/tmp/minosoft-render-substrate-r7-final-combined.png` | 658,611 | sky/clouds, terrain, hand, hotbar, and Iris reference composite visible |
| Base | `/tmp/minosoft-render-substrate-r7-final-base.png` | 661,311 | built-in sky/clouds, terrain, hand, hotbar, and composite visible |

The earlier four-profile matrix also produced:

- `/tmp/minosoft-render-substrate-matrix-combined.png` — 1,031,117 bytes;
- `/tmp/minosoft-render-substrate-matrix-iris.png` — 1,029,323 bytes;
- `/tmp/minosoft-render-substrate-matrix-base.png` — 1,022,752 bytes; and
- `/tmp/minosoft-render-substrate-matrix-sodium.png` — 1,023,580 bytes.

The live matrix reference-pack fingerprint was
`b4dffc3a73ccb83c82fe219f8174cefa37780b3f162af0ee1e6fe14f0ffe43ec`.
The checked-in checkpoint subsequently absorbed the player-light shader-source
integration and is pinned by its focused test at
`1e7f90799bac295700a14c43021bda4d4e6cff9667df06ad25e729fce59048f6`.
Both are project fixtures: they are executable evidence for the host contract,
not the target's independent pinned real-world shader-pack evidence.

## Reload and lifecycle evidence

Trajectory `render-substrate-r6-reload-2026-07-24` used a writable copy of the
reference pack. A deliberate final-shader syntax error failed the
`mods.iris.reload-shaders` operation while generation 1 and its fingerprint
remained active and frames continued. Twenty invalid/valid cycles then reported:

```text
invalid_preserved=20
valid_published=20
unexpected=0
```

Afterward the graph was generation 36 and shader generation 21. Shader and
terrain stores both reported zero active leases and zero retired generations
awaiting leases. This verifies last-known-good program/target publication and
bounded registry retirement. It does not count every driver-side buffer,
texture, program, and target, so `LIFECYCLE_CLEAN` remains partial.

## Performance checkpoint

Trajectory `render-substrate-r7-metrics-2026-07-24` used 600-sample bounded
windows. Within one process and scene:

| Profile/sample | Median frame | p95 frame | Median terrain prepare | p95 prepare | Median submission | p95 submission |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Base, generation 18 | 1,075,917 ns | 2,256,292 ns | 37,167 ns | 81,583 ns | 90,292 ns | 130,000 ns |
| Sodium-labelled, generation 19 | 1,076,542 ns | 2,457,083 ns | 29,208 ns | 70,959 ns | 77,667 ns | 150,750 ns |
| Later base, generation 20 | — | — | 27,833 ns | 60,000 ns | 72,209 ns | 117,000 ns |

The samples show bounded counts but do not accept `PERFORMANCE_ACCEPTED`.
There is no R0 percentile baseline, and the Sodium-labelled p95 submission
sample exceeded both built-in samples.

## Completion-function state

| Predicate | State | Reason |
| --- | --- | --- |
| `SINGLE_PIPELINE` | **Accepted** | All four profiles select providers inside the same production graph and target contracts. |
| `BASE_PROFILE_ACCEPTED` | Partial | Base capture is healthy, but the deterministic scene did not visibly exercise every required entity/block-entity/particle/weather gate. |
| `SODIUM_OWNS_TERRAIN` | **Not accepted** | Selection/exclusivity is real; scheduling, meshing, upload, visibility, batching, and draw implementation still delegate the Minosoft chunk core. |
| `IRIS_OWNS_SHADER_PIPELINE` | **Not accepted** | The executable reference pack owns terrain/shadow/composite resources, but it is project-owned and Minosoft-shaped rather than an independent pinned real-world pack. |
| `IRIS_SODIUM_COMPOSE` | Partial | Reference-path composition and no-duplicate submission are verified; the two full upstream contracts are not. |
| `TRANSACTIONAL_RESOURCES` | Partial | Graph/program/target publication is last-known-good; texture and physical vertex-layout generation coverage is incomplete. |
| `LIFECYCLE_CLEAN` | Partial | Twenty invalid/valid cycles and store counts pass; complete GPU object accounting is absent. |
| `HEADLESS_SAFE` | **Accepted** | Graph, resource, planner, negotiation, timing, and provider tests pass without OpenGL. |
| `MULTI_VERSION_SAFE` | **Accepted** | The boundary is version-normalized and the broad multi-version registry/integration suite passes. |
| `PERFORMANCE_ACCEPTED` | **Not accepted** | R0 percentiles are missing and Sodium-labelled p95 submission was slower. |
| `LEGACY_REMOVED` | **Accepted** | Old pipeline/compositor classes, tests, resource, and production call sites are removed. |
| `DOCUMENTATION_TRUTHFUL` | Partial | Current maps are corrected by this checkpoint; completion remains contingent on the open predicates above. |

Therefore `RENDER_SUBSTRATE_COMPLETE = false`.
