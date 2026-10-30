package com.homemine.themagnetweapons.server;

import com.homemine.themagnetweapons.Enchants;
import com.homemine.themagnetweapons.ModComponents;
import com.homemine.themagnetweapons.component.ThrownToolData;
import com.homemine.themagnetweapons.item.ToolPlaceholderItem;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 冲刺 / 滑模：
 * <ul>
 *   <li>手持占位符左键可在「冲刺中」与「停下」之间切换；</li>
 *   <li>不再因为站在地面上（verticalCollision）被打断；无蛄蛹者只会在撞墙或长时间没有位移时停；</li>
 *   <li>蛄蛹者：撞墙后继续贴面滑行，只有在长时间无法靠近矿镐时才停。</li>
 * </ul>
 */
public final class DashController {
    private static final double ARRIVE_DISTANCE = 2.0;
    private static final double MIN_SPEED = 0.35;
    private static final double MAX_SPEED = 1.4;
    private static final int STILL_TICKS_WITHOUT_WRIGGLER = 8;
    private static final int STILL_TICKS_WITH_WRIGGLER = 40;
    private static final Map<UUID, Dash> ACTIVE = new ConcurrentHashMap<>();

    public static void toggle(ServerPlayer player) {
        if (ACTIVE.remove(player.getUUID()) != null) {
            stop(player);
            return;
        }
        ItemStack placeholder = heldPlaceholder(player);
        if (placeholder == null) {
            return;
        }
        ThrownToolData data = placeholder.get(ModComponents.THROWN_TOOL.get());
        if (data == null || PickaxeTracker.getTool(data.entityId()) == null) {
            return;
        }
        boolean wriggler = Enchants.has(data.tool(), Enchants.WRIGGLER);
        ACTIVE.put(player.getUUID(), new Dash(data.entityId(), wriggler, player.position()));
        player.setNoGravity(true);
        player.resetFallDistance();
        player.hurtMarked = true;
    }

    public static void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Dash dash = ACTIVE.get(player.getUUID());
            if (dash == null) {
                continue;
            }
            ItemStack placeholder = heldPlaceholder(player);
            ThrownToolData data = placeholder == null
                    ? null : placeholder.get(ModComponents.THROWN_TOOL.get());
            if (data == null || !data.entityId().equals(dash.targetId)) {
                stop(player);
                continue;
            }
            Entity target = PickaxeTracker.getTool(dash.targetId);
            if (target == null || target.isRemoved() || !target.isAlive()) {
                stop(player);
                continue;
            }
            Vec3 to = target.position().subtract(player.position());
            double dist = to.length();
            if (dist < ARRIVE_DISTANCE) {
                stop(player);
                continue;
            }
            if (player.position().distanceToSqr(dash.lastPos) < 1.0E-4) {
                dash.stillTicks++;
                int limit = dash.wriggler ? STILL_TICKS_WITH_WRIGGLER : STILL_TICKS_WITHOUT_WRIGGLER;
                if (dash.stillTicks > limit) {
                    stop(player);
                    continue;
                }
            } else {
                dash.stillTicks = 0;
            }
            dash.lastPos = player.position();
            // 站在地面上（verticalCollision）不算被打断；撞墙才停。
            // 有蛄蛹者时连撞墙也不停，靠上面的无位移超时兜底。
            if (!dash.wriggler && player.horizontalCollision) {
                stop(player);
                continue;
            }
            double speed = Math.min(MAX_SPEED, Math.max(MIN_SPEED, dist * 0.5));
            player.setNoGravity(true);
            player.resetFallDistance();
            player.setDeltaMovement(to.normalize().scale(speed));
            player.hurtMarked = true;
        }
    }

    public static void stop(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
        player.setDeltaMovement(Vec3.ZERO);
        player.hurtMarked = true;
    }

    public static void onLogout(ServerPlayer player) {
        ACTIVE.remove(player.getUUID());
    }

    public static void clearAll() {
        ACTIVE.clear();
    }

    @Nullable
    private static ItemStack heldPlaceholder(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof ToolPlaceholderItem) {
                return stack;
            }
        }
        return null;
    }

    private static final class Dash {
        private final UUID targetId;
        private final boolean wriggler;
        private int stillTicks;
        private Vec3 lastPos;

        private Dash(UUID targetId, boolean wriggler, Vec3 lastPos) {
            this.targetId = targetId;
            this.wriggler = wriggler;
            this.lastPos = lastPos;
        }
    }

    private DashController() {
    }
}
