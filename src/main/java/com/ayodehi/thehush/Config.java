package com.ayodehi.thehush;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/hush-common.toml. Values are read when the server starts. */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.push("llm");
    }

    public static final ModConfigSpec.ConfigValue<String> API_KEY = BUILDER
            .comment("Anthropic API key. Leave empty to read it from the environment variable named in apiKeyEnvVar instead.")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> API_KEY_ENV_VAR = BUILDER
            .comment("Environment variable to read the API key from when apiKey is empty.")
            .define("apiKeyEnvVar", "ANTHROPIC_API_KEY");

    public static final ModConfigSpec.ConfigValue<String> BASE_URL = BUILDER
            .comment("Claude API base URL.")
            .define("baseUrl", "https://api.anthropic.com");

    public static final ModConfigSpec.ConfigValue<String> MODEL = BUILDER
            .comment("Model id used for villager conversations.")
            .define("model", "claude-opus-5");

    public static final ModConfigSpec.ConfigValue<String> EFFORT = BUILDER
            .comment("Reasoning effort: low, medium, high, xhigh or max. Low keeps replies fast for chat.")
            .define("effort", "low");

    public static final ModConfigSpec.IntValue MAX_TOKENS = BUILDER
            .comment("Upper bound on output tokens per reply (includes the model's internal reasoning).")
            .defineInRange("maxTokens", 4096, 256, 128000);

    public static final ModConfigSpec.DoubleValue INPUT_PRICE = BUILDER
            .comment("USD per million input tokens, for the cost estimate in /hush usage. 0 uses the built-in",
                     "rate for the model's tier (Opus 15, Sonnet 3, Haiku 1); check it against current pricing.")
            .defineInRange("inputPricePerMTok", 0.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue OUTPUT_PRICE = BUILDER
            .comment("USD per million output tokens. 0 uses the built-in rate (Opus 75, Sonnet 15, Haiku 5).")
            .defineInRange("outputPricePerMTok", 0.0, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue REQUEST_TIMEOUT_SECONDS = BUILDER
            .comment("Seconds to wait for a single API response.")
            .defineInRange("requestTimeoutSeconds", 90, 10, 600);

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

    public static final ModConfigSpec.ConfigValue<String> VOICE_PROVIDER = BUILDER
            .comment("Text to speech for what villagers say: 'none' or 'elevenlabs'. Lines are spoken aloud from where",
                     "the villager stands, quieter in sculk country; the chat text still appears.")
            .define("provider", "none");

    public static final ModConfigSpec.ConfigValue<String> VOICE_API_KEY = BUILDER
            .comment("ElevenLabs API key. Leave empty to read it from the environment variable named in apiKeyEnvVar.")
            .translation("thehush.configuration.voice.apiKey")
            .define("apiKey", "");

    public static final ModConfigSpec.ConfigValue<String> VOICE_API_KEY_ENV_VAR = BUILDER
            .comment("Environment variable to read the ElevenLabs key from when apiKey is empty.")
            .translation("thehush.configuration.voice.apiKeyEnvVar")
            .define("apiKeyEnvVar", "ELEVENLABS_API_KEY");

    public static final ModConfigSpec.ConfigValue<String> VOICE_ID = BUILDER
            .comment("ElevenLabs voice id (Voices > the voice > ID). The default is the stock voice 'George'.")
            .define("voiceId", "JBFqnCBsd6RMkjVDRZzb");

    public static final ModConfigSpec.ConfigValue<String> VOICE_MODEL = BUILDER
            .comment("ElevenLabs model: eleven_v3_conversational (expressive, fast, reads [cues]), eleven_v3 (most expressive, slower),",
                    "eleven_multilingual_v2 (lifelike, no cues), eleven_flash_v2_5 (fastest, half the credits).")
            .translation("thehush.configuration.voice.model")
            .define("model", "eleven_multilingual_v2");

    public static final ModConfigSpec.DoubleValue VOICE_STABILITY = BUILDER
            .comment("Voice stability 0..1: lower is more expressive, higher is steadier.")
            .defineInRange("stability", 0.45, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue VOICE_SIMILARITY = BUILDER
            .comment("Similarity boost 0..1.")
            .defineInRange("similarity", 0.8, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue VOICE_VOLUME = BUILDER
            .comment("Loudness of spoken lines, 0..2, on top of the game's Voice/Speech slider.")
            .defineInRange("volume", 1.0, 0.0, 2.0);

    public static final ModConfigSpec.DoubleValue VOICE_LINE_GAP_SECONDS = BUILDER
            .comment("Silence between one spoken line ending and the next beginning. Lines never overlap; this is the breath between them.")
            .defineInRange("lineGapSeconds", 0.7, 0.0, 10.0);

    public static final ModConfigSpec.IntValue VOICE_TIMEOUT_SECONDS = BUILDER
            .comment("Seconds to wait for the audio before the text goes out without it.")
            .defineInRange("timeoutSeconds", 20, 3, 120);

    public static final ModConfigSpec.DoubleValue VOICE_PRICE_PER_THOUSAND = BUILDER
            .comment("USD per thousand characters, for the cost estimate in /hush usage (ElevenLabs Creator is about 0.30).")
            .defineInRange("pricePerThousandChars", 0.30, 0.0, 100.0);

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
