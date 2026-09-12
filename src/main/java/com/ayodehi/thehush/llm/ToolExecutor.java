package com.ayodehi.thehush.llm;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Runs a tool the model asked for. The game side implements this by hopping onto the server thread,
 * which is why the result is a future rather than a plain value.
 */
@FunctionalInterface
public interface ToolExecutor {
    CompletableFuture<ContentPart.ToolResult> execute(ContentPart.ToolUse call);

    static ContentPart.ToolResult ok(ContentPart.ToolUse call, String content) {
        return new ContentPart.ToolResult(call.id(), content, false);
    }

    static ContentPart.ToolResult error(ContentPart.ToolUse call, String message) {
        return new ContentPart.ToolResult(call.id(), message, true);
    }

    /** Helper for implementations that want the raw input object. */
    static JsonObject inputOf(ContentPart.ToolUse call) {
        return call.input() == null ? new JsonObject() : call.input();
    }
}
