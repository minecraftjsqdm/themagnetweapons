package com.homemine.themagnetweapons;

import com.homemine.themagnetweapons.item.MagneticToolItem;
import com.homemine.themagnetweapons.server.DashController;
import com.homemine.themagnetweapons.server.MagnetFieldManager;
import com.homemine.themagnetweapons.server.PickaxeTracker;
import com.homemine.themagnetweapons.server.StackingProgress;
import com.homemine.themagnetweapons.server.ZoneManager;
import com.homemine.themagnetweapons.tool.ToolKind;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class MagneticWeaponsEvents {
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel) {
            PickaxeTracker.onEntityJoin(event.getEntity());
        }
    }

    /** 每破坏一个方块有 30% 概率扣 1 点耐久；镐 / 斧 / 锹适用。 */
    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        LevelAccessor level = event.getLevel();
        if (level instanceof ServerLevel serverLevel) {
            StackingProgress.onBlockBroken(serverLevel, event.getPos());
        }
        Player player = event.getPlayer();
        if (player == null || player.level().isClientSide) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof MagneticToolItem tool)) {
            return;
        }
        ToolKind kind = tool.kind();
        if (kind != ToolKind.PICKAXE && kind != ToolKind.AXE && kind != ToolKind.SHOVEL) {
            return;
        }
        if (maybeSiphon(stack, player)) {
            return;
        }
        if (player.getRandom().nextFloat() < 0.3f) {
            stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(InteractionHand.MAIN_HAND));
        }
    }

    /** 剑每命中一个实体扣 1 点耐久。 */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || event.isCanceled()) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.getItem() instanceof MagneticToolItem tool && tool.kind() == ToolKind.SWORD) {
            stack.hurtAndBreak(1, player, LivingEntity.getSlotForHand(InteractionHand.MAIN_HAND));
        }
    }

    /** 汲取：破坏方块时按 3%/级 的概率回复 1 点耐久。返回是否触发。 */
    private static boolean maybeSiphon(ItemStack stack, Player player) {
        int level = Enchants.level(stack, Enchants.SIPHON);
        if (level <= 0 || stack.getDamageValue() <= 0 || player.getRandom().nextFloat() >= 0.03f * level) {
            return false;
        }
        stack.setDamageValue(stack.getDamageValue() - 1);
        return true;
    }

    /** 必须晚于其它模组的同类监听，才能覆写冲刺的移动向量并维护不稳定区域。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onServerTick(ServerTickEvent.Post event) {
        // 蓄力时移速 -20%，停止蓄力（含切物品、被打断）立即恢复。
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (player.isUsingItem() && player.getUseItem().getItem() instanceof MagneticToolItem) {
                com.homemine.themagnetweapons.item.MagneticToolUse.applyChargeSlowdown(player);
            } else {
                com.homemine.themagnetweapons.item.MagneticToolUse.clearChargeSlowdown(player);
            }
        }
        PickaxeTracker.tick(event.getServer());
        StackingProgress.tick(event.getServer());
        ZoneManager.tick(event.getServer());
        DashController.tick(event.getServer());
        MagnetFieldManager.tick(event.getServer());
    }

    /** 不稳定区域内挖掘速度加成。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        ItemStack tool = event.getEntity().getMainHandItem();
        float bonus = ZoneManager.bonusAt(level, event.getPosition().orElse(event.getEntity().blockPosition()),
                tool, event.getState());
        if (bonus <= 0.0f) {
            return;
        }
        float factor = Math.min(bonus, 0.95f);
        event.setNewSpeed(event.getNewSpeed() / (1.0f - factor));
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            DashController.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel serverLevel) {
            StackingProgress.clearLevel(serverLevel.dimension());
            ZoneManager.clearLevel(serverLevel.dimension());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        StackingProgress.clearAll();
        PickaxeTracker.clearAll();
        ZoneManager.clearAll();
        DashController.clearAll();
        MagnetFieldManager.clearAll();
    }

    private MagneticWeaponsEvents() {
    }
}
