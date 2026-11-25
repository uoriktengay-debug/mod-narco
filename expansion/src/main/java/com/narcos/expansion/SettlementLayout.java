package com.narcos.expansion;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.Random;

public final class SettlementLayout {
    enum Type {
        CAPITAL("столица", 56 * 16),
        CITY("город", 4 * 16),
        VILLAGE("село", 2 * 16);

        final String label;
        final int radius;

        Type(String label, int radius) {
            this.label = label;
            this.radius = radius;
        }
    }

    private static final String[] CAPITAL_NAMES = {
            "Новоград", "Североград", "Златоград", "Велеград", "Гранитск"
    };
    private static final String[] CITY_NAMES = {
            "Каменск", "Берёзовск", "Приморск", "Лесогорск", "Рудный"
    };
    private static final String[] VILLAGE_NAMES = {
            "Сосновка", "Берёзки", "Малые Поля", "Речное", "Тихая Слобода"
    };

    private SettlementLayout() {
    }

    static BlockPos center(ServerWorld world, Type type) {
        BlockPos spawn = world.getSpawnPos();
        return switch (type) {
            case CAPITAL -> spawn;
            case CITY -> spawn.add(2400, 0, 0);
            case VILLAGE -> spawn.add(3800, 0, 0);
        };
    }

    static String name(ServerWorld world, Type type) {
        String[] names = switch (type) {
            case CAPITAL -> CAPITAL_NAMES;
            case CITY -> CITY_NAMES;
            case VILLAGE -> VILLAGE_NAMES;
        };
        long salt = switch (type) {
            case CAPITAL -> 0x61C8864680B583EBL;
            case CITY -> 0x4F1BBCDCBFA54001L;
            case VILLAGE -> 0x2D358DCCAA6C78A5L;
        };
        int index = Math.floorMod(Long.hashCode(world.getSeed() ^ salt), names.length);
        return names[index];
    }

    static Type typeAt(ServerWorld world, int x, int z) {
        for (Type type : Type.values()) {
            BlockPos center = center(world, type);
            long dx = (long) x - center.getX();
            long dz = (long) z - center.getZ();
            if (dx * dx + dz * dz <= (long) type.radius * type.radius) {
                return type;
            }
        }
        return null;
    }

    static long distance(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return Math.round(Math.sqrt(dx * dx + dz * dz));
    }

    public static void sendInfo(ServerPlayerEntity player) {
        ServerWorld world = player.getServerWorld();
        BlockPos pos = player.getBlockPos();
        Type current = typeAt(world, pos.getX(), pos.getZ());

        String currentText = current == null
                ? "между поселениями"
                : current.label + " «" + name(world, current) + "»";

        player.sendMessage(Text.literal("=== География поселений ===").formatted(Formatting.GOLD), false);
        player.sendMessage(Text.literal("Текущая территория: " + currentText).formatted(Formatting.YELLOW), false);

        BlockPos capital = center(world, Type.CAPITAL);
        BlockPos city = center(world, Type.CITY);
        BlockPos village = center(world, Type.VILLAGE);

        player.sendMessage(Text.literal("Столица «" + name(world, Type.CAPITAL) + "» — до неё "
                + distance(pos, capital) + " блоков; размер района 1792×1792.").formatted(Formatting.WHITE), false);
        player.sendMessage(Text.literal("Город «" + name(world, Type.CITY) + "» — до него "
                + distance(pos, city) + " блоков; размер района 128×128.").formatted(Formatting.WHITE), false);
        player.sendMessage(Text.literal("Село «" + name(world, Type.VILLAGE) + "» — до него "
                + distance(pos, village) + " блоков; размер района 64×64.").formatted(Formatting.WHITE), false);
        player.sendMessage(Text.literal("Между столицей и городом: 2400 блоков; между городом и селом: 1400 блоков.")
                .formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("Площадь столицы примерно в 196 раз больше площади города.")
                .formatted(Formatting.AQUA), false);
    }
}
