package com.narcos.expansion;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.World;

public class NarcosExpansion implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (!World.OVERWORLD.equals(world.getRegistryKey())) return;
            SettlementGenerator.onChunkLoad(world, chunk);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CommandNode<ServerCommandSource> biz = dispatcher.getRoot().getChild("biz");
            LiteralArgumentBuilder<ServerCommandSource> location = net.minecraft.server.command.CommandManager
                    .literal("location")
                    .executes(context -> showLocation(context.getSource().getPlayerOrThrow()));

            if (biz != null) {
                biz.addChild(location.build());
            } else {
                dispatcher.register(net.minecraft.server.command.CommandManager.literal("biz")
                        .then(location));
            }
        });
    }

    private static int showLocation(ServerPlayerEntity player) {
        SettlementLayout.sendInfo(player);
        return 1;
    }
}
