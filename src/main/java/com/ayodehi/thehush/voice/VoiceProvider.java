package com.ayodehi.thehush.voice;

import java.util.concurrent.CompletableFuture;

/** Turns a line of speech into audio. Implementations are called off the server thread. */
public interface VoiceProvider {
    /** 16-bit signed little-endian mono PCM. */
    record Audio(byte[] pcm, int sampleRate) {}

    CompletableFuture<Audio> speak(String text, Speech.Manner manner);

    /** True when the engine reads bracketed delivery cues itself (ElevenLabs v3); otherwise they are stripped. */
    boolean readsCues();

    String describe();
}
