package com.ayodehi.thehush.voice;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * ElevenLabs text to speech over its REST API: POST /v1/text-to-speech/{voice}?output_format=pcm_22050
 * with the xi-api-key header, JSON body {text, model_id, voice_settings}, raw PCM back. If the account's
 * tier refuses the sample rate, the request is retried at 16 kHz.
 */
public final class ElevenLabsVoice implements VoiceProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("thehush/voice");

    public record Settings(String apiKey, String voiceId, String model, double stability, double similarity, int timeoutSeconds) {}

    private final Settings settings;
    private final HttpClient http;
    private final Executor executor;
    private volatile int sampleRate = 22050;

    public ElevenLabsVoice(Settings settings, Executor executor) {
        this.settings = settings;
        this.executor = executor;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    }

    @Override
    public String describe() {
        return "ElevenLabs (" + settings.model() + ", voice " + settings.voiceId() + ")";
    }

    @Override
    public boolean readsCues() {
        return settings.model().startsWith("eleven_v3");
    }

    @Override
    public CompletableFuture<Audio> speak(String text, Speech.Manner manner) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return request(text, sampleRate, manner);
            } catch (RateRefused e) {
                if (sampleRate != 16000) {
                    LOGGER.info("ElevenLabs refused pcm_{}; using pcm_16000 from now on", sampleRate);
                    sampleRate = 16000;
                    try {
                        return request(text, 16000, manner);
                    } catch (IOException | RateRefused e2) {
                        throw new RuntimeException(e2.getMessage(), e2);
                    }
                }
                throw new RuntimeException(e.getMessage(), e);
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage(), e);
            }
        }, executor);
    }

    private static final class RateRefused extends Exception {
        RateRefused(String msg) {
            super(msg);
        }
    }

    private Audio request(String text, int rate, Speech.Manner manner) throws IOException, RateRefused {
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("model_id", settings.model());
        JsonObject vs = new JsonObject();
        double stability = settings.stability();
        if (!readsCues()) {
            // Older models cannot read cues; nudge the settings instead.
            switch (manner) {
                case WHISPER, SOFT -> stability = Math.min(1.0, stability + 0.25);
                case SHOUT, SHARP -> stability = Math.max(0.0, stability - 0.25);
                default -> { }
            }
        } else {
            // v3 accepts only creative (0.0), natural (0.5) and robust (1.0).
            stability = stability < 0.25 ? 0.0 : stability < 0.75 ? 0.5 : 1.0;
        }
        vs.addProperty("stability", stability);
        vs.addProperty("similarity_boost", settings.similarity());
        vs.addProperty("use_speaker_boost", true);
        if (!readsCues() && manner == Speech.Manner.SHARP) vs.addProperty("speed", 1.1);
        if (!readsCues() && (manner == Speech.Manner.SOFT || manner == Speech.Manner.WHISPER)) vs.addProperty("speed", 0.92);
        body.add("voice_settings", vs);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.elevenlabs.io/v1/text-to-speech/"
                        + settings.voiceId() + "?output_format=pcm_" + rate))
                .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                .header("xi-api-key", settings.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "audio/*")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (resp.statusCode() / 100 != 2) {
            String detail = new String(resp.body(), StandardCharsets.UTF_8);
            if (detail.length() > 300) detail = detail.substring(0, 300);
            if (resp.statusCode() == 400 || resp.statusCode() == 422) {
                String lower = detail.toLowerCase();
                if (lower.contains("output_format") || lower.contains("pcm")) throw new RateRefused("ElevenLabs " + resp.statusCode() + ": " + detail);
            }
            throw new IOException("ElevenLabs " + resp.statusCode() + ": " + detail);
        }
        return new Audio(resp.body(), rate);
    }
}
