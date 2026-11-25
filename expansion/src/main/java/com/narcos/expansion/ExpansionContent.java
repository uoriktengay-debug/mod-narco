package com.narcos.expansion;

import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public final class ExpansionContent {
    public static final String ID = "narcos_expansion";

    public static Block LAB_MARKER;
    public static Block LAB_BENCH;
    public static Item SCIENTIST_CONTRACT;

    private ExpansionContent() { }

    public static Identifier id(String path) {
        return new Identifier(ID, path);
    }

    private static Block registerBlock(String name, Block block) {
        Registry.register(Registries.BLOCK, id(name), block);
        Registry.register(Registries.ITEM, id(name), new BlockItem(block, new FabricItemSettings()));
        return block;
    }

    public static void init() {
        LAB_MARKER = registerBlock("lab_marker",
                new Block(AbstractBlock.Settings.copy(Blocks.REDSTONE_BLOCK).strength(1.5f)));
        LAB_BENCH = registerBlock("lab_bench",
                new Block(AbstractBlock.Settings.copy(Blocks.CRAFTING_TABLE).strength(2.5f)));
        SCIENTIST_CONTRACT = Registry.register(Registries.ITEM, id("scientist_contract"),
                new Item(new FabricItemSettings().maxCount(16)));

        Registry.register(Registries.ITEM_GROUP, id("main"), FabricItemGroup.builder()
                .icon(() -> new ItemStack(LAB_BENCH))
                .displayName(Text.literal("Narcos Expansion"))
                .entries((context, entries) -> {
                    entries.add(LAB_MARKER);
                    entries.add(LAB_BENCH);
                    entries.add(SCIENTIST_CONTRACT);
                }).build());
    }
}
