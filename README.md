# The Hush

A NeoForge mod for Minecraft 26.2. A stranger arrives in your village the morning after a storm: **the
Traveller**, a villager who talks. Walk up and speak to him in plain language; he answers through Claude,
can look at the world, remembers you across sessions, and guides you down a road that ends somewhere under
the Deep Dark. Everything he tells you is true. Almost none of it is the truth.

He needs a language model to think with and, if you want to hear him, a voice. Both are services you sign
up for yourself and pay for directly; the mod holds your keys locally and never sends them anywhere but to
those services. The first half of this file gets you set up; the second half is for people who want to
change the mod.

---

# For players

## Requirements

- Minecraft **26.2** with **NeoForge 26.2.0.79** or later.
- A language model: an **Anthropic API key** (Claude, the best experience, costs money), **Ollama** running
  a local model (free, no key, quality depends on the model), or any **OpenAI-compatible server** such as a
  LiteLLM gateway, OpenAI, or OpenRouter. Without one he is in the world but has no voice at all.
- An **ElevenLabs API key** (optional) if you want him to speak aloud rather than only in chat.

## Install

1. Download `thehush-<version>.jar` from the [Releases](https://github.com/Ayodehi/thehush/releases) page.
   The version starts with the Minecraft version it was built for (`26.2-1` is for 26.2).
2. Drop it into your `mods` folder next to NeoForge.
3. Launch the game once. It writes `config/thehush-common.toml` with every setting and empty key fields.

## Set up the language model (required)

He can think through Claude (recommended), through a model running on your own machine via Ollama, or
through any server that speaks the OpenAI chat API, which includes LiteLLM.

### Option A: Claude

1. Create an API key at [console.anthropic.com](https://console.anthropic.com) (API keys under Settings).
   It begins with `sk-ant-`. Keep it private: anyone with the key can spend your credit.
2. Give it to the mod, in whichever way suits you:
   - **In game:** main menu or pause menu > Mods > The Hush > Config > *Language model* > *API key*. Saving
     the screen applies it at once, no restart.
   - **In the config file:** `config/thehush-common.toml`, section `[llm.anthropic]`, `apiKey = "sk-ant-..."`. The
     game reloads the file when you save it (or run `/hush reload`).
   - **In the environment:** export `ANTHROPIC_API_KEY` before launching the game (or the server) and leave
     `apiKey` empty. Good for servers, and it keeps the key out of any file you might share.
3. Pick a model in the same section. `claude-sonnet-5` is the recommended default for play: quick and
   cheap enough for a running conversation. `claude-opus-5` is noticeably deeper in character and several
   times the cost. `effort = "low"` keeps replies fast; raise it if you want him to think harder.

**What it costs.** Each line you say to him, and each thing he chooses to comment on, is one call. His
prompt is large but cached, so a call is typically a few cents on Sonnet. `/hush usage` shows calls, tokens,
and an estimated cost for the session and for the whole campaign; set `inputPricePerMTok` and
`outputPricePerMTok` in `[llm.anthropic]` if your rates differ from list price.

**What is sent.** Your chat lines addressed to him, what he can see around you (position, nearby creatures,
your inventory when he looks), his notes about you, and the story text. Nothing goes to Anthropic unless he
is within earshot or you are talking to him. Lines starting with `!` are ordinary chat and never sent.

### Option B: Ollama (local, free)

1. Install [Ollama](https://ollama.com) and pull a model that supports tool calling, for example
   `ollama pull llama3.1` or `ollama pull qwen3`. Tool calling is what lets him look around, follow you,
   and hand you things; a model without it can talk but not act, and the mod says so in the log.
2. In `config/thehush-common.toml`, or the *Language model* page in game:
   - `[llm]`: `provider = "OLLAMA"`
   - `[llm.ollama]`: `model = "llama3.1"` (the tag you pulled); `url` if Ollama is not on this machine at the
     default port; `think = true` only for reasoning models (qwen3, deepseek-r1) and only if you accept
     slower replies; `context` stays at 16k or more, since his prompt alone is about 10k tokens.
3. Make sure Ollama is running (`ollama serve`, or the desktop app). If it is not, he "mumbles" that Ollama
   is not running; if the model is missing, that it needs pulling.

Expect a different Traveller. Small local models follow his rules less closely, forget the [cues] the voice
needs, and call tools less reliably than Claude; a 14B or larger model with enough VRAM comes closest.
Nothing leaves your machine, and `/hush usage` shows tokens but no cost.

### Option C: an OpenAI-compatible server (LiteLLM, OpenAI, OpenRouter, LM Studio, ...)

Anything that serves `/v1/chat/completions` works. [LiteLLM](https://github.com/BerriAI/litellm) is the
usual reason to want this: one gateway in front of many providers, with its own keys, budgets, and
logging.

1. Have the server running and know its URL, a key if it wants one, and a model name it recognises. For
   LiteLLM that is the proxy URL (default `http://127.0.0.1:4000`), a virtual key, and one of the model
   aliases from its config.
2. In `config/thehush-common.toml`, or the *Language model* page in game:
   - `[llm]`: `provider = "OPENAI"`
   - `[llm.openai]`: `url`, `apiKey` (or export `OPENAI_API_KEY`), and `model`. `reasoningEffort` only for
     models that take it. Set `inputPricePerMTok` and `outputPricePerMTok` if you want `/hush usage` to show
     a cost; the mod cannot know what an arbitrary server charges, so zero means "unknown", not free.
3. The model must support tool calling for him to look around, follow, and hand things over. Servers that
   refuse tools get retried without them, and the log says so.
4. If the gateway fronts an Ollama model, set the context window on the gateway's side: the OpenAI API has
   no field for it, so Ollama would otherwise use its 4k default and cut off the start of his prompt. In
   LiteLLM that is `num_ctx: 16384` under the model's `litellm_params`.

## Set up the voice (optional)

He can speak through ElevenLabs (the best voices, paid, cloud) or through any server that speaks the
OpenAI speech API, which includes free local engines: Kokoro, Orpheus, and Chatterbox.

### Option A: ElevenLabs

1. Sign up at [elevenlabs.io](https://elevenlabs.io) and create an API key. Under the key's permissions
   give **Text to Speech: Access** (required) and **User: Access** (recommended: it lets the mod read your
   remaining credits, show them in `/hush usage`, and warn you before he goes silent).
2. In `config/thehush-common.toml`, section `[voice]`, or the *Voice* page of the in-game config screen:
   - `provider = "elevenlabs"`
   - `apiKey = "..."` (or export `ELEVENLABS_API_KEY` and leave it empty)
   - `voiceId`: the ID of any voice in your ElevenLabs library (Voices > the voice > ID). The default is
     the stock voice George. A voice you design for him works too.
   - `model`: `eleven_v3_conversational` is expressive, fast, and reads his delivery cues (`[low]`,
     `[whispers]`, `[afraid]`); `eleven_v3` is the most expressive but slower; `eleven_multilingual_v2` is
     lifelike without cues; `eleven_flash_v2_5` is fastest and uses half the credits.
3. Loudness is the game's *Voice/Speech* slider times `voice.volume`. His voice comes from where he stands,
   quieter when he whispers, louder when he calls out. Lines never overlap; `voice.lineGapSeconds` is the
   breath between them.

**Credits.** ElevenLabs bills by character. A line of his is 80 to 150 characters; a chatty hour is a few
thousand. When the account runs out, he falls back to text, a grey line in chat tells you why and when the
credits reset, and the mod stops trying until then. Same if the key is wrong: you are told what to fix.
`/hush status` shows the voice state and characters left; `/hush usage` shows the total spent.

### Option B: a local engine (Kokoro, Orpheus, Chatterbox) or OpenAI

All of these serve `POST /v1/audio/speech`, so they share one settings page. Set the voice `provider` to
`OPENAI` and fill in `[voice.openai]`:

| Engine | How to run it | `url` | `model` | `voice` | `cues` | Notes |
|---|---|---|---|---|---|---|
| **Kokoro** (Apache 2.0) | `docker run -p 8880:8880 ghcr.io/remsky/kokoro-fastapi-cpu` | `http://127.0.0.1:8880` | `kokoro` | `bm_george`, `bm_lewis`, `am_michael` | `NONE` | Fast on a CPU, clean English. The default settings. |
| **Orpheus** (Apache 2.0) | Orpheus-FastAPI, with the model served by llama.cpp or LM Studio | its port | `orpheus` | `tara`, `leo`, `dan`, `zac` | `ORPHEUS` | Expressive; his cues become inline tags such as a sigh or a gasp. Wants a GPU or a fast Mac. |
| **Chatterbox** (MIT) | chatterbox-tts-api | its port | `chatterbox` | a name or sample path | `NONE` | Voice cloning; set `extra` to `{"exaggeration": 0.6}` for more feeling. GPU recommended. |
| **OpenAI** | cloud | `https://api.openai.com/v1` | `gpt-4o-mini-tts` | `onyx`, `ash`, `echo` | `INSTRUCTIONS` | Needs `apiKey`; his cues go in the instructions field. Set `pricePerThousandChars`. |

`format` stays `WAV` unless you know the server returns raw PCM; `speed` is the pace. Where an engine
cannot take cues they are stripped and his pace nudged instead, as with older ElevenLabs models. A local
server that is not running gets him a grey "nothing is listening" line and text only until it is.

Kokoro is the one to start with: one Docker command, no GPU, and a line of his comes back in about a
second.

## How it plays

You arrive in a new world. A storm rolls in; when it passes, a grey line tells you where he is. Go and speak
to him. From there the road is his to point out and yours to walk.

- **Talking.** Type in chat when you are near him (8 blocks by default); he hears anything addressed to him.
  With several talking villagers about, the one you name, the one you were already talking to, or the one
  you are looking at gets it. Right-click one to talk to it explicitly; sneak and right-click, or
  `/hush bye`, to stop. Start a line with `!` to talk past him to other players.
- **He notices things.** Weather, nightfall, monsters, what you mine and build, your gear wearing out, you
  going hungry. He may say a line about it or keep quiet; roughly one remark every couple of minutes at
  most (`conversation.ambientRemarks` turns it off).
- **He remembers.** Things you tell him, what happened between you, how far you have come. His notes live
  in the world save and survive restarts. `/hush memories` shows what he has written down about you.
- **He can act.** Follow you, lead you somewhere, point out the nearest structure or ore, hand you an
  item, teleport you. Ask in plain words. He is reluctant with gifts; the road is not a merchant's cart.
- **He cannot die**, exactly. Kill him and he is back within a couple of minutes, wherever you are. Each
  return costs him something. You will notice.
- **Silence is an answer.** Sometimes he will not speak: at night, near certain things, or when the question
  is one he will not answer yet. That is him, not a fault. If he has truly lost his voice the mod says so
  in a grey line.

Commands you will use (`/hush ...`):

| Command | What it does |
|---|---|
| `bye` | End your conversation |
| `memories` | What he has written down about you |
| `status` | Model and voice status, characters left, who you are talking to |
| `usage` | Calls, tokens, characters, and estimated cost this session and for the whole campaign |
| `reload` | Re-read the config and keys (op) |
| `campaign status` | Where you are on the road (op) |
| `campaign reset` | Start the road over in this world (op) |

The title screen's splash text draws from his lines too.

## Troubleshooting

- **"I have no voice today"** in chat: no working Anthropic key. Check `[llm.anthropic] apiKey` or the environment
  variable, then `/hush reload`.
- **He "mumbles something you can't make out"**: the model call failed; the rest of the line says why
  (network, a bad key, a rate limit; with Ollama, that it is not running or the model is not pulled). It
  passes; ask again.
- **"is still thinking; he heard you"** on the action bar: he was mid-thought when you spoke. Your line is
  queued and answered next.
- **He speaks in text only**: the voice provider is off, the key is wrong, or the credits are gone. A grey
  line tells you which; `/hush status` shows the state.
- **The voice is faint or missing**: check the game's *Voice/Speech* slider, then `voice.volume`.
- **He answers nothing at all**: see *Silence is an answer* above. If it persists in daylight away from
  anything strange, `/hush status` will say whether the model is reachable.

Spoilers: `docs/narrative.md` is the whole story, including its ending. Read it after, not before.

---

# For contributors

## Development

Requires JDK 25. Gradle's toolchain support finds it once installed. If your shell's default `java` is
older, point `JAVA_HOME` at JDK 25 before running Gradle:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)   # macOS
./gradlew runClient      # launch the dev client with the mod (game directory: run/)
./gradlew runServer      # dedicated dev server (run/)
./gradlew test           # unit tests for the conversation loop, API client, sonar, view cone, voice
./gradlew build          # jar in build/libs/
```

Dev runs use `run/` as the game directory, so your keys go in `run/config/thehush-common.toml`; that
folder is ignored by Git. Every push to `main` builds and tests on GitHub Actions and keeps the jar as an
artifact.

## Architecture

- `llm/` is Minecraft-independent: `LlmProvider` (pluggable backend), `ClaudeProvider` (Messages API over
  Java's `HttpClient` + Gson, with prompt caching and refusal fallbacks), `OllamaProvider` (`/api/chat` with
  OpenAI-style tool calls, no cache, free), `OpenAiProvider` (chat completions for LiteLLM, OpenAI, and other
  gateways), and `ConversationEngine`
  (history, the ask -> tool -> ask loop, JSON persistence). All LLM work runs on virtual threads; the
  server tick is never blocked.
- `persona/` holds the bundled Traveller and builds the stable system prompt.
- `tools/` are his senses and actions; they run on the server thread via `ToolRegistry`.
- `conversation/ConversationManager` maps players to villagers, routes chat, and queues lines that arrive
  while he is busy. `AmbientObserver` produces the unprompted remarks.
- `entity/AiVillagerEntity` extends the vanilla `Villager`, swaps trading for conversation, saves the
  history with the entity, and carries his seat, follow, lead, and post behaviour.
- `voice/` is text to speech: `VoiceService` (per-speaker queue so lines never overlap, outage handling,
  credit report), `ElevenLabsVoice`, `OpenAiSpeechVoice` (the OpenAI speech endpoint: Kokoro, Orpheus,
  Chatterbox, OpenAI, LiteLLM; `Wav` decodes what comes back), `Speech` (cues and what is shown versus
  spoken); `client/VoiceClient`
  plays PCM through OpenAL as a 3D source that follows him.
- `campaign/` runs the story: `CampaignManager` (state, stage machine, beats, forgetting, prompt section),
  `Clues` (placed items and sculk), `Avoidance`, `NightSilence`, `LanternEvents`, `Unseen` (sound-only
  scares), `Quiet` (the far-future dimension builder), `HushEncounter` (the boss).
- `entity/PilgrimEntity`, `WickEntity`, `EchoEntity` (with `Sonar` and `EchoHunts`), `UnsaidEntity` are the
  creatures; `block/` holds the snuffed torch and dark soul lantern.
- `debug/DebugBridge` is a loopback HTTP server for live inspection; `tools/mcp/hush_mcp.py` wraps it as
  an MCP server so Claude Code can read the running world. See `tools/mcp/README.md`. Dev runs turn it on;
  otherwise `debug.bridge = true`.
- `client/` has the Traveller's model and renderer, the compass tell, the silence and drop effects.

## What the villager can do

Tools the model may call while composing a reply:

- `get_time_and_weather`, `get_location`, `look_around`
- `inspect_player`, `get_player_inventory`, `get_nearby_creatures`
- `locate` (nearest structure, biome, or point of interest), `find_block` (nearest blocks of a kind)
- `lead_player_to`, `stop_leading`, `send_player_to` (teleport), `give_item`
- `remember`, `forget` (long-term notes, stored in the world save)
- `follow_player`, `stop_following`, `walk_to_player`, `face_player`

Add a tool by implementing `NpcTool` in `com.ayodehi.thehush.tools` and listing it in `WorldTools.all()`.

## Personas and story text

The Traveller and the campaign definition are bundled in the jar; the mod writes nothing to
`config/thehush/`. To add a persona or override his text, create `config/thehush/personas/<id>.json` (the
id `traveller` replaces the bundled one) and spawn it with `/hush spawn <id>`. To change stage text,
reveals, beats, or the cost of each death, place `config/thehush/campaigns/the_hush.json`; it replaces the
bundled definition. Progress is per world in `<world>/thehush/campaign.json`; his notes are in
`<world>/thehush/memory/`; both are plain JSON.

A persona can set `"skin"` to a texture on the Traveller model's 128x64 layout (the generator in
`tools/textures` documents the map), `"voice"` for his sounds, `"returnsFromDeath"`, and `"campaign"`.

## How the campaign is built (spoilers)

The narrative is `docs/narrative.md`; the build plan and its deviations are `docs/implementation-plan.md`.
In brief:

- **Arrival.** A three-minute storm on first join (heat lightning where it cannot rain), then he is placed
  by the well of the nearest village, or near spawn, and a grey line gives direction and rough distance.
  The first player to speak to him is the chosen.
- **The road.** Stages advance in order from world triggers (advancements, structures, biomes, dimensions,
  items, flags, days). His prompt only ever contains the current and earlier stages. Stage entry queues a
  scripted beat; lingering earns a nudge; a quiet spell earns a guiding line grounded in what the player
  carries.
- **Deaths cost memory.** Thresholds hide the last day, the player's name, the next step, his fear, and
  finally darken his eyes. Notes stay in the file; only the prompt hides them.
- **The name.** Saying it within 32 blocks of him opens everything; near a sensor it is heard.
- **Clues in the world.** Villagers keep their distance from him. A compass spins beside him; a recovery
  compass points at him. The village bell carries a letter. Sculk grows on the well; a villager goes
  missing; near sculk at night he will not answer. The register book in the stronghold library. Silverfish
  leave him alone. After the dragon, sensors and a shriek. In the ancient city the soul lanterns go out
  toward the frame.
- **Creatures.** Pilgrims move only unwatched and grip from behind; soul fire and sunlight undo them.
  The Wick eats torchlight below y 20 and leaves snuffed torches. The Echo hunts the chosen by sonar on a
  timer once they have iron. The Unsaid drift over the soul sand valleys and are held by a voice.
- **The Unseen.** Sound-only scares for a player alone in the dark: footsteps behind, a heartbeat under
  the sculk, a breath of total silence and a click, a knock, something below, the bell at night.
  `/hush campaign unseen <kind>` plays one.
- **The frame, the Quiet, the Hush.** Ring a bell inside the frame to cross into `thehush:quiet`, a flat
  sculk dimension with the dead village built on entry; the throat beneath the well holds the three-phase
  encounter (Listening, Calling, the Answer) and the ending, after which he greets the player as a stranger.

Debug commands (ops): `/hush campaign status | stage <id> | flag <name> | chosen <player> | reset |
pilgrim | hunt [status] | unseen <kind> | snapshot | quiet | hush start|status|reset`. Every creature has a
spawn egg. `campaign.debugLog` logs flags, stages, and scares.

## Configuration reference

`config/thehush-common.toml` has these sections: `[llm]` (provider, output limit, timeout) with
`[llm.anthropic]` (key, model, effort, prices), `[llm.ollama]` (server, model, context, thinking), and
`[llm.openai]` (URL, key, model, prices) beneath it; `[conversation]`
(earshot, history length, remarks, return delay); `[voice]` (provider, volume, line gap, timeout) with `[voice.elevenlabs]` (key, voice, model, style, price)
and `[voice.openai]` (URL, model, voice, format, cues, extra fields) beneath it; `[campaign]` (enable,
arrival, creature toggles and rarities, unseen sounds, debug log); `[debug]` (the bridge). Every value has a comment in the file and a tooltip in the in-game screen.

## Releasing

Versions are `<minecraft version>-<build>`, for example `26.2-1`. To cut a release:

1. Bump `mod_build` in `gradle.properties` and commit.
2. Tag the commit `v<version>` and push the tag:

   ```
   git tag v26.2-2
   git push origin v26.2-2
   ```

The Release workflow checks the tag against `gradle.properties`, builds and tests, and publishes a GitHub
Release with `thehush-<version>.jar` attached.

## Licence

MIT; see `LICENSE`. Three textures (the dark soul lantern, the snuffed torch, and the Pilgrim) are derived
from Minecraft's own and remain Mojang's; they are not covered by the MIT licence. Minecraft is a trademark
of Mojang Studios; this mod is not affiliated with or endorsed by Mojang or Microsoft.
