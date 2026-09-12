# The Hush developer MCP

`hush_mcp.py` is a [Model Context Protocol](https://modelcontextprotocol.io) server that lets Claude Code
(or any MCP client) inspect and drive the running game while the mod is being built. The repo's `.mcp.json`
registers it as the `hush` server; Claude Code starts it with `uv run --script tools/mcp/hush_mcp.py`
(needs [uv](https://docs.astral.sh/uv/); the `mcp` package is fetched on first run).

Two halves:

- **In the game**, `com.ayodehi.thehush.debug.DebugBridge` is a tiny HTTP server on `127.0.0.1:25599`
  that answers JSON. Every route that touches the world runs on the server thread through
  `server.submit` with a 20 s timeout, so a hung server thread answers 504 instead of corrupting anything.
  The dev run configurations enable it with `-Dthehush.bridge=true`; in a normal install it stays off unless
  `debug.bridge = true` in `config/thehush-common.toml` (`debug.bridgePort`, `debug.bridgeToken` too).
  It starts when a world opens (`ServerStartingEvent`) and stops when it closes.
- **Outside**, the MCP server proxies those routes as tools and adds the file and process tools for the dev
  loop. It reads `run/` relative to the repo (`HUSH_PROJECT` overrides), talks to `HUSH_BRIDGE_URL`
  (default `http://127.0.0.1:25599`), and sends `HUSH_BRIDGE_TOKEN` as a bearer token if set.

## Tools

Live world (need the bridge, i.e. a world open in a dev run):

| Tool | What it returns |
|---|---|
| `game_status` | tick, mspt, dimensions with time/weather/entity counts, players, providers, campaign summary, data paths |
| `players` | position, dimension, health, food, game mode, held item, op/chosen, who they are talking to |
| `entities_near` | entities around a player or a point, with mod state (Traveller, Echo, Pilgrim) |
| `villagers` / `villager` | every talking villager; one in detail with history, notes, and optionally the live system prompt |
| `campaign_state` | the full `campaign.json` state, the stage list, hunts, the Hush encounter, pending returns |
| `transcript` | player chat, villager lines, narrator lines since the world opened (with `since=` for deltas) |
| `snapshot` | the `/hush campaign snapshot` text picture, returned directly |
| `block_at` | a block's id, properties, light; optionally the cube around it |
| `run_command` | any command as the console or positioned as a player, with its output captured |
| `say_as_player` | injects chat as a player; villagers in earshot reply (see `transcript`) |
| `usage`, `mod_config` | API ledgers; every config value (keys masked) |

Dev loop (work without the game):

| Tool | What it does |
|---|---|
| `tail_log`, `crash_report` | `run/logs/latest.log` with a regex filter; newest crash report |
| `list_saves`, `world_file`, `read_config_file` | worlds under `run/saves`; `campaign.json`, `usage.json`, memory files; the dev config |
| `build` | `./gradlew compileJava test -q` with JDK 25 |
| `client_status`, `launch_client`, `stop_client` | the dev client as a background process; `stop_client` refuses while a world is open unless forced |
| `wait_for_log`, `wait_for_bridge` | block until a log line appears or the bridge answers |

The safe edit cycle is: `build`, ask the player to quit to the title screen, `wait_for_log("Stopping
singleplayer server")`, `stop_client`, `launch_client`, then `wait_for_bridge` once they reopen the world.

## Talking to the bridge directly

```sh
curl 'http://127.0.0.1:25599/status?pretty=1'
curl -X POST http://127.0.0.1:25599/command -d '{"command":"hush campaign status","as":"Ayodehi"}'
curl -X POST http://127.0.0.1:25599/chat -d '{"player":"Ayodehi","text":"Where should I go next?"}'
curl 'http://127.0.0.1:25599/transcript?since=0'
```

Routes: `/health`, `/status`, `/players`, `/entities`, `/villagers`, `/villager`, `/campaign`, `/snapshot`,
`/block`, `/command`, `/chat`, `/usage`, `/config`, `/transcript`. Parameters go in the query string or a
JSON body; add `pretty=1` for indented output.
