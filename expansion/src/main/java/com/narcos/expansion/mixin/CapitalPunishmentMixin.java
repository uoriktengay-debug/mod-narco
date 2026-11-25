package com.narcos.expansion.mixin;

import com.narcos.NarcosState;
import com.narcos.expansion.ExpansionState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.narcos.Game", remap = false)
public abstract class CapitalPunishmentMixin {
    private static final int NARCOS_EXPANSION_MIN_CONVICTIONS = 10;
    private static final int NARCOS_EXPANSION_MIN_SOLD_UNITS = 500;

    @Inject(method = "arrest", at = @At("HEAD"), cancellable = true, remap = false)
    private static void narcosExpansion$capitalPunishment(
            MinecraftServer server, ServerPlayerEntity player, NarcosState.PlayerData data,
            CallbackInfo ci) {
        if (player.isCreative() || player.isSpectator()) return;

        int convictionsIncludingThisArrest = data.record + 1;
        int soldUnits = ExpansionState.of(server).salesForPlayer(player.getUuid());
        if (convictionsIncludingThisArrest < NARCOS_EXPANSION_MIN_CONVICTIONS
                || soldUnits < NARCOS_EXPANSION_MIN_SOLD_UNITS) {
            return;
        }

        data.heat = 0;
        data.record = 0;
        data.jailUntil = 0L;
        data.couriers = 0;
        data.artists = 0;
        NarcosState.of(server).markDirty();

        Text verdict = Text.literal("СУД: назначена высшая мера наказания.")
                .formatted(Formatting.DARK_RED, Formatting.BOLD);
        server.getPlayerManager().broadcast(verdict, false);
        player.sendMessage(Text.literal("Основание: 10-я судимость и не менее 500 проданных единиц товара.")
                .formatted(Formatting.RED), false);

        player.damage(player.getDamageSources().outOfWorld(), 10000.0F);
        ci.cancel();
    }
}
