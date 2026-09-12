package com.ayodehi.thehush.debug;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.TheHushMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * A loopback HTTP bridge for debugging the live world from outside the game (the MCP server in tools/mcp
 * talks to it). Every route that touches the world runs on the server thread through {@code server.submit}
 * with a timeout, so a hung server thread answers 504 instead of corrupting anything. Bound to 127.0.0.1 only.
 * Off unless {@code debug.bridge} is set in the config or the JVM property {@code thehush.bridge} is true
 * (the dev run configurations set the property).
 */
public final class DebugBridge {
    public static final String VERSION = "1";
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final long SERVER_THREAD_TIMEOUT_SECONDS = 20;
    private static final int TRANSCRIPT_CAPACITY = 600;

    /** Thrown by a route to answer with a specific status. */
    public static final class Reply extends RuntimeException {
        final int status;

        public Reply(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Something said in the world: player chat, a villager line, or a narrator line. */
    public record Line(long seq, long realMillis, String kind, String who, String text) {}

    private static final DebugBridge INSTANCE = new DebugBridge();

    private final ArrayDeque<Line> transcript = new ArrayDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private final Map<String, BiFunction<MinecraftServer, MiniHttp.Request, JsonElement>> routes = BridgeRoutes.all();
    private @Nullable ServerSocket socket;
    private @Nullable Thread acceptor;
    private @Nullable ExecutorService workers;
    private @Nullable MinecraftServer server;
    private int port;

    private DebugBridge() {}

    public static DebugBridge get() {
        return INSTANCE;
    }

    public static boolean wanted() {
        return Boolean.getBoolean("thehush.bridge") || Config.BRIDGE.get();
    }

    public synchronized boolean running() {
        return socket != null && !socket.isClosed();
    }

    public int port() {
        return port;
    }

    public synchronized void start(MinecraftServer server) {
        if (running()) stop();
        if (!wanted()) return;
        int wantedPort = Integer.getInteger("thehush.bridgePort", Config.BRIDGE_PORT.get());
        this.server = server;
        try {
            // Explicit IPv4 loopback: getLoopbackAddress() may pick ::1, which 127.0.0.1 clients cannot reach.
            ServerSocket s = new ServerSocket(wantedPort, 8, InetAddress.getByAddress("localhost", new byte[] {127, 0, 0, 1}));
            s.setReuseAddress(true);
            socket = s;
            port = s.getLocalPort();
        } catch (IOException e) {
            TheHushMod.LOGGER.warn("Debug bridge could not listen on 127.0.0.1:{}: {}", wantedPort, e.toString());
            socket = null;
            return;
        }
        workers = Executors.newVirtualThreadPerTaskExecutor();
        acceptor = new Thread(this::acceptLoop, "thehush-debug-bridge");
        acceptor.setDaemon(true);
        acceptor.start();
        TheHushMod.LOGGER.info("Debug bridge listening on http://127.0.0.1:{}/ (token {})", port,
                Config.BRIDGE_TOKEN.get().isBlank() ? "not required" : "required");
    }

    public synchronized void stop() {
        ServerSocket s = socket;
        socket = null;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
        acceptor = null;
    }

    // ---- transcript ----

    public void record(String kind, String who, String text) {
        Line line = new Line(seq.incrementAndGet(), System.currentTimeMillis(), kind, who, text);
        synchronized (transcript) {
            transcript.addLast(line);
            while (transcript.size() > TRANSCRIPT_CAPACITY) transcript.removeFirst();
        }
    }

    public List<Line> transcriptSince(long after, int limit) {
        List<Line> out = new ArrayList<>();
        synchronized (transcript) {
            for (Line l : transcript) {
                if (l.seq() > after) out.add(l);
            }
        }
        if (out.size() > limit) return out.subList(out.size() - limit, out.size());
        return out;
    }

    public long lastSeq() {
        return seq.get();
    }

    // ---- HTTP ----

    private void acceptLoop() {
        ServerSocket s = socket;
        ExecutorService pool = workers;
        if (s == null || pool == null) return;
        while (!s.isClosed()) {
            try {
                Socket client = s.accept();
                client.setSoTimeout(30_000);
                pool.execute(() -> serve(client));
            } catch (SocketException e) {
                break; // closed
            } catch (IOException e) {
                TheHushMod.LOGGER.debug("Debug bridge accept failed", e);
            }
        }
    }

    private void serve(Socket client) {
        try (client; var in = client.getInputStream(); var out = client.getOutputStream()) {
            MiniHttp.Request req;
            try {
                req = MiniHttp.read(in);
            } catch (IOException e) {
                MiniHttp.write(out, 400, "application/json", error(e.getMessage()));
                return;
            }
            if (req == null) return;
            int status = 200;
            String body;
            try {
                body = handle(req);
            } catch (Reply r) {
                status = r.status;
                body = error(r.getMessage());
            } catch (Throwable t) {
                status = 500;
                body = error(describe(t));
            }
            MiniHttp.write(out, status, "application/json", body);
        } catch (IOException e) {
            TheHushMod.LOGGER.debug("Debug bridge connection failed", e);
        }
    }

    private String handle(MiniHttp.Request req) {
        String token = Config.BRIDGE_TOKEN.get();
        if (!token.isBlank()) {
            String auth = req.header("authorization");
            if (auth == null || !auth.equals("Bearer " + token)) throw new Reply(401, "Missing or wrong bearer token.");
        }
        if (req.path().equals("/health")) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("bridge", VERSION);
            o.addProperty("serverRunning", server != null);
            return GSON.toJson(o);
        }
        if (req.path().equals("/transcript")) {
            long after = parseLong(req.param("since", "0"));
            int limit = (int) Math.min(TRANSCRIPT_CAPACITY, Math.max(1, parseLong(req.param("limit", "100"))));
            JsonObject o = new JsonObject();
            o.addProperty("lastSeq", lastSeq());
            o.add("lines", GSON.toJsonTree(transcriptSince(after, limit)));
            return GSON.toJson(o);
        }
        var route = routes.get(req.path());
        if (route == null) {
            throw new Reply(404, "No such route. Routes: /health, /transcript, " + String.join(", ", routes.keySet()));
        }
        MinecraftServer s = server;
        if (s == null) throw new Reply(503, "No server is running.");
        JsonElement result;
        try {
            result = s.submit(() -> route.apply(s, req)).get(SERVER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new Reply(504, "The server thread did not answer within " + SERVER_THREAD_TIMEOUT_SECONDS
                    + "s; it may be hung or paused (the game is paused while a single-player menu is open).");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Reply r) throw r;
            throw new Reply(500, describe(cause));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Reply(503, "Interrupted.");
        }
        return req.param("pretty") != null ? PRETTY.toJson(result) : GSON.toJson(result);
    }

    static JsonObject bodyJson(MiniHttp.Request req) {
        if (req.body().isBlank()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(req.body());
            if (!e.isJsonObject()) throw new Reply(400, "The body must be a JSON object.");
            return e.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new Reply(400, "Bad JSON body: " + e.getMessage());
        }
    }

    /** A parameter from the query string or the JSON body, whichever has it. */
    static @Nullable String arg(MiniHttp.Request req, JsonObject body, String name) {
        String q = req.param(name);
        if (q != null && !q.isEmpty()) return q;
        JsonElement e = body.get(name);
        if (e == null || e.isJsonNull()) return null;
        return e.isJsonPrimitive() ? e.getAsString() : GSON.toJson(e);
    }

    static String required(MiniHttp.Request req, JsonObject body, String name) {
        String v = arg(req, body, name);
        if (v == null) throw new Reply(400, "Missing parameter '" + name + "'.");
        return v;
    }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            throw new Reply(400, "Not a number: " + s);
        }
    }

    static double parseDouble(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new Reply(400, "Not a number: " + s);
        }
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message == null ? "unknown error" : message);
        return GSON.toJson(o);
    }

    private static String describe(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        return trace.length() > 4000 ? trace.substring(0, 4000) + "..." : trace;
    }
}
