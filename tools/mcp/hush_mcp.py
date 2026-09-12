# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp>=1.10,<2"]
# ///
"""
The Hush developer MCP server.

Gives Claude Code (or any MCP client) two kinds of tools while the mod is being built:

  * live-world tools, which talk to the debugging bridge the mod runs inside the game
    (com.ayodehi.thehush.debug.DebugBridge, http://127.0.0.1:25599 by default): status, players,
    entities, the Traveller's history and notes, the campaign state, a snapshot of the player's
    surroundings, running commands, saying things as a player, and reading what was said;
  * out-of-game tools for the dev loop: tailing the game log, crash reports, world data files,
    building the mod, launching and stopping the dev client, and waiting for a log line.

Run with `uv run --script tools/mcp/hush_mcp.py` (the project's .mcp.json does this). Environment:
  HUSH_PROJECT      repo root (default: two levels up from this file)
  HUSH_BRIDGE_URL   bridge base URL (default http://127.0.0.1:25599)
  HUSH_BRIDGE_TOKEN bearer token if debug.bridgeToken is set in the mod config
  HUSH_JAVA_HOME    JDK for Gradle (default: /usr/libexec/java_home -v 25)
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

from mcp.server.fastmcp import FastMCP

PROJECT = Path(os.environ.get("HUSH_PROJECT") or Path(__file__).resolve().parents[2])
RUN_DIR = PROJECT / "run"
LOG_FILE = RUN_DIR / "logs" / "latest.log"
BRIDGE_URL = os.environ.get("HUSH_BRIDGE_URL", "http://127.0.0.1:25599").rstrip("/")
BRIDGE_TOKEN = os.environ.get("HUSH_BRIDGE_TOKEN", "")
SCRATCH = Path(os.environ.get("HUSH_SCRATCH") or (PROJECT / "build" / "mcp"))
CLIENT_LOG = SCRATCH / "runClient.log"
CLIENT_PID = SCRATCH / "runClient.pid"

mcp = FastMCP(
    "hush",
    instructions=(
        "Tools for debugging and driving The Hush (a NeoForge mod) while it runs. Live tools need the game's "
        "debug bridge; if they fail with 'bridge unreachable', the game is not running or no world is open "
        "(use client_status / launch_client). Never stop the client while a world is open unless the server "
        "thread is hung: unsaved chunks are lost. The safe cycle is build -> ask the player to quit to the "
        "title screen -> wait_for_log('Stopping singleplayer server') -> stop_client -> launch_client."
    ),
)


# ---------------------------------------------------------------- bridge plumbing

class BridgeError(RuntimeError):
    pass


def bridge(path: str, params: dict[str, Any] | None = None, body: dict[str, Any] | None = None, timeout: float = 30) -> Any:
    query = {k: v for k, v in (params or {}).items() if v is not None and v != ""}
    url = BRIDGE_URL + path + ("?" + urllib.parse.urlencode(query) if query else "")
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if BRIDGE_TOKEN:
        req.add_header("Authorization", f"Bearer {BRIDGE_TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            message = json.loads(e.read().decode()).get("error", str(e))
        except Exception:
            message = str(e)
        raise BridgeError(f"bridge {e.code} on {path}: {message}") from None
    except urllib.error.URLError as e:
        raise BridgeError(
            f"bridge unreachable at {BRIDGE_URL} ({e.reason}). The game is not running, no world is open yet, "
            "or the bridge is off (dev runs set -Dthehush.bridge=true; otherwise debug.bridge in the config)."
        ) from None


def pretty(obj: Any) -> str:
    return json.dumps(obj, indent=2, ensure_ascii=False)


# ---------------------------------------------------------------- live world

@mcp.tool()
def game_status() -> str:
    """Overview of the running world: tick, mspt, loaded dimensions with time/weather/entity counts, online
    players, the LLM and voice providers, campaign stage/chosen/flags, hunts, pending returns, and where the
    world's thehush/ data directory is. Start here."""
    return pretty(bridge("/status"))


@mcp.tool()
def players() -> str:
    """Every online player: position, dimension, facing, health, food, game mode, held item, whether they are
    op / the chosen, and which villager they are talking to."""
    return pretty(bridge("/players"))


