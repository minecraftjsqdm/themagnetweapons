package com.homemine.themagnetweapons.server;

import com.homemine.themagnetweapons.Enchants;
import com.homemine.themagnetweapons.ModComponents;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 不稳定区域的方块破坏进度：每次矿镐插入 / 拔出不稳定的连通区域时，
 * 区域内的方块各累加一份进度，可叠加，累计到 1.0 立即破坏，并正常触发时运 / 精准采集。
 * <p>
 * 裂纹使用独立 breakerId（0x6B...），与基模组 5x5x5 区域的 0x6A... 互不覆盖。
 */
public final class StackingProgress {
    private static final int CRACK_ID_BASE = 0x6B000000;
    private static final int REFRESH_INTERVAL = 40;
    private static final Map<BlockKey, Float> PROGRESS = new HashMap<>();

    /** 稳定模式：附了稳定器且开关打开。 */
    public static boolean isStable(ItemStack stack) {
        return !stack.isEmpty()
                && Enchants.has(stack, Enchants.STABILIZER)
                && stack.getOrDefault(ModComponents.STABLE.get(), false);
    }

    /** 给单个方块累加一份破坏进度，满 1.0 即破坏。 */
    public static boolean apply(ServerLevel level, BlockPos pos, BlockState state, float bonus,
                                Entity owner, ItemStack tool) {
        return apply(level, pos, state, bonus, owner, tool, true);
    }

    /**
     * 给单个方块累加一份破坏进度。
     *
     * @param allowBreak 为 false 时即使满 1.0 也卡在 99%（爆发破坏上限用）
     * @return 进度是否已满（allowBreak 为 false 时表示“本可以破坏”）
     */
    public static boolean apply(ServerLevel level, BlockPos pos, BlockState state, float bonus,
                                Entity owner, ItemStack tool, boolean allowBreak) {
        if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0f) {
            return false;
        }
        BlockKey key = new BlockKey(level.dimension(), pos.immutable());
        float progress = PROGRESS.merge(key, bonus, Float::sum);
        if (progress >= 1.0f) {
            if (!allowBreak) {
                PROGRESS.put(key, 0.99f);
                level.destroyBlockProgress(crackId(pos), pos, 9);
                return true;
            }
            PROGRESS.remove(key);
            breakBlock(level, pos, state, owner, tool);
            return true;
        }
        level.destroyBlockProgress(crackId(pos), pos, (int) (progress * 10.0f));
        return false;
    }

    private static void breakBlock(ServerLevel level, BlockPos pos, BlockState state,
                                   Entity owner, ItemStack tool) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        List<ItemStack> drops = Block.getDrops(state, level, pos, blockEntity, owner, tool);
        level.destroyBlockProgress(crackId(pos), pos, -1);
        // 走 destroyBlock 以便链锯等 BlockEvent.BreakEvent 监听器拿到玩家引用。
        level.destroyBlock(pos, false, owner);
        level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, pos, Block.getId(state));
        if (owner instanceof Player player && Enchants.has(tool, Enchants.HARVESTER)) {
            for (ItemStack drop : drops) {
                if (!player.getInventory().add(drop)) {
                    player.drop(drop, false);
                }
            }
        } else {
            for (ItemStack drop : drops) {
                Block.popResource(level, pos, drop);
            }
        }
    }

    /** 区域过期时把范围内的未完成进度与裂纹清零。 */
    public static void clearArea(ServerLevel level, BlockPos min, BlockPos max) {
        ResourceKey<Level> dimension = level.dimension();
        Iterator<Map.Entry<BlockKey, Float>> iterator = PROGRESS.entrySet().iterator();
        while (iterator.hasNext()) {
            BlockKey key = iterator.next().getKey();
            BlockPos pos = key.pos();
            if (!key.dimension().equals(dimension)
                    || pos.getX() < min.getX() || pos.getX() > max.getX()
                    || pos.getY() < min.getY() || pos.getY() > max.getY()
                    || pos.getZ() < min.getZ() || pos.getZ() > max.getZ()) {
                continue;
            }
            level.destroyBlockProgress(crackId(pos), pos, -1);
            iterator.remove();
        }
    }

    public static void tick(MinecraftServer server) {
        if (PROGRESS.isEmpty()) {
            return;
        }
        boolean refresh = server.getTickCount() % REFRESH_INTERVAL == 0;
        Iterator<Map.Entry<BlockKey, Float>> iterator = PROGRESS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Float> entry = iterator.next();
            BlockKey key = entry.getKey();
            ServerLevel level = server.getLevel(key.dimension());
            if (level == null || !level.isLoaded(key.pos())) {
                iterator.remove();
                continue;
            }
            BlockState state = level.getBlockState(key.pos());
            if (state.isAir() || state.getDestroySpeed(level, key.pos()) < 0.0f) {
                // 方块已被玩家挖掉，清掉裂纹。
                level.destroyBlockProgress(crackId(key.pos()), key.pos(), -1);
                iterator.remove();
                continue;
            }
            if (refresh) {
                level.destroyBlockProgress(crackId(key.pos()), key.pos(), (int) (entry.getValue() * 10.0f));
            }
        }
    }

    public static void onBlockBroken(ServerLevel level, BlockPos pos) {
        BlockKey key = new BlockKey(level.dimension(), pos.immutable());
        if (PROGRESS.remove(key) != null) {
            level.destroyBlockProgress(crackId(pos), pos, -1);
        }
    }

    public static void clearLevel(ResourceKey<Level> dimension) {
        PROGRESS.keySet().removeIf(key -> key.dimension().equals(dimension));
    }

    public static void clearAll() {
        PROGRESS.clear();
    }

    private static int crackId(BlockPos pos) {
        return CRACK_ID_BASE | pos.hashCode() & 0xFFFFFF;
    }

    private record BlockKey(ResourceKey<Level> dimension, BlockPos pos) {
    }

    private StackingProgress() {
    }
}
