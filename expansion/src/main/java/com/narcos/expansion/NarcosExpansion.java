package com.narcos.expansion;

import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.world.World;

public class NarcosExpansion implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (!World.OVERWORLD.equals(world.getRegistryKey())) return;
            SettlementGenerator.onChunkLoad(world, chunk);
        });

        // Добавляем подкоманду к /biz основного мода, не заменяя его механику.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            CommandNode<ServerCommandSource> biz = dispatcher.getRoot().getChild("biz");
            var location = CommandManager.literal("location")
                    .executes(context -> {
                        SettlementLayout.sendInfo(context.getSource().getPlayerOrThrow());
                        return 1;
                    }).build();

            if (biz != null) {
                biz.addChild(location);
            } else {
                dispatcher.register(CommandManager.literal("biz").then(CommandManager.literal("location")
                        .executes(context -> {
                            SettlementLayout.sendInfo(context.getSource().getPlayerOrThrow());
                            return 1;
                        })));
            }
        });
    }
}
