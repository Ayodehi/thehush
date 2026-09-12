package com.ayodehi.thehush.llm;

import java.util.concurrent.CompletableFuture;

/**
 * A backend that turns a conversation into the next assistant message.
 * Implementations must never block the calling thread; the returned future completes on a worker thread.
 */
public interface LlmProvider {
    CompletableFuture<LlmResponse> complete(LlmRequest request);

    /** Human-readable name for logs and the /hush status command. */
    String describe();
}
