package com.homemine.themagnetweapons.item;

import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import com.homemine.themagnetweapons.server.PickaxeTracker;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 投掷后的占位符：
 * 右击召回飞出的工具；武器与玩家不同维度时磁性链接失效。
 */
public class ToolPlaceholderItem extends Item {
    public ToolPlaceholderItem(Item.Properties properties) {
        super(properties);
    }

    /** 显示投出武器的名字（含铁砧改名的自定义名称）。 */
    @Override
    public Component getName(ItemStack stack) {
        ThrownToolData data = stack.get(ModComponents.THROWN_TOOL.get());
        return data == null ? super.getName(stack) : data.tool().getHoverName();
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        ThrownToolData data = stack.get(ModComponents.THROWN_TOOL.get());
        if (data == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        ThrownMagneticToolEntity thrown = PickaxeTracker.getTool(data.entityId());
        if (thrown == null) {
            // 实体还在未加载的区块里，链接尚未失效，继续等待。
            return InteractionResultHolder.pass(stack);
        }
        if (thrown.level() != level) {
            // 武器与玩家不在同一维度：磁性链接失效。
            thrown.breakLink(player);
        } else {
            thrown.startRecall();
        }
        return InteractionResultHolder.success(stack);
    }
}
