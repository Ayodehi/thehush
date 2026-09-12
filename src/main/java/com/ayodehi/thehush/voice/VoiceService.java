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
        String kind = Config.VOICE_PROVIDER.get().trim().toLowerCase();
        if (kind.isEmpty() || kind.equals("none")) return;
        String key = Config.VOICE_API_KEY.get().trim();
        if (key.isEmpty()) {
            String env = System.getenv(Config.VOICE_API_KEY_ENV_VAR.get());
            key = env == null ? "" : env.trim();
        }
        if (key.isEmpty()) {
            TheHushMod.LOGGER.warn("voice.provider is {} but no API key is set; he stays silent", kind);
            return;
        }
        if (executor == null) executor = Executors.newVirtualThreadPerTaskExecutor();
        if (kind.equals("elevenlabs")) {
            provider = new ElevenLabsVoice(new ElevenLabsVoice.Settings(key, Config.VOICE_ID.get(), Config.VOICE_MODEL.get(),
                    Config.VOICE_STABILITY.get(), Config.VOICE_SIMILARITY.get(), Config.VOICE_TIMEOUT_SECONDS.get()), executor);
        } else {
            TheHushMod.LOGGER.warn("Unknown voice.provider '{}'; he stays silent", kind);
        }
    }

    public synchronized void shutdown() {
        provider = null;
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
        return provider == null ? "none" : provider.describe();
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
        Throwable error = u.error;
        VoiceProvider.Audio a = u.audio;
        if (error != null || a == null) {
            Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
            TheHushMod.LOGGER.warn("Voice failed for {}: {}", u.speaker.speakerName(), cause == null ? "no audio" : cause.toString());
            return Math.min(MAX_TEXT_ONLY_MILLIS, u.text.length() * MILLIS_PER_CHAR);
        }
        UsageMeter.get().recordVoice(u.text.length(), Config.VOICE_PRICE_PER_THOUSAND.get());
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
