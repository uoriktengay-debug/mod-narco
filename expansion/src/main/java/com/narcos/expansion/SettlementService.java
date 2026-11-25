package com.narcos.expansion;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class SettlementService {
    private static final Map<UUID, String> LAST_REGION = new HashMap<>();

    private static final String[] VILLAGES = {
            "Берёзовка", "Сосновка", "Луговое", "Речное", "Ключи", "Тихая Балка",
            "Малые Озёрки", "Ясная Поляна", "Старый Мост", "Зелёный Яр"
    };
    private static final String[] CITIES = {
            "Каменоград", "Северск", "Лесогорск", "Рудный", "Новолеск", "Приморск",
            "Светлогорье", "Высокоград", "Озерск", "Гранитск", "Зареченск", "Медноград"
    };
    private static final String[] CAPITALS = {
            "Новоград", "Вершинск", "Североград", "Гранд-Сити", "Столичный"
    };

    private SettlementService() { }

    public static void updatePlayer(MinecraftServer server, ServerPlayerEntity player, long tick) {
        ServerWorld world = player.getServerWorld();
        if (world != server.getOverworld()) return;

        BlockPos pos = player.getBlockPos();
        SettlementInfo info = inspect(world, pos);
        String region = regionKey(pos);
        String last = LAST_REGION.put(player.getUuid(), region);
        if (!region.equals(last)) {
            player.sendMessage(Text.literal(info.message()).formatted(Formatting.GOLD), false);
        }

        if (info.tier >= 2 && tick % 1200 == 0) {
            ExpansionState state = ExpansionState.of(server);
            String key = "building_" + region;
            if (!state.hasBuilt(key) && buildMultiStoreyHouse(world, pos, info.tier)) {
                state.markBuilt(key);
                player.sendMessage(Text.literal("В районе построен новый многоэтажный дом.")
                        .formatted(Formatting.GREEN), false);
            }
        }
    }

    private static SettlementInfo inspect(ServerWorld world, BlockPos pos) {
        int population = world.getEntitiesByClass(VillagerEntity.class,
                net.minecraft.util.math.Box.of(pos.toCenterPos(), 192, 64, 192), entity -> true).size();

        BlockPos capitalCenter = world.getSpawnPos();
        double distance = Math.sqrt(Math.pow(pos.getX() - capitalCenter.getX(), 2)
                + Math.pow(pos.getZ() - capitalCenter.getZ(), 2));
        String capitalName = choose(world.getSeed(), CAPITALS, 0, 0, 17L);
        int rx = Math.floorDiv(pos.getX(), 2048);
        int rz = Math.floorDiv(pos.getZ(), 2048);
        String settlementName;

        if (population == 0) {
            return new SettlementInfo(0, "окрестности", "Безымянные земли", capitalName, distance, population);
        }
        if (distance <= 768 && population >= 4) {
            return new SettlementInfo(3, "столица", capitalName, capitalName, distance, population);
        }
        if (population >= 8) {
            settlementName = choose(world.getSeed(), CITIES, rx, rz, 132897987541L);
            return new SettlementInfo(2, "город", settlementName, capitalName, distance, population);
        }
        settlementName = choose(world.getSeed(), VILLAGES, rx, rz, 341873128712L);
        return new SettlementInfo(1, "село", settlementName, capitalName, distance, population);
    }

    private static String choose(long seed, String[] names, int x, int z, long salt) {
        long value = seed ^ ((long) x * 341873128712L) ^ ((long) z * 132897987541L) ^ salt;
        java.util.Random random = new java.util.Random(value);
        return names[random.nextInt(names.length)];
    }

    private static String regionKey(BlockPos pos) {
        return Math.floorDiv(pos.getX(), 2048) + "_" + Math.floorDiv(pos.getZ(), 2048);
    }

    private static boolean buildMultiStoreyHouse(ServerWorld world, BlockPos around, int tier) {
        int floors = tier >= 3 ? 4 : 3;
        int totalHeight = floors * 4 + 2;
        Random random = world.getRandom();
        BlockPos base = null;

        for (int attempt = 0; attempt < 30 && base == null; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            int distance = 28 + random.nextInt(20);
            int x = around.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = around.getZ() + (int) Math.round(Math.sin(angle) * distance);
            int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (y <= world.getBottomY() || y + totalHeight >= world.getTopY()) continue;

            boolean clear = true;
            for (int dx = -4; dx <= 4 && clear; dx++) {
                for (int dz = -4; dz <= 4 && clear; dz++) {
                    if (world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz) != y) {
                        clear = false;
                        break;
                    }
                    BlockPos ground = new BlockPos(x + dx, y - 1, z + dz);
                    if (!world.getBlockState(ground).isSolidBlock(world, ground)) {
                        clear = false;
                        break;
                    }
                    for (int dy = 0; dy < totalHeight; dy++) {
                        BlockPos check = new BlockPos(x + dx, y + dy, z + dz);
                        if (!world.getBlockState(check).isAir()
                                && !world.getBlockState(check).isReplaceable()) {
                            clear = false;
                            break;
                        }
                    }
                }
            }
            if (clear) base = new BlockPos(x, y, z);
        }

        if (base == null) return false;
        buildBuilding(world, base, floors, tier);
        return true;
    }

    private static void buildBuilding(ServerWorld world, BlockPos base, int floors, int tier) {
        Block wall = tier >= 3 ? Blocks.POLISHED_ANDESITE : Blocks.STONE_BRICKS;
        Block trim = tier >= 3 ? Blocks.QUARTZ_BLOCK : Blocks.SMOOTH_STONE;
        Block glass = tier >= 3 ? Blocks.LIGHT_BLUE_STAINED_GLASS : Blocks.GLASS;

        for (int level = 0; level < floors; level++) {
            int y = level * 4;
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    world.setBlockState(base.add(dx, y, dz), Blocks.OAK_PLANKS.getDefaultState(), Block.NOTIFY_ALL);
                    boolean edge = Math.abs(dx) == 4 || Math.abs(dz) == 4;
                    if (!edge) continue;

                    for (int dy = 1; dy <= 3; dy++) {
                        BlockPos at = base.add(dx, y + dy, dz);
                        boolean door = level == 0 && dz == 4 && dx == 0 && dy <= 2;
                        boolean window = dy == 2 && ((Math.abs(dz) == 4 && Math.abs(dx) == 2)
                                || (Math.abs(dx) == 4 && Math.abs(dz) == 2));
                        if (door) {
                            world.setBlockState(at, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
                        } else if (window) {
                            world.setBlockState(at, glass.getDefaultState(), Block.NOTIFY_ALL);
                        } else if (dy == 3) {
                            world.setBlockState(at, trim.getDefaultState(), Block.NOTIFY_ALL);
                        } else {
                            world.setBlockState(at, wall.getDefaultState(), Block.NOTIFY_ALL);
                        }
                    }
                }
            }
        }

        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                world.setBlockState(base.add(dx, floors * 4, dz), trim.getDefaultState(), Block.NOTIFY_ALL);
            }
        }
        world.setBlockState(base.add(0, 3, 4), Blocks.LANTERN.getDefaultState(), Block.NOTIFY_ALL);
    }

    private static final class SettlementInfo {
        final int tier;
        final String kind, name, capital;
        final double distance;
        final int population;

        SettlementInfo(int tier, String kind, String name, String capital, double distance, int population) {
            this.tier = tier;
            this.kind = kind;
            this.name = name;
            this.capital = capital;
            this.distance = distance;
            this.population = population;
        }

        String message() {
            String km = String.format(Locale.ROOT, "%.1f", distance / 1000.0);
            return "Поселение: " + kind + " «" + name + "» | жителей поблизости: " + population
                    + " | до столицы «" + capital + "»: " + km + " км";
        }
    }
}
