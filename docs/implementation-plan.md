# Implementing *The Hush*: step-by-step plan

This plan turns `narrative.md` into mod features, in the order that makes the Traveller a guide as early as
possible. Every phase ends with something playable. Phases 1 and 2 are the heart of it: after them the
Traveller knows where the player is on the road, steers them toward the next step, reveals himself in
tiers, and pays for every death with a memory. Later phases add the clues, the dread, and the ending.

Conventions used below: "he" is the Traveller; "the chosen" is the player the campaign is bound to; file
paths are relative to `src/main/java/com/ayodehi/thehush/`.

---

## Phase 0: Foundations (half a session)

Small changes that every later phase leans on.

1. **Campaign file per world.** `memory/CampaignStore` alongside `MemoryStore`, writing
   `<world>/thehush/campaign.json` with the same atomic-save pattern. Fields: `campaignId`, `stage`,
   `chosenPlayer` (UUID), `flags` (set of strings), `stageEnteredDay`, `stageEnteredTick`, `deaths`,
   `forgotten` (set of strings), `history` (list of `{stage, day}`).
2. **Campaign definition file.** `config/thehush/campaigns/the_hush.json`, written when missing like
   personas are. Persona JSON gains `"campaign": "the_hush"`; only a persona with a campaign takes part.
