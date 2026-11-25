package com.narcos.expansion;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.world.World;

public class NarcosExpansion implements ModInitializer {
    @Override
    public void onInitialize() {
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            if (!World.OVERWORLD.equals(world.getRegistryKey())) return;
            SettlementGenerator.onChunkLoad(world, chunk);
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                BizCommandOverlay.register(dispatcher));
    }
}
