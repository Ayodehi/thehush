package com.ayodehi.thehush.campaign;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Answers trigger questions about one player from the live world; each answer is computed once per snapshot. */
final class WorldSnapshot implements CampaignStage.Snapshot, CampaignDefinition.PlaceSnapshot {
    private final ServerPlayer player;
    private final ServerLevel level;
    private final Set<String> flags;
    private final long daysOnStage;
    private final Map<String, Boolean> cache = new HashMap<>();

    WorldSnapshot(ServerPlayer player, Set<String> flags, long daysOnStage) {
        this.player = player;
        this.level = (ServerLevel) player.level();
        this.flags = flags;
        this.daysOnStage = daysOnStage;
    }

    @Override
    public boolean hasAdvancement(String id) {
        return cache.computeIfAbsent("adv:" + id, k -> {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) return false;
            AdvancementHolder holder = level.getServer().getAdvancements().get(rl);
            return holder != null && player.getAdvancements().getOrStartProgress(holder).isDone();
        });
    }

    @Override
    public boolean inStructure(String id) {
        return cache.computeIfAbsent("structure:" + id, k -> {
            if (id.startsWith("#")) {
                Identifier tag = Identifier.tryParse(id.substring(1));
                if (tag == null) return false;
                TagKey<Structure> key = TagKey.create(Registries.STRUCTURE, tag);
                return level.structureManager().getStructureWithPieceAt(player.blockPosition(), key).isValid();
            }
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) return false;
            ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, rl);
            return level.structureManager().getStructureWithPieceAt(player.blockPosition(), h -> h.is(key)).isValid();
        });
    }

    /** Inside a particular piece of any structure, by the piece type's registry path (e.g. "shli"). */
    @Override
    public boolean inPiece(String piecePath) {
        return cache.computeIfAbsent("piece:" + piecePath, k -> {
            BlockPos pos = player.blockPosition();
            StructureStart start = level.structureManager().getStructureWithPieceAt(pos, h -> true);
            if (!start.isValid()) return false;
            for (StructurePiece piece : start.getPieces()) {
                if (!piece.getBoundingBox().isInside(pos)) continue;
                Identifier id = BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.getType());
                if (id != null && id.getPath().equalsIgnoreCase(piecePath)) return true;
            }
            return false;
        });
    }

    @Override
    public int y() {
        return player.getBlockY();
    }

    @Override
    public boolean blockNear(String id, int radius) {
        return cache.computeIfAbsent("block:" + id + ":" + radius, k -> {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) return false;
            Block block = BuiltInRegistries.BLOCK.getValue(rl);
            if (block == null) return false;
            BlockPos c = player.blockPosition();
            int vr = Math.min(radius, 4);
            for (BlockPos pos : BlockPos.betweenClosed(c.offset(-radius, -vr, -radius), c.offset(radius, vr, radius))) {
                if (level.getBlockState(pos).is(block)) return true;
            }
            return false;
        });
    }

    @Override
    public boolean entityNear(String id, int radius) {
        return cache.computeIfAbsent("entity:" + id + ":" + radius, k -> {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) return false;
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(rl);
            if (type == null) return false;
            return !level.getEntities(player, player.getBoundingBox().inflate(radius), e -> e.getType() == type && e.isAlive()).isEmpty();
        });
    }

    private static String pieceName(String path) {
        return switch (path.toLowerCase(java.util.Locale.ROOT)) {
            case "shli" -> "the library";
            case "shpr" -> "the portal room";
            case "shcc" -> "a chest corridor";
            case "shpc" -> "a prison cell";
            case "shsd" -> "a spiral stair";
            case "shs" -> "a straight corridor";
            case "shfc" -> "a five-way crossing";
            case "shrc" -> "a room";
            case "shlt" -> "a left turn";
            case "shrt" -> "a right turn";
            case "shstart" -> "the stair down";
            default -> path;
        };
    }

    /** One line about where the chosen is, for the guide heartbeat. */
    String describeWhere() {
        BlockPos pos = player.blockPosition();
        StringBuilder sb = new StringBuilder();
        String dim = level.dimension().identifier().getPath().replace('_', ' ');
        sb.append("in ").append(dim.equals("overworld") ? "the Overworld" : "the " + dim);
        level.getBiome(pos).unwrapKey().ifPresent(b -> sb.append(", ").append(b.identifier().getPath().replace('_', ' ')));
        StructureStart start = level.structureManager().getStructureWithPieceAt(pos, h -> true);
        if (start.isValid()) {
            Identifier sid = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(start.getStructure());
            sb.append(", inside a ").append(sid == null ? "structure" : sid.getPath().replace('_', ' '));
            for (StructurePiece piece : start.getPieces()) {
                if (piece.getBoundingBox().isInside(pos)) {
                    Identifier pid = BuiltInRegistries.STRUCTURE_PIECE.getKey(piece.getType());
                    if (pid != null) sb.append(" (").append(pieceName(pid.getPath())).append(")");
                    break;
                }
            }
        }
        sb.append(", at height ").append(pos.getY());
        sb.append(level.canSeeSky(pos.above()) ? ", under the open sky" : ", underground or under a roof");
        int light = level.getMaxLocalRawBrightness(pos);
        sb.append(light <= 3 ? ", nearly dark" : light <= 7 ? ", dim" : ", well lit");
        long t = level.getOverworldClockTime() % 24000L;
        sb.append(t < 12000 ? ", daytime" : t < 13000 ? ", dusk" : t < 23000 ? ", night" : ", dawn");
        return sb.toString();
    }

    @Override
    public boolean inBiome(String id) {
        return cache.computeIfAbsent("biome:" + id, k -> {
            Identifier rl = Identifier.tryParse(id);
            if (rl == null) return false;
            ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, rl);
            return level.getBiome(player.blockPosition()).is(key);
        });
    }

    @Override
    public boolean inDimension(String id) {
        return level.dimension().identifier().toString().equals(id);
    }

    @Override
    public boolean carries(String itemId) {
        return cache.computeIfAbsent("item:" + itemId, k -> {
            Identifier rl = Identifier.tryParse(itemId);
            if (rl == null) return false;
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack s = inv.getItem(i);
                if (!s.isEmpty() && rl.equals(BuiltInRegistries.ITEM.getKey(s.getItem()))) return true;
            }
            ItemStack off = player.getOffhandItem();
            return !off.isEmpty() && rl.equals(BuiltInRegistries.ITEM.getKey(off.getItem()));
        });
    }

    @Override
    public boolean hasFlag(String flag) {
        return flags.contains(flag);
    }

    @Override
    public long daysOnStage() {
        return daysOnStage;
    }

    BlockPos pos() {
        return player.blockPosition();
    }
}
