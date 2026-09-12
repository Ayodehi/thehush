package com.ayodehi.thehush.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/** Turns the music and ambient categories down to nothing while the player is in the Quiet, and puts them back. */
public final class SilenceClient {
    private static @Nullable Double savedMusic;
    private static @Nullable Double savedAmbient;
    /** The drop: master volume held at nothing for this many more ticks, then put back. */
    private static int dropTicks;
    private static @Nullable Double savedMaster;

    /** Every sound goes out for a moment. Nothing else changes; the world is simply not there to hear. */
    public static void drop(int ticks) {
        Options o = Minecraft.getInstance().options;
        if (savedMaster == null) savedMaster = o.getSoundSourceOptionInstance(SoundSource.MASTER).get();
        o.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
        Minecraft.getInstance().getSoundManager().pauseAllExcept();
        dropTicks = Math.max(dropTicks, ticks);
    }

    public static void tick() {
        if (dropTicks <= 0) return;
        if (--dropTicks == 0 || Minecraft.getInstance().level == null) restoreMaster();
    }

    private static void restoreMaster() {
        dropTicks = 0;
        if (savedMaster != null) {
            Minecraft.getInstance().options.getSoundSourceOptionInstance(SoundSource.MASTER).set(savedMaster);
            savedMaster = null;
        }
        Minecraft.getInstance().getSoundManager().resume();
    }

    private SilenceClient() {}

    public static void apply(boolean on) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        if (on) {
            if (savedMusic == null) {
                savedMusic = o.getSoundSourceOptionInstance(SoundSource.MUSIC).get();
                savedAmbient = o.getSoundSourceOptionInstance(SoundSource.AMBIENT).get();
            }
            o.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
            o.getSoundSourceOptionInstance(SoundSource.AMBIENT).set(0.0);
            mc.getMusicManager().stopPlaying();
        } else if (savedMusic != null) {
            restoreMaster();
            o.getSoundSourceOptionInstance(SoundSource.MUSIC).set(savedMusic);
            o.getSoundSourceOptionInstance(SoundSource.AMBIENT).set(savedAmbient == null ? 1.0 : savedAmbient);
            savedMusic = null;
            savedAmbient = null;
        }
    }
}
