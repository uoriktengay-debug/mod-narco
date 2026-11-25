package com.narcos;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.narcos.NarcosState.PlayerData;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.AbstractFurnaceBlock;
import net.minecraft.block.BlastFurnaceBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.PaneBlock;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import net.minecraft.world.GameMode;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Вся игровая логика: розыск, копы, тюрьма, продажа, бизнес, поселения, барыги. */
public class Game {
    // ===== Настройки =====
    static final int JAIL_TICKS = 20 * 120;      // базовый срок 2 мин (× судимости)
    static final int BAIL_BASE = 5;              // залог (+5 за каждую судимость)
    static final int SQUATS_BASE = 30;           // приседания (+20 за судимость)
    static final int COURIER_COST = 8, COURIER_MAX = 8;
    static final int ARTIST_COST = 12, ARTIST_MAX = 10;
    static final int COLOR_COST = 4;
    static final String COP_TAG = "narcos_police";
    static final String DEALER_TAG = "narcos_dealer";
    static final String[] COLOR_NAMES = {"красный", "синий", "зелёный", "чёрный"};
    static final String[] COLOR_DESC = {
            "цена товара +25%, но розыск растёт на 25% быстрее",
            "розыск растёт на 30% медленнее",
            "доставщики продают на 50% больше",
            "ночью розыск растёт в 2.5 раза медленнее"};

    // Типы местности: 0 глушь, 1 село, 2 город, 3 столица
    static final String[] TIER_NAMES = {"глушь", "село", "город", "столица"};
    static final int[] THR = {90, 70, 50, 40};                 // с какого розыска копы реально ловят
    static final float[] TIER_HEAT = {0.3f, 0.6f, 1.0f, 1.6f}; // как быстро растёт розыск
    static final float[] TIER_PRICE = {1.0f, 0.8f, 1.0f, 1.4f};
    static final int[] TIER_MAXSELL = {2, 2, 4, 8};            // сколько штук за один клик
    static final int[] HOOK_AT = {5, 5, 3, 2};                 // после скольких продаж житель «подсел»
    static final float[] REFUSE = {0f, 0f, 0.15f, 0.35f};     // шанс отказа (уже есть поставщик)

