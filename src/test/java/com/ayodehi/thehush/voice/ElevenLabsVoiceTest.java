package com.ayodehi.thehush.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElevenLabsVoiceTest {
    @Test
    void quotaExceededIsQuota() {
        VoiceException e = ElevenLabsVoice.classify(401, "{\"detail\":{\"status\":\"quota_exceeded\","
                + "\"message\":\"This request exceeds your quota of 10000. You have 12 credits remaining, while 240 credits are required for this request.\"}}");
        assertEquals(VoiceException.Kind.QUOTA, e.kind());
        assertTrue(e.getMessage().contains("quota_exceeded"));
        assertTrue(e.getMessage().contains("12 credits remaining"));
    }

    @Test
    void badKeyIsAuth() {
        assertEquals(VoiceException.Kind.AUTH, ElevenLabsVoice.classify(401,
                "{\"detail\":{\"status\":\"invalid_api_key\",\"message\":\"Invalid API key\"}}").kind());
        assertEquals(VoiceException.Kind.AUTH, ElevenLabsVoice.classify(401,
                "{\"detail\":{\"status\":\"detected_unusual_activity\",\"message\":\"Unusual activity detected. Free Tier usage disabled.\"}}").kind());
        assertEquals(VoiceException.Kind.AUTH, ElevenLabsVoice.classify(401, "Unauthorized").kind());
    }

    @Test
    void busyIsRateLimit() {
        assertEquals(VoiceException.Kind.RATE_LIMIT, ElevenLabsVoice.classify(429,
                "{\"detail\":{\"status\":\"too_many_concurrent_requests\",\"message\":\"...\"}}").kind());
        assertEquals(VoiceException.Kind.RATE_LIMIT, ElevenLabsVoice.classify(429, "<html>rate limited</html>").kind());
    }

    @Test
    void missingVoiceIsVoice() {
        assertEquals(VoiceException.Kind.VOICE, ElevenLabsVoice.classify(404,
                "{\"detail\":{\"status\":\"voice_not_found\",\"message\":\"A voice with voice_id x was not found.\"}}").kind());
    }

    @Test
    void serverErrorsAreOther() {
        assertEquals(VoiceException.Kind.OTHER, ElevenLabsVoice.classify(500, "Internal Server Error").kind());
        assertEquals(VoiceException.Kind.OTHER, ElevenLabsVoice.classify(502, "{\"detail\":\"upstream timeout\"}").kind());
    }
}
