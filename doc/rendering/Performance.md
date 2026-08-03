<!--
  Minosoft
  Copyright (C) 2026 Jacob Repp

  This program is free software: you can redistribute it and/or modify it under
  the terms of the GNU General Public License as published by the Free Software
  Foundation, either version 3 of the License, or (at your option) any later
  version.

  This program is distributed in the hope that it will be useful, but WITHOUT
  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
  FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.

  You should have received a copy of the GNU General Public License along with
  this program. If not, see <https://www.gnu.org/licenses/>.
-->

# Render performance and OpenGL submission

This document describes the production render path as implemented on 2026-08-02.
It is an implementation map and measurement queue, not a claim that every
implemented optimization has a measured speedup. Durable acceptance rules and
retained measurements live in the [graphics evidence map](../agents/areas/05-graphics.md)
and the [performance-measurement protocol](../agents/acceptance/scenarios.md#performance-measurement-guidance).

## Frame and submission shape

`RendererPipeline` builds one immutable render graph from producer-owned
`WorldRenderPass` declarations. The graph orders the main and optional shadow
views, terrain material classes, scene producers, depth snapshots, shader-pack
program families, composite, and HUD work. A selected terrain or shader
provider changes ownership inside this graph; it does not introduce a second
world-render loop.

The main production routes are:

| Producer | Preparation and ordering | Physical submission |
| --- | --- | --- |
| Near terrain | `ChunkRenderer` creates one frame plan per view. `OpenGlNearTerrainRegionRuntime` revision-caches visible page plans and front-to-back/back-to-front region membership. | One region/material/view batch is submitted as `glMultiDrawElementsBaseVertex` per primitive topology. Non-region meshes retain the conventional one-mesh draw fallback. |
| Distant terrain | `DistantHierarchicalTerrainRuntime` publishes independent main/shadow selections and uses the shared region submission boundary. | The same region device, batch cache, multi-draw, fence, and metrics path as near terrain. |
| Entities | `EntitiesRenderer` prepares visibility in parallel, merges thread-local collection batches once, then `EntityDrawer` sorts opaque/translucent and shadow feature lists. | Each retained feature/model-layer mesh is still an individual vertex-buffer draw. Skeletal draws also upload the current instance's transform UBO before submission. |
| Particles | All visible particles are rebuilt into one opaque point stream and one translucent point stream. | At most two draw calls. Each layer retains a geometrically grown buffer/VAO, updates its fixed-capacity storage, and selects only the used vertex prefix. |
| Iris scene programs | `ShaderPipelineRegistry` pins one pipeline for the frame and maps host scene contracts to compiled shader-pack variants. | Program, physical framebuffer attachments/draw buffers, samplers, frame state, host-uniform revisions, draw state, and blend overrides are independently cached. |

Terrain material classes are graph-level semantics: opaque, cutout, translucent,
emissive/additive, and distant water. Block/material identity, texture-array
selection, light, tint, normal/tangent data, and shader-pack fields are encoded
in vertices. Ordinary block and particle textures therefore do not require one
texture bind per material or object.

## State already cached

The OpenGL backend suppresses repeated capability, blend-function, depth
function, depth-mask, polygon-mode, polygon-offset, clear-color, viewport,
program, buffer, and VAO changes. `RenderConstants.DIRTY_BUFFER_UNBIND` is true,
so normal draws leave buffers and VAOs bound rather than emitting cleanup binds.
`OpenGlShaderManagement` also skips `glUseProgram` when two host shader objects
resolve to the same native program.

Terrain adds higher-level caches:

- `ChunkRenderer` reuses the main-view terrain frame plan while the visible-list
  revision is unchanged.
- Near and distant runtimes cache view ordering until their resident or selected
  page revision changes.
- `TerrainBatchCache` caches immutable region/material/view command templates
  and topology-grouped physical packets while acquiring fresh storage leases
  for each frame.
- `OpenGlTerrainRegionDevice` combines every compatible command in a region and
  topology into a single multi-draw call.

These caches are important constraints: an optimization proposal that only
adds another visible-page list or material grouping duplicates existing work.

## Retained performance evidence

The accepted Apple OpenGL 4.1 records establish the following reference points:

- The fixed-camera near-terrain workload submitted 348 logical page commands as
  24 device batches across four occupied regions, three material classes, and
  main/shadow views. Its 600-sample submission median was 83,292 ns and its p95
  bucket upper bound was 233,625 ns.
- The streaming workload submitted 390 near commands in 21 device batches and
  931 distant commands in nine device batches. Near submission p95 was bounded
  by 246,625 ns over 600 samples.
- The quiescent 30-minute terrain/resource soak kept live GPU names exactly
  flat but still created and deleted 37,986 names. This proves lifecycle
  balance, not absence of allocation churn.
- Earlier Iris scene evidence recorded 49,958,310 draw-uniform uploads by frame
  17,484 in an entity-rich scene. Texture-array dimensions were subsequently
  reduced to once-per-program-per-frame uploads, but general frame, host, and
  draw state remains rebound at host shader-use boundaries.

The terrain numbers come from
[`2026-07-31-terrain-near-fixed-camera-performance.json`](../agents/evidence/2026-07-31-terrain-near-fixed-camera-performance.json),
[`2026-07-31-terrain-streaming-performance.json`](../agents/evidence/2026-07-31-terrain-streaming-performance.json),
and [`2026-08-01-terrain-l6-workload-matrix.json`](../agents/evidence/2026-08-01-terrain-l6-workload-matrix.json).
The uniform count is retained in the
[`Iris render-pipeline evidence`](../agents/evidence/2026-07-26-iris-render-pipeline-support.md).

## Implemented optimization set

All six items below are implemented. Automated checks prove the source-level
state, lifecycle, ordering, and packet contracts. The named real-OpenGL
workloads remain required before attributing a measured speedup.

### 1. Add exact low-overhead driver-work counters

`OpenGlWorkCounters` records cumulative physical driver work at the shared
OpenGL boundary. `metrics.snapshot` publishes low-cost totals, while
`render.substrate` publishes totals plus deltas since its previous capture.

The fixed primitive counters cover:

- `glDrawArrays`, `glDrawElements`, base-vertex draws, multi-draw calls, logical
  commands, and submitted draw elements (vertices for arrays, indices for
  element draws);
- program changes and redundant program requests;
- framebuffer binds, attachment changes, draw/read-buffer changes, and
  completeness checks;
- active-texture changes, texture binds by target, image binds, and sampler
  parameter changes;
- VAO and buffer binds; and
- scalar/vector/matrix, sampler, uniform-block, and uniform-buffer uploads,
  including uniform-buffer bytes.

The hot path remains numeric and allocation-free; JSON names are derived only
when a debug response is built.

### 2. Cache complete Iris program bindings

`IrisWorldShaderPipeline` caches frame state per program/frame/stage, resolved
draw state per linked shader, and complete host-uniform snapshots by source
revision. `IrisOpenGlRenderTargets` changes only differing physical color/depth
attachments and draw/read buffers, checks completeness only after a
configuration change, caches sampler uniform units and depth-comparison modes,
and suppresses unchanged blend overrides.

The render-thread binding cache separates and keys:

- pipeline generation, view, linked program, and program phase;
- physical output/depth texture IDs after ping-pong resolution;
- blend override and framebuffer dimensions;
- sampled texture/image identities, units, and comparison modes; and
- the frame-state revision plus draw-state revision.

Physical texture identities make target flips and sampler-side ping-pong changes
miss naturally. Generation ownership clears all higher-level caches on reload;
the context state is invalidated on teardown. Changed entity/item/block IDs,
skeletal transforms, material overlays, and other draw-local values still
advance their independent revisions.

### 3. Retain particle GPU buffers

`ParticleRenderer` retains one opaque and one translucent mesh allocation.
Capacity grows to the next power of two, same-capacity frames update in place,
and an explicit used-vertex prefix prevents spare capacity from being drawn.

This preserves the two-draw shape while removing steady-state resource-name
churn. Sub-data versus orphaning or a bounded ring remains a cross-driver
measurement choice rather than a resource-ownership change.

### 4. Precompile terrain multi-draw packets

`TerrainBatchCache` caches a `TerrainDrawPacket` beside each immutable command
template. The packet owns topology groups, total indices, and private packed
count/byte-offset/base-vertex arrays. The OpenGL device realizes those values
once into bounded, lifecycle-owned native multi-draw buffers and reuses them on
later submissions. Each returned batch still acquires a fresh storage lease, so
cached membership cannot pin retired page ranges.

This reduces CPU allocation and command construction. It does not reduce the
already-small number of driver calls. Fewer calls would require sharing a
physical arena/VAO across regions or changing region size, which affects memory
ceilings, replacement granularity, translucent order, and retirement fences and
therefore needs separate evidence.

### 5. Replace class-only entity ordering with explicit state keys

`EntityDrawer` consumes producer-declared `EntityRenderStateKey` values that
name program family, vertex ABI, state ABI, material layer, and mesh group.
Opaque and shadow queues group by the immutable key. Translucent queues remain
strictly distance-first and use the state key only after equal depth.

State sorting reduces material/program changes but does not reduce one draw per
mesh. A later entity-heavy optimization can instance repeated models by moving
per-instance skeletal transforms and draw identity into an indexed instance
buffer. The current single skeletal UBO is overwritten before every instance,
so multi-draw alone cannot combine those draws. Any instancing design must keep
the OpenGL 3.3 floor, bounded transform counts, texture/material layers, Iris
draw IDs, emissive/armor passes, and shadow behavior intact.

### 6. Make texture binding state unit- and target-aware

`OpenGlRenderSystem` owns the active texture unit plus a binding entry for each
unit/target pair. Production texture creation, upload, sampler, framebuffer,
custom-resource, and deletion paths use this central state and invalidate every
cached occurrence of a deleted name.

This is first a correctness and observability prerequisite; a performance win
still requires the new counters to show fewer calls in matched workloads.

## Validation workloads

Measure each optimization with matched baseline/candidate intervals and the
repository's normal scenario protocol:

| Workload | Primary deltas |
| --- | --- |
| Warm fixed terrain, built-in and Bliss, main plus shadow | terrain device batches, physical draw calls, packet builds/hits, program/FBO/sampler changes, submission median/p95 |
| Dense repeated living entities with armor/emissive layers | scene draws, program/FBO/sampler changes, uniform/UBO bytes, CPU draw median/p95 |
| Fixed-count opaque and translucent particle canaries | draw calls, buffer/VAO creations, upload bytes/time, CPU draw median/p95 |
| Fullscreen-heavy Complementary frame | FBO attachment changes, completeness checks, sampler binds, frame-uniform versus draw-uniform uploads |
| Thirty-minute quiescent soak | live/created/deleted GPU names, heap allocation, state counters, visual and resource stability |

Warm queues and generations to an idle boundary, capture cumulative counters
before and after each interval, require at least 100 comparable samples for p95,
and interleave baseline/candidate runs. FPS, a single frame, or balanced resource
deletion alone is not sufficient evidence.

## Source map

- Graph and pass ownership: `RendererPipeline`, `WorldPassRegistry`,
  `WorldRenderPass`
- OpenGL state: `OpenGlRenderSystem`, `OpenGlShaderManagement`,
  `OpenGlGpuBuffer`, `OpenGlVao`, `OpenGlTextureArray`
- Terrain planning/submission: `ChunkRenderer`,
  `OpenGlNearTerrainRegionRuntime`, `DistantHierarchicalTerrainRuntime`,
  `TerrainBatchCache`, `TerrainRegionFrameSubmission`,
  `OpenGlTerrainRegionDevice`
- Shader-pack binding: `ShaderPipelineRegistry`, `IrisWorldShaderPipeline`,
  `IrisOpenGlRenderTargets`
- Entity and particle paths: `EntitiesRenderer`, `EntityDrawer`,
  `SkeletalInstance`, `SkeletalManager`, `ParticleRenderer`
- Metrics: `RenderStats`, `TerrainPerformanceTelemetry`, `ClientDebugChannel`
