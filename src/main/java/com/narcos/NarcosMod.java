package com.narcos;

import net.fabricmc.api.ModInitializer;

public class NarcosMod implements ModInitializer {
    @Override
    public void onInitialize() {
        ModContent.init();
        Game.init();
    }
}
