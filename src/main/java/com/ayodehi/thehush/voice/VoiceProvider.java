package com.ayodehi.thehush.voice;

import java.util.concurrent.CompletableFuture;

/** Turns a line of speech into audio. Implementations are called off the server thread. */
public interface VoiceProvider {
    /** 16-bit signed little-endian mono PCM. */
    record Audio(byte[] pcm, int sampleRate) {}

    /** What the account has left: characters used and allowed this period, when it resets (epoch seconds, -1 unknown), and the plan. */
    record Credits(long used, long limit, long resetEpochSeconds, String tier) {
        public long remaining() {
            return Math.max(0, limit - used);
        }

        public boolean exhausted() {
            return limit > 0 && used >= limit;
        }
    }

    /** Failures complete exceptionally with a {@link VoiceException} where the cause is known. */
    CompletableFuture<Audio> speak(String text, Speech.Manner manner);

    /** The account's remaining credit, if the engine can say. */
    default CompletableFuture<Credits> credits() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("no credit report"));
    }

    /** True when the engine reads bracketed delivery cues itself (ElevenLabs v3); otherwise they are stripped. */
    boolean readsCues();

    String describe();
}
