<!-- Copyright (C) 2026 Jacob Repp -->

# Render-performance and OpenGL optimization implementation

## Scope

This record closes the six source-level implementation items ranked by the
[August 1 audit](2026-08-01-render-performance-opengl-audit.md). It does not
claim a measured frame-time improvement. No running client/server state was
mutated; matched Apple OpenGL and second-driver workloads remain the performance
acceptance gate.

## Implemented contracts

| Status | Contract | Evidence |
| --- | --- | --- |
| Verified | One context-owned fixed-primitive counter set records physical draw APIs, logical commands/submitted elements, program requests/changes, framebuffer binds/attachments/draw/read buffers/completeness checks, active texture and target-specific/image binds, sampler-parameter changes, VAO/buffer binds, typed uniform uploads, and uniform-buffer bytes. `metrics.snapshot` exposes cumulative totals; `render.substrate` also exposes the delta from its prior capture. | `OpenGlWorkCounters`, all production OpenGL call sites, `ClientDebugChannel`, `OpenGlWorkCountersTest`, and `OpenGlInstrumentationBoundaryTest`. |
| Verified | Texture binding state is keyed by active unit plus target. Every production texture create/upload/bind/delete path uses the shared state, and deletion invalidates all occurrences of the retired name. | `OpenGlRenderSystem.bindTexture`, `invalidateTexture`, OpenGL texture arrays/attachments, and Iris target/custom-resource owners. |
| Verified | Iris binding realization independently caches physical framebuffer attachments and draw-buffer layouts, completeness, sampler units, comparison modes, blend overrides, frame/stage state, resolved draw state, and complete host-uniform snapshots by revision. Changed direct host uniforms publish their revision without replaying the entire snapshot on every setter. | `IrisOpenGlRenderTargets`, `IrisWorldShaderPipeline`, `Shader`, `ShaderUniform`, `ShaderPipelineRegistry`, and focused Iris tests. |
| Verified | Opaque and translucent particle layers retain geometrically grown VBO/VAO capacities, update equal-capacity data in place, and draw only the used vertex prefix. Empty frames hide but retain capacity; teardown owns both retained meshes. | `ParticleRenderer`, `ParticleMeshBuilder`, `VertexBuffer.setVertices`, `OpenGlVertexBuffer`, and `ParticleRendererTest`. |
| Verified | Cached terrain templates own immutable topology groups and private packed index-count, byte-offset, and base-vertex arrays. The OpenGL region device realizes them into bounded native buffers once per cached packet and frees those buffers on eviction or device close. Every frame still receives a fresh storage lease, so the packet cache cannot pin retired ranges. | `TerrainDrawPacket`, `TerrainBatchCache`, `OpenGlTerrainRegionDevice`, `TerrainBatchingTest`, `OpenGlTerrainDrawPacketCacheTest`, and `TerrainArchitectureBoundaryTest`. |
| Verified | Entity producers declare immutable keys naming program family, vertex/state ABI, material layer, and mesh group. Opaque/shadow queues group on the key; translucent queues remain distance-first and use state only after equal depth. | `EntityRenderStateKey`, `EntityRenderStateKeys`, `EntityDrawer`, `EntityDrawOrderTest`, and `EntityRenderStateBoundaryTest`. |

## Validation

- Java 25.0.1 ran the final build.
- `./gradlew :render-contracts:test :test :integrationTest` passed against the
  final source. Focused terrain-packet allocation/lifecycle,
  architecture-boundary, Iris identity/revision/framebuffer, entity-key/order,
  OpenGL-counter/instrumentation-boundary, texture-state, and retained-particle
  tests also passed during development.
- Documentation relative-link and referenced-symbol checks passed.
- `git diff --check` passed.
- `./play.sh status --json` reported no client, server, or external client, so
  no live state was mutated and no real-driver performance claim was made.

## Remaining acceptance

Run the workloads in
[render performance and OpenGL submission](../../rendering/Performance.md#validation-workloads)
with matched trajectory, generation, pose, presentation, window, warm-up, work
count, and instrumentation state. Use the new cumulative/delta counters to
separate eliminated driver calls from unchanged logical draws. A second OpenGL
driver remains required for portability and for choosing particle sub-data
versus orphan/ring upload policy.