@mcp.tool()
def entities_near(player: str | None = None, radius: float = 24, type_filter: str | None = None,
                  x: float | None = None, y: float | None = None, z: float | None = None,
                  dimension: str | None = None) -> str:
    """Entities within radius blocks of a player (default: the first online player) or of a point (x, y, z in
    a dimension such as 'overworld', 'the_nether', 'thehush:quiet'). type_filter matches the entity type id or
    custom name (e.g. 'echo', 'pilgrim', 'warden', 'ai_villager'). Mod entities include their state: the
    Traveller's persona/following/talking-to/navigation, an Echo's hunt phase, a Pilgrim's frozen flag."""
    return pretty(bridge("/entities", {"player": player, "radius": radius, "type": type_filter,
                                       "x": x, "y": y, "z": z, "dimension": dimension}))


@mcp.tool()
def villagers() -> str:
    """Every loaded talking villager (the Traveller and any others) across all dimensions, with persona,
    position, who they follow or talk to, message and note counts, and the memory file path."""
    return pretty(bridge("/villagers"))


@mcp.tool()
def villager(uuid: str | None = None, player: str | None = None, include_prompt: bool = False) -> str:
    """One talking villager in detail: the conversation history as sent to the model, long-term notes about
    online players and general notes, and (include_prompt) the full system prompt he is given right now.
    Pick by uuid, or by the player he is talking to (falls back to the campaign's Traveller near that player);
    with neither, the first villager anyone is talking to."""
    return pretty(bridge("/villager", {"uuid": uuid, "player": player, "prompt": "1" if include_prompt else None}))


@mcp.tool()
def campaign_state() -> str:
    """The full campaign state as saved in <world>/thehush/campaign.json (stage, chosen, flags, deaths,
    forgotten items, history, frame/pedestal/arrival coordinates, hunt schedule, storm) plus the stage list of
    the campaign definition, the Echo hunt status, the Hush encounter status, and pending returns."""
    return pretty(bridge("/campaign"))


@mcp.tool()
def transcript(since: int = 0, limit: int = 100) -> str:
    """What has been said in the world since the game started: player chat ('chat'), villager lines ('npc'),
    and narrator lines ('narrator'), newest last, each with a sequence number. Pass since=<lastSeq> to read
    only what is new, e.g. after say_as_player to collect the reply (allow a few seconds for the API call)."""
    return pretty(bridge("/transcript", {"since": since, "limit": limit}))


@mcp.tool()
def snapshot(player: str | None = None, write_file: bool = False) -> str:
    """A text picture of a player's surroundings (the same as /hush campaign snapshot): position and facing,
    campaign state, every entity within 24 blocks with what it is doing, doors, and 17x17 block maps of four
    layers around the feet with a legend. Good for 'why is he stuck' and 'what does the room look like'."""
    data = bridge("/snapshot", {"player": player, "write": "1" if write_file else None})
    out = data["text"]
    if "file" in data:
        out += f"\n(written to {data['file']})"
    return out


@mcp.tool()
def block_at(x: float, y: float, z: float, dimension: str | None = None, radius: int = 0) -> str:
    """The block at a position (id, state properties, light level, whether it has a block entity). With
    radius 1..8, also every non-air block in the cube around it. dimension defaults to the first player's."""
    return pretty(bridge("/block", {"x": x, "y": y, "z": z, "dimension": dimension, "radius": radius or None}))


@mcp.tool()
def run_command(command: str, as_player: str | None = None) -> str:
    """Run a server command and return its output lines. Without as_player it runs as the server console
    (owner permission, positioned at spawn); with as_player it runs at that player's position with owner
    permission so commands that need a player (e.g. '/hush spawn', '/hush campaign snapshot', '/tp ~ ~ ~',
    '/hush campaign pilgrim') work. The mod's own debug commands: /hush status, /hush usage, /hush campaign
    status|stage <id>|flag <name>|chosen <player>|reset|return|arrival|frame|pilgrim|hunt [status]|quiet|
    hush start|status|reset, /hush reload, /hush forget, /hush memories."""
    data = bridge("/command", body={"command": command, "as": as_player})
    lines = data.get("output") or ["(no output)"]
    return f"/{command.lstrip('/')} as {data['as']}:\n" + "\n".join(lines)


