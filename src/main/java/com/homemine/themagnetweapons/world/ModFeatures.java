package com.homemine.themagnetweapons.world;

import com.homemine.themagnetweapons.MagneticWeapons;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModFeatures {
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, MagneticWeapons.MODID);

    /** 愚人金矿脉（上下暴露空气则不生成）。 */
    public static final DeferredHolder<Feature<?>, FoolGoldOreFeature> FOOL_GOLD_ORE =
            FEATURES.register("fool_gold_ore", () -> new FoolGoldOreFeature(NoneFeatureConfiguration.CODEC));

    private ModFeatures() {
    }
}
