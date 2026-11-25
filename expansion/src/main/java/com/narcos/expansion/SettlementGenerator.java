package com.narcos.expansion;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Random;

final class SettlementGenerator {
    private static final int MARKER_Y_OFFSET = 0;

    private SettlementGenerator() {
    }

    static void onChunkLoad(ServerWorld world, WorldChunk chunk) {
        // Уже обжитые старые чанки не перестраиваем. Это сохраняет существующие дома и постройки.
        if (chunk.getInhabitedTime() > 0L) return;

        ChunkPos cp = chunk.getPos();
        int startX = cp.getStartX();
        int startZ = cp.getStartZ();
        BlockPos marker = new BlockPos(startX, world.getBottomY() + MARKER_Y_OFFSET, startZ);
        if (world.getBlockState(marker).isOf(Blocks.STRUCTURE_VOID)) return;

        int centerX = startX + 8;
        int centerZ = startZ + 8;
        SettlementLayout.Type type = SettlementLayout.typeAt(world, centerX, centerZ);
        if (type == null) return;

        // Ставим скрытый маркер на нижнем слое мира, чтобы не генерировать район повторно.
        world.setBlockState(marker, Blocks.STRUCTURE_VOID.getDefaultState(), Block.NOTIFY_LISTENERS);

        long seed = world.getSeed()
                ^ ((long) cp.x * 341873128712L)
                ^ ((long) cp.z * 132897987541L);
        Random random = new Random(seed);

        layRoads(world, startX, startZ, type);
        int groundY = findBuildHeight(world, startX, startZ);
        if (groundY != Integer.MIN_VALUE) {
            buildBuilding(world, startX + 4, startZ + 4, groundY, type, random);
            maybeSpawnResident(world, startX + 8, startZ + 8, groundY + 1, type, cp, random);
        }
    }

    private static int findBuildHeight(ServerWorld world, int startX, int startZ) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        int total = 0;
        int samples = 0;

        for (int x = startX + 4; x <= startX + 12; x++) {
            for (int z = startZ + 4; z <= startZ + 12; z++) {
                int surfaceY = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                BlockPos surface = new BlockPos(x, surfaceY, z);
                BlockState state = world.getBlockState(surface);
                if (!state.isSolidBlock(world, surface) || !world.getFluidState(surface).isEmpty()
                        || !isNaturalGround(state)) {
                    return Integer.MIN_VALUE;
                }
                min = Math.min(min, surfaceY);
                max = Math.max(max, surfaceY);
                total += surfaceY;
                samples++;
                for (int y = surfaceY + 1; y <= surfaceY + 3; y++) {
                    BlockState above = world.getBlockState(new BlockPos(x, y, z));
                    if (!above.isAir() && !above.isReplaceable()) {
                        return Integer.MIN_VALUE;
                    }
                }
            }
        }

