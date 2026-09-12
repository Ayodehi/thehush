package com.ayodehi.thehush.llm;

import java.util.List;

public record LlmResponse(List<ContentPart> content, StopReason stopReason, Usage usage) {

    public enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, REFUSAL, OTHER }

    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {}

    public List<ContentPart.ToolUse> toolUses() {
        return content.stream()
                .filter(p -> p instanceof ContentPart.ToolUse)
                .map(p -> (ContentPart.ToolUse) p)
                .toList();
    }

    public String text() {
        return new ChatMessage(ChatMessage.Role.ASSISTANT, content).text();
    }
}
