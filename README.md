# The Hush

A NeoForge mod for Minecraft 26.2. A stranger arrives in your village the morning after a storm: **the
Traveller**, a villager who talks. Walk up and speak to him in plain language; he answers through Claude,
can look at the world through a set of tools, remembers you across sessions, and guides you down a road
that ends somewhere under the Deep Dark. Everything he tells you is true. Almost none of it is the truth.

## How it plays

1. Get a spawn egg from the creative *Spawn Eggs* tab, or run `/hush spawn [persona]`.
2. Walk up and type in chat. Any talking villager within earshot (8 blocks by default) can hear you; with
   several nearby, the mod picks the one you are addressing: by name first ("Traveller, ..."), then whoever
   you were already talking to, then the one you are looking at, then your travelling companion, then the
   nearest. Right-click a villager to pick it explicitly; sneak + right-click (or `/hush bye`) to end.
3. Start a line with `!` to talk to other players instead of the villagers.
4. Nearby players see both sides of the conversation.

Commands (`/hush ...`):

| Command | What it does |
|---|---|
| `spawn [persona]` | Spawn a talking villager in front of you (op) |
| `persona <id>` | Change the persona of the villager you are talking to (op) |
| `bye` | End your conversation |
| `memories` | Show what the villager you are talking to has written down about you |
| `forget` | Wipe the chat history and notes of the villager you are talking to (op) |
| `status` | Show provider status, personas, memory size |
| `reload` | Reload persona files and re-read the API key (op) |
| `usage` | API calls, tokens (with cache reads and writes), and estimated cost for this session and the whole campaign; `usage reset` clears the campaign ledger (op) |

Usage is metered per call in `<world>/thehush/usage.json` (the campaign ledger; it clears with
`/hush campaign reset`). The cost is an estimate from built-in list prices per tier (Opus 15/75,
Sonnet 3/15, Haiku 1/5 USD per million input/output tokens, cache writes at 1.25x and reads at 0.1x);
set `llm.inputPricePerMTok` and `llm.outputPricePerMTok` if your rates differ.

The title screen's splash text draws from the mod's own lines as well (`assets/thehush/texts/splashes.txt`:
hints, his sayings, and a few jokes); NeoForge merges them into the vanilla pool.

## Setup

Requires an Anthropic API key. The easiest way is in game: main menu (or pause menu) > Mods > The Hush >
Config > Language model > API key. Saving the screen applies the key immediately, no restart needed.
Alternatively export `ANTHROPIC_API_KEY` in the environment that launches the game/server, or edit
`config/thehush-common.toml` under `llm.apiKey` (then run `/hush reload`).

The Traveller is bundled inside the mod; nothing is written to your config folder. To add a persona, or
replace his text, create `config/thehush/personas/<id>.json` (a file with the id `traveller` overrides the
bundled one) and spawn it with `/hush spawn <id>`.

## Unprompted remarks

Villagers notice the world: rain starting or stopping, thunder, dusk, dawn, being caught in the open at
night, a monster close by, or you being badly hurt. They also notice what you do: ores you mine, a furnace,
bed, or torch you place, advancements you earn (congratulated promptly), running low on torches underground, the tool in your hand wearing out (once at a third of its life, once when it is about to break),
hunger with no food, a cold furnace nearby while you carry ore or fuel, or near-total darkness where he stands. Each observation is offered to the model, which may
answer with one line or stay silent. Cooldowns in code keep this rare (one remark per two minutes by
default, longer per kind of event); tune or disable it under `conversation.ambientRemarks` in the config.

## A voice

Villagers can speak their lines aloud. Set `voice.provider = "elevenlabs"` and an API key (`voice.apiKey`,
or the `ELEVENLABS_API_KEY` environment variable) in `config/thehush-common.toml`, or in the in-game config
screen under *Voice*; `voice.voiceId` picks the voice (the default is the stock voice George; any voice in
your ElevenLabs library works, including one you design for him), `voice.model` the model
(`eleven_multilingual_v2` for quality, `eleven_flash_v2_5` for speed and half the credits). Each line is sent
to ElevenLabs when he speaks; the chat text waits for the audio (up to `voice.timeoutSeconds`) so they
arrive together, and the sound is streamed to every player within about 24 blocks and played through
OpenAL as a 3D source that follows him, quieter and shorter-ranged in sculk country. The game's *Voice/Speech*
slider and `voice.volume` set the loudness. Stage directions in asterisks and coordinate triples are not
spoken. With `eleven_v3` he also gets **delivery cues**: his prompt lets him begin a line with a short cue
in square brackets (`[whispers]`, `[low]`, `[sharp]`, `[afraid]`, `[sighs]`, `[calls out]`), which the model
performs and the chat never shows; the mod adds `[whispers]` on sculk ground and `[calls out]` when the
player he's talking to is more than 14 blocks off, and a whisper is quieter and shorter-ranged in game, a
shout louder and further. Older models don't read cues, so they are stripped and the stability and speed
settings nudged instead. Characters are counted in `/hush usage` at `voice.pricePerThousandChars`. Without a provider, or
if the request fails, the text goes out as before.

