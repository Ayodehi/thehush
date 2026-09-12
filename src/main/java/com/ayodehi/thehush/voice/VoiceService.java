package com.ayodehi.thehush.voice;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.llm.UsageMeter;
import com.ayodehi.thehush.network.HushVoicePayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Gives the villagers a voice. A line is sent to the configured engine off-thread; when the audio comes
 * back (or after a few seconds if it does not) the text is put in chat and the audio is streamed to
 * every player within range as chunks, to be played at the speaker's position on their client.
 * Without a provider, or on any failure, the text alone goes out at once.
 * <p>
 * Lines are paced per speaker: synthesis starts the moment a line is written, but a line is only shown and
 * played once the one before it has finished sounding (plus a short gap), so a greeting, a beat and a reply
 * that land together are heard one after another instead of on top of each other.
 */
public final class VoiceService {
    private static final VoiceService INSTANCE = new VoiceService();
    private static final int CHUNK_BYTES = 24_000;
    private static final int WAIT_SECONDS = 6;
    private static final double RANGE = 24.0;
    /** Reading pace assumed for a line whose audio failed, so text-only lines are still spaced out. */
    private static final long MILLIS_PER_CHAR = 55;
    private static final long MAX_TEXT_ONLY_MILLIS = 6_000;

    private @Nullable VoiceProvider provider;
    private @Nullable ExecutorService executor;
    /** Why he is silent right now, and until when (epoch millis); null when the voice is working. */
    private volatile @Nullable Outage outage;
    private volatile VoiceProvider.@Nullable Credits credits;
    /** USD per thousand characters for the configured engine (0 for a local one). */
    private volatile double pricePerThousand;
    private static final long AUTH_HOLD_MILLIS = 30 * 60_000L;
    private static final long QUOTA_HOLD_MILLIS = 30 * 60_000L;
    private static final long RATE_HOLD_MILLIS = 20_000L;

    /** A stretch of enforced silence: what happened, what to tell people, and when to try again. */
    private record Outage(VoiceException.Kind kind, String message, long untilMillis) {
        boolean active() {
            return System.currentTimeMillis() < untilMillis;
        }
    }
    private final AtomicInteger utterances = new AtomicInteger();
    /** Server thread only: what each speaker has yet to say, in order, and when they fall silent. */
    private final Map<UUID, Queue> queues = new HashMap<>();

    private VoiceService() {}

    /** One line waiting its turn: the audio arrives whenever it arrives; the line goes out in order. */
    private static final class Utterance {
        final AiVillagerEntity speaker;
        final Runnable showText;
        final String text;
        final Speech.Manner manner;
        final long started = System.currentTimeMillis();
        volatile boolean done;
        volatile VoiceProvider.@Nullable Audio audio;
        volatile @Nullable Throwable error;

        Utterance(AiVillagerEntity speaker, Runnable showText, String text, Speech.Manner manner) {
            this.speaker = speaker;
            this.showText = showText;
            this.text = text;
            this.manner = manner;
        }
    }

    private static final class Queue {
        final ArrayDeque<Utterance> lines = new ArrayDeque<>();
        long silentAt;
    }

    public static VoiceService get() {
        return INSTANCE;
    }

    public synchronized void configure() {
        provider = null;
        outage = null;
        credits = null;
        Config.VoiceBackend kind = Config.VOICE_PROVIDER.get();
        if (kind == Config.VoiceBackend.NONE) return;
        if (executor == null) executor = Executors.newVirtualThreadPerTaskExecutor();
        if (kind == Config.VoiceBackend.OPENAI) {
            String key = Config.SPEECH_API_KEY.get().trim();
            if (key.isEmpty()) {
                String env = System.getenv(Config.SPEECH_API_KEY_ENV_VAR.get());
                key = env == null ? "" : env.trim(); // may stay empty: local servers want none
            }
            pricePerThousand = Config.SPEECH_PRICE_PER_THOUSAND.get();
            provider = new OpenAiSpeechVoice(new OpenAiSpeechVoice.Settings(Config.SPEECH_URL.get().trim(), key,
                    Config.SPEECH_MODEL.get().trim(), Config.SPEECH_VOICE.get().trim(), Config.SPEECH_FORMAT.get(),
                    Config.SPEECH_PCM_RATE.get(), Config.SPEECH_CUES.get(), Config.SPEECH_SPEED.get(),
                    Config.SPEECH_EXTRA.get(), Config.VOICE_TIMEOUT_SECONDS.get()), executor);
            return;
        }
        String key = Config.VOICE_API_KEY.get().trim();
        if (key.isEmpty()) {
            String env = System.getenv(Config.VOICE_API_KEY_ENV_VAR.get());
            key = env == null ? "" : env.trim();
        }
        if (key.isEmpty()) {
            TheHushMod.LOGGER.warn("voice.provider is {} but no API key is set; he stays silent", kind);
            return;
        }
        if (kind == Config.VoiceBackend.ELEVENLABS) {
            pricePerThousand = Config.VOICE_PRICE_PER_THOUSAND.get();
            provider = new ElevenLabsVoice(new ElevenLabsVoice.Settings(key, Config.VOICE_ID.get(), Config.VOICE_MODEL.get(),
                    Config.VOICE_STABILITY.get(), Config.VOICE_SIMILARITY.get(), Config.VOICE_TIMEOUT_SECONDS.get()), executor);
            refreshCredits(null);
        } else {
            TheHushMod.LOGGER.warn("Unknown voice.provider {}; he stays silent", kind);
        }
    }

