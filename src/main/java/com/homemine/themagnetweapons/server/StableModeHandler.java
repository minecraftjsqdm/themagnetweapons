package com.homemine.themagnetweapons.server;

import com.homemine.themagnetweapons.Enchants;
import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.component.Polarity;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import com.homemine.themagnetweapons.item.MagneticToolItem;
import com.homemine.themagnetweapons.item.MeteoriteMagnetItem;
import com.homemine.themagnetweapons.item.ToolPlaceholderItem;
import com.homemine.themagnetweapons.tool.ToolKind;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * F7：手持陨石磁铁时切换极性，手持磁性矿镐时切换稳定模式，
 * 手持磁性链接（占位符）时切换所链接的投掷武器的稳定模式。
 */
public final class StableModeHandler {
    public static void toggle(ServerPlayer player) {
        if (toggleMagnetPolarity(player)) {
            return;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (isMagneticPickaxe(stack)) {
                toggleStack(player, stack);
                return;
            }
            if (stack.getItem() instanceof ToolPlaceholderItem) {
                toggleLinked(player, stack);
                return;
            }
        }
    }

    /** 翻转指定物品栈的稳定模式并提示。 */
    private static void toggleStack(ServerPlayer player, ItemStack stack) {
        if (!Enchants.has(stack, Enchants.STABILIZER)) {
            player.displayClientMessage(Component.translatable("message.themagnetweapons.stable.missing"), true);
            return;
        }
        boolean stable = !stack.getOrDefault(ModComponents.STABLE.get(), false);
        stack.set(ModComponents.STABLE.get(), stable);
        player.displayClientMessage(Component.translatable(
                stable ? "message.themagnetweapons.stable.on" : "message.themagnetweapons.stable.off"), true);
    }

    /** 手持磁性链接 → 切换该链接对应投掷武器的稳定模式。 */
    private static void toggleLinked(ServerPlayer player, ItemStack link) {
        ThrownToolData data = link.get(ModComponents.THROWN_TOOL.get());
        if (data == null) {
            return;
        }
        ThrownMagneticToolEntity thrown = PickaxeTracker.getTool(data.entityId());
        if (thrown == null) {
            return;
        }
        ItemStack tool = thrown.getPickaxeStack();
        if (!isMagneticPickaxe(tool)) {
            return;
        }
        ItemStack updated = tool.copy();
        if (!Enchants.has(updated, Enchants.STABILIZER)) {
            player.displayClientMessage(Component.translatable("message.themagnetweapons.stable.missing"), true);
            return;
        }
        boolean stable = !updated.getOrDefault(ModComponents.STABLE.get(), false);
        updated.set(ModComponents.STABLE.get(), stable);
        thrown.setPickaxeStack(updated);
        player.displayClientMessage(Component.translatable(
                stable ? "message.themagnetweapons.stable.on" : "message.themagnetweapons.stable.off"), true);
    }

    /** 手持陨石磁铁 → 红/蓝互换。找到并切换返回 true。 */
    private static boolean toggleMagnetPolarity(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof MeteoriteMagnetItem)) {
                continue;
            }
            Polarity next = MeteoriteMagnetItem.polarity(stack).opposite();
            MeteoriteMagnetItem.withPolarity(stack, next);
            player.displayClientMessage(Component.translatable(
                    next == Polarity.RED ? "message.themagnetweapons.polarity.red"
                            : "message.themagnetweapons.polarity.blue"), true);
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.LODESTONE_COMPASS_LOCK, SoundSource.PLAYERS, 0.6f,
                    next == Polarity.RED ? 1.25f : 0.85f);
            return true;
        }
        return false;
    }

    private static boolean isMagneticPickaxe(ItemStack stack) {
        return stack.getItem() instanceof MagneticToolItem tool && tool.kind() == ToolKind.PICKAXE;
    }

    private StableModeHandler() {
    }
}
