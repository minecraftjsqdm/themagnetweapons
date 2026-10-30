package com.homemine.themagnetweapons.server;

import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import com.homemine.themagnetweapons.item.ToolPlaceholderItem;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 跟踪飞行中的投掷矿镐实体。
 * <p>
 * 基模组不保存命中方块的坐标，也不在实体消失时通知外部，因此这里保留强引用，用来做五件事：
 * 1. 钉住后每 tick 冻结位置与速度，避免客户端仍在滑行导致「碰到方块回弹」；
 * 2. 检测插入 / 拔出跳变，驱动不稳定区域的爆发；
 * 3. 检测实体被移除 / 掉出世界底部，驱动忠诚归还；
 * 4. 实体 owner 随存档丢失时，按玩家背包里的占位符找回；
 * 5. 独立计时：出手满 30 s 强制回收、链接失效满 30 s 开放拾取。
 * <p>
 * 计时与回收不依赖实体自己的 {@code tick()}：武器飞出玩家模拟范围后实体不再被 tick，
 * 旧实现因此永远不触发强制回收，这里改由服务端每 tick 的强引用循环驱动。
 */
public final class PickaxeTracker {
    private static final Map<UUID, ThrownMagneticToolEntity> TRACKED = new ConcurrentHashMap<>();
    private static final Map<UUID, Track> TRACKS = new ConcurrentHashMap<>();
    private static final Set<UUID> STUCK = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Vec3> STUCK_POS = new ConcurrentHashMap<>();
    /** 已在未加载区块里被强制回收的实体：重新加载时直接销毁，避免武器复制。 */
    private static final Set<UUID> ABANDONED = ConcurrentHashMap.newKeySet();
    /** 回收阶段留给实体自己飞回来的时间（tick）。 */
    private static final int RECALL_GRACE = 40;
    /**
     * 飞行期间的区块票据。距离 2 对应实体 tick 等级，
     * 武器飞出玩家模拟距离后仍能继续 tick 并完成返回飞行。
     */
    private static final TicketType<ChunkPos> FLIGHT_TICKET =
            TicketType.create("themagnetweapons_flight", Comparator.comparingLong(ChunkPos::toLong));

    private static final class Track {
        int age;
        int recallAge = -1;
        @Nullable
        ChunkPos ticketPos;
        @Nullable
        ServerLevel ticketLevel;

        Track(int age) {
            this.age = age;
        }
    }

    public static void onEntityJoin(Entity entity) {
        if (entity instanceof ThrownMagneticToolEntity pickaxe && !pickaxe.level().isClientSide) {
            if (ABANDONED.remove(pickaxe.getUUID())) {
                pickaxe.discard();
                return;
            }
            TRACKED.put(pickaxe.getUUID(), pickaxe);
            Track track = TRACKS.computeIfAbsent(pickaxe.getUUID(),
                    key -> new Track(Math.max(0, pickaxe.getLifeTicks())));
            track.age = Math.max(track.age, pickaxe.getLifeTicks());
            restoreOwner(pickaxe);
        }
    }

    @Nullable
    public static ThrownMagneticToolEntity get(UUID id) {
        return TRACKED.get(id);
    }

    /** 取出本模组通用投掷物。 */
    @Nullable
    public static ThrownMagneticToolEntity getTool(UUID id) {
        return TRACKED.get(id);
    }

    public static void tick(MinecraftServer server) {
        if (TRACKED.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, ThrownMagneticToolEntity>> iterator = TRACKED.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ThrownMagneticToolEntity> entry = iterator.next();
            UUID id = entry.getKey();
            ThrownMagneticToolEntity pickaxe = entry.getValue();
            Track track = TRACKS.computeIfAbsent(id,
                    key -> new Track(Math.max(0, pickaxe.getLifeTicks())));

            boolean removed = pickaxe.isRemoved();
            boolean unloaded = removed && isUnloaded(pickaxe.getRemovalReason());

            if (removed && !unloaded) {
                if (STUCK.remove(id)) {
                    ZoneManager.onExtract(pickaxe);
                }
                STUCK_POS.remove(id);
                releaseTicket(track);
                TRACKS.remove(id);
                if (isLost(pickaxe.getRemovalReason())) {
                    pickaxe.breakLink();
                }
                iterator.remove();
                continue;
            }

            // 未加载区块中的实体不会被 tick，这里自己累加年龄，保持 30 s 计时准确。
            track.age = Math.max(track.age + 1, pickaxe.getLifeTicks());
            pickaxe.setLifeTicks(track.age);

            if (unloaded) {
                releaseTicket(track);
            } else {
                restoreOwner(pickaxe);
                keepLoaded(pickaxe, track);
            }

            if (applyLinkRules(pickaxe, track, unloaded)) {
                iterator.remove();
                continue;
            }
            if (unloaded) {
                continue;
            }

            // 早于原版 -64 的自毁阈值：先归还，再销毁实体。
            if (pickaxe.getY() < pickaxe.level().getMinBuildHeight() - 4) {
                if (STUCK.remove(id)) {
                    ZoneManager.onExtract(pickaxe);
                }
                STUCK_POS.remove(id);
                pickaxe.breakLink();
                pickaxe.discard();
                releaseTicket(track);
                TRACKS.remove(id);
                iterator.remove();
                continue;
            }
            if (pickaxe.isStuck()) {
                if (STUCK.add(id)) {
                    STUCK_POS.put(id, pickaxe.position());
                    ZoneManager.onInsert(pickaxe);
                }
                freeze(pickaxe, STUCK_POS.get(id));
            } else if (STUCK.remove(id)) {
                STUCK_POS.remove(id);
                ZoneManager.onExtract(pickaxe);
            }
        }
    }

