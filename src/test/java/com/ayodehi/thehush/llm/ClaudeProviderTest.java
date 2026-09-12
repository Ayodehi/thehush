package com.ayodehi.thehush.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class ClaudeProviderTest {

    private static ClaudeProvider provider() {
        Executor direct = Runnable::run;
        return new ClaudeProvider(new ClaudeProvider.Settings("k", "https://example.invalid", "claude-opus-5",
                4096, "low", Duration.ofSeconds(5)), direct);
    }

    @Test
    void requestBodyMatchesMessagesApiShape() {
        JsonObject input = new JsonObject();
        input.addProperty("radius", 8);
        List<ChatMessage> msgs = List.of(
                ChatMessage.user("[Ben] hi"),
                ChatMessage.assistant(List.of(new ContentPart.ToolUse("t1", "get_nearby_creatures", input))),
                ChatMessage.toolResults(List.of(new ContentPart.ToolResult("t1", "2 cows", false))));
        ToolSpec spec = ToolSpec.noArgs("get_time_and_weather", "time");

        JsonObject body = provider().buildBody(new LlmRequest("SYS", msgs, List.of(spec)));

        assertEquals("claude-opus-5", body.get("model").getAsString());
        assertEquals(4096, body.get("max_tokens").getAsInt());
        assertEquals("default", body.get("fallbacks").getAsString());
        assertEquals("low", body.getAsJsonObject("output_config").get("effort").getAsString());
        assertFalse(body.has("thinking"), "adaptive thinking is the default; don't send the param");

        JsonArray system = body.getAsJsonArray("system");
        assertEquals("SYS", system.get(0).getAsJsonObject().get("text").getAsString());
        assertTrue(system.get(0).getAsJsonObject().has("cache_control"));

        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("get_time_and_weather", tool.get("name").getAsString());
        assertEquals("object", tool.getAsJsonObject("input_schema").get("type").getAsString());

        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(3, messages.size());
        JsonObject toolUse = messages.get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("tool_use", toolUse.get("type").getAsString());
        assertEquals(8, toolUse.getAsJsonObject("input").get("radius").getAsInt());
        JsonObject toolResult = messages.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("tool_result", toolResult.get("type").getAsString());
        assertEquals("t1", toolResult.get("tool_use_id").getAsString());
        assertFalse(toolResult.has("is_error"));
        assertTrue(toolResult.has("cache_control"), "last message carries the second cache breakpoint");
    }

    @Test
    void responseParsingCoversTextToolUseAndUnknownBlocks() {
        JsonObject json = JsonParser.parseString("""
                {"id":"msg_1","type":"message","role":"assistant","stop_reason":"tool_use",
                 "content":[
                   {"type":"thinking","thinking":"","signature":"abc"},
                   {"type":"text","text":"Let me look."},
                   {"type":"tool_use","id":"toolu_1","name":"get_location","input":{}}
                 ],
                 "usage":{"input_tokens":120,"output_tokens":30,"cache_read_input_tokens":100,"cache_creation_input_tokens":0}}
                """).getAsJsonObject();

        LlmResponse r = ClaudeProvider.parse(json);

        assertEquals(LlmResponse.StopReason.TOOL_USE, r.stopReason());
        assertEquals("Let me look.", r.text());
        assertEquals(1, r.toolUses().size());
        assertEquals("get_location", r.toolUses().get(0).name());
        assertInstanceOf(ContentPart.Raw.class, r.content().get(0), "thinking block preserved verbatim");
        assertEquals(120, r.usage().inputTokens());
        assertEquals(100, r.usage().cacheReadTokens());
    }

    @Test
    void refusalAndEmptyContentDoNotBlowUp() {
        JsonObject json = JsonParser.parseString("""
                {"stop_reason":"refusal","content":[],"stop_details":{"type":"refusal","category":null}}
                """).getAsJsonObject();
        LlmResponse r = ClaudeProvider.parse(json);
        assertEquals(LlmResponse.StopReason.REFUSAL, r.stopReason());
        assertEquals("", r.text());
    }

    @Test
    void rawBlocksRoundTripUnchangedAndNeverGetCacheControl() {
        JsonObject thinking = JsonParser.parseString("{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"s\"}").getAsJsonObject();
        List<ChatMessage> msgs = List.of(
                ChatMessage.user("[Ben] hi"),
                ChatMessage.assistant(List.of(new ContentPart.Raw(thinking))));
        JsonObject body = provider().buildBody(new LlmRequest("SYS", msgs, List.of()));
        JsonObject last = body.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
        assertEquals("thinking", last.get("type").getAsString());
        assertEquals("s", last.get("signature").getAsString());
        assertFalse(last.has("cache_control"));
        assertFalse(body.has("tools"), "no tools array when none are offered");
    }

    @Test
    void recognisesUnsupportedParameterErrors() {
        assertEquals("fallbacks", ClaudeProvider.unsupportedParameter(
                "invalid_request_error - 'claude-sonnet-5' does not support the `fallbacks` parameter."));
        assertEquals("output_config", ClaudeProvider.unsupportedParameter("model does not support the `output_config` parameter"));
        assertNull(ClaudeProvider.unsupportedParameter("invalid_request_error - max_tokens too large"));
    }
}
