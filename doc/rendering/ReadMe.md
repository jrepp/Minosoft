<!-- Copyright (C) 2026 Jacob Repp -->

# Minosoft rendering system

## General

Minosoft keeps OpenGL 3.3 as its compatibility floor. Non-Apple GLFW launches
request a 4.3 core context first and may receive a newer compatible context.
macOS requests 4.1 before falling back to 3.3 because Apple does not expose
OpenGL 4.3. Compute, image, shader-storage, tessellation, and related paths are
gated on the actual context capabilities and driver limits; a requested version
is not treated as proof that a feature exists.

The rendering system remains abstract. Immutable graph/resource declarations,
provider ownership, terrain artifacts, scheduling, residency, submission, and
telemetry contracts live in the GUI-independent `render-contracts` module.
Window, Minecraft-world, LWJGL, and OpenGL realization stays in the application
module, so headless planning and tests do not require a graphics context.

## Frame architecture

Each `PlaySession` owns a `Rendering` instance and `RenderContext`.
`RendererPipeline` builds one immutable graph from producer-owned
`WorldRenderPass` declarations. Stable passes cover the main and optional
shadow views, sky, near and distant terrain material classes, entities, block
entities, particles, weather/overlays, hand, shader-pack fullscreen stages,
world presentation, and HUD.

`TerrainBackendRegistry` and `ShaderPipelineRegistry` pin one selected
generation for the complete frame. Built-in, optimized-terrain-labelled, Iris,
and combined profiles execute the same graph; provider selection changes
ownership and resources, not the frame architecture. Candidate preparation is
transactional, failed publication preserves the active generation, and retired
GPU resources wait for outstanding frame/storage leases.

## Lifecycle

Rendering initializes with the play session after assets and normalized content
are available. Shader, texture, framebuffer, mesh, terrain, and provider objects
have explicit load/unload or candidate/publish/retire ownership. OpenGL names
are tracked per context and resource namespace. Content or shader reloads build
complete candidates before swapping the last-known-good generation.

## Textures

The authoritative compatibility matrix for PNG dimensions, texture arrays,
`.png.mcmeta` animation, vanilla models, and higher-fidelity mod formats is the
[content and asset system](../Assets.md). The notes below describe the renderer
storage design.

### Static textures

Static block/item textures are bucketed by resolution in `GL_TEXTURE_2D_ARRAY`
objects. Vertices retain a packed array/layer identifier, so terrain, entity,
GUI, and particle batches can mix texture pages without per-object texture
binds. Mipmap levels form the remaining storage dimension. LabPBR normal and
specular data occupy companion pages in the same physical array allocation.

Ordinary `.png.mcmeta` animation updates the retained array layer. Material
companion animations advance on the CPU and upload only when their revision
changes.

### Dynamic textures

Dynamic textures, including player skins, use a separately growable texture
array with transactional replacement. Font glyphs use their own array.

## Performance

Near and distant terrain use transactional region arenas, cached
region/material/view command templates with topology-grouped physical packets,
OpenGL fences, and
`glMultiDrawElementsBaseVertex`; a conventional per-mesh loop remains for
non-region or non-OpenGL paths. Particles retain capacity-tracked buffers for
one opaque and one translucent draw. Entities retain feature/model meshes and
use explicit opaque/shadow state keys plus distance-first translucent queues,
but still submit individual meshes.

The source-grounded submission inventory, retained measurements, implemented
state/allocation optimizations, and measurement queue are in
[Render performance and OpenGL submission](Performance.md).

### Culling

Minosoft combines multiple culling techniques:

- face culling in OpenGL;
- block-neighbour face elimination during terrain meshing;
- frustum and configured-distance selection;
- revision-checked terrain visibility traversal and asynchronous occlusion
  queries for conventional chunk meshes; and
- provider-specific main/shadow view selection and near/distant coverage
  masking.

Distant page meshing uses bounded semantic merging. The removed legacy near
greedy-meshing experiment is not part of the production near terrain contract.

## Renderers

Renderers such as `ChunkRenderer`, `EntitiesRenderer`, and `ParticleRenderer`
declare their graph passes through `WorldPassRegistry`. Presentation/HUD
renderers also enter the graph, while Fabric compatibility hooks register at
explicit producer, visibility, frame, terrain-provider, or shader-provider
boundaries. Code must not add a second mutable world-renderer loop.

## Render phases

`RenderGraphGeneration` orders passes by explicit phase, dependency, view, and
stable ID. The graph distinguishes distant solid/water, opaque/cutout/emissive
world geometry, the pre-translucent depth boundaries, translucent producers,
hand, overlays, shader-pack fullscreen families, composite, and HUD. A shadow
view is a first-class set of graph nodes, not a hidden second traversal.

## Transparency

Cutout materials use alpha tests/discard. Translucent layers enable blending,
disable depth writes where required, and preserve back-to-front terrain page
ordering. Entity feature queues retain layer-specific ordering, but the engine
does not provide general per-face order-independent transparency.

## Lighting

Near terrain snapshots sample four independent corner light/color values and
continuous ambient occlusion while meshing. A section-local tint cache supplies
blended biome colors. Dynamic entities and particles consume the retained
lightmap path. Distant pages retain their own bounded block/skylight data and
resolved tint/material fields. Version-normalized light input and render
consumption are separate from the server/world simulation implementation.
