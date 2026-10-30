package com.homemine.themagnetweapons.item;

import com.homemine.themagnetweapons.tool.TierDef;
import com.homemine.themagnetweapons.tool.ToolKind;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * 磁性重锤「沉重核心」：单档，伤害 11.0、攻速 -3.4，
 * 手持 smash 完全沿用原版 {@link MaceItem} 逻辑。
 */
public class MagneticMaceToolItem extends MaceItem implements MagneticToolItem {
    public static final float ATTACK_DAMAGE = 11.0f;
    public static final float ATTACK_SPEED = -3.4f;

    public MagneticMaceToolItem(Item.Properties properties) {
        super(properties.attributes(MagneticToolUse.attributes(TierDef.NETHERITE.tier(),
                ToolKind.MACE, ATTACK_DAMAGE)));
    }

    @Override
    public ToolKind kind() {
        return ToolKind.MACE;
    }

    @Override
    public TierDef tierDef() {
        return TierDef.NETHERITE;
    }

    @Override
    public float attackDamage() {
        return ATTACK_DAMAGE;
    }

    @Override
    public int zoneHalf() {
        return 3;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        return MagneticToolUse.use(level, player, hand);
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return MagneticToolUse.animation();
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return MagneticToolUse.USE_DURATION;
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity entity, int timeLeft) {
        MagneticToolUse.release(stack, level, entity, timeLeft, this);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        MagneticToolUse.appendTooltip(stack, tooltip);
    }
}
