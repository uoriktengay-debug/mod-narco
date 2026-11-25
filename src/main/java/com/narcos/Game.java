package com.narcos;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.narcos.NarcosState.PlayerData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PaneBlock;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Вся игровая логика: розыск, копы, тюрьма, продажа, бизнес. */
public class Game {
    // ===== Настройки (можно крутить) =====
    static final int JAIL_TICKS = 20 * 120;   // срок в камере: 2 минуты
    static final int BAIL_COST = 5;           // залог в изумрудах
    static final int SQUATS_NEEDED = 30;      // или 30 приседаний
    static final int COURIER_COST = 8, COURIER_MAX = 8;
    static final int ARTIST_COST = 12, ARTIST_MAX = 10;
    static final int COLOR_COST = 4;
    static final String COP_TAG = "narcos_police";
    static final String[] COLOR_NAMES = {"красный", "синий", "зелёный", "чёрный"};
    static final String[] COLOR_DESC = {
            "цена товара +25%, но розыск растёт на 25% быстрее",
            "розыск растёт на 30% медленнее",
            "доставщики продают на 50% больше",
            "ночью розыск растёт в 2.5 раза медленнее"};

    static NarcosState state;
    static long tick;
    static final Map<UUID, Boolean> sneak = new HashMap<>();
    static final Map<UUID, Integer> squats = new HashMap<>();

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> state = NarcosState.of(s));
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> state = null);
        ServerTickEvents.END_SERVER_TICK.register(Game::onTick);
        UseEntityCallback.EVENT.register(Game::onUseEntity);
        // Копы арестовывают, а не бьют
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(source.getAttacker() != null && source.getAttacker().getCommandTags().contains(COP_TAG)));
        CommandRegistrationCallback.EVENT.register(Game::commands);
    }

    // ===================== ТИК =====================
    static void onTick(MinecraftServer server) {
        if (state == null) return;
        tick++;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            PlayerData d = state.get(p.getUuid());
            if (d.jailUntil > 0) {
                jailTick(server, p, d);
                continue;
            }
            if (p.isCreative() || p.isSpectator()) continue;
            if (tick % 10 == 0) arrestCheck(server, p, d);
            if (tick % 40 == 0) slowTick(p, d);
            if (tick % 1200 == 0) courierTick(p, d);
        }
        if (tick % 200 == 0) state.markDirty();
    }

    static void slowTick(ServerPlayerEntity p, PlayerData d) {
        ServerWorld w = p.getServerWorld();
        boolean has = hasContraband(p);
        if (d.heat > 0) d.heat = Math.max(0, d.heat - (has ? 1 : 2));

        // Видна ли плантация снаружи?
        boolean exposed = false;
        BlockPos c = p.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(c.add(-8, -4, -8), c.add(8, 4, 8))) {
            if (w.getBlockState(pos).getBlock() instanceof GreenCropBlock && visible(w, pos.toImmutable())) {
                exposed = true;
                break;
            }
        }
        if (exposed) addHeat(p, d, w.isDay() ? 3f : 1.5f);

        // Полиция
        List<IronGolemEntity> cops = cops(w, p.getPos(), 48);
        if (d.heat < 30 || !has) {
            for (IronGolemEntity cop : cops) {
                if (d.heat < 30) cop.discard();
                else cop.setTarget(null);
            }
        } else {
            for (IronGolemEntity cop : cops) cop.setTarget(p);
            int want = d.heat >= 80 ? 3 : 1;
            if (d.heat >= 50 && cops.size() < want && tick % 200 == 0) {
                if (spawnCop(w, p)) {
                    p.sendMessage(Text.literal("Копы заметили тебя!").formatted(Formatting.RED), false);
                }
            }
        }

        // Шкала розыска
        if (d.heat > 0 || exposed) {
            int f = Math.min(10, d.heat / 10);
            String bar = "█".repeat(f) + "░".repeat(10 - f);
            Formatting col = d.heat >= 80 ? Formatting.RED : d.heat >= 50 ? Formatting.GOLD : Formatting.YELLOW;
            p.sendMessage(Text.literal("Розыск " + bar + " " + d.heat + (exposed ? "  ⚠ плантацию видно!" : "")).formatted(col), true);
        }
    }

    /** Плантация видна, если над ней открытое небо или рядом окно. */
    static boolean visible(ServerWorld w, BlockPos p) {
        if (w.getLightLevel(LightType.SKY, p.up()) >= 14) return true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dir : dirs) {
            for (int i = 1; i <= 7; i++) {
                for (int dy = 0; dy <= 1; dy++) {
                    BlockPos q = p.add(dir[0] * i, dy, dir[1] * i);
                    BlockState s = w.getBlockState(q);
                    if (s.isAir()) continue;
                    if (s.isIn(BlockTags.IMPERMEABLE) || s.getBlock() instanceof PaneBlock) return true;
                }
                BlockState base = w.getBlockState(p.add(dir[0] * i, 0, dir[1] * i));
                if (base.isOpaque()) break;
            }
        }
        return false;
    }

    static void addHeat(ServerPlayerEntity p, PlayerData d, float amount) {
        float m = 1f;
        if (d.color == 0) m *= 1.25f;
        if (d.color == 1) m *= 0.7f;
        if (d.color == 3 && !p.getServerWorld().isDay()) m *= 0.4f;
        d.heat = Math.max(0, Math.min(100, d.heat + Math.round(amount * m)));
    }

    // ===================== ПОЛИЦИЯ =====================
    static List<IronGolemEntity> cops(ServerWorld w, Vec3d pos, double r) {
        return w.getEntitiesByClass(IronGolemEntity.class, Box.of(pos, r * 2, r * 2, r * 2),
                e -> e.getCommandTags().contains(COP_TAG));
    }

    static boolean spawnCop(ServerWorld w, ServerPlayerEntity p) {
        for (int i = 0; i < 25; i++) {
            double ang = w.random.nextDouble() * Math.PI * 2;
            double dist = 10 + w.random.nextDouble() * 8;
            int x = p.getBlockX() + (int) (Math.cos(ang) * dist);
            int z = p.getBlockZ() + (int) (Math.sin(ang) * dist);
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos pos = new BlockPos(x, p.getBlockY() + dy, z);
                if (w.getBlockState(pos.down()).isSolidBlock(w, pos.down())
                        && w.getBlockState(pos).isAir() && w.getBlockState(pos.up()).isAir()
                        && w.getBlockState(pos.up(2)).isAir()) {
                    IronGolemEntity cop = EntityType.IRON_GOLEM.create(w);
                    if (cop == null) return false;
                    cop.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
                    cop.addCommandTag(COP_TAG);
                    cop.setCustomName(Text.literal("Полицейский").formatted(Formatting.BLUE));
                    cop.setCustomNameVisible(true);
                    cop.setPersistent();
                    cop.setTarget(p);
                    w.spawnEntity(cop);
                    return true;
                }
            }
        }
        return false;
    }

    static void arrestCheck(MinecraftServer s, ServerPlayerEntity p, PlayerData d) {
        if (d.heat < 50 || !hasContraband(p)) return;
        if (!cops(p.getServerWorld(), p.getPos(), 3.0).isEmpty()) arrest(s, p, d);
    }

    // ===================== ТЮРЬМА =====================
    static void arrest(MinecraftServer s, ServerPlayerEntity p, PlayerData d) {
        int seized = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (st.isIn(ModContent.CONTRABAND)) {
                seized += st.getCount();
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
        ServerWorld w = p.getServerWorld();
        for (IronGolemEntity cop : cops(w, p.getPos(), 64)) cop.discard();

        d.heat = 0;
        d.rx = p.getX(); d.ry = p.getY(); d.rz = p.getZ();
        d.rdim = w.getRegistryKey().getValue().toString();
        d.prevMode = p.interactionManager.getGameMode().getId();

        ServerWorld ow = s.getOverworld();
        BlockPos sp = ow.getSpawnPos();
        buildJail(ow, sp.getX(), 250, sp.getZ());
        d.jailUntil = ow.getTime() + JAIL_TICKS;
        squats.put(p.getUuid(), 0);
        sneak.put(p.getUuid(), false);

        p.changeGameMode(GameMode.ADVENTURE);
        p.teleport(ow, sp.getX() + 0.5, 251, sp.getZ() + 0.5, 0, 0);
        p.sendMessage(Text.literal("ТЕБЯ ЗАДЕРЖАЛИ! Изъято товара: " + seized + " шт.").formatted(Formatting.RED, Formatting.BOLD), false);
        p.sendMessage(Text.literal("Выйти можно: отсидеть 2 минуты, сделать " + SQUATS_NEEDED
                + " приседаний (Shift) или заплатить залог: /bail (" + BAIL_COST + " изумрудов).").formatted(Formatting.GRAY), false);
        state.markDirty();
    }

    static void buildJail(ServerWorld w, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 4; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == 0 || dy == 4;
                    BlockState bs = edge ? Blocks.STONE_BRICKS.getDefaultState() : Blocks.AIR.getDefaultState();
                    if (dz == 2 && dx == 0 && (dy == 2 || dy == 3)) bs = Blocks.IRON_BARS.getDefaultState();
                    w.setBlockState(new BlockPos(x + dx, y + dy, z + dz), bs);
                }
            }
        }
        w.setBlockState(new BlockPos(x, y + 4, z), Blocks.GLOWSTONE.getDefaultState());
    }

    static void jailTick(MinecraftServer s, ServerPlayerEntity p, PlayerData d) {
        UUID id = p.getUuid();
        boolean sn = p.isSneaking();
        if (sn && !sneak.getOrDefault(id, false)) {
            int c = squats.merge(id, 1, Integer::sum);
            if (c >= SQUATS_NEEDED) {
                release(s, p, d, "Ты отработал срок приседаниями.");
                return;
            }
        }
        sneak.put(id, sn);
        if (tick % 20 == 0) {
            long left = d.jailUntil - s.getOverworld().getTime();
            if (left <= 0) {
                release(s, p, d, "Срок вышел.");
                return;
            }
            p.sendMessage(Text.literal("Камера: " + (left / 20) + " с | приседаний " + squats.getOrDefault(id, 0)
                    + "/" + SQUATS_NEEDED + " | /bail = " + BAIL_COST + " изумр.").formatted(Formatting.GRAY), true);
        }
    }

    static void release(MinecraftServer s, ServerPlayerEntity p, PlayerData d, String why) {
        ServerWorld w = s.getWorld(RegistryKey.of(RegistryKeys.WORLD, new Identifier(d.rdim)));
        if (w == null) w = s.getOverworld();
        d.jailUntil = 0;
        squats.remove(p.getUuid());
        p.changeGameMode(GameMode.byId(d.prevMode));
        p.teleport(w, d.rx, d.ry, d.rz, p.getYaw(), p.getPitch());
        p.sendMessage(Text.literal(why + " Ты на свободе, но товар изъят.").formatted(Formatting.GREEN), false);
        state.markDirty();
    }

    // ===================== ПРОДАЖА =====================
    static double priceOf(Item i) {
        if (i == ModContent.HEMP_LEAF) return 0.6;
        if (i == ModContent.POPPY_POD) return 0.8;
        if (i == ModContent.HEMP_PACK) return 3.0;
        if (i == ModContent.POPPY_PACK) return 4.0;
        return 0;
    }

    static double priceMult(PlayerData d) {
        return (1 + 0.08 * d.artists) * (d.color == 0 ? 1.25 : 1.0);
    }

    static ActionResult onUseEntity(PlayerEntity pl, World w, Hand h, Entity e, EntityHitResult hit) {
        if (!(e instanceof VillagerEntity)) return ActionResult.PASS;
        ItemStack held = pl.getStackInHand(h);
        if (!held.isIn(ModContent.CONTRABAND)) return ActionResult.PASS;
        if (w.isClient || state == null || !(pl instanceof ServerPlayerEntity p)) return ActionResult.SUCCESS;

        PlayerData d = state.get(p.getUuid());
        if (d.jailUntil > 0) return ActionResult.FAIL;
        int n = Math.min(4, held.getCount());
        double total = priceOf(held.getItem()) * n * priceMult(d);
        int pay = Math.max(1, (int) Math.round(total));
        held.decrement(n);
        p.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, pay));

        ServerWorld sw = p.getServerWorld();
        float heat = (sw.isDay() ? 7 : 3) + (cops(sw, p.getPos(), 30).isEmpty() ? 0 : 8);
        addHeat(p, d, heat);
        p.sendMessage(Text.literal("Сбыт: " + n + " шт. → " + pay + " изумр.").formatted(Formatting.GREEN), true);
        return ActionResult.SUCCESS;
    }

    static void courierTick(ServerPlayerEntity p, PlayerData d) {
        if (d.couriers <= 0) return;
        int cap = Math.round(d.couriers * 2 * (d.color == 2 ? 1.5f : 1f));
        int sold = 0;
        double total = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size() && sold < cap; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isIn(ModContent.CONTRABAND)) continue;
            while (sold < cap && st.getCount() > 0) {
                total += priceOf(st.getItem());
                st.decrement(1);
                sold++;
            }
        }
        if (sold == 0) return;
        int pay = Math.max(1, (int) Math.round(total * priceMult(d)));
        inv.offerOrDrop(new ItemStack(Items.EMERALD, pay));
        addHeat(p, d, 2 + (cops(p.getServerWorld(), p.getPos(), 30).isEmpty() ? 0 : 4));
        p.sendMessage(Text.literal("Доставщики продали " + sold + " шт. → +" + pay + " изумр.").formatted(Formatting.GREEN), false);
    }

    // ===================== УТИЛИТЫ =====================
    static boolean hasContraband(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isIn(ModContent.CONTRABAND)) return true;
        return false;
    }

    static int count(ServerPlayerEntity p, Item item) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) if (inv.getStack(i).isOf(item)) n += inv.getStack(i).getCount();
        return n;
    }

    static void take(ServerPlayerEntity p, Item item, int n) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size() && n > 0; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isOf(item)) {
                int t = Math.min(n, st.getCount());
                st.decrement(t);
                n -= t;
            }
        }
    }

    // ===================== КОМАНДЫ =====================
    static void commands(CommandDispatcher<ServerCommandSource> disp, CommandRegistryAccess reg, CommandManager.RegistrationEnvironment env) {
        disp.register(CommandManager.literal("bail").executes(c -> bail(c.getSource().getPlayerOrThrow())));

        LiteralArgumentBuilder<ServerCommandSource> biz = CommandManager.literal("biz")
                .executes(c -> status(c.getSource().getPlayerOrThrow()));
        biz.then(CommandManager.literal("hire")
                .then(CommandManager.literal("courier").executes(c -> hire(c.getSource().getPlayerOrThrow(), true)))
                .then(CommandManager.literal("artist").executes(c -> hire(c.getSource().getPlayerOrThrow(), false))));
        LiteralArgumentBuilder<ServerCommandSource> color = CommandManager.literal("color");
        String[] ids = {"red", "blue", "green", "black"};
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            color.then(CommandManager.literal(ids[i]).executes(c -> setColor(c.getSource().getPlayerOrThrow(), idx)));
        }
        biz.then(color);
        disp.register(biz);

        // для тестов (нужны права оператора)
        disp.register(CommandManager.literal("heat").requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.argument("value", IntegerArgumentType.integer(0, 100)).executes(c -> {
                    ServerPlayerEntity p = c.getSource().getPlayerOrThrow();
                    state.get(p.getUuid()).heat = IntegerArgumentType.getInteger(c, "value");
                    return 1;
                })));
    }

    static int bail(ServerPlayerEntity p) {
        PlayerData d = state.get(p.getUuid());
        if (d.jailUntil <= 0) {
            p.sendMessage(Text.literal("Ты не в тюрьме.").formatted(Formatting.GRAY), false);
            return 0;
        }
        if (count(p, Items.EMERALD) < BAIL_COST) {
            p.sendMessage(Text.literal("Нужно " + BAIL_COST + " изумрудов для залога.").formatted(Formatting.RED), false);
            return 0;
        }
        take(p, Items.EMERALD, BAIL_COST);
        release(p.getServer(), p, d, "Залог внесён.");
        return 1;
    }

    static int hire(ServerPlayerEntity p, boolean courier) {
        PlayerData d = state.get(p.getUuid());
        int cost = courier ? COURIER_COST : ARTIST_COST;
        int cur = courier ? d.couriers : d.artists;
        int max = courier ? COURIER_MAX : ARTIST_MAX;
        String name = courier ? "доставщика" : "граффитчика";
        if (cur >= max) {
            p.sendMessage(Text.literal("Максимум: " + max).formatted(Formatting.RED), false);
            return 0;
        }
        if (count(p, Items.EMERALD) < cost) {
            p.sendMessage(Text.literal("Нужно " + cost + " изумрудов, чтобы нанять " + name + ".").formatted(Formatting.RED), false);
            return 0;
        }
        take(p, Items.EMERALD, cost);
        if (courier) d.couriers++; else d.artists++;
        state.markDirty();
        p.sendMessage(Text.literal("Нанят " + (courier ? "доставщик" : "граффитчик") + "! Твой бизнес растёт.").formatted(Formatting.GREEN), false);
        return 1;
    }

    static int setColor(ServerPlayerEntity p, int idx) {
        PlayerData d = state.get(p.getUuid());
        if (d.color == idx) return 0;
        if (count(p, Items.EMERALD) < COLOR_COST) {
            p.sendMessage(Text.literal("Смена цвета банды стоит " + COLOR_COST + " изумр.").formatted(Formatting.RED), false);
            return 0;
        }
        take(p, Items.EMERALD, COLOR_COST);
        d.color = idx;
        state.markDirty();
        p.sendMessage(Text.literal("Цвет банды: " + COLOR_NAMES[idx] + " — " + COLOR_DESC[idx]).formatted(Formatting.AQUA), false);
        return 1;
    }

    static int status(ServerPlayerEntity p) {
        PlayerData d = state.get(p.getUuid());
        p.sendMessage(Text.literal("=== Твой бизнес ===").formatted(Formatting.GOLD), false);
        p.sendMessage(Text.literal("Розыск: " + d.heat + "/100"), false);
        p.sendMessage(Text.literal("Доставщики: " + d.couriers + "/" + COURIER_MAX + "  (/biz hire courier — " + COURIER_COST + " изумр.)"), false);
        p.sendMessage(Text.literal("Граффитчики: " + d.artists + "/" + ARTIST_MAX + "  (/biz hire artist — " + ARTIST_COST + " изумр.)"), false);
        p.sendMessage(Text.literal("Цвет банды: " + COLOR_NAMES[d.color] + " — " + COLOR_DESC[d.color]), false);
        p.sendMessage(Text.literal("Сменить цвет: /biz color red|blue|green|black (" + COLOR_COST + " изумр.)").formatted(Formatting.GRAY), false);
        return 1;
    }
}
