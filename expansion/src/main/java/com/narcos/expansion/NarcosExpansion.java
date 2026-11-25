package com.narcos.expansion;

import net.fabricmc.api.ModInitializer;

public final class NarcosExpansion implements ModInitializer {
    @Override
    public void onInitialize() {
        ExpansionContent.init();
        ExpansionGame.init();
    }
}
