package com.narcos;

import net.minecraft.block.CropBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemConvertible;

import java.util.function.Supplier;

public class GreenCropBlock extends CropBlock {
    private final Supplier<Item> seed;

    public GreenCropBlock(Settings settings, Supplier<Item> seed) {
        super(settings);
        this.seed = seed;
    }

    @Override
    protected ItemConvertible getSeedsItem() {
        return seed.get();
    }
}