@mcp.tool()
def say_as_player(text: str, player: str | None = None) -> str:
    """Put words in a player's mouth: the text is handled exactly as if they typed it in chat, so a villager
    in earshot hears it and replies through the API (the campaign also sees it, e.g. the name 'Vesper').
    Returns whether a villager took it; the reply lands in transcript() a few seconds later."""
    return pretty(bridge("/chat", body={"text": text, "player": player}))


@mcp.tool()
def usage() -> str:
    """API usage ledgers for this session and the whole campaign: calls, tokens, cache reads and writes,
    estimated cost, voice characters."""
    return pretty(bridge("/usage"))


@mcp.tool()
def mod_config() -> str:
    """Every value of the mod's config as loaded in the running game (API keys masked). Edit the file
    run/config/thehush-common.toml to change them; the game reloads it on save."""
    return pretty(bridge("/config"))


# ---------------------------------------------------------------- files and logs

def _tail(path: Path, lines: int, grep: str | None) -> str:
    if not path.exists():
        return f"(no such file: {path})"
    text = path.read_text(encoding="utf-8", errors="replace").splitlines()
    if grep:
        rx = re.compile(grep, re.IGNORECASE)
        text = [l for l in text if rx.search(l)]
    tail = text[-lines:] if lines > 0 else text
    header = f"{path} ({len(text)} matching lines, showing last {len(tail)})\n"
    return header + "\n".join(tail)


@mcp.tool()
def tail_log(lines: int = 150, grep: str | None = None, file: str | None = None) -> str:
    """Tail the game log (run/logs/latest.log). grep is a case-insensitive regex applied before the tail,
    e.g. 'thehush|Exception', 'Campaign', 'Debug bridge'. file names another log under run/logs/ or an
    absolute path (e.g. the runClient gradle output at build/mcp/runClient.log)."""
    path = LOG_FILE
    if file:
        p = Path(file)
        path = p if p.is_absolute() else (RUN_DIR / "logs" / file if (RUN_DIR / "logs" / file).exists() else PROJECT / file)
    return _tail(path, min(lines, 2000), grep)


@mcp.tool()
def crash_report(index: int = -1, lines: int = 120) -> str:
    """The newest crash report from run/crash-reports (index -2 for the one before, etc.): the first `lines`
    lines, which hold the description and stack trace."""
    d = RUN_DIR / "crash-reports"
    reports = sorted(d.glob("crash-*.txt")) if d.exists() else []
    if not reports:
        return "No crash reports."
    try:
        path = reports[index]
    except IndexError:
        return f"Only {len(reports)} report(s)."
    text = path.read_text(encoding="utf-8", errors="replace").splitlines()
    return f"{path} ({len(reports)} reports total)\n" + "\n".join(text[:lines])


@mcp.tool()
def list_saves() -> str:
    """The single-player worlds under run/saves, with which have campaign / memory / returning data."""
    saves = RUN_DIR / "saves"
    if not saves.exists():
        return "No saves directory."
    out = []
    for w in sorted(p for p in saves.iterdir() if p.is_dir()):
        mod = w / "thehush"
        files = sorted(p.name for p in mod.iterdir()) if mod.exists() else []
        mem = len(list((mod / "memory").glob("*.json"))) if (mod / "memory").exists() else 0
        out.append(f"{w.name}: {', '.join(files) or 'no mod data'}" + (f" ({mem} memory file(s))" if mem else ""))
    return "\n".join(out) or "No worlds."


@mcp.tool()
def world_file(name: str = "campaign.json", save: str | None = None) -> str:
    """Read a mod data file from a world save: campaign.json, usage.json, returning.json, snapshot.txt,
    'memory' (lists the memory files), or memory/<uuid>.json. save defaults to the most recently played world.
    Works when the game is closed; when it is open, prefer the live tools (the file may be stale)."""
    saves = RUN_DIR / "saves"
    if save is None:
        worlds = [p for p in saves.iterdir() if p.is_dir()] if saves.exists() else []
        if not worlds:
            return "No worlds."
        save = max(worlds, key=lambda p: (p / "level.dat").stat().st_mtime if (p / "level.dat").exists() else 0).name
    base = saves / save / "thehush"
    target = (base / name).resolve()
    if base.resolve() not in target.parents and target != base.resolve():
        return "Path escapes the world's thehush directory."
    if name == "memory":
        d = base / "memory"
        return "\n".join(str(p.relative_to(base)) for p in sorted(d.glob("*.json"))) if d.exists() else "No memory directory."
    if not target.exists():
        return f"No such file: {target}"
    return f"{target}\n" + target.read_text(encoding="utf-8", errors="replace")


