package com.narcos.expansion;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.WanderingTraderEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

public final class ExpansionGame {
    private static long ticks;

    private ExpansionGame() { }

    public static void init() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient && world.getBlockState(hit.getBlockPos()).isOf(ExpansionContent.LAB_BENCH)
                    && player instanceof ServerPlayerEntity) {
                LabManager.use((ServerWorld) world, hit.getBlockPos(), player, player.getStackInHand(hand));
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        ServerTickEvents.END_SERVER_TICK.register(ExpansionGame::tick);
    }

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % 100 == 0) {
            for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
                SettlementService.updatePlayer(server, player, ticks);
            }
        }
        if (ticks % 40 == 0) {
            for (ServerWorld world : server.getWorlds()) {
                for (ServerPlayerEntity player : world.getPlayers()) {
                    addLabTrades(world, player);
                }
            }
        }
    }

    private static void addLabTrades(ServerWorld world, ServerPlayerEntity player) {
        Box area = Box.of(player.getPos(), 192, 96, 192);
        for (WanderingTraderEntity trader : world.getEntitiesByClass(WanderingTraderEntity.class, area,
                entity -> entity.getCommandTags().contains("narcos_dealer"))) {
            TradeOfferList offers = trader.getOffers();
            addOfferIfMissing(offers, ExpansionContent.LAB_MARKER, 12, 4);
            addOfferIfMissing(offers, ExpansionContent.LAB_BENCH, 10, 1);
            addOfferIfMissing(offers, ExpansionContent.SCIENTIST_CONTRACT, 6, 1);
        }
    }

    private static void addOfferIfMissing(TradeOfferList offers, Item item, int price, int amount) {
        for (TradeOffer offer : offers) {
            if (offer.getSellItem().isOf(item)) return;
        }
        offers.add(new TradeOffer(new ItemStack(Items.EMERALD, price),
                new ItemStack(item, amount), 12, 1, 0.05f));
    }
}
