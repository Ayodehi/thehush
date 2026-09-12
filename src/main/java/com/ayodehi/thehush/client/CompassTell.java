package com.ayodehi.thehush.client;

import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.CompassAngleState;
import net.minecraft.client.renderer.item.properties.numeric.NeedleDirectionHelper;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;

/**
 * The compass clue: a needle property that behaves exactly like vanilla's {@code minecraft:compass} until one
 * of the talking villagers is near the holder. Then, depending on {@code tell}, the needle either loses its
 * bearing and spins ("spin": the plain compass beside him) or swings to point at him ("point": the recovery
 * compass, for which he is, in the game's own terms, a place where someone died). Registered as
 * {@code thehush:compass_angle}; the vanilla compass and recovery compass item definitions are overridden to use
 * it with the same wobble/target fields plus {@code tell} and {@code radius}.
 */
public final class CompassTell implements RangeSelectItemModelProperty {
    public static final MapCodec<CompassTell> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            CompassAngleState.MAP_CODEC.forGetter(t -> t.vanilla),
            Tell.CODEC.optionalFieldOf("tell", Tell.SPIN).forGetter(t -> t.tell),
            Codec.FLOAT.optionalFieldOf("radius", 4.0F).forGetter(t -> t.radius)
    ).apply(i, CompassTell::new));

    public enum Tell implements StringRepresentable {
        SPIN("spin"), POINT("point");

        static final Codec<Tell> CODEC = StringRepresentable.fromEnum(Tell::values);
        private final String name;

        Tell(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    private final CompassAngleState vanilla;
    private final Tell tell;
    private final float radius;
    /** Smooths the swing toward him for the local player, as vanilla does for its own targets. */
    private final NeedleDirectionHelper.Wobbler wobbler = NeedleDirectionHelper.standardWobbler(0.8F);
    // The nearest villager is looked up once per tick, not once per rendered frame.
    private long lookedUpTick = Long.MIN_VALUE;
    private @Nullable AiVillagerEntity nearest;

    private CompassTell(CompassAngleState vanilla, Tell tell, float radius) {
        this.vanilla = vanilla;
        this.tell = tell;
        this.radius = radius;
    }

    @Override
    public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        if (owner != null && level == null && owner.level() instanceof ClientLevel l) level = l;
        if (owner != null && level != null) {
            AiVillagerEntity him = nearestVillager(level, owner);
            if (him != null) {
                long time = level.getGameTime();
                return tell == Tell.SPIN ? spinning(seed, time, owner) : pointingAt(owner, time, him);
            }
        }
        return vanilla.get(stack, level, owner, seed);
    }

    private @Nullable AiVillagerEntity nearestVillager(ClientLevel level, ItemOwner owner) {
        long tick = level.getGameTime();
        if (tick != lookedUpTick) {
            lookedUpTick = tick;
            Vec3 at = owner.position();
            nearest = level.getEntitiesOfClass(AiVillagerEntity.class, AABB.ofSize(at, radius * 2, radius * 2, radius * 2),
                            v -> v.isAlive() && v.position().distanceToSqr(at) <= radius * radius)
                    .stream().min(Comparator.comparingDouble(v -> v.position().distanceToSqr(at))).orElse(null);
        }
        return nearest;
    }

    /**
     * A needle that cannot settle: it turns, but unevenly, hurrying and hesitating, with a per-stack offset so two
     * compasses side by side do not agree. Partial ticks are ignored; the stutter reads better at 20 Hz.
     */
    private static float spinning(int seed, long time, ItemOwner owner) {
        double t = time;
        double turn = t * 0.031                                  // about one turn every 32 ticks
                + 0.09 * Math.sin(t * 0.37)                       // hesitation
                + 0.05 * Math.sin(t * 1.9 + seed)                 // tremor
                + (seed * 1327217883) / 4294967296.0;             // this compass's own phase
        // Keep the reading relative to the holder so turning your head does not "steady" it by accident.
        float yaw = Mth.positiveModulo(owner.getVisualRotationYInDegrees() / 360.0F, 1.0F);
        return Mth.positiveModulo((float) turn - yaw, 1.0F);
    }

    /** The vanilla pointing formula with him as the target, smoothed for the local player as vanilla smooths its own. */
    private float pointingAt(ItemOwner owner, long time, AiVillagerEntity him) {
        Vec3 from = owner.position();
        float angleToTarget = (float) (Math.atan2(him.getZ() - from.z(), him.getX() - from.x()) / (Math.PI * 2));
        float yaw = Mth.positiveModulo(owner.getVisualRotationYInDegrees() / 360.0F, 1.0F);
        float rotation;
        if (owner.asLivingEntity() instanceof Player p && p.isLocalPlayer() && p.level().tickRateManager().runsNormally()) {
            if (wobbler.shouldUpdate(time)) wobbler.update(time, 0.5F - (yaw - 0.25F));
            rotation = angleToTarget + wobbler.rotation();
        } else {
            rotation = 0.5F - (yaw - 0.25F - angleToTarget);
        }
        return Mth.positiveModulo(rotation, 1.0F);
    }

    @Override
    public MapCodec<CompassTell> type() {
        return MAP_CODEC;
    }
}
