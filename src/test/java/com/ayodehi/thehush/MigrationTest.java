package com.ayodehi.thehush;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationTest {
    private static final String OLD = """
            [llm]
            \tprovider = "ollama"
            \t#Anthropic API key.
            \tapiKey = "sk-ant-secret"
            \tapiKeyEnvVar = "ANTHROPIC_API_KEY"
            \tbaseUrl = "https://api.anthropic.com"
            \tmodel = "claude-sonnet-5"
            \teffort = "low"
            \tmaxTokens = 4096
            \trequestTimeoutSeconds = 90
            \tinputPricePerMTok = 0.0
            \tollamaModel = "llama3.1"
            \tollamaUrl = "http://127.0.0.1:11434"
            \tollamaThink = false
            [conversation]
            \tconversationRadius = 8.0
            """;

    @Test
    void movesKeysIntoBackendSections() {
        String out = Migration.nestLlmSections(OLD);
        assertTrue(out.contains("[llm.anthropic]"));
        assertTrue(out.contains("[llm.ollama]"));
        int llm = out.indexOf("[llm]"), anth = out.indexOf("[llm.anthropic]"), oll = out.indexOf("[llm.ollama]"), conv = out.indexOf("[conversation]");
        assertTrue(llm < anth && anth < oll && oll < conv, "sections in order");
        // shared keys stay under [llm]
        String llmBody = out.substring(llm, anth);
        assertTrue(llmBody.contains("provider = \"ollama\""));
        assertTrue(llmBody.contains("maxTokens = 4096"));
        assertTrue(llmBody.contains("requestTimeoutSeconds = 90"));
        assertFalse(llmBody.contains("apiKey"));
        // the key and its comment travel together
        String anthBody = out.substring(anth, oll);
        assertTrue(anthBody.contains("#Anthropic API key.\n\tapiKey = \"sk-ant-secret\""));
        assertTrue(anthBody.contains("model = \"claude-sonnet-5\""));
        assertTrue(anthBody.contains("inputPricePerMTok = 0.0"));
        // ollama keys are renamed
        String ollBody = out.substring(oll, conv);
        assertTrue(ollBody.contains("\tmodel = \"llama3.1\""));
        assertTrue(ollBody.contains("\turl = \"http://127.0.0.1:11434\""));
        assertTrue(ollBody.contains("\tthink = false"));
        assertFalse(out.contains("ollamaModel"));
        // the rest of the file is untouched
        assertTrue(out.endsWith("[conversation]\n\tconversationRadius = 8.0\n"));
    }

    @Test
    void leavesMigratedOrForeignFilesAlone() {
        String done = Migration.nestLlmSections(OLD);
        assertSame(done, Migration.nestLlmSections(done));
        String other = "[voice]\n\tprovider = \"none\"\n";
        assertSame(other, Migration.nestLlmSections(other));
        String bare = "[llm]\n\tprovider = \"anthropic\"\n\tmaxTokens = 4096\n[conversation]\n";
        assertEquals(bare, Migration.nestLlmSections(bare));
    }

    @Test
    void movesVoiceKeysUnderElevenlabs() {
        String old = "[voice]\n\tprovider = \"elevenlabs\"\n\tapiKey = \"xi-secret\"\n\tvoiceId = \"JBFq\"\n\tmodel = \"eleven_v3\"\n"
                + "\tstability = 0.45\n\tvolume = 1.0\n\tlineGapSeconds = 0.7\n\ttimeoutSeconds = 20\n\tpricePerThousandChars = 0.3\n[debug]\n\tbridge = false\n";
        String out = Migration.nestVoiceSections(old);
        int voice = out.indexOf("[voice]"), el = out.indexOf("[voice.elevenlabs]"), dbg = out.indexOf("[debug]");
        assertTrue(voice < el && el < dbg);
        String shared = out.substring(voice, el);
        assertTrue(shared.contains("provider = \"elevenlabs\"") && shared.contains("volume = 1.0") && shared.contains("lineGapSeconds") && shared.contains("timeoutSeconds"));
        assertFalse(shared.contains("apiKey"));
        String el11 = out.substring(el, dbg);
        assertTrue(el11.contains("apiKey = \"xi-secret\"") && el11.contains("voiceId") && el11.contains("model = \"eleven_v3\"") && el11.contains("pricePerThousandChars"));
        assertSame(out, Migration.nestVoiceSections(out));
    }
}