3. **Debug commands.** `/hush campaign status`, `/hush campaign stage <id>` (op; jumps and
   fires the stage's entry beat), `/hush campaign flag <name>`, `/hush campaign reset`.
   These make every later phase testable in minutes instead of hours.
4. **Chosen player.** The first player who engages a campaign persona becomes the chosen. Others are
   "companions": he talks to them, remarks to them, but guidance, reveals, and the ending key off the
   chosen. Stored in the campaign file; `/hush campaign chosen <player>` overrides.

Acceptance: campaign file appears on first engagement with the Traveller; status command prints stage
and flags; jumping stages persists across restart.

---

## Phase 1: The road (one session)

Goal: he knows what step the player is on and guides them toward the next one, and what he will admit
grows with progress.

### 1.1 Stage machine

`campaign/CampaignStage` loaded from the definition file. Each stage:

```json
{
  "id": "stronghold",
  "title": "The Halls Below",
  "enter": { "any": [
    { "structure": "minecraft:stronghold" },
    { "advancement": "minecraft:story/follow_ender_eye" }
  ]},
  "purpose": "Get the player into the stronghold and to the portal room. You will not enter the portal room yourself; the door remembers you.",
  "guidance": [
    "Eyes of ender are made from blaze powder and ender pearls; thrown, they drift toward the nearest stronghold.",
    "The library holds a register. You go quiet in front of it and will not say why."
  ],
  "reveal": [
    "You know these halls too well: 'We built the wells shallow so the water would not carry sound.'"
  ],
  "onEnter": "Ben has entered a stronghold for the first time. You know every corridor. Something in the library frightens you.",
  "nudgeAfterMinutes": 20,
  "nudge": "Ben has been in or near the stronghold a while without reaching the portal room."
}
```

Trigger kinds to support, all checked on the server thread by a `campaign/CampaignTracker` ticking once
a second for the chosen player: `advancement` (from the existing `AdvancementEarnEvent` hook),
`structure` (player inside a structure, via the level's structure manager), `biome` (e.g.
`minecraft:deep_dark`), `dimension` (e.g. `minecraft:the_end`), `item` (player carries an item id, for the
bell and for wool), `flag` (set by other systems: name spoken, frame lit), `daysSince` (for time-gated
beats). `enter` takes `any`/`all` combinators. Stages advance in order only; a later trigger firing early
sets a flag but does not skip.

Stages for *The Hush*, in order: `arrival`, `geared` (iron armor advancement), `first_night` (survived a
night; `daysSince: 1`), `stronghold`, `end` (dimension), `dragon` (advancement `end/kill_dragon`),
`deep_dark` (biome), `ancient_city` (structure), `named` (flag `name_spoken`), `quiet` (dimension, Phase 5),
`ended` (flag).

### 1.2 Prompt integration

`persona/PromptBuilder.systemPrompt(persona)` gains a campaign section built from the current stage:

- *Your purpose now:* the stage's `purpose`.
- *What you know that helps:* the stage's `guidance` lines plus all earlier stages' guidance.
- *What you may reveal about yourself:* the `reveal` lines of the current and earlier stages. Everything in
  later stages is simply absent from the prompt, so it cannot leak.
- A standing rule: "Guide, don't narrate. Point at the next step when asked what to do, or when the player
  stalls. Never describe steps beyond the next one."

Cache note: the campaign section changes only on stage change, so the cached prefix survives normal play.

### 1.3 Stage beats and nudges

- On stage entry, `ConversationManager.remark` is called with the stage's `onEnter` text, bypassing the
  global cooldown (it is a scripted beat, not ambient noise).
- `AmbientObserver` gets a `CAMPAIGN` kind: if the chosen has been on a stage longer than
  `nudgeAfterMinutes` and is within earshot, fire the stage's `nudge` text once per 10 minutes.
- "What should I do?" needs no code: the purpose line answers it.

### 1.4 Tests

- Unit: stage machine ordering, `any`/`all` triggers, early triggers becoming flags, JSON round trip.
- In game: `/hush campaign stage stronghold` then ask "where are we going"; confirm he mentions the
  portal room and refuses to enter it; confirm the End Poem is not mentioned yet.

Acceptance: a fresh world, no commands, played from arrival through the stronghold, has him steering at
each step and never revealing later-tier material.

---

## Phase 2: The cost of dying (half a session)

Goal: each return from death takes something, and the player can notice.

1. `entity/ReturnRegistry.bringBack` increments `deaths` in the campaign file.
2. The definition file lists losses:
   ```json
   "forgetting": [
     { "deaths": 1, "forget": "recent_days", "prompt": "You have lost the last few days; you remember the player but not what you did together lately." },
     { "deaths": 2, "forget": "player_name", "prompt": "You no longer remember the player's name. Do not guess it; ask, and be quietly ashamed of asking." },
     { "deaths": 4, "forget": "mission_next", "prompt": "You no longer remember the next step of the road. You know there is one." },
     { "deaths": 6, "forget": "own_fear", "prompt": "You no longer remember why the dark frightens you. It still does." }
   ]
   ```
3. `PromptBuilder` applies them: `player_name` hides the name from the prompt and from the notes section
   (`VillagerMemory.promptSection` takes an `omitName` flag); `recent_days` drops notes younger than 3 days
   from the prompt (not from the file); `mission_next` blanks the purpose line; `own_fear` swaps the
   persona's darkness paragraph for the loss line. All losses append their `prompt` text so the model plays
   the gap instead of papering over it.
4. `/hush campaign status` shows deaths and what is forgotten; `/hush memories` marks hidden
   notes with `(forgotten)`.

Acceptance: kill him twice with `/kill`, wait for two dawns, and he asks your name; the note with your name
is still in the JSON file.

---

## Phase 3: The name (half a session)

Goal: the campaign's trap works live.

1. `conversation/NameTrigger`: on `ServerChatEvent` (already hooked), if the message contains the
   campaign's `trueName` as a whole word, case-insensitive, and the speaker is within 32 blocks of the
   Traveller: set flag `name_spoken`. If a sculk sensor or shrieker is within 16 blocks of the speaker,
   also set `heard`, which fast-forwards the endgame timer (Phase 6).
2. Stage `named` enters on `name_spoken`: its `reveal` is everything; its `onEnter` beat is the confession.
   With `heard` set, the beat text adds "and something turned toward the sound."
3. His name is never in the prompt before this stage. The register book (Phase 4) is the only source. If a
   player guesses it cold, that is a legitimate win.

Acceptance: say the name in chat next to him and the confession fires; say it 100 blocks away and nothing
happens.

---

## Phase 3b: The Pilgrims (one session)

Goal: a creature that only moves when unobserved, tied to the Traveller's fate.

1. **Entity.** `entity/PilgrimEntity extends PathfinderMob`, registered like the villager, rendered with
   the villager model and its own texture (hood down over the face, no eyes, grey cloth). Silent: no ambient,
   hurt, step, or death sounds. Never despawns while the campaign is active.
2. **Observed check.** Each server tick: for every player within 48 blocks, observed if the player has
   line of sight (`hasLineOfSight`) and the Pilgrim is inside the player's view cone (dot product of the
   look vector and the direction to the Pilgrim above about 0.35, a generous cone so peripheral vision
   counts) and the player does not have the darkness effect. Synced to the client as a boolean so the
   renderer can zero the limb swing while frozen.