    /**
     * 磁性链接的每 tick 维护。返回 true 表示实体已经被强制回收、可以停止跟踪。
     */
    private static boolean applyLinkRules(ThrownMagneticToolEntity pickaxe, Track track, boolean unloaded) {
        if (pickaxe.isLinkBroken()) {
            if (track.age >= ThrownMagneticToolEntity.LINK_TICKS) {
                pickaxe.setPickable(true);
                // 链接已断、也到了可拾取时间：不再需要占着区块。
                releaseTicket(track);
            }
            return false;
        }
        if (track.age >= ThrownMagneticToolEntity.LINK_TICKS) {
            if (!pickaxe.isCustomReturning()) {
                pickaxe.startRecall();
                track.recallAge = track.age;
            } else if (track.recallAge < 0) {
                track.recallAge = track.age;
            }
            if (track.recallAge >= 0 && (unloaded || track.age - track.recallAge >= RECALL_GRACE)) {
                forceReturn(pickaxe, track);
                return true;
            }
            return false;
        }
        if (!(pickaxe.getOwner() instanceof net.minecraft.world.entity.player.Player player)
                || !player.isAlive() || player.level() != pickaxe.level()) {
            pickaxe.breakLink();
        }
        return false;
    }

    /** 强制回收：主人还在就写回占位符槽，否则直接销毁实体。 */
    private static void forceReturn(ThrownMagneticToolEntity pickaxe, Track track) {
        UUID id = pickaxe.getUUID();
        boolean wasUnloaded = pickaxe.isRemoved();
        pickaxe.forceReturnToOwner();
        if (wasUnloaded) {
            // 实体已从世界移除但存档里还留着，重新加载时直接销毁。
            ABANDONED.add(id);
        }
        releaseTicket(track);
        TRACKS.remove(id);
        TRACKED.remove(id);
    }

    /** 飞行中的武器所在区块挂票据，避免飞出模拟距离后实体被卸载而停止 tick。 */
    private static void keepLoaded(ThrownMagneticToolEntity pickaxe, Track track) {
        if (!(pickaxe.level() instanceof ServerLevel level)) {
            return;
        }
        ChunkPos pos = pickaxe.chunkPosition();
        if (pos.equals(track.ticketPos) && level == track.ticketLevel) {
            return;
        }
        releaseTicket(track);
        level.getChunkSource().addRegionTicket(FLIGHT_TICKET, pos, 2, pos);
        track.ticketPos = pos;
        track.ticketLevel = level;
    }

    private static void releaseTicket(Track track) {
        if (track.ticketPos == null || track.ticketLevel == null) {
            return;
        }
        track.ticketLevel.getChunkSource().removeRegionTicket(FLIGHT_TICKET, track.ticketPos, 2, track.ticketPos);
        track.ticketPos = null;
        track.ticketLevel = null;
    }

    /** 钉住后把服务端实体焊死，客户端因此不会继续滑行 / 回弹。 */
    private static void freeze(ThrownMagneticToolEntity pickaxe, @Nullable Vec3 anchor) {
        pickaxe.setDeltaMovement(Vec3.ZERO);
        pickaxe.setNoGravity(true);
        pickaxe.noPhysics = true;
        pickaxe.hurtMarked = true;
        if (anchor != null && pickaxe.position().distanceToSqr(anchor) > 1.0E-8) {
            pickaxe.setPos(anchor.x, anchor.y, anchor.z);
            pickaxe.hurtMarked = true;
        }
    }

    /**
     * 实体保存数据里不带 owner，重进存档后实体找不到主人、无法回收。
     * 这里按玩家背包里的占位符 UUID 反查并补回。
     */
    private static void restoreOwner(ThrownMagneticToolEntity pickaxe) {
        if (pickaxe.getOwner() != null) {
            return;
        }
        MinecraftServer server = pickaxe.level().getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ownsPlaceholder(player, pickaxe.getUUID())) {
                pickaxe.setOwner(player);
                return;
            }
        }
    }

    private static boolean ownsPlaceholder(ServerPlayer player, UUID entityId) {
        Inventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.getItem() instanceof ToolPlaceholderItem) {
                ThrownToolData data = slot.get(ModComponents.THROWN_TOOL.get());
                if (data != null && data.entityId().equals(entityId)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 区块卸载不是「武器消失」，仍要保留跟踪并继续计时。 */
    private static boolean isUnloaded(@Nullable Entity.RemovalReason reason) {
        return reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER;
    }

    private static boolean isLost(@Nullable Entity.RemovalReason reason) {
        if (reason == null) {
            return false;
        }
        return reason != Entity.RemovalReason.UNLOADED_TO_CHUNK
                && reason != Entity.RemovalReason.UNLOADED_WITH_PLAYER
                && reason != Entity.RemovalReason.CHANGED_DIMENSION;
    }

    public static void clearAll() {
        for (Track track : TRACKS.values()) {
            releaseTicket(track);
        }
        TRACKED.clear();
        TRACKS.clear();
        STUCK.clear();
        STUCK_POS.clear();
        ABANDONED.clear();
    }

    private PickaxeTracker() {
    }
}
