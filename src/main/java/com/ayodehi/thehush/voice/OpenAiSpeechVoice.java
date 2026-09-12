package com.ayodehi.thehush.voice;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * The OpenAI speech endpoint, POST /v1/audio/speech, as served by OpenAI itself and by the local servers
 * for Kokoro (Kokoro-FastAPI), Orpheus (Orpheus-FastAPI), Chatterbox (chatterbox-tts-api), and by
 * openedai-speech, LocalAI, Speaches and LiteLLM. One request per line, WAV or raw PCM back. How his
 * delivery cues reach the engine depends on the server: as an instructions field (OpenAI's newer model),
 * as Orpheus's inline emotion tags, or not at all.
 */
public final class OpenAiSpeechVoice implements VoiceProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("thehush/voice");

    /** What comes back: raw 16-bit mono PCM at pcmSampleRate, or a WAV file whose header says. */
    public enum Format { WAV, PCM }

    /** How the cues in brackets are given to the engine. */
    public enum CueStyle { NONE, INSTRUCTIONS, ORPHEUS }

    public record Settings(String baseUrl, String apiKey, String model, String voice, Format format, int pcmSampleRate,
                           CueStyle cues, double speed, String extraJson, int timeoutSeconds) {}

    private static final Map<String, String> ORPHEUS_TAGS = Map.of(
            "sigh", "<sigh>", "laugh", "<laugh>", "chuckle", "<chuckle>", "gasp", "<gasp>",
            "groan", "<groan>", "yawn", "<yawn>", "cough", "<cough>", "sniff", "<sniffle>");

    private final Settings settings;
    private final HttpClient http;
    private final Executor executor;
    private final Set<String> unsupported = ConcurrentHashMap.newKeySet();

    public OpenAiSpeechVoice(Settings settings, Executor executor) {
        this.settings = settings;
        this.executor = executor;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String describe() {
        String host = settings.baseUrl().replaceFirst("^https?://", "").replaceFirst("/v1/?$", "");
        return "OpenAI-compatible speech (" + settings.model() + ", voice " + settings.voice() + " at " + host + ")";
    }

    @Override
    public boolean readsCues() {
        return settings.cues() != CueStyle.NONE;
    }

    String endpoint() {
        String base = settings.baseUrl().replaceAll("/+$", "");
        return (base.endsWith("/v1") ? base : base + "/v1") + "/audio/speech";
    }

    @Override
    public CompletableFuture<Audio> speak(String text, Speech.Manner manner) {
        return CompletableFuture.supplyAsync(() -> request(text, manner), executor);
    }

    // ---- request ----

    /** The text arrives with its cues still in brackets when readsCues() is true; they are turned into the server's own form here. */
    JsonObject buildBody(String text, Speech.Manner manner) {
        List<String> cues = Speech.cues(text);
        String bare = Speech.spoken(text, false);
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("voice", settings.voice());
        body.addProperty("response_format", settings.format() == Format.PCM ? "pcm" : "wav");
        double speed = settings.speed();
        switch (settings.cues()) {
            case INSTRUCTIONS -> {
                if (!unsupported.contains("instructions")) body.addProperty("instructions", instructions(cues, manner));
            }
            case ORPHEUS -> bare = orpheusTags(cues) + bare;
            default -> {
                // No way to pass cues: lean on pace instead, as with older ElevenLabs models.
                if (manner == Speech.Manner.SHARP) speed *= 1.08;
                if (manner == Speech.Manner.SOFT || manner == Speech.Manner.WHISPER) speed *= 0.94;
            }
        }
        body.addProperty("input", bare);
        if (Math.abs(speed - 1.0) > 0.001 && !unsupported.contains("speed")) body.addProperty("speed", Math.round(speed * 100) / 100.0);
        if (!settings.extraJson().isBlank()) {
            try {
                JsonElement extra = JsonParser.parseString(settings.extraJson());
                if (extra.isJsonObject()) extra.getAsJsonObject().entrySet().forEach(e -> body.add(e.getKey(), e.getValue()));
            } catch (RuntimeException e) {
                LOGGER.warn("voice.openai.extra is not a JSON object; ignored: {}", e.getMessage());
            }
        }
        return body;
    }

    /** A sentence of direction for a model that takes one: the cues as written, then the manner. */
    static String instructions(List<String> cues, Speech.Manner manner) {
        StringBuilder sb = new StringBuilder("An old traveller, weary and guarded, speaking to one person nearby.");
        if (!cues.isEmpty()) sb.append(" Delivery: ").append(String.join(", ", cues)).append('.');
        switch (manner) {
            case WHISPER -> sb.append(" Whispered.");
            case SOFT -> sb.append(" Low and quiet.");
            case SHOUT -> sb.append(" Raised, calling across a distance.");
            case SHARP -> sb.append(" Quick and urgent.");
            default -> { }
        }
        return sb.toString();
    }

    /** Orpheus reads a fixed set of inline tags; cues that name one become it, the rest are dropped. */
    static String orpheusTags(List<String> cues) {
        StringBuilder sb = new StringBuilder();
        for (String cue : cues) {
            String lower = cue.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> e : ORPHEUS_TAGS.entrySet()) {
                if (lower.contains(e.getKey()) && sb.indexOf(e.getValue()) < 0) sb.append(e.getValue()).append(' ');
            }
        }
        return sb.toString();
    }

    // ---- transport ----

    private Audio request(String text, Speech.Manner manner) {
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonObject body = buildBody(text, manner);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(endpoint()))
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "audio/*");
            if (!settings.apiKey().isBlank()) b.header("Authorization", "Bearer " + settings.apiKey());
            HttpResponse<byte[]> resp;
            try {
                resp = http.send(b.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
            } catch (ConnectException e) {
                throw new VoiceException(VoiceException.Kind.OTHER, "nothing is listening at " + settings.baseUrl() + " (is the speech server running?)");
            } catch (IOException e) {
                throw new VoiceException(VoiceException.Kind.OTHER, "speech server: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new VoiceException(VoiceException.Kind.OTHER, "interrupted");
            }
            if (resp.statusCode() / 100 == 2) return decode(resp);
            String detail = new String(resp.body(), StandardCharsets.UTF_8);
            if (detail.length() > 300) detail = detail.substring(0, 300);
            String lower = detail.toLowerCase(Locale.ROOT);
            if (resp.statusCode() == 400 || resp.statusCode() == 422) {
                // Servers differ in which knobs they take; drop the one they name and go again.
                for (String param : new String[] {"instructions", "speed"}) {
                    if (lower.contains(param) && body.has(param) && unsupported.add(param)) {
                        LOGGER.info("{} does not take '{}'; sending without it from now on", settings.model(), param);
                        attempt--;
                        body = null;
                        break;
                    }
                }
                if (body == null) continue;
            }
            throw classify(resp.statusCode(), detail);
        }
        throw new VoiceException(VoiceException.Kind.OTHER, "speech server kept refusing the request");
    }

    static VoiceException classify(int code, String detail) {
        String lower = detail.toLowerCase(Locale.ROOT);
        VoiceException.Kind kind;
        if (code == 401 || code == 403) kind = VoiceException.Kind.AUTH;
        else if (code == 429) kind = VoiceException.Kind.RATE_LIMIT;
        else if (lower.contains("quota") || lower.contains("insufficient_quota") || code == 402) kind = VoiceException.Kind.QUOTA;
        else if (code == 404 || lower.contains("voice") || lower.contains("model")) kind = VoiceException.Kind.VOICE;
        else kind = VoiceException.Kind.OTHER;
        return new VoiceException(kind, "speech server " + code + " (" + detail + ")");
    }

    private Audio decode(HttpResponse<byte[]> resp) {
        byte[] data = resp.body();
        if (data.length == 0) throw new VoiceException(VoiceException.Kind.OTHER, "speech server returned no audio");
        if (Wav.isWav(data)) return Wav.decode(data);
        String type = resp.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        if (settings.format() == Format.PCM || type.contains("pcm") || type.contains("l16") || type.contains("octet-stream")) {
            return new Audio(data.length % 2 == 0 ? data : java.util.Arrays.copyOf(data, data.length - 1), settings.pcmSampleRate());
        }
        throw new VoiceException(VoiceException.Kind.OTHER, "speech server answered with " + (type.isEmpty() ? "an unknown format" : type)
                + "; set voice.openai.format to WAV or PCM to match it");
    }
}