## Long-term memory

Besides the recent chat history (saved with the entity and trimmed as it grows), each villager keeps notes:
things you told it about yourself, events between you, mission progress. The model writes them with the
`remember` tool when you say something worth keeping, and they are fed back into its prompt whenever you
talk. They live in the world save at `<world>/thehush/memory/<villager-uuid>.json`, human-readable and
editable. `/hush memories` lists them; `remember`/`forget` tools maintain them.

## Looks and voice

The Traveller has his own model (`client/TravellerModel`): a villager's head and nose under a hood, a cowl,
a long indigo robe with gold trim, arms that hang in wide sleeves, and boots under the hem, with his eyes
and the small lights on the robe glowing in the dark (a second emissive pass). A persona can set `"skin"`
to a texture id drawn on that model's 128x64 layout (the map is documented in the texture generator; the
bundled `thehush:textures/entity/traveller.png` is the reference). A `_dark` variant with the eyes gone
out is used after enough deaths. Pilgrims keep the vanilla villager model and its 64x64 layout.

## Returning from death

A persona with `"returnsFromDeath": true` (the Traveller) never really dies. On death his identity, chat
history, follow state, and companion are written to `<world>/thehush/returning.json`; about ninety
seconds later (`conversation.returnDelaySeconds`), wherever that player is and once no Warden is near them,
he is recreated with the same UUID (so his notes are still his), placed 12-20 blocks away, and walks over. His prompt gives him no concept of death, so questions about it
befuddle him and get waved away.

## The campaign: *The Hush*

A persona with `"campaign": "the_hush"` (the Traveller) plays through the story in `docs/narrative.md`.
The definition is bundled inside the mod. To change the stage text, reveals, beats, or what each return
from death costs, place a `config/thehush/campaigns/the_hush.json` of your own; it replaces the bundled one
and is never written by the mod. Progress is saved per world in `<world>/thehush/campaign.json`.

- **The arrival.** In a fresh world, the first join brings a three-minute thunderstorm and a voice under
  the thunder ("...not again. It is happening again."). Where it cannot rain (desert, savanna, badlands,
  snow) the storm comes as heat lightning instead: harmless bolts thrown some way off every few seconds,
  so the thunder is still heard. When it passes
  the Traveller is at the well of the nearest village (within about 450 blocks of spawn), or a short walk
  from your spawn if there is none, and a grey narrator line gives the direction and clickable coordinates.
  Off with `campaign.autoArrival`; `/hush spawn` still works for placing him by hand.
- **The chosen.** The first player to talk to the Traveller binds the campaign to themselves. Beats,
  nudges, and the ending key off the chosen; other players are companions.
- **The road.** Stages advance in order from world triggers (advancements, structures, biomes,
  dimensions, items, flags, days): arrival, geared, first_night, stronghold, end, dragon, deep_dark,
  ancient_city, named, quiet, ended. His prompt only ever contains the current and earlier stages, so
  later material cannot leak. Each stage entry queues a scripted beat; a stage he lingers on gets a nudge.
- **Deaths cost memory.** Each return from death raises a counter; at thresholds he loses the last day,
  your name, the next step, why the dark frightens him, and finally his eyes go dark (texture swap). The
  notes stay in the memory file; only the prompt hides them.
- **The name.** Saying "Vesper" in chat within 32 blocks of him opens everything. Near a sculk sensor or
  shrieker it is also *heard*, which brings the first shriek forward.
- **Pilgrims.** A hooded thing that moves only when no player has it in view (a wide cone, line of
  sight, and the darkness effect counts as not looking). Unwatched, it walks slowly toward anyone it can
  see (line of sight, 48 blocks) or hear (16 blocks, 24 if sprinting, not at all if you sneak); lose it and
  it goes to where it last knew you were, then waits. Every so often, when it knows where you are, it is
  simply closer: 4 to 6 blocks from you, where you are not looking, standing still. It never takes hold of
  anyone facing it, and the first two times it reaches you it only shows itself: suddenly right behind you,
  a low sound at your ear, standing there for a few seconds to be turned around on, then gone into the dark.
  The third time, its touch is a grip:
  for six seconds you cannot walk or jump, darkness falls, and it bleeds you a heart a second while
  anything nearby gets its chance; then it lets go, leaves you weak for twenty seconds, withdraws into the
  dark out of sight, and can come again after ten. It also takes one of his notes about you. Weapons do
  nothing; a soul torch or soul lantern placed within two blocks of it, while you watch it or while it
  holds you, crumbles it, and so does sunlight. Neither it nor the Traveller is heard by sculk.
