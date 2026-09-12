package com.ayodehi.thehush;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

/** config/hush-common.toml. Values are read when the server starts. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("llm");
    }

    /** Where he thinks. Shown as a choice in the config screen; the file accepts the names in any case. */
    public enum LlmBackend implements TranslatableEnum {
        ANTHROPIC, OLLAMA, OPENAI;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("thehush.configuration.llm.provider." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** How he is heard. */
    public enum VoiceBackend implements TranslatableEnum {
        NONE, ELEVENLABS, OPENAI;

        @Override
        public Component getTranslatedName() {
            return Component.translatable("thehush.configuration.voice.provider." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    public static final ModConfigSpec.EnumValue<LlmBackend> LLM_PROVIDER = BUILDER
            .comment("Where he thinks: ANTHROPIC (Claude over the API, needs a key), OLLAMA (a local model, free, no key), or",
                     "OPENAI (any OpenAI-compatible server: LiteLLM, OpenAI, OpenRouter, LM Studio, vLLM, Groq).",
                     "Each backend's own settings are in its section below; only the chosen one is used.")
            .translation("thehush.configuration.llm.provider")
            .defineEnum("provider", LlmBackend.ANTHROPIC);

    public static final ModConfigSpec.IntValue MAX_TOKENS = BUILDER
            .comment("Upper bound on output tokens per reply (includes the model's internal reasoning).")
            .defineInRange("maxTokens", 4096, 256, 128000);

    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS = BUILDER
            .comment("Seconds to wait for a single model response.")
            .defineInRange("requestTimeoutSeconds", 90, 10, 600);

    // ---- llm.anthropic ----

    static {
        BUILDER.comment("Claude over the Anthropic API. Used when provider = ANTHROPIC.")
               .translation("thehush.configuration.llm.anthropic")
               .push("anthropic");
    }

    public static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .comment("Anthropic API key. Leave empty to read it from the environment variable named in apiKeyEnvVar instead.")
            .translation("thehush.configuration.llm.anthropic.apiKey")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> API_KEY_ENV_VAR = BUILDER
            .comment("Environment variable to read the API key from when apiKey is empty.")
            .translation("thehush.configuration.llm.anthropic.apiKeyEnvVar")
            .define("apiKeyEnvVar", "ANTHROPIC_API_KEY");

    public static final ModConfigSpec.ConfigValue<String> BASE_URL = BUILDER
            .comment("Claude API base URL.")
            .translation("thehush.configuration.llm.anthropic.baseUrl")
            .define("baseUrl", "https://api.anthropic.com");

    public static final ModConfigSpec.ConfigValue<String> MODEL = BUILDER
            .comment("Claude model id (claude-sonnet-5 is quick and cheap; claude-opus-5 deeper).")
            .translation("thehush.configuration.llm.anthropic.model")
            .define("model", "claude-sonnet-5");

    public static final ModConfigSpec.ConfigValue<String> EFFORT = BUILDER
            .comment("Reasoning effort: low, medium, high, xhigh or max. Low keeps replies fast for chat.")
            .translation("thehush.configuration.llm.anthropic.effort")
            .define("effort", "low");

    public static final ModConfigSpec.DoubleValue INPUT_PRICE = BUILDER
            .comment("USD per million input tokens, for the cost estimate in /hush usage. 0 uses the built-in",
                     "rate for the model's tier (Opus 15, Sonnet 3, Haiku 1); check it against current pricing.")
            .translation("thehush.configuration.llm.anthropic.inputPricePerMTok")
            .defineInRange("inputPricePerMTok", 0.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue OUTPUT_PRICE = BUILDER
            .comment("USD per million output tokens. 0 uses the built-in rate (Opus 75, Sonnet 15, Haiku 5).")
            .translation("thehush.configuration.llm.anthropic.outputPricePerMTok")
            .defineInRange("outputPricePerMTok", 0.0, 0.0, 1000.0);

    // ---- llm.ollama ----

    static {
        BUILDER.pop();
        BUILDER.comment("A local model through Ollama. Used when provider = OLLAMA. Free; quality depends on the model.")
               .translation("thehush.configuration.llm.ollama")
               .push("ollama");
    }

    public static final ModConfigSpec.ConfigValue<String> OLLAMA_URL = BUILDER
            .comment("Ollama server.")
            .translation("thehush.configuration.llm.ollama.url")
            .define("url", "http://127.0.0.1:11434");

    public static final ModConfigSpec.ConfigValue<String> OLLAMA_MODEL = BUILDER
            .comment("Model tag. It must be pulled already (ollama pull <tag>) and should support tool calling",
                     "(llama3.1, qwen3, mistral-nemo, ...) or he can talk but not act.")
            .translation("thehush.configuration.llm.ollama.model")
            .define("model", "llama3.1");

    public static final ModConfigSpec.IntValue OLLAMA_CONTEXT = BUILDER
            .comment("Context window (tokens) asked of the model. His prompt is about 10k tokens before any",
                     "conversation, so keep this at 16k or more; larger windows use more memory.")
            .translation("thehush.configuration.llm.ollama.context")
            .defineInRange("context", 16384, 4096, 262144);

    public static final ModConfigSpec.BooleanValue OLLAMA_THINK = BUILDER
            .comment("Ask a reasoning model (qwen3, deepseek-r1, gpt-oss) to think before answering. Slower; off for chat.")
            .translation("thehush.configuration.llm.ollama.think")
            .define("think", false);

    // ---- llm.openai ----

    static {
        BUILDER.pop(); // ollama
        BUILDER.comment("Any server speaking the OpenAI chat-completions API: a LiteLLM gateway, OpenAI itself, OpenRouter,",
                        "LM Studio, vLLM, Groq. Used when provider = OPENAI.")
               .translation("thehush.configuration.llm.openai")
               .push("openai");
    }

    public static final ModConfigSpec.ConfigValue<String> OPENAI_URL = BUILDER
            .comment("Base URL. LiteLLM's default is http://127.0.0.1:4000; OpenAI is https://api.openai.com/v1. With or without /v1.")
            .translation("thehush.configuration.llm.openai.url")
            .define("url", "http://127.0.0.1:4000");

    public static final ModConfigSpec.ConfigValue<String> OPENAI_API_KEY = BUILDER
            .comment("API key sent as a bearer token (a LiteLLM virtual key, an OpenAI key, ...). Leave empty to read the",
                     "environment variable named in apiKeyEnvVar, or when the server needs none.")
            .translation("thehush.configuration.llm.openai.apiKey")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> OPENAI_API_KEY_ENV_VAR = BUILDER
            .comment("Environment variable to read the key from when apiKey is empty.")
            .translation("thehush.configuration.llm.openai.apiKeyEnvVar")
            .define("apiKeyEnvVar", "OPENAI_API_KEY");

    public static final ModConfigSpec.ConfigValue<String> OPENAI_MODEL = BUILDER
            .comment("Model name as the server knows it (a LiteLLM alias, gpt-4o, ...). Tool calling is needed for him to act.")
            .translation("thehush.configuration.llm.openai.model")
            .define("model", "gpt-4o-mini");

    public static final ModConfigSpec.ConfigValue<String> OPENAI_REASONING_EFFORT = BUILDER
            .comment("reasoning_effort for models that take it (low, medium, high); empty sends nothing.")
            .translation("thehush.configuration.llm.openai.reasoningEffort")
            .define("reasoningEffort", "");

    public static final ModConfigSpec.DoubleValue OPENAI_INPUT_PRICE = BUILDER
            .comment("USD per million input tokens for the cost estimate; 0 means unknown and the cost shows as zero.")
            .translation("thehush.configuration.llm.openai.inputPricePerMTok")
            .defineInRange("inputPricePerMTok", 0.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue OPENAI_OUTPUT_PRICE = BUILDER
            .comment("USD per million output tokens; 0 means unknown.")
            .translation("thehush.configuration.llm.openai.outputPricePerMTok")
            .defineInRange("outputPricePerMTok", 0.0, 0.0, 1000.0);

    static {
        BUILDER.pop(); // openai
    }

    static {
        BUILDER.pop();
        BUILDER.push("conversation");
    }

    public static final ModConfigSpec.DoubleValue CONVERSATION_RADIUS = BUILDER
            .comment("Distance in blocks within which a player's chat is routed to the villager they are talking to.")
            .defineInRange("conversationRadius", 8.0, 2.0, 64.0);

    public static final ModConfigSpec.BooleanValue AUTO_LISTEN = BUILDER
            .comment("If true, villagers within conversationRadius hear you without a right-click: the mod picks who you",
                     "are addressing (by name, then whoever you were already talking to, then who you are looking at,",
                     "then your travelling companion, then the nearest). Start a message with ! to talk past them.")
            .define("autoListen", true);

    public static final ModConfigSpec.IntValue MAX_HISTORY_MESSAGES = BUILDER
            .comment("How many conversation messages a villager remembers before the oldest are dropped.")
            .defineInRange("maxHistoryMessages", 40, 4, 400);

    public static final ModConfigSpec.IntValue MAX_TOOL_ROUNDS = BUILDER
            .comment("Maximum tool-call rounds per reply before the villager must answer.")
            .defineInRange("maxToolRounds", 6, 0, 20);

    public static final ModConfigSpec.BooleanValue AMBIENT_REMARKS = BUILDER
            .comment("Let villagers comment unprompted on weather changes, nightfall, dawn, nearby monsters and your injuries",
                     "when you are within earshot. Each remark is one API call; cooldowns keep them rare.")
            .define("ambientRemarks", true);

    public static final ModConfigSpec.IntValue AMBIENT_COOLDOWN_SECONDS = BUILDER
            .comment("Minimum real seconds between unprompted remarks from one villager.")
            .defineInRange("ambientCooldownSeconds", 120, 15, 3600);

    public static final ModConfigSpec.IntValue RETURN_DELAY_SECONDS = BUILDER
            .comment("Real seconds after a returning villager dies before he walks back up to the player, wherever they are.",
                     "He waits a little longer if a Warden is near them.")
            .defineInRange("returnDelaySeconds", 90, 0, 3600);

    public static final ModConfigSpec.ConfigValue<String> DEFAULT_PERSONA = BUILDER
            .comment("Persona id (file name under config/thehush/personas) used when none is specified.")
            .define("defaultPersona", "traveller");

    static {
        BUILDER.pop();
        BUILDER.push("voice");
    }

    public static final ModConfigSpec.EnumValue<VoiceBackend> VOICE_PROVIDER = BUILDER
            .comment("Text to speech for what villagers say: NONE, ELEVENLABS, or OPENAI (any server speaking the OpenAI speech",
                     "API: OpenAI itself, or local Kokoro, Orpheus, Chatterbox, openedai-speech, LocalAI, LiteLLM). Lines are",
                     "spoken aloud from where the villager stands, quieter in sculk country; the chat text still appears.")
            .translation("thehush.configuration.voice.provider")
            .defineEnum("provider", VoiceBackend.NONE);

    public static final ModConfigSpec.DoubleValue VOICE_VOLUME = BUILDER
            .comment("Loudness of spoken lines, 0..2, on top of the game's Voice/Speech slider.")
            .defineInRange("volume", 1.0, 0.0, 2.0);

    public static final ModConfigSpec.DoubleValue VOICE_LINE_GAP_SECONDS = BUILDER
            .comment("Silence between one spoken line ending and the next beginning. Lines never overlap; this is the breath between them.")
            .defineInRange("lineGapSeconds", 0.7, 0.0, 10.0);

    public static final ModConfigSpec.IntValue VOICE_TIMEOUT_SECONDS = BUILDER
            .comment("Seconds to wait for the audio before the text goes out without it.")
            .defineInRange("timeoutSeconds", 20, 3, 120);

    // ---- voice.elevenlabs ----

    static {
        BUILDER.comment("ElevenLabs. Used when provider = ELEVENLABS.")
               .translation("thehush.configuration.voice.elevenlabs")
               .push("elevenlabs");
    }

    public static final ModConfigSpec.ConfigValue<String> VOICE_API_KEY = BUILDER
            .comment("ElevenLabs API key. Leave empty to read it from the environment variable named in apiKeyEnvVar.")
            .translation("thehush.configuration.voice.elevenlabs.apiKey")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> VOICE_API_KEY_ENV_VAR = BUILDER
            .comment("Environment variable to read the ElevenLabs key from when apiKey is empty.")
            .translation("thehush.configuration.voice.elevenlabs.apiKeyEnvVar")
            .define("apiKeyEnvVar", "ELEVENLABS_API_KEY");

    public static final ModConfigSpec.ConfigValue<String> VOICE_ID = BUILDER
            .comment("ElevenLabs voice id (Voices > the voice > ID). The default is the stock voice 'George'.")
            .translation("thehush.configuration.voice.elevenlabs.voiceId")
            .define("voiceId", "JBFqnCBsd6RMkjVDRZzb");

    public static final ModConfigSpec.ConfigValue<String> VOICE_MODEL = BUILDER
            .comment("ElevenLabs model: eleven_v3_conversational (expressive, fast, reads [cues]), eleven_v3 (most expressive, slower),",
                    "eleven_multilingual_v2 (lifelike, no cues), eleven_flash_v2_5 (fastest, half the credits).")
            .translation("thehush.configuration.voice.elevenlabs.model")
            .define("model", "eleven_multilingual_v2");

    public static final ModConfigSpec.DoubleValue VOICE_STABILITY = BUILDER
            .comment("Voice stability 0..1: lower is more expressive, higher is steadier.")
            .translation("thehush.configuration.voice.elevenlabs.stability")
            .defineInRange("stability", 0.45, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue VOICE_SIMILARITY = BUILDER
            .comment("How closely the voice sticks to the original, 0..1.")
            .translation("thehush.configuration.voice.elevenlabs.similarity")
            .defineInRange("similarity", 0.8, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue VOICE_PRICE_PER_THOUSAND = BUILDER
            .comment("USD per thousand characters, for the cost estimate in /hush usage (ElevenLabs Creator is about 0.30).")
            .translation("thehush.configuration.voice.elevenlabs.pricePerThousandChars")
            .defineInRange("pricePerThousandChars", 0.30, 0.0, 100.0);

    // ---- voice.openai ----

    static {
        BUILDER.pop();
        BUILDER.comment("Any server speaking the OpenAI speech API (POST /v1/audio/speech). Used when provider = OPENAI.",
                        "Local: Kokoro-FastAPI (port 8880, model kokoro, voices like bm_george), Orpheus-FastAPI (model orpheus,",
                        "voices tara/leo/dan; cues = ORPHEUS), chatterbox-tts-api (extra = {\"exaggeration\": 0.6}). Cloud: OpenAI",
                        "(https://api.openai.com/v1, model gpt-4o-mini-tts, cues = INSTRUCTIONS) or a LiteLLM gateway.")
               .translation("thehush.configuration.voice.openai")
               .push("openai");
    }

    public static final ModConfigSpec.ConfigValue<String> SPEECH_URL = BUILDER
            .comment("Base URL, with or without /v1.")
            .translation("thehush.configuration.voice.openai.url")
            .define("url", "http://127.0.0.1:8880");

    public static final ModConfigSpec.ConfigValue<String> SPEECH_API_KEY = BUILDER
            .comment("API key sent as a bearer token; empty for a local server that needs none, or to read the environment variable below.")
            .translation("thehush.configuration.voice.openai.apiKey")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> SPEECH_API_KEY_ENV_VAR = BUILDER
            .comment("Environment variable to read the key from when apiKey is empty.")
            .translation("thehush.configuration.voice.openai.apiKeyEnvVar")
            .define("apiKeyEnvVar", "OPENAI_API_KEY");

    public static final ModConfigSpec.ConfigValue<String> SPEECH_MODEL = BUILDER
            .comment("Model name as the server knows it: kokoro, orpheus, chatterbox, tts-1, gpt-4o-mini-tts, ...")
            .translation("thehush.configuration.voice.openai.model")
            .define("model", "kokoro");

    public static final ModConfigSpec.ConfigValue<String> SPEECH_VOICE = BUILDER
            .comment("Voice name as the server knows it (Kokoro: bm_george, am_michael, af_heart; Orpheus: tara, leo, dan; OpenAI: onyx, ash, ...).")
            .translation("thehush.configuration.voice.openai.voice")
            .define("voice", "bm_george");

    public static final ModConfigSpec.EnumValue<com.ayodehi.thehush.voice.OpenAiSpeechVoice.Format> SPEECH_FORMAT = BUILDER
            .comment("Audio format asked for: WAV (every server) or PCM (raw 16-bit at pcmSampleRate; fewer bytes, not every server).")
            .translation("thehush.configuration.voice.openai.format")
            .defineEnum("format", com.ayodehi.thehush.voice.OpenAiSpeechVoice.Format.WAV);

    public static final ModConfigSpec.IntValue SPEECH_PCM_RATE = BUILDER
            .comment("Sample rate of raw PCM answers (OpenAI and Kokoro: 24000).")
            .translation("thehush.configuration.voice.openai.pcmSampleRate")
            .defineInRange("pcmSampleRate", 24000, 8000, 48000);

    public static final ModConfigSpec.EnumValue<com.ayodehi.thehush.voice.OpenAiSpeechVoice.CueStyle> SPEECH_CUES = BUILDER
            .comment("How his bracketed delivery cues reach the engine: NONE (stripped; Kokoro, Chatterbox, tts-1),",
                     "INSTRUCTIONS (an instructions field; OpenAI gpt-4o-mini-tts), ORPHEUS (inline <sigh>-style tags).")
            .translation("thehush.configuration.voice.openai.cues")
            .defineEnum("cues", com.ayodehi.thehush.voice.OpenAiSpeechVoice.CueStyle.NONE);

    public static final ModConfigSpec.DoubleValue SPEECH_SPEED = BUILDER
            .comment("Speaking pace, 1.0 is the server's normal.")
            .translation("thehush.configuration.voice.openai.speed")
            .defineInRange("speed", 1.0, 0.5, 2.0);

    public static final ModConfigSpec.ConfigValue<String> SPEECH_EXTRA = BUILDER
            .comment("Extra JSON fields merged into every request, for server-specific knobs: Chatterbox {\"exaggeration\": 0.6, \"cfg_weight\": 0.4},",
                     "Kokoro {\"lang_code\": \"b\"}. Empty for none.")
            .translation("thehush.configuration.voice.openai.extra")
            .define("extra", "");

    public static final ModConfigSpec.DoubleValue SPEECH_PRICE_PER_THOUSAND = BUILDER
            .comment("USD per thousand characters for the cost estimate; 0 for a local server.")
            .translation("thehush.configuration.voice.openai.pricePerThousandChars")
            .defineInRange("pricePerThousandChars", 0.0, 0.0, 100.0);

    static {
        BUILDER.pop(); // openai
    }

    static {
        BUILDER.pop();
        BUILDER.push("campaign");
    }

    public static final ModConfigSpec.BooleanValue CAMPAIGN_ENABLED = BUILDER
            .comment("Run the story campaign for personas that name one (the Traveller plays 'The Hush').")
            .define("enabled", true);

    public static final ModConfigSpec.BooleanValue AUTO_ARRIVAL = BUILDER
            .comment("In a fresh world, the first join brings a thunderstorm; when it passes the campaign persona is",
                     "waiting at the nearest village well (or near your spawn) and a narrator line says where.")
            .define("autoArrival", true);

    public static final ModConfigSpec.ConfigValue<String> ARRIVAL_PERSONA = BUILDER
            .comment("Persona id placed by the opening storm.")
            .define("arrivalPersona", "traveller");

    public static final ModConfigSpec.IntValue GUIDE_MINUTES = BUILDER
            .comment("Minutes of silence, with the chosen player beside him, before the campaign villager is prompted to",
                     "say a line about where you are and what comes next. 0 disables.")
            .defineInRange("guideMinutes", 4, 0, 120);

    public static final ModConfigSpec.IntValue BEAT_GAP_SECONDS = BUILDER
            .comment("Minimum seconds between scripted story lines when several triggers fire at once (a teleport into",
                     "a city, say). 0 delivers them as fast as he can speak; handy while testing with /tp.")
            .defineInRange("beatGapSeconds", 20, 0, 600);

    public static final ModConfigSpec.BooleanValue WICKS = BUILDER
            .comment("Let Wicks (the things that eat torchlight) appear in caves you have lit, below y 20.")
            .define("wicks", true);

    public static final ModConfigSpec.IntValue WICK_RARITY = BUILDER
            .comment("One in this many five-second checks, per lit cave, brings a Wick. Higher is rarer.")
            .defineInRange("wickRarity", 24, 1, 1000);

    public static final ModConfigSpec.BooleanValue HUNTERS = BUILDER
            .comment("Send Echoes (blind hunters that stalk the chosen player, then strike) once the player has iron armor.")
            .define("hunters", true);

    public static final ModConfigSpec.IntValue HUNTER_MINUTES = BUILDER
            .comment("Minutes, give or take a third, between hunts: from the iron armor to the first Echo, and from the",
                     "end of one hunt to the next. 0 disables them.")
            .defineInRange("hunterMinutes", 25, 0, 600);

    public static final ModConfigSpec.BooleanValue UNSAID = BUILDER
            .comment("Let the Unsaid (grey ghosts in the Traveller's shape) rise in the Nether's soul sand valleys during the road.")
            .define("unsaid", true);

    public static final ModConfigSpec.IntValue UNSAID_RARITY = BUILDER
            .comment("One in this many five-second checks, per player in a soul sand valley, brings one of the Unsaid. Higher is rarer.")
            .defineInRange("unsaidRarity", 20, 1, 1000);

    public static final ModConfigSpec.BooleanValue UNSEEN = BUILDER
            .comment("Things heard and not seen: footsteps behind you, a heartbeat under the sculk, a breath of total silence,",
                    "a knock at the door, something far below, the bell at night. Only for players alone in the dark.")
            .define("unseen", true);

    public static final ModConfigSpec.IntValue UNSEEN_MINUTES = BUILDER
            .comment("About this many minutes between unseen sounds for a player who is alone in the dark (shorter as the road goes on).")
            .defineInRange("unseenMinutes", 5, 1, 120);

    public static final ModConfigSpec.BooleanValue CAMPAIGN_DEBUG = BUILDER
            .comment("Log every campaign flag and stage change.")
            .define("debugLog", false);

    static {
        BUILDER.pop();
        BUILDER.push("debug");
    }

    public static final ModConfigSpec.BooleanValue BRIDGE = BUILDER
            .comment("Run the debugging bridge: a small HTTP server on 127.0.0.1 that the developer tooling (tools/mcp) uses to",
                     "inspect and drive the live world. Loopback only; still, leave it off outside development. The dev run",
                     "configurations turn it on with -Dthehush.bridge=true regardless of this setting.")
            .define("bridge", false);

    public static final ModConfigSpec.IntValue BRIDGE_PORT = BUILDER
            .comment("Port for the debugging bridge (-Dthehush.bridgePort overrides).")
            .defineInRange("bridgePort", 25599, 1024, 65535);

    public static final ModConfigSpec.ConfigValue<String> BRIDGE_TOKEN = BUILDER
            .comment("If set, bridge requests must carry 'Authorization: Bearer <token>'.")
            .define("bridgeToken", "");

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Campaign started by command before anyone has talked to a campaign persona. */
    public static final String DEFAULT_CAMPAIGN_FALLBACK = "the_hush";

    private Config() {}
}
