package com.ayodehi.thehush.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local model through Ollama's /api/chat, non-streaming, with OpenAI-style tool calls. No key, no cost,
 * no prompt cache; the quality and the tool-calling depend entirely on the model pulled. Models that
 * refuse tools are retried without them, so he can still talk even if he cannot act.
 */
public final class OllamaProvider implements LlmProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("thehush/ollama");
    private static final Gson GSON = new Gson();
    private static final int MAX_ATTEMPTS = 3;
    /** Usage is recorded under this prefix so the pricing table knows it is free. */
    public static final String MODEL_PREFIX = "ollama/";

    public record Settings(String baseUrl, String model, int maxTokens, int contextTokens, boolean think, Duration timeout) {}

    private final Settings settings;
    private final HttpClient http;
    private final Executor executor;
    private final Set<String> unsupported = ConcurrentHashMap.newKeySet();
    private final AtomicInteger callIds = new AtomicInteger();

    public OllamaProvider(Settings settings, Executor executor) {
        this.settings = settings;
        this.executor = executor;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String describe() {
        String host = settings.baseUrl().replaceFirst("^https?://", "");
        return "Ollama (" + settings.model() + " at " + host + (settings.think() ? ", thinking" : "") + ")";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> send(request), executor);
    }

    // ---- request ----

    JsonObject buildBody(LlmRequest request, boolean withTools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("stream", false);
        body.addProperty("keep_alive", "15m");
        if (settings.think() && !unsupported.contains("think")) body.addProperty("think", true);
        JsonObject options = new JsonObject();
        options.addProperty("num_predict", settings.maxTokens());
        // Ollama's default window (4k) is smaller than his prompt; ask for room or the start is cut off.
        options.addProperty("num_ctx", settings.contextTokens());
        body.add("options", options);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.systemPrompt());
        messages.add(system);
        // A tool result only carries the call id; the name it answers is on the assistant turn before it.
        Map<String, String> toolNames = new HashMap<>();
        for (ChatMessage m : request.messages()) {
            for (ContentPart p : m.parts()) {
                if (p instanceof ContentPart.ToolUse u) toolNames.put(u.id(), u.name());
            }
        }
        for (ChatMessage m : request.messages()) toWire(m, messages, toolNames);
        body.add("messages", messages);

        if (withTools && !request.tools().isEmpty() && !unsupported.contains("tools")) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", spec.name());
                fn.addProperty("description", spec.description());
                fn.add("parameters", spec.inputSchema());
                JsonObject t = new JsonObject();
                t.addProperty("type", "function");
                t.add("function", fn);
                tools.add(t);
            }
            body.add("tools", tools);
        }
        return body;
    }

    private static void toWire(ChatMessage message, JsonArray out, Map<String, String> toolNames) {
        if (message.role() == ChatMessage.Role.USER) {
            // Text and tool results are separate messages in Ollama's shape.
            StringBuilder text = new StringBuilder();
            for (ContentPart p : message.parts()) {
                if (p instanceof ContentPart.Text t) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(t.text());
                } else if (p instanceof ContentPart.ToolResult r) {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", "tool");
                    o.addProperty("content", r.isError() ? "Error: " + r.content() : r.content());
                    String name = toolNames.get(r.toolUseId());
                    if (name != null) o.addProperty("tool_name", name);
                    out.add(o);
                }
            }
            if (!text.isEmpty()) {
                JsonObject o = new JsonObject();
                o.addProperty("role", "user");
                o.addProperty("content", text.toString());
                out.add(o);
            }
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("role", "assistant");
        o.addProperty("content", message.text());
        JsonArray calls = new JsonArray();
        for (ContentPart p : message.parts()) {
            if (p instanceof ContentPart.ToolUse u) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", u.name());
                fn.add("arguments", u.input());
                JsonObject call = new JsonObject();
                call.add("function", fn);
                calls.add(call);
            }
            // Raw blocks (another provider's thinking) are not Ollama's to see.
        }
        if (!calls.isEmpty()) o.add("tool_calls", calls);
        out.add(o);
    }

    // ---- transport ----

    private HttpRequest request(JsonObject body) {
        return HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/api/chat"))
                .timeout(settings.timeout())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
    }

    private LlmResponse send(LlmRequest req) {
        LlmException last = null;
        boolean withTools = true;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            JsonObject body = buildBody(req, withTools);
            try {
                HttpResponse<String> resp = http.send(request(body), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    LlmResponse parsed = parse(JsonParser.parseString(resp.body()).getAsJsonObject(), callIds);
                    UsageMeter.get().record(MODEL_PREFIX + settings.model(), parsed.usage());
                    return parsed;
                }
                String detail = errorDetail(resp.body());
                String lower = detail.toLowerCase();
                if (status == 400 && lower.contains("does not support tools") && withTools) {
                    unsupported.add("tools");
                    withTools = false;
                    LOGGER.warn("{} does not support tools; he can talk but not act. Pull a tool-capable model (e.g. llama3.1, qwen3) for the full villager", settings.model());
                    attempt--;
                    continue;
                }
                if (status == 400 && lower.contains("does not support thinking") && body.has("think")) {
                    unsupported.add("think");
                    LOGGER.info("{} does not support thinking; sending without it", settings.model());
                    attempt--;
                    continue;
                }
                if (status == 404 && lower.contains("not found")) {
                    throw new LlmException("Ollama has no model '" + settings.model() + "': run `ollama pull " + settings.model() + "`");
                }
                last = new LlmException("Ollama HTTP " + status + ": " + detail);
                if (status < 500) throw last;
                LOGGER.warn("Ollama {} (attempt {}/{}): {}", status, attempt, MAX_ATTEMPTS, detail);
            } catch (ConnectException e) {
                throw new LlmException("Ollama is not running at " + settings.baseUrl() + " (start it with `ollama serve`)", e);
            } catch (IOException e) {
                last = new LlmException("Ollama connection failed: " + e.getMessage(), e);
                LOGGER.warn("Ollama connection error (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted while waiting for Ollama", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted while waiting for Ollama", ie);
                }
            }
        }
        throw last == null ? new LlmException("Ollama gave no answer") : last;
    }

    private static String errorDetail(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error")) return o.get("error").getAsString();
        } catch (RuntimeException ignored) {
            // fall through
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    // ---- response ----

    /** {"message":{"content","tool_calls":[{"function":{"name","arguments"}}]},"done_reason","prompt_eval_count","eval_count"} */
    static LlmResponse parse(JsonObject json, AtomicInteger ids) {
        JsonObject message = json.has("message") && json.get("message").isJsonObject() ? json.getAsJsonObject("message") : new JsonObject();
        List<ContentPart> parts = new ArrayList<>();
        String content = message.has("content") && !message.get("content").isJsonNull() ? message.get("content").getAsString() : "";
        if (!content.isBlank()) parts.add(new ContentPart.Text(content));
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement el : message.getAsJsonArray("tool_calls")) {
                JsonObject call = el.getAsJsonObject();
                JsonObject fn = call.has("function") ? call.getAsJsonObject("function") : call;
                String name = fn.has("name") ? fn.get("name").getAsString() : "";
                JsonObject args = new JsonObject();
                if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                    JsonElement a = fn.get("arguments");
                    if (a.isJsonObject()) args = a.getAsJsonObject();
                    else if (a.isJsonPrimitive()) {
                        try {
                            JsonElement parsed = JsonParser.parseString(a.getAsString());
                            if (parsed.isJsonObject()) args = parsed.getAsJsonObject();
                        } catch (RuntimeException ignored) {
                            // leave empty
                        }
                    }
                }
                String id = call.has("id") && !call.get("id").isJsonNull() ? call.get("id").getAsString() : "call_" + ids.incrementAndGet();
                parts.add(new ContentPart.ToolUse(id, name, args));
            }
        }
        String done = json.has("done_reason") && !json.get("done_reason").isJsonNull() ? json.get("done_reason").getAsString() : "";
        LlmResponse.StopReason stop = parts.stream().anyMatch(p -> p instanceof ContentPart.ToolUse) ? LlmResponse.StopReason.TOOL_USE
                : switch (done) {
                    case "stop", "" -> LlmResponse.StopReason.END_TURN;
                    case "length" -> LlmResponse.StopReason.MAX_TOKENS;
                    default -> LlmResponse.StopReason.OTHER;
                };
        LlmResponse.Usage usage = new LlmResponse.Usage(longOr(json, "prompt_eval_count"), longOr(json, "eval_count"), 0, 0);
        return new LlmResponse(parts, stop, usage);
    }

    private static long longOr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
    }
}