    public synchronized void shutdown() {
        provider = null;
        outage = null;
        queues.clear();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public synchronized boolean enabled() {
        return provider != null;
    }

    public synchronized String describe() {
        if (provider == null) return "none";
        Outage o = outage;
        String base = provider.describe();
        if (o != null && o.active()) return base + " [SILENT: " + o.message + "]";
        VoiceProvider.Credits c = credits;
        return c == null || c.limit() <= 0 ? base : base + " [" + c.remaining() + " of " + c.limit() + " characters left]";
    }

    /** One line about the account's credit for /hush usage, or empty when the engine cannot say. */
    public String creditsLine() {
        VoiceProvider.Credits c = credits;
        if (c == null || c.limit() <= 0) return "";
        String tier = c.tier().isEmpty() ? "" : " (" + c.tier() + ")";
        String reset = c.resetEpochSeconds() > 0 ? ", resets " + when(c.resetEpochSeconds() * 1000L) : "";
        Outage o = outage;
        String state = o != null && o.active() && o.kind() == VoiceException.Kind.QUOTA ? "; he is silent until then" : "";
        return "Voice credits" + tier + ": " + c.used() + " of " + c.limit() + " characters used, " + c.remaining() + " left" + reset + state + ".";
    }

    /** Ask the engine what is left; on a quota outage the reset time is taken from the answer. */
    private void refreshCredits(@Nullable MinecraftServer server) {
        VoiceProvider p = provider;
        if (p == null) return;
        p.credits().whenComplete((c, error) -> {
            if (error != null || c == null) {
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                String why = cause == null ? "empty answer" : cause.getMessage();
                if (why != null && why.contains("missing_permissions")) {
                    TheHushMod.LOGGER.info("Voice credits cannot be read: the ElevenLabs API key lacks the 'user_read' permission. "
                            + "Speech still works; grant User: Read on the key at elevenlabs.io to see credits in /hush usage and get early warning when they run out.");
                } else {
                    TheHushMod.LOGGER.info("Voice credits cannot be read: {}", why);
                }
                return;
            }
            credits = c;
            TheHushMod.LOGGER.info("Voice credits: {} of {} characters used{}", c.used(), c.limit(),
                    c.resetEpochSeconds() > 0 ? ", reset " + when(c.resetEpochSeconds() * 1000L) : "");
            Outage o = outage;
            if (c.exhausted() && (o == null || !o.active())) {
                // Out before the first line was even asked for: say so now rather than on the first failure.
                Outage fresh = new Outage(VoiceException.Kind.QUOTA, quotaMessage(c), holdUntil(c));
                outage = fresh;
                if (server != null) server.execute(() -> announce(server, fresh));
                else TheHushMod.LOGGER.warn("He is silent: {}", fresh.message());
            } else if (o != null && o.kind() == VoiceException.Kind.QUOTA && c.resetEpochSeconds() > 0) {
                outage = new Outage(o.kind(), quotaMessage(c), holdUntil(c));
            }
        });
    }

    private static long holdUntil(VoiceProvider.Credits c) {
        long now = System.currentTimeMillis();
        return c.resetEpochSeconds() > 0 ? Math.max(now + 60_000L, c.resetEpochSeconds() * 1000L) : now + QUOTA_HOLD_MILLIS;
    }

    private static String quotaMessage(VoiceProvider.Credits c) {
        String reset = c.resetEpochSeconds() > 0 ? "; they reset " + when(c.resetEpochSeconds() * 1000L) : "";
        return "ElevenLabs credits are used up (" + c.used() + " of " + c.limit() + " characters" + reset + ")";
    }

    private static String when(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }

    /**
     * A failed line: decide whether to keep trying. Quota and key problems silence him for a while and are
     * announced once; rate limits pause briefly and quietly; anything else is just logged.
     */
    private void onFailure(MinecraftServer server, AiVillagerEntity speaker, Throwable error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        VoiceException ve = cause instanceof VoiceException v ? v : null;
        VoiceException.Kind kind = ve != null ? ve.kind() : VoiceException.Kind.OTHER;
        long now = System.currentTimeMillis();
        Outage o = outage;
        switch (kind) {
            case QUOTA -> {
                if (o != null && o.active() && o.kind() == kind) return;
                VoiceProvider.Credits c = credits;
                long until = ve.resetEpochSeconds() > 0 ? Math.max(now + 60_000L, ve.resetEpochSeconds() * 1000L)
                        : c != null && c.resetEpochSeconds() > 0 ? holdUntil(c) : now + QUOTA_HOLD_MILLIS;
                String msg = c != null && c.limit() > 0 ? quotaMessage(c) : "ElevenLabs credits are used up (" + ve.getMessage() + ")";
                Outage fresh = new Outage(kind, msg, until);
                outage = fresh;
                announce(server, fresh);
                refreshCredits(server); // learn the reset time and the exact numbers
            }
            case AUTH -> {
                if (o != null && o.active() && o.kind() == kind) return;
                Outage fresh = new Outage(kind, "ElevenLabs rejected the API key (" + cause.getMessage()
                        + "). Check voice.apiKey in config/thehush-common.toml; saving it puts his voice back", now + AUTH_HOLD_MILLIS);
                outage = fresh;
                announce(server, fresh);
            }
            case VOICE -> {
                if (o != null && o.active() && o.kind() == kind) return;
                Outage fresh = new Outage(kind, "ElevenLabs does not know this voice or model (" + cause.getMessage()
                        + "). Check voice.voiceId and voice.model", now + AUTH_HOLD_MILLIS);
                outage = fresh;
                announce(server, fresh);
            }
            case RATE_LIMIT -> {
                outage = new Outage(kind, "ElevenLabs is busy (" + cause.getMessage() + ")", now + RATE_HOLD_MILLIS);
                TheHushMod.LOGGER.warn("Voice paused for {}: {}", speaker.speakerName(), cause.toString());
            }
            default -> TheHushMod.LOGGER.warn("Voice failed for {}: {}", speaker.speakerName(), cause.toString());
        }
    }

    /** Tell the people who can do something: operators, and everyone in a single-player or LAN world. */
    private static void announce(MinecraftServer server, Outage o) {
        TheHushMod.LOGGER.warn("He is silent: {}", o.message());
        String text = "The voice has gone: " + o.message() + ". Lines arrive as text only until then.";
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (!server.isDedicatedServer() || server.getPlayerList().isOp(p.nameAndId())) {
                p.sendSystemMessage(net.minecraft.network.chat.Component.literal(text)
                        .withStyle(net.minecraft.ChatFormatting.GRAY, net.minecraft.ChatFormatting.ITALIC));
            }
        }
    }

