package com.narcos;

import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.AliasedBlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.loot.LootPool;
import net.minecraft.loot.condition.RandomChanceLootCondition;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ModContent {
    public static final String ID = "narcos";
    public static final TagKey<Item> CONTRABAND = TagKey.of(RegistryKeys.ITEM, new Identifier(ID, "contraband"));

    public static Block HEMP_CROP, POPPY_CROP;
    public static Item HEMP_SEED, HEMP_LEAF, HEMP_PACK, POPPY_SEED, POPPY_POD, POPPY_PACK;
    public static Item PRECURSOR, SOLVENT, RAW_POWDER, CRYSTAL, GROW_BOOST;

    static Identifier id(String path) {
        return new Identifier(ID, path);
    }

    static Item simple(String name) {
        return Registry.register(Registries.ITEM, id(name), new Item(new FabricItemSettings()));
    }

    public static void init() {
        HEMP_CROP = Registry.register(Registries.BLOCK, id("hemp_crop"),
                new GreenCropBlock(AbstractBlock.Settings.copy(Blocks.WHEAT), () -> HEMP_SEED));
        POPPY_CROP = Registry.register(Registries.BLOCK, id("poppy_crop"),
                new GreenCropBlock(AbstractBlock.Settings.copy(Blocks.WHEAT), () -> POPPY_SEED));

        HEMP_SEED = Registry.register(Registries.ITEM, id("hemp_seed"), new AliasedBlockItem(HEMP_CROP, new FabricItemSettings()));
        POPPY_SEED = Registry.register(Registries.ITEM, id("poppy_seed"), new AliasedBlockItem(POPPY_CROP, new FabricItemSettings()));
        HEMP_LEAF = simple("hemp_leaf");
        HEMP_PACK = simple("hemp_pack");
        POPPY_POD = simple("poppy_pod");
        POPPY_PACK = simple("poppy_pack");
        PRECURSOR = simple("precursor");
        SOLVENT = simple("solvent");
        RAW_POWDER = simple("raw_powder");
        CRYSTAL = simple("crystal");
        GROW_BOOST = simple("grow_boost");

        Registry.register(Registries.ITEM_GROUP, id("main"), FabricItemGroup.builder()
                .icon(() -> new ItemStack(HEMP_PACK))
                .displayName(Text.literal("Narcos"))
                .entries((ctx, e) -> {
                    e.add(HEMP_SEED); e.add(HEMP_LEAF); e.add(HEMP_PACK);
                    e.add(POPPY_SEED); e.add(POPPY_POD); e.add(POPPY_PACK);
                    e.add(PRECURSOR); e.add(SOLVENT); e.add(RAW_POWDER); e.add(CRYSTAL); e.add(GROW_BOOST);
                }).build());

        // Семена находятся при срубании травы и сборе маков
        LootTableEvents.MODIFY.register((rm, lm, tableId, table, source) -> {
            if (tableId.equals(new Identifier("minecraft", "blocks/grass"))) {
                table.pool(LootPool.builder().rolls(ConstantLootNumberProvider.create(1))
                        .conditionally(RandomChanceLootCondition.builder(0.10f))
                        .with(ItemEntry.builder(HEMP_SEED)));
            }
            if (tableId.equals(new Identifier("minecraft", "blocks/poppy"))) {
                table.pool(LootPool.builder().rolls(ConstantLootNumberProvider.create(1))
                        .conditionally(RandomChanceLootCondition.builder(0.5f))
                        .with(ItemEntry.builder(POPPY_SEED)));
            }
        });
    }
}
