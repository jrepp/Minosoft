<!-- Copyright (C) 2026 Jacob Repp -->

# Local asset provenance evidence

Date: 2026-07-24

## Boundary

Minecraft resource identifiers and translation keys are compatibility data and
may retain their normal strings. They do not authorize copying or downloading
the payload identified by a key.

Accepted payload providers are:

- Minosoft-authored resources packaged with this GPL project;
- files already present on the user's local system or explicitly imported into
  the Minosoft cache;
- user-selected resource packs and installed mods.

## Corrected behavior

- `JarAssetsManager` filters an already-present local client asset archive. If
  neither its filtered cache nor source archive exists locally, it fails with
  `LocalAssetUnavailableException`.
- `IndexAssetsManager` reads an already-present local index and verifies
  already-present indexed objects. Missing data produces the same local-import
  failure.
- The obsolete Mojang package, piston, and resource-service URL settings were
  removed from `SourceC`; neither asset manager has an official endpoint to
  format or open.
- `PlayerTexture` preserves the standard texture URL and hash as compatible
  profile metadata, but reads the image payload only from the local skin cache.
  A missing skin never opens the remote URL.
- Credits use `minosoft:texts/credits.json`; they do not read
  `minecraft:texts/end.txt`, `minecraft:texts/credits.json`, or
  `minecraft:texts/postcredits.txt`.
- Local packs and mods can contribute additional credits by providing the same
  Minosoft resource key.

## Validation

`LocalAssetSourceTest` verifies that present local bytes pass through unchanged
and missing bytes fail with a local-pack/mod instruction rather than a network
fallback. `PlayerTextureTest` holds that behavior at the skin-loading boundary.
Credits unit tests hold the namespace, provider ordering, and original fallback
copy. `CreditsAssetsIT` checks the live session provider is not a
`MinecraftAssetsManager`.

The source audit searches the asset and credit paths for the removed download
messages, official URL formatting calls, and Mojang credit resource paths.

## Acceptance result

On 2026-07-24:

- focused local-source, player-texture, credits-content, and credits-scroll
  tests passed;
- `CreditsAssetsIT` passed against the real session asset stack;
- `test`, `integrationTest`, `assemble`, `:debug-core:test`,
  `:play-util:installDist`, and `:debug-server-fabric:remapJar` passed together
  on Java 17;
- trajectory `local-asset-policy-2026-07-24` reached `both.ready` with Minecraft
  1.20.4 and the local `fabric-stack`: the client was joined, rendering, and
  playing while all seven adapted mods were active;
- `./play.sh stop` left no parent, server, client, debug, or port readiness
  state behind.
