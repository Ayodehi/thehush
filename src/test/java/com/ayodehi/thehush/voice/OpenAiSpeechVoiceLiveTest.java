package com.ayodehi.thehush.voice;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Talks to a Kokoro-FastAPI server on the default port if one is running; skipped otherwise. */
class OpenAiSpeechVoiceLiveTest {
    @Test
    void kokoroSpeaksALine() throws Exception {
        boolean up;
        try {
            HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8880/v1/models"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            up = r.statusCode() == 200;
        } catch (Exception e) {
            up = false;
        }
        assumeTrue(up, "no Kokoro server on 127.0.0.1:8880");
        var exec = Executors.newVirtualThreadPerTaskExecutor();
        for (OpenAiSpeechVoice.Format format : OpenAiSpeechVoice.Format.values()) {
            OpenAiSpeechVoice v = new OpenAiSpeechVoice(new OpenAiSpeechVoice.Settings("http://127.0.0.1:8880", "", "kokoro", "bm_george",
                    format, 24000, OpenAiSpeechVoice.CueStyle.NONE, 1.0, "", 30), exec);
            VoiceProvider.Audio a = v.speak("[low] Walls, Ben, now. I'd rather have stone at my back than sky.", Speech.Manner.SOFT).get(60, TimeUnit.SECONDS);
            double seconds = a.pcm().length / 2.0 / a.sampleRate();
            System.out.println("Kokoro " + format + ": " + a.pcm().length + " bytes at " + a.sampleRate() + " Hz = " + String.format("%.1f", seconds) + " s");
            assertTrue(seconds > 2.0 && seconds < 15.0, "a spoken line of plausible length, got " + seconds + " s");
        }
    }
}
