<!-- Copyright (C) 2026 Jacob Repp -->

# Complementary motion noise and first-person hand

Date: 2026-07-28

## Outcome

The reported cloud and distant-foliage noise was isolated from terrain build
publication and duplicate scene submission. Complementary Unbound r5.8.1
deliberately uses spatially sparse samples for expensive effects and relies on
temporal accumulation to converge them. A live TAA-off A/B made the cloud layer
substantially grainier, while the active TAA history ping-pong and all
fullscreen families continued advancing without rejected or fallback routes.

The managed pack now keeps TAA enabled, selects its strongest authored
smoothing level, retains FXAA during camera movement, doubles the Unbound
cloud-ray sample density with the pack's high cloud-quality mode, and enables
8x anisotropic filtering for mipmapped terrain:

```text
CLOUD_QUALITY=3;ANISOTROPIC_FILTER=8;TAA_MODE=1;TAA_SMOOTHING=4;
FXAA_STRENGTH=85;FXAA_TAA_INTERACTION=0
```

This reduces motion shimmer at the cost of a modestly softer image. It does not
claim that the pack's stochastic cloud or foliage sampling is eliminated at
every frame rate.

The translucent first-person hand had a separate cause. Minosoft blended the
arm and sleeve, but its Iris scene defaults did not apply the pinned
`GREATER 0.1` alpha test to `ARM` and `HELD_ITEM`. Complementary's
`gbuffers_hand` samples texture alpha without discarding transparent texels
itself, so zero-alpha sleeve texels could still update depth. The scene-plan
default now applies Iris 1.7.2's `ONE_TENTH_ALPHA` behavior to both retained
hand routes while preserving an authored `alphaTest.<program>` override.

Enabling the authored anisotropic mode exposed another concrete Iris bridge
gap. Complementary changes its terrain diffuse sample from `texture(tex, uv)`
to a helper taking `sampler2D texSampler` and issuing repeated
`textureLod(texSampler, ...)` calls. Minosoft had already removed the impossible
flat-atlas sampler in favor of its source-native texture array, leaving those
identifiers undeclared. The modern terrain transformer now removes the helper's
sampler argument and routes each explicit LOD tap through
`minosoftSampleTextureLod`, retaining the selected texture array and layer.

## Evidence

- Pinned Iris 1.7.2 `ShaderKey` bytecode assigns `ONE_TENTH_ALPHA` to
  `HAND_CUTOUT`, `HAND_CUTOUT_BRIGHT`, `HAND_CUTOUT_DIFFUSE`,
  `HAND_TRANSLUCENT`, `HAND_WATER_BRIGHT`, and `HAND_WATER_DIFFUSE`.
- `IrisAlphaTestDefaults.scene` now maps `SceneStateAbi.ARM` and
  `SceneStateAbi.HELD_ITEM` to `IrisAlphaTest.ONE_TENTH`.
- `IrisLegacyShaderTransformerTest` checks both retained hand state ABIs.
- The same focused test class proves the anisotropic helper has no remaining
  `tex`/`texSampler` dependency and samples the source-native texture array.
- TAA-off fingerprint `ac227...` produced dense stochastic cloud grain in
  `2026-07-28_19.10.23.png`. The managed profile fingerprint
  `72427...` produced a settled high-cloud/anisotropic frame after a controlled
  five-degree yaw change in `2026-07-28_19.44.20.png`.
- The accepted real-OpenGL client reported about 70–78 FPS, all hand/cloud and
  fullscreen routes advancing, empty rejection and fallback maps, and a stable
  40-texture/7-framebuffer/111-program/0-shader Iris ledger.
- A client-only glass canary physically selected the corrected arm and
  held-item contracts in the accepted generation; the checked frame is
  `2026-07-28_19.48.00.png`, and the prior empty hand was restored exactly.
- The exact pre-test player position and orientation were restored, and
  `visual.prepare-reference` re-enabled all ordinary world producers while
  clearing transient overlays.

Screenshots and lifecycle logs remain trajectory-local under `.run/`; they are
not durable source artifacts.

## Verification

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
PATH=/opt/homebrew/opt/openjdk@17/bin:$PATH \
./gradlew :test \
  --tests de.bixilon.minosoft.gui.rendering.shader.pipeline.IrisLegacyShaderTransformerTest

./play.sh modpack inspect fabric-stack \
  --trajectory diverse-small-biomes-2026-07-28

./play.sh status --json
```

All 32 focused transformer tests passed. Pack preparation and adapted-mod
preflight passed with the updated hash chain. The supervised parent and server
PIDs remained stable for the original hand/temporal reload. The first
anisotropic candidate then failed on undeclared `tex`/`texSampler` identifiers;
after the transformer correction, a fresh supervised launch compiled the exact
high-quality profile on Apple OpenGL 4.1 and returned to joined/render-ready
state.

An attempted root filtered Gradle invocation was not a product failure:
`--tests` propagated to `debug-core`, which has no matching class. The scoped
`:test` invocation above is the repeatable command. Java 25 also cannot
configure this build; use the documented Java 17 runtime.

## Remaining boundary

The A/B establishes that the observed moving grain is pack-authored temporal
sampling, not a missing history swap or duplicate terrain draw. Remaining fine
noise during rapid movement or at low frame rates should be approached as a
quality/performance tradeoff: raise pack sample quality, simplify clouds, or
disable foliage waving. A renderer change is warranted only if a future
diagnostic shows broken previous-frame matrices, non-advancing history buffers,
or duplicate physical submissions.
