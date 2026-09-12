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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Claude Messages API over Java's built-in HTTP client. No SDK: Minecraft already ships Gson, and the
 * official Java SDK's dependency tree (Kotlin, OkHttp, Jackson) is awkward to bundle inside a mod jar.
 */
public final class ClaudeProvider implements LlmProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("thehush/claude");
    private static final Gson GSON = new Gson();
    private static final String API_VERSION = "2023-06-01";
    /** Opts into Anthropic's default refusal fallbacks so a false-positive safety decline is retried server-side. */
    private static final String BETAS = "server-side-fallback-2026-07-01";
    private static final int MAX_ATTEMPTS = 3;

    public record Settings(String apiKey, String baseUrl, String model, int maxTokens, String effort,
                           Duration timeout) {}

    private static final java.util.regex.Pattern UNSUPPORTED =
            java.util.regex.Pattern.compile("does not support the `([a-z_]+)` parameter");

    private final Settings settings;
    private final HttpClient http;
    private final Executor executor;
    /** Request parameters this model has rejected (e.g. "fallbacks" on models without server-side fallbacks). */
    private final java.util.Set<String> unsupported = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ClaudeProvider(Settings settings, Executor executor) {
        this.settings = settings;
        this.executor = executor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    @Override
    public String describe() {
        return "Claude (" + settings.model() + ", effort=" + settings.effort() + ")";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> send(buildBody(request)), executor);
    }

    // ---- request ----

    JsonObject buildBody(LlmRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("max_tokens", settings.maxTokens());
        if (!unsupported.contains("fallbacks")) body.addProperty("fallbacks", "default");

        if (!unsupported.contains("output_config")) {
            JsonObject outputConfig = new JsonObject();
            outputConfig.addProperty("effort", settings.effort());
            body.add("output_config", outputConfig);
        }

        // System prompt is the stable prefix: cache it (tools render before system, so this covers them too).
        JsonObject systemBlock = new JsonObject();
        systemBlock.addProperty("type", "text");
        systemBlock.addProperty("text", request.systemPrompt());
        systemBlock.add("cache_control", ephemeral());
        JsonArray system = new JsonArray();
        system.add(systemBlock);
        body.add("system", system);

        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (ToolSpec spec : request.tools()) {
                JsonObject t = new JsonObject();
                t.addProperty("name", spec.name());
                t.addProperty("description", spec.description());
                t.add("input_schema", spec.inputSchema());
                tools.add(t);
            }
            body.add("tools", tools);
        }

        JsonArray messages = new JsonArray();
        List<ChatMessage> msgs = request.messages();
        for (int i = 0; i < msgs.size(); i++) {
            // Second breakpoint on the final message so long conversations reuse the cached history prefix.
            messages.add(toWire(msgs.get(i), i == msgs.size() - 1));
        }
        body.add("messages", messages);
        return body;
    }

    private static JsonObject ephemeral() {
        JsonObject cc = new JsonObject();
        cc.addProperty("type", "ephemeral");
        return cc;
    }

    private static JsonObject toWire(ChatMessage message, boolean cacheBreakpoint) {
        JsonObject m = new JsonObject();
        m.addProperty("role", message.role() == ChatMessage.Role.USER ? "user" : "assistant");
        JsonArray content = new JsonArray();
        for (ContentPart part : message.parts()) {
            content.add(switch (part) {
                case ContentPart.Text t -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", "text");
                    o.addProperty("text", t.text());
                    yield o;
                }
                case ContentPart.ToolUse u -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", "tool_use");
                    o.addProperty("id", u.id());
                    o.addProperty("name", u.name());
                    o.add("input", u.input());
                    yield o;
                }
                case ContentPart.ToolResult r -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("type", "tool_result");
                    o.addProperty("tool_use_id", r.toolUseId());
                    o.addProperty("content", r.content());
                    if (r.isError()) o.addProperty("is_error", true);
                    yield o;
                }
                case ContentPart.Raw raw -> raw.json().deepCopy();
            });
        }
        if (cacheBreakpoint && !content.isEmpty()) {
            JsonObject last = content.get(content.size() - 1).getAsJsonObject();
            // Thinking blocks can't carry cache_control; only mark text/tool blocks.
            String type = last.has("type") ? last.get("type").getAsString() : "";
            if (!type.contains("thinking")) {
                last.add("cache_control", ephemeral());
            }
        }
        m.add("content", content);
        return m;
    }

    // ---- transport ----

    private HttpRequest request(JsonObject body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(settings.baseUrl() + "/v1/messages"))
                .timeout(settings.timeout())
                .header("content-type", "application/json")
                .header("x-api-key", settings.apiKey())
                .header("anthropic-version", API_VERSION);
        if (body.has("fallbacks")) b.header("anthropic-beta", BETAS);
        return b.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
    }

    /** Returns the parameter name when the model rejected one it does not support, else null. */
    static @Nullable String unsupportedParameter(String detail) {
        java.util.regex.Matcher m = UNSUPPORTED.matcher(detail);
        return m.find() ? m.group(1) : null;
    }

    private LlmResponse send(JsonObject body) {
        LlmException last = null;
        int stripped = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> resp = http.send(request(body), HttpResponse.BodyHandlers.ofString());
                int status = resp.statusCode();
                if (status >= 200 && status < 300) {
                    LlmResponse parsed = parse(JsonParser.parseString(resp.body()).getAsJsonObject());
                    UsageMeter.get().record(settings.model(), parsed.usage());
                    return parsed;
                }
                String detail = errorDetail(resp.body());
                String param = status == 400 ? unsupportedParameter(detail) : null;
                if (param != null && body.has(param) && stripped++ < 3) {
                    // Some models take fewer knobs (no server-side fallbacks, no effort). Drop it and go again.
                    unsupported.add(param);
                    body.remove(param);
                    LOGGER.info("{} does not support '{}'; sending without it from now on", settings.model(), param);
                    attempt--;
                    continue;
                }
                boolean retryable = status == 429 || status == 408 || status == 409 || status >= 500;
                last = new LlmException("Claude API HTTP " + status + ": " + detail);
                if (!retryable) throw last;
                LOGGER.warn("Claude API {} (attempt {}/{}): {}", status, attempt, MAX_ATTEMPTS, detail);
            } catch (IOException e) {
                last = new LlmException("Claude API connection failed: " + e.getMessage(), e);
                LOGGER.warn("Claude API connection error (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted while waiting for Claude", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted while waiting for Claude", ie);
                }
            }
        }
        throw last;
    }

    private static String errorDetail(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error")) {
                JsonObject err = o.getAsJsonObject("error");
                return err.get("type").getAsString() + " - " + err.get("message").getAsString();
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    // ---- response ----

    static LlmResponse parse(JsonObject json) {
        String stop = json.has("stop_reason") && !json.get("stop_reason").isJsonNull()
                ? json.get("stop_reason").getAsString() : "";
        LlmResponse.StopReason stopReason = switch (stop) {
            case "end_turn", "stop_sequence" -> LlmResponse.StopReason.END_TURN;
            case "tool_use" -> LlmResponse.StopReason.TOOL_USE;
            case "max_tokens" -> LlmResponse.StopReason.MAX_TOKENS;
            case "refusal" -> LlmResponse.StopReason.REFUSAL;
            default -> LlmResponse.StopReason.OTHER;
        };

        List<ContentPart> parts = new java.util.ArrayList<>();
        JsonArray content = json.has("content") ? json.getAsJsonArray("content") : new JsonArray();
        for (JsonElement el : content) {
            JsonObject block = el.getAsJsonObject();
            String type = block.get("type").getAsString();
            switch (type) {
                case "text" -> parts.add(new ContentPart.Text(block.get("text").getAsString()));
                case "tool_use" -> parts.add(new ContentPart.ToolUse(
                        block.get("id").getAsString(),
                        block.get("name").getAsString(),
                        block.getAsJsonObject("input")));
                default -> parts.add(new ContentPart.Raw(block));
            }
        }

        JsonObject u = json.has("usage") ? json.getAsJsonObject("usage") : new JsonObject();
        LlmResponse.Usage usage = new LlmResponse.Usage(
                longOr(u, "input_tokens"), longOr(u, "output_tokens"),
                longOr(u, "cache_read_input_tokens"), longOr(u, "cache_creation_input_tokens"));
        return new LlmResponse(parts, stopReason, usage);
    }

    private static long longOr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
    }
}
