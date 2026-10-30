package com.homemine.themagnetweapons.client;

import com.homemine.themagnetweapons.entity.ThrownMagneticToolEntity;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 收回动画状态机（仅客户端）：
 * 前摇 5 tick 内右手前抬 90°、再向侧后方挥 160°，躯体向右后仰；
 * 回飞期间保持该姿势；武器回到手上后再向后仰振动一下并复位。
 * 状态来源是投掷实体同步的回收阶段，因此自己和其他玩家的收回都能看到。
 */
public final class RecallAnimation {
    private static final int RECOVER_TICKS = 10;
    /** 右手前抬 90°。 */
    private static final float ARM_X = (float) (-Math.PI / 2.0);
    /** 向侧后方挥 160°。 */
    private static final float ARM_Y = (float) Math.toRadians(-160.0);
    /** 躯体稍向右后倾。 */
    private static final float BODY_X = -0.14f;
    private static final float BODY_Z = 0.10f;

    private static final Map<UUID, Anim> ACTIVE = new HashMap<>();

    public record Pose(float armX, float armY, float bodyX, float bodyZ) {
    }

    private static final class Anim {
        int entityId;
        /** 1 = 前摇，2 = 回飞，3 = 后摇。 */
        int phase;
        int ticks;
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            ACTIVE.clear();
            return;
        }
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ThrownMagneticToolEntity tool) || tool.getRecallPhase() <= 0) {
                continue;
            }
            if (!(tool.getOwner() instanceof Player owner)) {
                continue;
            }
            Anim anim = ACTIVE.get(owner.getUUID());
            if (anim == null) {
                anim = new Anim();
                ACTIVE.put(owner.getUUID(), anim);
            }
            if (anim.entityId != tool.getId()) {
                anim.entityId = tool.getId();
                anim.ticks = 0;
                anim.phase = 0;
            }
            anim.phase = Math.max(anim.phase, tool.getRecallPhase());
        }
        Iterator<Map.Entry<UUID, Anim>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Anim> entry = iterator.next();
            Anim anim = entry.getValue();
            Player player = level.getPlayerByUUID(entry.getKey());
            if (player == null || !player.isAlive()) {
                iterator.remove();
                continue;
            }
            if (anim.phase <= 2) {
                anim.ticks++;
                Entity entity = level.getEntity(anim.entityId);
                if (!(entity instanceof ThrownMagneticToolEntity) || entity.isRemoved()) {
                    anim.phase = 3;
                    anim.ticks = 0;
                }
                continue;
            }
            anim.ticks++;
            if (anim.ticks >= RECOVER_TICKS) {
                iterator.remove();
            }
        }
    }

    /** 该玩家当前应收到的姿势；没有动画返回 null。 */
    @Nullable
    public static Pose poseFor(UUID playerId) {
        Anim anim = ACTIVE.get(playerId);
        if (anim == null) {
            return null;
        }
        if (anim.phase == 1) {
            float t = Mth.clamp(anim.ticks / (float) ThrownMagneticToolEntity.RECALL_WINDUP_TICKS, 0.0f, 1.0f);
            float raise = Mth.clamp(t / 0.45f, 0.0f, 1.0f);
            float sweep = Mth.clamp((t - 0.45f) / 0.55f, 0.0f, 1.0f);
            return new Pose(ARM_X * raise, ARM_Y * sweep, BODY_X * raise, BODY_Z * raise);
        }
        if (anim.phase == 2) {
            return new Pose(ARM_X, ARM_Y, BODY_X, BODY_Z);
        }
        float progress = Mth.clamp(anim.ticks / (float) RECOVER_TICKS, 0.0f, 1.0f);
        float extra = (float) Math.sin(Math.PI * progress) * 0.20f;
        float shake = (float) Math.sin(anim.ticks * 2.4f) * 0.06f * (1.0f - progress);
        return new Pose(ARM_X, ARM_Y, BODY_X - extra + shake, BODY_Z);
    }

    public static void clear() {
        ACTIVE.clear();
    }

    private RecallAnimation() {
    }
}