- **The Wick.** A low, charcoal-dark thing with embers for a mouth that eats torchlight, met in caves below
  y 20 once you've lit any of them (a single torch within 24 blocks is enough; `campaign.wicks`,
  `campaign.wickRarity`). It drifts to torches and lit campfires that no player can see and touches them
  out: torches become **snuffed torches** (same shape, black head, a thread of smoke, no light; relight
  with flint and steel or a fire charge, or craft one with a coal back into a torch). It keeps to the dark
  and slips away when you come near with light; cornered, or hurt, it fights (20 health, 4 damage), and a
  blow that lands eats up to a stack of the torches you carry. It cannot pass soul
  fire and burns in daylight. The Traveller knows it and tells you what stops it.
- **The Echo.** The Hush's hunter, sent after the chosen once you have iron armor and until the road
  goes into the Quiet: one hunt every `campaign.hunterMinutes` (25, give or take a third; `campaign.hunters`
  turns them off). It is blind and works by sonar: you hear its clicks in the dark, and it hears feet
  (20 blocks walking, 32 sprinting or jumping, 6 sneaking, none standing still and sneaking; half through
  walls), and every eight seconds or so a ping finds you within 48 blocks whatever you do. It is placed
  30 to 50 blocks off, out of sight, in the Overworld or the Nether (never the Deep Dark, the End, or the
  Quiet), and stalks for one and a half to four minutes: holding a post 18 to 28 blocks from where it last
  heard you, moving it when you come within 14 or turn to look, never seen if it can help it. Then it
  shrieks, stands for a second and a half, and comes. It never despawns; a chase it cannot win on foot ends
  with it beside you again. Hit it while it circles and it comes at once. Balanced for iron armor and an
  iron sword: 50 health, 7 damage, a blow every second and a half, 4 armor, some knockback resistance;
  each later hunt adds 5 health and half a heart of damage, up to four times. The only way out is to kill
  it. The Traveller is afraid of them, warns you when one is on your trail, keeps away from it, and says
  a word when it is dead.
- **The Unsaid.** Grey ghosts in the Traveller's own shape (his model, translucent, three faded robes,
  a face that is only shadow with two pale lights for eyes), drifting over the Nether's soul sand valleys
  from the iron-armor stage until the road goes into the Deep Dark (`campaign.unsaid`,
  `campaign.unsaidRarity`, at most two near a player). They are what is left of the ones who crossed the
  frame and turned back. They whisper in chat, unattributed and grey: names from the register, fragments
  of what he has never said, half of his true name, and things *you* said to him. They drift toward him
  first if he is in the Nether with you, then toward you; a touch on him takes a note from his memory and
  gets a shaken line out of him, a touch on you does 4 damage and gives one of your own lines back. **Your
  voice holds them:** the moment anyone speaks in chat within 24 blocks, every one nearby stops for four
  seconds to listen. Fire and lava do nothing; steel does (24 health). Each one killed leaves a page with
  a name on it and a word from him. He sends you into those valleys himself: in the Nether he adds a
  third errand to the blaze rods and pearls, a stack of soul sand or soul soil for "torches that burn
  blue", reminds you every eight minutes while you're there without any, and notes (flag `has_soul`) when
  you have it.
- **Clues.** *A Register of Those Who Crossed* (written book) on a lectern in the stronghold library and
  sometimes in library chests; *Bell (V)* in a chest by the ancient city frame and sometimes in city
  chests; sculk on the village well on day 3; a villager gone on day 4; sensor patches after the dragon
  and a shriek with three seconds of darkness two nights later; silverfish that leave him alone.
- **The frame and the Quiet.** In the ancient city, the reinforced deepslate frame is found and recorded.
  Ring a bell while standing inside it: the frame lights and pulls everyone standing in it, him included,
  into the Quiet, a datapack dimension (`thehush:quiet`: flat sculk, no sky, no light) where the dead
  village, the well, and the throat beneath it are built on first entry. Music and ambient sound are
  muted there through a small network packet.
