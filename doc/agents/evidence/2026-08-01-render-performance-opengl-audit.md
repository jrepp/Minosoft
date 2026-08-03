<!-- Copyright (C) 2026 Jacob Repp -->

# Render-performance and OpenGL submission audit

Date: 2026-08-01

## Scope and conclusion

This was a read-only source and retained-evidence audit of material changes,
draw submission, OpenGL state, and the documentation that describes them. No
client or server was running at the inspection boundary: `./play.sh status
--json` reported no parent, client, server, external client, or ready debug
endpoint. The audit therefore makes no new live performance claim.

The strongest current optimization target is Iris state realization rather
than terrain draw-call count. Region terrain already combines hundreds or
thousands of page commands into tens of physical device batches. By contrast,
scene shader-use boundaries can repeatedly configure framebuffer attachments,
samplers, frame uniforms, host uniform snapshots, and draw state. The retained
entity-rich Iris run reached 49,958,310 draw-uniform uploads by frame 17,484.

The second accepted target is resource churn. Particles already use at most one
opaque and one translucent draw, but both meshes are unloaded and replaced
every active frame. The quiescent 30-minute terrain/resource soak balanced
37,986 created and deleted GPU names while keeping the live total constant.
That proves cleanup, not absence of driver/resource allocation work.

## Current submission boundaries

| Status | Claim | Evidence |
| --- | --- | --- |
| Observed | `RendererPipeline` executes one immutable main/auxiliary graph. Producers declare semantics and state through `WorldRenderPass`; terrain and shader generations are pinned across preparation and graph execution. | `RendererPipeline`, `WorldPassRegistry`, `WorldRenderPass`, `ProductionTerrainPipelineRegistry` |
| Observed | The OpenGL backend suppresses repeated capability, blend, depth, depth-mask, polygon, viewport, native-program, buffer, and VAO changes. Normal buffer/VAO unbinds are disabled. | `OpenGlRenderSystem`, `OpenGlShaderManagement`, `OpenGlGpuBuffer`, `OpenGlVao`, `RenderConstants.DIRTY_BUFFER_UNBIND` |
| Verified | Near/distant region storage caches view plans and immutable region/material/view command templates, then issues one multi-draw per primitive topology. The fixed-camera run used 24 device batches for 348 logical commands; streaming used 21 near batches for 390 commands and nine distant batches for 931 commands. | `TerrainBatchCache`, `TerrainRegionFrameSubmission`, `OpenGlTerrainRegionDevice`, `OpenGlNearTerrainRegionRuntime`, `DistantHierarchicalTerrainRuntime`, [fixed-camera evidence](2026-07-31-terrain-near-fixed-camera-performance.json), [streaming evidence](2026-07-31-terrain-streaming-performance.json) |
| Observed | A cached terrain command template does not cache its physical topology grouping or multi-draw count/offset/base arrays. `OpenGlTerrainRegionDevice.draw` recreates those for every batch submission. | `OpenGlTerrainRegionResources.kt` |
| Observed | Iris scene/terrain binds configure render targets and samplers at bind time. `ViewFramebuffer.configure` binds the FBO, detaches every color slot, reattaches outputs/depth, creates a draw-buffer array, and checks completeness; no complete binding-key cache guards this path. | `IrisWorldShaderPipeline.bindScene`, `IrisWorldShaderPipeline.bindTerrain`, `IrisOpenGlRenderTargets.bindProgram`, `IrisOpenGlRenderTargets.ViewFramebuffer.configure` |
| Verified | Texture-array dimension uniforms have a per-program/per-frame guard, while retained evidence still showed 49,958,310 general draw-uniform uploads at frame 17,484 in the entity-rich Iris run. | `IrisWorldShaderPipeline.uploadFrameState`, `textureArrayUploadFrames`, [Iris pipeline evidence](2026-07-26-iris-render-pipeline-support.md) |
| Observed | `EntityDrawer` groups by priority and a class-derived sort value before distance/stable order. It does not expose a complete program/material/state key. Skeletal submission overwrites one transform UBO before each mesh draw. | `EntityDrawer.compareFeatureDrawables`, `DrawableEntityRenderFeature.sort`, `SkeletalInstance.drawMesh`, `SkeletalManager.upload` |
| Observed | `ParticleRenderer` aggregates geometry into opaque/translucent meshes but unloads both before preparation and loads replacements after preparation. | `ParticleRenderer.prePrepareDraw`, `ParticleRenderer.prepareDrawAsync`, `ParticleRenderer.postPrepareDraw` |
| Verified | A quiescent 30-minute soak held the live GPU-name total at 463 but created and deleted 37,986 names. The earlier fixed-pose allocation diagnosis sampled remaining mesh creation in GUI and particle paths. | [L6 workload matrix](2026-08-01-terrain-l6-workload-matrix.json), [render-churn correction](2026-07-31-render-churn-performance.md) |
| Observed | The OpenGL texture cache is one context-wide handle even though callers use multiple texture units and both 2D and 2D-array targets. It cannot serve as a complete sampler-binding cache. | `OpenGlRenderSystem.boundTexture`, `OpenGlTextureArray`, `OpenGlDynamicTextureArray`, `IrisOpenGlRenderTargets.bindSamplers` |
| Unknown | The repository does not expose exact complete-frame counts for physical draw API calls, program switches, FBO attachment changes, texture binds by unit/target, VAO/buffer binds, or all uniform uploads. `RenderStats` measures CPU draw-phase duration; terrain and Iris expose subsystem-specific counters. | `RenderStats`, `OpenGlVertexBuffer`, `TerrainRegionFrameSubmission`, `WorldShaderPipelineDiagnostics`, `ClientDebugChannel` |

## Decisions

1. Add bounded primitive OpenGL work counters before accepting a state-ordering
   or material-swap optimization.
2. Cache complete Iris bindings by generation, view, physical target set,
   program, samplers, blend state, frame revision, and draw-state revision.
   Invalidate on target flips, internal targets, depth copies, fullscreen/compute
   mutation, reload, view change, and context loss.
3. Retain and resize particle buffers instead of recreating buffer/VAO names.
4. Cache physical terrain topology packets without allowing cached membership to
   pin retired storage ranges.
5. Replace class-only opaque/shadow entity grouping with an explicit immutable
   state key; preserve translucent depth constraints. Evaluate repeated-model
   instancing only after state counters identify entity draws as material.
6. Replace the single texture-handle cache with unit-and-target-aware state
   before attempting broad sampler-bind suppression.

The human-facing implementation map and validation workloads are in
[render performance and OpenGL submission](../../rendering/Performance.md).

## Documentation changes

This audit refreshed:

- `doc/rendering/ReadMe.md` for the graph, provider, lifecycle, texture, culling,
  transparency, lighting, and region-submission architecture;
- `doc/rendering/Meshes.md` for retained meshes and region arenas;
- `doc/rendering/Entities.md` for the actual collection, sorting, skeletal, and
  hitbox paths;
- `doc/Shader.md` for frame-pinned shader-pack ownership; and
- the graphics map and render-substrate current-gap wording.

## Validation and limits

- `./play.sh status --json` passed and established that no live state was
  available or mutated.
- Source symbols and documentation links were checked by repository search.
- Java 25.0.1 ran `./gradlew :render-contracts:test --tests
  '*TerrainBatchingTest' :test --tests '*EntityDrawOrderTest'`; both focused
  targets passed.
- The proposed optimization order is supported by source shape and retained
  counters. Only the terrain submission and resource/uniform counts above are
  measured; expected gains require matched baseline/candidate workloads.
