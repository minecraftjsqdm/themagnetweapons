package com.homemine.themagnetweapons;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * 附魔键与等级查询。
 */
public final class Enchants {
    public static final ResourceKey<Enchantment> WRIGGLER = key(MagneticWeapons.MODID, "wriggler");
    public static final ResourceKey<Enchantment> STABILIZER = key(MagneticWeapons.MODID, "stabilizer");
    /** 忠诚：链接中断时立即归还投出的工具，按等级扣耐久。数据与逻辑均由本模组提供。 */
    public static final ResourceKey<Enchantment> LOYALTY = key(MagneticWeapons.MODID, "loyalty");
    /** 原版三叉戟忠诚，仅作为兼容回退（用铁砧把原版忠诚书打上去的老做法仍然生效）。 */
    public static final ResourceKey<Enchantment> VANILLA_LOYALTY = key("minecraft", "loyalty");
    /** 射程：投掷初速 +7%/级。 */
    public static final ResourceKey<Enchantment> REACH = key(MagneticWeapons.MODID, "reach");
    /** 冲击：重锤投掷伤害 +10%/级。 */
    public static final ResourceKey<Enchantment> IMPACT = key(MagneticWeapons.MODID, "impact");
    /** 汲取：破坏方块时 3%/级 概率回复 1 点耐久。 */
    public static final ResourceKey<Enchantment> SIPHON = key(MagneticWeapons.MODID, "siphon");
    /** 精锐：投出的锹可破坏镐类方块，受挖掘等级限制。 */
    public static final ResourceKey<Enchantment> PRECISION = key(MagneticWeapons.MODID, "precision");
    /** 丰收：破坏掉的方块直接进入玩家背包（背包满则掉在原地）。 */
    public static final ResourceKey<Enchantment> HARVESTER = key(MagneticWeapons.MODID, "harvester");

    private static ResourceKey<Enchantment> key(String namespace, String path) {
        return ResourceKey.create(Registries.ENCHANTMENT, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static int level(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        return rawLevel(stack, enchantment);
    }

    private static int rawLevel(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> holder : enchantments.keySet()) {
            if (holder.unwrapKey().map(enchantment::equals).orElse(false)) {
                return enchantments.getLevel(holder);
            }
        }
        return 0;
    }

    public static boolean has(ItemStack stack, ResourceKey<Enchantment> enchantment) {
        return level(stack, enchantment) > 0;
    }

    private Enchants() {
    }
}
