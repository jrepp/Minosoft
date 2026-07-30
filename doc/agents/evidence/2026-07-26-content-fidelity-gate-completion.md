<!-- Copyright (C) 2026 Jacob Repp -->

# Content-fidelity continuation-gate completion

Date: 2026-07-26

## Decision

The bounded continuation gates for EMF, ETF, GeckoLib, Animated Java, and
their shared lifecycle are implemented and have current automated plus
real-render evidence. This decision is about the explicitly named source-native
content gates. It is not a claim that arbitrary upstream Fabric/OptiFine/
GeckoLib binaries link against Minosoft, nor that every optional producer
configuration or dependent-mod renderer behavior is supported.

## Requirement audit

| Required gate | Authoritative implementation | Completion evidence | Status |
| --- | --- | --- | --- |
| EMF neutral skeletal model | `SkeletalModel`, `SkeletalElement`, `SkeletalAnimationClip`, and `ContentFidelityLoader` keep producer data independent from OpenGL and upstream classes | CEM/Gecko parser tests and `ContentFidelityMultiVersionTest` load the same neutral path headlessly | Verified |
| EMF JEM/JPM parsing | CEM discovery parses JEM roots, external JPM parts, hierarchy, UVs, transforms, attachments, and animations; malformed candidates fail atomically | `CemParserTest`, `ContentFidelityLoaderTest`, and `ContentFidelityMultiVersionTest` | Verified |
| EMF part aliases | `SkeletalPartAliasRegistry` resolves entity/version mappings during binding and rejects collisions | Alias and binder tests cover version precedence plus player/zombie/cow/pig/sheep mappings | Verified |
| EMF expression VM | The bounded reflection-free evaluator covers the pinned public catalog, live aliased part reads, ordered absolute writes, persistent variables, render outputs, and shared bounded NBT predicates | Expression/context tests plus the checked zombie fixture where ETF rule 1 drives EMF `rule_index` | Verified |
| EMF renderer binding and reload | `SkeletalModelBinder`/`SkeletalModelComposer` bind retained geometry, replace only targeted native parts, preserve attach isolation, and transact model/texture generations | Zero-tolerance `emf-etf-zombie-render-reference` survives upload rejection, publication rollback, accepted recovery, and removal | Verified |
| ETF property selection and variants | The parser/catalog retain ordered rules, deterministic weighted suffix selection, bounded context predicates, and prior rule/suffix chaining | Property/cache tests, the multi-version fixture, and the health-selected zombie real-render fixture | Verified |
| ETF emissive/blinking passes | `EntityTextureMaterial` selects open/half/closed base and paired emissive frames; retained skeletal body/feature meshes are independently baked and selected | `SkeletalLoaderTest.native skeletal feature materials select independent ETF layers` proves half/closed/open ticks, paired blink/blink2 emissives, two geometry passes, and two emissive passes across reload | Verified |
| ETF cache ownership | Per-texture and prior-selection maps are bounded to 2,048 entities and owned by the exact content generation; retained models lease that generation | Cache eviction/closure tests, generation-store lease tests, and retained old-model reload integration | Verified |
| GeckoLib geometry/animation ingestion | The source-native parser retains bones, cubes, inflation, box/per-face UVs, channels, loop modes, interpolation/easing, and event timelines | Parser/evaluator/binder tests, multi-version fixture, and the pinned Naturalist geometry path | Verified |
| GeckoLib dependent-mod controller/API layer | Stable content identities own target-isolated routes, controllers, tracked/host inputs, event triggers, raw queues, caches, effect handlers, texture selectors, and render layers without exposing Mojang/Gecko binary types | Controller/API suites, owner-close tests, Naturalist adapter tests, and the four-controller real-render rattlesnake scenario | Verified |
| Animated Java item predicates/custom-model-data | Audited 1.19.4/1.20.4 legacy providers use last-match selection; display/world items reselect and rebuild on threshold changes | Item predicate unit/integration tests and the exact export's seven custom-model-data passenger assertions | Verified |
| Animated Java display entities | Item/block/text displays retain mapped transforms, billboards, light, shadows, text style, view bounds, teleport interpolation, passenger state, interaction state, and outline participation | Display entity tests, exact unmodified export integration, zero-tolerance default/walk references, and cleanup scenario | Verified |
| Animated Java data-pack execution | Session-owned data/resource views execute bounded functions, tags, schedules, macros, scoreboards/storage, selectors, execute/return, entity mutation, and interaction callbacks transactionally | Reduced and exact-export fixtures, eight headless runtime replacements, rejected-load rollback, live summon/walk/remove/reload scenarios | Verified |
| Headless operation | Parsers, controller managers, material selection, data-pack execution, and generation transactions do not require a render context | Focused unit/integration suites and `ContentFidelityMultiVersionTest` | Verified |
| Multi-version fixtures | Version-specific aliases and item predicate catalogs are selected per session without later-provider leakage | Durable 1.19.4/1.20.4 CEM/ETF/Gecko fixture plus item-predicate integration coverage | Verified |
| Transactional reload | Candidate parse/bake/upload precedes atomic publication; rejection preserves active generation, functions, retained instances, and stable texture coordinates | CPU transaction tests and checked upload/publication rejection plus accepted recovery for Animated Java, EMF/ETF, and Naturalist | Verified |
| GPU cleanup | Retired meshes/textures remain leased until final consumer release; candidate and shutdown resource names are typed and balanced | Dummy/static-slot tests, real-GL rejection deltas, cloud-grid cleanup, clean supervisor stop, and empty endpoint discovery | Verified |

## Current real-render records

The final combined `content-fidelity-completion` run used the current source
state and both managed fixtures:

```text
animated-java-render-reference-2026-07-26T22-57-34-491563Z-58113
animated-java-rejected-reload-2026-07-26T22-58-01-312756Z-58373
emf-etf-zombie-render-reference-2026-07-26T22-58-25-400271Z-58608
animated-java-render-cleanup-2026-07-26T22-58-39-584328Z-58733
```

All checked crops matched at zero tolerance. Both rejection points retired 21
buffers, 11 vertex arrays, and one texture with zero live delta in the combined
fixture set. The cleanup scenario passed, supervised stop cleared parent/client
PIDs, and debug endpoint discovery returned an empty array.

The Naturalist dependent-mod lane separately passed twice with one retained
252-vertex body pass, four controller summaries, exact rattle-loop selection,
timeline migration across content reload, removal, and endpoint cleanup. See
the [Naturalist registry-sync evidence](2026-07-26-naturalist-registry-sync.md).

## Acceptance robustness correction

The first completion reference attempt passed the default pose but failed the
walk crop because macOS focus loss reopened a pause/settings screen between
captures. This was an acceptance-environment overlay, not a changed model.
Multi-capture scenarios now repeat `visual.prepare-reference` immediately
before every checked screenshot. The unchanged baselines then passed.

Pack preparation also caught a stale `content-fidelity` ladder hash. The
`ladder.tsv` → `index.toml` → `pack.toml` hash chain was refreshed, after which
the pack prepared two resource packs, two data packs, and two content fixtures.

## Boundary after completion

The compatibility catalogs correctly remain `partial` relative to the full
upstream projects. Remaining work includes broader EMF aliases/configuration
and non-world routes, non-skeletal ETF bindings and independent pixel
references, Gecko GUI-item/armor-fit and additional dependent mods, remote
Animated Java execution, another driver, and checked Naturalist seam/overlay
pixels. Those are continuation opportunities beyond the explicit gate set;
they do not invalidate the implemented source-native foundations audited here.
