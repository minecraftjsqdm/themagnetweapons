package com.homemine.themagnetweapons.tool;

import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;

/**
 * 磁性武器的材质档位。D 值为设计文档 §3.1 的基础攻击伤害参数，区域半边长来自 §3.4。
 *
 * <p>末地陨铁 / 嫦娥玉石 / 末世水晶三档的 {@link Tier} 定义在可选模组 oremygod 里，
 * 这里通过反射读取：oremygod 未安装时这三档标记为不可用（{@link #available()} 为 false），
 * 物品仍然注册但不会出现在创造栏，配方也会被数据条件跳过。
 */
public enum TierDef {
    IRON("iron", Tiers.IRON, 7.5f, 1, 40),
    GOLD("golden", Tiers.GOLD, 6.5f, 1, 80),
    DIAMOND("diamond", Tiers.DIAMOND, 9.5f, 2, 50),
    NETHERITE("netherite", Tiers.NETHERITE, 11.5f, 3, 60),
    END_METEORITE("end_meteorite", 17.5f, 4, 70,
            "com.homemine.oremygod.item.EndMeteoriteTier", "END_METEORITE"),
    CHANGE_JADE("change_jade", 32.5f, 4, 80,
            "com.homemine.oremygod.item.ChangeJadeTier", "CHANGE_JADE"),
    FINAL_CRYSTAL("final_crystal", 49.5f, 4, 85,
            "com.homemine.oremygod.item.FinalCrystalTier", "FINAL_CRYSTAL");

    private final String id;
    private final Tier tier;
    private final float damage;
    private final int zoneHalf;
    private final int shovelBlocks;
    private final boolean available;

    TierDef(String id, Tier tier, float damage, int zoneHalf, int shovelBlocks) {
        this.id = id;
        this.tier = tier;
        this.damage = damage;
        this.zoneHalf = zoneHalf;
        this.shovelBlocks = shovelBlocks;
        this.available = true;
    }

    TierDef(String id, float damage, int zoneHalf, int shovelBlocks, String tierClass, String tierField) {
        this.id = id;
        Tier resolved = resolveTier(tierClass, tierField);
        this.tier = resolved != null ? resolved : Tiers.NETHERITE;
        this.damage = damage;
        this.zoneHalf = zoneHalf;
        this.shovelBlocks = shovelBlocks;
        this.available = resolved != null;
    }

    /** 反射读取 oremygod 的材质等级；类缺失或字段异常时返回 null，代表该档不可用。 */
    private static Tier resolveTier(String tierClass, String tierField) {
        try {
            Object value = Class.forName(tierClass).getField(tierField).get(null);
            return value instanceof Tier tier ? tier : null;
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    public String id() {
        return id;
    }

    public Tier tier() {
        return tier;
    }

    public float damage() {
        return damage;
    }

    public int zoneHalf() {
        return zoneHalf;
    }

    /** 锹满蓄力一次可破坏的方块数（设计 §3.7 第 4 条）。 */
    public int shovelBlocks() {
        return shovelBlocks;
    }

    /** oremygod 三档需要防火。 */
    public boolean fireResistant() {
        return this == END_METEORITE || this == CHANGE_JADE || this == FINAL_CRYSTAL;
    }

    /** 该档位是否可用：前三档恒为 true，后三档取决于 oremygod 是否安装。 */
    public boolean available() {
        return available;
    }
}
