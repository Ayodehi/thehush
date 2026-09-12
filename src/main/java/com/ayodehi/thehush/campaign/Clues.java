package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.entity.PilgrimEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ShriekParticleOption;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetComponentsFunction;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.LootTableLoadEvent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Things left lying around the world for a careful player: the register, the twin bell, the sculk, the missing villager. */
public final class Clues {
    private Clues() {}

    // ---- items ----

    public static ItemStack registerBook() {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, registerContent());
        return book;
    }

    /** Built without an ItemStack so it can be used while loot tables load, before item components are bound. */
    public static WrittenBookContent registerContent() {
        List<Filterable<Component>> pages = new ArrayList<>();
        pages.add(Filterable.passThrough(Component.literal(
                "Those who crossed, in the order of their going, that the frame remember them though we cannot.")));
        pages.add(Filterable.passThrough(Component.literal("Anselm of the Low Wells\nHesper\nCorvin\nIlse\n")
                .append(Component.literal("Tamsin").withStyle(ChatFormatting.STRIKETHROUGH))
                .append(Component.literal("\n"))
                .append(Component.literal("V.").withStyle(ChatFormatting.BOLD, ChatFormatting.DARK_PURPLE))));
        pages.add(Filterable.passThrough(Component.literal(
                "The frame must be lit from the far side.\n\nWe sent one.\n\nWe did not send him alone.")));
        return new WrittenBookContent(Filterable.passThrough("A Register of Those Who Crossed"), "V.", 2, pages, true);
    }

    public static Component twinBellName() {
        return Component.literal("Bell (V)").withStyle(ChatFormatting.DARK_PURPLE);
    }

    public static ItemStack twinBell() {
        ItemStack bell = new ItemStack(Items.BELL);
        bell.set(DataComponents.CUSTOM_NAME, twinBellName());
        return bell;
    }

    // ---- the village bell ----

    private static final int VILLAGE_BELL_REACH = 48;

    /** A bell in his village: near where he arrived, once he has a village at all. */
    public static boolean isVillageBell(CampaignState s, BlockPos pos) {
        if (s.arrival == null || !s.arrivalVillage) return false;
        return pos.distSqr(new BlockPos(s.arrival[0], s.arrival[1], s.arrival[2])) <= (double) VILLAGE_BELL_REACH * VILLAGE_BELL_REACH;
    }

    /**
     * The bell he sits beside has a letter cast into its rim that no one remembers casting. The first ring
     * shows it; after that it is noticed now and then.
     */
    public static void villageBellTell(net.minecraft.server.MinecraftServer server, ServerPlayer player, CampaignState s, CampaignManager cm) {
        boolean first = !s.flags.contains("bell_v_seen");
        if (!first && player.getRandom().nextInt(6) != 0) return;
        if (first) cm.setFlag(server, "bell_v_seen");
        String text = "Cast into the rim, worn almost smooth, is a single letter: V.";
        player.sendSystemMessage(Component.literal(text).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC), true);
        com.ayodehi.thehush.debug.DebugBridge.get().record("narrator", "", text);
    }

    /** Breaking his village's bell: what drops is the bell with the letter on it. */
    public static void onBellBroken(net.neoforged.neoforge.event.level.BlockDropsEvent event) {
        net.minecraft.server.MinecraftServer server = event.getLevel().getServer();
        CampaignManager cm = CampaignManager.get();
        if (server == null || !cm.active(server) || event.getLevel() != server.overworld()) return;
        if (!isVillageBell(cm.state(server), event.getPos())) return;
        for (net.minecraft.world.entity.item.ItemEntity drop : event.getDrops()) {
            if (drop.getItem().is(Items.BELL)) drop.getItem().set(DataComponents.CUSTOM_NAME, twinBellName());
        }
    }

    /** Stronghold libraries may hold the register; ancient city chests may hold the twin bell. */
    public static void onLootTableLoad(LootTableLoadEvent event) {
        String id = event.getName().toString();
        if (id.equals("minecraft:chests/stronghold_library")) {
            event.getTable().addPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                    .when(LootItemRandomChanceCondition.randomChance(0.25F))
                    .add(LootItem.lootTableItem(Items.WRITTEN_BOOK).apply(SetComponentsFunction.setComponent(
                            DataComponents.WRITTEN_BOOK_CONTENT, registerContent())))
                    .build());
        } else if (id.equals("minecraft:chests/ancient_city")) {
            event.getTable().addPool(LootPool.lootPool().setRolls(ConstantValue.exactly(1))
                    .when(LootItemRandomChanceCondition.randomChance(0.3F))
                    .add(LootItem.lootTableItem(Items.BELL).apply(SetComponentsFunction.setComponent(
                            DataComponents.CUSTOM_NAME, twinBellName())))
                    .build());
        }
    }

    // ---- recorded block placement (undone at the ending) ----

    public static void setRecorded(ServerLevel level, BlockPos pos, BlockState state, CampaignState s) {
        BlockState before = level.getBlockState(pos);
        Identifier id = BuiltInRegistries.BLOCK.getKey(before.getBlock());
        // "x,y,z,block[,prop=value;prop=value]": the properties keep a hanging lantern hanging when it comes back.
        StringBuilder props = new StringBuilder();
        before.getValues().forEach(v -> props.append(props.isEmpty() ? "" : ";").append(v.toString()));
        s.placed.add(pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + (id == null ? "minecraft:air" : id)
                + (props.isEmpty() ? "" : "," + props));
        level.setBlock(pos, state, Block.UPDATE_ALL);
    }

    /** The recorded block's state: its default, with any recorded properties that it still has applied. */
    private static BlockState recordedState(String[] parts) {
        Identifier id = Identifier.tryParse(parts[3]);
        Block block = id == null ? Blocks.AIR : BuiltInRegistries.BLOCK.getValue(id);
        BlockState state = (block == null ? Blocks.AIR : block).defaultBlockState();
        if (parts.length < 5) return state;
        for (String kv : parts[4].split(";")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            Property<?> property = state.getBlock().getStateDefinition().getProperty(kv.substring(0, eq));
            if (property != null) state = withProperty(state, property, kv.substring(eq + 1));
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        return property.getValue(value).map(v -> state.setValue(property, v)).orElse(state);
    }

    public static void restoreRecorded(ServerLevel level, CampaignState s) {
        for (String entry : s.placed) {
            String[] parts = entry.split(",");
            if (parts.length < 4) continue;
            try {
                BlockPos pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                level.setBlock(pos, recordedState(parts), Block.UPDATE_ALL);
            } catch (NumberFormatException ignored) {
                // skip a bad line
            }
        }
        s.placed.clear();
    }

    // ---- the stronghold ----

    public static @Nullable StructureStart strongholdNear(ServerLevel level, BlockPos near) {
        Structure stronghold = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(BuiltinStructures.STRONGHOLD);
        if (stronghold == null) return null;
        StructureStart start = level.structureManager().getStructureAt(near, stronghold);
        if (start.isValid()) return start;
        BlockPos found = level.findNearestMapStructure(net.minecraft.tags.StructureTags.EYE_OF_ENDER_LOCATED, near, 100, false);
        if (found == null) return null;
        start = level.structureManager().getStructureAt(found, stronghold);
        return start.isValid() ? start : null;
    }

    public static @Nullable BoundingBox libraryBox(StructureStart stronghold) {
        for (StructurePiece piece : stronghold.getPieces()) {
            if (piece instanceof StrongholdPieces.Library) return piece.getBoundingBox();
        }
        return null;
    }

    /** A lectern with the register against a bookshelf on the library floor. Returns where it went. */
    public static @Nullable BlockPos placeRegisterLectern(ServerLevel level, BoundingBox box, CampaignState s) {
        int floor = box.minY() + 1;
        for (BlockPos pos : BlockPos.betweenClosed(box.minX() + 1, floor, box.minZ() + 1, box.maxX() - 1, floor, box.maxZ() - 1)) {
            if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) continue;
            if (!level.getBlockState(pos.below()).isSolid()) continue;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (level.getBlockState(pos.relative(d)).is(Blocks.BOOKSHELF)) {
                    BlockState lectern = Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, d.getOpposite());
                    setRecorded(level, pos, lectern, s);
                    LecternBlock.tryPlaceBook(null, level, pos, level.getBlockState(pos), registerBook());
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    /** A corner of the library floor, for the Pilgrim that was not there the first time. */
    public static @Nullable BlockPos libraryCorner(ServerLevel level, BoundingBox box) {
        int floor = box.minY() + 1;
        int[][] corners = {{box.minX() + 1, box.minZ() + 1}, {box.maxX() - 1, box.minZ() + 1}, {box.minX() + 1, box.maxZ() - 1}, {box.maxX() - 1, box.maxZ() - 1}};
        for (int[] c : corners) {
            for (int dx = 0; dx <= 2; dx++) {
                for (int dz = 0; dz <= 2; dz++) {
                    int x = c[0] + (c[0] == box.minX() + 1 ? dx : -dx);
                    int z = c[1] + (c[1] == box.minZ() + 1 ? dz : -dz);
                    BlockPos p = new BlockPos(x, floor, z);
                    if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolid()) return p;
                }
            }
        }
        return null;
    }

    // ---- the village ----

    /** The well nearest the arrival point: a water block ringed by stone. */
    public static @Nullable BlockPos findWell(ServerLevel level, BlockPos around, int r) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-r, -6, -r), around.offset(r, 6, r))) {
            if (!level.getBlockState(pos).is(Blocks.WATER)) continue;
            int solid = 0;
            for (Direction d : Direction.Plane.HORIZONTAL) if (level.getBlockState(pos.relative(d)).isSolid()) solid++;
            if (solid < 3) continue;
            double dist = pos.distSqr(around);
            if (dist < bestD) {
                bestD = dist;
                best = pos.immutable();
            }
        }
        return best;
    }

    /** Day 3: a patch of sculk on the well, or on the ground beside him if there is no well. */
    public static void growSculkOnWell(ServerLevel level, BlockPos arrival, CampaignState s) {
        BlockPos well = findWell(level, arrival, 24);
        BlockPos anchor;
        if (well != null) {
            anchor = null;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos rim = well.relative(d).above();
                if (level.getBlockState(rim).isSolid()) {
                    anchor = rim;
                    break;
                }
            }
            if (anchor == null) anchor = well.relative(Direction.NORTH);
        } else {
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, arrival.getX() + 3, arrival.getZ() + 3) - 1;
            anchor = new BlockPos(arrival.getX() + 3, y, arrival.getZ() + 3);
        }
        setRecorded(level, anchor, Blocks.SCULK.defaultBlockState(), s);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos side = anchor.relative(d);
            if (level.getBlockState(side).isSolid() && level.getBlockState(side.above()).isAir()) {
                setRecorded(level, side.above(), Blocks.SCULK_VEIN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true), s);
            }
        }
        if (level.getBlockState(anchor.above()).isAir()) {
            setRecorded(level, anchor.above(), Blocks.SCULK_VEIN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true), s);
        }
    }

    /** Day 4: one villager is simply gone. Returns how the Traveller thinks of them. */
    public static @Nullable String removeAVillager(ServerLevel level, BlockPos arrival) {
        List<Villager> near = level.getEntitiesOfClass(Villager.class, new AABB(arrival).inflate(32, 12, 32),
                v -> v.isAlive() && !(v instanceof AiVillagerEntity) && !(v instanceof PilgrimEntity) && !v.isBaby());
        if (near.isEmpty()) return null;
        Villager v = near.get(level.getRandom().nextInt(near.size()));
        String who = v.getDisplayName().getString().toLowerCase(java.util.Locale.ROOT);
        BlockPos p = v.blockPosition();
        v.discard();
        return "the " + who + " who lived near (" + p.getX() + ", " + p.getZ() + ")";
    }

    /** Sculk with a few sensors around a point, on the surface. */
    public static void placeSensorPatch(ServerLevel level, BlockPos around, int count, CampaignState s) {
        var random = level.getRandom();
        for (int i = 0; i < count * 3 && count > 0; i++) {
            int x = around.getX() + random.nextInt(13) - 6;
            int z = around.getZ() + random.nextInt(13) - 6;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, y - 1, z);
            if (!level.getBlockState(ground).isSolid() || !level.getBlockState(ground.above()).isAir()) continue;
            setRecorded(level, ground, Blocks.SCULK.defaultBlockState(), s);
            setRecorded(level, ground.above(), Blocks.SCULK_SENSOR.defaultBlockState(), s);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos side = ground.relative(d);
                if (level.getBlockState(side).isSolid() && random.nextBoolean()) setRecorded(level, side, Blocks.SCULK.defaultBlockState(), s);
            }
            count--;
        }
    }

    public static @Nullable BlockPos placeShrieker(ServerLevel level, BlockPos around, CampaignState s) {
        var random = level.getRandom();
        for (int i = 0; i < 20; i++) {
            int x = around.getX() + random.nextInt(9) - 4;
            int z = around.getZ() + random.nextInt(9) - 4;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos ground = new BlockPos(x, y - 1, z);
            if (!level.getBlockState(ground).isSolid() || !level.getBlockState(ground.above()).isAir()) continue;
            setRecorded(level, ground, Blocks.SCULK.defaultBlockState(), s);
            BlockPos at = ground.above();
            setRecorded(level, at, Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, false), s);
            return at;
        }
        return null;
    }

    /** The first warning: a shriek in the night and three seconds of darkness for everyone near. */
    public static void fireShrieker(ServerLevel level, BlockPos at) {
        level.playSound(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, 3.0F, 0.9F);
        level.sendParticles(new ShriekParticleOption(0), at.getX() + 0.5, at.getY() + 1.0, at.getZ() + 0.5, 1, 0, 0, 0, 0);
        BlockState st = level.getBlockState(at);
        if (st.is(Blocks.SCULK_SHRIEKER)) {
            level.setBlock(at, st.setValue(SculkShriekerBlock.SHRIEKING, true), Block.UPDATE_ALL);
            level.scheduleTick(at, Blocks.SCULK_SHRIEKER, 40);
        }
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(at.getX(), at.getY(), at.getZ()) <= 24 * 24) {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 60, 0, false, false));
            }
        }
    }

    /** Put an item into the nearest chest (or a fresh one) within a few blocks of a point. */
    public static @Nullable BlockPos stockChest(ServerLevel level, BlockPos near, ItemStack stack, CampaignState s) {
        for (BlockPos pos : BlockPos.betweenClosed(near.offset(-8, -3, -8), near.offset(8, 3, 8))) {
            if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                    if (chest.getItem(slot).isEmpty()) {
                        chest.setItem(slot, stack);
                        chest.setChanged();
                        return pos.immutable();
                    }
                }
            }
        }
        for (BlockPos pos : BlockPos.betweenClosed(near.offset(-4, -2, -4), near.offset(4, 2, 4))) {
            if (level.getBlockState(pos).isAir() && level.getBlockState(pos.below()).isSolid() && !pos.equals(near)) {
                setRecorded(level, pos, Blocks.CHEST.defaultBlockState(), s);
                if (level.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                    chest.setItem(13, stack);
                    chest.setChanged();
                }
                return pos.immutable();
            }
        }
        return null;
    }

    /**
     * The mark at the frame's foot: a 3x3 of polished deepslate on the floor, a pillar of the builders'
     * stone with nothing on top of it, a soul lantern at two corners, and a chest with the bell beside it.
     * Returns where the bell belongs (the empty top of the pillar).
     */
    public static @Nullable BlockPos buildPedestal(ServerLevel level, BoundingBox frame, CampaignState s) {
        BlockPos c = frame.getCenter();
        // The frame is a vertical plane; "in front" is across its thin axis. Walk out a few blocks on both
        // sides and drop to the ground there; the lowest ground found is the city floor, not a ledge.
        boolean thinInX = frame.getXSpan() <= frame.getZSpan();
        BlockPos floor = null;
        for (int out : new int[] {3, 5, 7, 2}) {
            for (int sign : new int[] {1, -1}) {
                int x = thinInX ? c.getX() + sign * out : c.getX();
                int z = thinInX ? c.getZ() : c.getZ() + sign * out;
                for (int y = frame.maxY(); y > level.getMinY() + 1; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.getBlockState(p).isSolid() && level.getBlockState(p.above()).isAir() && level.getBlockState(p.above(2)).isAir()) {
                        if (floor == null || p.getY() < floor.getY()) floor = p;
                        break;
                    }
                }
            }
            if (floor != null && floor.getY() < frame.minY() - 3) break; // found real ground below the frame
        }
        if (floor == null) return null;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                setRecorded(level, floor.offset(dx, 0, dz), Blocks.POLISHED_DEEPSLATE.defaultBlockState(), s);
                setRecorded(level, floor.offset(dx, 1, dz), Blocks.AIR.defaultBlockState(), s);
                setRecorded(level, floor.offset(dx, 2, dz), Blocks.AIR.defaultBlockState(), s);
            }
        }
        setRecorded(level, floor.above(), Blocks.REINFORCED_DEEPSLATE.defaultBlockState(), s);
        setRecorded(level, floor.offset(-1, 1, -1), Blocks.SOUL_LANTERN.defaultBlockState(), s);
        setRecorded(level, floor.offset(1, 1, 1), Blocks.SOUL_LANTERN.defaultBlockState(), s);
        BlockPos chestPos = thinInX ? floor.offset(0, 1, 2) : floor.offset(2, 1, 0);
        setRecorded(level, chestPos, Blocks.CHEST.defaultBlockState(), s);
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
            chest.setItem(13, twinBell());
            chest.setChanged();
        }
        return floor.above(2).immutable();
    }

    /** Put back recorded blocks within a few blocks of a point (an old pedestal) and forget them. */
    public static void restoreRecordedNear(ServerLevel level, CampaignState s, BlockPos center, int r) {
        List<String> keep = new ArrayList<>();
        for (String entry : s.placed) {
            String[] parts = entry.split(",");
            try {
                BlockPos pos = new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                if (pos.distSqr(center) > (long) r * r) {
                    keep.add(entry);
                    continue;
                }
                level.setBlock(pos, recordedState(parts), Block.UPDATE_ALL);
            } catch (RuntimeException e) {
                keep.add(entry);
            }
        }
        s.placed.clear();
        s.placed.addAll(keep);
    }

    /** The reinforced deepslate ring at the heart of an ancient city: its bounding box, or null. */
    public static @Nullable BoundingBox findFrame(ServerLevel level, BoundingBox city) {
        BlockPos c = city.getCenter();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int found = 0;
        for (BlockPos pos : BlockPos.betweenClosed(c.getX() - 64, city.minY(), c.getZ() - 64, c.getX() + 64, city.maxY(), c.getZ() + 64)) {
            if (!level.getBlockState(pos).is(Blocks.REINFORCED_DEEPSLATE)) continue;
            found++;
            minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
            minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
        }
        if (found < 8) return null;
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    public static boolean darkFloor(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).isAir() && level.getBlockState(pos.above()).isAir()
                && level.getBlockState(pos.below()).isSolid() && level.getMaxLocalRawBrightness(pos) <= 3;
    }

    static boolean hasProperty(BlockState st) {
        return st.hasProperty(BlockStateProperties.LIT);
    }
}
