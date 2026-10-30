package com.homemine.themagnetweapons;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.util.valueproviders.UniformInt;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 愚人金矿石两个变种与对应方块物品。 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MagneticWeapons.MODID);

    public static final DeferredBlock<Block> FOOL_GOLD_ORE = BLOCKS.register("fool_gold_ore",
            () -> new DropExperienceBlock(UniformInt.of(0, 2),
                    Block.Properties.ofFullCopy(Blocks.GOLD_ORE)));

    public static final DeferredBlock<Block> DEEPSLATE_FOOL_GOLD_ORE = BLOCKS.register("deepslate_fool_gold_ore",
            () -> new DropExperienceBlock(UniformInt.of(0, 2),
                    Block.Properties.ofFullCopy(Blocks.DEEPSLATE_GOLD_ORE)));

    public static final DeferredItem<net.minecraft.world.item.BlockItem> FOOL_GOLD_ORE_ITEM =
            ModItems.ITEMS.registerSimpleBlockItem("fool_gold_ore", FOOL_GOLD_ORE);

    public static final DeferredItem<net.minecraft.world.item.BlockItem> DEEPSLATE_FOOL_GOLD_ORE_ITEM =
            ModItems.ITEMS.registerSimpleBlockItem("deepslate_fool_gold_ore", DEEPSLATE_FOOL_GOLD_ORE);

    private ModBlocks() {
    }
}