    /** True when the engine will act on bracketed delivery cues, so the persona is told it may write them. */
    public synchronized boolean readsCues() {
        return provider != null && provider.readsCues();
    }

    /**
     * Say a line: fetch the audio, then run {@code showText} on the server thread and stream the sound.
     * If the voice is off or fails, {@code showText} runs without sound (at once, or after the wait).
     */
    public void say(AiVillagerEntity speaker, String line, Runnable showText) {
        VoiceProvider p;
        synchronized (this) {
            p = provider;
        }
        MinecraftServer server = speaker.level().getServer();
        if (p == null || server == null) {
            showText.run();
            return;
        }
        Outage o = outage;
        if (o != null && o.active()) {
            // Silent for a known reason: the text goes out at once, in order with anything still queued.
            Utterance u = new Utterance(speaker, showText, "", Speech.Manner.PLAIN);
            u.done = true;
            queues.computeIfAbsent(speaker.getUUID(), k -> new Queue()).lines.addLast(u);
            tick();
            return;
        }
        // Cues from the world: he whispers on sculk ground, and calls out when the one he talks to is far off.
        String cued = line;
        if (speaker.level() instanceof ServerLevel lvl && speaker.quietGround(lvl)) cued = Speech.withCue(cued, "whispers");
        else {
            ServerPlayer partner = speaker.talkingTo();
            if (partner != null && partner.level() == speaker.level() && partner.distanceToSqr(speaker) > 14 * 14) cued = Speech.withCue(cued, "calls out");
        }
        Speech.Manner manner = Speech.manner(Speech.cues(cued));
        String text = Speech.spoken(cued, p.readsCues());
        if (text.isEmpty()) {
            showText.run();
            return;
        }
        Utterance u = new Utterance(speaker, showText, text, manner);
        queues.computeIfAbsent(speaker.getUUID(), k -> new Queue()).lines.addLast(u);
        p.speak(text, manner).orTimeout(WAIT_SECONDS, TimeUnit.SECONDS).whenComplete((a, error) -> {
            u.audio = a;
            u.error = error;
            u.done = true;
        });
        tick(); // if nothing is queued ahead and the audio is somehow already here, go now
    }

