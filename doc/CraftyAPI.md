<!-- Copyright (C) 2026 Jacob Repp -->

# Crafty public API

Minosoft provides an opt-in client for Crafty's bearer-authenticated public API.
It does not contact Crafty during startup and does not automatically mount,
cache, or render any returned skin or generated image.

Set the token only in the process environment:

```sh
export CRAFTY_API_TOKEN="your-token"
```

Then construct the client outside JavaFX or rendering work:

```kotlin
val crafty = CraftyApiClient.fromEnvironment()
val status = crafty.pingServer("play.example.net:25565")
println("${status.players?.online}/${status.players?.max}")
```

The client supports every endpoint documented by Crafty API 1.0.0:

| Area | Client methods |
| --- | --- |
| Players | `searchPlayers`, `getPlayer` |
| Servers | `pingServer` for Java or Bedrock |
| Skins | `getRawSkin`, `getSkinByHash` |
| Image generation | `generateAchievement`, `generateDeathScreen`, `generateObserver`, `generateSign`, `generateSplashText`, `generateText` |

Player responses remain `JsonNode` because the public documentation does not
publish a stable response schema for those endpoints. Server ping has typed
version/player/description models. Skin and generated-image calls return
caller-owned bytes plus their response content type.

Non-2xx responses, malformed JSON, JSON error envelopes, and unexpected empty
binary responses throw `CraftyApiException`. The API client applies a 15-second
request timeout and never includes its bearer token in exception messages.
Crash-report environment output redacts token-, secret-, password-, key-, and
credential-shaped variable names.

Do not store the token in source control or a Minosoft profile. Callers are
responsible for Crafty's terms, rate limits, privacy rules, and the provenance
of any content they explicitly retrieve.

See [Crafty public API integration test plan](CraftyAPI-TestPlan.md) for the
deterministic, security, provenance, and opt-in live acceptance gates.
