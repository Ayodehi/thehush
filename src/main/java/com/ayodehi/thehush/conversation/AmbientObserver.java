package com.ayodehi.thehush.conversation;

import com.ayodehi.thehush.Config;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jspecify.annotations.Nullable;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the world around one villager and turns notable changes into short observations the villager
 * may comment on. All rate limiting lives here so a persona can be as moody as it likes without spamming:
 * one remark per global cooldown, plus a per-kind cooldown, plus once-per-night for the open-sky warning.
 */
public final class AmbientObserver {
    private static final int CHECK_EVERY_TICKS = 20;
    private static final long MINUTE = 20L * 60L;

    enum Kind {
        WEATHER(4 * MINUTE), DUSK(10 * MINUTE), DAWN(10 * MINUTE), NIGHT_OPEN(10 * MINUTE),
        MONSTER(3 * MINUTE), HURT(3 * MINUTE),
        ACTIVITY(3 * MINUTE), SUPPLIES(6 * MINUTE), FURNACE(8 * MINUTE), HUNGER(5 * MINUTE), TOOL_WEAR(2 * MINUTE),
        DARK(3 * MINUTE), ACHIEVEMENT(MINUTE);

        final long cooldownTicks;
        Kind(long cooldownTicks) { this.cooldownTicks = cooldownTicks; }
    }

    private boolean primed;
    private int lastWeather;   // 0 clear, 1 rain, 2 thunder
    private int lastPhase;     // 0 day, 1 dusk, 2 night, 3 dawn
    private long lastNightWarnDay = -1;
    private long lastRemarkTick = Long.MIN_VALUE / 2;
    private final Map<Kind, Long> lastByKind = new EnumMap<>(Kind.class);
    /** Which wear stage (1 = worn, 2 = about to break) has been mentioned, per held tool. */
    private final Map<String, Integer> toolStageSaid = new java.util.HashMap<>();
    private boolean inTheDark;

    /** Called every server tick; returns an observation worth mentioning, or null. */
    public @Nullable String observe(AiVillagerEntity npc, ServerLevel level, ServerPlayer audience) {
        if (npc.tickCount % CHECK_EVERY_TICKS != 0) return null;
        long now = level.getGameTime();
        long clock = level.getOverworldClockTime();
        long day = clock / 24000L;
        long t = clock % 24000L;
        int weather = level.isThundering() ? 2 : level.isRaining() ? 1 : 0;
        int phase = t < 12000 ? 0 : t < 13000 ? 1 : t < 23000 ? 2 : 3;

        if (!primed) {
            primed = true;
            lastWeather = weather;
            lastPhase = phase;
            return null;
        }
        int prevWeather = lastWeather;
        int prevPhase = lastPhase;
        lastWeather = weather;
        lastPhase = phase;

        boolean openSky = level.canSeeSky(npc.blockPosition().above());
        String where = openSky ? "You are standing under the open sky" : "You are under a roof";
        boolean globalReady = now - lastRemarkTick >= Config.AMBIENT_COOLDOWN_SECONDS.get() * 20L;

        // Weather changes are the most noticeable; they may fire even during the global cooldown's tail.
        if (weather != prevWeather && ready(Kind.WEATHER, now) && (globalReady || now - lastRemarkTick >= MINUTE)) {
            String what = switch (weather) {
                case 2 -> "A thunderstorm has broken over you";
                case 1 -> prevWeather == 2 ? "The thunder has passed but the rain goes on" : "It has started to rain";
                default -> "The rain has stopped and the sky is clearing";
            };
            return fire(Kind.WEATHER, now, what + ". " + where + ".");
        }
        // Achievements deserve a prompt word; allow them after a short gap even inside the global cooldown.
        if (ready(Kind.ACHIEVEMENT, now) && now - lastRemarkTick >= MINUTE / 2) {
            String earned = PlayerActivity.drainAchievements(audience.getUUID());
            if (earned != null) {
                return fire(Kind.ACHIEVEMENT, now, audience.getName().getString() + " has just earned the advancement " + earned + ".");
            }
        }
        if (!globalReady) return null;

        if (phase == 1 && prevPhase != 1 && ready(Kind.DUSK, now)) {
            return fire(Kind.DUSK, now, "The sun is going down; night is coming. " + where + ".");
        }
        if (phase == 3 && prevPhase != 3 && ready(Kind.DAWN, now)) {
            return fire(Kind.DAWN, now, "Dawn is breaking; the night is over. " + where + ".");
        }
        if (phase == 2 && openSky && day != lastNightWarnDay && ready(Kind.NIGHT_OPEN, now)) {
            lastNightWarnDay = day;
            return fire(Kind.NIGHT_OPEN, now, "It is full night and you are out in the open with no roof or walls near you.");
        }
        if ((phase == 2 || level.isDarkOutside()) && ready(Kind.MONSTER, now)) {
            List<Monster> monsters = level.getEntitiesOfClass(Monster.class, npc.getBoundingBox().inflate(10), Monster::isAlive);
            if (!monsters.isEmpty()) {
                monsters.sort(Comparator.comparingDouble(npc::distanceToSqr));
                Monster m = monsters.get(0);
                return fire(Kind.MONSTER, now, "A " + m.getType().getDescription().getString().toLowerCase()
                        + " is close, about " + Math.round(npc.distanceTo(m)) + " blocks away. " + where + ".");
            }
        }
        String who = audience.getName().getString();
        if (audience.getHealth() < audience.getMaxHealth() * 0.35F && ready(Kind.HURT, now)) {
            return fire(Kind.HURT, now, who + " looks badly hurt ("
                    + Math.round(audience.getHealth()) + " of " + Math.round(audience.getMaxHealth()) + " health).");
        }

        // What the player has been doing lately (mining ores, placing a furnace or bed, advancements).
        if (ready(Kind.ACTIVITY, now)) {
            String doing = PlayerActivity.drain(audience.getUUID(), now);
            if (doing != null) return fire(Kind.ACTIVITY, now, who + " has just: " + doing + ".");
        }

        // Darkness: light level at his feet near black. Speaks once per dark spell, resets when light returns.
        int light = level.getMaxLocalRawBrightness(npc.blockPosition());
        if (light <= 3) {
            if (!inTheDark && ready(Kind.DARK, now)) {
                inTheDark = true;
                int torches = count(audience, Items.TORCH) + count(audience, Items.SOUL_TORCH);
                return fire(Kind.DARK, now, "It is nearly pitch black where you stand (light level " + light + "). "
                        + (torches > 0 ? who + " is carrying " + torches + " torches." : who + " has no torches."));
            }
        } else if (light >= 7) {
            inTheDark = false;
        }

        // The tool in hand: say something once when it is worn, once more when it is about to break.
        ItemStack held = audience.getMainHandItem();
        if (held.isDamageableItem() && held.getMaxDamage() > 0) {
            int left = held.getMaxDamage() - held.getDamageValue();
            int stage = left <= Math.max(6, held.getMaxDamage() / 10) ? 2 : left <= held.getMaxDamage() / 3 ? 1 : 0;
            String key = held.getItem().toString() + "#" + held.getMaxDamage();
            int said = toolStageSaid.getOrDefault(key, 0);
            if (stage > said && ready(Kind.TOOL_WEAR, now)) {
                toolStageSaid.put(key, stage);
                if (toolStageSaid.size() > 16) toolStageSaid.clear();
                String name = held.getHoverName().getString();
                int pct = Math.max(1, left * 100 / held.getMaxDamage());
                return fire(Kind.TOOL_WEAR, now, stage == 2
                        ? who + "'s " + name + " is about to break (" + pct + "% of its life left)."
                        : who + "'s " + name + " is getting worn (" + pct + "% of its life left).");
            }
        } else if (!held.isEmpty()) {
            // switched to something fresh: nothing to track
        }

        // Supplies: torches underground.
        if (ready(Kind.SUPPLIES, now)) {
            boolean underground = !level.canSeeSky(audience.blockPosition().above());
            int torches = count(audience, Items.TORCH) + count(audience, Items.SOUL_TORCH);
            if (underground && torches < 6) {
                return fire(Kind.SUPPLIES, now, who + " is underground with " + (torches == 0 ? "no torches" : "only " + torches + " torches") + " left.");
            }
        }

        // Food.
        if (ready(Kind.HUNGER, now) && audience.getFoodData().getFoodLevel() <= 8 && !hasFood(audience)) {
            return fire(Kind.HUNGER, now, who + " is hungry (" + audience.getFoodData().getFoodLevel() + "/20) and carries no food.");
        }

        // A cold furnace nearby while the player carries something to smelt or burn.
        if (ready(Kind.FURNACE, now) && (carriesAny(audience, "raw_") || count(audience, Items.COAL) > 0 || count(audience, Items.CHARCOAL) > 0)) {
            BlockPos furnace = findColdFurnace(level, audience.blockPosition(), 8);
            if (furnace != null) {
                return fire(Kind.FURNACE, now, "There is a cold, unlit furnace " + Math.round(Math.sqrt(audience.blockPosition().distSqr(furnace)))
                        + " blocks from " + who + ", and they are carrying ore or fuel.");
            }
        }
        return null;
    }

