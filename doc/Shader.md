# Shader

## Variable declarations/naming

All variables are prefixed with:

- Vertex: `v`
- Geometry: `g`
- Fragment: `f`

followed by `in`. So `vinPosition` is a valid name.

## Uniforms

Prefixed with `u`

## Shader packs

The managed Complementary Unbound integration, including the pinned upstream
archive, option profile, resolved-source transformations, auxiliary-buffer
contracts, and validation gates, is documented in
[Complementary Unbound integration](rendering/ComplementaryUnbound.md).

Shader-pack programs are selected through the frame-pinned
`ShaderPipelineRegistry`. Terrain binds by view and semantic material class;
scene shaders declare a program family plus vertex/state ABI. Iris owns the
matching world/shadow targets, samplers, depth snapshots, compute/fullscreen
families where supported, and final presentation. An unclassified scene draw
fails while a shader-pack generation owns the frame rather than silently using
an incompatible host program.

The current binding costs and caching opportunities are documented in
[Render performance and OpenGL submission](rendering/Performance.md).