3. **Movement.** Observed: navigation stopped, velocity zeroed, invulnerable to all damage. Unobserved:
   it is aware of a player it can see (line of sight, 48 blocks) or hear (16 blocks unless sneaking, 24
   when sprinting). Aware, it walks toward them at 0.6x villager speed and, every 8 to 15 seconds while the
   player is 10 to 40 blocks off, reappears 4 to 6 blocks from them at a spot outside every watching
   player's view cone, and stands still there. Unaware, it walks to where it last knew the player to be
   for ten seconds, then stands. The Traveller and the Pilgrims dampen vibrations, so sculk never hears
   them.
4. **The scare, then the touch.** Within 1.5 blocks, unwatched for at least ten ticks, the player not
   facing it (view cone alone, no line-of-sight test), and not within ten seconds of a previous grip. The
   first two times (`scares`, saved with the entity) it only appears 1.8 blocks behind the player with a
   low sound, stands frozen for 70 ticks whether watched or not, then slips away unseen. After that it takes
   hold. For 120 ticks the player has Slowness 11 and a negative jump boost (no walking, no jumping) and
   darkness, and takes 2 magic damage every 20 ticks; a heartbeat sound and sculk particles mark each pull.
   On release the player gets Weakness for 400 ticks and the Pilgrim snaps 8 to 12 blocks away to a spot no
   player can see, frozen, with a 200-tick cooldown before it may grip again. One random note about the
   player is moved from the Traveller's memory to `taken` (restored at the ending). A soul torch or soul
   lantern within 2 blocks during the grip releases the player and starts the crumble. A campaign counter
   records touches.
5. **Destruction.** While observed, if a soul torch, soul lantern, or soul fire is within 2 blocks: crumble
   over 60 ticks (grey particles, the sculk-catalyst bloom sound), then remove and drop nothing. In direct
   sunlight (sky light 15, daytime) the same crumble starts on its own.
6. **Placement.** `campaign/PilgrimEvents`: after `dragon`, place one in the nearest stronghold library
   piece, in a corner not visible from the door. At `ancient_city` entry, place four in unlit alcoves of
   the city. In the Quiet structure they are part of the build. A `/hush campaign pilgrim` command
   spawns one for testing.
7. **The Traveller.** Guidance line at first sighting: "Do not look away from it. Do not ask me why."
   Reveal at tier 4. At 6+ deaths, swap his texture to a variant with the eyes darkened (a second PNG and a
   synced flag), and add the loss line "You have stopped counting your crossings."
8. **Tests.** Unit test for the view-cone math; in game, stand still and watch one for a minute (it must
   not move), turn your back within earshot for ten seconds (it must be nearer, and eventually beside you),
   sneak away out of its sight (it must go to where you were and stop), let it take hold (you cannot move;
   a heart a second), place a soul torch beside it while held (it must let go and crumble), and place one
   beside it while watching (it must crumble).

---

## Phase 3c: The Wick (one session, built)

1. **Blocks.** `block/SnuffedTorchBlock` and `SnuffedWallTorchBlock` extend the vanilla torch classes with
   no light and an occasional smoke particle; flint and steel or a fire charge relights them in place;
   a shapeless recipe (snuffed torch + coal) gives a torch back. One item (`StandingAndWallBlockItem`).
2. **Entity.** `entity/WickEntity extends Monster`, 20 health, 0.3 speed, 4 damage, rendered with the
   vanilla vex model at 1.6x in a charcoal texture with an ember mouth. Goals: cornered melee (only while
   hurt by a player or trapped), flee into the darkest reachable spot when a player is within 10 blocks and
   it stands in light (no refuge within 12 blocks marks it cornered), snuff (the nearest torch or lit
   campfire within 24 blocks that no player within 40 can see, not within 3 of soul fire; abandoned if
   someone looks while it is at the block), random stroll. Hits eat up to 16 carried torches. Soul fire within 2 blocks hurts it; daylight ignites it.
3. **Spawning.** `entity/WickSpawner`: every 5 s per player in the Overworld, underground, below y 20, with
   at least one torch within 24 blocks and no Wick within 64: a 1-in-`wickRarity` chance to place one on a
   dark, out-of-sight floor 16 to 32 blocks away.
4. **The Traveller.** Arrival-stage guidance on what it is and what stops it; a once-per-campaign beat on
   first sight; a beat, at most every 8 minutes, when a torch is snuffed within 48 blocks of the chosen.