- **The Hush.** In the throat: Listening (sensors spawn wardens, cap three; wool, snowballs, and arrows
  work as in vanilla), Calling (full darkness, every sensor lit, shrieks; ringing a bell or hitting a note
  block pulls the wardens to the sound), the Answer (he walks to the heart and speaks; every warden turns
  on him; ring the pedestal bell within thirty seconds or he dies and it starts over), Sound (bells, note
  blocks, the End Poem in his voice, the sculk turning to stone).
- **The ending.** You wake at your bed at sunrise. The scripted sculk is gone. He is back where he
  arrived, remembers nothing of you (your notes are archived in his memory file, the Pilgrims' thefts
  restored), and greets you as a stranger. `/hush campaign reset` starts the road again.

Debug commands (ops): `/hush campaign status`, `stage <id>`, `flag <name>`, `chosen <player>`,
`reset`, `pilgrim` (one behind you), `hunt` (send an Echo now; `hunt status`), `snapshot` (write a text picture of your surroundings to `<world>/thehush/snapshot.txt`), `quiet` (cross now),
`hush start|status|reset`. The Pilgrim, the Wick, the Echo, and the Unsaid also have spawn eggs. `campaign.enabled` and `campaign.debugLog` are in the config.

## Debugging from outside the game
In dev runs the mod opens a small loopback HTTP bridge (`127.0.0.1:25599`) and the repo ships an MCP server
(`tools/mcp/hush_mcp.py`, registered in `.mcp.json`) so Claude Code can read the live world (players,
entities, the Traveller's history, notes and prompt, campaign state, a snapshot, the chat transcript), run
commands, speak as a player, tail the log, build, and relaunch the client. See `tools/mcp/README.md`.
Outside dev runs the bridge is off unless `debug.bridge = true` in the config.

## What the villager can do

Tools the model may call while composing a reply:

- `get_time_and_weather`, `get_location`, `look_around`
- `inspect_player`, `get_player_inventory`, `get_nearby_creatures`
- `locate` (nearest structure, biome, or point of interest, like `/locate`)
- `find_block` (nearest blocks of a kind within 16, with direction and depth), `lead_player_to`, `stop_leading`
- `send_player_to` (teleport), `give_item` (like `/give`)
- `remember`, `forget` (long-term notes about you, stored in the world save)
- `follow_player`, `stop_following` (pet-style: walks behind you, teleports to catch up, crosses dimensions)
- `walk_to_player`, `face_player`

Add a tool by implementing `NpcTool` in `com.ayodehi.thehush.tools` and listing it in `WorldTools.all()`.

## Development

Requires JDK 25. Gradle's toolchain support finds it automatically once installed
(`~/Library/Java/JavaVirtualMachines/temurin-25.jdk` on this machine). If your shell's default `java` is
older, point `JAVA_HOME` at JDK 25 before running Gradle:

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 25)
./gradlew runClient      # launch the dev client with the mod
./gradlew runServer      # dedicated dev server (run/ directory)
./gradlew test           # unit tests for the conversation loop and API client
./gradlew build          # jar in build/libs/
```

## Architecture

- `llm/` is Minecraft-independent: `LlmProvider` (pluggable backend), `ClaudeProvider` (Messages API over
  Java's `HttpClient` + Gson, with prompt caching and server-side refusal fallbacks), and
  `ConversationEngine` (history, the ask -> tool -> ask loop, JSON persistence).
- `persona/` loads persona JSON and builds the stable system prompt.
- `tools/` are the villager's senses and actions; they run on the server thread via `ToolRegistry`.
- `entity/AiVillagerEntity` extends the vanilla `Villager`, swaps trading for conversation, and saves the
  conversation history with the entity.
- `conversation/ConversationManager` maps players to villagers and routes chat.
- `campaign/` runs the story: `CampaignManager` (state, stage machine, beats, forgetting, prompt section),
  `Clues` (placed items and sculk), `Quiet` (the far-future dimension builder), `HushEncounter` (the boss).
- `entity/PilgrimEntity` is the watched-and-still creature; `entity/WickEntity` and `WickSpawner` are
  the light-eater and where it comes from; `block/SnuffedTorchBlock` is what it leaves behind;
  `entity/EchoEntity` is the blind hunter (`Sonar` is its hearing, `EchoHunts` decides when one is
  sent); `entity/UnsaidEntity` and `UnsaidSpawner` are the Nether ghosts; `network/HushSilencePayload`
  mutes the client.

All LLM work happens on virtual threads; the server tick is never blocked.

## Licence

MIT; see `LICENSE`. Three textures (the dark soul lantern, the snuffed torch, and the Pilgrim) are derived
from Minecraft's own and remain Mojang's; they are not covered by the MIT licence. Minecraft is a trademark
of Mojang Studios; this mod is not affiliated with or endorsed by Mojang or Microsoft.
