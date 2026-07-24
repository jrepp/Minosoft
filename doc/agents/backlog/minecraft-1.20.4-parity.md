<!-- Copyright (C) 2026 Jacob Repp -->

# Minecraft Java 1.20.4 parity

This is the acceptance backlog for making Minosoft behaviorally compatible with
Minecraft Java 1.20.4 without copying Mojang implementation code or copyrighted
content. Assets and display text must be Minosoft-authored or supplied by the
user's local system, resource packs, or installed mods. `minecraft:*` identifiers
remain valid compatibility keys; a key is not content provenance.

“Parity” is a measured behavioral and visual result, not a branding claim. A
surface moves from partial to verified only when its normal runtime path has an
automated assertion and, where presentation matters, a framebuffer comparison.

| Surface | Current evidence | Remaining acceptance gate |
| --- | --- | --- |
| Versioned assets | Session assets are priority-layered across project-local resources, user-local packs/caches, and installed mods. Official-service fallback downloads are disabled. | Add provenance metadata and screenshot a representative block, item, entity, GUI, font, particle, and sound event using a completely local 1.20.4-compatible pack. |
| Title and menus | Eros has a Minecraft-style title activity; pause, options, audio, lighting, mods, and debug pages are functional. | Match 1.20.4 layout, focus order, keyboard narration, button states, panorama, splash, accessibility, and every vanilla submenu. |
| HUD and inventory | Hotbar, first-person hands, containers, creative catalog, crafting, offhand, and debug HUD have focused evidence. | Pixel baselines for every HUD scale, status bar, effect, recipe book, creative tab/search state, tooltip, and container family. |
| Input and camera | Movement, double-tap sprint, arm swing, inventory keys, debug controls, and injected acceptance input use normal paths. | Differential movement/camera traces for walking, sprinting, swimming, flying, vehicles, damage, item use, and all configurable bindings. |
| Blocks and items | Version-aware registries and baked models cover ordinary blocks/items; entity-backed item fallbacks remain visible. | Complete special renderer, tint, animation, NBT/component, transform, particle, sound, and interaction coverage for every 1.20.4 registry entry. |
| Entities | Players and several mobs have dedicated renderers; zombies have six visible limbs; unknown living entities fail visibly. | Species-accurate model, layer, animation, equipment, pose, sound, AI-observation, and packet-state gates for every entity type. |
| World and lighting | Chunks, light, sky, fog, weather, Terralith, and Terratonic have runtime evidence. | Vanilla seed A/B worldgen, biome/color, weather, dimension, light propagation, block-entity, and render-distance comparisons. |
| Physics and gameplay | Session ticks, collision, mining, sprint, gamemode aliases, containers, and local authority paths have tests. | Differential tick traces for survival rules, combat, hunger, effects, enchanting, brewing, redstone, mobs, advancements, and death/respawn. |
| Protocol and multiplayer | The client has version-aware packet fixtures and a real 1.20.4 Fabric-server acceptance path. | Full login/configuration/play/disconnect packet corpus, encryption/auth, commands, resource packs, latency/loss, and adjacent-version regressions. |
| Audio and particles | OpenAL playback, positional sound, mining cadence, asset layering, and several particle paths are observable. | Event-by-event 1.20.4 sound selection/attenuation and particle count, motion, lifetime, texture, and render-state comparisons. |
| End credits | Original Minosoft credits and same-key local pack/mod contributions scroll through the normal GUI path. | Live win-event framebuffer sequence plus accessibility/narration comparison. |
| Headless and lifecycle | Headless operation, supervised client generations, endpoint cleanup, and repeatable scenario automation are verified. | Keep these gates green for every parity slice; visual parity must never make JavaFX/OpenGL mandatory for headless sessions. |

The next implementation priority is entity completeness: the generic living-mob
fallback is intentionally diagnostic and cannot count as Minecraft-equivalent
geometry or animation.
