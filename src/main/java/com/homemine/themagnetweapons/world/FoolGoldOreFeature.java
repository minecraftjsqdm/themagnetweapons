package com.homemine.themagnetweapons.world;

import com.homemine.themagnetweapons.ModBlocks;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * 愚人金矿脉：在石头 / 深板岩中生成 3~6 块。
 * 上下任意一侧暴露于空气的位置跳过，所以矿脉只出现在实心岩层内部。
 */
public class FoolGoldOreFeature extends Feature<NoneFeatureConfiguration> {
    private static final int MIN_VEIN = 3;
    private static final int MAX_VEIN = 6;
    private static final int SPREAD = 2;

    public FoolGoldOreFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        RandomSource random = context.random();
        BlockState stoneOre = ModBlocks.FOOL_GOLD_ORE.get().defaultBlockState();
        BlockState deepslateOre = ModBlocks.DEEPSLATE_FOOL_GOLD_ORE.get().defaultBlockState();

        int target = MIN_VEIN + random.nextInt(MAX_VEIN - MIN_VEIN + 1);
        int placed = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int attempt = 0; attempt < target * 3 && placed < target; attempt++) {
            cursor.set(origin.getX() + random.nextInt(SPREAD * 2 + 1) - SPREAD,
                    origin.getY() + random.nextInt(SPREAD * 2 + 1) - SPREAD,
                    origin.getZ() + random.nextInt(SPREAD * 2 + 1) - SPREAD);
            BlockState current = level.getBlockState(cursor);
            boolean inDeepslate = current.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES);
            boolean inStone = current.is(BlockTags.STONE_ORE_REPLACEABLES);
            if (!inDeepslate && !inStone) {
                continue;
            }
            BlockPos pos = cursor.immutable();
            if (level.isEmptyBlock(pos.above()) || level.isEmptyBlock(pos.below())) {
                continue;
            }
            level.setBlock(pos, inDeepslate || pos.getY() < 0 ? deepslateOre : stoneOre, 2);
            placed++;
        }
        return placed > 0;
    }
}