    /** True while this speaker has a line sounding or waiting to sound; unprompted remarks hold off. */
    public boolean isSpeaking(AiVillagerEntity speaker) {
        Queue q = queues.get(speaker.getUUID());
        return q != null && (!q.lines.isEmpty() || System.currentTimeMillis() < q.silentAt);
    }

    /** Every server tick: send out each speaker's next line once its audio is here and the last line has ended. */
    public void tick() {
        if (queues.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Queue>> it = queues.entrySet().iterator();
        while (it.hasNext()) {
            Queue q = it.next().getValue();
            while (!q.lines.isEmpty() && now >= q.silentAt) {
                Utterance u = q.lines.peekFirst();
                if (!u.done) break; // wait for the audio; later lines wait behind it so the order holds
                q.lines.pollFirst();
                q.silentAt = now + dispatch(u) + Math.round(Config.VOICE_LINE_GAP_SECONDS.get() * 1000);
            }
            if (q.lines.isEmpty() && now >= q.silentAt) it.remove();
        }
    }

    /** Show the text and start the sound; returns how long the line will take to hear. */
    private long dispatch(Utterance u) {
        u.showText.run();
        if (u.text.isEmpty()) return 0; // a text-only line during an outage: no pacing needed
        Throwable error = u.error;
        VoiceProvider.Audio a = u.audio;
        if (error != null || a == null) {
            MinecraftServer server = u.speaker.level().getServer();
            if (error != null && server != null) onFailure(server, u.speaker, error);
            else TheHushMod.LOGGER.warn("Voice failed for {}: no audio", u.speaker.speakerName());
            return Math.min(MAX_TEXT_ONLY_MILLIS, u.text.length() * MILLIS_PER_CHAR);
        }
        UsageMeter.get().recordVoice(u.text.length(), pricePerThousand);
        AiVillagerEntity live = u.speaker.current();
        if (!live.isAlive()) return 0;
        stream(live, a, u.manner);
        long millis = a.pcm().length * 1000L / (2L * Math.max(1, a.sampleRate()));
        TheHushMod.LOGGER.debug("Voice for {}: {} chars, {} bytes, {} ms long, ready after {} ms", live.speakerName(), u.text.length(),
                a.pcm().length, millis, System.currentTimeMillis() - u.started);
        return millis;
    }

    private void stream(AiVillagerEntity speaker, VoiceProvider.Audio audio, Speech.Manner manner) {
        ServerLevel level = (ServerLevel) speaker.level();
        double loud = switch (manner) {
            case WHISPER -> 0.5;
            case SOFT -> 0.8;
            case SHOUT -> 1.3;
            default -> 1.0;
        };
        double reach = switch (manner) {
            case WHISPER -> 0.5;
            case SOFT -> 0.8;
            case SHOUT -> 1.6;
            default -> 1.0;
        };
        float gain = (float) Math.min(2.0, Config.VOICE_VOLUME.get() * loud);
        float range = (float) (RANGE * reach);
        int id = utterances.incrementAndGet();
        byte[] pcm = audio.pcm();
        int total = Math.max(1, (pcm.length + CHUNK_BYTES - 1) / CHUNK_BYTES);
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(speaker) > (range + 8) * (range + 8)) continue;
            for (int seq = 0; seq < total; seq++) {
                int from = seq * CHUNK_BYTES;
                byte[] part = Arrays.copyOfRange(pcm, from, Math.min(pcm.length, from + CHUNK_BYTES));
                PacketDistributor.sendToPlayer(p, new HushVoicePayload(id, speaker.getId(), audio.sampleRate(), seq, total, gain, range, part));
            }
        }
    }
}
