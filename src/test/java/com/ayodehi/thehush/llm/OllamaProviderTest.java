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

class OllamaProviderTest {
    private static OllamaProvider provider(boolean think) {
        Executor direct = Runnable::run;
        return new OllamaProvider(new OllamaProvider.Settings("http://127.0.0.1:11434", "llama3.1", 2048, 16384, think, Duration.ofSeconds(5)), direct);
    }

    @Test
    void requestBodyMatchesOllamaChatShape() {
        JsonObject input = new JsonObject();
        input.addProperty("radius", 8);
        List<ChatMessage> msgs = List.of(
                ChatMessage.user("[Ben] hi"),
                ChatMessage.assistant(List.of(new ContentPart.ToolUse("call_1", "get_nearby_creatures", input))),
                ChatMessage.toolResults(List.of(new ContentPart.ToolResult("call_1", "2 cows", false))),
                ChatMessage.assistant(List.of(new ContentPart.Text("Two cows, Ben."))));
        JsonObject body = provider(false).buildBody(new LlmRequest("SYS", msgs, List.of(ToolSpec.noArgs("get_time_and_weather", "time"))), true);

        assertEquals("llama3.1", body.get("model").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
        assertFalse(body.has("think"));
        assertEquals(2048, body.getAsJsonObject("options").get("num_predict").getAsInt());
        assertEquals(16384, body.getAsJsonObject("options").get("num_ctx").getAsInt());

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(5, messages.size(), "system, user, assistant with call, tool result, assistant");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("SYS", messages.get(0).getAsJsonObject().get("content").getAsString());
        JsonObject call = messages.get(2).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function");
        assertEquals("get_nearby_creatures", call.get("name").getAsString());
        assertEquals(8, call.getAsJsonObject("arguments").get("radius").getAsInt());
        JsonObject result = messages.get(3).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("2 cows", result.get("content").getAsString());
        assertEquals("get_nearby_creatures", result.get("tool_name").getAsString());

        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        assertEquals("get_time_and_weather", tool.getAsJsonObject("function").get("name").getAsString());
        assertEquals("object", tool.getAsJsonObject("function").getAsJsonObject("parameters").get("type").getAsString());
    }

    @Test
    void thinkingAndToollessRequests() {
        JsonObject body = provider(true).buildBody(new LlmRequest("SYS", List.of(ChatMessage.user("hi")), List.of(ToolSpec.noArgs("t", "t"))), false);
        assertTrue(body.get("think").getAsBoolean());
        assertFalse(body.has("tools"));
    }

    @Test
    void parsesTextReply() {
        LlmResponse r = OllamaProvider.parse(JsonParser.parseString(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"Walls, Ben.\"},\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":120,\"eval_count\":7}")
                .getAsJsonObject(), new AtomicInteger());
        assertEquals("Walls, Ben.", r.text());
        assertEquals(LlmResponse.StopReason.END_TURN, r.stopReason());
        assertEquals(120, r.usage().inputTokens());
        assertEquals(7, r.usage().outputTokens());
    }

    @Test
    void parsesToolCallsWithObjectOrStringArguments() {
        LlmResponse r = OllamaProvider.parse(JsonParser.parseString(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":["
                + "{\"function\":{\"name\":\"give_item\",\"arguments\":{\"item\":\"bread\",\"count\":3}}},"
                + "{\"function\":{\"name\":\"get_location\",\"arguments\":\"{}\"}}]},\"done_reason\":\"stop\"}")
                .getAsJsonObject(), new AtomicInteger());
        assertEquals(LlmResponse.StopReason.TOOL_USE, r.stopReason());
        List<ContentPart.ToolUse> uses = r.toolUses();
        assertEquals(2, uses.size());
        assertEquals("give_item", uses.get(0).name());
        assertEquals(3, uses.get(0).input().get("count").getAsInt());
        assertEquals("call_1", uses.get(0).id());
        assertEquals("call_2", uses.get(1).id());
        assertEquals(0, uses.get(1).input().size());
    }

    @Test
    void lengthIsMaxTokensAndLocalIsFree() {
        LlmResponse r = OllamaProvider.parse(JsonParser.parseString("{\"message\":{\"content\":\"and so\"},\"done_reason\":\"length\"}").getAsJsonObject(), new AtomicInteger());
        assertEquals(LlmResponse.StopReason.MAX_TOKENS, r.stopReason());
        assertEquals(0.0, Pricing.cost("ollama/llama3.1", 1000, 1000, 0, 0, 3.0, 15.0));
    }
}
