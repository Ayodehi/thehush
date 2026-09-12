package com.ayodehi.thehush.client;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.network.HushVoicePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Plays spoken lines on the client through OpenAL, the same device Minecraft's own sounds use, as a 3D
 * source that follows the speaking entity. Chunks are gathered per utterance and played once complete.
 * Volume follows the game's master and voice sliders.
 */
public final class VoiceClient {
    private static final Map<Integer, Pending> pending = new HashMap<>();
    private static final List<Playing> playing = new ArrayList<>();

    private VoiceClient() {}

    private static final class Pending {
        final byte[][] parts;
        int have;
        long since = System.currentTimeMillis();

        Pending(int total) {
            parts = new byte[total][];
        }
    }

    private static final class Playing {
        final int source;
        final int buffer;
        final int entityId;
        final float gain;

        Playing(int source, int buffer, int entityId, float gain) {
            this.source = source;
            this.buffer = buffer;
            this.entityId = entityId;
            this.gain = gain;
        }
    }

    /** Runs on the client main thread. */
    public static void receive(HushVoicePayload payload) {
        Pending p = pending.computeIfAbsent(payload.utterance(), k -> new Pending(payload.total()));
        if (payload.seq() < 0 || payload.seq() >= p.parts.length || p.parts[payload.seq()] != null) return;
        p.parts[payload.seq()] = payload.data();
        p.have++;
        if (p.have < p.parts.length) return;
        pending.remove(payload.utterance());
        int length = 0;
        for (byte[] part : p.parts) length += part.length;
        ByteBuffer pcm = ByteBuffer.allocateDirect(length).order(ByteOrder.LITTLE_ENDIAN);
        for (byte[] part : p.parts) pcm.put(part);
        pcm.flip();
        play(pcm, payload.sampleRate(), payload.entityId(), payload.gain(), payload.range());
    }

    private static void play(ByteBuffer pcm, int sampleRate, int entityId, float gain, float range) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Entity speaker = mc.level.getEntity(entityId);
        try {
            int buffer = AL10.alGenBuffers();
            AL10.alBufferData(buffer, AL10.AL_FORMAT_MONO16, pcm, sampleRate);
            int source = AL10.alGenSources();
            AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
            AL10.alSourcei(source, AL11.AL_SOURCE_RELATIVE, AL10.AL_FALSE);
            AL10.alSourcei(source, AL10.AL_DISTANCE_MODEL, AL11.AL_LINEAR_DISTANCE_CLAMPED);
            AL10.alSourcef(source, AL10.AL_MAX_DISTANCE, range);
            AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 1.0F);
            AL10.alSourcef(source, AL10.AL_REFERENCE_DISTANCE, 0.0F);
            AL10.alSourcef(source, AL10.AL_PITCH, 1.0F);
            Playing pl = new Playing(source, buffer, entityId, gain);
            position(pl, speaker);
            AL10.alSourcef(source, AL10.AL_GAIN, volume(pl));
            AL10.alSourcePlay(source);
            int err = AL10.alGetError();
            if (err != AL10.AL_NO_ERROR) {
                TheHushMod.LOGGER.warn("OpenAL error {} playing a voice line", err);
                AL10.alDeleteSources(source);
                AL10.alDeleteBuffers(buffer);
                return;
            }
            playing.add(pl);
        } catch (RuntimeException e) {
            TheHushMod.LOGGER.warn("Could not play a voice line", e);
        }
    }

    private static float volume(Playing pl) {
        var options = Minecraft.getInstance().options;
        return pl.gain * options.getFinalSoundSourceVolume(SoundSource.VOICE);
    }

    private static void position(Playing pl, Entity speaker) {
        if (speaker == null) return;
        AL10.alSource3f(pl.source, AL10.AL_POSITION, (float) speaker.getX(), (float) speaker.getEyeY(), (float) speaker.getZ());
    }

    /** Every client tick: follow the speakers, apply the sliders, and free finished lines. */
    public static void tick() {
        if (playing.isEmpty() && pending.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        pending.values().removeIf(p -> now - p.since > 20_000);
        Iterator<Playing> it = playing.iterator();
        while (it.hasNext()) {
            Playing pl = it.next();
            int state = AL10.alGetSourcei(pl.source, AL10.AL_SOURCE_STATE);
            if (state != AL10.AL_PLAYING || mc.level == null) {
                AL10.alSourceStop(pl.source);
                AL10.alDeleteSources(pl.source);
                AL10.alDeleteBuffers(pl.buffer);
                it.remove();
                continue;
            }
            Entity speaker = mc.level.getEntity(pl.entityId);
            position(pl, speaker);
            AL10.alSourcef(pl.source, AL10.AL_GAIN, volume(pl));
        }
    }

    /** Leaving a world: silence everything. */
    public static void clear() {
        for (Playing pl : playing) {
            AL10.alSourceStop(pl.source);
            AL10.alDeleteSources(pl.source);
            AL10.alDeleteBuffers(pl.buffer);
        }
        playing.clear();
        pending.clear();
    }
}
