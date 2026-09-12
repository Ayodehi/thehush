package com.ayodehi.thehush.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiProviderTest {
    private static OpenAiProvider provider(String url, String effort) {
        Executor direct = Runnable::run;
        return new OpenAiProvider(new OpenAiProvider.Settings(url, "sk-test", "gpt-4o-mini", 1024, effort, 0.15, 0.6, Duration.ofSeconds(5)), direct);
    }

    @Test
    void endpointToleratesTrailingV1() {
        assertEquals("http://127.0.0.1:4000/v1/chat/completions", provider("http://127.0.0.1:4000", "").endpoint());
        assertEquals("http://127.0.0.1:4000/v1/chat/completions", provider("http://127.0.0.1:4000/", "").endpoint());
        assertEquals("https://api.openai.com/v1/chat/completions", provider("https://api.openai.com/v1", "").endpoint());
    }

    @Test
    void requestBodyMatchesChatCompletionsShape() {
        JsonObject input = new JsonObject();
        input.addProperty("item", "bread");
        List<ChatMessage> msgs = List.of(
                ChatMessage.user("[Ben] bread please"),
                ChatMessage.assistant(List.of(new ContentPart.ToolUse("call_abc", "give_item", input))),
                ChatMessage.toolResults(List.of(new ContentPart.ToolResult("call_abc", "You hand Ben 1 Bread.", false))),
                ChatMessage.assistant(List.of(new ContentPart.Text("There."))));
        JsonObject body = provider("http://127.0.0.1:4000", "low").buildBody(new LlmRequest("SYS", msgs, List.of(ToolSpec.noArgs("get_location", "where"))), true);

        assertEquals("gpt-4o-mini", body.get("model").getAsString());
        assertEquals(1024, body.get("max_tokens").getAsInt());
        assertEquals("low", body.get("reasoning_effort").getAsString());
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(5, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        JsonObject call = messages.get(2).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_abc", call.get("id").getAsString());
        assertEquals("function", call.get("type").getAsString());
        assertEquals("give_item", call.getAsJsonObject("function").get("name").getAsString());
        assertTrue(call.getAsJsonObject("function").get("arguments").isJsonPrimitive(), "arguments are a JSON string");
        assertEquals("{\"item\":\"bread\"}", call.getAsJsonObject("function").get("arguments").getAsString());
        JsonObject result = messages.get(3).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("call_abc", result.get("tool_call_id").getAsString());
        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("get_location", tool.getAsJsonObject("function").get("name").getAsString());
    }

    @Test
    void noEffortNoToolsWhenAsked() {
        JsonObject body = provider("http://x", "").buildBody(new LlmRequest("SYS", List.of(ChatMessage.user("hi")), List.of(ToolSpec.noArgs("t", "t"))), false);
        assertFalse(body.has("reasoning_effort"));
        assertFalse(body.has("tools"));
    }

    @Test
    void parsesTextWithCachedUsage() {
        LlmResponse r = OpenAiProvider.parse(JsonParser.parseString(
                "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Walls, Ben.\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":7,\"prompt_tokens_details\":{\"cached_tokens\":800}}}").getAsJsonObject(), new AtomicInteger());
        assertEquals("Walls, Ben.", r.text());
        assertEquals(LlmResponse.StopReason.END_TURN, r.stopReason());
        assertEquals(200, r.usage().inputTokens());
        assertEquals(800, r.usage().cacheReadTokens());
        assertEquals(7, r.usage().outputTokens());
    }

    @Test
    void parsesToolCallsWithStringArguments() {
        LlmResponse r = OpenAiProvider.parse(JsonParser.parseString(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_9\",\"type\":\"function\","
                + "\"function\":{\"name\":\"give_item\",\"arguments\":\"{\\\"item\\\":\\\"bread\\\",\\\"count\\\":2}\"}}]},\"finish_reason\":\"tool_calls\"}]}").getAsJsonObject(), new AtomicInteger());
        assertEquals(LlmResponse.StopReason.TOOL_USE, r.stopReason());
        ContentPart.ToolUse use = r.toolUses().get(0);
        assertEquals("call_9", use.id());
        assertEquals("give_item", use.name());
        assertEquals(2, use.input().get("count").getAsInt());
    }

    @Test
    void finishReasonsAndPricing() {
        assertEquals(LlmResponse.StopReason.MAX_TOKENS, OpenAiProvider.parse(JsonParser.parseString("{\"choices\":[{\"message\":{\"content\":\"a\"},\"finish_reason\":\"length\"}]}").getAsJsonObject(), new AtomicInteger()).stopReason());
        assertEquals(LlmResponse.StopReason.REFUSAL, OpenAiProvider.parse(JsonParser.parseString("{\"choices\":[{\"message\":{\"content\":\"\"},\"finish_reason\":\"content_filter\"}]}").getAsJsonObject(), new AtomicInteger()).stopReason());
        assertEquals(0.0, Pricing.cost("openai/gpt-4o-mini", 1_000_000, 1_000_000, 0, 0, 0.0, 0.0), "unknown prices show as zero");
        assertEquals(0.75, Pricing.cost("openai/gpt-4o-mini", 1_000_000, 1_000_000, 0, 0, 0.15, 0.6), 1e-9);
        assertEquals(0.0, Pricing.cost("ollama/llama3.1", 1_000_000, 1_000_000, 0, 0, 3.0, 15.0), "local is never priced");
    }
}