@mcp.tool()
def read_config_file() -> str:
    """The mod's config file for the dev game, run/config/thehush-common.toml, as text (API key lines masked)."""
    p = RUN_DIR / "config" / "thehush-common.toml"
    if not p.exists():
        return f"No such file: {p}"
    out = []
    for line in p.read_text(encoding="utf-8").splitlines():
        if re.match(r"\s*apiKey\s*=", line) and not re.search(r'=\s*""', line):
            out.append(re.sub(r"=.*", '= "(set)"', line))
        else:
            out.append(line)
    return f"{p}\n" + "\n".join(out)


# ---------------------------------------------------------------- dev loop

def _java_home() -> str:
    env = os.environ.get("HUSH_JAVA_HOME")
    if env:
        return env
    try:
        return subprocess.run(["/usr/libexec/java_home", "-v", "25"], capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        candidate = Path.home() / "Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home"
        return str(candidate) if candidate.exists() else os.environ.get("JAVA_HOME", "")


def _gradle_env() -> dict[str, str]:
    env = dict(os.environ)
    jh = _java_home()
    if jh:
        env["JAVA_HOME"] = jh
    return env


@mcp.tool()
def build(run_tests: bool = True, tail: int = 60) -> str:
    """Compile the mod (./gradlew compileJava, plus test unless run_tests is false). Returns the exit code
    and the last lines of output. A running client keeps its old classes; relaunch to pick up the change."""
    tasks = ["compileJava"] + (["test"] if run_tests else [])
    proc = subprocess.run(["./gradlew", *tasks, "-q"], cwd=PROJECT, env=_gradle_env(), capture_output=True, text=True, timeout=900)
    out = (proc.stdout + proc.stderr).strip().splitlines()
    status = "BUILD OK" if proc.returncode == 0 else f"BUILD FAILED (exit {proc.returncode})"
    return f"{status}: ./gradlew {' '.join(tasks)}\n" + "\n".join(out[-tail:])


def _client_pids() -> list[int]:
    """Java processes of the dev client: the game itself (ModDevGradle launches it through devlaunch) and the
    Gradle wrapper that started it. Shells whose command text merely mentions runClient are ignored."""
    try:
        out = subprocess.run(["ps", "-axo", "pid=,command="], capture_output=True, text=True).stdout
    except FileNotFoundError:
        return []
    pids = []
    for line in out.splitlines():
        line = line.strip()
        if not line:
            continue
        pid_s, _, cmd = line.partition(" ")
        if not pid_s.isdigit() or int(pid_s) == os.getpid():
            continue
        head = cmd.split(" ", 1)[0]
        is_java = head.endswith("/java") or head == "java"
        if is_java and ("devlaunch" in cmd or "GradleWrapperMain runClient" in cmd):
            pids.append(int(pid_s))
    return pids


def _bridge_ok() -> bool:
    try:
        return bool(bridge("/health", timeout=3).get("ok"))
    except BridgeError:
        return False


def _log_state() -> str:
    """Whether the integrated server is up, judged from the last relevant log lines."""
    if not LOG_FILE.exists():
        return "no log"
    lines = LOG_FILE.read_text(encoding="utf-8", errors="replace").splitlines()
    last = "unknown"
    for line in lines:
        if "Starting integrated minecraft server" in line or "Preparing level" in line:
            last = "world open"
        elif "Stopping singleplayer server" in line or "Stopping server" in line:
            last = "at title screen"
        elif "Backend library: LWJGL" in line or "Setting user:" in line:
            last = "client starting"
    return last


@mcp.tool()
def client_status() -> str:
    """Is the dev client running, is the bridge reachable, and what does the log say (world open, at the
    title screen, starting)? Includes the last few log lines."""
    pids = _client_pids()
    ok = _bridge_ok()
    state = _log_state()
    tail = _tail(LOG_FILE, 8, None) if LOG_FILE.exists() else "(no log)"
    return (f"client processes: {pids or 'none'}\nbridge: {'reachable at ' + BRIDGE_URL if ok else 'unreachable'}\n"
            f"log says: {state}\n\n{tail}")


@mcp.tool()
def launch_client() -> str:
    """Start the dev client in the background (nohup ./gradlew runClient) with the bridge enabled. Refuses
    if one is already running. Gradle output goes to build/mcp/runClient.log; the game log to
    run/logs/latest.log. Startup takes a minute or two: wait_for_log('Backend library|LWJGL') for the window,
    then the user opens a world; the bridge answers once the integrated server has started."""
    if _client_pids():
        return f"A client is already running (pids {_client_pids()}). Use stop_client first."
    SCRATCH.mkdir(parents=True, exist_ok=True)
    log = open(CLIENT_LOG, "ab")
    proc = subprocess.Popen(["nohup", "./gradlew", "runClient"], cwd=PROJECT, env=_gradle_env(),
                            stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    CLIENT_PID.write_text(str(proc.pid))
    return f"Launched ./gradlew runClient (gradle pid {proc.pid}); output in {CLIENT_LOG}."


@mcp.tool()
def stop_client(force: bool = False) -> str:
    """Stop the dev client. Refuses while a world is open (the bridge answers and the log says so) unless
    force=True, because killing a live world loses unsaved chunks; the safe order is: player quits to the
    title screen, wait_for_log('Stopping singleplayer server'), then stop_client. force=True is for a hung
    server thread (bridge returns 504)."""
    pids = _client_pids()
    if not pids:
        return "No client is running."
    world_open = _log_state() == "world open" or _bridge_ok()
    if world_open and not force:
        return ("Refusing: a world appears to be open. Ask the player to quit to the title screen, "
                "wait_for_log('Stopping singleplayer server'), then call again (or force=True if the server thread is hung).")
    subprocess.run(["kill", *map(str, pids)], capture_output=True)
    time.sleep(3)
    left = _client_pids()
    if left:
        subprocess.run(["kill", "-9", *map(str, left)], capture_output=True)
        time.sleep(1)
    return f"Stopped pids {pids}" + (f"; killed -9 {left}" if left else "") + f"; remaining: {_client_pids() or 'none'}."


@mcp.tool()
def wait_for_log(pattern: str, timeout_seconds: int = 600, from_start: bool = False) -> str:
    """Block until a regex appears in run/logs/latest.log (new lines only, unless from_start), or time out.
    Typical: 'Stopping singleplayer server' (player quit to the title screen), 'Debug bridge listening'
    (world open, bridge up), 'Backend library' (client window up), 'Exception|Error' while reproducing a bug."""
    rx = re.compile(pattern, re.IGNORECASE)
    deadline = time.time() + max(1, min(timeout_seconds, 3600))
    offset = 0 if from_start or not LOG_FILE.exists() else LOG_FILE.stat().st_size
    while time.time() < deadline:
        if LOG_FILE.exists():
            size = LOG_FILE.stat().st_size
            if size < offset:
                offset = 0  # rotated
            if size > offset:
                with LOG_FILE.open("rb") as f:
                    f.seek(offset)
                    chunk = f.read().decode("utf-8", errors="replace")
                offset = size
                for line in chunk.splitlines():
                    if rx.search(line):
                        return f"matched after {int(deadline - time.time())}s left: {line}"
        time.sleep(1)
    return f"timed out after {timeout_seconds}s waiting for /{pattern}/"


@mcp.tool()
def wait_for_bridge(timeout_seconds: int = 300) -> str:
    """Block until the in-game bridge answers (a world has been opened), or time out."""
    deadline = time.time() + max(1, min(timeout_seconds, 3600))
    while time.time() < deadline:
        if _bridge_ok():
            return "bridge is up: " + pretty(bridge("/status"))
        time.sleep(2)
    return f"timed out after {timeout_seconds}s; the bridge at {BRIDGE_URL} never answered."


if __name__ == "__main__":
    if not shutil.which("pgrep"):
        print("warning: pgrep not found; client_status/stop_client will be blind", file=sys.stderr)
    mcp.run()
