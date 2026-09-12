package com.ayodehi.thehush.voice;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiSpeechVoiceTest {
    private static OpenAiSpeechVoice voice(OpenAiSpeechVoice.CueStyle cues, String extra) {
        return new OpenAiSpeechVoice(new OpenAiSpeechVoice.Settings("http://127.0.0.1:8880", "", "kokoro", "bm_george",
                OpenAiSpeechVoice.Format.WAV, 24000, cues, 1.0, extra, 20), Runnable::run);
    }

    @Test
    void endpointAndCueStripping() {
        assertEquals("http://127.0.0.1:8880/v1/audio/speech", voice(OpenAiSpeechVoice.CueStyle.NONE, "").endpoint());
        JsonObject body = voice(OpenAiSpeechVoice.CueStyle.NONE, "").buildBody("[low] Walls, Ben... *points* now.", Speech.Manner.SOFT);
        assertEquals("kokoro", body.get("model").getAsString());
        assertEquals("bm_george", body.get("voice").getAsString());
        assertEquals("wav", body.get("response_format").getAsString());
        assertEquals("Walls, Ben... now.", body.get("input").getAsString());
        assertFalse(body.has("instructions"));
        assertEquals(0.94, body.get("speed").getAsDouble(), 1e-9, "no cue channel: a soft line is paced slower");
    }

    @Test
    void instructionsCarryTheCues() {
        JsonObject body = voice(OpenAiSpeechVoice.CueStyle.INSTRUCTIONS, "").buildBody("[afraid, low] Did you hear that?", Speech.Manner.SHARP);
        assertEquals("Did you hear that?", body.get("input").getAsString());
        String instr = body.get("instructions").getAsString();
        assertTrue(instr.contains("afraid, low"));
        assertTrue(instr.contains("Quick and urgent"));
        assertFalse(body.has("speed"));
    }

    @Test
    void orpheusTagsFromCues() {
        assertEquals("<sigh> ", OpenAiSpeechVoice.orpheusTags(List.of("sighs")));
        assertEquals("<laugh> <gasp> ", OpenAiSpeechVoice.orpheusTags(List.of("laughs", "gasps", "low")));
        assertEquals("", OpenAiSpeechVoice.orpheusTags(List.of("whispers")));
        JsonObject body = voice(OpenAiSpeechVoice.CueStyle.ORPHEUS, "").buildBody("[sighs] There.", Speech.Manner.SOFT);
        assertEquals("<sigh> There.", body.get("input").getAsString());
    }

    @Test
    void extraFieldsAreMerged() {
        JsonObject body = voice(OpenAiSpeechVoice.CueStyle.NONE, "{\"exaggeration\": 0.6, \"cfg_weight\": 0.4}").buildBody("Hm.", Speech.Manner.PLAIN);
        assertEquals(0.6, body.get("exaggeration").getAsDouble(), 1e-9);
        assertEquals(0.4, body.get("cfg_weight").getAsDouble(), 1e-9);
    }

    @Test
    void classifiesServerErrors() {
        assertEquals(VoiceException.Kind.AUTH, OpenAiSpeechVoice.classify(401, "{\"error\":\"invalid api key\"}").kind());
        assertEquals(VoiceException.Kind.RATE_LIMIT, OpenAiSpeechVoice.classify(429, "slow down").kind());
        assertEquals(VoiceException.Kind.RATE_LIMIT, OpenAiSpeechVoice.classify(429, "insufficient_quota").kind(), "429 is a rate limit first");
        assertEquals(VoiceException.Kind.QUOTA, OpenAiSpeechVoice.classify(400, "You exceeded your current quota").kind());
        assertEquals(VoiceException.Kind.VOICE, OpenAiSpeechVoice.classify(400, "Voice 'xx' not found").kind());
        assertEquals(VoiceException.Kind.OTHER, OpenAiSpeechVoice.classify(500, "boom").kind());
    }

    @Test
    void wavStereo16BitBecomesMono() {
        byte[] wav = wav(2, 22050, 16, new int[][] {{1000, 3000}, {-2000, -2000}, {32767, 32767}});
        VoiceProvider.Audio a = Wav.decode(wav);
        assertEquals(22050, a.sampleRate());
        assertEquals(6, a.pcm().length);
        ByteBuffer b = ByteBuffer.wrap(a.pcm()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2000, b.getShort());
        assertEquals(-2000, b.getShort());
        assertEquals(32767, b.getShort());
    }

    @Test
    void wavFloatAndEightBit() {
        byte[] f = wavFloat(24000, new float[] {0.5f, -1.0f});
        VoiceProvider.Audio a = Wav.decode(f);
        ByteBuffer b = ByteBuffer.wrap(a.pcm()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(16384, b.getShort());
        assertEquals(-32768, b.getShort());
        byte[] eight = wav(1, 8000, 8, new int[][] {{128}, {255}, {0}});
        ByteBuffer e = ByteBuffer.wrap(Wav.decode(eight).pcm()).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, e.getShort());
        assertTrue(e.getShort() > 32000);
        assertTrue(e.getShort() < -32000);
        assertFalse(Wav.isWav(new byte[] {1, 2, 3}));
    }

    private static byte[] wav(int channels, int rate, int bits, int[][] frames) {
        int bps = bits / 8;
        int dataLen = frames.length * channels * bps;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels).putInt(rate)
         .putInt(rate * channels * bps).putShort((short) (channels * bps)).putShort((short) bits);
        b.put("data".getBytes()).putInt(dataLen);
        for (int[] frame : frames) for (int s : frame) {
            if (bits == 16) b.putShort((short) s); else b.put((byte) s);
        }
        return b.array();
    }

    private static byte[] wavFloat(int rate, float[] samples) {
        int dataLen = samples.length * 4;
        ByteBuffer b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(36 + dataLen).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 3).putShort((short) 1).putInt(rate)
         .putInt(rate * 4).putShort((short) 4).putShort((short) 32);
        b.put("data".getBytes()).putInt(dataLen);
        for (float s : samples) b.putFloat(s);
        return b.array();
    }
}
