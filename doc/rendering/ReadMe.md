# Minosoft rendering system

(I'll extend this document when I find time or motivation)

## General

Minosoft keeps OpenGL 3.3 as its compatibility floor. On non-Apple platforms
it first requests an OpenGL 4.3 core context, which enables the compute-shader
and shader-storage paths used by advanced shader packs when the actual driver
capabilities and limits pass validation. GLFW may return a newer compatible
context. macOS requests OpenGL 4.1 because Apple does not expose 4.3, so 4.3-only
features remain disabled there. The rendering system stays abstract and
headless planning does not require OpenGL.

Everything is working in shaders (written in glsl), some things even have multiple shaders.

## Integration

The whole render system is like a separate module. There are almost no references to it, only in `PlaySession.kt`. Everything is event driven (or abstract).

## Loading

The whole render system gets loaded, as soon as you tell eros to connect to a server. Everything gets downloaded then and the render subsystem loads.

## Textures

The authoritative compatibility matrix for PNG dimensions, texture arrays,
`.png.mcmeta` animation, vanilla models, and higher-fidelity mod formats is the
[content and asset system](../Assets.md). The notes below describe the renderer
storage design.

### Static textures

Textures that don't get modified anymore (like block textures or items).
The textures are stored in 5 dimensional way (2d for x and y coordinates, 1d for the texture index (aka. what texture), 1d for the resolution (like `16x16` or `32x32`) and the last dimension for mipmaps).
Every vertex can have an additional animation id, that is done via an uniform buffer.

### Dynamic textures

Used for e.g. skins.

## (Performance) optimizations

- Chunking (like minecraft does it)

### Culling

Minosoft is using multiple culling techniques that all work together to archive the best performance.

- Face culling (`glCullFace`; gpu only)
- Neighbour culling (hide unseen faces; cpu only)
- Frustum culling (hide what is behind you/not in the camera perspective)
- View distance clipping (maximum render distance)
- Occlusion culling (hide chunks that are not visible (e.g. caves from the surface); cpu and gpu)
- ~~Greedy meshing (combining multiple blocks into a single face)~~ not really worked and even got removed

## Renderers

Even the render system is dynamic. There are a lot of so-called renderers (e.g. `WorldRenderer`, `ParticleRenderer` or `GUIRenderer`) that get registered dynamically while loading. So extending the system is pretty easy and moddable.

## Render phases

There are multiple render phases. 3D breaks when it comes to transparency, or it gets optimized when drawing in a specific order (gpu occlusion culling). For example a render phase is `OPAQUE` or `TRANSLUCENT`.
It basically lets all renderers draw their opaque objects first and then draw transparent ones.

## Transparency

100% transparent pixels get `discard`ed in the shader. That makes no problem. Translucency is getting hacked with `glDepthMask(false)`. It is not the best solution but a good workaround. Some face sorting is needed in the future.

## Lighting

Lighting is done as soon as a block changes in the world. Increasing light is a lot faster than decreasing light.
A custom light engine is included, all server light is ignored by default (documentation needed).

The lighting on the render side is done via a lightmap, basically another uniform buffer.
