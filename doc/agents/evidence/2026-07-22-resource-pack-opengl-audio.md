<!-- Copyright (C) 2026 Jacob Repp -->

# Resource-pack OpenGL and positional-audio evidence — 2026-07-22

## Failure map

The Faithful 64x trajectory exposed three independent OpenGL contracts:

1. `uTextures[16]` is compiled as sixteen active `sampler2DArray` uniforms on
   Apple's switch-based GLSL path. Unassigned elements defaulted to texture unit
   zero, which only held the 2D framebuffer texture. The first draw reported an
   unloadable 2D-array sampler.
2. The dynamic array allocated fixed 64×64 layers. Faithful supplied 256×256
   default player textures; the loader warned and still issued an oversized
   subimage upload, producing `GL_INVALID_VALUE`.
3. The font array allocated fixed 1024×1024 layers. Faithful supplied
   `nonlatin_european.png` at 512×2144 and `accented.png` at 576×3600; both
   subimage uploads exceeded the allocated height and produced
   `GL_INVALID_VALUE`.

Stage-local `glGetError` probes established these call sites. The probes were
removed after the fixes; they are not permanent per-frame overhead.

## Corrected contracts

- `OpenGlTextureManager` initializes every active sampler-array element to the
  always-allocated dynamic 2D array, then static/dynamic/font owners override
  their assigned indices. Unit zero remains available to the 2D framebuffer.
- `OpenGlDynamicTextureArray` grows to the next power-of-two resolution when a
  loaded texture requires it, recreates and republishes existing layers, and
  rejects dimensions beyond `GL_MAX_TEXTURE_SIZE` before upload.
- `OpenGlFontTextureArray` allocates rectangular storage from the independent
  maximum width and height across loaded fonts, retains the 1024 minimum needed
  by generated fonts, transforms UVs against the actual allocation, and checks
  the driver limit before upload.
- `OpenGlTextureSizing` holds the CPU-only sizing rules and focused tests.

## Live acceptance

- Supervised generation 21 grew the dynamic array from 64×64 to 256×256 and
  rendered a 1800×1000 Faithful world/HUD/menu capture without the Apple
  unloadable-sampler warning or `GL_INVALID_VALUE`.
- The helper extraction then activated as generation 22, PID 94056. The parent
  remained PID 77297 and the ready Fabric server remained PID 77325. Its startup
  and sampled live log window contained no OpenGL, driver sampler, fatal, or
  exception entry.
- The framebuffer capture showed world textures, font glyphs, GUI controls, and
  HUD textures present; the earlier black/unloadable presentation did not
  recur.

## Positional harvesting acceptance

Requested/resolved sound counters were not treated as playback proof. Some
server/world `minecraft:block.grass.break` events were correctly rejected
outside their attenuation radius and were unrelated to the targeted block.

A sustained debug-input harvest targeted stone directly in front of the player.
The block disappeared, and client debug state reported:

```text
lastStartedSound = minecraft:block.stone.break
lastStartedPosition = (0.5, 96.5, -23.5)
listenerPosition = (0.7488369, 97.53, -21.7)
startedSounds = 1
missingBufferSounds = 0
```

`AudioPlayer` keeps the source non-relative and assigns the block-center world
coordinate. It also synchronizes the listener from the current camera on the
audio thread before applying attenuation, covering camera/audio initialization
order.

## Mining-progress audio and arm acceptance

Survival mining now resolves the target state's sound group and emits its
`hit` event immediately, then no more often than every 200 ms. The cadence uses
monotonic time rather than input/render callback count, so low or high frame
rate cannot change the intended four-game-tick spacing. The completion tick
does not emit another hit; `BlockDestroyedHandler` retains the distinct
`destroy` event.

`BlockBreakAnimationS2CP` applies the same positional hit path for another
player's non-terminal break progress. It uses the block state at the packet's
world coordinate, ignores the local player entity ID, and does nothing for the
animation-clear stage. Focused integration coverage verifies remote position,
local-player suppression, and clear-stage suppression. A second live player
was not required for this acceptance run.

First-person arm timing now belongs to `PlayerEntity` through `ArmSwingState`;
it no longer depends on the disabled first-person copy of the third-person
skeletal model. `ArmRenderer` consumes the shared 200 ms progress and applies a
bounded transform. Live framebuffer captures at generation 27 showed the
resting hand and the visibly displaced mining hand while the target displayed
partial break progress. The initial transform was rejected because it moved
the hand entirely outside the viewport; the accepted transform keeps it
visible.

Generation 28 (PID 6733) then accepted the monotonic audio cadence without
restarting the server or parent. A short stone strike left the stone present
and reported:

```text
lastStartedSound = minecraft:block.stone.hit
lastStartedPosition = (1.5, 94.5, -14.5)
listenerPosition = (1.4786986, 95.53, -18.431198)
```

A later bounded press spanning about 350 ms at the live 8.6 FPS produced two
hit-source starts—one immediate and one after the 200 ms boundary—and left the
target present. The final targeted dirt-family block resolved
`minecraft:block.gravel.hit` at `(0.5, 94.5, -16.5)`.

## Automated acceptance

- Focused sizing, sound-index, and audio-control checks passed: 10 tests.
- Full Java 17 acceptance passed: 1,444 unit tests and 2,001 integration tests
  with zero failures or errors (116 integration skips).
- Focused mining additions passed: two shared-arm timing tests, one monotonic
  hit-cadence test, and three positional/local/remote mining-audio integration
  tests.
- Final combined Java 17 acceptance passed: 1,447 unit tests and 2,004
  integration tests with zero failures or errors (115 integration skips).
