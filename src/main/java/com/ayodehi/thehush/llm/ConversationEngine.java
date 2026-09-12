package com.ayodehi.thehush.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One NPC's conversation state plus the ask -> tool -> ask loop. Knows nothing about Minecraft:
 * the game side supplies a ToolExecutor and consumes the final text.
 */
public final class ConversationEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger("thehush/conversation");

    private final LlmProvider provider;
    private final List<ToolSpec> toolSpecs;
    private final ToolExecutor tools;
    private final int maxToolRounds;
    private final int maxHistoryMessages;

    private final List<ChatMessage> history = new ArrayList<>();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long busySince;

    public ConversationEngine(LlmProvider provider, List<ToolSpec> toolSpecs, ToolExecutor tools,
                              int maxToolRounds, int maxHistoryMessages) {
        this.provider = provider;
        this.toolSpecs = List.copyOf(toolSpecs);
        this.tools = tools;
        this.maxToolRounds = maxToolRounds;
        this.maxHistoryMessages = maxHistoryMessages;
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** Milliseconds the current reply has been in flight, or 0 when idle. */
    public long busyMillis() {
        return busy.get() ? System.currentTimeMillis() - busySince : 0L;
    }

    /** The player's last few lines to this villager, without the [name] tag; [world] notices are skipped. */
    public synchronized List<String> recentUserLines(int max) {
        List<String> out = new java.util.ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && out.size() < max; i--) {
            ChatMessage m = history.get(i);
            if (m.role() != ChatMessage.Role.USER) continue;
            String t = m.text().trim();
            if (t.startsWith("[world]") || t.isEmpty()) continue;
            if (t.startsWith("[")) {
                int close = t.indexOf(']');
                if (close > 0) t = t.substring(close + 1).trim();
            }
            if (!t.isEmpty() && t.length() <= 80) out.add(t);
        }
        return out;
    }

    public synchronized int historySize() {
        return history.size();
    }

    public synchronized void clearHistory() {
        history.clear();
    }

    /**
     * Send a player utterance and resolve to the NPC's spoken reply.
     * Rejects (fails the future) if a previous exchange is still in flight.
     */
    public CompletableFuture<String> respond(String systemPrompt, String userText) {
        if (!busy.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(new IllegalStateException("still thinking"));
        }
        busySince = System.currentTimeMillis();
        synchronized (this) {
            trimHistory();
            exchangeStart = history.size();
            history.add(ChatMessage.user(userText));
        }
        return step(systemPrompt, 0).whenComplete((r, t) -> {
            if (t != null) rollBackExchange();
            busy.set(false);
        });
    }

    /** Where the current exchange began; a failure removes everything after it so tool_use/tool_result pairs never dangle. */
    private int exchangeStart;

    /**
     * After a failed or refused exchange, remove the player's message and every tool round that followed it,
     * so roles keep alternating and no tool_use is left without its tool_result.
     */
    private synchronized void rollBackExchange() {
        if (history.size() > exchangeStart) history.subList(exchangeStart, history.size()).clear();
    }

    private CompletableFuture<String> step(String systemPrompt, int round) {
        LlmRequest request;
        synchronized (this) {
            request = new LlmRequest(systemPrompt, List.copyOf(history), toolSpecs);
        }
        return provider.complete(request).thenCompose(response -> {
            LOGGER.debug("stop={} in={} out={} cacheRead={} cacheWrite={}", response.stopReason(),
                    response.usage().inputTokens(), response.usage().outputTokens(),
                    response.usage().cacheReadTokens(), response.usage().cacheWriteTokens());

            if (response.stopReason() == LlmResponse.StopReason.REFUSAL) {
                // Don't record the refusal or anything leading up to it; the player can rephrase.
                rollBackExchange();
                return CompletableFuture.completedFuture("...I'd rather not talk about that.");
            }

            synchronized (this) {
                history.add(ChatMessage.assistant(response.content()));
            }

            List<ContentPart.ToolUse> calls = response.toolUses();
            if (response.stopReason() != LlmResponse.StopReason.TOOL_USE || calls.isEmpty()) {
                String text = response.text().strip();
                return CompletableFuture.completedFuture(text.isEmpty() ? "..." : text);
            }

            if (round >= maxToolRounds) {
                List<ContentPart.ToolResult> exhausted = calls.stream()
                        .map(c -> ToolExecutor.error(c, "Tool budget exhausted; answer with what you already know."))
                        .toList();
                synchronized (this) {
                    history.add(ChatMessage.toolResults(exhausted));
                }
                return stepWithoutTools(systemPrompt);
            }

            List<CompletableFuture<ContentPart.ToolResult>> futures = new ArrayList<>();
            for (ContentPart.ToolUse call : calls) {
                futures.add(tools.execute(call).exceptionally(t -> {
                    LOGGER.warn("tool {} failed", call.name(), t);
                    return ToolExecutor.error(call, "Tool failed: " + t.getMessage());
                }));
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenCompose(v -> {
                List<ContentPart.ToolResult> results = futures.stream().map(CompletableFuture::join).toList();
                synchronized (this) {
                    history.add(ChatMessage.toolResults(results));
                }
                return step(systemPrompt, round + 1);
            });
        });
    }

    /** Final call after the tool budget is spent: no tools offered, so the model must answer in text. */
    private CompletableFuture<String> stepWithoutTools(String systemPrompt) {
        LlmRequest request;
        synchronized (this) {
            request = new LlmRequest(systemPrompt, List.copyOf(history), List.of());
        }
        return provider.complete(request).thenApply(response -> {
            synchronized (this) {
                history.add(ChatMessage.assistant(response.content()));
            }
            String text = response.text().strip();
            return text.isEmpty() ? "..." : text;
        });
    }

    /** Drop oldest exchanges, but only cut at a plain user message so tool_use/tool_result pairs stay intact. */
    private void trimHistory() {
        while (history.size() > maxHistoryMessages) {
            int cut = -1;
            for (int i = 1; i < history.size(); i++) {
                ChatMessage m = history.get(i);
                if (m.role() == ChatMessage.Role.USER && !m.hasToolResults()) {
                    cut = i;
                    break;
                }
            }
            if (cut <= 0) return;
            history.subList(0, cut).clear();
        }
    }

    // ---- persistence (JSON, stored in the entity's save data) ----

    public synchronized JsonArray toJson() {
        JsonArray arr = new JsonArray();
        for (ChatMessage m : history) {
            JsonObject o = new JsonObject();
            o.addProperty("role", m.role().name());
            JsonArray parts = new JsonArray();
            for (ContentPart p : m.parts()) {
                JsonObject po = new JsonObject();
                switch (p) {
                    case ContentPart.Text t -> {
                        po.addProperty("kind", "text");
                        po.addProperty("text", t.text());
                    }
                    case ContentPart.ToolUse u -> {
                        po.addProperty("kind", "tool_use");
                        po.addProperty("id", u.id());
                        po.addProperty("name", u.name());
                        po.add("input", u.input());
                    }
                    case ContentPart.ToolResult r -> {
                        po.addProperty("kind", "tool_result");
                        po.addProperty("id", r.toolUseId());
                        po.addProperty("content", r.content());
                        po.addProperty("error", r.isError());
                    }
                    case ContentPart.Raw raw -> {
                        po.addProperty("kind", "raw");
                        po.add("json", raw.json());
                    }
                }
                parts.add(po);
            }
            o.add("parts", parts);
            arr.add(o);
        }
        return arr;
    }

    public synchronized void loadJson(JsonArray arr) {
        history.clear();
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            ChatMessage.Role role = ChatMessage.Role.valueOf(o.get("role").getAsString());
            List<ContentPart> parts = new ArrayList<>();
            for (JsonElement pe : o.getAsJsonArray("parts")) {
                JsonObject po = pe.getAsJsonObject();
                switch (po.get("kind").getAsString()) {
                    case "text" -> parts.add(new ContentPart.Text(po.get("text").getAsString()));
                    case "tool_use" -> parts.add(new ContentPart.ToolUse(po.get("id").getAsString(),
                            po.get("name").getAsString(), po.getAsJsonObject("input")));
                    case "tool_result" -> parts.add(new ContentPart.ToolResult(po.get("id").getAsString(),
                            po.get("content").getAsString(), po.get("error").getAsBoolean()));
                    case "raw" -> parts.add(new ContentPart.Raw(po.getAsJsonObject("json")));
                    default -> { }
                }
            }
            history.add(new ChatMessage(role, parts));
        }
        trimHistory();
    }
}
