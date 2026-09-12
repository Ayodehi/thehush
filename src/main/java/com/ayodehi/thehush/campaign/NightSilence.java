package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.debug.DebugBridge;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * At night, near the sculk, he stops answering. Questions go unanswered and unremembered; at dawn he
 * answers again as if there had been no gap. It stops once the road reaches the Deep Dark, where he
 * explains why (and whispers instead). The first unanswered line each night gets a quiet note so the
 * player knows it was not the road being slow.
 */
public final class NightSilence {
    private static final int SCULK_RADIUS = 8;
    private static final int RECHECK_TICKS = 20;

    private record Check(long tick, @Nullable BlockPos sculk) {}

    private static final Map<UUID, Check> checks = new HashMap<>();
    private static final Map<UUID, Long> notedDay = new HashMap<>();

    private NightSilence() {}

    /** True when he will not answer right now. Cheap to call every tick. */
    public static boolean holdsTongue(AiVillagerEntity him) {
        return sculkThatSilences(him) != null;
    }

    private static @Nullable BlockPos sculkThatSilences(AiVillagerEntity him) {
        if (!(him.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return null;
        MinecraftServer server = level.getServer();
        CampaignManager cm = CampaignManager.get();
        if (!cm.active(server) || !cm.takesPart(him)) return null;
        CampaignDefinition def = cm.definition(server);
        if (def == null) return null;
        String stage = cm.state(server).stage;
        if (def.indexOf(stage) < 0 || def.indexOf(stage) >= def.indexOf("deep_dark")) return null;
        long t = level.getOverworldClockTime() % 24000L;
        if (t < 13000 || t >= 23000) return null;
        long now = level.getGameTime();
        Check c = checks.get(him.getUUID());
        if (c == null || now - c.tick >= RECHECK_TICKS) {
            c = new Check(now, sculkNear(level, him.blockPosition(), SCULK_RADIUS));
            checks.put(him.getUUID(), c);
        }
        return c.sculk;
    }

    public static @Nullable BlockPos sculkNear(ServerLevel level, BlockPos centre, int r) {
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r, -3, -r), centre.offset(r, 3, r))) {
            BlockState st = level.getBlockState(p);
            if (st.is(Blocks.SCULK) || st.is(Blocks.SCULK_VEIN) || st.is(Blocks.SCULK_SENSOR)
                    || st.is(Blocks.SCULK_SHRIEKER) || st.is(Blocks.SCULK_CATALYST)) return p.immutable();
        }
        return null;
    }

    /** A line was spoken to him and he let it pass. He looks at the sculk; the player is told, once a night. */
    public static void onSilenced(AiVillagerEntity him, ServerPlayer player) {
        BlockPos sculk = sculkThatSilences(him);
        if (sculk != null) him.getLookControl().setLookAt(sculk.getX() + 0.5, sculk.getY() + 0.5, sculk.getZ() + 0.5);
        long day = player.level().getGameTime() / 24000L;
        Long noted = notedDay.get(player.getUUID());
        if (noted != null && noted == day) return;
        notedDay.put(player.getUUID(), day);
        String text = him.speakerName() + " does not answer. He is looking at something on the ground, and then at nothing.";
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        DebugBridge.get().record("narrator", "", text);
    }

    public static void clear() {
        checks.clear();
        notedDay.clear();
    }
}
