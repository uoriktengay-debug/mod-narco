package com.narcos.expansion.mixin;

import com.narcos.expansion.ExpansionState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

@Mixin(targets = "com.narcos.Game", remap = false)
public abstract class MainGameMixin {
    private static final float NARCOS_EXPANSION_RIVAL_SALE_CHANCE = 0.35F;
    private static final TagKey<Item> NARCOS_EXPANSION_CONTRABAND =
            TagKey.of(RegistryKeys.ITEM, new Identifier("narcos", "contraband"));
    private static final ThreadLocal<Integer> NARCOS_EXPANSION_BEFORE_SALE = new ThreadLocal<>();

    @Inject(method = "onUseEntity", at = @At("HEAD"), remap = false)
    private static void narcosExpansion$captureSale(
            PlayerEntity player, World world, Hand hand, Entity target, EntityHitResult hit,
            CallbackInfoReturnable<ActionResult> cir) {
        NARCOS_EXPANSION_BEFORE_SALE.set(player.getStackInHand(hand).getCount());

        if (world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)
                || !(target instanceof VillagerEntity)) {
            return;
        }

        ItemStack held = player.getStackInHand(hand);
        if (!held.isIn(NARCOS_EXPANSION_CONTRABAND)) return;

        MinecraftServer server = serverPlayer.getServer();
        if (server == null) return;
        UUID incumbent = ExpansionState.of(server).preferredSupplier(target.getUuid());
        if (incumbent == null || incumbent.equals(serverPlayer.getUuid())) return;

        // A rival's customer is harder to win over, but not impossible to steal.
        if (world.random.nextFloat() >= NARCOS_EXPANSION_RIVAL_SALE_CHANCE) {
            serverPlayer.sendMessage(Text.literal(
                    "У этого жителя уже есть постоянный поставщик. Попробуй переманить его.")
                    .formatted(Formatting.GRAY), false);
            cir.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(method = "onUseEntity", at = @At("RETURN"), remap = false)
    private static void narcosExpansion$recordSale(
            PlayerEntity player, World world, Hand hand, Entity target, EntityHitResult hit,
            CallbackInfoReturnable<ActionResult> cir) {
        Integer before = NARCOS_EXPANSION_BEFORE_SALE.get();
        NARCOS_EXPANSION_BEFORE_SALE.remove();

        if (before == null || world.isClient || !(player instanceof ServerPlayerEntity serverPlayer)
                || !(target instanceof VillagerEntity)
                || cir.getReturnValue() != ActionResult.SUCCESS) {
            return;
        }

        ItemStack remaining = player.getStackInHand(hand);
        int soldUnits = before - remaining.getCount();
        if (soldUnits <= 0) return;

        MinecraftServer server = serverPlayer.getServer();
        if (server == null) return;

        boolean converted = ExpansionState.of(server)
                .recordSale(serverPlayer.getUuid(), target.getUuid(), soldUnits);
        if (converted) {
            serverPlayer.sendMessage(Text.literal(
                    "Житель перешёл на твой сбыт — ты переманил клиента.")
                    .formatted(Formatting.GREEN), false);
        }
    }
}