    static NarcosState state;
    static long tick;
    static final Map<UUID, Boolean> sneak = new HashMap<>();
    static final Map<UUID, Integer> squats = new HashMap<>();

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> state = NarcosState.of(s));
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> state = null);
        ServerTickEvents.END_SERVER_TICK.register(Game::onTick);
        UseEntityCallback.EVENT.register(Game::onUseEntity);
        UseBlockCallback.EVENT.register(Game::onUseBlock);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(source.getAttacker() != null && source.getAttacker().getCommandTags().contains(COP_TAG)));
        CommandRegistrationCallback.EVENT.register(Game::commands);
    }

    // ===================== ПОСЕЛЕНИЯ =====================
    /** Тип местности по числу жителей вокруг (радиус 64). */
    static int tierAt(ServerWorld w, Vec3d pos) {
        int n = w.getEntitiesByClass(VillagerEntity.class, Box.of(pos, 128, 64, 128), e -> true).size();
        if (n == 0) return 0;
        if (n < 8) return 1;
        if (n < 20) return 2;
        return 3;
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
        int tier = tierAt(w, p.getPos());
        if (tick % 80 == 0 && d.heat > 0) d.heat = Math.max(0, d.heat - (has ? 1 : 2));

        // Что видно и чувствуется снаружи
        boolean exposed = false, smell = false;
        BlockPos c = p.getBlockPos();
        for (BlockPos pos : BlockPos.iterate(c.add(-8, -4, -8), c.add(8, 4, 8))) {
            BlockState st = w.getBlockState(pos);
            if (!exposed && st.getBlock() instanceof GreenCropBlock && visible(w, pos.toImmutable())) exposed = true;
            if (!smell && st.getBlock() instanceof BlastFurnaceBlock && st.get(AbstractFurnaceBlock.LIT)) smell = true;
        }
        if (exposed) addHeat(p, d, w.isDay() ? 6f : 3f);
        if (smell) addHeat(p, d, 5f);
        if (has && w.isDay() && tier >= 2) addHeat(p, d, 1f);

        // Судимость постепенно гасится
        if (tick % 24000 == 0 && d.record > 0 && d.heat < 10) {
            d.record--;
            p.sendMessage(Text.literal("Одна судимость погашена. Осталось: " + d.record).formatted(Formatting.GREEN), false);
        }

        // Полиция
        int thr = THR[tier];
        List<IronGolemEntity> cops = cops(w, p.getPos(), 48);
        if (d.heat < thr - 20 || !has) {
            for (IronGolemEntity cop : cops) {
                if (d.heat < thr - 20) cop.discard();
                else cop.setTarget(null);
            }
        } else {
            for (IronGolemEntity cop : cops) cop.setTarget(p);
            int want = (d.heat >= 80 ? 3 : 1) + (tier == 3 ? 1 : 0);
            if (d.heat >= thr && cops.size() < want && tick % 200 == 0) {
                if (spawnCop(w, p)) {
                    p.sendMessage(Text.literal("Копы заметили тебя!").formatted(Formatting.RED), false);
                }
            }
        }

        // Барыги
        List<WanderingTraderEntity> dealers = dealers(w, p.getPos(), 100);
        for (WanderingTraderEntity dl : dealers) {
            dl.removeStatusEffect(StatusEffects.INVISIBILITY);
            if (dl.age > 14000 && w.getClosestPlayer(dl, 24) == null) dl.discard();
        }
        if (tick % 600 == 0 && tier >= 1 && dealers.isEmpty() && w.random.nextFloat() < 0.4f) {
            if (spawnDealer(w, p, tier)) {
                p.sendMessage(Text.literal("Слухи: поблизости ходит барыга (" + TIER_NAMES[tier] + "). Ищи тёмно-красное имя.").formatted(Formatting.DARK_GRAY), false);
            }
        }

        // Шкала
        if (d.heat > 0 || exposed || smell) {
            int f = Math.min(10, d.heat / 10);
            String bar = "█".repeat(f) + "░".repeat(10 - f);
            Formatting col = d.heat >= 80 ? Formatting.RED : d.heat >= THR[tier] ? Formatting.GOLD : Formatting.YELLOW;
            p.sendMessage(Text.literal("Розыск " + bar + " " + d.heat + " | " + TIER_NAMES[tier]
                    + (exposed ? " ⚠ плантацию видно" : "") + (smell ? " ☠ вонь из лаборатории" : "")).formatted(col), true);
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
        float m = TIER_HEAT[tierAt(p.getServerWorld(), p.getPos())];
        if (d.color == 0) m *= 1.25f;
        if (d.color == 1) m *= 0.7f;
        if (d.color == 3 && !p.getServerWorld().isDay()) m *= 0.4f;
        d.heat = Math.max(0, Math.min(100, d.heat + Math.round(amount * m)));
    }

    // ===================== ПОЛИЦИЯ И БАРЫГИ =====================
    static List<IronGolemEntity> cops(ServerWorld w, Vec3d pos, double r) {
        return w.getEntitiesByClass(IronGolemEntity.class, Box.of(pos, r * 2, r * 2, r * 2),
                e -> e.getCommandTags().contains(COP_TAG));
    }

    static List<WanderingTraderEntity> dealers(ServerWorld w, Vec3d pos, double r) {
        return w.getEntitiesByClass(WanderingTraderEntity.class, Box.of(pos, r * 2, r * 2, r * 2),
                e -> e.getCommandTags().contains(DEALER_TAG));
    }

    static BlockPos findSpot(ServerWorld w, ServerPlayerEntity p, double minD, double maxD) {
        for (int i = 0; i < 25; i++) {
            double ang = w.random.nextDouble() * Math.PI * 2;
            double dist = minD + w.random.nextDouble() * (maxD - minD);
            int x = p.getBlockX() + (int) (Math.cos(ang) * dist);
            int z = p.getBlockZ() + (int) (Math.sin(ang) * dist);
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos pos = new BlockPos(x, p.getBlockY() + dy, z);
                if (w.getBlockState(pos.down()).isSolidBlock(w, pos.down())
                        && w.getBlockState(pos).isAir() && w.getBlockState(pos.up()).isAir()
                        && w.getBlockState(pos.up(2)).isAir()) return pos;
            }
        }
        return null;
    }

    static boolean spawnCop(ServerWorld w, ServerPlayerEntity p) {
        BlockPos pos = findSpot(w, p, 10, 18);
        if (pos == null) return false;
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

    static void offer(TradeOfferList l, int price, Item item, int count) {
        l.add(new TradeOffer(new ItemStack(Items.EMERALD, price), new ItemStack(item, count), 8, 0, 0.05f));
    }

    static boolean spawnDealer(ServerWorld w, ServerPlayerEntity p, int tier) {
        BlockPos pos = findSpot(w, p, 12, 26);
        if (pos == null) return false;
        WanderingTraderEntity t = EntityType.WANDERING_TRADER.create(w);
        if (t == null) return false;
        t.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        t.addCommandTag(DEALER_TAG);
        t.addCommandTag("narcos_tier_" + tier);
        t.setCustomName(Text.literal("Барыга").formatted(Formatting.DARK_RED, Formatting.BOLD));
        t.setCustomNameVisible(true);
        t.setPersistent();
        TradeOfferList l = t.getOffers();
        l.clear();
        offer(l, 2, ModContent.HEMP_SEED, 2);
        offer(l, 3, ModContent.POPPY_SEED, 2);
        if (tier >= 2) {
            offer(l, 6, ModContent.GROW_BOOST, 1);
            offer(l, 8, ModContent.PRECURSOR, 1);
        }
        if (tier >= 3) {
            offer(l, 4, ModContent.SOLVENT, 1);
        }
        w.spawnEntity(t);
        return true;
    }

    static void arrestCheck(MinecraftServer s, ServerPlayerEntity p, PlayerData d) {
        if (d.heat < 40 || !hasContraband(p)) return;
        ServerWorld w = p.getServerWorld();
        int tier = tierAt(w, p.getPos());
        if (d.heat < THR[tier]) return;
        if (!cops(w, p.getPos(), 3.0).isEmpty()) arrest(s, p, d);
    }

    // ===================== ТЮРЬМА =====================
    static void arrest(MinecraftServer s, ServerPlayerEntity p, PlayerData d) {
        int rec = d.record;
        int seized = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            boolean seed = rec >= 2 && (st.isOf(ModContent.HEMP_SEED) || st.isOf(ModContent.POPPY_SEED));
            if (st.isIn(ModContent.CONTRABAND) || seed) {
                seized += st.getCount();
                inv.setStack(i, ItemStack.EMPTY);
            }
        }
        int fine = Math.min(count(p, Items.EMERALD), 3 * (rec + 1));
        take(p, Items.EMERALD, fine);
        boolean fired = false;
        if (rec >= 3) {
            if (d.couriers > 0) { d.couriers--; fired = true; }
            if (d.artists > 0) { d.artists--; fired = true; }
        }

        ServerWorld w = p.getServerWorld();
        for (IronGolemEntity cop : cops(w, p.getPos(), 64)) cop.discard();

        d.heat = 0;
        d.record = Math.min(10, rec + 1);
        d.squatsNeeded = SQUATS_BASE + 20 * rec;
        d.bailCost = BAIL_BASE + 5 * rec;
        d.rx = p.getX(); d.ry = p.getY(); d.rz = p.getZ();
        d.rdim = w.getRegistryKey().getValue().toString();
        d.prevMode = p.interactionManager.getGameMode().getId();

        ServerWorld ow = s.getOverworld();
        BlockPos sp = ow.getSpawnPos();
        buildJail(ow, sp.getX(), 250, sp.getZ());
        d.jailUntil = ow.getTime() + (long) JAIL_TICKS * (1 + Math.min(rec, 4));
        squats.put(p.getUuid(), 0);
        sneak.put(p.getUuid(), false);

        p.changeGameMode(GameMode.ADVENTURE);
        p.teleport(ow, sp.getX() + 0.5, 251, sp.getZ() + 0.5, 0, 0);
        p.sendMessage(Text.literal("ТЕБЯ ЗАДЕРЖАЛИ! Изъято товара: " + seized + " шт., штраф: " + fine + " изумр.").formatted(Formatting.RED, Formatting.BOLD), false);
        p.sendMessage(Text.literal("Судимостей: " + d.record + (rec >= 2 ? " (изъяты и семена)" : "")
                + (fired ? " | часть твоих людей сбежала" : "")).formatted(Formatting.DARK_RED), false);
        p.sendMessage(Text.literal("Выход: срок " + (JAIL_TICKS * (1 + Math.min(rec, 4)) / 1200) + " мин, или " + d.squatsNeeded
                + " приседаний (Shift), или /bail (" + d.bailCost + " изумр.).").formatted(Formatting.GRAY), false);
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
        int need = d.squatsNeeded > 0 ? d.squatsNeeded : SQUATS_BASE;
        boolean sn = p.isSneaking();
        if (sn && !sneak.getOrDefault(id, false)) {
            int c = squats.merge(id, 1, Integer::sum);
            if (c >= need) {
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
                    + "/" + need + " | /bail = " + (d.bailCost > 0 ? d.bailCost : BAIL_BASE) + " изумр.").formatted(Formatting.GRAY), true);
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
        if (i == ModContent.RAW_POWDER) return 3.0;
        if (i == ModContent.CRYSTAL) return 10.0;
        return 0;
    }

    static double priceMult(PlayerData d) {
        return (1 + 0.08 * d.artists) * (d.color == 0 ? 1.25 : 1.0);
    }

    static int getSold(Entity e) {
        for (String t : e.getCommandTags()) {
            if (t.startsWith("nsold_")) {
                try { return Integer.parseInt(t.substring(6)); } catch (Exception ignored) { }
            }
        }
        return 0;
    }

    static void setSold(Entity e, int n) {
        for (String t : new ArrayList<>(e.getCommandTags())) if (t.startsWith("nsold_")) e.removeScoreboardTag(t);
        e.addCommandTag("nsold_" + n);
    }

    static ActionResult onUseEntity(PlayerEntity pl, World w, Hand h, Entity e, EntityHitResult hit) {
        if (!(e instanceof VillagerEntity)) return ActionResult.PASS;
        ItemStack held = pl.getStackInHand(h);
        if (!held.isIn(ModContent.CONTRABAND)) return ActionResult.PASS;
        if (w.isClient || state == null || !(pl instanceof ServerPlayerEntity p)) return ActionResult.SUCCESS;

        PlayerData d = state.get(p.getUuid());
        if (d.jailUntil > 0) return ActionResult.FAIL;
        ServerWorld sw = p.getServerWorld();
        int tier = Math.max(1, tierAt(sw, e.getPos()));
        int sold = getSold(e);
        boolean hooked = sold >= HOOK_AT[tier];
        boolean dayHeat = sw.isDay();
        float baseHeat = (dayHeat ? 14 : 6) + (cops(sw, p.getPos(), 30).isEmpty() ? 0 : 8);

        // Конкуренция: у него уже есть поставщик
        if (!hooked && sw.random.nextFloat() < REFUSE[tier]) {
            addHeat(p, d, 3);
            p.sendMessage(Text.literal("«У меня уже есть свой поставщик.» (" + TIER_NAMES[tier] + ": конкуренция)").formatted(Formatting.GRAY), true);
            return ActionResult.SUCCESS;
        }

        int n = Math.min(TIER_MAXSELL[tier] * (hooked ? 2 : 1), held.getCount());
        double total = priceOf(held.getItem()) * n * priceMult(d) * TIER_PRICE[tier] * (hooked ? 1.4 : 1.0);
        int pay = Math.max(1, (int) Math.round(total));
        held.decrement(n);
        p.getInventory().offerOrDrop(new ItemStack(Items.EMERALD, pay));
        setSold(e, sold + 1);
        addHeat(p, d, baseHeat);
        p.sendMessage(Text.literal("Сбыт (" + TIER_NAMES[tier] + "): " + n + " шт. → " + pay + " изумр."
                + (hooked ? "  — он на крючке!" : "")).formatted(Formatting.GREEN), true);
        return ActionResult.SUCCESS;
    }

    /** Стимулятор роста: правый клик по кусту. */
    static ActionResult onUseBlock(PlayerEntity pl, World w, Hand h, BlockHitResult hit) {
        ItemStack st = pl.getStackInHand(h);
        if (!st.isOf(ModContent.GROW_BOOST)) return ActionResult.PASS;
        BlockPos bp = hit.getBlockPos();
        BlockState bs = w.getBlockState(bp);
        if (!(bs.getBlock() instanceof GreenCropBlock crop)) return ActionResult.PASS;
        int age = bs.get(CropBlock.AGE);
        if (age >= 7) return ActionResult.FAIL;
        if (!w.isClient) {
            w.setBlockState(bp, crop.withAge(Math.min(7, age + 4)), 2);
            ((ServerWorld) w).spawnParticles(ParticleTypes.HAPPY_VILLAGER, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 10, 0.3, 0.3, 0.3, 0.0);
            if (!pl.isCreative()) st.decrement(1);
        }
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
        addHeat(p, d, 4 + (cops(p.getServerWorld(), p.getPos(), 30).isEmpty() ? 0 : 6));
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
        int cost = d.bailCost > 0 ? d.bailCost : BAIL_BASE;
        if (count(p, Items.EMERALD) < cost) {
            p.sendMessage(Text.literal("Нужно " + cost + " изумрудов для залога.").formatted(Formatting.RED), false);
            return 0;
        }
        take(p, Items.EMERALD, cost);
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
        int tier = tierAt(p.getServerWorld(), p.getPos());
        p.sendMessage(Text.literal("=== Твой бизнес ===").formatted(Formatting.GOLD), false);
        p.sendMessage(Text.literal("Розыск: " + d.heat + "/100 | Судимостей: " + d.record), false);
        p.sendMessage(Text.literal("Ты сейчас: " + TIER_NAMES[tier] + " (копы ловят с розыска " + THR[tier] + ")"), false);
        p.sendMessage(Text.literal("Доставщики: " + d.couriers + "/" + COURIER_MAX + "  (/biz hire courier — " + COURIER_COST + " изумр.)"), false);
        p.sendMessage(Text.literal("Граффитчики: " + d.artists + "/" + ARTIST_MAX + "  (/biz hire artist — " + ARTIST_COST + " изумр.)"), false);
        p.sendMessage(Text.literal("Цвет банды: " + COLOR_NAMES[d.color] + " — " + COLOR_DESC[d.color]), false);
        p.sendMessage(Text.literal("Сменить цвет: /biz color red|blue|green|black (" + COLOR_COST + " изумр.)").formatted(Formatting.GRAY), false);
        return 1;
    }
}
