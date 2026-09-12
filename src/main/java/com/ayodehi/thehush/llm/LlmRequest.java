package com.ayodehi.thehush.llm;

import java.util.List;

public record LlmRequest(String systemPrompt, List<ChatMessage> messages, List<ToolSpec> tools) {}
