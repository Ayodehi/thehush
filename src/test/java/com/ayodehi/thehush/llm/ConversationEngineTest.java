package com.ayodehi.thehush.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ConversationEngineTest {

    /** Scripted provider: hands out canned responses in order and records every request it saw. */
    static final class FakeProvider implements LlmProvider {
        final Deque<LlmResponse> script = new ArrayDeque<>();
        final List<LlmRequest> seen = new ArrayList<>();

        FakeProvider text(String t) {
            script.add(new LlmResponse(List.of(new ContentPart.Text(t)), LlmResponse.StopReason.END_TURN, zero()));
            return this;
        }

        FakeProvider toolCall(String id, String name) {
            script.add(new LlmResponse(List.of(
                    new ContentPart.Text("Let me look."),
                    new ContentPart.ToolUse(id, name, new JsonObject())), LlmResponse.StopReason.TOOL_USE, zero()));
            return this;
        }

        FakeProvider refusal() {
            script.add(new LlmResponse(List.of(), LlmResponse.StopReason.REFUSAL, zero()));
            return this;
        }

        @Override
        public CompletableFuture<LlmResponse> complete(LlmRequest request) {
            seen.add(request);
            LlmResponse next = script.poll();
            return next == null
                    ? CompletableFuture.failedFuture(new LlmException("script exhausted"))
                    : CompletableFuture.completedFuture(next);
        }

        @Override
        public String describe() { return "fake"; }

        private static LlmResponse.Usage zero() { return new LlmResponse.Usage(0, 0, 0, 0); }
    }

    private static final ToolSpec CLOCK = ToolSpec.noArgs("get_time", "time");

    @Test
    void plainReplyIsRecordedInHistory() {
        FakeProvider p = new FakeProvider().text("Hello there.");
        ConversationEngine e = new ConversationEngine(p, List.of(CLOCK), call -> fail("no tool expected"), 3, 40);

        assertEquals("Hello there.", e.respond("sys", "[Ben] hi").join());
        assertEquals(2, e.historySize());
        assertEquals("sys", p.seen.get(0).systemPrompt());
        assertEquals(List.of(CLOCK), p.seen.get(0).tools());
        assertFalse(e.isBusy());
    }

    @Test
    void toolCallsAreExecutedAndFedBack() {
        FakeProvider p = new FakeProvider().toolCall("t1", "get_time").text("It is noon.");
        List<String> executed = new ArrayList<>();
        ToolExecutor tools = call -> {
            executed.add(call.name());
            return CompletableFuture.completedFuture(ToolExecutor.ok(call, "noon"));
        };
        ConversationEngine e = new ConversationEngine(p, List.of(CLOCK), tools, 3, 40);

        assertEquals("It is noon.", e.respond("sys", "[Ben] what time is it?").join());
        assertEquals(List.of("get_time"), executed);
        // user, assistant(tool_use), user(tool_result), assistant(text)
        assertEquals(4, e.historySize());
        // second request carried the tool result back to the model
        LlmRequest second = p.seen.get(1);
        ChatMessage toolResultMsg = second.messages().get(2);
        assertTrue(toolResultMsg.hasToolResults());
        assertEquals("noon", ((ContentPart.ToolResult) toolResultMsg.parts().get(0)).content());
    }

    @Test
    void toolBudgetExhaustionForcesTextAnswer() {
        FakeProvider p = new FakeProvider().toolCall("t1", "get_time").text("Fine, noon-ish.");
        ToolExecutor never = call -> fail("tool should not run once the budget is spent");
        ConversationEngine e = new ConversationEngine(p, List.of(CLOCK), never, 0, 40);

        assertEquals("Fine, noon-ish.", e.respond("sys", "[Ben] time?").join());
        assertTrue(p.seen.get(1).tools().isEmpty(), "final request must not offer tools");
    }

    @Test
    void refusalLeavesHistoryCleanAndAnswersInCharacter() {
        FakeProvider p = new FakeProvider().refusal();
        ConversationEngine e = new ConversationEngine(p, List.of(), call -> fail(), 3, 40);

        String reply = e.respond("sys", "[Ben] something off-limits").join();
        assertFalse(reply.isBlank());
        assertEquals(0, e.historySize());
    }

    @Test
    void providerFailureDropsTheUnansweredTurn() {
        FakeProvider p = new FakeProvider(); // empty script -> failure
        ConversationEngine e = new ConversationEngine(p, List.of(), call -> fail(), 3, 40);

        assertThrows(Exception.class, () -> e.respond("sys", "[Ben] hi").join());
        assertEquals(0, e.historySize());
        assertFalse(e.isBusy());
    }

    @Test
    void failureAfterAToolRoundRollsBackTheWholeExchange() {
        // Round 1 asks for a tool; round 2 (the script is exhausted) fails. Nothing of the exchange may remain,
        // least of all an assistant tool_use without its tool_result, which the API would reject forever after.
        FakeProvider p = new FakeProvider().text("Hello.").toolCall("t1", "get_time");
        ToolExecutor tools = call -> CompletableFuture.completedFuture(ToolExecutor.ok(call, "noon"));
        ConversationEngine e = new ConversationEngine(p, List.of(CLOCK), tools, 3, 40);
        assertEquals("Hello.", e.respond("sys", "[Ben] hi").join());
        assertEquals(2, e.historySize());
        assertThrows(Exception.class, () -> e.respond("sys", "[Ben] what time is it?").join());
        assertEquals(2, e.historySize(), "the failed exchange, tool round included, is gone");
        // And the engine still works afterwards.
        p.text("Still here.");
        assertEquals("Still here.", e.respond("sys", "[Ben] you there?").join());
        assertEquals(4, e.historySize());
    }

    @Test
    void trimmingKeepsToolPairsIntact() {
        FakeProvider p = new FakeProvider();
        for (int i = 0; i < 6; i++) p.toolCall("t" + i, "get_time").text("reply " + i);
        ToolExecutor tools = call -> CompletableFuture.completedFuture(ToolExecutor.ok(call, "noon"));
        ConversationEngine e = new ConversationEngine(p, List.of(CLOCK), tools, 3, 6);

        for (int i = 0; i < 6; i++) e.respond("sys", "[Ben] q" + i).join();

        assertTrue(e.historySize() <= 8, "history should be trimmed, was " + e.historySize());
        List<ChatMessage> h = e.toJson().size() > 0 ? roundTrip(e) : List.of();
        assertEquals(ChatMessage.Role.USER, h.get(0).role());
        assertFalse(h.get(0).hasToolResults(), "history must start with a plain user turn");
    }

    @Test
    void jsonRoundTripPreservesEveryPartKind() {
        FakeProvider p = new FakeProvider().toolCall("t1", "get_time").text("noon");
        ToolExecutor tools = call -> CompletableFuture.completedFuture(ToolExecutor.error(call, "broken"));
        ConversationEngine e = new ConversationEngine(p, List.of(CLOCK), tools, 3, 40);
        e.respond("sys", "[Ben] time?").join();

        ConversationEngine copy = new ConversationEngine(p, List.of(CLOCK), tools, 3, 40);
        copy.loadJson(e.toJson());
        assertEquals(e.toJson(), copy.toJson());
        assertEquals(4, copy.historySize());
    }

    private static List<ChatMessage> roundTrip(ConversationEngine e) {
        ConversationEngine copy = new ConversationEngine(new FakeProvider(), List.of(), call -> fail(), 3, 400);
        copy.loadJson(e.toJson());
        List<ChatMessage> out = new ArrayList<>();
        // loadJson keeps order; re-serialise to inspect roles
        e.toJson().forEach(el -> {
            JsonObject o = el.getAsJsonObject();
            out.add(new ChatMessage(ChatMessage.Role.valueOf(o.get("role").getAsString()),
                    o.getAsJsonArray("parts").size() > 0
                            && o.getAsJsonArray("parts").get(0).getAsJsonObject().get("kind").getAsString().equals("tool_result")
                            ? List.of(new ContentPart.ToolResult("x", "y", false))
                            : List.of(new ContentPart.Text("z"))));
        });
        return out;
    }
}
