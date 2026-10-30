package com.homemine.themagnetweapons.item;

import com.homemine.themagnetweapons.tool.TierDef;
import com.homemine.themagnetweapons.tool.ToolKind;
import net.minecraft.world.item.Tier;

/**
 * 所有磁性工具共用的识别接口。物品本身仍继承各自的原版工具类。
 */
public interface MagneticToolItem {
    ToolKind kind();

    TierDef tierDef();

    default Tier tier() {
        return tierDef().tier();
    }

    /** 基础攻击伤害参数（写入属性修饰符）。 */
    default float attackDamage() {
        return tierDef().damage() + kind().damageOffset();
    }

    /** 不稳定区域半边长。 */
    default int zoneHalf() {
        return kind().zoneHalf(tierDef().zoneHalf());
    }
}
