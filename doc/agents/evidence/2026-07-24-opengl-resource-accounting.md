<!-- Copyright (C) 2026 Jacob Repp -->

# OpenGL resource-accounting evidence

## Outcome

Minosoft now accounts for every OpenGL name created by its rendering backend:
buffers, vertex arrays, textures, renderbuffers, framebuffers, shaders,
programs, and queries. Accounting is per render context and per OpenGL
namespace. Reusing the same integer in two resource types is valid; creating a
duplicate live name or deleting an unknown name fails immediately.

`render.substrate` exposes cumulative created/deleted and current live totals,
plus the same values per resource type. Context destruction logs a warning
when tracked names remain live. This establishes the measurement substrate for
repeated content reload, Animated Java rendered-reference, EMF/ETF/Gecko
real-GL, and outline-pass acceptance; it does not by itself prove those
acceptance loops return to baseline.

## Lifecycle changes

- OpenGL texture allocation now receives its owning render system directly
  instead of resolving ownership through the thread-local current context.
- Shader compile/link failure and reload paths account for temporary shader
  objects, candidate programs, replaced programs, and cleanup failures.
- Buffer, vertex-array, texture, renderbuffer, framebuffer, and query
  creation/deletion paths update the same typed tracker.
- Buffer, vertex-array, framebuffer attachment, font-array, and dynamic-array
  allocation failures delete candidate names before propagating the failure.
- Static, dynamic, and font texture arrays now have an explicit texture-manager
  teardown path. Render shutdown also closes integrated framebuffers and any
  shader still registered with the context.
- Cloud-array replacement now unloads only the outgoing grid edge before
  moving retained cells. Each `CloudArray` releases its mesh, pending retired
  layers drain once, and cloud-renderer shutdown unloads both active and
  pending layers.
- Framebuffer creation rolls back partial attachments and its framebuffer name.
  Separate stencil renderbuffers attach to `GL_STENCIL_ATTACHMENT`; they no
  longer overwrite the depth attachment.

## Automated evidence

Run with Java 17:

```sh
./gradlew compileKotlin
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.system.opengl.resource.OpenGlResourceTrackerTest
```

On 2026-07-24 both checks passed. The focused test proves typed namespaces,
created/deleted/live totals, handle reuse after deletion, and rejection of
duplicate creation, unknown deletion, and invalid zero handles.

A Java 17 local-world `content-fidelity` launch also proved the real endpoint:
`core.capabilities` advertised `render.substrate` and
`render.reload-content`; the former returned all eight resource types and the
latter synchronously published content generation 2. A supervised code change
then activated client generation 2, left exactly one generation-2 endpoint,
retained both capabilities, and accepted another reload. Stopping the
supervisor removed the endpoint. Active-world counts were intentionally not
accepted as a leak baseline because chunk mesh and occlusion-query populations
were still changing.

On 2026-07-25 a supervised void-world launch exercised both the implemented
entity-outline mask/composite path and the exact mounted Animated Java export
on the real driver. The graph exposed `minosoft:scene/entity-outlines` as a
`world_overlay` pass at order `4000`. The seven generated item-display bones
held a repeatable 42-object model delta: 28 buffers and 14 vertex arrays. The
summoned fixture held at `311/176/88` live total/buffer/vertex-array names.
Removal returned to the warmed baseline family (`269/148/74`, with transient
HUD/chunk values up to `272/150/75`) across four consecutive content
generations, with no cumulative growth. Texture, renderbuffer, framebuffer,
program, shader-object, and query populations did not accumulate.

The checked macOS/Apple M4 Max scenario now compares a 550x550 default pose and
static walk frame 10 with zero tolerance. The walk pixels remain exact after a
production content reload. The same scenario also passed when the pause menu
was deliberately open before execution because `visual.prepare-reference`
clears transient overlays and disables the HUD.

Allocation-stack diagnostics then distinguished fixture ownership from an
independent renderer leak: every reported buffer came from `CloudArray.build`
after `CloudLayer.pushX/pushZ`, not from an item-display feature. After the
cloud ownership fix, `animated-java-render-cleanup.json` removed the generated
hierarchy, moved the camera 512 blocks and back to replace the whole cloud
grid, and left only the local player. Two explicit `jcmd GC.run` collections
emitted no new `MemoryLeakException`, `IllegalStateException`, or double-unload
warning. The client stopped cleanly and its debug endpoint was removed.

On 2026-07-25 `animated-java-rejected-reload.json` added a synchronous
candidate-local accounting gate. `render.reload-content` rejected at
`after-upload` and `after-publication`; each attempt created and deleted 16
buffers plus eight vertex arrays and returned every typed live delta to zero.
The active generation remained 1, the exact walk crop remained unchanged, and
the mounted remove/summon functions still executed. A normal recovery then
published generation 3. Because both resource snapshots occur in one render
operation, unrelated frame/cloud work cannot enter the measured interval.

This accepts the exact Animated Java default/walk
success/rejection/recovery/removal path on the documented platform. Equivalent
fixed EMF/ETF/Gecko loops remain before the broader adapter gate can be
considered quiescent across all content systems.

## Remaining acceptance

Before any content adapter moves from partial to full support:

1. capture a live baseline after loading a fixed world and fixture;
2. repeat candidate success, rejected candidate, recovery, entity removal, and
   content unload while sampling `render.substrate.gpuResources`;
3. require retired buffer/texture/program counts to return to the baseline
   after render-queue cleanup;
4. terminate the client and require the destruction warning to report no live
   tracked resources;
5. pair those counts with fixed framebuffer references for EMF, ETF, GeckoLib,
   and both the default and settled walk poses of the exact Animated Java
   Blockbench export.

The Animated Java default/walk success/reload/removal path now satisfies its
platform-qualified positive loop in items 1–3, including forced-finalizer
diagnostics after entity and cloud-grid replacement, and its two-phase
rejected-candidate/recovery loop. Other platforms/drivers and the equivalent
EMF/ETF/Gecko loops remain.

The outline implementation now allocates and resizes its texture-only
color-mask target transactionally and unloads its mesh, shader, framebuffer,
and queued commands. The remaining gate is a fixed glowing fixture with
reference pixels plus quiescent pre/post removal resource counts.
