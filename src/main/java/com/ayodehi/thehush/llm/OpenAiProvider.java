package com.ayodehi.thehush.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The OpenAI chat-completions shape, which is what LiteLLM, OpenAI, OpenRouter, LM Studio, vLLM, Groq and
 * most gateways speak. Non-streaming, with function tools. Prices are whatever the config says, since the
 * server could be anything; unknown means the cost shows as zero.
 */
public final class OpenAiProvider implements LlmProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("thehush/openai");
    private static final Gson GSON = new Gson();
    private static final int MAX_ATTEMPTS = 3;
    public static final String MODEL_PREFIX = "openai/";

    public record Settings(String baseUrl, String apiKey, String model, int maxTokens, String reasoningEffort,
                           double inputPricePerMTok, double outputPricePerMTok, Duration timeout) {}

    private final Settings settings;
    private final HttpClient http;
    private final Executor executor;
    private final Set<String> unsupported = ConcurrentHashMap.newKeySet();
    private final AtomicInteger callIds = new AtomicInteger();

    public OpenAiProvider(Settings settings, Executor executor) {
        this.settings = settings;
        this.executor = executor;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String describe() {
        String host = settings.baseUrl().replaceFirst("^https?://", "").replaceFirst("/v1/?$", "");
        return "OpenAI-compatible (" + settings.model() + " at " + host + ")";
    }

    @Override
    public CompletableFuture<LlmResponse> complete(LlmRequest request) {
        return CompletableFuture.supplyAsync(() -> send(request), executor);
    }

    /** {base}/v1/chat/completions, whether or not the configured URL already ends in /v1. */
    String endpoint() {
        String base = settings.baseUrl().replaceAll("/+$", "");
        return (base.endsWith("/v1") ? base : base + "/v1") + "/chat/completions";
    }

    // ---- request ----

    JsonObject buildBody(LlmRequest request, boolean withTools) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("stream", false);
        // Newer OpenAI models take max_completion_tokens and reject max_tokens; most other servers are the reverse.
        body.addProperty(unsupported.contains("max_tokens") ? "max_completion_tokens" : "max_tokens", settings.maxTokens());
        if (!settings.reasoningEffort().isBlank() && !unsupported.contains("reasoning_effort")) {
            body.addProperty("reasoning_effort", settings.reasoningEffort());
        }

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.systemPrompt());
        messages.add(system);
        for (ChatMessage m : request.messages()) toWire(m, messages);
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

    private static void toWire(ChatMessage message, JsonArray out) {
        if (message.role() == ChatMessage.Role.USER) {
            StringBuilder text = new StringBuilder();
            for (ContentPart p : message.parts()) {
                if (p instanceof ContentPart.Text t) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(t.text());
                } else if (p instanceof ContentPart.ToolResult r) {
                    JsonObject o = new JsonObject();
                    o.addProperty("role", "tool");
                    o.addProperty("tool_call_id", r.toolUseId());
                    o.addProperty("content", r.isError() ? "Error: " + r.content() : r.content());
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
        String text = message.text();
        if (!text.isEmpty()) o.addProperty("content", text);
        JsonArray calls = new JsonArray();
        for (ContentPart p : message.parts()) {
            if (p instanceof ContentPart.ToolUse u) {
                JsonObject fn = new JsonObject();
                fn.addProperty("name", u.name());
                fn.addProperty("arguments", GSON.toJson(u.input())); // a JSON string, not an object
                JsonObject call = new JsonObject();
                call.addProperty("id", u.id());
                call.addProperty("type", "function");
                call.add("function", fn);
                calls.add(call);
            }
        }
        if (!calls.isEmpty()) o.add("tool_calls", calls);
        if (text.isEmpty() && calls.isEmpty()) o.addProperty("content", "");
        out.add(o);
    }

    // ---- transport ----

    private HttpRequest request(JsonObject body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(settings.timeout())
                .header("content-type", "application/json");
        if (!settings.apiKey().isBlank()) b.header("authorization", "Bearer " + settings.apiKey());
        return b.POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
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
                    UsageMeter.get().record(MODEL_PREFIX + settings.model(), parsed.usage(), settings.inputPricePerMTok(), settings.outputPricePerMTok());
                    return parsed;
                }
                String detail = errorDetail(resp.body());
                String lower = detail.toLowerCase();
                if (status == 400 && lower.contains("max_tokens") && lower.contains("max_completion_tokens") && !unsupported.contains("max_tokens")) {
                    unsupported.add("max_tokens");
                    attempt--;
                    continue;
                }
                if (status == 400 && lower.contains("reasoning_effort") && body.has("reasoning_effort")) {
                    unsupported.add("reasoning_effort");
                    LOGGER.info("{} does not take reasoning_effort; sending without it", settings.model());
                    attempt--;
                    continue;
                }
                if (status == 400 && (lower.contains("tool") && (lower.contains("not support") || lower.contains("unsupported"))) && withTools) {
                    unsupported.add("tools");
                    withTools = false;
                    LOGGER.warn("{} does not support tools; he can talk but not act", settings.model());
                    attempt--;
                    continue;
                }
                if (status == 401 || status == 403) throw new LlmException("The server rejected the API key (HTTP " + status + "): " + detail);
                if (status == 404) throw new LlmException("HTTP 404 from " + endpoint() + ": " + detail + " (check llm.openai.url and the model name)");
                boolean retryable = status == 429 || status == 408 || status >= 500;
                last = new LlmException("OpenAI-compatible API HTTP " + status + ": " + detail);
                if (!retryable) throw last;
                LOGGER.warn("HTTP {} (attempt {}/{}): {}", status, attempt, MAX_ATTEMPTS, detail);
            } catch (ConnectException e) {
                throw new LlmException("Nothing is listening at " + settings.baseUrl() + " (is the gateway running?)", e);
            } catch (IOException e) {
                last = new LlmException("Connection failed: " + e.getMessage(), e);
                LOGGER.warn("Connection error (attempt {}/{}): {}", attempt, MAX_ATTEMPTS, e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted while waiting for the model", e);
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted while waiting for the model", ie);
                }
            }
        }
        throw last == null ? new LlmException("No answer from the model") : last;
    }

    private static String errorDetail(String body) {
        try {
            JsonObject o = JsonParser.parseString(body).getAsJsonObject();
            if (o.has("error")) {
                JsonElement e = o.get("error");
                if (e.isJsonObject() && e.getAsJsonObject().has("message")) return e.getAsJsonObject().get("message").getAsString();
                if (e.isJsonPrimitive()) return e.getAsString();
            }
            if (o.has("detail")) return o.get("detail").toString();
        } catch (RuntimeException ignored) {
            // fall through
        }
        return body.length() > 300 ? body.substring(0, 300) : body;
    }

    // ---- response ----

    /** choices[0].message {content, tool_calls[{id, function{name, arguments: string}}]}, finish_reason, usage. */
    static LlmResponse parse(JsonObject json, AtomicInteger ids) {
        JsonObject message = new JsonObject();
        String finish = "";
        if (json.has("choices") && json.get("choices").isJsonArray() && !json.getAsJsonArray("choices").isEmpty()) {
            JsonObject choice = json.getAsJsonArray("choices").get(0).getAsJsonObject();
            if (choice.has("message") && choice.get("message").isJsonObject()) message = choice.getAsJsonObject("message");
            if (choice.has("finish_reason") && !choice.get("finish_reason").isJsonNull()) finish = choice.get("finish_reason").getAsString();
        }
        List<ContentPart> parts = new ArrayList<>();
        if (message.has("content") && !message.get("content").isJsonNull()) {
            String content = message.get("content").isJsonPrimitive() ? message.get("content").getAsString() : message.get("content").toString();
            if (!content.isBlank()) parts.add(new ContentPart.Text(content));
        }
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement el : message.getAsJsonArray("tool_calls")) {
                JsonObject call = el.getAsJsonObject();
                JsonObject fn = call.has("function") ? call.getAsJsonObject("function") : call;
                String name = fn.has("name") ? fn.get("name").getAsString() : "";
                JsonObject args = new JsonObject();
                if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                    JsonElement a = fn.get("arguments");
                    try {
                        JsonElement parsed = a.isJsonPrimitive() ? JsonParser.parseString(a.getAsString()) : a;
                        if (parsed.isJsonObject()) args = parsed.getAsJsonObject();
                    } catch (RuntimeException ignored) {
                        // malformed arguments: call with none
                    }
                }
                String id = call.has("id") && !call.get("id").isJsonNull() ? call.get("id").getAsString() : "call_" + ids.incrementAndGet();
                parts.add(new ContentPart.ToolUse(id, name, args));
            }
        }
        boolean hasTools = parts.stream().anyMatch(p -> p instanceof ContentPart.ToolUse);
        LlmResponse.StopReason stop = hasTools ? LlmResponse.StopReason.TOOL_USE : switch (finish) {
            case "stop", "" -> LlmResponse.StopReason.END_TURN;
            case "length" -> LlmResponse.StopReason.MAX_TOKENS;
            case "content_filter" -> LlmResponse.StopReason.REFUSAL;
            default -> LlmResponse.StopReason.OTHER;
        };
        JsonObject u = json.has("usage") && json.get("usage").isJsonObject() ? json.getAsJsonObject("usage") : new JsonObject();
        long cached = 0;
        if (u.has("prompt_tokens_details") && u.get("prompt_tokens_details").isJsonObject()) cached = longOr(u.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
        long prompt = longOr(u, "prompt_tokens");
        LlmResponse.Usage usage = new LlmResponse.Usage(Math.max(0, prompt - cached), longOr(u, "completion_tokens"), cached, 0);
        return new LlmResponse(parts, stop, usage);
    }

    private static long longOr(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L;
    }
}