## Phase 3d: The Echo (one session, built)

1. **Entity.** `entity/EchoEntity extends Monster`, rendered with the vanilla enderman model at 0.9x in a
   bone-pale texture with no eyes (`client/EchoRenderer`; the strike uses the enderman's "creepy" shake).
   50 health, 0.3 speed, 7 damage, 4 armour, 0.3 knockback resistance, 64 follow range; never despawns.
   Three phases: STALK (hold a post 18 to 28 blocks from where it last heard the prey, out of the prey's
   view cone and line of sight, dark for preference; move it when the prey comes within 14, looks at it for
   two seconds, or the post goes stale; if it cannot path there for six seconds it is placed there unseen),
   WINDUP (shriek, 30 ticks still), STRIKE (melee at 1.25x speed, a blow every 30 ticks, no line-of-sight
   check; 64 blocks behind for 15 s or stuck for 8 s puts it 6 to 10 blocks from the prey again). Hearing is
   `entity/Sonar` (unit tested): walking 20, sprinting or airborne 32, sneaking 6, still and sneaking 0,
   halved through walls; a ping every 8 to 12 s (2 s while striking) finds the prey within 48 regardless.
   Hurt by its prey while stalking, it strikes at once. Lost for 30 s, it is placed near again. Prey gone
   from the level (death, portal) fades it.
2. **Scheduling.** `entity/EchoHunts`: from the `geared` stage through `named`, a wait of `hunterMinutes`
   (give or take a third) then one placed 32 to 48 blocks from the chosen in the Overworld or the Nether
   (not in the Deep Dark biome, not creative or spectator), one at a time; state in `CampaignState`
   (`nextHuntTick`, `hunts`, `echoesKilled`, `echo`). Death reschedules (flag `echo_killed`); a fade
   reschedules sooner. Config `campaign.hunters`, `campaign.hunterMinutes`.
3. **The Traveller.** A `geared` guidance line on what they are and what to do; a scripted beat when one is
   sent (longer the first time), a beat when one is killed, a place beat within 20 blocks every 6 minutes;
   he runs from any Echo within 14 blocks.
4. **Commands.** `/hush campaign hunt` sends one now; `hunt status` reports.

## Phase 3e: The Unsaid (one session, built)

1. **Entity.** `entity/UnsaidEntity extends Monster`, fire immune, no gravity, `FlyingMoveControl` and
   `FlyingPathNavigation`, 24 health, 4 damage, flying speed 0.14. Rendered with `TravellerModel` through
   `RenderTypes.entityTranslucent` (`client/UnsaidRenderer`), three texture variants picked at spawn and
   saved, a bob in `getRenderOffset`, and an eyes glow layer. One goal: drift toward the campaign villager
   within 48 blocks (unless it touched him in the last 20 s), else the nearest survival player within 32,
   else wander; touch within 1.7 blocks. Whispers every 10 to 22 s to players within 24: 40% one of the
   player's own recent lines to him (`ConversationEngine.recentUserLines`, falling back to his notes),
   20% a register name, else a fragment. `onSomeoneSpoke` (server chat hook) makes every one within 24
   blocks stop for 90 ticks. Death drops a named paper and queues a beat; a touch on him takes a note
   (`takeRandomNoteAbout`) and queues a beat, both at most every 3 minutes.
2. **Spawning.** `entity/UnsaidSpawner`: every 5 s per survival player in a Nether soul sand valley, stages
   `geared` through `deep_dark`, 1-in-`unsaidRarity`, at most two within 48 blocks, in open air 16 to 32
   blocks off out of the player's view.
3. **The Traveller.** A `geared` guidance line; place beats `unsaid_seen` (once) and `unsaid_near` (every
   8 min); he runs from any within 10 blocks.

## Phase 4: Clues and dread in the world (one session)

Goal: the player can uncover the story from the world, not only from him.

1. **The register.** A written book item, *A Register of Those Who Crossed*, generated in code with the
   names, the scratched entry, and the last page. Added to `minecraft:chests/stronghold_library` through a
   NeoForge global loot modifier (datapack JSON plus a small `IGlobalLootModifier`), one per stronghold at
   most. Also placed on a lectern in the library if the loot path is missed: on `stronghold` stage entry,
   find the nearest library piece and set a lectern with the book.
2. **The bells.** The village bell nearest the Traveller's arrival point is renamed *Bell (V)* by replacing
   its block entity's custom name at `arrival`. A second bell item, *Bell (V)*, goes into
   `minecraft:chests/ancient_city` via the same loot modifier, and is placed beside the frame on
   `ancient_city` entry as a fallback.
3. **The compass tell.** No code: recovery compasses point at the last death location, and he dies. Note it
   in `guidance` so he reacts if asked ("Put that away").
4. **Sculk escalation.** `campaign/SculkEvents`: on `arrival + 3 days` place a sculk vein on the village
   well (the bell POI's nearest water/well block); on `dragon` place a sensor patch near the village edge
   and one at the stronghold entrance; two nights later place a shrieker there and, at night, fire it once
   (`level.playSound` shriek plus `MobEffects.DARKNESS` for 60 ticks on players within 24 blocks). The
   Traveller's beat for that night is scripted.
5. **The missing villager.** On day 4 pick one named villager within 32 blocks and remove it. His
   `guidance` gets the name, and a rule: he knows, will not say it, and flinches if asked.
6. **Silverfish.** Small mixin or `LivingChangeTargetEvent` listener: silverfish never target an
   `AiVillagerEntity` with the campaign persona.

Acceptance: a stronghold library chest contains the register; reading it gives the name; the well grows
sculk on day 3; the shrieker beat plays after the dragon.

---

## Phase 5: The Deep Dark and the Quiet (two sessions)

Goal: the descent, the frame, and the crossing.

1. **Wool.** At `deep_dark` entry, his beat asks for wool; the `item` trigger on wool sets `has_wool`, and
   his guidance changes to sneaking and wool-laying advice. Without wool, his nudges are warnings only.
2. **Warden avoidance.** In lead mode, `AiVillagerEntity.tickLeading` gets a "quiet" variant: never
   sprint, stop when a warden is within 20 blocks, and, when following, refuse to move while the darkness
   effect is on the chosen. He whispers: the remark prompt for this stage says so.
3. **The frame.** `campaign/FrameDetector`: at `ancient_city` entry, find the reinforced deepslate frame
   near the city center by scanning for the ring. Store its center in the campaign file.
4. **Lighting the frame.** A bell block placed within the frame and rung (`BellBlock` use, via
   `PlayerInteractEvent.RightClickBlock`) with the chosen inside the frame: set flag `frame_lit`, play the
   sculk shrieker and end portal sounds, fill the frame interior with a custom `hush_portal` block (a
   non-solid, light-emitting block that teleports on contact, like an end gateway), and fire the beat.
5. **The Quiet dimension.** Datapack JSON: `dimension_type` (no skylight, ambient light 0, fixed time),
   `dimension` with a flat or void generator, and a single jigsaw or hand-built structure: the dead village
   around the well with the throat beneath it. First version: a hand-built structure NBT exported from a
   creative build, with sculk, soul lanterns, note blocks in the walls, and a bell pedestal in the throat.
6. **Silence.** A custom network payload `HushSilencePayload(strength)` sent on dimension entry; the client
   handler lowers ambient and music volume categories to zero while in the Quiet and restores them on
   leaving, and applies the darkness effect at half strength client-side. Server keeps the chosen and the
   Traveller in the darkness effect the whole time.
7. He crosses with the player (teleport when the player does, via the follow logic's cross-dimension path).

Acceptance: ring the bell inside the frame with him beside you; both arrive in a silent, dark village of
sculk; leaving restores sound.

---

## Phase 6: The Hush (two sessions)

Goal: the boss, built from vanilla parts and a phase controller.

1. `campaign/HushEncounter`: a server-side controller created when the chosen enters the throat structure's
   trigger volume. It owns the phase, timers, spawned wardens, and the bell state, and ticks with the level.
2. **Phase 1, The Listening.** Sensors in the chamber are real sculk sensors; a listener on
   `VibrationSystem` events (or simply on sensor activation via block state change) spawns a warden from a
   wall spawn point when any sensor near the chosen activates. Wool placed by the player works as in
   vanilla. Snowballs and arrows work as in vanilla. Cap at 3 wardens alive.
3. **Phase 2, The Calling.** Triggered when the chosen reaches the center or after 4 minutes. Full darkness
   on the chosen, every sensor lit (block state), a shriek sound every 10 seconds, wardens re-target the
   chosen. Ringing any bell in the chamber makes wardens target the bell position for 8 seconds (they are
   given a temporary target entity, an invisible marker at the bell). Note blocks in the walls do the same
   with a shorter pull.
4. **Phase 3, The Answer.** After 90 seconds of Phase 2, or when the chosen rings the bell three times, the
   Traveller walks to the heart (lead mode to a fixed point) and his scripted confession plays as a remark
   sequence (three lines, 5 seconds apart, bypassing cooldowns). Every warden retargets him. The chosen has
   30 seconds to reach the heart and ring the pedestal bell. Success: Phase 4. Failure: he "dies" (the
   registry stashes him as usual) and the encounter resets to Phase 1 with the darkness lifted; the
   campaign records a failed attempt, which his forgetting reacts to.
5. **Phase 4, Sound.** All note blocks play, the bell rings on a loop, the silence payload is lifted, the
   End Poem is read as a sequence of chat lines in his color, the chamber's sculk blocks are replaced with
   deepslate in an expanding sphere over 10 seconds, and the wardens dig down (vanilla `Warden.setDigging`
   behaviour via their brain, or simply removed with the dig animation sound). Then the ending.

Acceptance: the encounter can be started with `/hush campaign stage quiet` plus a `/hush
campaign hush start` command, completed with a bell, and failed by standing still; each path leaves the
campaign file consistent.

---

## Phase 7: The ending and the loop (half a session)

1. Teleport the chosen to their bed (or spawn) at dawn, set the time to sunrise, clear the darkness effect,
   remove the scripted sculk placed in Phase 4 (recorded positions), and remove the sculk vein from the
   well.
2. Move the Traveller to the village bell, set stage `ended`, and set his memory flag `strangerToPlayer`:
   the prompt hides every note about the chosen and adds "You have never met this person. You have been
   waiting for someone; it may be them." The notes stay in the file under an `archive` key, for the second
   playthrough's payoff.
3. The greeting line plays once more.
4. `/hush campaign reset` restores everything for another run.

---

## Cross-cutting work

- **Persona definition changes.** `secrets` stays for non-campaign personas. Campaign personas draw their
  tiered reveals from the campaign file so the persona JSON stays readable.
- **Prompt size.** Guidance and reveals accumulate; cap the campaign section at roughly 600 tokens by
  keeping only the last three stages' guidance in full and one line per earlier stage.
- **Multiplayer.** Beats and nudges go to the chosen; reveals apply to anyone he talks to (he does not track
  who knows what; the story does). Companions can ring the bell but the ending keys off the chosen.
- **Config.** `campaign.enabled` (default true when a campaign persona exists), `campaign.debugLog`.
- **Documentation.** `docs/narrative.md` is the source of truth for text; stage `purpose`, `guidance`,
  `reveal`, and beats are copied from it into `the_hush.json` and kept in sync by hand.

## Status (as built)

All phases are implemented. Deviations from the plan above, made while building:

- The village bell is not renamed (bell block entities have no name); the *V* appears on the twin bell
  item and in the register instead.
- The frame interior is filled with vanilla light blocks and particles rather than a custom portal block;
  the crossing happens when the chosen stands inside the lit frame.
- The Quiet is a datapack flat world (sculk over deepslate, no skylight) with the village and the throat
  built in code on first entry, at fixed coordinates, instead of a hand-built NBT structure.
- The bell "pull" in the Hush uses an invisible, silent, very tough chicken as the wardens' target for a
  few seconds; armor stands and invulnerable entities cannot be warden targets.
- The Hush controller does not persist across a restart; it returns to Listening.
- The Pilgrim borrows the villager body (and renderer) so it needs no new model; its brain is skipped.

## Suggested order and effort

| Phase | What the player gets | Effort |
|---|---|---|
| 0 | Campaign file, debug commands | 0.5 session |
| 1 | He guides the road and reveals in tiers | 1 session |
| 2 | Deaths cost memory | 0.5 session |
| 3 | The name trap | 0.5 session |
| 4 | Register, bells, sculk, the missing villager | 1 session |
| 5 | Wool, warden avoidance, the frame, the Quiet, silence | 2 sessions |
| 6 | The Hush encounter | 2 sessions |
| 7 | The ending and the loop | 0.5 session |

Phases 0 through 3 make a complete, playable mystery with the existing world as the stage. Phases 4
onward are content. Nothing after Phase 1 blocks anything before it, so the order can shift if a set piece
is wanted earlier for a stream.
