package com.ayodehi.thehush.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
            throw classify(resp.statusCode(), detail);
        }
        return new Audio(resp.body(), rate);
    }

    /**
     * ElevenLabs answers errors with {"detail": {"status": "...", "message": "..."}}. The statuses that
     * matter: quota_exceeded (no characters left), invalid_api_key / missing_permissions /
     * detected_unusual_activity (the key), too_many_concurrent_requests / system_busy (later), voice_not_found.
     */
    static VoiceException classify(int code, String body) {
        String status = "";
        String message = body;
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has("detail")) {
                JsonElement d = root.getAsJsonObject().get("detail");
                if (d.isJsonObject()) {
                    JsonObject o = d.getAsJsonObject();
                    if (o.has("status")) status = o.get("status").getAsString();
                    if (o.has("message")) message = o.get("message").getAsString();
                } else if (d.isJsonPrimitive()) {
                    message = d.getAsString();
                }
            }
        } catch (RuntimeException ignored) {
            // not JSON; keep the raw body
        }
        String lower = (status + " " + message).toLowerCase();
        VoiceException.Kind kind;
        if (status.equals("quota_exceeded") || lower.contains("quota") || lower.contains("credits remaining") || code == 402) kind = VoiceException.Kind.QUOTA;
        else if (code == 429 || status.contains("too_many") || status.equals("system_busy")) kind = VoiceException.Kind.RATE_LIMIT;
        else if (code == 401 || code == 403 || status.contains("api_key") || status.contains("permission") || status.contains("unusual_activity")) kind = VoiceException.Kind.AUTH;
        else if (status.contains("voice") || status.contains("model")) kind = VoiceException.Kind.VOICE;
        else kind = VoiceException.Kind.OTHER;
        String shown = status.isEmpty() ? message : status + ": " + message;
        return new VoiceException(kind, "ElevenLabs " + code + " (" + shown + ")");
    }

    /** GET /v1/user/subscription: character_count, character_limit, next_character_count_reset_unix, tier. */
    @Override
    public CompletableFuture<Credits> credits() {
        return CompletableFuture.supplyAsync(() -> {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.elevenlabs.io/v1/user/subscription"))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("xi-api-key", settings.apiKey())
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new VoiceException(VoiceException.Kind.OTHER, "ElevenLabs subscription: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VoiceException(VoiceException.Kind.OTHER, "interrupted");
            }
            if (resp.statusCode() / 100 != 2) throw classify(resp.statusCode(), resp.body());
            JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
            long used = o.has("character_count") ? o.get("character_count").getAsLong() : 0;
            long limit = o.has("character_limit") ? o.get("character_limit").getAsLong() : 0;
            long reset = o.has("next_character_count_reset_unix") && !o.get("next_character_count_reset_unix").isJsonNull()
                    ? o.get("next_character_count_reset_unix").getAsLong() : -1;
            String tier = o.has("tier") && !o.get("tier").isJsonNull() ? o.get("tier").getAsString() : "";
            return new Credits(used, limit, reset, tier);
        }, executor);
    }
}
