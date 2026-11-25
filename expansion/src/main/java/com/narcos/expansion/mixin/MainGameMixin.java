package com.narcos.expansion.mixin;

import com.narcos.expansion.ExpansionState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.narcos.Game", remap = false)
public abstract class MainGameMixin {
    private static final ThreadLocal<Integer> NARCOS_EXPANSION_BEFORE_SALE = new ThreadLocal<>();

    @Inject(method = "onUseEntity", at = @At("HEAD"), remap = false)
    private static void narcosExpansion$captureSale(
            PlayerEntity player, World world, Hand hand, Entity target, EntityHitResult hit,
            CallbackInfoReturnable<ActionResult> cir) {
        NARCOS_EXPANSION_BEFORE_SALE.set(player.getStackInHand(hand).getCount());
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
            serverPlayer.sendMessage(net.minecraft.text.Text.literal(
                    "Житель перешёл на твой сбыт — ты переманил клиента.")
                    .formatted(net.minecraft.util.Formatting.GREEN), false);
        }
    }
}