        if (max - min > 3 || samples == 0) return Integer.MIN_VALUE;
        return Math.round((float) total / samples);
    }

    private static void layRoads(ServerWorld world, int startX, int startZ, SettlementLayout.Type type) {
        Block road = type == SettlementLayout.Type.VILLAGE ? Blocks.GRAVEL : Blocks.GRAY_CONCRETE;
        Block sidewalk = type == SettlementLayout.Type.VILLAGE ? Blocks.COBBLESTONE : Blocks.SMOOTH_STONE;
        for (int i = 0; i < 16; i++) {
            placeOnSurface(world, startX + i, startZ, road);
            placeOnSurface(world, startX + i, startZ + 1, road);
            placeOnSurface(world, startX + i, startZ + 2, road);
            placeOnSurface(world, startX, startZ + i, road);
            placeOnSurface(world, startX + 1, startZ + i, road);
            placeOnSurface(world, startX + 2, startZ + i, road);
        }
        for (int i = 3; i < 16; i++) {
            placeOnSurface(world, startX + 3, startZ + i, sidewalk);
            placeOnSurface(world, startX + i, startZ + 3, sidewalk);
        }
    }

    private static void placeOnSurface(ServerWorld world, int x, int z, Block block) {
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState surface = world.getBlockState(pos);
        if (!world.getFluidState(pos).isEmpty() || !isNaturalGround(surface)) return;
        world.setBlockState(pos, block.getDefaultState(), Block.NOTIFY_LISTENERS);
        BlockPos above = pos.up();
        if (world.getBlockState(above).isReplaceable()) {
            world.setBlockState(above, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
        }
    }

    private static void buildBuilding(ServerWorld world, int x0, int z0, int groundY,
                                      SettlementLayout.Type type, Random random) {
        int width = 9;
        int depth = 9;
        int x1 = x0 + width - 1;
        int z1 = z0 + depth - 1;

        int floors;
        Block facade;
        Block window;
        Block roof;
        Block floor;
        if (type == SettlementLayout.Type.VILLAGE) {
            floors = 1 + random.nextInt(2);
            facade = random.nextBoolean() ? Blocks.OAK_PLANKS : Blocks.BRICKS;
            window = Blocks.GLASS_PANE;
            roof = Blocks.DARK_OAK_PLANKS;
            floor = Blocks.OAK_PLANKS;
        } else if (type == SettlementLayout.Type.CITY) {
            floors = 3 + random.nextInt(4);
            facade = random.nextBoolean() ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.WHITE_CONCRETE;
            window = Blocks.LIGHT_BLUE_STAINED_GLASS;
            roof = Blocks.GRAY_CONCRETE;
            floor = Blocks.SMOOTH_STONE;
        } else {
            floors = 7 + random.nextInt(9);
            if (random.nextInt(5) == 0) floors += 4;
            facade = random.nextInt(3) == 0 ? Blocks.WHITE_CONCRETE
                    : (random.nextBoolean() ? Blocks.LIGHT_GRAY_CONCRETE : Blocks.POLISHED_ANDESITE);
            window = random.nextBoolean() ? Blocks.LIGHT_BLUE_STAINED_GLASS : Blocks.BLACK_STAINED_GLASS;
            roof = Blocks.GRAY_CONCRETE;
            floor = Blocks.SMOOTH_STONE;
        }

        // Основание и внутренние перекрытия.
        fill(world, x0, groundY, z0, x1, groundY, z1, type == SettlementLayout.Type.VILLAGE
                ? Blocks.STONE_BRICKS : Blocks.POLISHED_ANDESITE);

        for (int level = 0; level < floors; level++) {
            int floorY = groundY + level * 4;
            if (level > 0) {
                fill(world, x0 + 1, floorY, z0 + 1, x1 - 1, floorY, z1 - 1, floor);
            }

            // Наружные стены и оконные пролёты.
            for (int y = floorY + 1; y <= floorY + 3; y++) {
                for (int x = x0; x <= x1; x++) {
                    put(world, x, y, z0, facade);
                    put(world, x, y, z1, facade);
                }
                for (int z = z0; z <= z1; z++) {
                    put(world, x0, y, z, facade);
                    put(world, x1, y, z, facade);
                }
            }

            for (int x = x0 + 2; x <= x1 - 2; x += 2) {
                put(world, x, floorY + 1, z0, window);
                put(world, x, floorY + 2, z0, window);
                put(world, x, floorY + 1, z1, window);
                put(world, x, floorY + 2, z1, window);
            }
            for (int z = z0 + 2; z <= z1 - 2; z += 2) {
                put(world, x0, floorY + 1, z, window);
                put(world, x0, floorY + 2, z, window);
                put(world, x1, floorY + 1, z, window);
                put(world, x1, floorY + 2, z, window);
            }

            // Лестничная шахта с проходом между этажами.
            Direction[] directions = {Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH};
            int[][] stairOffsets = {{1, 1}, {2, 1}, {2, 2}, {1, 2}};
            for (int step = 0; step < 4; step++) {
                BlockState stair = Blocks.STONE_BRICK_STAIRS.getDefaultState()
                        .with(StairsBlock.FACING, directions[step]);
                putState(world, x0 + stairOffsets[step][0], floorY + 1 + step,
                        z0 + stairOffsets[step][1], stair);
            }

            // Вход на первом этаже — широкий открытый проём, не загороженный стеной.
            if (level == 0) {
                int doorX = x0 + width / 2;
                put(world, doorX, floorY + 1, z1, Blocks.AIR);
                put(world, doorX, floorY + 2, z1, Blocks.AIR);
            }

            // Балконы у городских домов.
            if (type != SettlementLayout.Type.VILLAGE && level % 2 == 1) {
                for (int x = x0 + 2; x <= x1 - 2; x++) {
                    put(world, x, floorY + 1, z1 + 1, Blocks.IRON_BARS);
                }
            }
        }

        int roofY = groundY + floors * 4;
        fill(world, x0 - 1, roofY, z0 - 1, x1 + 1, roofY, z1 + 1, roof);

        // Скатные деревянные крыши в селе, плоские крыши и парапеты в городе и столице.
        if (type == SettlementLayout.Type.VILLAGE) {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                for (int step = 0; step < 4; step++) {
                    putState(world, x, roofY + 1 + step, z0 + step,
                            Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH));
                    putState(world, x, roofY + 1 + step, z1 - step,
                            Blocks.SPRUCE_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH));
                }
                put(world, x, roofY + 5, z0 + 4, Blocks.SPRUCE_PLANKS);
            }
            put(world, x0 + 4, roofY + 1, z0 + 4, Blocks.CAMPFIRE);
        } else {
            for (int x = x0 - 1; x <= x1 + 1; x++) {
                put(world, x, roofY + 1, z0 - 1, Blocks.IRON_BARS);
                put(world, x, roofY + 1, z1 + 1, Blocks.IRON_BARS);
            }
            for (int z = z0 - 1; z <= z1 + 1; z++) {
                put(world, x0 - 1, roofY + 1, z, Blocks.IRON_BARS);
                put(world, x1 + 1, roofY + 1, z, Blocks.IRON_BARS);
            }
            if (type == SettlementLayout.Type.CAPITAL && floors >= 12) {
                put(world, x0 + 4, roofY + 1, z0 + 4, Blocks.LIGHTNING_ROD);
            } else {
                put(world, x0 + 4, roofY + 1, z0 + 4, Blocks.LANTERN);
            }
        }

        // Уличные фонари у крупных зданий.
        if (type != SettlementLayout.Type.VILLAGE) {
            int lampX = x0 + width / 2;
            int lampZ = z0 - 1;
            put(world, lampX, groundY + 1, lampZ, Blocks.IRON_BARS);
            put(world, lampX, groundY + 2, lampZ, Blocks.IRON_BARS);
            put(world, lampX, groundY + 3, lampZ, Blocks.LANTERN);
        }
    }

    private static void maybeSpawnResident(ServerWorld world, int x, int z, int y,
                                           SettlementLayout.Type type, ChunkPos cp, Random random) {
        int divisor = switch (type) {
            case CAPITAL -> 3;
            case CITY -> 5;
            case VILLAGE -> 11;
        };
        int slot = Math.floorMod(cp.x * 31 + cp.z * 17, divisor);
        if (slot != 0) return;

        VillagerEntity villager = EntityType.VILLAGER.create(world);
        if (villager == null) return;
        villager.refreshPositionAndAngles(x + 0.5, y, z + 0.5, random.nextFloat() * 360f, 0f);
        world.spawnEntity(villager);
    }

    private static boolean isNaturalGround(BlockState state) {
        return state.isOf(Blocks.GRASS_BLOCK)
                || state.isOf(Blocks.DIRT)
                || state.isOf(Blocks.COARSE_DIRT)
                || state.isOf(Blocks.ROOTED_DIRT)
                || state.isOf(Blocks.PODZOL)
                || state.isOf(Blocks.MYCELIUM)
                || state.isOf(Blocks.MOSS_BLOCK)
                || state.isOf(Blocks.MUD)
                || state.isOf(Blocks.PACKED_MUD)
                || state.isOf(Blocks.STONE)
                || state.isOf(Blocks.DEEPSLATE)
                || state.isOf(Blocks.ANDESITE)
                || state.isOf(Blocks.DIORITE)
                || state.isOf(Blocks.GRANITE)
                || state.isOf(Blocks.TUFF)
                || state.isOf(Blocks.CALCITE)
                || state.isOf(Blocks.SAND)
                || state.isOf(Blocks.RED_SAND)
                || state.isOf(Blocks.GRAVEL)
                || state.isOf(Blocks.SNOW_BLOCK)
                || state.isOf(Blocks.TERRACOTTA)
                || state.isOf(Blocks.WHITE_TERRACOTTA)
                || state.isOf(Blocks.ORANGE_TERRACOTTA)
                || state.isOf(Blocks.YELLOW_TERRACOTTA)
                || state.isOf(Blocks.BROWN_TERRACOTTA);
    }

    private static void fill(ServerWorld world, int x0, int y0, int z0,
                             int x1, int y1, int z1, Block block) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    put(world, x, y, z, block);
                }
            }
        }
    }

    private static void put(ServerWorld world, int x, int y, int z, Block block) {
        putState(world, x, y, z, block.getDefaultState());
    }

    private static void putState(ServerWorld world, int x, int y, int z, BlockState state) {
        world.setBlockState(new BlockPos(x, y, z), state, Block.NOTIFY_LISTENERS);
    }
}
