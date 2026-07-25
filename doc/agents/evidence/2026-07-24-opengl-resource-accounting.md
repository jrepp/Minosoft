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
real-GL, and future outline-pass acceptance; it does not by itself prove those
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
   and the exact Animated Java Blockbench export.

The next outline-specific implementation can now allocate a color-mask target
and optional stencil attachment transactionally and prove both are reclaimed.
