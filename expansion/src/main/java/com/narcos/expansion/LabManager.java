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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        long benchKey = bench.asLong();

        if (held.isOf(ExpansionContent.SCIENTIST_CONTRACT)) {
            int current = data.scientistsAt(benchKey);
            if (current >= 8) {
                player.sendMessage(Text.literal("В этой лаборатории уже нанято 8 учёных.")
                        .formatted(Formatting.YELLOW), false);
                return;
            }
            if (!player.getAbilities().creativeMode) held.decrement(1);
            current = data.addScientist(benchKey);
            spawnScientist(world, area, benchKey, current);
            player.sendMessage(Text.literal("Нанят учёный. Всего в лаборатории: " + current + "/8.")
                    .formatted(Formatting.GREEN), false);
            return;
        }

        int stations = countWorkstations(world, area);
        int scientists = data.scientistsAt(benchKey);
        player.sendMessage(Text.literal("Лаборатория размечена: " + area.width() + "×" + area.depth()
                + " блоков. Учёные: " + scientists + "/8. Рабочие станции: " + stations
                + " (верстак, печь/коптильня, зельеварка).")
                .formatted(Formatting.AQUA), false);
        player.sendMessage(Text.literal("Контракт учёного покупается у барыги. Используй его по верстаку лаборатории.")
                .formatted(Formatting.GRAY), false);
    }

    private static void spawnScientist(ServerWorld world, LabArea area, long benchKey, int desired) {
        String tag = "narcos_expansion_scientist_" + Long.toUnsignedString(benchKey);
        Box box = new Box(area.minX + 1, area.y + 1, area.minZ + 1,
                area.maxX, area.y + 4, area.maxZ);
        int existing = world.getEntitiesByClass(VillagerEntity.class, box,
                entity -> entity.getCommandTags().contains(tag)).size();

        while (existing < desired) {
            BlockPos spawn = findOpenInterior(world, area);
            if (spawn == null) return;
            VillagerEntity scientist = EntityType.VILLAGER.create(world);
            if (scientist == null) return;
            scientist.refreshPositionAndAngles(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0, 0);
            scientist.setCustomName(Text.literal("Учёный").formatted(Formatting.DARK_AQUA));
            scientist.setCustomNameVisible(true);
            scientist.setPersistent();
            scientist.setAiDisabled(true);
            scientist.addCommandTag(tag);
            world.spawnEntity(scientist);
            existing++;
        }
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
                if (state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.FURNACE)
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
        List<BlockPos> markers = new ArrayList<>();
        for (int x = bench.getX() - 32; x <= bench.getX() + 32; x++) {
            for (int z = bench.getZ() - 32; z <= bench.getZ() + 32; z++) {
                BlockPos candidate = new BlockPos(x, y, z);
                if (world.getBlockState(candidate).isOf(ExpansionContent.LAB_MARKER)) {
                    markers.add(candidate);
                }
            }
        }
        if (markers.size() != 4) return null;

        int minX = markers.stream().mapToInt(BlockPos::getX).min().orElse(bench.getX());
        int maxX = markers.stream().mapToInt(BlockPos::getX).max().orElse(bench.getX());
        int minZ = markers.stream().mapToInt(BlockPos::getZ).min().orElse(bench.getZ());
        int maxZ = markers.stream().mapToInt(BlockPos::getZ).max().orElse(bench.getZ());
        if (maxX - minX < 4 || maxZ - minZ < 4 || maxX - minX > 32 || maxZ - minZ > 32) return null;

        Set<BlockPos> expected = new HashSet<>();
        expected.add(new BlockPos(minX, y, minZ));
        expected.add(new BlockPos(minX, y, maxZ));
        expected.add(new BlockPos(maxX, y, minZ));
        expected.add(new BlockPos(maxX, y, maxZ));
        if (!new HashSet<>(markers).equals(expected)) return null;

        if (bench.getX() <= minX || bench.getX() >= maxX
                || bench.getZ() <= minZ || bench.getZ() >= maxZ) return null;
        return new LabArea(minX, maxX, minZ, maxZ, y);
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
    }
}
