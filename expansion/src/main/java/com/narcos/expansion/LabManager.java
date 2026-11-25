package com.narcos.expansion;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;


public final class LabManager {
    private LabManager() { }

    public static void use(ServerWorld world, BlockPos bench, PlayerEntity player, ItemStack held) {
        LabArea area = findArea(world, bench);
        if (area == null) {
            player.sendMessage(Text.literal("Лаборатория не размечена: поставь 4 маркера квадратом, а верстак — внутри.")
                    .formatted(Formatting.RED), false);
            return;
        }

        MinecraftServer server = world.getServer();
        ExpansionState data = ExpansionState.of(server);
        String labKey = area.key(world);
        java.util.UUID owner = data.claimOrGetLabOwner(labKey, player.getUuid());
        if (!owner.equals(player.getUuid())) {
            player.sendMessage(Text.literal("Эта лаборатория принадлежит другому игроку.")
                    .formatted(Formatting.RED), false);
            return;
        }

        if (held.isOf(ExpansionContent.SCIENTIST_CONTRACT)) {
            int current = data.scientistsAt(labKey);
            if (current >= 8) {
                player.sendMessage(Text.literal("В этой лаборатории уже нанято 8 учёных.")
                        .formatted(Formatting.YELLOW), false);
                return;
            }
            if (!spawnScientist(world, area, labKey)) {
                player.sendMessage(Text.literal("В лаборатории нет свободного места для нового учёного.")
                        .formatted(Formatting.RED), false);
                return;
            }
            if (!player.getAbilities().creativeMode) held.decrement(1);
            current = data.addScientist(labKey);
            player.sendMessage(Text.literal("Нанят учёный. Всего в лаборатории: " + current + "/8.")
                    .formatted(Formatting.GREEN), false);
            return;
        }

        int stations = countWorkstations(world, area);
        int scientists = data.scientistsAt(labKey);
        player.sendMessage(Text.literal("Лаборатория размечена: " + area.width() + "×" + area.depth()
                + " блоков. Учёные: " + scientists + "/8. Рабочие станции: " + stations
                + " (верстак, печь/коптильня, зельеварка).")
                .formatted(Formatting.AQUA), false);
        player.sendMessage(Text.literal("Контракт учёного покупается у барыги. Используй его по верстаку лаборатории.")
                .formatted(Formatting.GRAY), false);
    }

    private static boolean spawnScientist(ServerWorld world, LabArea area, String labKey) {
        BlockPos spawn = findOpenInterior(world, area);
        if (spawn == null) return false;

        String tag = "narcos_expansion_scientist_" + java.util.UUID.nameUUIDFromBytes(
                labKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        VillagerEntity scientist = EntityType.VILLAGER.create(world);
        if (scientist == null) return false;
        scientist.refreshPositionAndAngles(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
        scientist.setCustomName(Text.literal("Учёный").formatted(Formatting.DARK_AQUA));
        scientist.setCustomNameVisible(true);
        scientist.setPersistent();
        scientist.setAiDisabled(true);
        scientist.addCommandTag(tag);
        return world.spawnEntity(scientist);
    }

    private static BlockPos findOpenInterior(ServerWorld world, LabArea area) {
        for (int x = area.minX + 1; x < area.maxX; x++) {
            for (int z = area.minZ + 1; z < area.maxZ; z++) {
                BlockPos feet = new BlockPos(x, area.y + 1, z);
                if (world.getBlockState(feet).isAir() && world.getBlockState(feet.up()).isAir()) return feet;
            }
        }
        return null;
    }

    private static int countWorkstations(ServerWorld world, LabArea area) {
        int count = 0;
        for (int x = area.minX + 1; x < area.maxX; x++) {
            for (int z = area.minZ + 1; z < area.maxZ; z++) {
                BlockPos pos = new BlockPos(x, area.y, z);
                BlockState state = world.getBlockState(pos);
                if (state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(ExpansionContent.LAB_BENCH)
                        || state.isOf(Blocks.FURNACE)
                        || state.isOf(Blocks.SMOKER) || state.isOf(Blocks.BREWING_STAND)
                        || state.isOf(Blocks.BLAST_FURNACE)) {
                    count++;
                }
            }
        }
        return count;
    }

    private static LabArea findArea(ServerWorld world, BlockPos bench) {
        int y = bench.getY();
        LabArea found = null;

        // Search for a square whose four corner markers surround this workbench.
        // This allows multiple separate labs to exist near one another.
        for (int side = 4; side <= 32; side++) {
            for (int minX = bench.getX() - side + 1; minX < bench.getX(); minX++) {
                int maxX = minX + side;
                for (int minZ = bench.getZ() - side + 1; minZ < bench.getZ(); minZ++) {
                    int maxZ = minZ + side;
                    if (!isMarker(world, minX, y, minZ)
                            || !isMarker(world, minX, y, maxZ)
                            || !isMarker(world, maxX, y, minZ)
                            || !isMarker(world, maxX, y, maxZ)) {
                        continue;
                    }

                    // Ambiguous overlapping claims should not silently select the wrong lab.
                    if (found != null) return null;
                    found = new LabArea(minX, maxX, minZ, maxZ, y);
                }
            }
        }
        return found;
    }

    private static boolean isMarker(ServerWorld world, int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z)).isOf(ExpansionContent.LAB_MARKER);
    }

    private static final class LabArea {
        final int minX, maxX, minZ, maxZ, y;

        LabArea(int minX, int maxX, int minZ, int maxZ, int y) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.y = y;
        }

        int width() { return maxX - minX + 1; }
        int depth() { return maxZ - minZ + 1; }

        String key(ServerWorld world) {
            return world.getRegistryKey().getValue() + "|" + minX + "|" + y + "|" + minZ
                    + "|" + maxX + "|" + maxZ;
        }
    }
}
