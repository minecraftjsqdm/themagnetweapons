package com.homemine.themagnetweapons.tool;

/**
 * 磁性武器的工具种类。所有数值来自设计文档 §3.3。
 */
public enum ToolKind {
    PICKAXE("pickaxe", 0, -3.0f, 1.2f, 1.3f, 0.03, 10, 1.0f, 0),
    AXE("axe", 2, -3.0f, 1.2f, 1.3f, 0.03, 10, 1.0f, -1),
    SWORD("sword", -2, -2.4f, 1.45f, 1.5f, 0.03, 8, 1.15f, -2),
    SHOVEL("shovel", -3, -3.0f, 1.2f, 1.3f, 0.03, 10, 0.9f, 1),
    HOE("hoe", -6, -2.8f, 1.2f, 1.3f, 0.03, 12, 0.75f, 1),
    MACE("mace", 0, -3.4f, 0.9f, 0.9f, 0.08, 20, 1.3f, 0);

    private final String id;
    private final int damageOffset;
    private final float attackSpeed;
    private final float throwBase;
    private final float throwPower;
    private final double gravity;
    private final int cooldown;
    private final float directMultiplier;
    private final int zoneDelta;

    ToolKind(String id, int damageOffset, float attackSpeed, float throwBase, float throwPower,
             double gravity, int cooldown, float directMultiplier, int zoneDelta) {
        this.id = id;
        this.damageOffset = damageOffset;
        this.attackSpeed = attackSpeed;
        this.throwBase = throwBase;
        this.throwPower = throwPower;
        this.gravity = gravity;
        this.cooldown = cooldown;
        this.directMultiplier = directMultiplier;
        this.zoneDelta = zoneDelta;
    }

    public String id() {
        return id;
    }

    public int damageOffset() {
        return damageOffset;
    }

    public float attackSpeed() {
        return attackSpeed;
    }

    public float throwBase() {
        return throwBase;
    }

    public float throwPower() {
        return throwPower;
    }

    /** 重锤为 0.08，其余 0.03。 */
    public double gravity() {
        return gravity;
    }

    public int cooldown() {
        return cooldown;
    }

    public float directMultiplier() {
        return directMultiplier;
    }

    /** 投出后是否吸附在方块上（镐 / 斧 / 锤）。 */
    public boolean sticksToBlocks() {
        return this == PICKAXE || this == AXE || this == MACE;
    }

    /** 不稳定区域半边长；锹固定 2（半径 2.5 取整），锤固定 3（7³）。 */
    public int zoneHalf(int tierHalf) {
        return switch (this) {
            case SHOVEL -> 2;
            case MACE -> 3;
            default -> Math.max(1, Math.min(5, tierHalf + zoneDelta));
        };
    }

    /** 会建立不稳定区域的类型（锄不建区，见设计 §3.8）。 */
    public boolean buildsZone() {
        return this == PICKAXE || this == AXE || this == MACE || this == SHOVEL;
    }

    /** 区域爆发时是否会破坏方块（锤 / 锹的区域只参与伤害）。 */
    public boolean destroysBlocks() {
        return this == PICKAXE || this == AXE;
    }

    /** 落地后是否进入贴地滑行（剑 / 锄）。 */
    public boolean slides() {
        return this == SWORD || this == HOE;
    }
}
