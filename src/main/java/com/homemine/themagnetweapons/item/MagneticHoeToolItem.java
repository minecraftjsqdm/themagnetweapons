package com.homemine.themagnetweapons.item;

import com.homemine.themagnetweapons.component.HoeContents;
import com.homemine.themagnetweapons.tool.TierDef;
import com.homemine.themagnetweapons.tool.ToolKind;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

public class MagneticHoeToolItem extends HoeItem implements MagneticToolItem {
    private final TierDef tierDef;

    public MagneticHoeToolItem(TierDef tierDef, Item.Properties properties) {
        super(tierDef.tier(), properties.attributes(
                MagneticToolUse.attributes(tierDef.tier(), ToolKind.HOE,
                        tierDef.damage() + ToolKind.HOE.damageOffset())));
        this.tierDef = tierDef;
    }

    @Override
    public ToolKind kind() {
        return ToolKind.HOE;
    }

    @Override
    public TierDef tierDef() {
        return tierDef;
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
        HoeContents contents = stack.getOrDefault(
                com.homemine.themagnetweapons.ModComponents.HOE_CONTENTS.get(), HoeContents.EMPTY);
        tooltip.add(Component.translatable("tooltip.themagnetweapons.hoe_contents", contents.count())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.themagnetweapons.hoe_hint")
                .withStyle(ChatFormatting.DARK_GRAY));
        MagneticToolUse.appendTooltip(stack, tooltip);
    }
}
