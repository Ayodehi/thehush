package com.ayodehi.thehush.llm;

import com.google.gson.JsonObject;

/** One block inside a chat message. Provider-neutral so other backends can be added later. */
public sealed interface ContentPart {

    record Text(String text) implements ContentPart {}

    /** The model asked us to run a tool. */
    record ToolUse(String id, String name, JsonObject input) implements ContentPart {}

    /** Our answer to a previous ToolUse. */
    record ToolResult(String toolUseId, String content, boolean isError) implements ContentPart {}

    /**
     * A block we don't interpret (e.g. thinking blocks with signatures). Preserved verbatim so it can be
     * echoed back to the provider unchanged, which some providers require.
     */
    record Raw(JsonObject json) implements ContentPart {}
}
