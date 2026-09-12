package com.ayodehi.thehush.llm;

import java.util.List;

public record ChatMessage(Role role, List<ContentPart> parts) {

    public enum Role { USER, ASSISTANT }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, List.of(new ContentPart.Text(text)));
    }

    public static ChatMessage assistant(List<ContentPart> parts) {
        return new ChatMessage(Role.ASSISTANT, List.copyOf(parts));
    }

    public static ChatMessage toolResults(List<ContentPart.ToolResult> results) {
        return new ChatMessage(Role.USER, List.copyOf(results));
    }

    public boolean hasToolResults() {
        return parts.stream().anyMatch(p -> p instanceof ContentPart.ToolResult);
    }

    /** Concatenated text blocks, or empty string. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentPart p : parts) {
            if (p instanceof ContentPart.Text t) {
                if (!sb.isEmpty()) sb.append('\n');
                sb.append(t.text());
            }
        }
        return sb.toString();
    }
}