    /** Every slot, including the offhand where players tend to keep torches. */
    private static java.util.List<ItemStack> allStacks(ServerPlayer p) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) out.add(inv.getItem(i));
        out.add(p.getOffhandItem());
        return out;
    }

    private static int count(ServerPlayer p, net.minecraft.world.item.Item item) {
        int n = 0;
        for (ItemStack s : allStacks(p)) if (s.is(item)) n += s.getCount();
        return n;
    }

    private static boolean hasFood(ServerPlayer p) {
        for (ItemStack s : allStacks(p)) if (!s.isEmpty() && s.has(DataComponents.FOOD)) return true;
        return false;
    }

    private static boolean carriesAny(ServerPlayer p, String pathPrefix) {
        for (ItemStack s : allStacks(p)) {
            if (s.isEmpty()) continue;
            var id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
            if (id != null && id.getPath().startsWith(pathPrefix)) return true;
        }
        return false;
    }

    private static @Nullable BlockPos findColdFurnace(ServerLevel level, BlockPos center, int r) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-r, -3, -r), center.offset(r, 3, r))) {
            BlockState st = level.getBlockState(pos);
            if ((st.is(Blocks.FURNACE) || st.is(Blocks.BLAST_FURNACE) || st.is(Blocks.SMOKER))
                    && st.hasProperty(BlockStateProperties.LIT) && !st.getValue(BlockStateProperties.LIT)) {
                return pos.immutable();
            }
        }
        return null;
    }

    /** A scripted campaign beat was just spoken: start the global cooldown so ambient noise waits its turn. */
    public void markScripted(long now) {
        lastRemarkTick = now;
    }

    private boolean ready(Kind kind, long now) {
        Long last = lastByKind.get(kind);
        return last == null || now - last >= kind.cooldownTicks;
    }

    private String fire(Kind kind, long now, String text) {
        lastByKind.put(kind, now);
        lastRemarkTick = now;
        return text;
    }

}
