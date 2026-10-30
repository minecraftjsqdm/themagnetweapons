package com.homemine.themagnetweapons.server;

import com.homemine.themagnetweapons.Enchants;
import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import com.homemine.themagnetweapons.item.ToolPlaceholderItem;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 磁性链接失效后的处理：
 * 没有忠诚附魔时占位符直接消失；
 * 附魔忠诚时立即归还，并按等级额外损失 80 / 60 / 35 点耐久。
 */
public final class LoyaltyHandler {
    private static final int[] DAMAGE_COST = {80, 60, 35};

    public static void onLinkBroken(ThrownMagneticToolEntity tool, Player player) {
        if (tool.level().isClientSide) {
            return;
        }
        Inventory inventory = player.getInventory();
        int slot = findPlaceholder(inventory, tool);
        if (slot < 0) {
            return;
        }
        int level = Math.max(Enchants.level(tool.getPickaxeStack(), Enchants.LOYALTY),
                Enchants.level(tool.getPickaxeStack(), Enchants.VANILLA_LOYALTY));
        if (level <= 0) {
            inventory.setItem(slot, ItemStack.EMPTY);
            return;
        }
        ItemStack returned = tool.getPickaxeStack().copy();
        int damage = returned.getDamageValue() + damageCost(level);
        if (returned.isDamageableItem() && damage >= returned.getMaxDamage()) {
            inventory.setItem(slot, ItemStack.EMPTY);
            player.level().playSound(null, player.blockPosition(),
                    SoundEvents.ITEM_BREAK, SoundSource.PLAYERS, 1.0f, 1.0f);
            return;
        }
        if (returned.isDamageableItem()) {
            returned.setDamageValue(damage);
        }
        inventory.setItem(slot, returned);
        player.level().playSound(null, player.blockPosition(),
                SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    /** 忠诚等级对应的耐久损失。 */
    public static int damageCost(int level) {
        return DAMAGE_COST[Math.min(level, DAMAGE_COST.length) - 1];
    }

    /** 找出与该投掷实体绑定的占位符槽位，找不到返回 -1。 */
    public static int findPlaceholder(Inventory inventory, ThrownMagneticToolEntity tool) {
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (!(slot.getItem() instanceof ToolPlaceholderItem)) {
                continue;
            }
            ThrownToolData data = slot.get(ModComponents.THROWN_TOOL.get());
            if (data != null && data.entityId().equals(tool.getUUID())) {
                return i;
            }
        }
        return -1;
    }

    private LoyaltyHandler() {
    }
}
