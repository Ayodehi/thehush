package com.ayodehi.thehush.campaign;

import com.ayodehi.thehush.TheHushMod;
import com.ayodehi.thehush.entity.AiVillagerEntity;
import com.ayodehi.thehush.entity.PilgrimEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SculkShriekerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * The far future: the village grown over with sculk under no sky, the well, and the throat beneath it.
 * A flat sculk world from the datapack; the village and the chamber are built in code the first time
 * anyone crosses, at fixed coordinates so the encounter knows where everything is.
 */
public final class Quiet {
    public static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            Identifier.fromNamespaceAndPath(TheHushMod.MODID, "quiet"));
    /** Feet level on the sculk surface (bedrock 1 + deepslate 60 + sculk 1). */
    public static final int SURFACE = 62;
    public static final BlockPos WELL = new BlockPos(0, SURFACE, 0);
    public static final BlockPos ARRIVAL = new BlockPos(0, SURFACE, -30);
    /** Interior of the throat. */
    public static final BoundingBox THROAT = new BoundingBox(-15, 30, 0, 15, 40, 35);
    public static final BlockPos HEART = new BlockPos(0, 30, 30);
    public static final BlockPos HEART_BELL = new BlockPos(0, 32, 30);
    public static final List<BlockPos> WARDEN_SPAWNS = List.of(
            new BlockPos(-14, 30, 18), new BlockPos(14, 30, 18), new BlockPos(0, 30, 34),
            new BlockPos(-14, 30, 4), new BlockPos(14, 30, 4));
    private static final int[][] HOUSES = {{-14, -12}, {12, -14}, {-16, 10}, {14, 12}, {0, -22}, {-2, 20}};

    private Quiet() {}

    public static @Nullable ServerLevel level(MinecraftServer server) {
        return server.getLevel(DIMENSION);
    }

    public static boolean isQuiet(Level level) {
        return level.dimension() == DIMENSION;
    }

    public static boolean inThroat(BlockPos pos) {
        return THROAT.isInside(pos);
    }

    // ---- crossing ----

    /** Bring the chosen (and him, and anyone standing with them) through the frame. */
    public static void enter(MinecraftServer server, ServerPlayer chosen, @Nullable AiVillagerEntity traveller, List<ServerPlayer> companions, CampaignState s) {
        ServerLevel quiet = level(server);
        if (quiet == null) {
            TheHushMod.LOGGER.error("The Quiet dimension is missing; is the datapack loaded?");
            return;
        }
        if (!s.quietBuilt) {
            build(quiet);
            s.quietBuilt = true;
            s.save();
        }
        double x = ARRIVAL.getX() + 0.5, y = ARRIVAL.getY(), z = ARRIVAL.getZ() + 0.5;
        chosen.teleportTo(quiet, x, y, z, Set.<Relative>of(), 0F, 0F, true);
        int i = 1;
        for (ServerPlayer p : companions) {
            if (p == chosen) continue;
            p.teleportTo(quiet, x + (i % 2 == 0 ? i : -i), y, z, Set.<Relative>of(), 0F, 0F, true);
            i++;
        }
        if (traveller != null && traveller.isAlive()) {
            traveller.stopLeading();
            traveller.teleportTo(quiet, x + 1.5, y, z - 1, Set.<Relative>of(), 0F, 0F, false);
            traveller.getNavigation().stop();
        }
    }

    // ---- building ----

    public static void build(ServerLevel level) {
        RandomSource random = level.getRandom();
        for (int cx = -4; cx <= 4; cx++) for (int cz = -4; cz <= 4; cz++) level.getChunk(cx, cz);

        // Ground detail: veins and catalysts scattered over the village.
        for (int i = 0; i < 60; i++) {
            int x = random.nextInt(81) - 40, z = random.nextInt(81) - 40;
            BlockPos ground = new BlockPos(x, SURFACE - 1, z);
            if (random.nextInt(8) == 0) set(level, ground, Blocks.SCULK_CATALYST.defaultBlockState());
            else set(level, ground.above(), Blocks.SCULK_VEIN.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
        }
        for (int[] h : HOUSES) house(level, h[0], h[1], random);
        // Lantern posts along the way in: not many, and not near the well.
        int[][] posts = {{-6, -8}, {6, -8}, {-8, 6}, {8, 6}, {0, -16}, {-3, 14}};
        for (int[] p : posts) {
            BlockPos base = new BlockPos(p[0], SURFACE, p[1]);
            set(level, base, Blocks.DEEPSLATE_BRICK_WALL.defaultBlockState());
            set(level, base.above(), Blocks.DEEPSLATE_BRICK_WALL.defaultBlockState());
            set(level, base.above(2), Blocks.SOUL_LANTERN.defaultBlockState());
        }
        // The well: a stone ring on the sculk with a black shaft in the middle.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                set(level, WELL.offset(dx, 0, dz), Blocks.STONE_BRICKS.defaultBlockState());
                set(level, WELL.offset(dx, -1, dz), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        // The throat: hollow it out of the deepslate, line it with sculk.
        BoundingBox shell = new BoundingBox(THROAT.minX() - 1, THROAT.minY() - 1, THROAT.minZ() - 1, THROAT.maxX() + 1, THROAT.maxY() + 1, THROAT.maxZ() + 1);
        for (BlockPos pos : BlockPos.betweenClosed(shell.minX(), shell.minY(), shell.minZ(), shell.maxX(), shell.maxY(), shell.maxZ())) {
            boolean wall = pos.getX() == shell.minX() || pos.getX() == shell.maxX() || pos.getY() == shell.minY()
                    || pos.getY() == shell.maxY() || pos.getZ() == shell.minZ() || pos.getZ() == shell.maxZ();
            set(level, pos, wall ? Blocks.SCULK.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
        // The shaft from the well down to the throat, with a ladder on its north wall.
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        for (int y = THROAT.minY(); y <= SURFACE - 1; y++) {
            BlockPos p = new BlockPos(0, y, 0);
            set(level, p, ladder);
            if (y > THROAT.maxY()) {
                set(level, p.north(), Blocks.SCULK.defaultBlockState());
                set(level, p.south(), Blocks.DEEPSLATE.defaultBlockState());
                set(level, p.east(), Blocks.DEEPSLATE.defaultBlockState());
                set(level, p.west(), Blocks.DEEPSLATE.defaultBlockState());
            }
        }
        // Ears: sensors on the floor in a grid; shriekers that never summon; note blocks set into the walls.
        for (int x = -12; x <= 12; x += 6) {
            for (int z = 6; z <= 30; z += 6) {
                if (x == 0 && z == 30) continue;
                set(level, new BlockPos(x, THROAT.minY(), z), Blocks.SCULK_SENSOR.defaultBlockState());
            }
        }
        int[][] shriekers = {{-10, 10}, {10, 10}, {-10, 22}, {10, 22}};
        for (int[] sp : shriekers) {
            set(level, new BlockPos(sp[0], THROAT.minY(), sp[1]), Blocks.SCULK_SHRIEKER.defaultBlockState().setValue(SculkShriekerBlock.CAN_SUMMON, false));
        }
        for (int z = 8; z <= 32; z += 8) {
            set(level, new BlockPos(THROAT.minX() - 1, THROAT.minY() + 1, z), Blocks.NOTE_BLOCK.defaultBlockState());
            set(level, new BlockPos(THROAT.maxX() + 1, THROAT.minY() + 1, z), Blocks.NOTE_BLOCK.defaultBlockState());
        }
        // The heart: a pedestal of the builders' stone with the bell on it.
        set(level, HEART, Blocks.REINFORCED_DEEPSLATE.defaultBlockState());
        set(level, HEART.above(), Blocks.REINFORCED_DEEPSLATE.defaultBlockState());
        set(level, HEART_BELL, Blocks.BELL.defaultBlockState());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            set(level, HEART.relative(d), Blocks.SCULK_CATALYST.defaultBlockState());
        }
        // Pilgrims in the shadows of the village.
        int[][] pilgrims = {{-10, -16}, {16, -8}, {-19, 4}, {18, 16}, {5, 24}, {-6, -26}};
        for (int[] p : pilgrims) PilgrimEntity.place(level, new BlockPos(p[0], SURFACE, p[1]));
        TheHushMod.LOGGER.info("Built the Quiet");
    }

    private static void house(ServerLevel level, int x0, int z0, RandomSource random) {
        int y0 = SURFACE;
        for (int y = y0; y < y0 + 4; y++) {
            for (int dx = 0; dx < 7; dx++) {
                for (int dz = 0; dz < 7; dz++) {
                    boolean edge = dx == 0 || dx == 6 || dz == 0 || dz == 6;
                    if (!edge) continue;
                    BlockPos p = new BlockPos(x0 + dx, y, z0 + dz);
                    // A doorway on the side nearest the well, and a window or two.
                    boolean door = (y == y0 || y == y0 + 1) && dx == 3 && (z0 < 0 ? dz == 6 : dz == 0);
                    boolean window = y == y0 + 2 && ((dx == 1 || dx == 5) && (dz == 0 || dz == 6));
                    if (door || window) {
                        set(level, p, Blocks.AIR.defaultBlockState());
                        continue;
                    }
                    int r = random.nextInt(10);
                    BlockState wall = r < 6 ? Blocks.DEEPSLATE_BRICKS.defaultBlockState()
                            : r < 8 ? Blocks.CRACKED_DEEPSLATE_BRICKS.defaultBlockState()
                            : r < 9 ? Blocks.SCULK.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    set(level, p, wall);
                }
            }
        }
        for (int dx = -1; dx <= 7; dx++) {
            for (int dz = -1; dz <= 7; dz++) {
                if (random.nextInt(7) == 0) continue; // holes in the roof
                set(level, new BlockPos(x0 + dx, y0 + 4, z0 + dz), Blocks.DEEPSLATE_TILES.defaultBlockState());
            }
        }
        // Something in the corner of one room: a bed frame gone to sculk, a chest, a lantern that still burns.
        if (random.nextBoolean()) set(level, new BlockPos(x0 + 1, y0, z0 + 1), Blocks.CHEST.defaultBlockState());
        if (random.nextInt(3) == 0) set(level, new BlockPos(x0 + 5, y0 + 3, z0 + 5), Blocks.SOUL_LANTERN.defaultBlockState());
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }
}
